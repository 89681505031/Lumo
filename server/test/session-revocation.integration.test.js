import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
import { spawn } from "node:child_process";
import WebSocket from "ws";
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

test("revoke-others preserves the current session and revokes every other session",{
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
  let oldSocket;

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
    const password="session-test-"+randomUUID();
    const username=prefix+randomUUID().replaceAll("-","").slice(0,14);
    const result=await request("/api/register",{
      method:"POST",
      body:{username,displayName:"Session Test",password}
    });
    assert.equal(result.status,201);
    userIds.push(result.body.user.id);
    return {...result.body,password,username};
  }

  async function login(account){
    const result=await request("/api/login",{
      method:"POST",
      body:{username:account.username,password:account.password}
    });
    assert.equal(result.status,200);
    return result.body.token;
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

    const alice=await register("sess_a_");
    const bob=await register("sess_b_");
    const currentToken=await login(alice);
    const otherToken=await login(alice);

    assert.equal((await request("/api/me",{token:alice.token})).status,200);
    assert.equal((await request("/api/me",{token:currentToken})).status,200);
    assert.equal((await request("/api/me",{token:otherToken})).status,200);
    assert.equal((await request("/api/me",{token:bob.token})).status,200);

    oldSocket=new WebSocket(`ws://127.0.0.1:${port}/ws`,{
      headers:{Authorization:"Bearer "+otherToken}
    });
    const readyEvent=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error("Other session WebSocket did not become ready")),3000);
      oldSocket.once("message",data=>{
        clearTimeout(timer);
        resolve(JSON.parse(data.toString()));
      });
      oldSocket.once("error",error=>{clearTimeout(timer);reject(error);});
    });
    assert.equal(readyEvent.type,"ready");

    const closed=new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error("Revoked WebSocket stayed connected")),3000);
      oldSocket.once("close",(code)=>{
        clearTimeout(timer);
        resolve(code);
      });
      oldSocket.once("error",error=>{clearTimeout(timer);reject(error);});
    });

    const anonymous=await request("/api/sessions/revoke-others",{method:"POST"});
    assert.equal(anonymous.status,401);

    const revoked=await request("/api/sessions/revoke-others",{
      method:"POST",
      token:currentToken
    });
    assert.equal(revoked.status,200);
    assert.equal(revoked.body.revoked,2);
    assert.equal(await closed,1008);

    assert.equal((await request("/api/me",{token:currentToken})).status,200);
    assert.equal((await request("/api/me",{token:alice.token})).status,401);
    assert.equal((await request("/api/me",{token:otherToken})).status,401);
    assert.equal((await request("/api/me",{token:bob.token})).status,200,
      "revoking Alice sessions must not affect another account");

    const repeated=await request("/api/sessions/revoke-others",{
      method:"POST",
      token:currentToken
    });
    assert.equal(repeated.status,200);
    assert.equal(repeated.body.revoked,0);
  }finally{
    oldSocket?.terminate();
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
