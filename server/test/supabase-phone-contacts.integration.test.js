import test from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import pg from "pg";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

function shaPhone(phone){
  return createHash("sha256").update(phone).digest("hex");
}

async function listen(server){
  await new Promise((resolve,reject)=>
    server.once("error",reject).listen(0,"127.0.0.1",resolve));
  return server.address().port;
}

test("Supabase phone exchange and saved-contact discovery stay scoped and private",{
  skip:!databaseUrl,
  timeout:35_000
},async()=>{
  const identities=new Map();
  const aliceSupabaseId=randomUUID();
  const bobSupabaseId=randomUUID();
  const charlieSupabaseId=randomUUID();
  const alicePhone="+48111111111";
  const bobPhone="+48222222222";
  identities.set("token-alice-"+randomUUID(),{id:aliceSupabaseId,phone:alicePhone});
  identities.set("token-bob-"+randomUUID(),{id:bobSupabaseId,phone:bobPhone});
  identities.set("token-charlie-"+randomUUID(),{id:charlieSupabaseId,phone:bobPhone});
  const tokens=[...identities.keys()];

  const authServer=createServer((req,res)=>{
    if(req.url!=="/auth/v1/user" || req.headers.apikey!=="sb_publishable_test"){
      res.writeHead(404).end();
      return;
    }
    const raw=String(req.headers.authorization||"");
    const token=raw.startsWith("Bearer ")?raw.slice(7):"";
    const identity=identities.get(token);
    if(!identity){
      res.writeHead(401,{"content-type":"application/json"});
      res.end(JSON.stringify({error:"invalid_token"}));
      return;
    }
    res.writeHead(200,{"content-type":"application/json"});
    res.end(JSON.stringify(identity));
  });
  const authPort=await listen(authServer);

  const appProbe=createServer();
  const appPort=await listen(appProbe);
  await new Promise(resolve=>appProbe.close(resolve));

  const child=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    env:{
      ...process.env,
      NODE_ENV:"test",
      PORT:String(appPort),
      DATABASE_URL:databaseUrl,
      DATABASE_SSL:"false",
      SUPABASE_URL:"http://127.0.0.1:"+authPort,
      SUPABASE_PUBLISHABLE_KEY:"sb_publishable_test"
    },
    stdio:"ignore"
  });
  const base="http://127.0.0.1:"+appPort;
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const createdIds=[];

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

  async function exchange(accessToken,displayName){
    return request("/api/auth/phone/exchange",{
      method:"POST",
      body:{
        accessToken,
        ...(displayName!==undefined?{displayName}:{})
      }
    });
  }

  try{
    let ready=false;
    for(let i=0;i<140;i++){
      if(child.exitCode!==null)throw new Error("Server exited early");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const caps=await request("/api/capabilities");
    assert.equal(caps.status,200);
    assert.equal(caps.body.phoneAuth,true);
    assert.equal(caps.body.contactDiscovery,true);

    const needsProfile=await exchange(tokens[0]);
    assert.equal(needsProfile.status,409);
    assert.equal(needsProfile.body.error,"profile_required");

    const alice=await exchange(tokens[0],"Alice Phone");
    assert.equal(alice.status,201);
    assert.equal(alice.body.isNew,true);
    createdIds.push(alice.body.user.id);

    const bob=await exchange(tokens[1],"Bob Phone");
    assert.equal(bob.status,201);
    createdIds.push(bob.body.user.id);

    const repeatAlice=await exchange(tokens[0],"Different Name");
    assert.equal(repeatAlice.status,200);
    assert.equal(repeatAlice.body.isNew,false);
    assert.equal(repeatAlice.body.user.id,alice.body.user.id);
    assert.equal(repeatAlice.body.user.displayName,"Alice Phone");

    const invalid=await exchange("invalid-token-that-is-long-enough-for-validation");
    assert.equal(invalid.status,401);
    assert.equal(invalid.body.error,"invalid_phone_session");

    const collision=await exchange(tokens[2],"Charlie");
    assert.equal(collision.status,409);
    assert.equal(collision.body.error,"phone_identity_conflict");

    const unknownHash=shaPhone("+48999999999");
    const discovery=await request("/api/contacts/discover",{
      method:"POST",
      token:alice.body.token,
      body:{hashes:[shaPhone(bobPhone),unknownHash]}
    });
    assert.equal(discovery.status,200);
    assert.equal(discovery.body.length,1);
    assert.equal(discovery.body[0].contactHash,shaPhone(bobPhone));
    assert.equal(discovery.body[0].user.id,bob.body.user.id);

    const invalidHashes=await request("/api/contacts/discover",{
      method:"POST",
      token:alice.body.token,
      body:{hashes:["raw-phone-number"]}
    });
    assert.equal(invalidHashes.status,400);
    assert.equal(invalidHashes.body.error,"invalid_contact_hashes");

    const block=await request("/api/blocks/"+bob.body.user.id,{
      method:"PUT",
      token:alice.body.token
    });
    assert.equal(block.status,200);

    const hidden=await request("/api/contacts/discover",{
      method:"POST",
      token:alice.body.token,
      body:{hashes:[shaPhone(bobPhone)]}
    });
    assert.equal(hidden.status,200);
    assert.deepEqual(hidden.body,[]);

    const row=await pool.query(
      "select supabase_user_id,phone_hash,password_hash from users where id=$1",
      [alice.body.user.id]
    );
    assert.equal(String(row.rows[0].supabase_user_id),aliceSupabaseId);
    assert.equal(row.rows[0].phone_hash,shaPhone(alicePhone));
    assert.equal(row.rows[0].password_hash,null);
  }finally{
    child.kill("SIGTERM");
    await new Promise(resolve=>{
      if(child.exitCode!==null||child.signalCode!==null)return resolve();
      child.once("exit",resolve);
      setTimeout(resolve,1200).unref();
    });
    await new Promise(resolve=>authServer.close(resolve));
    if(createdIds.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[createdIds]);}
      catch{}
    }
    await pool.end();
  }
});
