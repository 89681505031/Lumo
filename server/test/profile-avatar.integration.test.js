import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

async function freePort(){
  const listener=createServer();
  await new Promise((resolve,reject)=>
    listener.once("error",reject).listen(0,"127.0.0.1",resolve));
  const port=listener.address().port;
  await new Promise(resolve=>listener.close(resolve));
  return port;
}

// Minimal structural JPEG accepted by the server validator. The service stores
// bytes as-is and Android always uploads a real decoded/recompressed JPEG.
function testJpeg(width=64,height=64){
  const sof=Buffer.from([
    0xff,0xc0,0x00,0x11,0x08,
    (height>>8)&0xff,height&0xff,
    (width>>8)&0xff,width&0xff,
    0x03,
    0x01,0x11,0x00,
    0x02,0x11,0x00,
    0x03,0x11,0x00
  ]);
  return Buffer.concat([Buffer.from([0xff,0xd8]),sof,Buffer.from([0xff,0xd9])]);
}

test("profile avatar is explicit, authenticated, bounded and removable",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  const port=await freePort();
  const child=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    env:{...process.env,PORT:String(port),DATABASE_URL:databaseUrl,DATABASE_SSL:"false"},
    stdio:"ignore"
  });
  const base=`http://127.0.0.1:${port}`;
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const ids=[];

  async function jsonRequest(path,{token,method="GET",body}={}){
    const response=await fetch(base+path,{
      method,
      headers:{
        ...(token?{Authorization:"Bearer "+token}:{}),
        ...(body?{"Content-Type":"application/json"}:{})
      },
      ...(body?{body:JSON.stringify(body)}:{})
    });
    const raw=await response.text();
    return {status:response.status,body:raw?JSON.parse(raw):null,headers:response.headers};
  }

  async function avatarRequest(path,{token,method="GET",bytes,mime="image/jpeg"}={}){
    const response=await fetch(base+path,{
      method,
      headers:{
        ...(token?{Authorization:"Bearer "+token}:{}),
        ...(bytes?{"Content-Type":mime}:{})
      },
      ...(bytes?{body:bytes}:{})
    });
    const buffer=Buffer.from(await response.arrayBuffer());
    return {status:response.status,bytes:buffer,headers:response.headers};
  }

  async function register(prefix){
    const result=await jsonRequest("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,16),
        displayName:"Avatar Test",
        password:"avatar-test-"+randomUUID()
      }
    });
    assert.equal(result.status,201);
    ids.push(result.body.user.id);
    return result.body;
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(child.exitCode!==null)throw new Error("Server exited early");
      try{
        if((await jsonRequest("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const alice=await register("ava_a_");
    const bob=await register("ava_b_");
    assert.equal(alice.user.hasAvatar,false);
    assert.equal(alice.user.avatarVersion,"");

    const jpeg=testJpeg(96,96);
    assert.equal((await avatarRequest("/api/me/avatar",{
      method:"PUT",bytes:jpeg
    })).status,401,"avatar upload requires a session");

    const wrongMime=await avatarRequest("/api/me/avatar",{
      token:alice.token,method:"PUT",bytes:jpeg,mime:"image/png"
    });
    assert.equal(wrongMime.status,415);

    const invalid=await avatarRequest("/api/me/avatar",{
      token:alice.token,method:"PUT",bytes:Buffer.from([0xff,0xd8,0xff,0xd9])
    });
    assert.equal(invalid.status,400);

    const upload=await avatarRequest("/api/me/avatar",{
      token:alice.token,method:"PUT",bytes:jpeg
    });
    assert.equal(upload.status,200);
    const uploaded=JSON.parse(upload.bytes.toString("utf8"));
    assert.equal(uploaded.hasAvatar,true);
    assert.ok(uploaded.avatarVersion);

    const me=await jsonRequest("/api/me",{token:alice.token});
    assert.equal(me.status,200);
    assert.equal(me.body.hasAvatar,true);
    assert.equal(me.body.avatarVersion,uploaded.avatarVersion);

    const search=await jsonRequest("/api/users?q=Avatar",{token:bob.token});
    assert.equal(search.status,200);
    const publicAlice=search.body.find(u=>u.id===alice.user.id);
    assert.ok(publicAlice);
    assert.equal(publicAlice.hasAvatar,true);
    assert.equal(publicAlice.avatarVersion,uploaded.avatarVersion);

    assert.equal((await avatarRequest(
      "/api/users/"+alice.user.id+"/avatar"
    )).status,401,"avatar reads are authenticated too");

    const read=await avatarRequest(
      "/api/users/"+alice.user.id+"/avatar",
      {token:bob.token}
    );
    assert.equal(read.status,200);
    assert.equal(read.headers.get("content-type"),"image/jpeg");
    assert.equal(read.headers.get("x-content-type-options"),"nosniff");
    assert.deepEqual(read.bytes,jpeg);

    const missing=await avatarRequest(
      "/api/users/"+bob.user.id+"/avatar",
      {token:alice.token}
    );
    assert.equal(missing.status,404);

    const removed=await jsonRequest("/api/me/avatar",{
      token:alice.token,method:"DELETE"
    });
    assert.equal(removed.status,200);
    assert.equal(removed.body.hasAvatar,false);
    assert.ok(removed.body.avatarVersion,
      "deletion changes version so clients can invalidate cached images");
    assert.equal((await avatarRequest(
      "/api/users/"+alice.user.id+"/avatar",
      {token:bob.token}
    )).status,404);

    const searchAfter=await jsonRequest("/api/users?q=Avatar",{token:bob.token});
    const after=searchAfter.body.find(u=>u.id===alice.user.id);
    assert.equal(after.hasAvatar,false);
  }finally{
    child.kill("SIGTERM");
    await new Promise(resolve=>{
      if(child.exitCode!==null||child.signalCode!==null)return resolve();
      child.once("exit",resolve);
      setTimeout(resolve,1200).unref();
    });
    if(ids.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[ids]);}
      catch{}
    }
    await pool.end();
  }
});
