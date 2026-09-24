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
  const cleanupSecret="media-cleanup-test-secret-over-thirty-two-chars";

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
    if(req.method==="DELETE"){
      uploaded.delete(key);
      res.writeHead(204,{"x-amz-request-id":"delete-test"});
      return res.end();
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
      MEDIA_ENDPOINT:`http://127.0.0.1:${storagePort}`,
      MEDIA_UNREFERENCED_RETENTION_DAYS:"30",
      CRON_SECRET:cleanupSecret
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
    const stranger=await register("docd");

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

    const forwardClientId=randomUUID();
    const forwarded=await request(
      "/api/media/"+init.json.assetId+"/forward","POST",bob.token,{
        to:other.user.id,
        clientMessageId:forwardClientId,
        caption:"↪ План проекта"
      }
    );
    assert.equal(forwarded.status,201);
    assert.equal(forwarded.json.from,bob.user.id);
    assert.equal(forwarded.json.to,other.user.id);
    assert.equal(forwarded.json.attachmentId,init.json.assetId);
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",other.token
    )).status,200,"explicit forwarding grants only the new message participant access");
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",stranger.token
    )).status,404,"forwarding never makes the private object public");

    const forwardReplay=await request(
      "/api/media/"+init.json.assetId+"/forward","POST",bob.token,{
        to:other.user.id,
        clientMessageId:forwardClientId,
        caption:"↪ План проекта"
      }
    );
    assert.equal(forwardReplay.status,200);
    assert.equal(forwardReplay.json.id,forwarded.json.id);

    const forwardConflict=await request(
      "/api/media/"+init.json.assetId+"/forward","POST",bob.token,{
        to:stranger.user.id,
        clientMessageId:forwardClientId,
        caption:"↪ План проекта"
      }
    );
    assert.equal(forwardConflict.status,409,
      "same forwarding idempotency key cannot silently change recipient");

    const deletedForward=await request(
      "/api/messages/"+forwarded.json.id,"DELETE",bob.token
    );
    assert.equal(deletedForward.status,200);
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",other.token
    )).status,404,"deleting the forwarded message revokes its recipient link");
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",bob.token
    )).status,200,"original direct-message participant still has access");

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
    assert.equal(rows.rows[0].n,2,"original plus tombstoned forwarded message share one private object");

    const deleted=await request(
      "/api/messages/"+sent.json.id,"DELETE",alice.token
    );
    assert.equal(deleted.status,200);
    assert.ok(deleted.json.deletedAt);
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",bob.token
    )).status,404,"message deletion revokes future recipient signed URLs");
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",alice.token
    )).status,200,"storage owner keeps access until retention cleanup");

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

    // Group attachments reuse the same private storage but authorization comes
    // from the viewer's CURRENT group membership window.
    const createdGroup=await request("/api/groups","POST",alice.token,{
      title:"Private media group"
    });
    assert.equal(createdGroup.status,201);
    const groupId=createdGroup.json.id;
    assert.equal((await request(
      "/api/groups/"+groupId+"/members","POST",alice.token,{userId:bob.user.id}
    )).status,201);

    const groupCaps=await request("/api/capabilities");
    assert.equal(groupCaps.json.groupAttachments,true);

    expectedMime="text/plain";
    expectedBytes=Buffer.from("Lumo private group attachment");
    const groupInit=await request(
      "/api/groups/"+groupId+"/media/init","POST",alice.token,{
        mime:expectedMime,
        bytes:expectedBytes.length,
        filename:"group-notes.txt"
      }
    );
    assert.equal(groupInit.status,201);
    expectedUploadKey=groupInit.json.fields.key||groupInit.json.fields.Key;
    assert.ok(expectedUploadKey?.startsWith(
      "private/"+alice.user.id+"/groups/"+groupId+"/"
    ));

    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",bob.token
    )).status,404,"a group member cannot read an unclaimed upload");

    const groupForm=new FormData();
    for(const [key,value] of Object.entries(groupInit.json.fields))
      groupForm.append(key,value);
    groupForm.append(
      "file",new Blob([expectedBytes],{type:expectedMime}),"group-notes.txt"
    );
    assert.equal((await fetch(
      groupInit.json.uploadUrl,{method:"POST",body:groupForm}
    )).status,204);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/complete","POST",alice.token
    )).status,200);

    const groupClientId=randomUUID();
    const groupSent=await request(
      "/api/groups/"+groupId+"/media/"+groupInit.json.assetId+"/send",
      "POST",alice.token,{
        clientMessageId:groupClientId,
        caption:"Групповой документ"
      }
    );
    assert.equal(groupSent.status,201);
    assert.equal(groupSent.json.attachmentId,groupInit.json.assetId);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",bob.token
    )).status,200,"current members can read an attachment linked to visible group history");
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",stranger.token
    )).status,404);

    const groupReplay=await request(
      "/api/groups/"+groupId+"/media/"+groupInit.json.assetId+"/send",
      "POST",alice.token,{
        clientMessageId:groupClientId,
        caption:"Групповой документ"
      }
    );
    assert.equal(groupReplay.status,200);
    assert.equal(groupReplay.json.id,groupSent.json.id);

    const groupHistory=await request(
      "/api/groups/"+groupId+"/messages","GET",bob.token
    );
    assert.equal(groupHistory.status,200);
    assert.equal(
      groupHistory.json.find(m=>m.id===groupSent.json.id)?.attachmentId,
      groupInit.json.assetId
    );

    // A late join must not use a new attachment message as a side channel to
    // read media from before that membership period.
    assert.equal((await request(
      "/api/groups/"+groupId+"/members","POST",alice.token,{userId:other.user.id}
    )).status,201);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",other.token
    )).status,404,"late joiners cannot open older group attachments");

    assert.equal((await request(
      "/api/groups/"+groupId+"/members/"+bob.user.id,"DELETE",alice.token
    )).status,204);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",bob.token
    )).status,404,"leaving/removal revokes group attachment access");

    assert.equal((await request(
      "/api/groups/"+groupId+"/members","POST",alice.token,{userId:bob.user.id}
    )).status,201);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",bob.token
    )).status,404,"rejoining does not reopen old group attachments");

    const groupDeleted=await request(
      "/api/groups/"+groupId+"/messages/"+groupSent.json.id,
      "DELETE",alice.token
    );
    assert.equal(groupDeleted.status,200);
    assert.ok(groupDeleted.json.deletedAt);
    assert.equal((await request(
      "/api/media/"+groupInit.json.assetId+"/download","GET",alice.token
    )).status,200,"the uploader keeps owner access pending retention cleanup");

    // Deleting an owned group must remove its private objects before the group
    // row cascades away, otherwise an S3 object would become unreachable from DB.
    const groupObjectKey=(await db.query(
      "select object_key from media_assets where id=$1",
      [groupInit.json.assetId]
    )).rows[0].object_key;
    assert.equal(uploaded.has(groupObjectKey),true);
    const removeGroup=await request(
      "/api/groups/"+groupId,"DELETE",alice.token
    );
    assert.equal(removeGroup.status,204);
    assert.equal(uploaded.has(groupObjectKey),false);
    assert.equal(
      Number((await db.query(
        "select count(*) from media_assets where id=$1",
        [groupInit.json.assetId]
      )).rows[0].count),
      0
    );

    // Claimed direct media whose every message reference is now deleted is
    // retained until the explicit policy window elapses.
    await db.query(
      `update media_assets
       set uploaded_at=now()-interval '31 days'
       where id=$1`,
      [init.json.assetId]
    );

    // Outstanding unclaimed reservations are bounded per account. This protects
    // a private bucket from repeated presign requests that are never committed.
    assert.equal((await request("/internal/media-cleanup")).status,401);
    assert.equal((await request(
      "/internal/media-cleanup","GET","wrong-secret"
    )).status,401);

    const reservedIds=[];
    for(let i=0;i<8;i++){
      const reservation=await request("/api/media/init","POST",stranger.token,{
        to:alice.user.id,
        mime:"text/plain",
        bytes:1,
        filename:"quota-"+i+".txt"
      });
      assert.equal(reservation.status,201);
      reservedIds.push(reservation.json.assetId);
    }
    const overQuota=await request("/api/media/init","POST",stranger.token,{
      to:alice.user.id,
      mime:"text/plain",
      bytes:1,
      filename:"quota-overflow.txt"
    });
    assert.equal(overQuota.status,429);
    assert.equal(overQuota.json.error,"media_quota_exceeded");

    // Expired unclaimed reservations are safe to remove: send/complete refuse
    // them after expiry, and DeleteObject is idempotent even if upload never ran.
    await db.query(
      `update media_assets set expires_at=now()-interval '2 minutes'
       where id=any($1::uuid[])`,
      [reservedIds]
    );
    const cleanup=await request(
      "/internal/media-cleanup","GET",cleanupSecret
    );
    assert.equal(cleanup.status,200);
    assert.equal(cleanup.json.abandoned.deleted,8);
    assert.equal(cleanup.json.abandoned.failed,0);
    assert.equal(cleanup.json.unreferenced.enabled,true);
    assert.equal(cleanup.json.unreferenced.retentionDays,30);
    assert.ok(cleanup.json.unreferenced.deleted>=1);
    assert.equal(
      Number((await db.query(
        "select count(*) from media_assets where id=any($1::uuid[])",
        [reservedIds]
      )).rows[0].count),
      0
    );
    assert.equal(
      Number((await db.query(
        "select count(*) from media_assets where id=$1",
        [init.json.assetId]
      )).rows[0].count),
      0,
      "old claimed media with no live message references is garbage-collected"
    );
    assert.equal((await request(
      "/api/media/"+init.json.assetId+"/download","GET",alice.token
    )).status,404);

    const afterCleanup=await request("/api/media/init","POST",stranger.token,{
      to:alice.user.id,
      mime:"text/plain",
      bytes:1,
      filename:"quota-reset.txt"
    });
    assert.equal(afterCleanup.status,201,
      "cleanup releases outstanding-reservation capacity");
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
