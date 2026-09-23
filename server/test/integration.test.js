import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import WebSocket from "ws";
import pg from "pg";
import { dispatchPushBatch } from "../src/push-outbox.js";
import { readFile } from "node:fs/promises";

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
  let replica;
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
    // Simulate two Vercel function instances sharing one PostgreSQL database.
    const listener2=createServer();
    await new Promise((resolve,reject)=>listener2.once("error",reject).listen(0,"127.0.0.1",resolve));
    const otherPort=listener2.address().port;
    await new Promise(resolve=>listener2.close(resolve));
    replica=spawn(process.execPath,["src/index.js"],{
      cwd:process.cwd(),
      env:{...process.env,PORT:String(otherPort),DATABASE_URL:databaseUrl,DATABASE_SSL:"false"},
      stdio:"ignore"
    });
    const otherBase=`http://127.0.0.1:${otherPort}`;
    let replicaHealthy=false;
    for(let i=0;i<100;i++){
      if(replica.exitCode!==null)throw new Error("Second integration server exited");
      try{
        const r=await fetch(otherBase+"/health");
        if(r.ok){replicaHealthy=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.ok(replicaHealthy,"Second server did not share the ready database");
    // The SQL Editor deployment path must also be safe against an existing schema.
    const schema=await readFile(new URL("../db/schema.sql",import.meta.url),"utf8");
    const schemaPool=new pg.Pool({connectionString:databaseUrl,ssl:false});
    try{
      await schemaPool.query(schema);
      await schemaPool.query(schema);
    }finally{
      await schemaPool.end();
    }
    async function request(path,method="GET",token=null,body=null,origin=base){
      const r=await fetch(origin+path,{
        method,
        headers:{...(token?{Authorization:"Bearer "+token}:{}),...(body?{"content-type":"application/json"}:{})},
        ...(body?{body:JSON.stringify(body)}:{})
      });
      const raw=await r.text();
      return {status:r.status,json:raw ? JSON.parse(raw) : null};
    }
    const testPass="test-"+randomUUID()+"-Secure";
    const first=await request("/api/register","POST",null,{username:"a"+randomUUID().slice(0,8),displayName:"Alice",password:testPass});
    const second=await request("/api/register","POST",null,{username:"b"+randomUUID().slice(0,8),displayName:"Bob",password:testPass});
    const third=await request("/api/register","POST",null,{username:"c"+randomUUID().slice(0,8),displayName:"Charlie",password:testPass});
    const weak=await request("/api/register","POST",null,{username:"weak"+randomUUID().slice(0,8),displayName:"Weak",password:"short"});
    assert.equal(weak.status,400);
    assert.equal(weak.json.error,"invalid_password");
    assert.equal(first.status,201);
    assert.equal(second.status,201);
    assert.equal(third.status,201);
    const a=first.json,b=second.json,c=third.json;
    // The inbox must load even before the user has sent any messages.
    const initialChats=await request("/api/conversations","GET",a.token);
    assert.equal(initialChats.status,200,"Empty inbox must not fail on ambiguous SQL columns");
    assert.deepEqual(initialChats.json,[]);
    const prematurePin=await request("/api/conversations/"+b.user.id+"/pin","PUT",a.token);
    assert.equal(prematurePin.status,404,"Cannot pin a conversation before any messages");
    assert.equal(a.user.password_hash,undefined);
    const invalidLogin=await request("/api/login","POST",null,{username:a.user.username,password:"invalid-"+randomUUID()});
    assert.equal(invalidLogin.status,401);
    const relogin=await request("/api/login","POST",null,{username:a.user.username,password:testPass});
    assert.equal(relogin.status,200);
    assert.equal(relogin.json.user.id,a.user.id);
    assert.notEqual(relogin.json.token,a.token);
    const queryTokenSocket=new WebSocket(`ws://127.0.0.1:${port}/ws?token=${a.token}`);
    const queryTokenClose=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{queryTokenSocket.terminate();reject(new Error("Query-token WebSocket accepted"));},3000);
      queryTokenSocket.once("close",code=>{clearTimeout(timer);resolve(code);});
      queryTokenSocket.once("error",error=>{clearTimeout(timer);reject(error);});
    });
    assert.equal(queryTokenClose,1008,"Valid session tokens in URLs must not authenticate");
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
    const fromCharlie=await request("/api/messages","POST",c.token,{
      to:b.user.id,text:"from another conversation",clientMessageId:randomUUID()
    });
    assert.equal(fromCharlie.status,201);
    const aliceChats=await request("/api/conversations","GET",a.token,null,otherBase);
    assert.equal(aliceChats.status,200);
    assert.equal(aliceChats.json.length,1);
    assert.equal(aliceChats.json[0].peer.id,b.user.id);
    assert.equal(aliceChats.json[0].lastMessage,payload.text);
    const bobChats=await request("/api/conversations","GET",b.token);
    assert.equal(bobChats.status,200);
    assert.equal(bobChats.json.length,2,"Inbox includes both conversation peers");
    assert.deepEqual(new Set(bobChats.json.map(chat=>chat.peer.id)),new Set([a.user.id,c.user.id]));
    assert.equal(bobChats.json.find(chat=>chat.peer.id===a.user.id).unreadCount,1);
    assert.equal(bobChats.json.find(chat=>chat.peer.id===c.user.id).unreadCount,1);
    assert.equal(aliceChats.json[0].unreadCount,0);
    const pinAlice=await request("/api/conversations/"+a.user.id+"/pin","PUT",b.token);
    assert.equal(pinAlice.status,200);
    assert.equal(pinAlice.json.pinned,true);
    const pinnedChats=await request("/api/conversations","GET",b.token,null,otherBase);
    assert.equal(pinnedChats.status,200);
    assert.equal(pinnedChats.json[0].peer.id,a.user.id,"Pins are persisted across server instances");
    assert.equal(pinnedChats.json[0].pinned,true);
    const otherUserView=await request("/api/conversations","GET",a.token);
    assert.equal(otherUserView.json[0].pinned,false,"Pins belong only to the user");
    const received=await request("/api/messages/"+a.user.id,"GET",b.token,null,otherBase);
    assert.equal(received.status,200);
    assert.equal(received.json.length,1);
    assert.ok(received.json[0].deliveredAt,"Fetching history records delivery");
    const unopened=await request("/api/messages/"+b.user.id,"GET",c.token);
    assert.equal(unopened.status,200);
    assert.equal(unopened.json.length,1);
    assert.equal(unopened.json[0].deliveredAt,null,"Fetching Alice must not mark Charlie delivered");
    const read=await request("/api/messages/read","POST",b.token,{ids:[sent.json.id]},otherBase);
    assert.equal(read.status,200);
    assert.equal(read.json.receipts.length,1);
    assert.ok(read.json.receipts[0].readAt);
    const afterRead=await request("/api/conversations","GET",b.token);
    assert.equal(afterRead.json.find(chat=>chat.peer.id===a.user.id).unreadCount,0);
    assert.equal(afterRead.json.find(chat=>chat.peer.id===c.user.id).unreadCount,1);
    const unpinAlice=await request("/api/conversations/"+a.user.id+"/pin","DELETE",b.token);
    assert.equal(unpinAlice.status,200);
    assert.equal(unpinAlice.json.pinned,false);
    const history=await request("/api/messages/"+b.user.id,"GET",a.token);
    assert.equal(history.status,200);
    assert.equal(history.json.length,1);
    assert.ok(history.json[0].readAt,"Sender can recover read status across instances");
    const followUp=await request("/api/messages","POST",b.token,{to:a.user.id,text:"most recent message",clientMessageId:randomUUID()});
    assert.equal(followUp.status,201);
    const updatedChats=await request("/api/conversations","GET",a.token);
    assert.equal(updatedChats.status,200);
    assert.equal(updatedChats.json.length,1);
    assert.equal(updatedChats.json[0].lastMessage,"most recent message");
    const invalidSelfBlock=await request("/api/blocks/"+b.user.id,"PUT",b.token);
    assert.equal(invalidSelfBlock.status,400);
    const block=await request("/api/blocks/"+c.user.id,"PUT",b.token);
    assert.equal(block.status,200);
    assert.equal(block.json.blocked,true);
    const blocks=await request("/api/blocks","GET",b.token,null,otherBase);
    assert.equal(blocks.status,200);
    assert.equal(blocks.json.length,1);
    assert.equal(blocks.json[0].id,c.user.id);
    const blockedOutgoing=await request("/api/messages","POST",b.token,{to:c.user.id,text:"blocked outgoing",clientMessageId:randomUUID()});
    const blockedIncoming=await request("/api/messages","POST",c.token,{to:b.user.id,text:"blocked incoming",clientMessageId:randomUUID()},otherBase);
    assert.equal(blockedOutgoing.status,403);
    assert.equal(blockedOutgoing.json.error,"user_blocked");
    assert.equal(blockedIncoming.status,403);
    assert.equal(blockedIncoming.json.error,"user_blocked");
    const blockedSocket=new WebSocket(`ws://127.0.0.1:${port}/ws`,{headers:{Authorization:"Bearer "+c.token}});
    try {
      const connected=await new Promise((resolve,reject)=>{
        const timeout=setTimeout(()=>reject(new Error("Blocked-message socket did not connect")),3000);
        blockedSocket.once("message",data=>{clearTimeout(timeout);resolve(JSON.parse(data.toString()));});
        blockedSocket.once("error",error=>{clearTimeout(timeout);reject(error);});
      });
      assert.equal(connected.type,"ready");
      const rejected=await new Promise((resolve,reject)=>{
        const timeout=setTimeout(()=>reject(new Error("WebSocket bypassed the user block")),3000);
        blockedSocket.once("message",data=>{clearTimeout(timeout);resolve(JSON.parse(data.toString()));});
        blockedSocket.send(JSON.stringify({type:"message",to:b.user.id,text:"blocked WS",clientMessageId:randomUUID()}));
      });
      assert.equal(rejected.type,"error");
      assert.equal(rejected.error,"user_blocked");
    }finally{blockedSocket.terminate();}
    const searchBlockedA=await request("/api/users?q="+c.user.username,"GET",b.token);
    const searchBlockedB=await request("/api/users?q="+b.user.username,"GET",c.token);
    assert.ok(searchBlockedA.json.every(user=>user.id!==c.user.id));
    assert.ok(searchBlockedB.json.every(user=>user.id!==b.user.id));
    const blockedHistory=await request("/api/messages/"+c.user.id,"GET",b.token);
    assert.equal(blockedHistory.status,200,"Blocking does not delete already exchanged messages");
    assert.equal(blockedHistory.json.length,1);
    const unblock=await request("/api/blocks/"+c.user.id,"DELETE",b.token);
    assert.equal(unblock.status,204);
    const cleared=await request("/api/blocks","GET",b.token);
    assert.deepEqual(cleared.json,[]);
    const restored=await request("/api/messages","POST",c.token,{to:b.user.id,text:"after unblock",clientMessageId:randomUUID()});
    assert.equal(restored.status,201);

    const pushToken="test-fcm:"+randomUUID()+randomUUID();
    const invalidPush=await request("/api/devices/push","POST",a.token,{platform:"android",token:"short"});
    assert.equal(invalidPush.status,400);
    const unauthorizedPush=await request("/api/devices/push","POST",null,{platform:"android",token:pushToken});
    assert.equal(unauthorizedPush.status,401);
    const registered=await request("/api/devices/push","POST",a.token,{platform:"android",token:pushToken});
    assert.equal(registered.status,200);
    assert.deepEqual(registered.json,{registered:true},"Do not echo private push tokens in API responses");
    const registeredAgain=await request("/api/devices/push","POST",a.token,{platform:"android",token:pushToken},otherBase);
    assert.equal(registeredAgain.status,200,"Registration retries are idempotent across instances");
    const moved=await request("/api/devices/push","POST",b.token,{platform:"android",token:pushToken},otherBase);
    assert.equal(moved.status,200,"Switching accounts on one device transfers ownership");
    const pushPool=new pg.Pool({connectionString:databaseUrl,ssl:false});
    try{
      const rows=await pushPool.query("select user_id,session_token,fcm_token from push_devices where fcm_token=$1",[pushToken]);
      assert.equal(rows.rowCount,1,"At most one session owns one FCM token");
      assert.equal(rows.rows[0].user_id,b.user.id);
      assert.equal(rows.rows[0].session_token,b.token);

      // A message insert and its per-session job commit atomically, for HTTP and WS alike.
      const notificationText="A private message that must never leave this database";
      const pushId=randomUUID();
      const newMessage=await request("/api/messages","POST",c.token,{
        to:b.user.id,text:notificationText,clientMessageId:pushId
      },otherBase);
      assert.equal(newMessage.status,201);
      const retriedMessage=await request("/api/messages","POST",c.token,{
        to:b.user.id,text:notificationText,clientMessageId:pushId
      });
      assert.equal(retriedMessage.status,200);
      const queued=await pushPool.query("select session_token,status from push_outbox where message_id=$1",[newMessage.json.id]);
      assert.equal(queued.rowCount,1,"Idempotent retried message creates one notification job");
      assert.equal(queued.rows[0].session_token,b.token);
      const notificationRequests=[];
      const sender=async p=>{notificationRequests.push(p);};
      const parallel=await Promise.all([
        dispatchPushBatch({db:pushPool,send:sender,max:2}),
        dispatchPushBatch({db:pushPool,send:sender,max:2})
      ]);
      assert.equal(parallel.reduce((sum,r)=>sum+r.sent,0),1,
        "Cross-instance workers may claim a notification only once");
      assert.equal(notificationRequests.length,1);
      assert.equal(notificationRequests[0].token,pushToken);
      assert.ok(!JSON.stringify(notificationRequests[0]).includes(notificationText),
        "Do not send message contents or author names to FCM");
      const afterDelivery=await pushPool.query("select status from push_outbox where message_id=$1",[newMessage.json.id]);
      assert.equal(afterDelivery.rows[0].status,"sent");

      // Reproduce a stalled provider response after the worker's lease expires.
      // A second cron instance can reclaim the job. When the first finally
      // reports an INVALID token, it MUST NOT delete the second worker's active
      // registration or overwrite the second worker's successful result.
      const leaseMessage=await request("/api/messages","POST",c.token,{
        to:b.user.id,text:"Race test: stale worker",clientMessageId:randomUUID()
      });
      assert.equal(leaseMessage.status,201);
      let onProviderStarted;
      const providerStarted=new Promise(resolve=>{onProviderStarted=resolve;});
      let releaseStalledSender;
      const stalled=new Promise(resolve=>{releaseStalledSender=resolve;});
      const oldWorker=dispatchPushBatch({
        db:pushPool,max:1,
        send:async()=>{
          onProviderStarted();
          await stalled;
          throw Object.assign(new Error("Invalid token from delayed provider"),{
            code:"messaging/invalid-registration-token"
          });
        }
      });
      await providerStarted;
      const artificialExpiry=await pushPool.query(`update push_outbox
        set lease_until=now()-interval '1 second'
        where message_id=$1 and status='pending'
        returning attempts`,[leaseMessage.json.id]);
      assert.equal(artificialExpiry.rows[0].attempts,1);
      const recovered=await dispatchPushBatch({
        db:pushPool,max:1,send:async p=>{notificationRequests.push(p);}
      });
      assert.equal(recovered.sent,1,"Another worker can recover an expired lease");
      releaseStalledSender();
      const staleResult=await oldWorker;
      assert.equal(staleResult.dropped,0,"A superseded worker must not drop another worker's job");
      const recoveredRow=await pushPool.query(`select status,attempts
        from push_outbox where message_id=$1`,[leaseMessage.json.id]);
      assert.equal(recoveredRow.rows[0].status,"sent");
      assert.equal(recoveredRow.rows[0].attempts,2);
      const registration=await pushPool.query(
        "select 1 from push_devices where fcm_token=$1 and session_token=$2",
        [pushToken,b.token]
      );
      assert.equal(registration.rowCount,1,
        "Stale invalid-token callback must not delete the active registration");

      // Four timed-out leases must not generate a fifth provider send or
      // remain pending indefinitely when a serverless worker crashes.
      const exhaustedMessage=await request("/api/messages","POST",c.token,{
        to:b.user.id,text:"Exhausted lease must stay silent",clientMessageId:randomUUID()
      });
      assert.equal(exhaustedMessage.status,201);
      await pushPool.query(`update push_outbox
        set attempts=4,lease_until=now()-interval '1 second'
        where message_id=$1`,[exhaustedMessage.json.id]);
      const countBeforeExpiry=notificationRequests.length;
      const reclaimed=await dispatchPushBatch({
        db:pushPool,send:async p=>{notificationRequests.push(p);}
      });
      assert.ok(reclaimed.dropped>=1,"Exhausted lease should be dropped");
      assert.equal(notificationRequests.length,countBeforeExpiry,
        "A fifth attempt must never send another push");
      const droppedRow=await pushPool.query(`select status,last_error_code from push_outbox
        where message_id=$1`,[exhaustedMessage.json.id]);
      assert.equal(droppedRow.rows[0].status,"dropped");
      assert.equal(droppedRow.rows[0].last_error_code,"lease_exhausted");

      // Messages already read by the recipient should never generate late alerts.
      const readMessage=await request("/api/messages","POST",c.token,{
        to:b.user.id,text:"Read before worker",clientMessageId:randomUUID()
      });
      assert.equal(readMessage.status,201);
      assert.equal((await request("/api/messages/read","POST",b.token,{ids:[readMessage.json.id]})).status,200);
      const beforeSkipped=notificationRequests.length;
      await dispatchPushBatch({db:pushPool,send:sender});
      assert.equal(notificationRequests.length,beforeSkipped,"Skip already-read notifications");

      // Blocking after insertion but before dispatch must suppress queued alerts.
      const blockedMessage=await request("/api/messages","POST",c.token,{
        to:b.user.id,text:"Blocked before worker",clientMessageId:randomUUID()
      });
      assert.equal(blockedMessage.status,201);
      assert.equal((await request("/api/blocks/"+c.user.id,"PUT",b.token)).status,200);
      await dispatchPushBatch({db:pushPool,send:sender});
      assert.equal(notificationRequests.length,beforeSkipped,"Blocked sender must not generate push");
      assert.equal((await request("/api/blocks/"+c.user.id,"DELETE",b.token)).status,204);

      // Opting out between message insertion and dispatch is respected.
      const revokeMessage=await request("/api/messages","POST",c.token,{
        to:b.user.id,text:"Revoked before worker",clientMessageId:randomUUID()
      });
      assert.equal(revokeMessage.status,201);
      const revoked=await request("/api/devices/push","DELETE",b.token,null,otherBase);
      assert.equal(revoked.status,204);
      await dispatchPushBatch({db:pushPool,send:sender});
      assert.equal(notificationRequests.length,beforeSkipped,"Revoked device must not receive pending alerts");
      assert.equal(revoked.status,204);
      const gone=await pushPool.query("select 1 from push_devices where fcm_token=$1",[pushToken]);
      assert.equal(gone.rowCount,0,"Revoking push registration removes the token");
      const registerLogout=await request("/api/devices/push","POST",a.token,{platform:"android",token:pushToken});
      assert.equal(registerLogout.status,200);
    }finally{await pushPool.end();}
    const socketClosed=new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error("Logged-out WebSocket remains open")),3000);
      socket.once("close",code=>{clearTimeout(timer);resolve(code);});
      socket.once("error",error=>{clearTimeout(timer);reject(error);});
    });
    const logout=await fetch(otherBase+"/api/logout",{
      method:"POST",headers:{Authorization:"Bearer "+a.token}
    });
    assert.equal(logout.status,204);

    const postLogoutPool=new pg.Pool({connectionString:databaseUrl,ssl:false});
    try{
      const left=await postLogoutPool.query("select 1 from push_devices where session_token=$1",[a.token]);
      assert.equal(left.rowCount,0,"Session revocation must cascade to push token cleanup");
    }finally{await postLogoutPool.end();}
    // Revocation on another instance must block the next message on this socket.
    socket.send(JSON.stringify({type:"read",ids:[]}));
    assert.equal(await socketClosed,1008);
    const revoked=await request("/api/me","GET",a.token);
    assert.equal(revoked.status,401);
    const otherSession=await request("/api/me","GET",relogin.json.token);
    assert.equal(otherSession.status,200);
    assert.equal(otherSession.json.id,a.user.id);
    const expiringWs=new WebSocket(`ws://127.0.0.1:${port}/ws`,{
      headers:{Authorization:"Bearer "+relogin.json.token}
    });
    const accepted=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error("Valid login WebSocket not accepted")),3000);
      expiringWs.once("message",data=>{clearTimeout(timer);resolve(JSON.parse(data.toString()));});
      expiringWs.once("error",error=>{clearTimeout(timer);reject(error);});
    });
    assert.equal(accepted.type,"ready");
    const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
    try {
      await pool.query("update sessions set expires_at=now()-interval '1 second' where token=$1",[relogin.json.token]);
    } finally {
      await pool.end();
    }
    const expired=await request("/api/me","GET",relogin.json.token);
    assert.equal(expired.status,401);
    const expiredSocketClose=new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{expiringWs.terminate();reject(new Error("Expired WebSocket stayed active"));},3000);
      expiringWs.once("close",code=>{clearTimeout(timer);resolve(code);});
      expiringWs.once("error",error=>{clearTimeout(timer);reject(error);});
    });
    expiringWs.send(JSON.stringify({type:"read",ids:[]}));
    assert.equal(await expiredSocketClose,1008);
  }finally{
    socket?.terminate();
    replica?.kill();
    child.kill();
  }
});
