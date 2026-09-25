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

test("direct user blocking is private, bidirectional for interaction, and reversible",{
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

  async function register(prefix,name){
    const password="block-"+randomUUID()+"-Secure";
    const username=prefix+randomUUID().replaceAll("-","").slice(0,14);
    const result=await request("/api/register",{
      method:"POST",
      body:{username,displayName:name,password}
    });
    assert.equal(result.status,201);
    userIds.push(result.body.user.id);
    return result.body;
  }

  async function send(token,to,text){
    return request("/api/messages",{
      method:"POST",token,
      body:{to,text,clientMessageId:randomUUID()}
    });
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(child.exitCode!==null)throw new Error("Server exited early");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const alice=await register("block_a_","Alice");
    const bob=await register("block_b_","Bob");
    const charlie=await register("block_c_","Charlie");

    const before=await send(alice.token,bob.user.id,"message before block");
    assert.equal(before.status,201);

    const selfBlock=await request("/api/blocks/"+alice.user.id,{
      method:"PUT",token:alice.token
    });
    assert.equal(selfBlock.status,400);

    const missing=await request("/api/blocks/"+randomUUID(),{
      method:"PUT",token:alice.token
    });
    assert.equal(missing.status,404);
    assert.equal(missing.body.error,"user_not_found");

    const blocked=await request("/api/blocks/"+bob.user.id,{
      method:"PUT",token:alice.token
    });
    assert.equal(blocked.status,200);
    assert.equal(blocked.body.blocked,true);

    const repeated=await request("/api/blocks/"+bob.user.id,{
      method:"PUT",token:alice.token
    });
    assert.equal(repeated.status,200);
    assert.equal(repeated.body.blocked,true);

    const status=await request("/api/blocks/"+bob.user.id,{token:alice.token});
    assert.equal(status.status,200);
    assert.equal(status.body.blocked,true);

    const aliceList=await request("/api/blocks",{token:alice.token});
    assert.equal(aliceList.status,200);
    assert.equal(aliceList.body.length,1);
    assert.equal(aliceList.body[0].id,bob.user.id);

    const bobList=await request("/api/blocks",{token:bob.token});
    assert.equal(bobList.status,200);
    assert.deepEqual(bobList.body,[],
      "the API must not reveal who has blocked the current user");

    const aliceToBob=await send(alice.token,bob.user.id,"blocked outbound");
    assert.equal(aliceToBob.status,403);
    assert.equal(aliceToBob.body.error,"user_blocked");

    const bobToAlice=await send(bob.token,alice.user.id,"blocked inbound");
    assert.equal(bobToAlice.status,403);
    assert.equal(bobToAlice.body.error,"user_blocked");

    const reaction=await request("/api/reactions/"+before.body.id,{
      method:"PUT",token:bob.token,body:{emoji:"👍"}
    });
    assert.equal(reaction.status,404,
      "blocked users may not add interaction to old direct messages");

    const charlieToAlice=await send(charlie.token,alice.user.id,"unrelated contact");
    assert.equal(charlieToAlice.status,201,
      "blocking Bob must not affect Charlie");

    const history=await request("/api/messages/"+bob.user.id,{token:alice.token});
    assert.equal(history.status,200);
    assert.ok(history.body.some(message=>message.id===before.body.id),
      "blocking must not silently erase existing history");

    const unblocked=await request("/api/blocks/"+bob.user.id,{
      method:"DELETE",token:alice.token
    });
    assert.equal(unblocked.status,204);

    const afterStatus=await request("/api/blocks/"+bob.user.id,{token:alice.token});
    assert.equal(afterStatus.status,200);
    assert.equal(afterStatus.body.blocked,false);

    const restored=await send(bob.token,alice.user.id,"works after unblock");
    assert.equal(restored.status,201);
  }finally{
    child.kill("SIGTERM");
    await new Promise(resolve=>{
      if(child.exitCode!==null||child.signalCode!==null)return resolve();
      child.once("exit",resolve);
      setTimeout(resolve,1200).unref();
    });
    if(userIds.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[userIds]);}
      catch{}
    }
    await pool.end();
  }
});
