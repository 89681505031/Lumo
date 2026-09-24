import { timingSafeEqual } from "node:crypto";
import { pool } from "./db.js";

// Privacy rule: a provider payload never contains chat/group name, sender,
// message text, attachment metadata, or an internal message identifier.
export const genericPushMessage=token=>({
  token,
  data:{kind:"lumo_message"},
  android:{
    priority:"normal",
    ttl:10*60*1000,
    collapseKey:"lumo_generic_message"
  }
});

export const invalidPushTokenCode=code=>[
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token"
].includes(code);

export const retrySeconds=attempts=>
  Math.min(300,20*Math.pow(2,Math.min(attempts,4)));

/**
 * Durable at-least-once generic notification dispatcher.
 *
 * The outbox row contains only database identifiers needed for authorization;
 * it never stores message/group text. Every claimed job is re-authorized
 * immediately before contacting FCM so logout, opt-out, membership removal,
 * deletion, read state (direct chats), or blocking can suppress a stale push.
 */
export async function dispatchPushBatch({db,send,max=12}) {
  const limit=Math.max(1,Math.min(20,Math.floor(max)));

  const exhausted=await db.query(`with expired as (
    select id from push_outbox
    where status='pending' and attempts>=4
      and (lease_until is null or lease_until<=now())
    order by available_at,id
    for update skip locked limit $1
  )
  update push_outbox o
  set status='dropped',processed_at=now(),lease_until=null,
      last_error_code='lease_exhausted'
  from expired
  where o.id=expired.id
  returning o.id`,[limit]);

  const claimed=await db.query(`with due as (
    select id from push_outbox
    where status='pending' and attempts<4
      and available_at<=now()
      and (lease_until is null or lease_until<=now())
    order by available_at,id
    for update skip locked limit $1
  )
  update push_outbox o
  set attempts=o.attempts+1,
      lease_until=now()+interval '2 minutes'
  from due
  where o.id=due.id
  returning o.id,o.direct_message_id,o.group_message_id,
            o.session_token,o.recipient_id,o.attempts,o.created_at`,[limit]);

  let sent=0,dropped=exhausted.rowCount,retried=0;

  for(const job of claimed.rows) {
    const ready=await db.query(`
      select p.fcm_token,p.token_hash,p.xmin::text as device_revision
      from push_outbox o
      join push_devices p
        on p.session_token=o.session_token and p.user_id=o.recipient_id
      join sessions s
        on s.token=p.session_token and s.user_id=p.user_id
       and s.expires_at>now()
      left join messages dm
        on dm.id=o.direct_message_id and dm.recipient_id=o.recipient_id
      left join chat_group_messages gm
        on gm.id=o.group_message_id
      left join chat_group_members member
        on member.group_id=gm.group_id
       and member.user_id=o.recipient_id
       and gm.created_at>=member.joined_at
      where o.id=$1 and o.status='pending'
        and o.attempts=$2 and o.lease_until>now()
        and (
          (
            o.direct_message_id is not null
            and dm.id is not null
            and dm.read_at is null
            and dm.deleted_at is null
            and not exists (
              select 1 from user_blocks b
              where (b.blocker_id=dm.sender_id and b.blocked_id=dm.recipient_id)
                 or (b.blocker_id=dm.recipient_id and b.blocked_id=dm.sender_id)
            )
          )
          or
          (
            o.group_message_id is not null
            and gm.id is not null
            and gm.deleted_at is null
            and member.user_id is not null
            and gm.sender_id<>o.recipient_id
            and not exists (
              select 1 from user_blocks b
              where (b.blocker_id=gm.sender_id and b.blocked_id=o.recipient_id)
                 or (b.blocker_id=o.recipient_id and b.blocked_id=gm.sender_id)
            )
          )
        )
      limit 1`,[job.id,job.attempts]);

    const expired=Date.now()-new Date(job.created_at).getTime()>10*60*1000;
    if(!ready.rowCount || expired) {
      const abandoned=await db.query(`update push_outbox
        set status='dropped',processed_at=now(),lease_until=null,
            last_error_code=$3
        where id=$1 and attempts=$2 and status='pending'
          and lease_until>now()`,
        [job.id,job.attempts,expired?"stale":"no_consent"]);
      dropped+=abandoned.rowCount;
      continue;
    }

    const {fcm_token,token_hash,device_revision}=ready.rows[0];
    try{
      await send(genericPushMessage(fcm_token));
      const completed=await db.query(`update push_outbox
        set status='sent',processed_at=now(),lease_until=null,
            last_error_code=null
        where id=$1 and attempts=$2 and status='pending'
          and lease_until>now()`,
        [job.id,job.attempts]);
      sent+=completed.rowCount;
    }catch(error){
      const code=typeof error?.code==="string" ? error.code : "provider_error";

      if(invalidPushTokenCode(code)) {
        // attempts + lease + xmin fence a slow/stale worker from deleting a
        // newer registration that replaced this token during provider I/O.
        await db.query(`delete from push_devices p using push_outbox o
          where p.session_token=$1 and p.token_hash=$2
            and o.id=$3 and o.session_token=p.session_token
            and o.status='pending' and o.attempts=$4
            and o.lease_until>now()
            and p.xmin::text=$5`,
          [job.session_token,token_hash,job.id,job.attempts,device_revision]);
      }

      if(invalidPushTokenCode(code) || job.attempts>=4) {
        const rejected=await db.query(`update push_outbox
          set status='dropped',processed_at=now(),lease_until=null,
              last_error_code=$3
          where id=$1 and attempts=$2 and status='pending'
            and lease_until>now()`,
          [job.id,job.attempts,
           invalidPushTokenCode(code)?"invalid_token":"send_failed"]);
        dropped+=rejected.rowCount;
      }else{
        const postponed=await db.query(`update push_outbox
          set lease_until=null,
              available_at=now()+($3::int*interval '1 second'),
              last_error_code='provider_retry'
          where id=$1 and attempts=$2 and status='pending'
            and lease_until>now()`,
          [job.id,job.attempts,retrySeconds(job.attempts)]);
        retried+=postponed.rowCount;
      }
    }
  }

  await db.query(
    "delete from push_outbox where created_at<now()-interval '1 day'"
  );

  return {
    processed:claimed.rowCount+exhausted.rowCount,
    sent,dropped,retried
  };
}

let firebaseMessaging;
async function firebaseSend(message) {
  if(!firebaseMessaging) {
    const [
      {initializeApp,applicationDefault,getApps},
      {getMessaging}
    ]=await Promise.all([
      import("firebase-admin/app"),
      import("firebase-admin/messaging")
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

/**
 * Disabled by default. Production/staging operators must separately configure
 * Firebase ADC, project ID, CRON_SECRET and an HTTPS scheduled invocation.
 */
export function registerPushDispatch(app) {
  app.get("/internal/push-dispatch",async(req,res)=>{
    res.set("Cache-Control","private, no-store");
    const secret=process.env.CRON_SECRET || "";
    if(process.env.LUMO_PUSH_DELIVERY_ENABLED!=="true" || !pool ||
       !process.env.FIREBASE_PROJECT_ID || Buffer.byteLength(secret)<32)
      return res.status(404).json({error:"feature_unavailable"});

    const provided=typeof req.headers.authorization==="string" &&
      req.headers.authorization.startsWith("Bearer ")
      ? req.headers.authorization.slice(7) : "";
    const a=Buffer.from(provided,"utf8");
    const b=Buffer.from(secret,"utf8");
    if(a.length!==b.length || !timingSafeEqual(a,b))
      return res.status(401).json({error:"unauthorized"});

    try{
      const result=await dispatchPushBatch({db:pool,send:firebaseSend});
      return res.json({ok:true,...result});
    }catch(error){
      console.error(
        "Push worker failed",
        typeof error?.code==="string" ? error.code : "unknown"
      );
      return res.status(503).json({error:"service_unavailable"});
    }
  });
}
