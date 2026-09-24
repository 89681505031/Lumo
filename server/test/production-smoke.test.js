import test from "node:test";
import assert from "node:assert/strict";

const base=process.env.LUMO_PRODUCTION_URL?.replace(/\/$/,"");

async function request(path,options={}) {
  const response=await fetch(base+path,{
    ...options,
    signal:AbortSignal.timeout(12000),
    headers:{"Cache-Control":"no-cache",...options.headers}
  });
  let body;
  const raw=await response.text();
  try { body=raw ? JSON.parse(raw) : null; } catch {
    throw new Error(`Non-JSON response for ${path} (HTTP ${response.status})`);
  }
  return {status:response.status,body,headers:response.headers};
}

test("deployed Lumo API and PostgreSQL are available without test accounts",{
  skip:!base,
  timeout:50000
},async()=>{
  assert.match(base,/^https:\/\//,"Production tests require HTTPS");
  const live=await request("/live");
  assert.equal(live.status,200,"Deployed server must respond");
  assert.equal(live.body?.ok,true);

  const health=await request("/health");
  assert.equal(health.status,200,"Production PostgreSQL/schema must be ready");
  assert.equal(health.body?.database?.configured,true);
  assert.equal(health.body?.database?.ok,true);

  const capabilities=await request("/api/capabilities");
  assert.equal(
    capabilities.status,200,
    "Production backend is stale: /api/capabilities must exist"
  );
  assert.equal(
    capabilities.body?.groupsReady,true,
    "Production backend must expose current private-group support"
  );
  assert.equal(typeof capabilities.body?.mediaReady,"boolean");
  assert.equal(typeof capabilities.body?.groupAttachments,"boolean");
  assert.equal(typeof capabilities.body?.callsReady,"boolean");
  assert.equal(typeof capabilities.body?.turnReady,"boolean");

  const anonymous=await request("/api/me");
  assert.equal(anonymous.status,401,"Private profile must reject anonymous requests");
  assert.equal(anonymous.body?.error,"unauthorized");
  assert.equal(anonymous.headers.get("cache-control"),"no-store");

  const anonymousLogout=await request("/api/logout",{method:"POST"});
  assert.equal(anonymousLogout.status,401);
  assert.equal(anonymousLogout.headers.get("cache-control"),"no-store");

  const malformed=await request("/api/login",{
    method:"POST",
    headers:{"Content-Type":"application/json"},
    body:JSON.stringify({username:"probe_invalid_name",password:"x"})
  });
  assert.equal(malformed.status,401,"Invalid login must not create a session");
  assert.equal(malformed.body?.error,"invalid_credentials");
  assert.equal(malformed.headers.get("cache-control"),"no-store");
});
