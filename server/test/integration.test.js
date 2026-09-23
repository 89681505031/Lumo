import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import WebSocket from "ws";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

test("persistent HTTP messaging, idempotency, receipts and WebSocket bearer auth", {
  skip: !databaseUrl,
  timeout: 25000
}, async () => {
  const listener=createServer();
  await new Promise((resolve,reject)=>listener.once("error",reject).listen(0,"127.0.0.1",resolve));
  const port=listener.address().port;
  await new Promise(resolve=>listener.close(resolve));
  const child=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    env:{...process.env,PORT:String(port),DATABASE_URL:databaseUrl,DATABASE_SSL:"false"},
    stdio:"ignore"
  });
  let socket;
  try{
    const base=`http://127.0.0.1:${port}`;
    let healthy=false;
    for(let i=0;i<100;i++){
      if(child.exitCode!==null)throw new Error("Integration server exited");
      try{
        const r=await fetch(base+"/health");
        if(r.ok){healthy=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.ok(healthy,"PostgreSQL schema did not become ready");
    async function request(path,method="GET",token=null,body=null){
      const r=await fetch(base+path,{
        method,
        headers:{...(token?{Authorization:"Bearer "+token}:{}),...(body?{"content-type":"application/json"}:{})},
        ...(body?{body:JSON.stringify(body)}:{})
      });
      return {status:r.status,json:await r.json()};
    }
    const first=await request("/api/register","POST",null,{username:"a"+randomUUID().slice(0,12),displayName:"Alice"});
    const second=await request("/api/register","POST",null,{username:"b"+randomUUID().slice(0,12),displayName:"Bob"});
    assert.equal(first.status,201);
    assert.equal(second.status,201);
    const a=first.json,b=second.json;
    socket=new WebSocket(`ws://127.0.0.1:${port}/ws`,{headers:{Authorization:"Bearer "+a.token}});
    const ready=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error("No authenticated WebSocket ready event")),3000);
      socket.once("message",data=>{clearTimeout(timer);resolve(JSON.parse(data.toString()));});
      socket.once("error",error=>{clearTimeout(timer);reject(error);});
    });
    assert.equal(ready.type,"ready");
    assert.equal(ready.userId,a.user.id);
    const clientMessageId=randomUUID();
    const payload={to:b.user.id,text:"hello over HTTP",clientMessageId};
    const sent=await request("/api/messages","POST",a.token,payload);
    assert.equal(sent.status,201);
    assert.equal(sent.json.clientMessageId,clientMessageId);
    const retried=await request("/api/messages","POST",a.token,payload);
    assert.equal(retried.status,200);
    assert.equal(retried.json.id,sent.json.id);
    const conflict=await request("/api/messages","POST",a.token,{...payload,text:"changed"});
    assert.equal(conflict.status,409);
    assert.equal(conflict.json.error,"client_message_id_conflict");
    const invalid=await request("/api/messages","POST",a.token,{...payload,to:"bad"});
    assert.equal(invalid.status,400);
    const received=await request("/api/messages/"+a.user.id,"GET",b.token);
    assert.equal(received.status,200);
    assert.equal(received.json.length,1);
    assert.ok(received.json[0].deliveredAt,"Fetching history records delivery");
    const read=await request("/api/messages/read","POST",b.token,{ids:[sent.json.id]});
    assert.equal(read.status,200);
    assert.equal(read.json.receipts.length,1);
    assert.ok(read.json.receipts[0].readAt);
    const history=await request("/api/messages/"+b.user.id,"GET",a.token);
    assert.equal(history.status,200);
    assert.equal(history.json.length,1);
    assert.ok(history.json[0].readAt,"Sender can recover read status across instances");
  }finally{
    socket?.terminate();
    child.kill();
  }
});
