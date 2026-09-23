import pg from "pg";
const { Pool } = pg;
export const pool = process.env.DATABASE_URL ? new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.DATABASE_SSL === "false" ? false : { rejectUnauthorized: process.env.DATABASE_SSL_REJECT_UNAUTHORIZED !== "false" },
  max: 5,
  connectionTimeoutMillis: 5000,
  idleTimeoutMillis: 10000,
  query_timeout: 10000
}) : null;
export const hasDatabase = Boolean(pool);
export async function dbQuery(text, params=[]) {
  if (!pool) throw new Error("DATABASE_URL is not configured");
  return pool.query(text, params);
}
export async function initDatabase() {
  if (!pool) return false;
  await pool.query(`create table if not exists users (id uuid primary key, username varchar(24) unique not null, display_name varchar(50) not null, created_at timestamptz not null default now())`);
  await pool.query(`alter table users add column if not exists password_hash text`);
  await pool.query(`create table if not exists sessions (
    token uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '30 days')
  )`);
  await pool.query(`alter table sessions add column if not exists expires_at timestamptz`);
  await pool.query(`update sessions set expires_at=created_at + interval '30 days' where expires_at is null`);
  await pool.query(`alter table sessions alter column expires_at set default (now() + interval '30 days')`);
  await pool.query(`alter table sessions alter column expires_at set not null`);
  await pool.query(`create index if not exists sessions_expiry_idx on sessions(expires_at)`);
  await pool.query(`create table if not exists messages (id uuid primary key, sender_id uuid not null references users(id) on delete cascade, recipient_id uuid not null references users(id) on delete cascade, text varchar(4000) not null, created_at timestamptz not null default now(), delivered_at timestamptz, read_at timestamptz)`);
  await pool.query(`alter table messages add column if not exists client_message_id uuid`);
  await pool.query(`create unique index if not exists messages_sender_client_id_uidx on messages(sender_id, client_message_id) where client_message_id is not null`);
  await pool.query(`create index if not exists messages_sender_idx on messages(sender_id, created_at desc)`);
  await pool.query(`create index if not exists messages_recipient_idx on messages(recipient_id, created_at desc)`);
  await pool.query(`create table if not exists conversation_prefs (
    owner_id uuid not null references users(id) on delete cascade,
    peer_id uuid not null references users(id) on delete cascade,
    pinned boolean not null default false,
    primary key (owner_id,peer_id)
  )`);
  await pool.query(`create table if not exists user_blocks (
    blocker_id uuid not null references users(id) on delete cascade,
    blocked_id uuid not null references users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id,blocked_id),
    constraint no_self_block check (blocker_id<>blocked_id)
  )`);
  await pool.query(`create index if not exists user_blocks_blocked_idx on user_blocks(blocked_id,blocker_id)`);
  await pool.query(`create table if not exists chat_groups (
    id uuid primary key,
    title varchar(80) not null,
    owner_id uuid not null references users(id),
    created_at timestamptz not null default now()
  )`);
  await pool.query(`create table if not exists chat_group_members (
    group_id uuid not null references chat_groups(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    role varchar(10) not null check (role in ('owner','admin','member')),
    joined_at timestamptz not null default now(),
    primary key(group_id,user_id)
  )`);
  await pool.query(`create index if not exists chat_group_members_user_idx on chat_group_members(user_id,group_id)`);
  await pool.query(`create table if not exists chat_group_messages (
    id uuid primary key,
    group_id uuid not null references chat_groups(id) on delete cascade,
    sender_id uuid not null references users(id),
    text varchar(4000) not null,
    client_message_id uuid not null,
    created_at timestamptz not null default now(),
    unique(group_id,sender_id,client_message_id)
  )`);
  await pool.query(`create index if not exists chat_group_messages_history_idx on chat_group_messages(group_id,created_at desc,id desc)`);

  return true;
}
export async function dbHealth() {
  if (!pool) return { configured:false };
  const r=await pool.query(`select now() as now,
    to_regclass('users') as users_table,
    to_regclass('sessions') as sessions_table,
    to_regclass('messages') as messages_table,
    to_regclass('conversation_prefs') as conversation_prefs_table,
    to_regclass('user_blocks') as user_blocks_table,
    to_regclass('chat_groups') as groups_table,
    to_regclass('chat_group_members') as group_members_table,
    to_regclass('chat_group_messages') as group_messages_table,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='users' and column_name='password_hash') as password_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='sessions' and column_name='expires_at') as session_expiry_column`);
  const row=r.rows[0];
  return { configured:true, ok:Boolean(row.users_table && row.sessions_table && row.messages_table && row.conversation_prefs_table && row.user_blocks_table && row.groups_table && row.group_members_table && row.group_messages_table && row.password_column && row.session_expiry_column), now:row.now };
}
