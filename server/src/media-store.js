import { randomUUID } from "node:crypto";
import {
  S3Client, HeadObjectCommand, GetObjectCommand
} from "@aws-sdk/client-s3";
import { createPresignedPost } from "@aws-sdk/s3-presigned-post";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { dbQuery, pool } from "./db.js";

export const allowedMimeSizes=Object.freeze({
  "image/jpeg":8*1024*1024,
  "image/png":8*1024*1024,
  "image/webp":8*1024*1024,
  "audio/mp4":10*1024*1024,
  "audio/aac":10*1024*1024,
  "audio/ogg":10*1024*1024,
  "audio/mpeg":10*1024*1024,
  "video/mp4":25*1024*1024
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

const required=["MEDIA_BUCKET","MEDIA_REGION","MEDIA_ACCESS_KEY_ID","MEDIA_SECRET_ACCESS_KEY"];
export const mediaReady=required.every(key=>Boolean(process.env[key]?.trim()));
let client=null;
const bucket=mediaReady?process.env.MEDIA_BUCKET:null;
if(mediaReady){
  client=new S3Client({
    region:process.env.MEDIA_REGION,
    endpoint:normalizedEndpoint(process.env.MEDIA_ENDPOINT),
    forcePathStyle:true,
    credentials:{
      accessKeyId:process.env.MEDIA_ACCESS_KEY_ID,
      secretAccessKey:process.env.MEDIA_SECRET_ACCESS_KEY
    }
  });
}
const iso = d => d?.toISOString?.() || d || null;
const assetPublic = row => ({
  id:row.id, mime:row.mime, filename:row.file_name,
  bytes:Number(row.byte_length), uploaded:row.uploaded_at!==null,
  expiresAt:iso(row.expires_at)
});
const mappedMessage = row => ({
  id:row.id,from:row.sender_id,to:row.recipient_id,
  text:row.text,createdAt:iso(row.created_at),
  deliveredAt:iso(row.delivered_at),readAt:iso(row.read_at),
  clientMessageId:row.client_message_id || null,attachmentId:row.media_id || null
});
function displayName(value){
  const clean=value.normalize("NFKC").replace(/[^a-zA-Z0-9._-]/g,"_")
    .replace(/^\.+/,"").slice(0,80);
  return clean || "attachment";
}
export function validateMedia({mime,bytes,filename}){
  if(typeof mime!=="string" || !Object.hasOwn(allowedMimeSizes,mime))
    return {error:"unsupported_media_type"};
  if(!Number.isSafeInteger(bytes) || bytes<1 || bytes>allowedMimeSizes[mime])
    return {error:"invalid_media_size"};
  if(typeof filename!=="string" || filename.length<1 || filename.length>160)
    return {error:"invalid_file_name"};
  return {mime,bytes,filename:displayName(filename)};
}
function placeholder(mime) {
  return mime.startsWith("image/") ? "📷 Фото" :
    mime.startsWith("video/") ? "🎬 Видео" : "🎤 Голосовое сообщение";
}

export const mediaStore={
  async initiate(ownerId,recipientId,input){
    const checked=validateMedia(input);
    if(checked.error)return checked;
    if(!mediaReady)return {error:"media_unavailable"};
    const id=randomUUID(),key=`private/${ownerId}/${id}`;
    await dbQuery(`insert into media_assets(
      id,owner_id,recipient_id,object_key,mime,file_name,byte_length
    ) values($1,$2,$3,$4,$5,$6,$7)`,
    [id,ownerId,recipientId,key,checked.mime,checked.filename,checked.bytes]);
    // Server generates the key, exact media type and upload limit. Bucket must
    // be PRIVATE; browser/Android clients never get permanent storage keys.
    const upload=await createPresignedPost(client,{
      Bucket:bucket,Key:key,Expires:300,
      Fields:{"Content-Type":checked.mime},
      Conditions:[
        {"Content-Type":checked.mime},
        ["content-length-range",1,allowedMimeSizes[checked.mime]]
      ]
    });
    return {
      assetId:id,uploadUrl:upload.url,fields:upload.fields,
      expiresIn:300,maxBytes:allowedMimeSizes[checked.mime]
    };
  },
  async owned(ownerId,id){
    const r=await dbQuery("select * from media_assets where id=$1 and owner_id=$2",[id,ownerId]);
    return r.rows[0] || null;
  },
  async confirm(ownerId,id){
    if(!mediaReady)return {error:"media_unavailable"};
    const item=await this.owned(ownerId,id);
    if(!item)return {error:"media_not_found"};
    if(item.uploaded_at)return {asset:assetPublic(item)};
    if(new Date(item.expires_at).getTime()<=Date.now())return {error:"upload_expired"};
    let object;
    try {
      object=await client.send(new HeadObjectCommand({Bucket:bucket,Key:item.object_key}));
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
    const row=updated.rows[0] || (await this.owned(ownerId,id));
    return row?.uploaded_at ? {asset:assetPublic(row)} : {error:"upload_expired"};
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
      const text=caption || placeholder(a.mime);
      const blocked=await conn.query(`
        select 1 from user_blocks where
        (blocker_id=$1 and blocked_id=$2) or (blocker_id=$2 and blocked_id=$1) limit 1`,
        [ownerId,a.recipient_id]);
      if(blocked.rowCount)return {error:"user_blocked"};
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
        insert into messages(id,sender_id,recipient_id,text,client_message_id,media_id)
        values($1,$2,$3,$4,$5,$6)
        on conflict(sender_id,client_message_id)
          where client_message_id is not null do nothing returning *`,
        [messageId,ownerId,a.recipient_id,text,clientMessageId,id]);
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
      await conn.query("commit");committed=true;
      return {message:mappedMessage(inserted.rows[0]),inserted:true};
    }catch(error){
      throw error;
    }finally{
      if(!committed)await conn.query("rollback").catch(()=>{});
      conn.release();
    }
  },
  async signedDownload(userId,id){
    if(!mediaReady)return {error:"media_unavailable"};
    const r=await dbQuery(`
      select a.* from media_assets a
      left join messages m on m.id=a.claimed_message_id and m.media_id=a.id
      where a.id=$1 and a.uploaded_at is not null
        and (a.owner_id=$2 or (a.recipient_id=$2 and m.id is not null))`,
      [id,userId]);
    const item=r.rows[0];
    if(!item)return {error:"media_not_found"};
    const url=await getSignedUrl(client,new GetObjectCommand({
      Bucket:bucket,Key:item.object_key,
      ResponseContentDisposition:`attachment; filename="${item.file_name}"`,
      ResponseContentType:item.mime
    }),{expiresIn:90});
    return {url,filename:item.file_name,mime:item.mime,bytes:Number(item.byte_length),expiresIn:90};
  }
};
