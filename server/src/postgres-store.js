import { dbQuery, hasDatabase } from "./db.js";

const mapUser = r => ({ id:r.id, username:r.username, displayName:r.display_name });
const mapMessage = r => ({ id:r.id, from:r.sender_id, to:r.recipient_id, text:r.text, createdAt:r.created_at?.toISOString?.() || r.created_at, deliveredAt:r.delivered_at?.toISOString?.() || r.delivered_at || null, readAt:r.read_at?.toISOString?.() || r.read_at || null });

export const postgresStore = {
  enabled: hasDatabase,
  async userBySession(token) {
    const r=await dbQuery("select u.* from sessions s join users u on u.id=s.user_id where s.token=$1",[token]);
    return r.rows[0] ? mapUser(r.rows[0]) : null;
  },
  async createUser({id,username,displayName,token}) {
    try {
      const r=await dbQuery(`with created as (
        insert into users(id,username,display_name) values($1,$2,$3)
        returning id,username,display_name
      ), session as (
        insert into sessions(token,user_id) select $4,id from created
      ) select * from created`,[id,username,displayName,token]);
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
    const r=await dbQuery("select * from users where id<>$1 and ($2='' or username ilike $3 or display_name ilike $3) order by display_name limit 50",[me,q,term]);
    return r.rows.map(mapUser);
  },
  async userExists(id) { const r=await dbQuery("select 1 from users where id=$1",[id]); return r.rowCount>0; },
  async conversations(me) { const r=await dbQuery(`select distinct on (peer_id) peer_id, username, display_name, text, created_at from (select case when m.sender_id=$1 then m.recipient_id else m.sender_id end peer_id,m.text,m.created_at from messages m where m.sender_id=$1 or m.recipient_id=$1) x join users u on u.id=x.peer_id order by peer_id,created_at desc`,[me]); return r.rows.map(x=>({peer:{id:x.peer_id,username:x.username,displayName:x.display_name},lastMessage:x.text,lastAt:x.created_at?.toISOString?.()||x.created_at})).sort((a,b)=>String(b.lastAt).localeCompare(String(a.lastAt))); },
  async messages(me,peer) {
    const r=await dbQuery("select * from messages where (sender_id=$1 and recipient_id=$2) or (sender_id=$2 and recipient_id=$1) order by created_at",[me,peer]);
    return r.rows.map(mapMessage);
  },
  async saveMessage(m) {
    if(m.clientMessageId){
      const r=await dbQuery(`insert into messages(id,sender_id,recipient_id,text,created_at,delivered_at,read_at,client_message_id) values($1,$2,$3,$4,$5,$6,$7,$8) on conflict (sender_id,client_message_id) where client_message_id is not null do update set sender_id=excluded.sender_id returning *`,[m.id,m.from,m.to,m.text,m.createdAt,m.deliveredAt,m.readAt,m.clientMessageId]);
      return mapMessage(r.rows[0]);
    }
    await dbQuery("insert into messages(id,sender_id,recipient_id,text,created_at,delivered_at,read_at) values($1,$2,$3,$4,$5,$6,$7)",[m.id,m.from,m.to,m.text,m.createdAt,m.deliveredAt,m.readAt]);
    return m;
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
