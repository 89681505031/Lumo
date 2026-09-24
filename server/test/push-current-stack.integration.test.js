import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer as createNetServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";
import { dispatchPushBatch } from "../src/push-outbox.js";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

async function freePort(){
  const server=createNetServer();
  await new Promise((resolve,reject)=>
    server.once("error",reject).listen(0,"127.0.0.1",resolve));
  const port=server.address().port;
  await new Promise(resolve=>server.close(resolve));
  return port;
}

test("session push outbox stays generic and rechecks direct/group privacy before dispatch",{
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
      LUMO_PUSH_DELIVERY_ENABLED:"false"
    }
  });
  const base=`http://127.0.0.1:${port}`;
  const db=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const userIds=[];

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
        username:prefix+randomUUID().replaceAll("-","").slice(0,14),
        displayName:"Push Test",
        password:"push-test-"+randomUUID()
      }
    });
    assert.equal(r.status,201);
    userIds.push(r.body.user.id);
    return r.body;
  }

  async function registerPush(account,label){
    const token=("fcm-"+label+"-"+randomUUID().replaceAll("-","")).padEnd(40,"x");
    const r=await request("/api/devices/push",{
      token:account.token,
      method:"POST",
      body:{platform:"android",token}
    });
    assert.equal(r.status,200);
    return token;
  }

  async function sendDirect(from,to,text){
    const r=await request("/api/messages",{
      token:from.token,
      method:"POST",
      body:{to:to.user.id,text,clientMessageId:randomUUID()}
    });
    assert.equal(r.status,201);
    return r.body;
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

    // Delivery endpoint is dark by default even though registration/schema exist.
    assert.equal((await request("/internal/push-dispatch")).status,404);

    const alice=await register("psha");
    const bob=await register("pshb");

    assert.equal((await request("/api/devices/push",{
      token:bob.token,method:"POST",
      body:{platform:"android",token:"short"}
    })).status,400);

    const aliceFcm=await registerPush(alice,"alice");
    const bobFcm=await registerPush(bob,"bob");
    assert.notEqual(aliceFcm,bobFcm);

    const direct=await sendDirect(alice,bob,"PRIVATE TEXT MUST NEVER ENTER FCM");
    const directJob=await db.query(
      `select message_id,group_message_id,recipient_id,status
       from push_outbox where message_id=$1 and recipient_id=$2`,
      [direct.id,bob.user.id]
    );
    assert.equal(directJob.rowCount,1);
    assert.equal(directJob.rows[0].group_message_id,null);
    assert.equal(directJob.rows[0].status,"pending");

    const directPayloads=[];
    const directDispatch=await dispatchPushBatch({
      db,
      send:async payload=>directPayloads.push(payload),
      max:10
    });
    assert.equal(directDispatch.sent,1);
    assert.equal(directPayloads.length,1);
    assert.equal(directPayloads[0].token,bobFcm);
    assert.deepEqual(directPayloads[0].data,{kind:"lumo_message"});
    assert.equal(
      JSON.stringify(directPayloads[0]).includes("PRIVATE TEXT"),
      false
    );

    // Read-before-dispatch turns a queued direct push into a drop.
    const readBeforePush=await sendDirect(alice,bob,"read before worker");
    assert.equal((await request("/api/messages/read",{
      token:bob.token,method:"POST",body:{ids:[readBeforePush.id]}
    })).status,200);
    const afterRead=[];
    await dispatchPushBatch({db,send:async p=>afterRead.push(p),max:10});
    assert.equal(afterRead.length,0);
    const readJob=await db.query(
      "select status,last_error_code from push_outbox where message_id=$1",
      [readBeforePush.id]
    );
    assert.equal(readJob.rows[0].status,"dropped");
    assert.equal(readJob.rows[0].last_error_code,"no_consent");

    const group=await request("/api/groups",{
      token:alice.token,method:"POST",body:{title:"Push privacy group"}
    });
    assert.equal(group.status,201);
    const groupId=group.body.id;
    assert.equal((await request("/api/groups/"+groupId+"/members",{
      token:alice.token,method:"POST",body:{userId:bob.user.id}
    })).status,201);

    const groupMessage=await request("/api/groups/"+groupId+"/messages",{
      token:alice.token,
      method:"POST",
      body:{
        text:"GROUP SECRET MUST NEVER ENTER FCM",
        clientMessageId:randomUUID()
      }
    });
    assert.equal(groupMessage.status,201);

    const groupJobs=await db.query(
      `select recipient_id,message_id,group_message_id
       from push_outbox where group_message_id=$1 order by recipient_id`,
      [groupMessage.body.id]
    );
    assert.deepEqual(
      groupJobs.rows.map(r=>r.recipient_id),
      [bob.user.id],
      "group sender must not receive a push for their own message"
    );
    assert.equal(groupJobs.rows[0].message_id,null);

    const groupPayloads=[];
    const groupDispatch=await dispatchPushBatch({
      db,send:async payload=>groupPayloads.push(payload),max:10
    });
    assert.equal(groupDispatch.sent,1);
    assert.equal(groupPayloads.length,1);
    assert.equal(groupPayloads[0].token,bobFcm);
    assert.deepEqual(groupPayloads[0].data,{kind:"lumo_message"});
    assert.equal(
      JSON.stringify(groupPayloads[0]).includes("GROUP SECRET"),
      false
    );

    // If a member is removed after enqueue and then rejoins, the new joined_at
    // is later than the old group message. Dispatch must not resurrect it.
    const oldGroupMessage=await request("/api/groups/"+groupId+"/messages",{
      token:alice.token,
      method:"POST",
      body:{text:"queued before removal",clientMessageId:randomUUID()}
    });
    assert.equal(oldGroupMessage.status,201);
    assert.equal((await request(
      "/api/groups/"+groupId+"/members/"+bob.user.id,
      {token:alice.token,method:"DELETE"}
    )).status,204);
    await new Promise(resolve=>setTimeout(resolve,10));
    assert.equal((await request("/api/groups/"+groupId+"/members",{
      token:alice.token,method:"POST",body:{userId:bob.user.id}
    })).status,201);

    const afterRejoin=[];
    await dispatchPushBatch({db,send:async p=>afterRejoin.push(p),max:10});
    assert.equal(afterRejoin.length,0);
    const rejoinJob=await db.query(
      "select status,last_error_code from push_outbox where group_message_id=$1",
      [oldGroupMessage.body.id]
    );
    assert.equal(rejoinJob.rows[0].status,"dropped");
    assert.equal(rejoinJob.rows[0].last_error_code,"no_consent");

    const newGroupMessage=await request("/api/groups/"+groupId+"/messages",{
      token:alice.token,
      method:"POST",
      body:{text:"visible after rejoin",clientMessageId:randomUUID()}
    });
    assert.equal(newGroupMessage.status,201);
    const afterRejoinNew=[];
    await dispatchPushBatch({db,send:async p=>afterRejoinNew.push(p),max:10});
    assert.equal(afterRejoinNew.length,1);
    assert.equal(afterRejoinNew[0].token,bobFcm);

    // Explicit opt-out immediately removes this session's device registration.
    assert.equal((await request("/api/devices/push",{
      token:bob.token,method:"DELETE"
    })).status,204);
    const noDevice=await db.query(
      "select 1 from push_devices where session_token=$1",
      [bob.token]
    );
    assert.equal(noDevice.rowCount,0);

    const noPushMessage=await request("/api/groups/"+groupId+"/messages",{
      token:alice.token,
      method:"POST",
      body:{text:"after opt out",clientMessageId:randomUUID()}
    });
    assert.equal(noPushMessage.status,201);
    const noPushJob=await db.query(
      "select 1 from push_outbox where group_message_id=$1 and recipient_id=$2",
      [noPushMessage.body.id,bob.user.id]
    );
    assert.equal(noPushJob.rowCount,0);
  }finally{
    child.kill("SIGTERM");
    if(child.exitCode===null)await new Promise(resolve=>{
      const timer=setTimeout(resolve,1500);
      child.once("exit",()=>{clearTimeout(timer);resolve();});
    });
    if(userIds.length){
      try{
        await db.query(
          "delete from chat_groups where owner_id=any($1::uuid[])",
          [userIds]
        );
        await db.query(
          "delete from users where id=any($1::uuid[])",
          [userIds]
        );
      }catch{}
    }
    await db.end();
  }
});
