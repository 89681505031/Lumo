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

test("message edit/delete/search stays sender-owned and conversation-scoped",{
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
    const r=await request("/api/register",{
      method:"POST",
      body:{
        username:prefix+randomUUID().replaceAll("-","").slice(0,14),
        displayName:"Mutation Test",
        password:"mutation-test-"+randomUUID()
      }
    });
    assert.equal(r.status,201);
    ids.push(r.body.user.id);
    return r.body;
  }

  async function send(token,to,text,replyToMessageId){
    return request("/api/messages",{
      token,method:"POST",
      body:{
        to,text,clientMessageId:randomUUID(),
        ...(replyToMessageId?{replyToMessageId}:{})
      }
    });
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(child.exitCode!==null)throw new Error("Mutation test server exited early");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const alice=await register("muta");
    const bob=await register("mutb");
    const charlie=await register("mutc");

    const caps=await request("/api/messages/capabilities",{token:alice.token});
    assert.equal(caps.status,200);
    assert.equal(caps.body.messageEdit,true);
    assert.equal(caps.body.messageDelete,true);
    assert.equal(caps.body.messageSearch,true);

    const original=await send(
      alice.token,bob.user.id,"Космический проект Альфа"
    );
    assert.equal(original.status,201);
    assert.equal(original.body.editedAt,null);
    assert.equal(original.body.deletedAt,null);

    const otherChat=await send(
      charlie.token,bob.user.id,"Космический проект Альфа из другого чата"
    );
    assert.equal(otherChat.status,201);

    const searchAlice=await request(
      "/api/messages/search/"+bob.user.id+"?q="+encodeURIComponent("проект Альфа"),
      {token:alice.token}
    );
    assert.equal(searchAlice.status,200);
    assert.deepEqual(searchAlice.body.map(x=>x.id),[original.body.id],
      "search cannot leak matching messages from another conversation");

    const recipientEdit=await request("/api/messages/"+original.body.id,{
      token:bob.token,method:"PATCH",body:{text:"Подмена"}
    });
    assert.equal(recipientEdit.status,404);

    const edited=await request("/api/messages/"+original.body.id,{
      token:alice.token,method:"PATCH",
      body:{text:"Космический проект Бета"}
    });
    assert.equal(edited.status,200);
    assert.equal(edited.body.text,"Космический проект Бета");
    assert.ok(edited.body.editedAt);
    assert.equal(edited.body.deletedAt,null);

    const oldSearch=await request(
      "/api/messages/search/"+bob.user.id+"?q="+encodeURIComponent("Альфа"),
      {token:alice.token}
    );
    assert.equal(oldSearch.status,200);
    assert.equal(oldSearch.body.length,0);
    const newSearch=await request(
      "/api/messages/search/"+bob.user.id+"?q="+encodeURIComponent("Бета"),
      {token:bob.token}
    );
    assert.equal(newSearch.status,200);
    assert.equal(newSearch.body[0].id,original.body.id);

    // SQL wildcard characters are ordinary search characters with strpos().
    const wildcard=await request(
      "/api/messages/search/"+bob.user.id+"?q="+encodeURIComponent("%_"),
      {token:alice.token}
    );
    assert.equal(wildcard.status,200);
    assert.deepEqual(wildcard.body,[]);

    const recipientDelete=await request("/api/messages/"+original.body.id,{
      token:bob.token,method:"DELETE"
    });
    assert.equal(recipientDelete.status,404);

    const deleted=await request("/api/messages/"+original.body.id,{
      token:alice.token,method:"DELETE"
    });
    assert.equal(deleted.status,200);
    assert.equal(deleted.body.text,"Сообщение удалено");
    assert.ok(deleted.body.deletedAt);

    const afterDeleteSearch=await request(
      "/api/messages/search/"+bob.user.id+"?q="+encodeURIComponent("Сообщение"),
      {token:bob.token}
    );
    assert.equal(afterDeleteSearch.status,200);
    assert.equal(afterDeleteSearch.body.some(x=>x.id===original.body.id),false,
      "deleted messages are excluded from search");

    const replyToDeleted=await send(
      bob.token,alice.user.id,"Ответ на удалённое",original.body.id
    );
    assert.equal(replyToDeleted.status,404);
    assert.equal(replyToDeleted.body.error,"reply_message_not_found");

    const history=await request("/api/messages/"+alice.user.id,{token:bob.token});
    assert.equal(history.status,200);
    const tombstone=history.body.find(x=>x.id===original.body.id);
    assert.ok(tombstone);
    assert.equal(tombstone.text,"Сообщение удалено");
    assert.ok(tombstone.deletedAt);

    const unrelatedEdit=await request("/api/messages/"+otherChat.body.id,{
      token:alice.token,method:"PATCH",body:{text:"Не мой текст"}
    });
    assert.equal(unrelatedEdit.status,404);
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
