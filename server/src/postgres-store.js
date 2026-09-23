import { dbQuery, hasDatabase } from "./db.js";

const mapUser = r => ({ id:r.id, username:r.username, displayName:r.display_name });
const mapMessage = r => ({ id:r.id, from:r.sender_id, to:r.recipient_id, text:r.text, createdAt:r.created_at?.toISOString?.() || r.created_at, deliveredAt:r.delivered_at?.toISOString?.() || r.delivered_at || null, readAt:r.read_at?.toISOString?.() || r.read_at || null, clientMessageId:r.client_message_id || null });

export const postgresStore = {
  enabled: hasDatabase,
  async userBySession(token) {
    const r=await dbQuery("select u.* from sessions s join users u on u.id=s.user_id where s.token=$1 and s.expires_at>now()",[token]);
    return r.rows[0] ? mapUser(r.rows[0]) : null;
  },
  async authUserByUsername(username) {
    const r=await dbQuery("select id,username,display_name,password_hash from users where username=$1",[username]);
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
        returning id,username,display_name
      ), session as (
        insert into sessions(token,user_id) select $5,id from created
      ) select * from created`,[id,username,displayName,passwordHash,token]);
      return r.rows[0] ? mapUser(r.rows[0]) : null;
    } catch (error) {
      if(error?.code==="23505") return null;
      throw error;
    }
  },
  async updateUser(id,displayName) {
    const r=await dbQuery("update users set display_name=$2 where id=$1 returning *",[id,displayName]);
    return r.rows[0] ? mapUser(r.rows[0]) : null;
  },
  async searchUsers(me,q) {
    const term="%"+q+"%";
    const r=await dbQuery(`select u.* from users u where u.id<>$1
      and ($2='' or u.username ilike $3 or u.display_name ilike $3)
      and not exists (
        select 1 from user_blocks b where
        (b.blocker_id=$1 and b.blocked_id=u.id) or
        (b.blocker_id=u.id and b.blocked_id=$1)
      ) order by u.display_name limit 50`,[me,q,term]);
    return r.rows.map(mapUser);
  },
  async userExists(id) { const r=await dbQuery("select 1 from users where id=$1",[id]); return r.rowCount>0; },
  async conversations(me) {
    const r=await dbQuery(`
      select latest.peer_id, u.username, u.display_name, latest.text, latest.created_at,
        coalesce(unread.unread_count,0) as unread_count, coalesce(p.pinned,false) as pinned
      from (
        select distinct on (x.peer_id) x.peer_id, x.text, x.created_at
        from (
          select case when m.sender_id=$1 then m.recipient_id else m.sender_id end as peer_id,
            m.text, m.created_at
          from messages m where m.sender_id=$1 or m.recipient_id=$1
        ) x
        order by x.peer_id, x.created_at desc
      ) latest
      join users u on u.id=latest.peer_id
      left join (
        select sender_id as peer_id, count(*)::integer as unread_count
        from messages where recipient_id=$1 and read_at is null
        group by sender_id
      ) unread on unread.peer_id=latest.peer_id
      left join conversation_prefs p on p.owner_id=$1 and p.peer_id=latest.peer_id
      order by coalesce(p.pinned,false) desc, latest.created_at desc, latest.peer_id
    `,[me]);
    return r.rows.map(x=>({
      peer:{id:x.peer_id,username:x.username,displayName:x.display_name},
      lastMessage:x.text,
      lastAt:x.created_at?.toISOString?.()||x.created_at,
      unreadCount:x.unread_count,
      pinned:x.pinned
    }));
  },
  async setPinned(me,peer,pinned) {
    const conversation=await dbQuery(
      "select 1 from messages where (sender_id=$1 and recipient_id=$2) or (sender_id=$2 and recipient_id=$1) limit 1",
      [me,peer]
    );
    if(conversation.rowCount===0)return false;
    if(pinned)await dbQuery(
      "insert into conversation_prefs(owner_id,peer_id,pinned) values($1,$2,true) on conflict(owner_id,peer_id) do update set pinned=true",
      [me,peer]
    );
    else await dbQuery("delete from conversation_prefs where owner_id=$1 and peer_id=$2",[me,peer]);
    return true;
  },
  async blockedBetween(a,b) {
    const r=await dbQuery(
      "select 1 from user_blocks where (blocker_id=$1 and blocked_id=$2) or (blocker_id=$2 and blocked_id=$1) limit 1",
      [a,b]
    );
    return r.rowCount>0;
  },
  async listBlocks(me) {
    const r=await dbQuery(
      "select u.* from user_blocks b join users u on u.id=b.blocked_id where b.blocker_id=$1 order by b.created_at desc",
      [me]
    );
    return r.rows.map(mapUser);
  },
  async blockUser(me,peer) {
    if(!await this.userExists(peer))return false;
    await dbQuery("insert into user_blocks(blocker_id,blocked_id) values($1,$2) on conflict do nothing",[me,peer]);
    return true;
  },
  async unblockUser(me,peer) {
    await dbQuery("delete from user_blocks where blocker_id=$1 and blocked_id=$2",[me,peer]);
  },
  async messages(me,peer) {
    const r=await dbQuery("select * from messages where (sender_id=$1 and recipient_id=$2) or (sender_id=$2 and recipient_id=$1) order by created_at",[me,peer]);
    return r.rows.map(mapMessage);
  },
  async saveMessage(m) {
    if(m.clientMessageId){
      const inserted=await dbQuery(`insert into messages(id,sender_id,recipient_id,text,created_at,delivered_at,read_at,client_message_id) values($1,$2,$3,$4,$5,$6,$7,$8) on conflict (sender_id,client_message_id) where client_message_id is not null do nothing returning *`,[m.id,m.from,m.to,m.text,m.createdAt,m.deliveredAt,m.readAt,m.clientMessageId]);
      if(inserted.rows[0]) return {message:mapMessage(inserted.rows[0]),inserted:true};
      const existing=await dbQuery("select * from messages where sender_id=$1 and client_message_id=$2",[m.from,m.clientMessageId]);
      const row=existing.rows[0];
      if(!row || row.recipient_id!==m.to || row.text!==m.text){const error=new Error("client_message_id_conflict");error.code="CLIENT_MESSAGE_ID_CONFLICT";throw error;}
      return {message:mapMessage(row),inserted:false};
    }
    await dbQuery("insert into messages(id,sender_id,recipient_id,text,created_at,delivered_at,read_at) values($1,$2,$3,$4,$5,$6,$7)",[m.id,m.from,m.to,m.text,m.createdAt,m.deliveredAt,m.readAt]);
    return {message:m,inserted:true};
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
