import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID, createHmac } from "node:crypto";
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;
const turnSecret="ci-only-test-turn-secret-longer-than-32-chars";
const cronSecret="ci-call-cleanup-only-secret-over-thirty-two-chars";

async function freePort(){
  const listener=createServer();
  await new Promise((resolve,reject)=>
    listener.once("error",reject).listen(0,"127.0.0.1",resolve));
  const port=listener.address().port;
  await new Promise(resolve=>listener.close(resolve));
  return port;
}

function spawnServer(port){
  return spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    stdio:"ignore",
    env:{
      ...process.env,
      PORT:String(port),
      DATABASE_URL:databaseUrl,
      DATABASE_SSL:"false",
      LUMO_CALL_SIGNALING_ENABLED:"true",
      LUMO_TURN_URLS:"turn:127.0.0.1:3478?transport=udp",
      LUMO_TURN_SECRET:turnSecret,
      CRON_SECRET:cronSecret
    }
  });
}

async function waitReady(child,base){
  for(let i=0;i<120;i++){
    if(child.exitCode!==null)throw new Error("Call staging server exited early");
    try{
      const r=await fetch(base+"/health");
      if(r.ok)return;
    }catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error("Call staging server did not become healthy");
}

test("private cross-instance call signaling and TURN credentials are participant-only",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  const port=await freePort();
  const otherPort=await freePort();
  const child=spawnServer(port);
  const replica=spawnServer(otherPort);
  const base=`http://127.0.0.1:${port}`;
  const other=`http://127.0.0.1:${otherPort}`;
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const userIds=[];

  async function request(path,{origin=base,token,method="GET",body,authOverride}={}){
    const authorization=authOverride!==undefined?authOverride:(token?"Bearer "+token:null);
    const response=await fetch(origin+path,{
      method,
      headers:{
        ...(authorization?{Authorization:authorization}:{}),
        ...(body?{"Content-Type":"application/json"}:{})
      },
      ...(body?{body:JSON.stringify(body)}:{})
    });
    const raw=await response.text();
    return {status:response.status,body:raw?JSON.parse(raw):null};
  }

  async function register(prefix){
    const result=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,14),
        displayName:"Call Test",
        password:"call-test-"+randomUUID()
      }
    });
    assert.equal(result.status,201);
    userIds.push(result.body.user.id);
    return result.body;
  }

  try{
    await Promise.all([waitReady(child,base),waitReady(replica,other)]);
    const alice=await register("cala");
    const bob=await register("calb");
    const charlie=await register("calc");

    assert.equal((await request("/api/calls")).status,401);
    assert.equal((await request("/api/calls",{
      token:alice.token,method:"POST",
      body:{to:alice.user.id,kind:"audio"}
    })).status,400);

    const invite=await request("/api/calls",{
      token:alice.token,method:"POST",
      body:{to:bob.user.id,kind:"audio"}
    });
    assert.equal(invite.status,201);
    assert.equal(invite.body.status,"ringing");
    const callId=invite.body.id;

    const reverse=await request("/api/calls",{
      origin:other,token:bob.token,method:"POST",
      body:{to:alice.user.id,kind:"audio"}
    });
    assert.equal(reverse.status,409);
    assert.equal(reverse.body.error,"call_busy");

    const outsider=await request("/api/calls/"+callId+"/respond",{
      origin:other,token:charlie.token,method:"POST",body:{action:"accept"}
    });
    assert.equal(outsider.status,404);

    assert.equal((await request("/api/calls/"+callId+"/ice-config",{
      token:alice.token
    })).status,409,"TURN credentials are not issued before acceptance");

    assert.equal((await request("/api/calls/"+callId+"/respond",{
      token:alice.token,method:"POST",body:{action:"accept"}
    })).status,403,"caller cannot self-accept");

    const accepted=await request("/api/calls/"+callId+"/respond",{
      origin:other,token:bob.token,method:"POST",body:{action:"accept"}
    });
    assert.equal(accepted.status,200);
    assert.equal(accepted.body.status,"accepted");

    const iceAlice=await request("/api/calls/"+callId+"/ice-config",{
      origin:other,token:alice.token
    });
    assert.equal(iceAlice.status,200);
    assert.deepEqual(
      iceAlice.body.iceServers[0].urls,
      ["turn:127.0.0.1:3478?transport=udp"]
    );
    const turnUser=iceAlice.body.iceServers[0].username;
    assert.ok(turnUser.endsWith(":"+alice.user.id+":"+callId));
    assert.equal(
      iceAlice.body.iceServers[0].credential,
      createHmac("sha1",turnSecret).update(turnUser).digest("base64")
    );
    assert.equal((await request("/api/calls/"+callId+"/ice-config",{
      token:charlie.token
    })).status,404);

    const offer={
      clientSignalId:randomUUID(),
      type:"offer",
      payload:{sdp:"v=0\\r\\n"}
    };
    assert.equal((await request("/api/calls/"+callId+"/signals",{
      origin:other,token:bob.token,method:"POST",body:offer
    })).status,403,"callee cannot publish caller offer");

    const first=await request("/api/calls/"+callId+"/signals",{
      token:alice.token,method:"POST",body:offer
    });
    assert.equal(first.status,201);
    assert.equal(first.body.seq,1);

    const replay=await request("/api/calls/"+callId+"/signals",{
      origin:other,token:alice.token,method:"POST",body:offer
    });
    assert.equal(replay.status,200);
    assert.equal(replay.body.seq,1,"uncertain network retry stays idempotent");

    const conflict=await request("/api/calls/"+callId+"/signals",{
      token:alice.token,method:"POST",
      body:{...offer,payload:{sdp:"different"}}
    });
    assert.equal(conflict.status,409);

    const seen=await request("/api/calls/"+callId+"/signals?after=0",{
      origin:other,token:bob.token
    });
    assert.equal(seen.status,200);
    assert.equal(seen.body.signals.length,1);
    assert.equal(seen.body.signals[0].payload.sdp,offer.payload.sdp);

    const answer=await request("/api/calls/"+callId+"/signals",{
      origin:other,token:bob.token,method:"POST",
      body:{
        clientSignalId:randomUUID(),
        type:"answer",
        payload:{sdp:"v=0\\r\\na=answer"}
      }
    });
    assert.equal(answer.status,201);
    assert.equal(answer.body.seq,2);

    const ended=await request("/api/calls/"+callId+"/respond",{
      token:alice.token,method:"POST",body:{action:"end"}
    });
    assert.equal(ended.status,200);
    assert.equal((await request("/api/calls/"+callId+"/ice-config",{
      token:bob.token
    })).status,409);
    assert.equal((await request("/api/calls/"+callId+"/signals",{
      token:alice.token,method:"POST",
      body:{
        clientSignalId:randomUUID(),
        type:"ice",
        payload:{candidate:"candidate:late"}
      }
    })).status,409);

    // Cleanup is separate from user sessions and requires its own private secret.
    assert.equal((await request("/internal/call-cleanup")).status,401);
    assert.equal((await request("/internal/call-cleanup",{
      token:alice.token
    })).status,401);
    await pool.query(
      "update call_signals set created_at=now()-interval '2 hours' where call_id=$1",
      [callId]
    );
    await pool.query(
      "update calls set expires_at=now()-interval '2 hours' where id=$1",
      [callId]
    );
    const cleanup=await request("/internal/call-cleanup",{
      origin:other,authOverride:"Bearer "+cronSecret
    });
    assert.equal(cleanup.status,200);
    assert.ok(cleanup.body.signalsDeleted>=2);
    assert.ok(cleanup.body.callsDeleted>=1);
    assert.equal(
      (await pool.query("select 1 from calls where id=$1",[callId])).rowCount,
      0
    );
  }finally{
    child.kill("SIGTERM");
    replica.kill("SIGTERM");
    await Promise.all([child,replica].map(proc=>new Promise(resolve=>{
      if(proc.exitCode!==null||proc.signalCode!==null)return resolve();
      proc.once("exit",resolve);
      setTimeout(resolve,1200).unref();
    })));
    if(userIds.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[userIds]);}
      catch{}
    }
    await pool.end();
  }
});
