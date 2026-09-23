import { timingSafeEqual } from "node:crypto";
import { pool } from "./db.js";

// No notification content or sender metadata is ever stored in the queue.
export const genericPushMessage = token => ({
  token,
  notification:{title:"Lumo",body:"Новое сообщение"},
  android:{priority:"normal"}
});
export const invalidPushTokenCode = code => [
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token"
].includes(code);

export const retrySeconds = attempts => Math.min(300,20*Math.pow(2,Math.min(attempts,4)));

// A scheduled, bounded at-least-once dispatcher. Each row represents exactly
// one message+SESSION; duplicate message retries cannot create duplicate jobs.
// A worker crash after FCM succeeds may cause one repeated generic push.
export async function dispatchPushBatch({db,send,max=12}) {
  const limit=Math.max(1,Math.min(20,Math.floor(max)));
  // Separate, short transactions: workers do not hold row locks across FCM.
  // A two-minute lease prevents two serverless cron instances sending at once.
  const claimed=await db.query(`with due as (
    select id from push_outbox where status='pending'
      and available_at<=now() and (lease_until is null or lease_until<=now())
    order by available_at,id for update skip locked limit $1
  ) update push_outbox o set attempts=o.attempts+1,
      lease_until=now()+interval '2 minutes'
    from due where o.id=due.id
    returning o.id,o.message_id,o.session_token,o.recipient_id,o.attempts,o.created_at`,[limit]);
  let sent=0,dropped=0,retried=0;
  for(const job of claimed.rows) {
    // Re-check ownership and consent for EACH job. If the user logs out,
    // un-registers, reads the message or blocks the sender before dispatch,
    // there is no new push. No message text or FCM token is ever logged.
    const ready=await db.query(`select p.fcm_token,p.token_hash
      from push_outbox o
      join push_devices p on p.session_token=o.session_token and p.user_id=o.recipient_id
      join sessions s on s.token=p.session_token and s.user_id=p.user_id and s.expires_at>now()
      join messages m on m.id=o.message_id and m.recipient_id=o.recipient_id
      where o.id=$1 and o.status='pending' and m.read_at is null
        and not exists (select 1 from user_blocks b where
          (b.blocker_id=m.sender_id and b.blocked_id=m.recipient_id)
          or (b.blocker_id=m.recipient_id and b.blocked_id=m.sender_id))
      limit 1`,[job.id]);
    const expired=Date.now()-new Date(job.created_at).getTime()>10*60*1000;
    if(!ready.rowCount || expired) {
      await db.query("update push_outbox set status='dropped',processed_at=now(),lease_until=null,last_error_code=$2 where id=$1",
        [job.id,expired?"stale":"no_consent"]);
      dropped++;
      continue;
    }
    const {fcm_token,token_hash}=ready.rows[0];
    try {
      await send(genericPushMessage(fcm_token));
      await db.query("update push_outbox set status='sent',processed_at=now(),lease_until=null,last_error_code=null where id=$1",
        [job.id]);
      sent++;
    }catch(error) {
      const code=typeof error?.code==="string" ? error.code : "provider_error";
      if(invalidPushTokenCode(code)) {
        // Never delete another owner's replacement token.
        await db.query("delete from push_devices where session_token=$1 and token_hash=$2",
          [job.session_token,token_hash]);
      }
      if(invalidPushTokenCode(code) || job.attempts>=4) {
        await db.query("update push_outbox set status='dropped',processed_at=now(),lease_until=null,last_error_code=$2 where id=$1",
          [job.id,invalidPushTokenCode(code)?"invalid_token":"send_failed"]);
        dropped++;
      }else{
        await db.query(`update push_outbox set lease_until=null,
          available_at=now()+($2::int * interval '1 second'),
          last_error_code='provider_retry'
          where id=$1`,[job.id,retrySeconds(job.attempts)]);
        retried++;
      }
    }
  }
  // Keep old metadata bounded even when no subsequent messages arrive.
  await db.query("delete from push_outbox where created_at<now()-interval '1 day'");
  return {processed:claimed.rowCount,sent,dropped,retried};
}

let firebaseMessaging;
async function firebaseSend(message) {
  if(!firebaseMessaging) {
    // Privileged SDK is loaded only by an explicitly enabled cron. ADC and
    // project ID must be configured by the operator; no keys enter the repo.
    const [{initializeApp,applicationDefault,getApps},{getMessaging}]=await Promise.all([
      import("firebase-admin/app"),import("firebase-admin/messaging")
    ]);
    const app=getApps().find(a=>a.name==="lumo-push-worker") ||
      initializeApp({
        credential:applicationDefault(),
        projectId:process.env.FIREBASE_PROJECT_ID
      },"lumo-push-worker");
    firebaseMessaging=getMessaging(app);
  }
  await firebaseMessaging.send(message);
}

// This endpoint is NOT enabled just by deploying code. The owner must
// configure an authenticated, HTTPS-only, scheduled invocation separately.
export function registerPushDispatch(app) {
  app.get("/internal/push-dispatch",async(req,res)=>{
    res.set("Cache-Control","private, no-store");
    const secret=process.env.CRON_SECRET || "";
    if(process.env.LUMO_PUSH_DELIVERY_ENABLED!=="true" || !pool ||
       !process.env.FIREBASE_PROJECT_ID || Buffer.byteLength(secret)<32)
      return res.status(404).json({error:"feature_unavailable"});
    const provided=typeof req.headers.authorization==="string" &&
      req.headers.authorization.startsWith("Bearer ") ?
      req.headers.authorization.slice(7):"";
    const a=Buffer.from(provided,"utf8"),b=Buffer.from(secret,"utf8");
    if(a.length!==b.length || !timingSafeEqual(a,b))
      return res.status(401).json({error:"unauthorized"});
    try {
      const result=await dispatchPushBatch({db:pool,send:firebaseSend});
      res.json({ok:true,...result});
    }catch(error) {
      // Never log credentials, push payloads, SQL parameters or provider bodies.
      console.error("Push worker failed",typeof error?.code==="string"?error.code:"unknown");
      res.status(503).json({error:"service_unavailable"});
    }
  });
}
