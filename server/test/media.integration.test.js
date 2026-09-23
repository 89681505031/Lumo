import test from "node:test";
import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import {spawn} from "node:child_process";
import {createServer as createHttpServer} from "node:http";
import {createServer as createNetServer} from "node:net";
import pg from "pg";

const databaseUrl=process.env.LUMO_TEST_DATABASE_URL;

test("private photo upload policy, ownership, direct delivery and signed reads",{
 skip:!databaseUrl,timeout:25000
},async()=>{
 const storage=createHttpServer();
 const uploaded=new Map();
 let expectedUploadKey=null,expectedMime="image/png",expectedBytes=Buffer.from("PNGTEST");
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
  const key=path.startsWith(bucket+"/")?decodeURIComponent(path.slice(bucket.length+1)):"";
  const item=uploaded.get(key);
  if(req.method==="HEAD"){
   if(!item){res.writeHead(404,{"x-amz-request-id":"missing"});return res.end();}
   res.writeHead(200,{
    "Content-Type":item.mime,"Content-Length":item.bytes.length,
    "ETag":'"fake-object"',"x-amz-request-id":"test"
   });return res.end();
  }
  if(req.method==="GET" && item){
   res.writeHead(200,{"Content-Type":item.mime,"Content-Length":item.bytes.length});
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
  cwd:process.cwd(),stdio:"ignore",
  env:{...process.env,PORT:String(port),DATABASE_URL:databaseUrl,DATABASE_SSL:"false",
   NODE_ENV:"test",MEDIA_TEST_ALLOW_HTTP_LOCAL:"true",MEDIA_ENABLE_UPLOADS:"true",
   MEDIA_BUCKET:"private-test",MEDIA_REGION:"us-east-1",
   MEDIA_ACCESS_KEY_ID:"test-key",MEDIA_SECRET_ACCESS_KEY:"test-secret",
   MEDIA_ENDPOINT:`http://127.0.0.1:${storagePort}`}
 });
 const db=new pg.Pool({connectionString:databaseUrl,ssl:false});
 const base=`http://127.0.0.1:${port}`;
 async function request(path,method="GET",token=null,data=null){
  const r=await fetch(base+path,{method,
   headers:{...(token?{Authorization:"Bearer "+token}:{}),
    ...(data!==null?{"Content-Type":"application/json"}:{})},
   ...(data!==null?{body:JSON.stringify(data)}:{})});
  const raw=await r.text();
  return {status:r.status,json:raw?JSON.parse(raw):null};
 }
 try{
  let ready=false;
  for(let attempt=0;attempt<120;attempt++){
   if(server.exitCode!==null)throw new Error("Media API process exited");
   try{const response=await fetch(base+"/health");if(response.ok){ready=true;break;}}catch{}
   await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.ok(ready,"Media database migration did not finish");
  const capability=await request("/api/capabilities");
  assert.equal(capability.status,200);
  assert.equal(capability.json.mediaReady,true);
  async function register(prefix){
   const r=await request("/api/register","POST",null,{
    username:prefix+randomUUID().slice(0,8),displayName:prefix,
    password:"Test-"+randomUUID()+"-Strong"
   });assert.equal(r.status,201);return r.json;
  }
  const alice=await register("ma"),bob=await register("mb"),other=await register("mc");
  const input={to:bob.user.id,mime:"image/png",bytes:expectedBytes.length,filename:"../../ photo .png"};
  assert.equal((await request("/api/media/init","POST",null,input)).status,401);
  assert.equal((await request("/api/media/init","POST",alice.token,{...input,to:alice.user.id})).status,400);
  assert.equal((await request("/api/media/init","POST",alice.token,{...input,mime:"text/html"})).status,400);
  assert.equal((await request("/api/media/init","POST",alice.token,{...input,bytes:999999999})).status,400);
  assert.equal((await request("/api/media/init","POST",alice.token,{...input,filename:""})).status,400);
  assert.equal((await request("/api/media/init","POST",alice.token,{...input,to:randomUUID()})).status,404);
  assert.equal((await request("/api/media/init","POST",alice.token,{...input,bytes:1.5})).status,400);
  const init=await request("/api/media/init","POST",alice.token,input);
  assert.equal(init.status,201);
  assert.ok(init.json.uploadUrl.startsWith(`http://127.0.0.1:${storagePort}`));
  assert.equal(init.json.expiresIn,300);
  assert.equal(init.json.maxBytes,8*1024*1024);
  assert.ok(Object.keys(init.json.fields).length>0);
  const policyText=init.json.fields.Policy||init.json.fields.policy;
  assert.ok(policyText,"Missing signed upload policy");
  const policy=JSON.parse(Buffer.from(policyText,"base64").toString("utf8"));
  assert.ok(policy.conditions.some(c=>Array.isArray(c)&&c[0]==="content-length-range"),"No server-side S3 upload size policy");
  assert.ok(policy.conditions.some(c=>c["Content-Type"]==="image/png"),"Media type must be signed");
  const assetId=init.json.assetId;
  assert.equal((await request("/api/media/"+assetId+"/complete","POST",bob.token)).status,404);
  assert.equal((await request("/api/media/"+assetId+"/complete","POST",alice.token)).status,409);
  assert.equal((await request("/api/media/"+assetId+"/download","GET",bob.token)).status,404);
  assert.equal((await request("/api/media/"+assetId+"/download","GET",other.token)).status,404);
  expectedUploadKey=init.json.fields.key||init.json.fields.Key;
  assert.ok(expectedUploadKey?.startsWith("private/"+alice.user.id+"/"));
  const form=new FormData();
  for(const [key,val] of Object.entries(init.json.fields))form.append(key,val);
  form.append("file",new Blob([expectedBytes],{type:"image/png"}),"test.png");
  const uploadResult=await fetch(init.json.uploadUrl,{method:"POST",body:form});
  assert.equal(uploadResult.status,204);
  const confirmed=await request("/api/media/"+assetId+"/complete","POST",alice.token);
  assert.equal(confirmed.status,200);
  assert.equal(confirmed.json.uploaded,true);
  assert.equal(confirmed.json.filename.includes("/"),false,"Prevent filename path injection");
  assert.equal((await request("/api/media/"+assetId+"/complete","POST",alice.token)).status,200);
  const senderLink=await request("/api/media/"+assetId+"/download","GET",alice.token);
  assert.equal(senderLink.status,200);
  assert.equal(senderLink.json.mime,"image/png");
  assert.equal(senderLink.json.expiresIn,90);
  assert.equal((await request("/api/media/"+assetId+"/download","GET",bob.token)).status,404);
  assert.equal((await request("/api/media/"+assetId+"/send","POST",other.token,{
   clientMessageId:randomUUID()
  })).status,404);
  assert.equal((await request("/api/media/"+assetId+"/send","POST",alice.token,{
   clientMessageId:"bad"
  })).status,400);
  await request("/api/blocks/"+bob.user.id,"PUT",alice.token);
  const deny=await request("/api/media/"+assetId+"/send","POST",alice.token,{
   clientMessageId:randomUUID()
  });
  assert.equal(deny.status,403);
  await request("/api/blocks/"+bob.user.id,"DELETE",alice.token);
  const msgId=randomUUID();
  const sent=await request("/api/media/"+assetId+"/send","POST",alice.token,{
   clientMessageId:msgId
  });
  assert.equal(sent.status,201);
  assert.equal(sent.json.text,"📷 Фото");
  assert.equal(sent.json.attachmentId,assetId);
  assert.equal((await request("/api/media/"+assetId+"/download","GET",bob.token)).status,200);
  assert.equal((await request("/api/media/"+assetId+"/download","GET",other.token)).status,404);
  const inbox=await request("/api/messages/"+alice.user.id,"GET",bob.token);
  assert.equal(inbox.status,200);
  assert.equal(inbox.json.length,1);
  assert.equal(inbox.json[0].attachmentId,assetId);
  assert.equal((await request("/api/conversations","GET",bob.token)).json[0].lastMessage,"📷 Фото");
  const replay=await request("/api/media/"+assetId+"/send","POST",alice.token,{
   clientMessageId:msgId
  });
  assert.equal(replay.status,200);
  assert.equal(replay.json.id,sent.json.id);
  assert.equal((await request("/api/media/"+assetId+"/send","POST",alice.token,{
   clientMessageId:randomUUID()
  })).status,409);
  const alt=await request("/api/media/init","POST",alice.token,input);
  assert.equal(alt.status,201);
  const conflicting=await request("/api/media/"+alt.json.assetId+"/send","POST",alice.token,{
   clientMessageId:msgId
  });
  assert.equal(conflicting.status,409 /* not uploaded yet */);
  assert.equal((await request("/api/media/not-valid/download","GET",alice.token)).status,400);
  const rows=await db.query("select count(*)::integer as n from messages where media_id=$1",[assetId]);
  assert.equal(rows.rows[0].n,1,"Retry must not create duplicate media messages");
 }finally{
  server.kill("SIGTERM");
  if(server.exitCode===null)await new Promise(resolve=>{
   const timer=setTimeout(resolve,1500);
   server.once("exit",()=>{clearTimeout(timer);resolve();});
  });
  await db.end();
  await new Promise(resolve=>storage.close(resolve));
 }
});
