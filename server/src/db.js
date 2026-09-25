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
  const client=await pool.connect();
  const migrationLock=[1280724580,20260924];
  try {
    await client.query("select pg_advisory_lock($1,$2)",migrationLock);
  await client.query(`create table if not exists users (id uuid primary key, username varchar(24) unique not null, display_name varchar(50) not null, created_at timestamptz not null default now())`);
  await client.query(`alter table users add column if not exists password_hash text`);
  await client.query(`alter table users add column if not exists bio varchar(160) not null default ''`);
  await client.query(`alter table users add column if not exists avatar_mime varchar(32)`);
  await client.query(`alter table users add column if not exists avatar_bytes bytea`);
  await client.query(`alter table users add column if not exists avatar_updated_at timestamptz`);
  await client.query(`create table if not exists sessions (
    token uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '30 days')
  )`);
  await client.query(`alter table sessions add column if not exists expires_at timestamptz`);
  await client.query(`update sessions set expires_at=created_at + interval '30 days' where expires_at is null`);
  await client.query(`alter table sessions alter column expires_at set default (now() + interval '30 days')`);
  await client.query(`alter table sessions alter column expires_at set not null`);
  await client.query(`create index if not exists sessions_expiry_idx on sessions(expires_at)`);
  await client.query(`create table if not exists messages (id uuid primary key, sender_id uuid not null references users(id) on delete cascade, recipient_id uuid not null references users(id) on delete cascade, text varchar(4000) not null, created_at timestamptz not null default now(), delivered_at timestamptz, read_at timestamptz)`);
  await client.query(`alter table messages add column if not exists client_message_id uuid`);
  await client.query(`alter table messages add column if not exists reply_to_message_id uuid`);
  await client.query(`alter table messages add column if not exists edited_at timestamptz`);
  await client.query(`alter table messages add column if not exists deleted_at timestamptz`);
  const replyFk=await client.query(
    "select 1 from pg_constraint where conname=$1 and conrelid='messages'::regclass",
    ["messages_reply_to_fk"]
  );
  if(!replyFk.rowCount) {
    await client.query(`alter table messages add constraint messages_reply_to_fk foreign key (reply_to_message_id) references messages(id) on delete set null`);
  }
  await client.query(`create index if not exists messages_reply_to_idx on messages(reply_to_message_id) where reply_to_message_id is not null`);
  await client.query(`create unique index if not exists messages_sender_client_id_uidx on messages(sender_id, client_message_id) where client_message_id is not null`);
  await client.query(`create index if not exists messages_sender_idx on messages(sender_id, created_at desc)`);
  await client.query(`create index if not exists messages_recipient_idx on messages(recipient_id, created_at desc)`);
  await client.query(`create table if not exists media_assets (
    id uuid primary key,
    owner_id uuid not null references users(id) on delete cascade,
    recipient_id uuid not null references users(id) on delete cascade,
    object_key text not null unique,
    mime varchar(120) not null,
    file_name varchar(80) not null,
    byte_length integer not null check(byte_length>0 and byte_length<=26214400),
    expires_at timestamptz not null default (now() + interval '1 day'),
    created_at timestamptz not null default now(),
    uploaded_at timestamptz,
    claimed_message_id uuid unique
  )`);
  await client.query(`alter table media_assets add column if not exists storage_mode varchar(8) not null default 's3'`);
  await client.query(`alter table media_assets add column if not exists inline_bytes bytea`);
  await client.query(`create index if not exists media_assets_owner_idx on media_assets(owner_id,created_at desc)`);
  await client.query(`create table if not exists media_inline_chunks (
    asset_id uuid not null references media_assets(id) on delete cascade,
    chunk_index integer not null check(chunk_index>=0 and chunk_index<16),
    byte_length integer not null check(byte_length>0 and byte_length<=3145728),
    body bytea not null,
    created_at timestamptz not null default now(),
    primary key(asset_id,chunk_index),
    constraint media_inline_chunk_length_ck check(octet_length(body)=byte_length)
  )`);
  await client.query(`create table if not exists media_download_tokens (
    token uuid primary key,
    asset_id uuid not null references media_assets(id) on delete cascade,
    expires_at timestamptz not null default (now() + interval '90 seconds'),
    created_at timestamptz not null default now()
  )`);
  await client.query(`create index if not exists media_download_tokens_expiry_idx on media_download_tokens(expires_at)`);
  await client.query(`alter table messages add column if not exists media_id uuid references media_assets(id)`);
  await client.query(`create index if not exists messages_media_idx on messages(media_id) where media_id is not null`);
  // Safe to repeat on old databases. Reaction ownership is enforced by the API
  // and by the foreign keys: deleting a message/account removes its reactions.
  await client.query(`create table if not exists message_reactions (
    message_id uuid not null references messages(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    emoji varchar(12) not null,
    created_at timestamptz not null default now(),
    primary key (message_id,user_id,emoji)
  )`);
  await client.query(`create index if not exists message_reactions_user_idx on message_reactions(user_id,created_at desc)`);
  await client.query(`create table if not exists user_blocks (
    blocker_id uuid not null references users(id) on delete cascade,
    blocked_id uuid not null references users(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id,blocked_id),
    constraint no_self_block check (blocker_id<>blocked_id)
  )`);
  await client.query(`create index if not exists user_blocks_blocked_idx on user_blocks(blocked_id,blocker_id)`);

  await client.query(`create table if not exists calls (
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
  await client.query(`create index if not exists calls_caller_recent_idx on calls(caller_id,created_at desc)`);
  await client.query(`create index if not exists calls_callee_recent_idx on calls(callee_id,created_at desc)`);
  await client.query(`create index if not exists calls_expires_idx on calls(expires_at)`);
  await client.query(`create table if not exists call_signals (
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
  await client.query(`create index if not exists call_signals_created_idx on call_signals(created_at)`);
  await client.query(`create table if not exists chat_groups (
    id uuid primary key,
    title varchar(80) not null,
    owner_id uuid not null references users(id),
    created_at timestamptz not null default now()
  )`);
  await client.query(`create table if not exists chat_group_members (
    group_id uuid not null references chat_groups(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    role varchar(10) not null check (role in ('owner','admin','member')),
    joined_at timestamptz not null default now(),
    primary key(group_id,user_id)
  )`);
  await client.query(`create index if not exists chat_group_members_user_idx on chat_group_members(user_id,group_id)`);
  await client.query(`create table if not exists chat_group_messages (
    id uuid primary key,
    group_id uuid not null references chat_groups(id) on delete cascade,
    sender_id uuid not null references users(id),
    text varchar(4000) not null,
    client_message_id uuid not null,
    created_at timestamptz not null default now(),
    unique(group_id,sender_id,client_message_id)
  )`);
  await client.query(`create index if not exists chat_group_messages_history_idx on chat_group_messages(group_id,created_at desc,id desc)`);
  await client.query(`alter table chat_group_messages add column if not exists reply_to_message_id uuid`);
  await client.query(`alter table chat_group_messages add column if not exists edited_at timestamptz`);
  await client.query(`alter table chat_group_messages add column if not exists deleted_at timestamptz`);
  await client.query(`create table if not exists chat_group_message_reactions (
    message_id uuid not null references chat_group_messages(id) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    emoji varchar(16) not null,
    created_at timestamptz not null default now(),
    primary key(message_id,user_id,emoji)
  )`);
  await client.query(`create index if not exists chat_group_message_reactions_message_idx on chat_group_message_reactions(message_id,created_at desc)`);
  await client.query(`alter table media_assets alter column recipient_id drop not null`);
  await client.query(`alter table media_assets add column if not exists group_id uuid`);
  await client.query(`alter table media_assets add column if not exists claimed_group_message_id uuid`);
  const mediaGroupFk=await client.query(
    "select 1 from pg_constraint where conname=$1 and conrelid='media_assets'::regclass",
    ["media_assets_group_fk"]
  );
  if(!mediaGroupFk.rowCount){
    await client.query(`alter table media_assets add constraint media_assets_group_fk foreign key(group_id) references chat_groups(id) on delete cascade`);
  }
  const mediaScopeCheck=await client.query(
    "select 1 from pg_constraint where conname=$1 and conrelid='media_assets'::regclass",
    ["media_assets_scope_ck"]
  );
  if(!mediaScopeCheck.rowCount){
    await client.query(`alter table media_assets add constraint media_assets_scope_ck check ((recipient_id is null) <> (group_id is null))`);
  }
  await client.query(`create unique index if not exists media_assets_claimed_group_uidx on media_assets(claimed_group_message_id) where claimed_group_message_id is not null`);
  const mediaClaimedGroupFk=await client.query(
    "select 1 from pg_constraint where conname=$1 and conrelid='media_assets'::regclass",
    ["media_assets_claimed_group_fk"]
  );
  if(!mediaClaimedGroupFk.rowCount){
    await client.query(`alter table media_assets add constraint media_assets_claimed_group_fk foreign key(claimed_group_message_id) references chat_group_messages(id) on delete set null`);
  }
  await client.query(`alter table chat_group_messages add column if not exists media_id uuid references media_assets(id) on delete set null`);
  await client.query(`create index if not exists chat_group_messages_media_idx on chat_group_messages(media_id) where media_id is not null`);
  const groupReplyFk=await client.query(
    "select 1 from pg_constraint where conname=$1 and conrelid='chat_group_messages'::regclass",
    ["chat_group_messages_reply_to_fk"]
  );
  if(!groupReplyFk.rowCount){
    await client.query(`alter table chat_group_messages add constraint chat_group_messages_reply_to_fk foreign key (reply_to_message_id) references chat_group_messages(id) on delete set null`);
  }
  await client.query(`create index if not exists chat_group_messages_reply_idx on chat_group_messages(reply_to_message_id) where reply_to_message_id is not null`);
  await client.query(`create table if not exists push_devices (
    session_token uuid primary key references sessions(token) on delete cascade,
    user_id uuid not null references users(id) on delete cascade,
    token_hash varchar(64) not null unique,
    fcm_token text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
  )`);
  await client.query(`create index if not exists push_devices_user_idx on push_devices(user_id)`);
  await client.query(`create table if not exists push_outbox (
    id bigserial primary key,
    direct_message_id uuid references messages(id) on delete cascade,
    group_message_id uuid references chat_group_messages(id) on delete cascade,
    session_token uuid not null references sessions(token) on delete cascade,
    recipient_id uuid not null references users(id) on delete cascade,
    status varchar(16) not null default 'pending'
      check(status in ('pending','sent','dropped')),
    attempts integer not null default 0,
    available_at timestamptz not null default now(),
    lease_until timestamptz,
    processed_at timestamptz,
    created_at timestamptz not null default now(),
    last_error_code varchar(40),
    constraint push_outbox_scope_ck
      check ((direct_message_id is null) <> (group_message_id is null))
  )`);
  await client.query(`alter table push_outbox add column if not exists direct_message_id uuid references messages(id) on delete cascade`);
  await client.query(`alter table push_outbox add column if not exists group_message_id uuid references chat_group_messages(id) on delete cascade`);
  const legacyPushMessageColumn=await client.query(
    `select is_nullable from information_schema.columns
     where table_schema=current_schema() and table_name='push_outbox' and column_name='message_id'`
  );
  if(legacyPushMessageColumn.rowCount){
    await client.query(`alter table push_outbox alter column message_id drop not null`);
    await client.query(`update push_outbox
      set direct_message_id=message_id
      where direct_message_id is null and group_message_id is null and message_id is not null`);
  }
  const pushScopeCheck=await client.query(
    "select 1 from pg_constraint where conname=$1 and conrelid='push_outbox'::regclass",
    ["push_outbox_scope_ck"]
  );
  if(!pushScopeCheck.rowCount){
    await client.query(`alter table push_outbox add constraint push_outbox_scope_ck
      check ((direct_message_id is null) <> (group_message_id is null))`);
  }
  await client.query(`create unique index if not exists push_outbox_direct_session_uidx
    on push_outbox(direct_message_id,session_token)
    where direct_message_id is not null`);
  await client.query(`create unique index if not exists push_outbox_group_session_uidx
    on push_outbox(group_message_id,session_token)
    where group_message_id is not null`);
  await client.query(`create index if not exists push_outbox_claim_idx
    on push_outbox(available_at,id) where status='pending'`);

  await client.query(`create or replace function lumo_enqueue_direct_push()
    returns trigger as $lumo$
    begin
      insert into push_outbox(direct_message_id,session_token,recipient_id)
      select new.id,p.session_token,new.recipient_id
      from push_devices p
      join sessions s on s.token=p.session_token
        and s.user_id=p.user_id and s.expires_at>now()
      where p.user_id=new.recipient_id
      on conflict do nothing;
      return new;
    end;
    $lumo$ language plpgsql`);
  const directPushTrigger=await client.query(
    `select p.proname
     from pg_trigger t join pg_proc p on p.oid=t.tgfoid
     where t.tgname=$1 and t.tgrelid='messages'::regclass and not t.tgisinternal`,
    ["lumo_message_push_outbox"]
  );
  if(directPushTrigger.rows[0]?.proname!=="lumo_enqueue_direct_push"){
    if(directPushTrigger.rowCount)
      await client.query(`drop trigger lumo_message_push_outbox on messages`);
    await client.query(`create trigger lumo_message_push_outbox
      after insert on messages
      for each row execute function lumo_enqueue_direct_push()`);
  }

  await client.query(`create or replace function lumo_enqueue_group_push()
    returns trigger as $lumo_group$
    begin
      insert into push_outbox(group_message_id,session_token,recipient_id)
      select new.id,p.session_token,m.user_id
      from chat_group_members m
      join push_devices p on p.user_id=m.user_id
      join sessions s on s.token=p.session_token
        and s.user_id=p.user_id and s.expires_at>now()
      where m.group_id=new.group_id
        and m.user_id<>new.sender_id
        and m.joined_at<=new.created_at
      on conflict do nothing;
      return new;
    end;
    $lumo_group$ language plpgsql`);
  const groupPushTrigger=await client.query(
    "select 1 from pg_trigger where tgname=$1 and tgrelid='chat_group_messages'::regclass and not tgisinternal",
    ["lumo_group_message_push_outbox"]
  );
  if(!groupPushTrigger.rowCount){
    await client.query(`create trigger lumo_group_message_push_outbox
      after insert on chat_group_messages
      for each row execute function lumo_enqueue_group_push()`);
  }
    return true;
  } finally {
    await client.query("select pg_advisory_unlock($1,$2)",migrationLock).catch(()=>{});
    client.release();
  }
}

export async function dbHealth() {
  if (!pool) return { configured:false };
  const r=await pool.query(`select now() as now,
    to_regclass('users') as users_table,
    to_regclass('sessions') as sessions_table,
    to_regclass('messages') as messages_table,
    to_regclass('media_assets') as media_assets_table,
    to_regclass('media_download_tokens') as media_download_tokens_table,
    to_regclass('media_inline_chunks') as media_inline_chunks_table,
    to_regclass('user_blocks') as user_blocks_table,
    to_regclass('calls') as calls_table,
    to_regclass('call_signals') as call_signals_table,
    to_regclass('chat_groups') as groups_table,
    to_regclass('chat_group_members') as group_members_table,
    to_regclass('chat_group_messages') as group_messages_table,
    to_regclass('push_devices') as push_devices_table,
    to_regclass('push_outbox') as push_outbox_table,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='chat_group_messages' and column_name='reply_to_message_id') as group_reply_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='chat_group_messages' and column_name='edited_at') as group_edited_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='chat_group_messages' and column_name='deleted_at') as group_deleted_column,
    to_regclass('chat_group_message_reactions') as group_reactions_table,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='chat_group_messages' and column_name='media_id') as group_media_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='media_assets' and column_name='group_id') as media_group_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='media_assets' and column_name='storage_mode') as media_storage_mode_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='media_assets' and column_name='inline_bytes') as media_inline_bytes_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='users' and column_name='password_hash') as password_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='users' and column_name='bio') as bio_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='users' and column_name='avatar_bytes') as avatar_bytes_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='users' and column_name='avatar_updated_at') as avatar_updated_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='messages' and column_name='reply_to_message_id') as reply_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='messages' and column_name='edited_at') as edited_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='messages' and column_name='deleted_at') as deleted_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='messages' and column_name='media_id') as media_column,
    exists(select 1 from information_schema.columns
      where table_schema=current_schema() and table_name='sessions' and column_name='expires_at') as session_expiry_column`);
  const row=r.rows[0];
  return { configured:true, ok:Boolean(row.users_table && row.sessions_table && row.messages_table && row.media_assets_table && row.media_download_tokens_table && row.media_inline_chunks_table && row.user_blocks_table && row.groups_table && row.group_members_table && row.group_messages_table && row.push_devices_table && row.push_outbox_table && row.group_reply_column && row.group_edited_column && row.group_deleted_column && row.group_reactions_table && row.group_media_column && row.media_group_column && row.media_storage_mode_column && row.media_inline_bytes_column && row.password_column && row.bio_column && row.avatar_bytes_column && row.avatar_updated_column && row.reply_column && row.edited_column && row.deleted_column && row.media_column && row.session_expiry_column && (process.env.LUMO_CALL_SIGNALING_ENABLED !== 'true' || (row.calls_table && row.call_signals_table))), now:row.now };
}
