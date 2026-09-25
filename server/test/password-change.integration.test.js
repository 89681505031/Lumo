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

test("authenticated password change preserves current session and revokes old credentials",{
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

  async function register(prefix){
    const password="old-"+randomUUID()+"-Secure";
    const username=prefix+randomUUID().replaceAll("-","").slice(0,14);
    const result=await request("/api/register",{
      method:"POST",
      body:{username,displayName:"Password Test",password}
    });
    assert.equal(result.status,201);
    userIds.push(result.body.user.id);
    return {...result.body,password,username};
  }

  async function login(username,password){
    return request("/api/login",{
      method:"POST",
      body:{username,password}
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

    const alice=await register("pwd_a_");
    const bob=await register("pwd_b_");
    const current=(await login(alice.username,alice.password)).body.token;
    const other=(await login(alice.username,alice.password)).body.token;
    const newPassword="new-"+randomUUID()+"-Secure";

    const weak=await request("/api/account/password",{
      method:"POST",token:current,
      body:{currentPassword:alice.password,newPassword:"short"}
    });
    assert.equal(weak.status,400);
    assert.equal(weak.body.error,"invalid_new_password");

    const wrong=await request("/api/account/password",{
      method:"POST",token:current,
      body:{currentPassword:"wrong-"+randomUUID()+"-Secure",newPassword}
    });
    assert.equal(wrong.status,403);
    assert.equal(wrong.body.error,"current_password_incorrect");

    const unchanged=await request("/api/account/password",{
      method:"POST",token:current,
      body:{currentPassword:alice.password,newPassword:alice.password}
    });
    assert.equal(unchanged.status,409);
    assert.equal(unchanged.body.error,"password_unchanged");

    const changed=await request("/api/account/password",{
      method:"POST",token:current,
      body:{currentPassword:alice.password,newPassword}
    });
    assert.equal(changed.status,200);
    assert.equal(changed.body.changed,true);
    assert.equal(changed.body.revoked,2);

    assert.equal((await request("/api/me",{token:current})).status,200);
    assert.equal((await request("/api/me",{token:alice.token})).status,401);
    assert.equal((await request("/api/me",{token:other})).status,401);
    assert.equal((await request("/api/me",{token:bob.token})).status,200);

    const oldLogin=await login(alice.username,alice.password);
    assert.equal(oldLogin.status,401);
    assert.equal(oldLogin.body.error,"invalid_credentials");

    const newLogin=await login(alice.username,newPassword);
    assert.equal(newLogin.status,200);
    assert.equal(newLogin.body.user.id,alice.user.id);
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
