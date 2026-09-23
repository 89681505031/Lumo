import { randomUUID } from "node:crypto";
import { dbQuery, pool } from "./db.js";

const iso = value => value?.toISOString?.() || value || null;
const message = row => ({
  id:row.id, groupId:row.group_id, from:row.sender_id,
  text:row.text, createdAt:iso(row.created_at), clientMessageId:row.client_message_id
});
const group = row => ({
  id:row.id, title:row.title, ownerId:row.owner_id,
  createdAt:iso(row.created_at), role:row.role,
  memberCount:Number(row.member_count || 0),
  lastMessage:row.last_message ?? null,
  lastAt:iso(row.last_at)
});
const person = row => ({id:row.user_id,username:row.username,displayName:row.display_name,role:row.role});

export const groupStore = {
  async create(ownerId,title) {
    const client=await pool.connect();
    const id=randomUUID();
    try {
      await client.query("begin");
      const inserted=await client.query(
        "insert into chat_groups(id,title,owner_id) values($1,$2,$3) returning *",
        [id,title,ownerId]
      );
      await client.query(
        "insert into chat_group_members(group_id,user_id,role) values($1,$2,'owner')",
        [id,ownerId]
      );
      await client.query("commit");
      return {...group({...inserted.rows[0],role:"owner",member_count:1})};
    } catch(error) {
      await client.query("rollback").catch(()=>{});
      throw error;
    } finally { client.release(); }
  },
  async list(userId) {
    const rows=await dbQuery(`
      select g.*, mine.role, members.member_count, last.text as last_message,
        last.created_at as last_at
      from chat_group_members mine
      join chat_groups g on g.id=mine.group_id
      cross join lateral (
        select count(*)::integer as member_count
        from chat_group_members m where m.group_id=g.id
      ) members
      left join lateral (
        select gm.text, gm.created_at from chat_group_messages gm
        where gm.group_id=g.id and gm.created_at>=mine.joined_at
        order by gm.created_at desc,gm.id desc limit 1
      ) last on true
      where mine.user_id=$1
      order by coalesce(last.created_at,g.created_at) desc,g.id
      limit 100`,[userId]);
    return rows.rows.map(group);
  },
  async detail(userId,groupId) {
    const row=await dbQuery(`
      select g.*,mine.role,
        (select count(*)::integer from chat_group_members m where m.group_id=g.id) as member_count
      from chat_groups g
      join chat_group_members mine on mine.group_id=g.id and mine.user_id=$1
      where g.id=$2`,[userId,groupId]);
    if(!row.rows[0])return null;
    const members=await dbQuery(`
      select m.user_id,u.username,u.display_name,m.role
      from chat_group_members m join users u on u.id=m.user_id
      where m.group_id=$1
      order by case m.role when 'owner' then 0 when 'admin' then 1 else 2 end,
        lower(u.display_name),m.user_id`,[groupId]);
    // A removal racing the members query must not expose members to a former member.
    const stillMember=await dbQuery(
      "select 1 from chat_group_members where group_id=$1 and user_id=$2",
      [groupId,userId]
    );
    if(!stillMember.rowCount)return null;
    return {...group(row.rows[0]),members:members.rows.map(person)};
  },
  async membership(userId,groupId) {
    const row=await dbQuery(
      "select role,joined_at from chat_group_members where group_id=$1 and user_id=$2",
      [groupId,userId]
    );
    return row.rows[0] || null;
  },
  async invite(actorId,groupId,inviteeId) {
    const client=await pool.connect();
    try {
      await client.query("begin");
      // Serialize membership mutations and the 50-person limit per group.
      const groupRow=await client.query("select id from chat_groups where id=$1 for update",[groupId]);
      if(!groupRow.rowCount)return {error:"group_not_found"};
      const actor=await client.query(
        "select role from chat_group_members where group_id=$1 and user_id=$2",
        [groupId,actorId]
      );
      if(!["owner","admin"].includes(actor.rows[0]?.role))return {error:"forbidden"};
      if(actorId===inviteeId)return {error:"invalid_member"};
      const user=await client.query("select 1 from users where id=$1",[inviteeId]);
      if(!user.rowCount)return {error:"user_not_found"};
      const blocked=await client.query(`
        select 1 from user_blocks where
        (blocker_id=$1 and blocked_id=$2) or (blocker_id=$2 and blocked_id=$1)
        limit 1`,[actorId,inviteeId]);
      if(blocked.rowCount)return {error:"user_blocked"};
      const existing=await client.query(
        "select 1 from chat_group_members where group_id=$1 and user_id=$2",
        [groupId,inviteeId]
      );
      if(existing.rowCount)return {error:"already_member"};
      const count=await client.query("select count(*)::integer as n from chat_group_members where group_id=$1",[groupId]);
      if(count.rows[0].n>=50)return {error:"group_full"};
      await client.query(
        "insert into chat_group_members(group_id,user_id,role) values($1,$2,'member')",
        [groupId,inviteeId]
      );
      await client.query("commit");
      return {ok:true};
    } catch(error) {
      await client.query("rollback").catch(()=>{});
      throw error;
    } finally { client.release(); }
  },
  async setRole(actorId,groupId,userId,role) {
    const client=await pool.connect();
    try {
      await client.query("begin");
      const groupRow=await client.query("select owner_id from chat_groups where id=$1 for update",[groupId]);
      if(!groupRow.rowCount)return {error:"group_not_found"};
      if(groupRow.rows[0].owner_id!==actorId)return {error:"forbidden"};
      if(userId===actorId)return {error:"owner_role_immutable"};
      const updated=await client.query(
        "update chat_group_members set role=$3 where group_id=$1 and user_id=$2 and role<>'owner' returning role",
        [groupId,userId,role]
      );
      if(!updated.rowCount)return {error:"member_not_found"};
      await client.query("commit");
      return {role:updated.rows[0].role};
    } catch(error) {
      await client.query("rollback").catch(()=>{});
      throw error;
    } finally { client.release(); }
  },
  async remove(actorId,groupId,userId) {
    const client=await pool.connect();
    try {
      await client.query("begin");
      const g=await client.query("select owner_id from chat_groups where id=$1 for update",[groupId]);
      if(!g.rowCount)return {error:"group_not_found"};
      const actor=await client.query(
        "select role from chat_group_members where group_id=$1 and user_id=$2",
        [groupId,actorId]
      );
      if(!actor.rows[0])return {error:"group_not_found"};
      if(userId===g.rows[0].owner_id)return {error:"owner_cannot_leave"};
      const target=await client.query(
        "select role from chat_group_members where group_id=$1 and user_id=$2",
        [groupId,userId]
      );
      if(!target.rows[0])return {error:"member_not_found"};
      if(userId!==actorId &&
         !(actor.rows[0].role==="owner" ||
           (actor.rows[0].role==="admin" && target.rows[0].role==="member")))
        return {error:"forbidden"};
      await client.query(
        "delete from chat_group_members where group_id=$1 and user_id=$2",
        [groupId,userId]
      );
      await client.query("commit");
      return {ok:true};
    } catch(error) {
      await client.query("rollback").catch(()=>{});
      throw error;
    } finally { client.release(); }
  },
  async delete(actorId,groupId) {
    const row=await dbQuery(
      "delete from chat_groups where id=$1 and owner_id=$2 returning id",
      [groupId,actorId]
    );
    return row.rowCount>0;
  },
  async history(userId,groupId) {
    const membership=await this.membership(userId,groupId);
    if(!membership)return null;
    const rows=await dbQuery(`
      select gm.* from chat_group_members m
      join chat_group_messages gm on gm.group_id=m.group_id
        and gm.created_at>=m.joined_at
      where m.group_id=$1 and m.user_id=$2
      order by gm.created_at desc,gm.id desc limit 100`,[groupId,userId]);
    return rows.rows.reverse().map(message);
  },
  async send(userId,groupId,text,clientMessageId) {
    // Scope a new write to CURRENT membership in the insertion statement.
    // A retry is accepted only for the same group, sender, and original text.
    const id=randomUUID();
    const inserted=await dbQuery(`
      insert into chat_group_messages(id,group_id,sender_id,text,client_message_id)
      select $1,$2,$3,$4,$5 from chat_group_members m
      where m.group_id=$2 and m.user_id=$3
      on conflict(group_id,sender_id,client_message_id) do nothing returning *`,
      [id,groupId,userId,text,clientMessageId]);
    if(inserted.rows[0])return {message:message(inserted.rows[0]),inserted:true};
    const existing=await dbQuery(`
      select gm.* from chat_group_messages gm
      join chat_group_members m on m.group_id=gm.group_id and m.user_id=$2
      where gm.group_id=$1 and gm.sender_id=$2 and gm.client_message_id=$3
        and gm.created_at>=m.joined_at`,[groupId,userId,clientMessageId]);
    if(existing.rows[0]){
      if(existing.rows[0].text!==text)return {error:"client_message_id_conflict"};
      return {message:message(existing.rows[0]),inserted:false};
    }
    return {error:"group_not_found"};
  },
  async recipients(groupId,createdAt) {
    const rows=await dbQuery(
      "select user_id from chat_group_members where group_id=$1 and joined_at<=$2",
      [groupId,createdAt]
    );
    return rows.rows.map(r=>r.user_id);
  }
};
