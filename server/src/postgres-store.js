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
    const c=await dbQuery("select 1 from users where username=$1",[username]);
    if(c.rowCount) return null;
    await dbQuery("insert into users(id,username,display_name) values($1,$2,$3)",[id,username,displayName]);
    await dbQuery("insert into sessions(token,user_id) values($1,$2)",[token,id]);
    return {id,username,displayName};
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
  async messages(me,peer) {
    const r=await dbQuery("select * from messages where (sender_id=$1 and recipient_id=$2) or (sender_id=$2 and recipient_id=$1) order by created_at",[me,peer]);
    return r.rows.map(mapMessage);
  },
  async saveMessage(m) {
    await dbQuery("insert into messages(id,sender_id,recipient_id,text,created_at,delivered_at,read_at) values($1,$2,$3,$4,$5,$6,$7)",[m.id,m.from,m.to,m.text,m.createdAt,m.deliveredAt,m.readAt]);
    return m;
  },
  async markRead(ids,userId) {
    const r=await dbQuery("update messages set read_at=coalesce(read_at,now()) where id=any($1::uuid[]) and recipient_id=$2 returning *",[ids,userId]);
    return r.rows.map(mapMessage);
  }
};
