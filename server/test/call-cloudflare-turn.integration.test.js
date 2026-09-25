import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer as createHttpServer } from "node:http";
import { createServer as createNetServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;
const keyId="0123456789abcdef0123456789abcdef";
const apiToken="cloudflare-turn-test-token-over-thirty-two-characters";

async function freePort(){
  const listener=createNetServer();
  await new Promise((resolve,reject)=>
    listener.once("error",reject).listen(0,"127.0.0.1",resolve));
  const port=listener.address().port;
  await new Promise(resolve=>listener.close(resolve));
  return port;
}

test("Cloudflare TURN credentials stay server-side and are normalized for Android",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  let seenAuthorization="";
  let seenTtl=0;
  let credentialRequests=0;

  const turn=createHttpServer((req,res)=>{
    const expected="/v1/turn/keys/"+keyId+
      "/credentials/generate-ice-servers";
    if(req.method!=="POST" || req.url!==expected){
      res.writeHead(404);return res.end();
    }
    credentialRequests++;
    seenAuthorization=String(req.headers.authorization||"");
    const chunks=[];
    req.on("data",chunk=>chunks.push(chunk));
    req.on("end",()=>{
      const body=JSON.parse(Buffer.concat(chunks).toString("utf8"));
      seenTtl=body.ttl;
      res.writeHead(201,{"Content-Type":"application/json"});
      res.end(JSON.stringify({
        iceServers:[
          {urls:[
            "stun:stun.cloudflare.com:3478",
            "stun:stun.cloudflare.com:53"
          ]},
          {
            urls:[
              "turn:turn.cloudflare.com:3478?transport=udp",
              "turn:turn.cloudflare.com:53?transport=udp",
              "turn:turn.cloudflare.com:3478?transport=tcp",
              "turn:turn.cloudflare.com:80?transport=tcp",
              "turns:turn.cloudflare.com:5349?transport=tcp",
              "turns:turn.cloudflare.com:443?transport=tcp"
            ],
            username:"ephemeral-user",
            credential:"ephemeral-credential"
          }
        ]
      }));
    });
  });
  await new Promise(resolve=>turn.listen(0,"127.0.0.1",resolve));
  const turnPort=turn.address().port;
  const port=await freePort();

  const child=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    stdio:"ignore",
    env:{
      ...process.env,
      PORT:String(port),
      DATABASE_URL:databaseUrl,
      DATABASE_SSL:"false",
      NODE_ENV:"test",
      LUMO_CALL_SIGNALING_ENABLED:"true",
      LUMO_TURN_URLS:"",
      LUMO_TURN_SECRET:"",
      LUMO_CLOUDFLARE_TURN_KEY_ID:keyId,
      LUMO_CLOUDFLARE_TURN_API_TOKEN:apiToken,
      LUMO_TEST_CLOUDFLARE_TURN_BASE_URL:
        "http://127.0.0.1:"+turnPort
    }
  });
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const base="http://127.0.0.1:"+port;
  const ids=[];

  async function request(path,{token,method="GET",body}={}){
    const response=await fetch(base+path,{
      method,
      headers:{
        ...(token?{Authorization:"Bearer "+token}:{}),
        ...(body?{"Content-Type":"application/json"}:{})
      },
      ...(body?{body:JSON.stringify(body)}:{})
    });
    const raw=await response.text();
    return {status:response.status,body:raw?JSON.parse(raw):null};
  }

  async function register(prefix){
    const r=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,12),
        displayName:"Cloudflare TURN Test",
        password:"turn-test-"+randomUUID()
      }
    });
    assert.equal(r.status,201);
    ids.push(r.body.user.id);
    return r.body;
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(child.exitCode!==null)throw new Error("TURN test server exited");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const caps=await request("/api/capabilities");
    assert.equal(caps.status,200);
    assert.equal(caps.body.callsReady,true);
    assert.equal(caps.body.turnReady,true);
    assert.equal(caps.body.turnProvider,"cloudflare");

    const alice=await register("cfta");
    const bob=await register("cftb");
    const invite=await request("/api/calls",{
      token:alice.token,method:"POST",
      body:{to:bob.user.id,kind:"audio"}
    });
    assert.equal(invite.status,201);

    const accepted=await request(
      "/api/calls/"+invite.body.id+"/respond",
      {token:bob.token,method:"POST",body:{action:"accept"}}
    );
    assert.equal(accepted.status,200);

    const ice=await request(
      "/api/calls/"+invite.body.id+"/ice-config",
      {token:alice.token}
    );
    assert.equal(ice.status,200);
    assert.equal(credentialRequests,1);
    assert.equal(seenAuthorization,"Bearer "+apiToken);
    assert.ok(Number.isInteger(seenTtl));
    assert.ok(seenTtl>=1 && seenTtl<=35*60);

    assert.equal(ice.body.iceServers.length,1);
    assert.equal(ice.body.iceServers[0].username,"ephemeral-user");
    assert.equal(
      ice.body.iceServers[0].credential,
      "ephemeral-credential"
    );
    assert.deepEqual(
      ice.body.iceServers[0].urls,
      [
        "turn:turn.cloudflare.com:3478?transport=udp",
        "turn:turn.cloudflare.com:3478?transport=tcp",
        "turn:turn.cloudflare.com:80?transport=tcp",
        "turns:turn.cloudflare.com:5349?transport=tcp",
        "turns:turn.cloudflare.com:443?transport=tcp"
      ]
    );
    assert.equal(
      JSON.stringify(ice.body).includes(apiToken),
      false,
      "long-term Cloudflare token must never be returned to Android"
    );
    assert.equal(
      JSON.stringify(ice.body).includes(keyId),
      false,
      "Cloudflare TURN key id stays server-side too"
    );
  }finally{
    child.kill("SIGTERM");
    if(child.exitCode===null)await new Promise(resolve=>{
      const timer=setTimeout(resolve,1500);
      child.once("exit",()=>{clearTimeout(timer);resolve();});
    });
    if(ids.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[ids]);}
      catch{}
    }
    await pool.end();
    await new Promise(resolve=>turn.close(resolve));
  }
});
