import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer as createHttpServer } from "node:http";
import { createServer as createNetServer } from "node:net";
import { spawn } from "node:child_process";
import pg from "pg";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

test("private document attachments are allowlisted, signed, participant-only and idempotent",{
  skip:!databaseUrl,
  timeout:30_000
},async()=>{
  const storage=createHttpServer();
  const uploaded=new Map();
  let expectedUploadKey=null;
  let expectedMime="application/pdf";
  let expectedBytes=Buffer.from("%PDF-1.4\nLumo private document test\n%%EOF");

  storage.on("request",(req,res)=>{
    const path=new URL(req.url,"http://localhost").pathname;
    const bucket="/private-test";
    if(req.method==="POST" && (path===bucket || path===bucket+"/")){
      const chunks=[];
      req.on("data",chunk=>chunks.push(chunk));
      req.on("end",()=>{
        const body=Buffer.concat(chunks);
        if(!expectedUploadKey || !body.includes(expectedBytes)){
          res.writeHead(400);return res.end("bad multipart upload");
        }
        uploaded.set(expectedUploadKey,{mime:expectedMime,bytes:expectedBytes});
        res.writeHead(204);res.end();
      });
      return;
    }
    const key=path.startsWith(bucket+"/")
      ?decodeURIComponent(path.slice(bucket.length+1)):"";
    const item=uploaded.get(key);
    if(req.method==="HEAD"){
      if(!item){res.writeHead(404,{"x-amz-request-id":"missing"});return res.end();}
      res.writeHead(200,{
        "Content-Type":item.mime,
        "Content-Length":item.bytes.length,
        "ETag":'"fake-object"',
        "x-amz-request-id":"test"
      });
      return res.end();
    }
    if(req.method==="GET" && item){
      res.writeHead(200,{
        "Content-Type":item.mime,
        "Content-Length":item.bytes.length
      });
      return res.end(item.bytes);
    }
    res.writeHead(404);res.end();
  });
  await new Promise(resolve=>storage.listen(0,"127.0.0.1",resolve));
  const storagePort=storage.address().port;

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
      MEDIA_TEST_ALLOW_HTTP_LOCAL:"true",
      MEDIA_ENABLE_UPLOADS:"true",
      MEDIA_BUCKET:"private-test",
      MEDIA_REGION:"us-east-1",
      MEDIA_ACCESS_KEY_ID:"test-key",
      MEDIA_SECRET_ACCESS_KEY:"test-secret",
      MEDIA_ENDPOINT:`http://127.0.0.1:${storagePort}`
    }
  });
  const db=new pg.Pool({connectionString:databaseUrl,ssl:false});
  const base=`http://127.0.0.1:${port}`;
  const ids=[];

  async function request(path,method="GET",token=null,data=null){
    const response=await fetch(base+path,{
      method,
      headers:{
        ...(token?{Authorization:"Bearer "+token}:{}),
        ...(data!==null?{"Content-Type":"application/json"}:{})
      },
      ...(data!==null?{body:JSON.stringify(data)}:{})
    });
    const raw=await response.text();
    return {
      status:response.status,
      json:raw?JSON.parse(raw):null,
      headers:response.headers
    };
  }

  async function register(prefix){
    const result=await request("/api/register","POST",null,{
      username:prefix+randomUUID().replaceAll("-","").slice(0,14),
      displayName:"Document Test",
      password:"document-test-"+randomUUID()
    });
    assert.equal(result.status,201);
    ids.push(result.json.user.id);
    return result.json;
  }

  try{
    let ready=false;
    for(let i=0;i<120;i++){
      if(server.exitCode!==null)throw new Error("Media API process exited");
      try{
        if((await request("/health")).status===200){ready=true;break;}
      }catch{}
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    assert.equal(ready,true);

    const capabilities=await request("/api/capabilities");
    assert.equal(capabilities.status,200);
    assert.equal(capabilities.json.mediaReady,true);
    assert.equal(capabilities.json.documentsReady,true);

    const alice=await register("doca");
    const bob=await register("docb");
    const other=await register("docc");

    const input={
      to:bob.user.id,
      mime:"application/pdf",
      bytes:expectedBytes.length,
      filename:"../../report.exe"
    };

    assert.equal((await request("/api/media/init","POST",null,input)).status,401);
    assert.equal((await request("/api/media/init","POST",alice.token,{
      ...input,to:alice.user.id
    })).status,400);
    assert.equal((await request("/api/media/init","POST",alice.token,{
      ...input,mime:"application/zip"
    })).status,400,"archives/executables are outside the document allowlist");
    assert.equal((await request("/api/media/init","POST",alice.token,{
      ...input,bytes:16*1024*1024
    })).status,400,"PDF size limit is enforced server-side");

    const init=await request("/api/media/init","POST",alice.token,input);
    assert.equal(init.status,201);
    assert.equal(init.json.filename,"report.pdf",
      "unsafe/mismatched extension is replaced with the canonical safe extension");
    assert.ok(init.json.uploadUrl.startsWith(`http://127.0.0.1:${storagePort}`));
    assert.equal(init.json.maxBytes,15*1024*1024);
    expectedUploadKey=init.json.fields.key||init.json.fields.Key;
    assert.ok(expectedUploadKey?.startsWith("private/"+alice.user.id+"/"));

    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",bob.token
    )).status,404,"recipient cannot read an unclaimed upload");

    const form=new FormData();
    for(const [key,value] of Object.entries(init.json.fields))form.append(key,value);
    form.append("file",new Blob([expectedBytes],{type:expectedMime}),"report.pdf");
    const upload=await fetch(init.json.uploadUrl,{method:"POST",body:form});
    assert.equal(upload.status,204);

    const complete=await request(
      "/api/media/"+init.json.assetId+"/complete","POST",alice.token
    );
    assert.equal(complete.status,200);
    assert.equal(complete.json.uploaded,true);
    assert.equal(complete.json.mime,"application/pdf");

    const senderLink=await request(
      "/api/media/"+init.json.assetId+"/download","GET",alice.token
    );
    assert.equal(senderLink.status,200);
    assert.equal(senderLink.json.filename,"report.pdf");
    assert.equal(senderLink.json.expiresIn,90);

    const clientMessageId=randomUUID();
    const sent=await request(
      "/api/media/"+init.json.assetId+"/send","POST",alice.token,{
        clientMessageId,caption:"План проекта"
      }
    );
    assert.equal(sent.status,201);
    assert.equal(sent.json.text,"План проекта");
    assert.equal(sent.json.attachmentId,init.json.assetId);

    const recipientLink=await request(
      "/api/media/"+init.json.assetId+"/download","GET",bob.token
    );
    assert.equal(recipientLink.status,200);
    assert.equal(recipientLink.json.mime,"application/pdf");
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",other.token
    )).status,404,"unrelated authenticated users cannot obtain a signed URL");

    const history=await request("/api/messages/"+alice.user.id,"GET",bob.token);
    assert.equal(history.status,200);
    const message=history.json.find(item=>item.id===sent.json.id);
    assert.ok(message);
    assert.equal(message.attachmentId,init.json.assetId);

    const replay=await request(
      "/api/media/"+init.json.assetId+"/send","POST",alice.token,{
        clientMessageId,caption:"План проекта"
      }
    );
    assert.equal(replay.status,200);
    assert.equal(replay.json.id,sent.json.id);

    const rows=await db.query(
      "select count(*)::integer as n from messages where media_id=$1",
      [init.json.assetId]
    );
    assert.equal(rows.rows[0].n,1);

    // Verify an allowlisted OpenXML document receives a signed upload policy.
    expectedMime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    expectedBytes=Buffer.from("PK\u0003\u0004fake-docx");
    const docx=await request("/api/media/init","POST",alice.token,{
      to:bob.user.id,
      mime:expectedMime,
      bytes:expectedBytes.length,
      filename:"notes.docx"
    });
    assert.equal(docx.status,201);
    assert.equal(docx.json.maxBytes,15*1024*1024);
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
    await new Promise(resolve=>storage.close(resolve));
  }
});
