import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer as createNetServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

test("Postgres inline media fallback works without S3 and remains participant-only",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  const unused=createNetServer();
  await new Promise(resolve=>unused.listen(0,"127.0.0.1",resolve));
  const port=unused.address().port;
  await new Promise(resolve=>unused.close(resolve));

  const server=spawn(process.execPath,["src/index.js"],{
    cwd:process.cwd(),
    stdio:"ignore",
    env:{
      ...process.env,
      PORT:String(port),
      DATABASE_URL:databaseUrl,
      DATABASE_SSL:"false",
      NODE_ENV:"test",
      MEDIA_ENABLE_UPLOADS:"true",
      MEDIA_INLINE_FALLBACK:"true",
      MEDIA_BUCKET:"",
      MEDIA_REGION:"",
      MEDIA_ACCESS_KEY_ID:"",
      MEDIA_SECRET_ACCESS_KEY:"",
      MEDIA_ENDPOINT:""
    }
  });
  const db=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const base=`http://127.0.0.1:${port}`;
  const ids=[];

  async function request(path,method="GET",token=null,data=null,headers={}){
    const response=await fetch(base+path,{
      method,
      headers:{
        ...(token?{Authorization:"Bearer "+token}:{}),
        ...(data!==null && !Buffer.isBuffer(data)?{"Content-Type":"application/json"}:{}),
        ...headers
      },
      ...(data!==null?{
        body:Buffer.isBuffer(data)?data:JSON.stringify(data)
      }:{})
    });
    const raw=await response.text();
    let json=null;
    if(raw){
      try{json=JSON.parse(raw)}catch{}
    }
    return {status:response.status,json,raw,headers:response.headers};
  }

  async function register(prefix){
    const result=await request("/api/register","POST",null,{
      username:prefix+randomUUID().replaceAll("-","").slice(0,12),
      displayName:"Inline Media Test",
      password:"inline-media-"+randomUUID()
    });
    assert.equal(result.status,201);
    ids.push(result.json.user.id);
    return result.json;
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(server.exitCode!==null)throw new Error("Inline media API process exited");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const caps=await request("/api/capabilities");
    assert.equal(caps.status,200);
    assert.equal(caps.json.mediaReady,true);
    assert.equal(caps.json.mediaStorageReady,false);
    assert.equal(caps.json.mediaInlineReady,true);
    assert.equal(caps.json.mediaMode,"inline");
    assert.equal(caps.json.mediaInlineMaxBytes,4*1024*1024);
    assert.equal(caps.json.groupAttachments,true);

    const alice=await register("ina");
    const bob=await register("inb");
    const stranger=await register("inc");
    const bytes=Buffer.from("hello inline media");

    const tooLarge=await request("/api/media/init","POST",alice.token,{
      to:bob.user.id,
      mime:"image/jpeg",
      bytes:4*1024*1024+1,
      filename:"large.jpg"
    });
    assert.equal(tooLarge.status,413);
    assert.equal(tooLarge.json.error,"inline_media_too_large");

    const init=await request("/api/media/init","POST",alice.token,{
      to:bob.user.id,
      mime:"text/plain",
      bytes:bytes.length,
      filename:"notes.txt"
    });
    assert.equal(init.status,201);
    assert.equal(init.json.uploadMode,"inline");
    assert.equal(init.json.uploadUrl,null);
    assert.equal(init.json.maxBytes,2*1024*1024);

    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/content","PUT",alice.token,bytes,
      {"Content-Type":"application/octet-stream"}
    )).status,409,"declared MIME must match reservation");

    const uploaded=await request(
      "/api/media/"+init.json.assetId+"/content","PUT",alice.token,bytes,
      {"Content-Type":"text/plain"}
    );
    assert.equal(uploaded.status,200);
    assert.equal(uploaded.json.uploaded,true);

    const complete=await request(
      "/api/media/"+init.json.assetId+"/complete","POST",alice.token
    );
    assert.equal(complete.status,200);

    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",bob.token
    )).status,404,"recipient cannot read media before it is sent");

    const sent=await request(
      "/api/media/"+init.json.assetId+"/send","POST",alice.token,{
        clientMessageId:randomUUID(),
        caption:"Inline note"
      }
    );
    assert.equal(sent.status,201);
    assert.equal(sent.json.attachmentId,init.json.assetId);

    const link=await request(
      "/api/media/"+init.json.assetId+"/download","GET",bob.token
    );
    assert.equal(link.status,200);
    assert.match(link.json.url,/^\/api\/media\/content\/[0-9a-f-]+$/i);
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",stranger.token
    )).status,404);

    const publicGet=await fetch(base+link.json.url);
    assert.equal(publicGet.status,200);
    assert.match(
      publicGet.headers.get("content-type")||"",
      /^text\/plain(?:;|$)/
    );
    assert.deepEqual(Buffer.from(await publicGet.arrayBuffer()),bytes);

    const ranged=await fetch(base+link.json.url,{headers:{Range:"bytes=0-4"}});
    assert.equal(ranged.status,206);
    assert.equal(await ranged.text(),"hello");
    assert.equal(ranged.headers.get("content-range"),`bytes 0-4/${bytes.length}`);

    const head=await fetch(base+link.json.url,{method:"HEAD"});
    assert.equal(head.status,200);
    assert.equal(Number(head.headers.get("content-length")),bytes.length);

    const token=link.json.url.split("/").pop();
    await db.query(
      "update media_download_tokens set expires_at=now()-interval '1 second' where token=$1",
      [token]
    );
    assert.equal((await fetch(base+link.json.url)).status,404);

    const group=await request("/api/groups","POST",alice.token,{title:"Inline group"});
    assert.equal(group.status,201);
    assert.equal((await request(
      "/api/groups/"+group.json.id+"/members","POST",alice.token,{userId:bob.user.id}
    )).status,201);
    const groupBytes=Buffer.from("group inline");
    const groupInit=await request(
      "/api/groups/"+group.json.id+"/media/init","POST",alice.token,{
        mime:"text/plain",bytes:groupBytes.length,filename:"group.txt"
      }
    );
    assert.equal(groupInit.status,201);
    assert.equal(groupInit.json.uploadMode,"inline");
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/content","PUT",alice.token,groupBytes,
      {"Content-Type":"text/plain"}
    )).status,200);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/complete","POST",alice.token
    )).status,200);
    const groupSent=await request(
      "/api/groups/"+group.json.id+"/media/"+groupInit.json.assetId+"/send",
      "POST",alice.token,{clientMessageId:randomUUID(),caption:"Group inline"}
    );
    assert.equal(groupSent.status,201);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",bob.token
    )).status,200);
  }finally{
    server.kill("SIGTERM");
    if(server.exitCode===null)await new Promise(resolve=>{
      const timer=setTimeout(resolve,1500);
      server.once("exit",()=>{clearTimeout(timer);resolve();});
    });
    if(ids.length){
      try{await db.query("delete from users where id=any($1::uuid[])",[ids]);}
      catch{}
    }
    await db.end();
  }
});
