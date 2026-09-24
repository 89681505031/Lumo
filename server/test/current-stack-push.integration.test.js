import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";
import { dispatchPushBatch, genericPushMessage } from "../src/push-outbox.js";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

async function freePort(){
  const listener=createServer();
  await new Promise((resolve,reject)=>
    listener.once("error",reject).listen(0,"127.0.0.1",resolve));
  const port=listener.address().port;
  await new Promise(resolve=>listener.close(resolve));
  return port;
}

test("generic push outbox covers direct and private-group messages without content leakage",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  const port=await freePort();
  const child=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    stdio:"ignore",
    env:{
      ...process.env,
      PORT:String(port),
      DATABASE_URL:databaseUrl,
      DATABASE_SSL:"false",
      LUMO_PUSH_DELIVERY_ENABLED:"false",
      FIREBASE_PROJECT_ID:""
    }
  });
  const base=`http://127.0.0.1:${port}`;
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const userIds=[];
  let groupId=null;

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
    const result=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,14),
        displayName:"Push Test",
        password:"push-test-"+randomUUID()
      }
    });
    assert.equal(result.status,201);
    userIds.push(result.body.user.id);
    return result.body;
  }

  async function registerDevice(account,token){
    const result=await request("/api/devices/push",{
      token:account.token,method:"POST",
      body:{platform:"android",token}
    });
    assert.equal(result.status,200);
  }

  async function sendDirect(from,to,text){
    return request("/api/messages",{
      token:from.token,method:"POST",
      body:{to:to.user.id,text,clientMessageId:randomUUID()}
    });
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(child.exitCode!==null)throw new Error("Push test server exited early");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const alice=await register("pusha");
    const bob=await register("pushb");
    const carol=await register("pushc");

    assert.equal((await request("/api/devices/push",{
      method:"POST",body:{platform:"android",token:"x".repeat(40)}
    })).status,401);
    assert.equal((await request("/api/devices/push",{
      token:bob.token,method:"POST",
      body:{platform:"android",token:"too short"}
    })).status,400);

    const aliceFcm="fcm-alice-"+randomUUID();
    const bobFcm="fcm-bob-"+randomUUID();
    const carolFcm="fcm-carol-"+randomUUID();
    await registerDevice(alice,aliceFcm);
    await registerDevice(bob,bobFcm);
    await registerDevice(carol,carolFcm);

    const direct=await sendDirect(alice,bob,"private direct content must never enter FCM");
    assert.equal(direct.status,201);

    const directJobs=await pool.query(
      `select direct_message_id,group_message_id,recipient_id,status
       from push_outbox where direct_message_id=$1`,
      [direct.body.id]
    );
    assert.equal(directJobs.rowCount,1);
    assert.equal(directJobs.rows[0].recipient_id,bob.user.id);
    assert.equal(directJobs.rows[0].group_message_id,null);
    assert.equal(directJobs.rows[0].status,"pending");

    const providerMessages=[];
    const firstDispatch=await dispatchPushBatch({
      db:pool,
      send:async message=>providerMessages.push(message)
    });
    assert.equal(firstDispatch.sent,1);
    assert.equal(providerMessages.length,1);
    assert.deepEqual(providerMessages[0],genericPushMessage(bobFcm));
    assert.deepEqual(providerMessages[0].data,{kind:"lumo_message"});
    assert.equal("notification" in providerMessages[0],false);
    const serialized=JSON.stringify(providerMessages[0]);
    assert.equal(serialized.includes("private direct content"),false);
    assert.equal(serialized.includes(alice.user.username),false);

    const readBeforeDispatch=await sendDirect(alice,bob,"already read");
    assert.equal(readBeforeDispatch.status,201);
    await pool.query(
      "update messages set read_at=now() where id=$1",
      [readBeforeDispatch.body.id]
    );
    const suppressed=[];
    const readDispatch=await dispatchPushBatch({
      db:pool,send:async message=>suppressed.push(message)
    });
    assert.equal(readDispatch.sent,0);
    assert.equal(readDispatch.dropped,1);
    assert.deepEqual(suppressed,[]);

    const created=await request("/api/groups",{
      token:alice.token,method:"POST",body:{title:"Secret Group Title"}
    });
    assert.equal(created.status,201);
    groupId=created.body.id;
    for(const account of [bob,carol]){
      const invite=await request("/api/groups/"+groupId+"/members",{
        token:alice.token,method:"POST",body:{userId:account.user.id}
      });
      assert.equal(invite.status,201);
    }

    const groupMessage=await request("/api/groups/"+groupId+"/messages",{
      token:alice.token,method:"POST",
      body:{
        text:"private group content must not enter FCM",
        clientMessageId:randomUUID()
      }
    });
    assert.equal(groupMessage.status,201);

    const groupJobs=await pool.query(
      `select recipient_id,status from push_outbox
       where group_message_id=$1 order by recipient_id`,
      [groupMessage.body.id]
    );
    assert.equal(groupJobs.rowCount,2,"sender must never receive own group push");
    assert.deepEqual(
      new Set(groupJobs.rows.map(r=>r.recipient_id)),
      new Set([bob.user.id,carol.user.id])
    );

    const removed=await request(
      "/api/groups/"+groupId+"/members/"+carol.user.id,
      {token:alice.token,method:"DELETE"}
    );
    assert.equal(removed.status,204);

    const groupProvider=[];
    const groupDispatch=await dispatchPushBatch({
      db:pool,send:async message=>groupProvider.push(message)
    });
    assert.equal(groupDispatch.sent,1);
    assert.equal(groupDispatch.dropped,1);
    assert.equal(groupProvider.length,1);
    assert.deepEqual(groupProvider[0],genericPushMessage(bobFcm));
    const groupSerialized=JSON.stringify(groupProvider[0]);
    assert.equal(groupSerialized.includes("Secret Group Title"),false);
    assert.equal(groupSerialized.includes("private group content"),false);

    // A single physical FCM token can move to a new current session/account,
    // but cannot remain registered to the old account as well.
    const sharedToken="fcm-shared-"+randomUUID();
    await registerDevice(bob,sharedToken);
    await registerDevice(carol,sharedToken);
    const owners=await pool.query(
      "select user_id from push_devices where token_hash=encode(digest($1,'sha256'),'hex')",
      [sharedToken]
    ).catch(()=>null);
    if(owners){
      assert.equal(owners.rowCount,1);
      assert.equal(owners.rows[0].user_id,carol.user.id);
    }else{
      // CI databases do not promise pgcrypto; verify by raw token without
      // requiring an optional extension.
      const byToken=await pool.query(
        "select user_id from push_devices where fcm_token=$1",
        [sharedToken]
      );
      assert.equal(byToken.rowCount,1);
      assert.equal(byToken.rows[0].user_id,carol.user.id);
    }

    assert.equal((await request("/internal/push-dispatch")).status,404,
      "provider delivery stays disabled without explicit staging configuration");

    const revoked=await request("/api/devices/push",{
      token:carol.token,method:"DELETE"
    });
    assert.equal(revoked.status,204);
    const afterRevoke=await pool.query(
      "select 1 from push_devices where session_token=$1",
      [carol.token]
    );
    assert.equal(afterRevoke.rowCount,0);
  }finally{
    child.kill("SIGTERM");
    await new Promise(resolve=>{
      if(child.exitCode!==null||child.signalCode!==null)return resolve();
      child.once("exit",resolve);
      setTimeout(resolve,1200).unref();
    });
    if(groupId){
      try{await pool.query("delete from chat_groups where id=$1",[groupId]);}
      catch{}
    }
    if(userIds.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[userIds]);}
      catch{}
    }
    await pool.end();
  }
});
