import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { readFile } from "node:fs/promises";
import pg from "pg";
import WebSocket from "ws";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

test("private groups: durable membership, roles, history privacy and idempotent delivery",{
  skip:!databaseUrl,timeout:30000
},async()=>{
  async function unusedPort(){
    const s=createServer();
    await new Promise((resolve,reject)=>s.once("error",reject).listen(0,"127.0.0.1",resolve));
    const port=s.address().port;
    await new Promise(resolve=>s.close(resolve));
    return port;
  }
  const port=await unusedPort(),secondPort=await unusedPort();
  const children=[port,secondPort].map(p=>spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    env:{...process.env,PORT:String(p),DATABASE_URL:databaseUrl,DATABASE_SSL:"false"},
    stdio:"ignore"
  }));
  const base=`http://127.0.0.1:${port}`,replica=`http://127.0.0.1:${secondPort}`;
  let socket;
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
  async function request(path,method="GET",token=null,body=null,origin=base){
    const r=await fetch(origin+path,{
      method,
      headers:{...(token?{Authorization:"Bearer "+token}:{}),
        ...(body!==null?{"content-type":"application/json"}:{})},
      ...(body!==null?{body:JSON.stringify(body)}:{})
    });
    const raw=await r.text();
    return {status:r.status,json:raw?JSON.parse(raw):null};
  }
  try{
    for(const origin of [base,replica]){
      let ready=false;
      for(let attempt=0;attempt<120;attempt++){
        if(children.some(c=>c.exitCode!==null))throw new Error("Group test server exited");
        try{const r=await fetch(origin+"/health");if(r.ok){ready=true;break;}}catch{}
        await new Promise(resolve=>setTimeout(resolve,100));
      }
      assert.ok(ready,"Group server not ready: "+origin);
    }
    // Both migration paths must work when tables already exist.
    const ddl=await readFile(new URL("../db/schema.sql",import.meta.url),"utf8");
    await pool.query(ddl);
    await pool.query(ddl);
    const password="Test-"+randomUUID()+"-Secure";
    async function register(prefix){
      const r=await request("/api/register","POST",null,{
        username:prefix+randomUUID().slice(0,8),displayName:prefix,password
      });
      assert.equal(r.status,201);
      return r.json;
    }
    const owner=await register("ow"),admin=await register("ad"),
      member=await register("mb"),outsider=await register("ou"),blocked=await register("bl");
    assert.deepEqual((await request("/api/groups","GET",owner.token)).json,[]);
    assert.equal((await request("/api/groups","POST",owner.token,{title:" "})).status,400);
    assert.equal((await request("/api/groups","POST",null,{title:"Private group"})).status,401);
    const created=await request("/api/groups","POST",owner.token,{title:" Private group "});
    assert.equal(created.status,201);
    assert.equal(created.json.title,"Private group");
    assert.equal(created.json.memberCount,1);
    assert.equal(created.json.role,"owner");
    const id=created.json.id;
    const prefix="/api/groups/"+id;
    assert.equal((await request("/api/groups","GET",outsider.token)).json.length,0);
    assert.equal((await request(prefix,"GET",outsider.token)).status,404);
    assert.equal((await request(prefix+"/messages","GET",outsider.token)).status,404);
    assert.equal((await request(prefix+"/messages","POST",outsider.token,{
      text:"outsider",clientMessageId:randomUUID()
    })).status,404);
    assert.equal((await request(prefix+"/members","POST",outsider.token,{
      userId:member.user.id
    })).status,403);
    const badGroup=await request("/api/groups/not-a-uuid/messages","GET",owner.token);
    assert.equal(badGroup.status,400);
    assert.equal((await request(prefix+"/members","POST",owner.token,{
      userId:owner.user.id
    })).status,400);
    assert.equal((await request(prefix+"/members","POST",owner.token,{
      userId:randomUUID()
    })).status,404);
    await pool.query(
      "insert into user_blocks(blocker_id,blocked_id) values($1,$2) on conflict do nothing",
      [blocked.user.id,owner.user.id]
    );
    const blockedInvite=await request(prefix+"/members","POST",owner.token,{
      userId:blocked.user.id
    });
    assert.equal(blockedInvite.status,403);
    assert.equal(blockedInvite.json.error,"user_blocked");

    const messageId=randomUUID();
    const initial=await request(prefix+"/messages","POST",owner.token,{
      text:"Before anyone joined",clientMessageId:messageId
    },replica);
    assert.equal(initial.status,201);
    const retried=await request(prefix+"/messages","POST",owner.token,{
      text:"Before anyone joined",clientMessageId:messageId
    });
    assert.equal(retried.status,200);
    assert.equal(retried.json.id,initial.json.id);
    const conflict=await request(prefix+"/messages","POST",owner.token,{
      text:"Changed replay",clientMessageId:messageId
    });
    assert.equal(conflict.status,409);
    assert.equal(conflict.json.error,"client_message_id_conflict");
    assert.equal((await request(prefix+"/messages","POST",owner.token,{
      text:"",clientMessageId:randomUUID()
    })).status,400);
    assert.equal((await request(prefix+"/messages","POST",owner.token,{
      text:"x".repeat(4001),clientMessageId:randomUUID()
    })).status,400);

    const addedAdmin=await request(prefix+"/members","POST",owner.token,{
      userId:admin.user.id
    },replica);
    assert.equal(addedAdmin.status,201);
    assert.equal((await request(prefix+"/members","POST",owner.token,{
      userId:admin.user.id
    })).status,409);
    const joinedLater=await request(prefix+"/messages","GET",admin.token);
    assert.deepEqual(joinedLater.json,[],"Late-joining member cannot read old messages");
    assert.equal((await request(prefix+"/members/"+admin.user.id,"PATCH",admin.token,{
      role:"admin"
    })).status,403);
    const promote=await request(prefix+"/members/"+admin.user.id,"PATCH",owner.token,{
      role:"admin"
    },replica);
    assert.equal(promote.status,200);
    assert.equal(promote.json.role,"admin");
    assert.equal((await request(prefix+"/members/"+owner.user.id,"PATCH",owner.token,{
      role:"member"
    })).status,409);
    const groupDetails=await request(prefix,"GET",admin.token);
    assert.equal(groupDetails.status,200);
    assert.equal(groupDetails.json.members.find(u=>u.id===admin.user.id).role,"admin");
    assert.equal(groupDetails.json.memberCount,2);
    assert.ok((await request("/api/groups","GET",admin.token,null,replica)).json
      .some(g=>g.id===id && g.role==="admin"));
    assert.equal((await request(prefix+"/members","POST",admin.token,{
      userId:member.user.id
    })).status,201);

    socket=new WebSocket(`ws://127.0.0.1:${port}/ws`,{
      headers:{Authorization:"Bearer "+admin.token}
    });
    const ready=await new Promise((resolve,reject)=>{
      const t=setTimeout(()=>reject(new Error("Admin group socket unavailable")),4000);
      socket.once("message",raw=>{clearTimeout(t);resolve(JSON.parse(raw.toString()));});
      socket.once("error",reject);
    });
    assert.equal(ready.type,"ready");
    const notification=new Promise((resolve,reject)=>{
      const t=setTimeout(()=>reject(new Error("Missing same-instance group message push")),4000);
      const handler=raw=>{
        const event=JSON.parse(raw.toString());
        if(event.type==="group_message"){clearTimeout(t);socket.off("message",handler);resolve(event);}
      };
      socket.on("message",handler);
    });
    const sent=await request(prefix+"/messages","POST",owner.token,{
      text:"Everyone online now",clientMessageId:randomUUID()
    });
    assert.equal(sent.status,201);
    assert.equal((await notification).message.id,sent.json.id);
    const afterInvite=await request(prefix+"/messages","GET",member.token,null,replica);
    assert.equal(afterInvite.status,200);
    assert.equal(afterInvite.json.length,1);
    assert.equal(afterInvite.json[0].id,sent.json.id);
    const adminHistory=await request(prefix+"/messages","GET",admin.token);
    assert.equal(adminHistory.json.length,1,"Old messages remain inaccessible to invited members");
    const ownerHistory=await request(prefix+"/messages","GET",owner.token,null,replica);
    assert.equal(ownerHistory.json.length,2);
    assert.equal(ownerHistory.json[0].id,initial.json.id);
    const memberCannotInvite=await request(prefix+"/members","POST",member.token,{
      userId:outsider.user.id
    });
    assert.equal(memberCannotInvite.status,403);
    assert.equal((await request(prefix+"/members/"+admin.user.id,"DELETE",member.token)).status,403);
    assert.equal((await request(prefix+"/members/"+owner.user.id,"DELETE",admin.token)).status,409);
    assert.equal((await request(prefix+"/members/"+admin.user.id,"DELETE",admin.token)).status,204);
    assert.equal((await request(prefix+"/messages","GET",admin.token)).status,404);
    assert.equal((await request(prefix+"/members","POST",member.token,{userId:outsider.user.id})).status,403);
    assert.equal((await request(prefix+"/members/"+member.user.id,"DELETE",owner.token)).status,204);
    assert.equal((await request(prefix+"/messages","GET",member.token)).status,404);
    const newer=await request(prefix+"/messages","POST",owner.token,{
      text:"After people left",clientMessageId:randomUUID()
    });
    assert.equal(newer.status,201);
    const invitedAgain=await request(prefix+"/members","POST",owner.token,{
      userId:member.user.id
    });
    assert.equal(invitedAgain.status,201);
    const rejoinedHistory=await request(prefix+"/messages","GET",member.token);
    assert.deepEqual(rejoinedHistory.json,[],"Rejoining never reopens messages from prior membership");
    const detail=await request(prefix,"GET",member.token);
    assert.equal(detail.json.role,"member");
    assert.equal((await request(prefix+"/members/"+member.user.id,"PATCH",member.token,{
      role:"admin"
    })).status,403);
    assert.equal((await request(prefix,"DELETE",member.token)).status,404);
    assert.equal((await request(prefix,"DELETE",owner.token,null,replica)).status,204);
    assert.equal((await request(prefix,"GET",owner.token)).status,404);
    assert.deepEqual((await request("/api/groups","GET",member.token)).json,[]);
    const persisted=await pool.query("select 1 from chat_group_messages where group_id=$1",[id]);
    assert.equal(persisted.rowCount,0,"Deleting a group cascades through its messages");
  } finally {
    socket?.terminate();
    for(const child of children){
      child.kill("SIGTERM");
      if(child.exitCode===null)await new Promise(resolve=>{
        const timer=setTimeout(resolve,1500);
        child.once("exit",()=>{clearTimeout(timer);resolve();});
      });
    }
    await pool.end();
  }
});
