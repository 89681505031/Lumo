import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";

const dbUrl=process.env.LUMO_TEST_DATABASE_URL;

test("feature-flagged direct-message reactions: authorization, persistence and idempotence", {
  skip:!dbUrl,
  timeout:35_000
},async()=>{
  const listener=createServer();
  await new Promise((resolve,reject)=>
    listener.once("error",reject).listen(0,"127.0.0.1",resolve));
  const port=listener.address().port;
  await new Promise(resolve=>listener.close(resolve));
  const processServer=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    env:{...process.env,PORT:String(port),DATABASE_URL:dbUrl,
      DATABASE_SSL:"false"},
    stdio:"ignore"
  });
  const base=`http://127.0.0.1:${port}`;
  const pool=new pg.Pool({connectionString:dbUrl,ssl:false});
  const accounts=[];
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
  try{
    let healthy=false;
    for(let i=0;i<125;i++){
      if(processServer.exitCode!==null)throw Error("Server exited before health");
      try{
        const r=await request("/health");
        if(r.status===200){healthy=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,110));
    }
    assert.equal(healthy,true,"staging test server should initialize PostgreSQL");
    async function createUser(){
      const r=await request("/api/register",{
        method:"POST",body:{
          username:"r"+randomUUID().replaceAll("-","").slice(0,19),
          displayName:"Test Reactions",password:"reaction-test-"+randomUUID()
        }
      });
      assert.equal(r.status,201);
      accounts.push(r.body.user.id);
      return r.body;
    }
    const alice=await createUser(),bob=await createUser(),stranger=await createUser();
    const caps=await request("/api/reactions/capabilities",{token:alice.token});
    assert.equal(caps.status,200);
    assert.equal(caps.body.enabled,true);
    assert.deepEqual(caps.body.emojis,["👍","❤️","😂","😮","👏","🚀"]);
    assert.equal((await request("/api/reactions/capabilities")).status,401);

    const sent=await request("/api/messages",{
      method:"POST",token:alice.token,body:{
        to:bob.user.id,text:"Only direct participants should see reactions",
        clientMessageId:randomUUID()
      }
    });
    assert.equal(sent.status,201);
    const id=sent.body.id;

    // A stranger knowing a real UUID may neither add nor remove reactions.
    assert.equal((await request("/api/reactions/"+id,{
      token:stranger.token,method:"PUT",body:{emoji:"❤️"}
    })).status,404);
    assert.equal((await request("/api/reactions/"+id,{
      token:stranger.token,method:"DELETE",body:{emoji:"❤️"}
    })).status,404);
    assert.deepEqual((await request("/api/reactions/with/"+bob.user.id,{
      token:stranger.token
    })).body,[]);

    assert.equal((await request("/api/reactions/"+id,{
      token:alice.token,method:"PUT",body:{emoji:"not-a-reaction"}
    })).status,400);
    assert.equal((await request("/api/reactions/no-such-id",{
      token:alice.token,method:"PUT",body:{emoji:"❤️"}
    })).status,400);
    const add=await request("/api/reactions/"+id,{
      token:alice.token,method:"PUT",body:{emoji:"❤️"}
    });
    assert.equal(add.status,200);
    assert.equal(add.body.active,true);
    assert.equal((await request("/api/reactions/"+id,{
      token:alice.token,method:"PUT",body:{emoji:"❤️"}
    })).status,200,"Retry must not create a duplicate");
    assert.equal((await request("/api/reactions/"+id,{
      token:bob.token,method:"PUT",body:{emoji:"❤️"}
    })).status,200,"Both participants may react independently");
    let listed=await request("/api/reactions/with/"+bob.user.id,{token:alice.token});
    assert.equal(listed.status,200);
    assert.equal(listed.body.length,2);
    assert.deepEqual(new Set(listed.body.map(r=>r.userId)),
      new Set([alice.user.id,bob.user.id]));
    assert.deepEqual(listed.body.map(r=>r.messageId),[id,id]);

    assert.equal((await request("/api/reactions/"+id,{
      token:alice.token,method:"DELETE",body:{emoji:"❤️"}
    })).status,204);
    assert.equal((await request("/api/reactions/"+id,{
      token:alice.token,method:"DELETE",body:{emoji:"❤️"}
    })).status,204,"Retry of removal must remain harmless");
    listed=await request("/api/reactions/with/"+alice.user.id,{token:bob.token});
    assert.equal(listed.body.length,1);
    assert.equal(listed.body[0].userId,bob.user.id);
    assert.equal((await request("/api/reactions/"+randomUUID(),{
      token:alice.token,method:"PUT",body:{emoji:"🚀"}
    })).status,404);
    await request("/api/logout",{method:"POST",token:alice.token});
    assert.equal((await request("/api/reactions/with/"+bob.user.id,{
      token:alice.token
    })).status,401);
  }finally{
    processServer.kill("SIGTERM");
    await new Promise(resolve=>{
      if(processServer.exitCode!==null||processServer.signalCode!==null)return resolve();
      processServer.once("exit",resolve);
      setTimeout(resolve,1500).unref();
    });
    if(accounts.length){
      try{
        await pool.query("delete from users where id=any($1::uuid[])",[accounts]);
      }catch{/* Avoid masking a test assertion with fixture cleanup. */}
    }
    await pool.end();
  }
});
