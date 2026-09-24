import express from "express";
import { randomUUID, createHmac } from "node:crypto";
import { pool } from "./db.js";

// Signaling only: no media transport, TURN, push wakeup, or production call UI.
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const enabled = () => process.env.LUMO_CALL_SIGNALING_ENABLED === "true";
const publicCall = r => ({
  id:r.id, callerId:r.caller_id, calleeId:r.callee_id, kind:r.kind,
  status:r.status, createdAt:r.created_at, updatedAt:r.updated_at, expiresAt:r.expires_at
});

export function validateSignal(type, payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const mediaSessionId = payload.mediaSessionId;
  if (typeof mediaSessionId !== "string" || !uuid.test(mediaSessionId)) return null;
  if (type === "offer" || type === "answer") {
    if (typeof payload.sdp !== "string" || payload.sdp.length < 1 || payload.sdp.length > 12000) return null;
    return { mediaSessionId, sdp:payload.sdp };
  }
  if (type === "ice") {
    if (typeof payload.candidate !== "string" || payload.candidate.length > 2048) return null;
    if (payload.sdpMid != null && (typeof payload.sdpMid !== "string" || payload.sdpMid.length > 128)) return null;
    if (payload.sdpMLineIndex != null && (!Number.isInteger(payload.sdpMLineIndex) || payload.sdpMLineIndex < 0 || payload.sdpMLineIndex > 64)) return null;
    return {
      mediaSessionId,
      candidate:payload.candidate,
      sdpMid:payload.sdpMid ?? null,
      sdpMLineIndex:payload.sdpMLineIndex ?? null
    };
  }
  return null;
}

async function transaction(action) {
  const c = await pool.connect();
  try {
    await c.query("begin");
    const result = await action(c);
    await c.query("commit");
    return result;
  } catch (error) {
    await c.query("rollback");
    throw error;
  } finally {
    c.release();
  }
}

const send = (res, result) => res.status(result.status).json(result.body);
const guard = handler => async (req,res) => {
  try { await handler(req,res); }
  catch (error) {
    console.error("Call signaling database operation failed", error?.code || "unknown");
    if (!res.headersSent) res.status(503).json({error:"service_unavailable"});
  }
};
async function participant(c, callId, userId, lock=false) {
  const r = await c.query(
    `select *, expires_at<=now() as timed_out from calls
      where id=$1 and (caller_id=$2 or callee_id=$2)${lock ? " for update" : ""}`,
    [callId,userId]
  );
  return r.rows[0] || null;
}
async function blocked(c, first, second) {
  const r = await c.query(
    "select 1 from user_blocks where (blocker_id=$1 and blocked_id=$2) or (blocker_id=$2 and blocked_id=$1) limit 1",
    [first,second]
  );
  return r.rowCount > 0;
}

export function callRouter(auth) {
  const router = express.Router();
  // Always revalidate sessions through the existing shared HTTP middleware.
  router.use(auth);
  router.use((_req,res,next) => {
    if (!enabled()) return res.status(404).json({error:"feature_unavailable"});
    if (!pool) return res.status(503).json({error:"database_unavailable"});
    next();
  });

  // Poll this list across instances for incoming calls and changed statuses.
  router.get("/", guard(async (req,res) => {
    const r = await pool.query(
      `select id,caller_id,callee_id,kind,created_at,updated_at,expires_at,
        case when status in ('ringing','accepted') and expires_at<=now()
             then 'expired' else status end as status
       from calls where (caller_id=$1 or callee_id=$1)
         and created_at>now()-interval '24 hours'
       order by created_at desc limit 40`, [req.user.id]
    );
    res.json(r.rows.map(publicCall));
  }));

  // Serialize both participants, including simultaneous cross-instance invitations.
  router.post("/", guard(async (req,res) => {
    const to = req.body?.to;
    const kind = req.body?.kind;
    if (typeof to !== "string" || !uuid.test(to) || to === req.user.id ||
        !["audio","video"].includes(kind)) return res.status(400).json({error:"invalid_call"});
    const result = await transaction(async c => {
      // Stable lock order prevents reciprocal calls A→B and B→A from deadlocking.
      // The same user cannot start overlapping calls with different recipients.
      for (const id of [req.user.id,to].sort()) {
        await c.query("select pg_advisory_xact_lock(31524, hashtext($1::text))", [id]);
      }
      const recipient = await c.query("select 1 from users where id=$1", [to]);
      if (!recipient.rowCount) return {status:404,body:{error:"recipient_not_found"}};
      if (await blocked(c,req.user.id,to)) return {status:403,body:{error:"user_blocked"}};
      const limit = await c.query(
        `select count(*) filter(where created_at>now()-interval '1 minute')::int as attempts,
           count(*) filter(where status in ('ringing','accepted') and expires_at>now())::int as active
         from calls where caller_id=$1 and
           (created_at>now()-interval '1 minute' or
            (status in ('ringing','accepted') and expires_at>now()))`,
        [req.user.id]
      );
      if (limit.rows[0].attempts >= 3)
        return {status:429,body:{error:"call_rate_limited"}};
      // Include recipients, not just outgoing calls. A ringing invitation occupies
      // both sides until accepted, declined, ended or expired.
      const occupied = await c.query(
        `select id from calls where status in ('ringing','accepted')
            and expires_at>now()
            and (caller_id=any($1::uuid[]) or callee_id=any($1::uuid[]))
            limit 1`,
        [[req.user.id,to]]
      );
      if (occupied.rowCount) return {status:409,body:{error:"call_busy"}};
      const r = await c.query(
        `insert into calls(id,caller_id,callee_id,kind)
         values($1,$2,$3,$4) returning *`,
        [randomUUID(),req.user.id,to,kind]
      );
      return {status:201,body:publicCall(r.rows[0])};
    });
    send(res,result);
  }));

  // Only the callee can accept/decline; either participant can end the call.
  router.post("/:id/respond", guard(async (req,res) => {
    if (!uuid.test(req.params.id) || !["accept","decline","end"].includes(req.body?.action))
      return res.status(400).json({error:"invalid_call_action"});
    const result = await transaction(async c => {
      const call = await participant(c,req.params.id,req.user.id,true);
      if (!call) return {status:404,body:{error:"call_not_found"}};
      if (call.timed_out || !["ringing","accepted"].includes(call.status))
        return {status:409,body:{error:"call_inactive"}};
      const action = req.body.action;
      if (action !== "end" && call.callee_id !== req.user.id)
        return {status:403,body:{error:"not_call_recipient"}};
      if (action !== "end" && call.status !== "ringing")
        return {status:409,body:{error:"invalid_call_state"}};
      if (action === "accept" && await blocked(c,call.caller_id,call.callee_id))
        return {status:403,body:{error:"user_blocked"}};
      const status = action === "accept" ? "accepted" : action === "decline" ? "declined" : "ended";
      const r = await c.query(
        `update calls set status=$2,updated_at=now(),
         expires_at=case when $2='accepted' then now()+interval '30 minutes'
                         when $2 in ('declined','ended') then now()
                         else expires_at end where id=$1 returning *`,
        [call.id,status]
      );
      return {status:200,body:publicCall(r.rows[0])};
    });
    send(res,result);
  }));

  // Coturn REST credentials are derived per accepted call, never committed to the
  // Android app. The developer must supply a private production TURN service.
  router.get("/:id/ice-config", guard(async (req,res) => {
    if (!uuid.test(req.params.id))
      return res.status(400).json({error:"invalid_call_id"});
    const call = await participant(pool,req.params.id,req.user.id);
    if (!call) return res.status(404).json({error:"call_not_found"});
    if (call.status !== "accepted" || call.timed_out)
      return res.status(409).json({error:"call_inactive"});
    if (await blocked(pool,call.caller_id,call.callee_id))
      return res.status(403).json({error:"user_blocked"});
    const secret = process.env.LUMO_TURN_SECRET || "";
    const raw = process.env.LUMO_TURN_URLS || "";
    const urls = raw.split(",").map(v=>v.trim()).filter(Boolean);
    // Fail closed: never hand out unusable or arbitrary ICE URLs.
    if (secret.length < 32 || urls.length < 1 || urls.length > 3 ||
        urls.some(v=>!/^turns?:[a-zA-Z0-9.-]+(?::[0-9]{1,5})?(?:[?]transport=(?:udp|tcp))?$/.test(v)))
      return res.status(503).json({error:"turn_unavailable"});
    // 35-minute upper bound covers an accepted 30-minute lab call.
    const remaining = Math.max(1,Math.ceil((new Date(call.expires_at).getTime()-Date.now())/1000));
    const expiry = Math.floor(Date.now()/1000)+Math.min(remaining+30,35*60);
    const username = expiry+":"+req.user.id+":"+call.id;
    const credential = createHmac("sha1",secret).update(username).digest("base64");
    res.set("Cache-Control","private, no-store");
    res.json({ iceServers:[{urls,username,credential}], expiresAt:new Date(expiry*1000).toISOString() });
  }));

  // Stable per-call sequence number: row lock commits each signal before a later
  // sequence can be allocated. This prevents holes during concurrent polling.
  router.post("/:id/signals", guard(async (req,res) => {
    if (!uuid.test(req.params.id) || !uuid.test(req.body?.clientSignalId || ""))
      return res.status(400).json({error:"invalid_signal_id"});
    const type = req.body?.type;
    const payload = validateSignal(type,req.body?.payload);
    if (!payload) return res.status(400).json({error:"invalid_signal"});
    const result = await transaction(async c => {
      const call = await participant(c,req.params.id,req.user.id,true);
      if (!call) return {status:404,body:{error:"call_not_found"}};
      if (call.status !== "accepted" || call.timed_out)
        return {status:409,body:{error:"call_inactive"}};
      if (await blocked(c,call.caller_id,call.callee_id))
        return {status:403,body:{error:"user_blocked"}};
      if ((type === "offer" && call.caller_id !== req.user.id) ||
          (type === "answer" && call.callee_id !== req.user.id))
        return {status:403,body:{error:"invalid_signal_role"}};
      const existing = await c.query(
        "select seq,type,payload from call_signals where call_id=$1 and sender_id=$2 and client_signal_id=$3",
        [call.id,req.user.id,req.body.clientSignalId]
      );
      if (existing.rows[0]) {
        const x=existing.rows[0];
        const sameSession = x.payload.mediaSessionId === payload.mediaSessionId;
        const matches = type === "ice"
          ? sameSession && x.payload.candidate === payload.candidate &&
            (x.payload.sdpMid ?? null) === payload.sdpMid &&
            (x.payload.sdpMLineIndex ?? null) === payload.sdpMLineIndex
          : sameSession && x.payload.sdp === payload.sdp;
        if (x.type !== type || !matches)
          return {status:409,body:{error:"client_signal_id_conflict"}};
        return {status:200,body:{seq:x.seq}};
      }
      // A mediaSessionId scopes one WebRTC negotiation attempt. Old SDP/ICE
      // stays immutable for audit/cleanup, while a later explicit user restart can
      // negotiate again without accidentally consuming a stale answer/candidate.
      if (type !== "offer") {
        const offer = await c.query(
          `select 1 from call_signals
             where call_id=$1 and sender_id=$2 and type='offer'
               and payload->>'mediaSessionId'=$3 limit 1`,
          [call.id,call.caller_id,payload.mediaSessionId]
        );
        if (!offer.rowCount)
          return {status:409,body:{error:"signal_session_not_found"}};
      }
      if (type === "offer") {
        const prev = await c.query(
          `select 1 from call_signals
             where call_id=$1 and sender_id=$2 and type='offer'
               and payload->>'mediaSessionId'=$3 limit 1`,
          [call.id,req.user.id,payload.mediaSessionId]
        );
        if (prev.rowCount) return {status:409,body:{error:"signal_already_submitted"}};
      }
      if (type === "answer") {
        const answers = await c.query(
          `select count(*)::int as count from call_signals
             where call_id=$1 and sender_id=$2 and type='answer'
               and payload->>'mediaSessionId'=$3`,
          [call.id,req.user.id,payload.mediaSessionId]
        );
        // A participant may recreate its peer connection after rotation/background.
        // Bound re-answers so this recovery path cannot become an unbounded signal sink.
        if (answers.rows[0].count >= 6)
          return {status:429,body:{error:"signal_session_limit"}};
      }
      if (call.last_signal_seq >= 150) return {status:429,body:{error:"signal_limit"}};
      const seq = call.last_signal_seq + 1;
      await c.query("update calls set last_signal_seq=$2 where id=$1",[call.id,seq]);
      await c.query(
        `insert into call_signals(call_id,seq,sender_id,client_signal_id,type,payload)
           values($1,$2,$3,$4,$5,$6::jsonb)`,
        [call.id,seq,req.user.id,req.body.clientSignalId,type,JSON.stringify(payload)]
      );
      return {status:201,body:{seq}};
    });
    send(res,result);
  }));

  router.get("/:id/signals", guard(async (req,res) => {
    if (!uuid.test(req.params.id) || !/^(0|[1-9][0-9]{0,5})$/.test(String(req.query.after ?? "0")))
      return res.status(400).json({error:"invalid_signal_cursor"});
    const call = await participant(pool,req.params.id,req.user.id);
    if (!call) return res.status(404).json({error:"call_not_found"});
    if (call.status !== "accepted" || call.timed_out)
      return res.status(409).json({error:"call_inactive"});
    if (await blocked(pool,call.caller_id,call.callee_id))
      return res.status(403).json({error:"user_blocked"});
    const r = await pool.query(
      `select seq,sender_id as "from",type,payload,created_at as "createdAt"
       from call_signals where call_id=$1 and seq>$2
       order by seq asc limit 50`, [call.id,Number(req.query.after ?? 0)]
    );
    res.json({signals:r.rows});
  }));
  return router;
}
