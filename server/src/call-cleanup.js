import { timingSafeEqual } from "node:crypto";
import { pool } from "./db.js";

/**
 * Scheduled maintenance for private SDP/ICE metadata. There is no in-memory
 * timer: serverless instances may be destroyed between requests.
 *
 * The operator must provision CRON_SECRET and an authenticated scheduled GET.
 * Nothing is scheduled or enabled merely by merging this source file.
 */
export function registerCallCleanup(app) {
  app.get("/internal/call-cleanup", async (req, res) => {
    res.set("Cache-Control", "private, no-store");
    const secret = process.env.CRON_SECRET || "";
    if (process.env.LUMO_CALL_SIGNALING_ENABLED !== "true" ||
        !pool || Buffer.byteLength(secret) < 32) {
      return res.status(404).json({error:"feature_unavailable"});
    }
    const supplied = typeof req.headers.authorization === "string" &&
      req.headers.authorization.startsWith("Bearer ")
      ? req.headers.authorization.slice(7) : "";
    const left = Buffer.from(supplied, "utf8");
    const right = Buffer.from(secret, "utf8");
    if (left.length !== right.length || !timingSafeEqual(left,right)) {
      return res.status(401).json({error:"unauthorized"});
    }

    const client = await pool.connect().catch(() => null);
    if (!client) return res.status(503).json({error:"service_unavailable"});
    try {
      await client.query("begin");
      // Metadata is not retained merely because nobody initiates new calls.
      // Signaling is capped at 30 minutes per accepted session, so no active
      // session should have a signal more than one hour old.
      const signals = await client.query(
        "delete from call_signals where created_at < now() - interval '1 hour'"
      );
      // Deleting old call rows also cascades any leftover signals.
      const calls = await client.query(
        "delete from calls where expires_at < now() - interval '1 hour'"
      );
      await client.query("commit");
      return res.json({ok:true, signalsDeleted:signals.rowCount, callsDeleted:calls.rowCount});
    } catch (error) {
      await client.query("rollback").catch(() => {});
      console.error("Call metadata maintenance failed", error?.code || "unknown");
      return res.status(503).json({error:"service_unavailable"});
    } finally {
      client.release();
    }
  });
}
