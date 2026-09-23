import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID, createHmac } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import WebSocket from "ws";
import pg from "pg";
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
    env:{...process.env,PORT:String(port),DATABASE_URL:databaseUrl,DATABASE_SSL:"false",LUMO_CALL_SIGNALING_ENABLED:"true",LUMO_TURN_URLS:"turn:127.0.0.1:3478?transport=udp",LUMO_TURN_SECRET:"ci-only-test-turn-secret-longer-than-32-chars",CRON_SECRET:"ci-call-cleanup-only-secret-over-thirty-two-chars"},
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
      env:{...process.env,PORT:String(otherPort),DATABASE_URL:databaseUrl,DATABASE_SSL:"false",LUMO_CALL_SIGNALING_ENABLED:"true",LUMO_TURN_URLS:"turn:127.0.0.1:3478?transport=udp",LUMO_TURN_SECRET:"ci-only-test-turn-secret-longer-than-32-chars"},
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
    // Feature-gated signaling uses the shared database, not an in-memory socket registry.
    const callUnauth=await request("/api/calls");
    assert.equal(callUnauth.status,401);
    const selfCall=await request("/api/calls","POST",a.token,{to:a.user.id,kind:"audio"});
    assert.equal(selfCall.status,400);
    const invite=await request("/api/calls","POST",a.token,{to:b.user.id,kind:"audio"});
    assert.equal(invite.status,201);
    assert.equal(invite.json.status,"ringing");
    const callId=invite.json.id;
    // Both participants are occupied while an invitation is ringing.
    const reverse=await request("/api/calls","POST",b.token,{to:a.user.id,kind:"audio"},otherBase);
    assert.equal(reverse.status,409,"Reciprocal ringing call must not be created");
    assert.equal(reverse.json.error,"call_busy");
    const thirdPartyInvite=await request("/api/calls","POST",c.token,{to:b.user.id,kind:"audio"});
    assert.equal(thirdPartyInvite.status,409,"An invited recipient cannot receive overlapping calls");
    const thirdPartyAction=await request("/api/calls/"+callId+"/respond","POST",c.token,{action:"accept"},otherBase);
    assert.equal(thirdPartyAction.status,404,"An outsider must not discover a private call");
    const incoming=await request("/api/calls","GET",b.token,null,otherBase);
    assert.equal(incoming.status,200);
    assert.equal(incoming.json.find(x=>x.id===callId).status,"ringing");
    const prematureIce=await request("/api/calls/"+callId+"/ice-config","GET",a.token);
    assert.equal(prematureIce.status,409,"No ICE configuration before acceptance");
    const outsiderIce=await request("/api/calls/"+callId+"/ice-config","GET",c.token,null,otherBase);
    assert.equal(outsiderIce.status,404,"Outsiders must not fetch TURN credentials");
    const cannotSelfAccept=await request("/api/calls/"+callId+"/respond","POST",a.token,{action:"accept"});
    assert.equal(cannotSelfAccept.status,403);
    const callAccepted=await request("/api/calls/"+callId+"/respond","POST",b.token,{action:"accept"},otherBase);
    assert.equal(callAccepted.status,200);
    assert.equal(callAccepted.json.status,"accepted");
    const aliceIce=await request("/api/calls/"+callId+"/ice-config","GET",a.token,null,otherBase);
    assert.equal(aliceIce.status,200);
    assert.deepEqual(aliceIce.json.iceServers[0].urls,["turn:127.0.0.1:3478?transport=udp"]);
    assert.ok(aliceIce.json.iceServers[0].username.endsWith(":"+a.user.id+":"+callId));
    assert.equal(aliceIce.json.iceServers[0].credential,
      createHmac("sha1","ci-only-test-turn-secret-longer-than-32-chars")
        .update(aliceIce.json.iceServers[0].username).digest("base64"));
    assert.equal(aliceIce.json.iceServers[0].credential.length>20,true);
    assert.equal(aliceIce.json.iceServers[0].username.includes("ci-only-test-turn"),false);
    const bobIce=await request("/api/calls/"+callId+"/ice-config","GET",b.token);
    assert.equal(bobIce.status,200);
    assert.notEqual(aliceIce.json.iceServers[0].username,bobIce.json.iceServers[0].username);

    const offer={clientSignalId:randomUUID(),type:"offer",payload:{sdp:"v=0\\r\\n"}};
    const wrongRole=await request("/api/calls/"+callId+"/signals","POST",b.token,offer,otherBase);
    assert.equal(wrongRole.status,403);
    const firstSignal=await request("/api/calls/"+callId+"/signals","POST",a.token,offer);
    assert.equal(firstSignal.status,201);
    assert.equal(firstSignal.json.seq,1);
    const replay=await request("/api/calls/"+callId+"/signals","POST",a.token,offer,otherBase);
    assert.equal(replay.status,200,"Uncertain-network retry must not duplicate SDP");
    assert.equal(replay.json.seq,1);
    const conflictSignal=await request("/api/calls/"+callId+"/signals","POST",a.token,{
      ...offer,payload:{sdp:"different"}
    });
    assert.equal(conflictSignal.status,409);
    const bobSignals=await request("/api/calls/"+callId+"/signals?after=0","GET",b.token,null,otherBase);
    assert.equal(bobSignals.status,200);
    assert.equal(bobSignals.json.signals.length,1);
    assert.equal(bobSignals.json.signals[0].payload.sdp,offer.payload.sdp);
    const unauthorizedSignals=await request("/api/calls/"+callId+"/signals","GET",c.token);
    assert.equal(unauthorizedSignals.status,404);
    const secondSignal=await request("/api/calls/"+callId+"/signals","POST",b.token,{
      clientSignalId:randomUUID(),type:"answer",payload:{sdp:"v=0\\r\\na=answer"}
    },otherBase);
    assert.equal(secondSignal.status,201);
    assert.equal(secondSignal.json.seq,2);
    const aliceSignals=await request("/api/calls/"+callId+"/signals?after=1","GET",a.token);
    assert.equal(aliceSignals.json.signals.length,1);
    assert.equal(aliceSignals.json.signals[0].seq,2);
    const ended=await request("/api/calls/"+callId+"/respond","POST",a.token,{action:"end"});
    assert.equal(ended.status,200);
    const endedIce=await request("/api/calls/"+callId+"/ice-config","GET",b.token);
    assert.equal(endedIce.status,409);

    const endSeen=await request("/api/calls","GET",b.token,null,otherBase);
    assert.equal(endSeen.json.find(x=>x.id===callId).status,"ended");
    const lateSignal=await request("/api/calls/"+callId+"/signals","POST",a.token,{
      clientSignalId:randomUUID(),type:"ice",payload:{candidate:"candidate:late"}
    });
    assert.equal(lateSignal.status,409);

    // Parallel invites on two separate server instances must serialize on
    // BOTH users, not just on the outgoing caller.
    const [raceAC,raceCA]=await Promise.all([
      request("/api/calls","POST",a.token,{to:c.user.id,kind:"audio"}),
      request("/api/calls","POST",c.token,{to:a.user.id,kind:"audio"},otherBase)
    ]);
    assert.deepEqual([raceAC.status,raceCA.status].sort(),[201,409]);
    const winner=raceAC.status===201?raceAC:raceCA;
    const winnerToken=raceAC.status===201?a.token:c.token;
    const endedRace=await request("/api/calls/"+winner.json.id+"/respond","POST",winnerToken,{action:"end"});
    assert.equal(endedRace.status,200);
    // The cron endpoint is secret-protected and independent from user sessions.
    const cleanupNoKey=await request("/internal/call-cleanup","GET");
    assert.equal(cleanupNoKey.status,401);
    const cleanupUserKey=await request("/internal/call-cleanup","GET",a.token, null, otherBase);
    assert.equal(cleanupUserKey.status,401);
    const maintenancePool=new pg.Pool({connectionString:databaseUrl,ssl:false});
    try {
      await maintenancePool.query(
        "update call_signals set created_at=now()-interval '2 hours' where call_id=$1",
        [callId]
      );
      await maintenancePool.query(
        "update calls set expires_at=now()-interval '2 hours' where id=$1",
        [callId]
      );
      const cleanup=await request(
        "/internal/call-cleanup","GET","ci-call-cleanup-only-secret-over-thirty-two-chars",null,otherBase
      );
      assert.equal(cleanup.status,200);
      assert.equal(cleanup.json.signalsDeleted>=2,true,"Old SDP and ICE metadata removed");
      assert.equal(cleanup.json.callsDeleted>=1,true,"Old calls removed even without new invitations");
      assert.equal((await maintenancePool.query("select id from calls where id=$1",[callId])).rowCount,0);
      assert.equal((await maintenancePool.query("select 1 from calls where id=$1",[winner.json.id])).rowCount,1,
        "Recently ended calls should not be removed prematurely");
      const repeatedCleanup=await request(
        "/internal/call-cleanup","GET","ci-call-cleanup-only-secret-over-thirty-two-chars"
      );
      assert.equal(repeatedCleanup.status,200,"Maintenance can run repeatedly");
    } finally {
      await maintenancePool.end();
    }
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
    const socketClosed=new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error("Logged-out WebSocket remains open")),3000);
      socket.once("close",code=>{clearTimeout(timer);resolve(code);});
      socket.once("error",error=>{clearTimeout(timer);reject(error);});
    });
    const logout=await fetch(otherBase+"/api/logout",{
      method:"POST",headers:{Authorization:"Bearer "+a.token}
    });
    assert.equal(logout.status,204);
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
