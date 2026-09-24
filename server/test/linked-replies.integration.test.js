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

test("linked replies stay inside one direct conversation and remain idempotent",{
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
    const result=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,16),
        displayName:"Reply Test",
        password:"reply-test-"+randomUUID()
      }
    });
    assert.equal(result.status,201);
    ids.push(result.body.user.id);
    return result.body;
  }

  async function send(token,to,text,clientMessageId=randomUUID(),replyToMessageId){
    return request("/api/messages",{
      token,method:"POST",
      body:{
        to,text,clientMessageId,
        ...(replyToMessageId?{replyToMessageId}:{})
      }
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

    const alice=await register("rep_a_");
    const bob=await register("rep_b_");
    const charlie=await register("rep_c_");

    const caps=await request("/api/messages/capabilities",{token:alice.token});
    assert.equal(caps.status,200);
    assert.equal(caps.body.linkedReplies,true);

    const original=await send(alice.token,bob.user.id,"Первое сообщение");
    assert.equal(original.status,201);
    assert.equal(original.body.replyToMessageId,null);

    const replyId=randomUUID();
    const reply=await send(
      bob.token,alice.user.id,"Связанный ответ",replyId,original.body.id
    );
    assert.equal(reply.status,201);
    assert.equal(reply.body.replyToMessageId,original.body.id);
    assert.equal(reply.body.replyPreviewText,"Первое сообщение");
    assert.equal(reply.body.replyPreviewFrom,alice.user.id);

    const retry=await send(
      bob.token,alice.user.id,"Связанный ответ",replyId,original.body.id
    );
    assert.equal(retry.status,200,"same client UUID + same reply is idempotent");
    assert.equal(retry.body.id,reply.body.id);
    assert.equal(retry.body.replyToMessageId,original.body.id);

    const other=await send(alice.token,bob.user.id,"Другой текст");
    assert.equal(other.status,201);
    const conflict=await send(
      bob.token,alice.user.id,"Связанный ответ",replyId,other.body.id
    );
    assert.equal(conflict.status,409,
      "same idempotency key cannot silently change reply target");

    const charlieMessage=await send(
      charlie.token,alice.user.id,"Чужой диалог"
    );
    assert.equal(charlieMessage.status,201);
    const crossConversation=await send(
      bob.token,alice.user.id,"Нельзя отвечать на чужой чат",
      randomUUID(),charlieMessage.body.id
    );
    assert.equal(crossConversation.status,404);
    assert.equal(crossConversation.body.error,"reply_message_not_found");

    const malformed=await send(
      bob.token,alice.user.id,"Bad reply id",randomUUID(),"not-a-uuid"
    );
    assert.equal(malformed.status,400);
    assert.equal(malformed.body.error,"invalid_reply_message_id");

    const history=await request("/api/messages/with/"+bob.user.id,{
      token:alice.token
    });
    assert.equal(history.status,200);
    const linked=history.body.find(m=>m.id===reply.body.id);
    assert.ok(linked);
    assert.equal(linked.replyToMessageId,original.body.id);
    assert.equal(linked.replyPreviewText,"Первое сообщение");
    assert.equal(linked.replyPreviewFrom,alice.user.id);

    const plain=await send(bob.token,alice.user.id,"Обычное сообщение");
    assert.equal(plain.status,201);
    assert.equal(plain.body.replyToMessageId,null,
      "older/plain message behavior stays additive");
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
