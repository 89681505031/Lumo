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

function spawnServer(port){
  return spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    stdio:"ignore",
    env:{
      ...process.env,
      PORT:String(port),
      DATABASE_URL:databaseUrl,
      DATABASE_SSL:"false"
    }
  });
}

async function waitReady(child,base){
  for(let i=0;i<120;i++){
    if(child.exitCode!==null)throw new Error("Pagination test server exited early");
    try{
      const r=await fetch(base+"/health");
      if(r.ok)return;
    }catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw new Error("Pagination test server did not become healthy");
}

test("direct history pagination is stable for equal timestamps and conversation-scoped",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  const port=await freePort();
  const child=spawnServer(port);
  const base=`http://127.0.0.1:${port}`;
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
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
    const result=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,14),
        displayName:"Pagination Test",
        password:"pagination-test-"+randomUUID()
      }
    });
    assert.equal(result.status,201);
    userIds.push(result.body.user.id);
    return result.body;
  }

  try{
    await waitReady(child,base);
    const alice=await register("paga");
    const bob=await register("pagb");
    const charlie=await register("pagc");

    const caps=await request("/api/capabilities");
    assert.equal(caps.status,200);
    assert.equal(caps.body.directPagination,true);

    const sameTime="2026-09-25 10:00:00.123456+00";
    const ids=[];
    for(let i=0;i<125;i++){
      const id=randomUUID();
      ids.push(id);
      await pool.query(
        `insert into messages(id,sender_id,recipient_id,text,created_at)
         values($1,$2,$3,$4,$5)`,
        [id,alice.user.id,bob.user.id,"message-"+i,sameTime]
      );
    }
    const unrelatedId=randomUUID();
    await pool.query(
      `insert into messages(id,sender_id,recipient_id,text,created_at)
       values($1,$2,$3,$4,$5)`,
      [unrelatedId,alice.user.id,charlie.user.id,"other conversation",sameTime]
    );

    assert.equal(
      (await request("/api/messages/"+bob.user.id+"/page")).status,
      401
    );

    const first=await request(
      "/api/messages/"+bob.user.id+"/page?limit=50",
      {token:alice.token}
    );
    assert.equal(first.status,200);
    assert.equal(first.body.messages.length,50);
    assert.ok(first.body.next?.id);

    const second=await request(
      "/api/messages/"+bob.user.id+"/page?limit=50&beforeId="+
        encodeURIComponent(first.body.next.id),
      {token:alice.token}
    );
    assert.equal(second.status,200);
    assert.equal(second.body.messages.length,50);
    assert.ok(second.body.next?.id);

    const third=await request(
      "/api/messages/"+bob.user.id+"/page?limit=50&beforeId="+
        encodeURIComponent(second.body.next.id),
      {token:alice.token}
    );
    assert.equal(third.status,200);
    assert.equal(third.body.messages.length,25);
    assert.equal(third.body.next,null);

    const all=[
      ...first.body.messages,
      ...second.body.messages,
      ...third.body.messages
    ];
    assert.equal(all.length,125);
    assert.equal(new Set(all.map(m=>m.id)).size,125);
    assert.deepEqual(
      new Set(all.map(m=>m.id)),
      new Set(ids),
      "all conversation messages are returned exactly once"
    );

    const outsiderCursor=await request(
      "/api/messages/"+bob.user.id+"/page?beforeId="+unrelatedId,
      {token:alice.token}
    );
    assert.equal(outsiderCursor.status,400);
    assert.equal(outsiderCursor.body.error,"history_cursor_not_found");

    const malformed=await request(
      "/api/messages/"+bob.user.id+"/page?beforeId=not-a-uuid",
      {token:alice.token}
    );
    assert.equal(malformed.status,400);
    assert.equal(malformed.body.error,"invalid_history_cursor");

    const oversized=await request(
      "/api/messages/"+bob.user.id+"/page?limit=101",
      {token:alice.token}
    );
    assert.equal(oversized.status,400);
    assert.equal(oversized.body.error,"invalid_history_limit");
  }finally{
    child.kill("SIGTERM");
    if(child.exitCode===null&&child.signalCode===null){
      await new Promise(resolve=>{
        child.once("exit",resolve);
        setTimeout(resolve,1200).unref();
      });
    }
    if(userIds.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[userIds]);}
      catch{}
    }
    await pool.end();
  }
});
