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

test("profile bio is bounded, public and preserved by older display-name-only updates",{
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
  const ids=[];

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
    const password="profile-bio-"+randomUUID();
    const username=prefix+randomUUID().replaceAll("-","").slice(0,16);
    const result=await request("/api/register",{
      method:"POST",
      body:{username,displayName:"Profile Test",password}
    });
    assert.equal(result.status,201);
    ids.push(result.body.user.id);
    return {...result.body,password,username};
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

    const alice=await register("bio_a_");
    const bob=await register("bio_b_");

    assert.equal(alice.user.bio,"","new profiles expose an empty bounded bio");
    const bio="Люблю космос и создаю проекты в Lumo.";
    const changed=await request("/api/me",{
      token:alice.token,method:"PATCH",
      body:{displayName:"Alice Profile",bio}
    });
    assert.equal(changed.status,200);
    assert.equal(changed.body.displayName,"Alice Profile");
    assert.equal(changed.body.bio,bio);

    const me=await request("/api/me",{token:alice.token});
    assert.equal(me.status,200);
    assert.equal(me.body.bio,bio);

    const publicSearch=await request("/api/users?q=Alice",{token:bob.token});
    assert.equal(publicSearch.status,200);
    const alicePublic=publicSearch.body.find(u=>u.id===alice.user.id);
    assert.ok(alicePublic);
    assert.equal(alicePublic.bio,bio);

    // Older Android builds send only displayName. They must not wipe the new bio.
    const oldClient=await request("/api/me",{
      token:alice.token,method:"PATCH",
      body:{displayName:"Alice Renamed"}
    });
    assert.equal(oldClient.status,200);
    assert.equal(oldClient.body.bio,bio);

    const invalid=await request("/api/me",{
      token:alice.token,method:"PATCH",
      body:{displayName:"Alice Renamed",bio:"x".repeat(161)}
    });
    assert.equal(invalid.status,400);
    assert.equal(invalid.body.error,"invalid_bio");
    assert.equal((await request("/api/me",{token:alice.token})).body.bio,bio);

    await request("/api/logout",{method:"POST",token:alice.token});
    const logged=await request("/api/login",{
      method:"POST",
      body:{username:alice.username,password:alice.password}
    });
    assert.equal(logged.status,200);
    assert.equal(logged.body.user.bio,bio,"bio survives a fresh session/login");
  }finally{
    child.kill("SIGTERM");
    await new Promise(resolve=>{
      if(child.exitCode!==null||child.signalCode!==null)return resolve();
      child.once("exit",resolve);
      setTimeout(resolve,1200).unref();
    });
    if(ids.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[ids]);}
      catch{}
    }
    await pool.end();
  }
});
