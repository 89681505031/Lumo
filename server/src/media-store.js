import { randomUUID, timingSafeEqual } from "node:crypto";
import {
  S3Client, HeadObjectCommand, GetObjectCommand, DeleteObjectCommand
} from "@aws-sdk/client-s3";
import { createPresignedPost } from "@aws-sdk/s3-presigned-post";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { dbQuery, pool } from "./db.js";

export const allowedMimeSizes=Object.freeze({
  "image/jpeg":8*1024*1024,
  "image/png":8*1024*1024,
  "image/webp":8*1024*1024,
  "audio/mp4":10*1024*1024,
  "video/mp4":25*1024*1024,
  "application/pdf":15*1024*1024,
  "text/plain":2*1024*1024,
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document":15*1024*1024,
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":15*1024*1024,
  "application/vnd.openxmlformats-officedocument.presentationml.presentation":20*1024*1024
});

const extensions=Object.freeze({
  "image/jpeg":[".jpg",".jpeg"],
  "image/png":[".png"],
  "image/webp":[".webp"],
  "audio/mp4":[".m4a",".mp4"],
  "video/mp4":[".mp4"],
  "application/pdf":[".pdf"],
  "text/plain":[".txt"],
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document":[".docx"],
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":[".xlsx"],
  "application/vnd.openxmlformats-officedocument.presentationml.presentation":[".pptx"]
});

function normalizedEndpoint(raw) {
  if(!raw)return undefined;
  const u=new URL(raw);
  const allowTestLocal=process.env.NODE_ENV==="test" &&
    process.env.MEDIA_TEST_ALLOW_HTTP_LOCAL==="true" &&
    ["127.0.0.1","localhost"].includes(u.hostname);
  if(u.protocol!=="https:" && !(allowTestLocal && u.protocol==="http:"))
    throw new Error("MEDIA_ENDPOINT requires HTTPS");
  if(u.username || u.password || u.search || u.hash)
    throw new Error("MEDIA_ENDPOINT must not contain credentials, query or fragment");
  return u.toString().replace(/\/$/,"");
}

// Prefer Lumo-specific names, but also accept the standard S3-compatible
// environment names used by Neon Object Storage integrations. Values are
// consumed server-side only and never returned by the API.
const storageBucket=(process.env.MEDIA_BUCKET || "lumo-media").trim();
const storageRegion=(process.env.MEDIA_REGION || process.env.AWS_REGION || "").trim();
const storageAccessKeyId=(
  process.env.MEDIA_ACCESS_KEY_ID || process.env.AWS_ACCESS_KEY_ID || ""
).trim();
const storageSecretAccessKey=(
  process.env.MEDIA_SECRET_ACCESS_KEY || process.env.AWS_SECRET_ACCESS_KEY || ""
).trim();
const storageEndpoint=(
  process.env.MEDIA_ENDPOINT || process.env.AWS_ENDPOINT_URL_S3 || ""
).trim();

export const mediaReady=Boolean(
  storageBucket && storageRegion && storageAccessKeyId && storageSecretAccessKey
);
export const inlineMediaMaxBytes=4*1024*1024;
export const inlineMediaReady=Boolean(pool) &&
  process.env.MEDIA_INLINE_FALLBACK!=="false";
export const mediaAvailable=mediaReady || inlineMediaReady;
let client=null;
const bucket=mediaReady?storageBucket:null;
const MAX_UNCLAIMED_RESERVATIONS=8;
const MAX_UNCLAIMED_RESERVED_BYTES=75*1024*1024;
const MAX_INLINE_ACCOUNT_BYTES=75*1024*1024;
function unreferencedRetentionDays(){
  const raw=String(process.env.MEDIA_UNREFERENCED_RETENTION_DAYS||"").trim();
  if(!raw)return null; // Explicit opt-in: never delete claimed media by default.
  const days=Number(raw);
  if(!Number.isInteger(days) || days<1 || days>3650)
    throw new Error("MEDIA_UNREFERENCED_RETENTION_DAYS must be an integer from 1 to 3650");
  return days;
}
if(mediaReady){
  client=new S3Client({
    region:storageRegion,
    endpoint:normalizedEndpoint(storageEndpoint),
    forcePathStyle:true,
    credentials:{
      accessKeyId:storageAccessKeyId,
      secretAccessKey:storageSecretAccessKey
    }
  });
}
const iso=d=>d?.toISOString?.()||d||null;
const assetPublic=row=>({
  id:row.id,
  mime:row.mime,
  filename:row.file_name,
  bytes:Number(row.byte_length),
  uploaded:row.uploaded_at!==null,
  expiresAt:iso(row.expires_at)
});
const mappedMessage=row=>({
  id:row.id,
  from:row.sender_id,
  to:row.recipient_id,
  text:row.text,
  createdAt:iso(row.created_at),
  deliveredAt:iso(row.delivered_at),
  readAt:iso(row.read_at),
  clientMessageId:row.client_message_id||null,
  attachmentId:row.media_id||null,
  replyToMessageId:row.reply_to_message_id||null,
  replyPreviewText:null,
  replyPreviewFrom:null,
  editedAt:iso(row.edited_at),
  deletedAt:iso(row.deleted_at)
});
const mappedGroupMessage=row=>({
  id:row.id,
  groupId:row.group_id,
  from:row.sender_id,
  text:row.text,
  createdAt:iso(row.created_at),
  clientMessageId:row.client_message_id||null,
  attachmentId:row.media_id||null,
  replyToMessageId:row.reply_to_message_id||null,
  replyPreviewText:null,
  replyPreviewFrom:null,
  editedAt:iso(row.edited_at),
  deletedAt:iso(row.deleted_at)
});

function safeFilename(value,mime){
  const allowed=extensions[mime];
  if(!allowed)return "attachment";
  const source=String(value||"").normalize("NFKC").split(/[\\/]/).pop()||"attachment";
  let clean=source.replace(/[^\p{L}\p{N} ._()-]/gu,"_").replace(/^\.+/,"").trim();
  if(!clean)clean="attachment";
  const lower=clean.toLowerCase();
  if(!allowed.some(ext=>lower.endsWith(ext))){
    clean=clean.replace(/\.[^.]{1,12}$/,"").trim()||"attachment";
    clean+=allowed[0];
  }
  if(clean.length>80){
    const ext=allowed.find(e=>clean.toLowerCase().endsWith(e))||allowed[0];
    const stem=clean.slice(0,Math.max(1,80-ext.length)).replace(/[ .]+$/,"");
    clean=stem+ext;
  }
  return clean;
}

export function validateMedia({mime,bytes,filename}){
  if(typeof mime!=="string" || !Object.hasOwn(allowedMimeSizes,mime))
    return {error:"unsupported_media_type"};
  if(!Number.isSafeInteger(bytes) || bytes<1 || bytes>allowedMimeSizes[mime])
    return {error:"invalid_media_size"};
  if(typeof filename!=="string" || filename.length<1 || filename.length>160)
    return {error:"invalid_file_name"};
  return {mime,bytes,filename:safeFilename(filename,mime)};
}

async function reserveAsset({
  id,ownerId,recipientId=null,groupId=null,key,mime,filename,bytes,
  storageMode="s3"
}) {
  const conn=await pool.connect();
  let committed=false;
  try{
    await conn.query("begin");
    // Serialize only this owner's reservations and inline quota accounting.
    await conn.query("select pg_advisory_xact_lock(hashtext($1))",[ownerId]);
    const usage=await conn.query(`
      select count(*)::integer as count,
             coalesce(sum(byte_length),0)::bigint as bytes
      from media_assets
      where owner_id=$1
        and claimed_message_id is null
        and claimed_group_message_id is null
        and expires_at>now()`,[ownerId]);
    const count=Number(usage.rows[0]?.count||0);
    const reserved=Number(usage.rows[0]?.bytes||0);
    if(count>=MAX_UNCLAIMED_RESERVATIONS ||
       reserved+bytes>MAX_UNCLAIMED_RESERVED_BYTES)
      return {error:"media_quota_exceeded"};
    if(storageMode==="inline"){
      const total=await conn.query(`
        select coalesce(sum(byte_length),0)::bigint as bytes
        from media_assets
        where owner_id=$1 and storage_mode='inline'`,[ownerId]);
      if(Number(total.rows[0]?.bytes||0)+bytes>MAX_INLINE_ACCOUNT_BYTES)
        return {error:"media_storage_quota_exceeded"};
    }
    await conn.query(`
      insert into media_assets(
        id,owner_id,recipient_id,group_id,object_key,mime,file_name,byte_length,
        storage_mode
      ) values($1,$2,$3,$4,$5,$6,$7,$8,$9)`,
      [id,ownerId,recipientId,groupId,key,mime,filename,bytes,storageMode]);
    await conn.query("commit");
    committed=true;
    return {ok:true};
  }finally{
    if(!committed)await conn.query("rollback").catch(()=>{});
    conn.release();
  }
}

async function deleteBackingObject(item){
  if(item.storage_mode==="inline")return;
  if(!mediaReady){
    const error=new Error("S3 media storage unavailable");
    error.code="media_unavailable";
    throw error;
  }
  await client.send(new DeleteObjectCommand({
    Bucket:bucket,Key:item.object_key
  }));
}

function placeholder(mime,filename) {
  if(mime.startsWith("image/"))return "📷 Фото";
  if(mime.startsWith("video/"))return "🎬 Видео";
  if(mime.startsWith("audio/"))return "🎤 Голосовое сообщение";
  return "📎 "+filename;
}

export const mediaStore={
  async initiate(ownerId,recipientId,input){
    const checked=validateMedia(input);
    if(checked.error)return checked;
    if(!mediaAvailable)return {error:"media_unavailable"};
    const storageMode=mediaReady?"s3":"inline";
    if(storageMode==="inline" && checked.bytes>inlineMediaMaxBytes)
      return {error:"inline_media_too_large"};
    const id=randomUUID();
    const key=storageMode==="s3"
      ?`private/${ownerId}/${id}`
      :`inline/${ownerId}/${id}`;
    const upload=storageMode==="s3"
      ?await createPresignedPost(client,{
        Bucket:bucket,
        Key:key,
        Expires:300,
        Fields:{"Content-Type":checked.mime},
        Conditions:[
          {"Content-Type":checked.mime},
          ["content-length-range",1,allowedMimeSizes[checked.mime]]
        ]
      })
      :null;
    const reserved=await reserveAsset({
      id,ownerId,recipientId,key,mime:checked.mime,
      filename:checked.filename,bytes:checked.bytes,storageMode
    });
    if(reserved.error)return reserved;
    return {
      assetId:id,
      uploadMode:storageMode,
      uploadUrl:upload?.url||null,
      fields:upload?.fields||{},
      expiresIn:300,
      maxBytes:storageMode==="inline"
        ?Math.min(allowedMimeSizes[checked.mime],inlineMediaMaxBytes)
        :allowedMimeSizes[checked.mime],
      filename:checked.filename,
      mime:checked.mime
    };
  },

  async initiateGroup(ownerId,groupId,input){
    const checked=validateMedia(input);
    if(checked.error)return checked;
    if(!mediaAvailable)return {error:"media_unavailable"};
    const storageMode=mediaReady?"s3":"inline";
    if(storageMode==="inline" && checked.bytes>inlineMediaMaxBytes)
      return {error:"inline_media_too_large"};
    const id=randomUUID();
    const key=storageMode==="s3"
      ?`private/${ownerId}/groups/${groupId}/${id}`
      :`inline/${ownerId}/groups/${groupId}/${id}`;
    const upload=storageMode==="s3"
      ?await createPresignedPost(client,{
        Bucket:bucket,
        Key:key,
        Expires:300,
        Fields:{"Content-Type":checked.mime},
        Conditions:[
          {"Content-Type":checked.mime},
          ["content-length-range",1,allowedMimeSizes[checked.mime]]
        ]
      })
      :null;
    const reserved=await reserveAsset({
      id,ownerId,groupId,key,mime:checked.mime,
      filename:checked.filename,bytes:checked.bytes,storageMode
    });
    if(reserved.error)return reserved;
    return {
      assetId:id,
      uploadMode:storageMode,
      uploadUrl:upload?.url||null,
      fields:upload?.fields||{},
      expiresIn:300,
      maxBytes:storageMode==="inline"
        ?Math.min(allowedMimeSizes[checked.mime],inlineMediaMaxBytes)
        :allowedMimeSizes[checked.mime],
      filename:checked.filename,
      mime:checked.mime
    };
  },

  async owned(ownerId,id){
    const r=await dbQuery(
      "select * from media_assets where id=$1 and owner_id=$2",
      [id,ownerId]
    );
    return r.rows[0]||null;
  },

  async storeInline(ownerId,id,mime,body){
    if(!inlineMediaReady)return {error:"media_unavailable"};
    if(!Buffer.isBuffer(body) || body.length<1)
      return {error:"upload_mismatch"};
    if(body.length>inlineMediaMaxBytes)
      return {error:"inline_media_too_large"};
    const conn=await pool.connect();
    let committed=false;
    try{
      await conn.query("begin");
      const selected=await conn.query(
        "select * from media_assets where id=$1 and owner_id=$2 for update",
        [id,ownerId]
      );
      const item=selected.rows[0];
      if(!item)return {error:"media_not_found"};
      if(item.storage_mode!=="inline")return {error:"upload_mode_mismatch"};
      if(item.uploaded_at){
        await conn.query("commit");
        committed=true;
        return {asset:assetPublic(item)};
      }
      if(new Date(item.expires_at).getTime()<=Date.now())
        return {error:"upload_expired"};
      if(String(mime||"").toLowerCase()!==item.mime ||
         body.length!==Number(item.byte_length))
        return {error:"upload_mismatch"};
      const updated=await conn.query(`
        update media_assets
        set inline_bytes=$3, uploaded_at=now()
        where id=$1 and owner_id=$2 and uploaded_at is null
          and storage_mode='inline' and expires_at>now()
        returning *`,[id,ownerId,body]);
      if(!updated.rows[0])return {error:"upload_expired"};
      await conn.query("commit");
      committed=true;
      return {asset:assetPublic(updated.rows[0])};
    }finally{
      if(!committed)await conn.query("rollback").catch(()=>{});
      conn.release();
    }
  },

  async confirm(ownerId,id){
    if(!mediaAvailable)return {error:"media_unavailable"};
    const item=await this.owned(ownerId,id);
    if(!item)return {error:"media_not_found"};
    if(item.storage_mode==="inline"){
      if(item.uploaded_at && Buffer.isBuffer(item.inline_bytes) &&
         item.inline_bytes.length===Number(item.byte_length))
        return {asset:assetPublic(item)};
      if(new Date(item.expires_at).getTime()<=Date.now())
        return {error:"upload_expired"};
      return {error:"upload_incomplete"};
    }
    if(!mediaReady)return {error:"media_unavailable"};
    if(item.uploaded_at)return {asset:assetPublic(item)};
    if(new Date(item.expires_at).getTime()<=Date.now())return {error:"upload_expired"};
    let object;
    try{
      object=await client.send(new HeadObjectCommand({
        Bucket:bucket,Key:item.object_key
      }));
    }catch(error){
      if(error?.$metadata?.httpStatusCode===404 || error?.name==="NotFound")
        return {error:"upload_not_found"};
      throw error;
    }
    if(Number(object.ContentLength)!==Number(item.byte_length) ||
       String(object.ContentType||"").toLowerCase()!==item.mime)
      return {error:"upload_mismatch"};
    const updated=await dbQuery(
      "update media_assets set uploaded_at=now() where id=$1 and owner_id=$2 and uploaded_at is null and expires_at>now() returning *",
      [id,ownerId]
    );
    const row=updated.rows[0]||(await this.owned(ownerId,id));
    return row?.uploaded_at?{asset:assetPublic(row)}:{error:"upload_expired"};
  },

  async send(ownerId,id,clientMessageId,caption){
    const conn=await pool.connect();
    let committed=false;
    try{
      await conn.query("begin");
      const selected=await conn.query(
        "select * from media_assets where id=$1 and owner_id=$2 for update",
        [id,ownerId]
      );
      const a=selected.rows[0];
      if(!a)return {error:"media_not_found"};
      if(!a.uploaded_at)return {error:"upload_incomplete"};
      if(new Date(a.expires_at).getTime()<=Date.now() && !a.claimed_message_id)
        return {error:"upload_expired"};
      const text=caption||placeholder(a.mime,a.file_name);
      if(a.claimed_message_id){
        const previous=await conn.query(
          "select * from messages where id=$1 and sender_id=$2",
          [a.claimed_message_id,ownerId]
        );
        const p=previous.rows[0];
        if(p && p.client_message_id===clientMessageId && p.text===text)
          return {message:mappedMessage(p),inserted:false};
        return {error:"media_already_sent"};
      }
      const messageId=randomUUID();
      const inserted=await conn.query(`
        insert into messages(
          id,sender_id,recipient_id,text,client_message_id,media_id
        ) values($1,$2,$3,$4,$5,$6)
        on conflict(sender_id,client_message_id)
          where client_message_id is not null do nothing returning *`,
        [messageId,ownerId,a.recipient_id,text,clientMessageId,id]
      );
      if(!inserted.rows[0]){
        const old=await conn.query(
          "select * from messages where sender_id=$1 and client_message_id=$2",
          [ownerId,clientMessageId]
        );
        const p=old.rows[0];
        if(p && p.media_id===id && p.text===text)
          return {message:mappedMessage(p),inserted:false};
        return {error:"client_message_id_conflict"};
      }
      await conn.query(
        "update media_assets set claimed_message_id=$2 where id=$1",
        [id,messageId]
      );
      await conn.query("commit");
      committed=true;
      return {message:mappedMessage(inserted.rows[0]),inserted:true};
    }finally{
      if(!committed)await conn.query("rollback").catch(()=>{});
      conn.release();
    }
  },

  async sendGroup(ownerId,id,groupId,clientMessageId,caption){
    const conn=await pool.connect();
    let committed=false;
    try{
      await conn.query("begin");
      const selected=await conn.query(`
        select a.* from media_assets a
        join chat_group_members membership
          on membership.group_id=a.group_id and membership.user_id=$2
        where a.id=$1 and a.owner_id=$2 and a.group_id=$3
        for update of a`,
        [id,ownerId,groupId]
      );
      const a=selected.rows[0];
      if(!a)return {error:"media_not_found"};
      if(!a.uploaded_at)return {error:"upload_incomplete"};
      if(new Date(a.expires_at).getTime()<=Date.now() && !a.claimed_group_message_id)
        return {error:"upload_expired"};
      const text=(caption||placeholder(a.mime,a.file_name)).trim();
      if(a.claimed_group_message_id){
        const previous=await conn.query(
          `select * from chat_group_messages
           where id=$1 and group_id=$2 and sender_id=$3`,
          [a.claimed_group_message_id,groupId,ownerId]
        );
        const p=previous.rows[0];
        if(p && p.client_message_id===clientMessageId &&
           p.text===text && p.media_id===id)
          return {message:mappedGroupMessage(p),inserted:false};
        return {error:"media_already_sent"};
      }
      const messageId=randomUUID();
      const inserted=await conn.query(`
        insert into chat_group_messages(
          id,group_id,sender_id,text,client_message_id,media_id
        )
        select $1,$2,$3,$4,$5,$6
        from chat_group_members membership
        where membership.group_id=$2 and membership.user_id=$3
        on conflict(group_id,sender_id,client_message_id) do nothing
        returning *`,
        [messageId,groupId,ownerId,text,clientMessageId,id]
      );
      if(!inserted.rows[0]){
        const old=await conn.query(`
          select * from chat_group_messages
          where group_id=$1 and sender_id=$2 and client_message_id=$3`,
          [groupId,ownerId,clientMessageId]
        );
        const p=old.rows[0];
        if(p && p.media_id===id && p.text===text)
          return {message:mappedGroupMessage(p),inserted:false};
        return {error:"client_message_id_conflict"};
      }
      await conn.query(
        "update media_assets set claimed_group_message_id=$2 where id=$1",
        [id,messageId]
      );
      await conn.query("commit");
      committed=true;
      return {message:mappedGroupMessage(inserted.rows[0]),inserted:true};
    }finally{
      if(!committed)await conn.query("rollback").catch(()=>{});
      conn.release();
    }
  },

  async forward(userId,id,to,clientMessageId,caption){
    const conn=await pool.connect();
    let committed=false;
    try{
      await conn.query("begin");
      const source=await conn.query(
        `select a.*,m.id as source_message_id
         from media_assets a
         join messages m on m.media_id=a.id
         where a.id=$1 and a.uploaded_at is not null
           and m.deleted_at is null
           and (m.sender_id=$2 or m.recipient_id=$2)
         order by m.created_at asc
         limit 1
         for share of a`,
        [id,userId]
      );
      const a=source.rows[0];
      if(!a)return {error:"media_not_found"};
      const text=(caption||("↪ "+placeholder(a.mime,a.file_name))).trim();
      const messageId=randomUUID();
      const inserted=await conn.query(
        `insert into messages(
          id,sender_id,recipient_id,text,client_message_id,media_id
        ) values($1,$2,$3,$4,$5,$6)
        on conflict(sender_id,client_message_id)
          where client_message_id is not null do nothing
        returning *`,
        [messageId,userId,to,text,clientMessageId,id]
      );
      if(!inserted.rows[0]){
        const old=await conn.query(
          "select * from messages where sender_id=$1 and client_message_id=$2",
          [userId,clientMessageId]
        );
        const p=old.rows[0];
        if(p && p.recipient_id===to && p.media_id===id && p.text===text)
          return {message:mappedMessage(p),inserted:false};
        return {error:"client_message_id_conflict"};
      }
      await conn.query("commit");
      committed=true;
      return {message:mappedMessage(inserted.rows[0]),inserted:true};
    }finally{
      if(!committed)await conn.query("rollback").catch(()=>{});
      conn.release();
    }
  },

  async cleanupAbandoned(limit=100){
    if(!mediaAvailable)return {error:"media_unavailable"};
    const bounded=Math.max(1,Math.min(250,Math.floor(limit)));
    const candidates=await dbQuery(`
      select id,object_key,storage_mode
      from media_assets
      where expires_at<=now()
        and claimed_message_id is null
        and claimed_group_message_id is null
      order by expires_at asc,id asc
      limit $1`,[bounded]);
    let deleted=0,failed=0;
    for(const item of candidates.rows){
      try{
        await deleteBackingObject(item);
        const removed=await dbQuery(`
          delete from media_assets
          where id=$1 and expires_at<=now()
            and claimed_message_id is null
            and claimed_group_message_id is null
          returning id`,[item.id]);
        deleted+=removed.rowCount;
      }catch(error){
        failed++;
      }
    }
    return {scanned:candidates.rowCount,deleted,failed};
  },

  async cleanupUnreferenced(limit=100){
    if(!mediaAvailable)return {error:"media_unavailable"};
    const days=unreferencedRetentionDays();
    if(days===null)return {enabled:false,scanned:0,deleted:0,failed:0};
    const bounded=Math.max(1,Math.min(250,Math.floor(limit)));
    const candidates=await dbQuery(`
      select a.id,a.object_key,a.storage_mode
      from media_assets a
      where a.uploaded_at is not null
        and a.uploaded_at<=now()-make_interval(days=>$1)
        and not exists(
          select 1 from messages m
          where m.media_id=a.id and m.deleted_at is null
        )
        and not exists(
          select 1 from chat_group_messages gm
          where gm.media_id=a.id and gm.deleted_at is null
        )
      order by a.uploaded_at asc,a.id asc
      limit $2`,[days,bounded]);
    let deleted=0,failed=0;
    for(const item of candidates.rows){
      try{
        await deleteBackingObject(item);
        const conn=await pool.connect();
        let committed=false;
        try{
          await conn.query("begin");
          const locked=await conn.query(`
            select a.id from media_assets a
            where a.id=$1
              and a.uploaded_at<=now()-make_interval(days=>$2)
              and not exists(
                select 1 from messages m
                where m.media_id=a.id and m.deleted_at is null
              )
              and not exists(
                select 1 from chat_group_messages gm
                where gm.media_id=a.id and gm.deleted_at is null
              )
            for update`,[item.id,days]);
          if(!locked.rowCount){
            await conn.query("rollback");
            committed=true;
            continue;
          }
          await conn.query(
            "update messages set media_id=null where media_id=$1 and deleted_at is not null",
            [item.id]
          );
          await conn.query(
            "update chat_group_messages set media_id=null where media_id=$1 and deleted_at is not null",
            [item.id]
          );
          const removed=await conn.query(
            "delete from media_assets where id=$1 returning id",
            [item.id]
          );
          await conn.query("commit");
          committed=true;
          deleted+=removed.rowCount;
        }finally{
          if(!committed)await conn.query("rollback").catch(()=>{});
          conn.release();
        }
      }catch(error){
        failed++;
      }
    }
    return {enabled:true,retentionDays:days,scanned:candidates.rowCount,deleted,failed};
  },

  async groupAssetCount(groupId){
    const r=await dbQuery(
      "select count(*)::integer as count from media_assets where group_id=$1",
      [groupId]
    );
    return Number(r.rows[0]?.count||0);
  },

  async cleanupGroupAssets(groupId){
    if(!mediaAvailable)return {error:"media_unavailable"};
    const items=await dbQuery(
      "select id,object_key,storage_mode from media_assets where group_id=$1 order by id",
      [groupId]
    );
    let deletedObjects=0;
    for(const item of items.rows){
      try{
        await deleteBackingObject(item);
        deletedObjects++;
      }catch(error){
        return {
          error:error?.code==="media_unavailable"
            ?"media_cleanup_unavailable":"media_cleanup_failed",
          deletedObjects,
          remaining:items.rowCount-deletedObjects
        };
      }
    }
    const removed=await dbQuery(
      "delete from media_assets where group_id=$1 returning id",
      [groupId]
    );
    return {deletedObjects,deletedRows:removed.rowCount};
  },

  async signedDownload(userId,id){
    if(!mediaAvailable)return {error:"media_unavailable"};
    const r=await dbQuery(`
      select distinct a.* from media_assets a
      left join messages m
        on m.media_id=a.id
       and m.deleted_at is null
       and (m.sender_id=$2 or m.recipient_id=$2)
      left join chat_group_messages gm
        on gm.media_id=a.id and gm.deleted_at is null
      left join chat_group_members membership
        on membership.group_id=gm.group_id
       and membership.user_id=$2
       and gm.created_at>=membership.joined_at
      where a.id=$1 and a.uploaded_at is not null
        and (a.owner_id=$2 or m.id is not null or membership.user_id is not null)
      limit 1`,
      [id,userId]
    );
    const item=r.rows[0];
    if(!item)return {error:"media_not_found"};
    if(item.storage_mode==="inline"){
      if(!Buffer.isBuffer(item.inline_bytes))
        return {error:"media_not_found"};
      await dbQuery("delete from media_download_tokens where expires_at<=now()");
      const token=randomUUID();
      await dbQuery(
        "insert into media_download_tokens(token,asset_id) values($1,$2)",
        [token,id]
      );
      return {
        url:`/api/media/content/${token}`,
        filename:item.file_name,
        mime:item.mime,
        bytes:Number(item.byte_length),
        expiresIn:90
      };
    }
    if(!mediaReady)return {error:"media_unavailable"};
    const url=await getSignedUrl(client,new GetObjectCommand({
      Bucket:bucket,
      Key:item.object_key,
      ResponseContentDisposition:`attachment; filename="${item.file_name.replace(/["\\]/g,"_")}"`,
      ResponseContentType:item.mime
    }),{expiresIn:90});
    return {
      url,
      filename:item.file_name,
      mime:item.mime,
      bytes:Number(item.byte_length),
      expiresIn:90
    };
  },

  async inlineDownload(token){
    if(!inlineMediaReady)return {error:"media_unavailable"};
    const r=await dbQuery(`
      select a.mime,a.file_name,a.byte_length,a.inline_bytes
      from media_download_tokens t
      join media_assets a on a.id=t.asset_id
      where t.token=$1 and t.expires_at>now()
        and a.storage_mode='inline'
        and a.uploaded_at is not null
      limit 1`,[token]);
    const item=r.rows[0];
    if(!item || !Buffer.isBuffer(item.inline_bytes))
      return {error:"media_not_found"};
    return {
      body:item.inline_bytes,
      filename:item.file_name,
      mime:item.mime,
      bytes:Number(item.byte_length)
    };
  }
};

export function registerMediaCleanup(app){
  app.get("/internal/media-cleanup",async(req,res)=>{
    res.set("Cache-Control","private, no-store");
    const secret=process.env.CRON_SECRET || "";
    if(process.env.MEDIA_ENABLE_UPLOADS==="false" || !mediaAvailable ||
       Buffer.byteLength(secret)<32)
      return res.status(404).json({error:"feature_unavailable"});
    const provided=typeof req.headers.authorization==="string" &&
      req.headers.authorization.startsWith("Bearer ")
      ? req.headers.authorization.slice(7) : "";
    const a=Buffer.from(provided,"utf8");
    const b=Buffer.from(secret,"utf8");
    if(a.length!==b.length || !timingSafeEqual(a,b))
      return res.status(401).json({error:"unauthorized"});
    try{
      const abandoned=await mediaStore.cleanupAbandoned(100);
      if(abandoned.error)return res.status(503).json({error:abandoned.error});
      const unreferenced=await mediaStore.cleanupUnreferenced(100);
      if(unreferenced.error)return res.status(503).json({error:unreferenced.error});
      return res.json({ok:true,...abandoned,abandoned,unreferenced});
    }catch(error){
      console.error(
        "Media cleanup failed",
        typeof error?.name==="string"?error.name:"unknown"
      );
      return res.status(503).json({error:"service_unavailable"});
    }
  });
}
