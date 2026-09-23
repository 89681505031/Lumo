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

  // Call signaling data is separate from message history and is opt-in.
  await pool.query(`create table if not exists calls (
    id uuid primary key,
    caller_id uuid not null references users(id) on delete cascade,
    callee_id uuid not null references users(id) on delete cascade,
    kind text not null check(kind in ('audio','video')),
    status text not null default 'ringing' check(status in ('ringing','accepted','declined','ended')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    expires_at timestamptz not null default (now()+interval '45 seconds'),
    last_signal_seq integer not null default 0,
    constraint different_call_users check(caller_id<>callee_id)
  )`);
  await pool.query(`create index if not exists calls_caller_recent_idx on calls(caller_id,created_at desc)`);
  await pool.query(`create index if not exists calls_callee_recent_idx on calls(callee_id,created_at desc)`);
  await pool.query(`create table if not exists call_signals (
    call_id uuid not null references calls(id) on delete cascade,
    seq integer not null check(seq>0),
    sender_id uuid not null references users(id) on delete cascade,
    client_signal_id uuid not null,
    type text not null check(type in ('offer','answer','ice')),
    payload jsonb not null,
    created_at timestamptz not null default now(),
    primary key(call_id,seq),
    unique(call_id,sender_id,client_signal_id)
  )`);
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
    to_regclass('calls') as calls_table,
    to_regclass('call_signals') as call_signals_table,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='users' and column_name='password_hash') as password_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='sessions' and column_name='expires_at') as session_expiry_column`);
  const row=r.rows[0];
  return { configured:true, ok:Boolean(row.users_table && row.sessions_table && row.messages_table && row.conversation_prefs_table && row.user_blocks_table && row.password_column && row.session_expiry_column && (process.env.LUMO_CALL_SIGNALING_ENABLED !== 'true' || (row.calls_table && row.call_signals_table))), now:row.now };
}
