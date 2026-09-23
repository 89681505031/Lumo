import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => server.once("error", reject).listen(0, "127.0.0.1", resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

test("liveness works while database-dependent routes fail safely", { timeout: 15000 }, async () => {
  const port = await freePort();
  const { DATABASE_URL: _ignored, ...env } = process.env;
  const child = spawn(process.execPath, ["src/index.js"], {
    cwd: process.cwd(), env: { ...env, PORT: String(port) }, stdio: "ignore"
  });
  try {
    const base = `http://127.0.0.1:${port}`;
    let live;
    for (let attempt = 0; attempt < 80; attempt++) {
      if (child.exitCode !== null) throw new Error("Server exited before becoming ready");
      try { live = await fetch(base + "/live"); break; } catch { await new Promise(resolve => setTimeout(resolve, 100)); }
    }
    assert.ok(live, "Server did not start");
    assert.equal(live.status, 200);
    assert.equal((await live.json()).ok, true);
    const health = await fetch(base + "/health");
    assert.equal(health.status, 503);
    assert.equal((await health.json()).database.configured, false);
    const register = await fetch(base + "/api/register", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ username: "smoketest", displayName: "Smoke Test" })
    });
    assert.equal(register.status, 503);
    assert.equal((await register.json()).error, "database_unavailable");
    const malformed = await fetch(base + "/api/register", {
      method: "POST", headers: { "content-type": "application/json" }, body: "{"
    });
    assert.equal(malformed.status, 400);
    assert.equal((await malformed.json()).error, "invalid_json");
    const oversized = await fetch(base + "/api/register", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ padding: "x".repeat(70_000) })
    });
    assert.equal(oversized.status, 413);
    assert.equal((await oversized.json()).error, "payload_too_large");
    const unauthorized = await fetch(base + "/api/me");
    assert.equal(unauthorized.status, 401);
    assert.equal((await unauthorized.json()).error, "unauthorized");
  } finally {
    child.kill();
  }
});
