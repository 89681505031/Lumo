import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:net";
import { createServer as createHttpServer } from "node:http";
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

test("Lumo AI is authenticated, explicit-context only and provider-key stays server-side",{
  skip:!databaseUrl,
  timeout:35_000
},async()=>{
  const providerPort=await freePort();
  const seen=[];
  const provider=createHttpServer(async(req,res)=>{
    const chunks=[];
    for await(const chunk of req)chunks.push(chunk);
    const raw=Buffer.concat(chunks).toString("utf8");
    seen.push({
      authorization:req.headers.authorization,
      path:req.url,
      body:JSON.parse(raw)
    });
    res.writeHead(200,{"Content-Type":"application/json"});
    res.end(JSON.stringify({
      choices:[{message:{role:"assistant",content:"Тестовый ответ Lumo AI"}}]
    }));
  });
  await new Promise((resolve,reject)=>
    provider.once("error",reject).listen(providerPort,"127.0.0.1",resolve));

  const port=await freePort();
  const child=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    env:{
      ...process.env,
      NODE_ENV:"test",
      PORT:String(port),
      DATABASE_URL:databaseUrl,
      DATABASE_SSL:"false",
      LUMO_AI_ENABLED:"true",
      LUMO_AI_PROVIDER_URL:`http://127.0.0.1:${providerPort}/v1/chat/completions`,
      LUMO_AI_PROVIDER_KEY:"server-only-test-key",
      LUMO_AI_MODEL:"test-model"
    },
    stdio:"ignore"
  });
  const base=`http://127.0.0.1:${port}`;
  const pool=new pg.Pool({connectionString:databaseUrl,ssl:false});
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

  async function createUser(prefix){
    const result=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,18),
        displayName:"AI Test",
        password:"ai-test-password-"+randomUUID()
      }
    });
    assert.equal(result.status,201);
    accounts.push(result.body.user.id);
    return result.body;
  }

  try{
    let healthy=false;
    for(let i=0;i<120;i++){
      if(child.exitCode!==null)throw new Error("Lumo server exited");
      try{
        const r=await request("/health");
        if(r.status===200){healthy=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(healthy,true);

    const alice=await createUser("ai_a_");
    const bob=await createUser("ai_b_");

    assert.equal((await request("/api/ai/capabilities")).status,401);
    const caps=await request("/api/ai/capabilities",{token:alice.token});
    assert.equal(caps.status,200);
    assert.equal(caps.body.enabled,true);
    assert.equal(caps.body.chatDataSharedAutomatically,false);

    // Store a normal private chat message with a sentinel. The AI route must not
    // query or forward it unless the user explicitly types it into the AI thread.
    const secret="PRIVATE_CHAT_SENTINEL_"+randomUUID();
    const sent=await request("/api/messages",{
      method:"POST",
      token:alice.token,
      body:{to:bob.user.id,text:secret,clientMessageId:randomUUID()}
    });
    assert.equal(sent.status,201);

    assert.equal((await request("/api/ai/chat",{
      method:"POST",token:alice.token,body:{message:"   "}
    })).status,400);

    const reply=await request("/api/ai/chat",{
      method:"POST",
      token:alice.token,
      body:{
        message:"Объясни коротко, что такое орбита.",
        history:[
          {role:"user",content:"Отвечай кратко."},
          {role:"assistant",content:"Хорошо."}
        ]
      }
    });
    assert.equal(reply.status,200);
    assert.equal(reply.body.reply,"Тестовый ответ Lumo AI");
    assert.equal(seen.length,1);
    assert.equal(seen[0].authorization,"Bearer server-only-test-key");
    assert.equal(seen[0].path,"/v1/chat/completions");
    const serialized=JSON.stringify(seen[0].body);
    assert.equal(serialized.includes(secret),false,
      "Normal Lumo chat content must not be copied to the AI provider");
    assert.equal(serialized.includes(alice.token),false,
      "Lumo login session token must not be sent to provider");
    assert.equal(seen[0].body.model,"test-model");
    assert.equal(seen[0].body.messages.at(-1).content,
      "Объясни коротко, что такое орбита.");

    const tooMuch=Array.from({length:9},(_,i)=>({
      role:i%2?"assistant":"user",content:"x"
    }));
    assert.equal((await request("/api/ai/chat",{
      method:"POST",token:alice.token,
      body:{message:"test",history:tooMuch}
    })).status,400);
    assert.equal(seen.length,1,"Invalid context must not reach provider");

    await request("/api/logout",{method:"POST",token:alice.token});
    assert.equal((await request("/api/ai/chat",{
      method:"POST",token:alice.token,body:{message:"after logout"}
    })).status,401);
  }finally{
    child.kill("SIGTERM");
    await new Promise(resolve=>{
      if(child.exitCode!==null||child.signalCode!==null)return resolve();
      child.once("exit",resolve);
      setTimeout(resolve,1500).unref();
    });
    await new Promise(resolve=>provider.close(resolve));
    if(accounts.length){
      try{await pool.query("delete from users where id=any($1::uuid[])",[accounts]);}
      catch{/* cleanup should not mask the assertion that failed */}
    }
    await pool.end();
  }
});
