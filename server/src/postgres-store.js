import { dbQuery, hasDatabase } from "./db.js";

const mapUser = r => ({
  id:r.id,
  username:r.username,
  displayName:r.display_name,
  bio:r.bio || "",
  hasAvatar:Boolean(r.has_avatar ?? r.avatar_bytes),
  avatarVersion:r.avatar_updated_at?.toISOString?.() || r.avatar_updated_at || ""
});
const mapMessage = r => ({
  id:r.id,
  from:r.sender_id,
  to:r.recipient_id,
  text:r.text,
  createdAt:r.created_at?.toISOString?.() || r.created_at,
  deliveredAt:r.delivered_at?.toISOString?.() || r.delivered_at || null,
  readAt:r.read_at?.toISOString?.() || r.read_at || null,
  clientMessageId:r.client_message_id || null,
  attachmentId:r.media_id || null,
  replyToMessageId:r.reply_to_message_id || null,
  replyPreviewText:r.reply_preview_text || null,
  replyPreviewFrom:r.reply_preview_from || null
});

export const postgresStore = {
  enabled: hasDatabase,
  async userBySession(token) {
    const r=await dbQuery("select u.id,u.username,u.display_name,u.bio,(u.avatar_bytes is not null) as has_avatar,u.avatar_updated_at from sessions s join users u on u.id=s.user_id where s.token=$1 and s.expires_at>now()",[token]);
    return r.rows[0] ? mapUser(r.rows[0]) : null;
  },
  async authUserByUsername(username) {
    const r=await dbQuery("select id,username,display_name,bio,password_hash,(avatar_bytes is not null) as has_avatar,avatar_updated_at from users where username=$1",[username]);
    return r.rows[0] || null;
  },
  async createSession(userId,token) {
    await dbQuery("insert into sessions(token,user_id) values($1,$2)",[token,userId]);
  },
  async revokeSession(userId,token) {
    await dbQuery("delete from sessions where user_id=$1 and token=$2",[userId,token]);
  },
  async createUser({id,username,displayName,passwordHash,token}) {
    try {
      const r=await dbQuery(`with created as (
        insert into users(id,username,display_name,password_hash) values($1,$2,$3,$4)
        returning id,username,display_name,bio,false as has_avatar,null::timestamptz as avatar_updated_at
      ), session as (
        insert into sessions(token,user_id) select $5,id from created
      ) select * from created`,[id,username,displayName,passwordHash,token]);
      return r.rows[0] ? mapUser(r.rows[0]) : null;
    } catch (error) {
      if(error?.code==="23505") return null;
      throw error;
    }
  },
  async updateUser(id,displayName,bio=null) {
    const r=await dbQuery("update users set display_name=$2, bio=coalesce($3,bio) where id=$1 returning id,username,display_name,bio,(avatar_bytes is not null) as has_avatar,avatar_updated_at",[id,displayName,bio]);
    return r.rows[0] ? mapUser(r.rows[0]) : null;
  },
  async searchUsers(me,q) {
    const term="%"+q+"%";
    const r=await dbQuery("select id,username,display_name,bio,(avatar_bytes is not null) as has_avatar,avatar_updated_at from users where id<>$1 and ($2='' or username ilike $3 or display_name ilike $3) order by display_name limit 50",[me,q,term]);
    return r.rows.map(mapUser);
  },
  async userExists(id) { const r=await dbQuery("select 1 from users where id=$1",[id]); return r.rowCount>0; },
  async conversations(me) { const r=await dbQuery(`select distinct on (x.peer_id) x.peer_id, u.username, u.display_name, u.bio, (u.avatar_bytes is not null) as has_avatar, u.avatar_updated_at, x.text, x.created_at from (select case when m.sender_id=$1 then m.recipient_id else m.sender_id end peer_id,m.text,m.created_at from messages m where m.sender_id=$1 or m.recipient_id=$1) x join users u on u.id=x.peer_id order by x.peer_id,x.created_at desc`,[me]); return r.rows.map(x=>({peer:{id:x.peer_id,username:x.username,displayName:x.display_name,bio:x.bio||"",hasAvatar:Boolean(x.has_avatar),avatarVersion:x.avatar_updated_at?.toISOString?.()||x.avatar_updated_at||""},lastMessage:x.text,lastAt:x.created_at?.toISOString?.()||x.created_at})).sort((a,b)=>String(b.lastAt).localeCompare(String(a.lastAt))); },
  async setAvatar(userId,mime,bytes) {
    const r=await dbQuery(
      "update users set avatar_mime=$2,avatar_bytes=$3,avatar_updated_at=now() where id=$1 returning id,username,display_name,bio,true as has_avatar,avatar_updated_at",
      [userId,mime,bytes]
    );
    return r.rows[0] ? mapUser(r.rows[0]) : null;
  },
  async removeAvatar(userId) {
    const r=await dbQuery(
      "update users set avatar_mime=null,avatar_bytes=null,avatar_updated_at=now() where id=$1 returning id,username,display_name,bio,false as has_avatar,avatar_updated_at",
      [userId]
    );
    return r.rows[0] ? mapUser(r.rows[0]) : null;
  },
  async avatar(userId) {
    const r=await dbQuery(
      "select avatar_mime,avatar_bytes,avatar_updated_at from users where id=$1",
      [userId]
    );
    const row=r.rows[0];
    if(!row || !row.avatar_bytes)return null;
    return {
      mime:row.avatar_mime || "image/jpeg",
      bytes:row.avatar_bytes,
      updatedAt:row.avatar_updated_at?.toISOString?.() || row.avatar_updated_at || ""
    };
  },
  async messages(me,peer) {
    const r=await dbQuery(
      `select m.*, left(reply.text,240) as reply_preview_text, reply.sender_id as reply_preview_from
       from messages m
       left join messages reply on reply.id=m.reply_to_message_id
       where (m.sender_id=$1 and m.recipient_id=$2)
          or (m.sender_id=$2 and m.recipient_id=$1)
       order by m.created_at`,
      [me,peer]
    );
    return r.rows.map(mapMessage);
  },
  async replyTarget(userId,peerId,messageId) {
    const r=await dbQuery(
      `select id,left(text,240) as text,sender_id from messages
       where id=$1 and (
         (sender_id=$2 and recipient_id=$3)
         or (sender_id=$3 and recipient_id=$2)
       )`,
      [messageId,userId,peerId]
    );
    const row=r.rows[0];
    return row ? {id:row.id,text:row.text,from:row.sender_id} : null;
  },
  async saveMessage(m) {
    const replyTo=m.replyToMessageId || null;
    if(m.clientMessageId){
      const inserted=await dbQuery(
        `insert into messages(
          id,sender_id,recipient_id,text,created_at,delivered_at,read_at,
          client_message_id,reply_to_message_id
        ) values($1,$2,$3,$4,$5,$6,$7,$8,$9)
        on conflict (sender_id,client_message_id)
          where client_message_id is not null do nothing
        returning *`,
        [m.id,m.from,m.to,m.text,m.createdAt,m.deliveredAt,m.readAt,m.clientMessageId,replyTo]
      );
      if(inserted.rows[0]) return {message:mapMessage(inserted.rows[0]),inserted:true};
      const existing=await dbQuery(
        "select * from messages where sender_id=$1 and client_message_id=$2",
        [m.from,m.clientMessageId]
      );
      const row=existing.rows[0];
      if(!row || row.recipient_id!==m.to || row.text!==m.text ||
         (row.reply_to_message_id || null)!==replyTo){
        const error=new Error("client_message_id_conflict");
        error.code="CLIENT_MESSAGE_ID_CONFLICT";
        throw error;
      }
      return {message:mapMessage(row),inserted:false};
    }
    const inserted=await dbQuery(
      `insert into messages(
        id,sender_id,recipient_id,text,created_at,delivered_at,read_at,reply_to_message_id
      ) values($1,$2,$3,$4,$5,$6,$7,$8) returning *`,
      [m.id,m.from,m.to,m.text,m.createdAt,m.deliveredAt,m.readAt,replyTo]
    );
    return {message:mapMessage(inserted.rows[0]),inserted:true};
  },
  // Only direct-message participants may react. No lookup ever returns an
  // unrelated user's message, including when the caller knows its UUID.
  async messageAccessible(messageId,userId) {
    const r=await dbQuery(
      "select 1 from messages where id=$1 and (sender_id=$2 or recipient_id=$2)",
      [messageId,userId]
    );
    return r.rowCount>0;
  },
  async addReaction(messageId,userId,emoji) {
    const result=await dbQuery(
      `insert into message_reactions(message_id,user_id,emoji)
       select m.id,$2,$3 from messages m
       where m.id=$1 and (m.sender_id=$2 or m.recipient_id=$2)
       on conflict (message_id,user_id,emoji) do nothing
       returning message_id`,
      [messageId,userId,emoji]
    );
    // Existing reaction is also a successful, idempotent PUT, but a
    // nonexistent/unrelated message must not be treated as successful.
    return result.rowCount>0 || await this.messageAccessible(messageId,userId);
  },
  async removeReaction(messageId,userId,emoji) {
    if(!await this.messageAccessible(messageId,userId))return false;
    await dbQuery(
      `delete from message_reactions r using messages m
       where r.message_id=m.id and m.id=$1 and r.user_id=$2 and r.emoji=$3
         and (m.sender_id=$2 or m.recipient_id=$2)`,
      [messageId,userId,emoji]
    );
    return true;
  },
  async reactionsWithPeer(userId,peerId) {
    const r=await dbQuery(
      `select r.message_id,r.user_id,r.emoji from message_reactions r
       join messages m on m.id=r.message_id
       where (m.sender_id=$1 and m.recipient_id=$2)
          or (m.sender_id=$2 and m.recipient_id=$1)
       order by m.created_at desc,r.created_at desc
       limit 500`,
      [userId,peerId]
    );
    return r.rows.map(x=>({messageId:x.message_id,userId:x.user_id,emoji:x.emoji}));
  },
  async markMessageDelivered(messageId,userId) {
    const r=await dbQuery("update messages set delivered_at=coalesce(delivered_at,now()) where id=$1 and recipient_id=$2 returning *",[messageId,userId]);
    return r.rows[0] ? mapMessage(r.rows[0]) : null;
  },
  async markDeliveredFromPeer(userId, peerId, messageIds) {
    if (!messageIds.length) return [];
    const r=await dbQuery(
      "update messages set delivered_at=now() where recipient_id=$1 and sender_id=$2 and id=any($3::uuid[]) and delivered_at is null returning *",
      [userId, peerId, messageIds]
    );
    return r.rows.map(mapMessage);
  },
  async markDelivered(userId) {
    const r=await dbQuery("update messages set delivered_at=coalesce(delivered_at,now()) where recipient_id=$1 and delivered_at is null returning *",[userId]);
    return r.rows.map(mapMessage);
  },
  async markRead(ids,userId) {
    const r=await dbQuery("update messages set delivered_at=coalesce(delivered_at,now()), read_at=coalesce(read_at,now()) where id=any($1::uuid[]) and recipient_id=$2 returning *",[ids,userId]);
    return r.rows.map(mapMessage);
  }
};
