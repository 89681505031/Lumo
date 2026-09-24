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

test("group history cursor pagination is stable, bounded and membership-window safe",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  const port=await freePort();
  const child=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    stdio:"ignore",
    env:{...process.env,PORT:String(port),DATABASE_URL:databaseUrl,DATABASE_SSL:"false"}
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
    const r=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,14),
        displayName:"Pagination Test",
        password:"pagination-test-"+randomUUID()
      }
    });
    assert.equal(r.status,201);
    userIds.push(r.body.user.id);
    return r.body;
  }

  async function insertBatch(ownerId,count,prefix,baseOffsetMinutes){
    const ids=Array.from({length:count},()=>randomUUID());
    const clientIds=Array.from({length:count},()=>randomUUID());
    const texts=Array.from({length:count},(_,i)=>`${prefix}-${String(i+1).padStart(3,"0")}`);
    await pool.query(`
      insert into chat_group_messages(
        id,group_id,sender_id,text,client_message_id,created_at
      )
      select x.id,$1,$2,x.text,x.client_id,
        now()+($6::integer*interval '1 minute')+(x.ord*interval '1 millisecond')
      from unnest($3::uuid[],$4::uuid[],$5::text[])
        with ordinality as x(id,client_id,text,ord)`,
      [groupId,ownerId,ids,clientIds,texts,baseOffsetMinutes]
    );
    return ids;
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(child.exitCode!==null)throw new Error("Pagination server exited early");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const owner=await register("pageo");
    const late=await register("pagel");
    const outsider=await register("pagex");

    const caps=await request("/api/capabilities");
    assert.equal(caps.status,200);
    assert.equal(caps.body.groupHistoryPagination,true);

    const created=await request("/api/groups",{
      token:owner.token,method:"POST",body:{title:"Paginated Group"}
    });
    assert.equal(created.status,201);
    groupId=created.body.id;

    // Put the owner's membership safely before the synthetic old history.
    await pool.query(
      "update chat_group_members set joined_at=now()-interval '2 hours' where group_id=$1 and user_id=$2",
      [groupId,owner.user.id]
    );
    await insertBatch(owner.user.id,125,"old",-60);

    const regular=await request("/api/groups/"+groupId+"/messages",{token:owner.token});
    assert.equal(regular.status,200);
    assert.equal(regular.body.length,100,"legacy history stays bounded to latest 100");
    assert.equal(regular.body[0].text,"old-026");
    assert.equal(regular.body.at(-1).text,"old-125");

    const first=await request(
      "/api/groups/"+groupId+"/messages/page?limit=50",
      {token:owner.token}
    );
    assert.equal(first.status,200);
    assert.equal(first.body.messages.length,50);
    assert.equal(first.body.messages[0].text,"old-076");
    assert.equal(first.body.messages.at(-1).text,"old-125");
    assert.ok(first.body.next?.createdAt);
    assert.ok(first.body.next?.id);

    const second=await request(
      "/api/groups/"+groupId+"/messages/page?limit=50"+
      "&beforeCreatedAt="+encodeURIComponent(first.body.next.createdAt)+
      "&beforeId="+first.body.next.id,
      {token:owner.token}
    );
    assert.equal(second.status,200);
    assert.equal(second.body.messages.length,50);
    assert.equal(second.body.messages[0].text,"old-026");
    assert.equal(second.body.messages.at(-1).text,"old-075");
    assert.ok(second.body.next);

    const third=await request(
      "/api/groups/"+groupId+"/messages/page?limit=50"+
      "&beforeCreatedAt="+encodeURIComponent(second.body.next.createdAt)+
      "&beforeId="+second.body.next.id,
      {token:owner.token}
    );
    assert.equal(third.status,200);
    assert.equal(third.body.messages.length,25);
    assert.equal(third.body.messages[0].text,"old-001");
    assert.equal(third.body.messages.at(-1).text,"old-025");
    assert.equal(third.body.next,null);

    const all=[
      ...third.body.messages,
      ...second.body.messages,
      ...first.body.messages
    ];
    assert.equal(all.length,125);
    assert.equal(new Set(all.map(m=>m.id)).size,125,"cursor pages never overlap");

    assert.equal((await request(
      "/api/groups/"+groupId+"/messages/page?limit=9",
      {token:owner.token}
    )).status,400);
    assert.equal((await request(
      "/api/groups/"+groupId+"/messages/page?limit=50&beforeCreatedAt="+
      encodeURIComponent(first.body.next.createdAt),
      {token:owner.token}
    )).status,400);
    assert.equal((await request(
      "/api/groups/"+groupId+"/messages/page?limit=50",
      {token:outsider.token}
    )).status,404);

    const invite=await request("/api/groups/"+groupId+"/members",{
      token:owner.token,method:"POST",body:{userId:late.user.id}
    });
    assert.equal(invite.status,201);

    // These messages are definitely after the late member joined.
    await new Promise(resolve=>setTimeout(resolve,20));
    await insertBatch(owner.user.id,12,"new",1);

    const latePage=await request(
      "/api/groups/"+groupId+"/messages/page?limit=50",
      {token:late.token}
    );
    assert.equal(latePage.status,200);
    assert.equal(latePage.body.messages.length,12);
    assert.equal(latePage.body.messages[0].text,"new-001");
    assert.equal(latePage.body.messages.at(-1).text,"new-012");
    assert.equal(latePage.body.next,null,
      "pagination cannot cross the current membership boundary");
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
