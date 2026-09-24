-- Lumo PostgreSQL schema
create table if not exists users (
  id uuid primary key,
  username varchar(24) unique not null,
  display_name varchar(50) not null,
  password_hash text, -- nullable for legacy accounts; new registrations require a hash
  created_at timestamptz not null default now()
);
alter table users add column if not exists password_hash text;
alter table users add column if not exists bio varchar(160) not null default '';
alter table users add column if not exists avatar_mime varchar(32);
alter table users add column if not exists avatar_bytes bytea;
alter table users add column if not exists avatar_updated_at timestamptz;
create table if not exists sessions (
  token uuid primary key,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '30 days')
);
alter table sessions add column if not exists expires_at timestamptz;
update sessions set expires_at=created_at + interval '30 days' where expires_at is null;
alter table sessions alter column expires_at set default (now() + interval '30 days');
alter table sessions alter column expires_at set not null;
create index if not exists sessions_expiry_idx on sessions(expires_at);
create table if not exists messages (
  id uuid primary key,
  sender_id uuid not null references users(id) on delete cascade,
  recipient_id uuid not null references users(id) on delete cascade,
  text varchar(4000) not null,
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  read_at timestamptz,
  client_message_id uuid
);
alter table messages add column if not exists reply_to_message_id uuid;
alter table messages add column if not exists edited_at timestamptz;
alter table messages add column if not exists deleted_at timestamptz;
do 'begin if not exists (select 1 from pg_constraint where conname = ''messages_reply_to_fk'' and conrelid = ''messages''::regclass) then alter table messages add constraint messages_reply_to_fk foreign key (reply_to_message_id) references messages(id) on delete set null; end if; end';
create index if not exists messages_reply_to_idx on messages(reply_to_message_id) where reply_to_message_id is not null;
create unique index if not exists messages_sender_client_id_uidx on messages(sender_id, client_message_id) where client_message_id is not null;
create index if not exists messages_sender_idx on messages(sender_id, created_at desc);
create index if not exists messages_recipient_idx on messages(recipient_id, created_at desc);
create table if not exists media_assets (
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
);
create index if not exists media_assets_owner_idx on media_assets(owner_id,created_at desc);
alter table messages add column if not exists media_id uuid references media_assets(id);
create index if not exists messages_media_idx on messages(media_id) where media_id is not null;
-- Authenticated, idempotent direct-message reactions. Schema-only until feature flag.
create table if not exists message_reactions (
  message_id uuid not null references messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  emoji varchar(12) not null,
  created_at timestamptz not null default now(),
  primary key (message_id,user_id,emoji)
);
create index if not exists message_reactions_user_idx on message_reactions(user_id,created_at desc);
create table if not exists user_blocks (
  blocker_id uuid not null references users(id) on delete cascade,
  blocked_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id,blocked_id),
  constraint no_self_block check (blocker_id<>blocked_id)
);
create index if not exists user_blocks_blocked_idx on user_blocks(blocked_id,blocker_id);
create table if not exists calls (
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
);
create index if not exists calls_caller_recent_idx on calls(caller_id,created_at desc);
create index if not exists calls_callee_recent_idx on calls(callee_id,created_at desc);
create index if not exists calls_expires_idx on calls(expires_at);
create table if not exists call_signals (
  call_id uuid not null references calls(id) on delete cascade,
  seq integer not null check(seq>0),
  sender_id uuid not null references users(id) on delete cascade,
  client_signal_id uuid not null,
  type text not null check(type in ('offer','answer','ice')),
  payload jsonb not null,
  created_at timestamptz not null default now(),
  primary key(call_id,seq),
  unique(call_id,sender_id,client_signal_id)
);
create index if not exists call_signals_created_idx on call_signals(created_at);
create table if not exists chat_groups (
  id uuid primary key,
  title varchar(80) not null,
  owner_id uuid not null references users(id),
  created_at timestamptz not null default now()
);
create table if not exists chat_group_members (
  group_id uuid not null references chat_groups(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role varchar(10) not null check (role in ('owner','admin','member')),
  joined_at timestamptz not null default now(),
  primary key(group_id,user_id)
);
create index if not exists chat_group_members_user_idx on chat_group_members(user_id,group_id);
create table if not exists chat_group_messages (
  id uuid primary key,
  group_id uuid not null references chat_groups(id) on delete cascade,
  sender_id uuid not null references users(id),
  text varchar(4000) not null,
  client_message_id uuid not null,
  created_at timestamptz not null default now(),
  unique(group_id,sender_id,client_message_id)
);
create index if not exists chat_group_messages_history_idx on chat_group_messages(group_id,created_at desc,id desc);
alter table chat_group_messages add column if not exists reply_to_message_id uuid;
alter table chat_group_messages add column if not exists edited_at timestamptz;
alter table chat_group_messages add column if not exists deleted_at timestamptz;
create table if not exists chat_group_message_reactions (
  message_id uuid not null references chat_group_messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  emoji varchar(16) not null,
  created_at timestamptz not null default now(),
  primary key(message_id,user_id,emoji)
);
create index if not exists chat_group_message_reactions_message_idx on chat_group_message_reactions(message_id,created_at desc);
do 'begin if not exists (select 1 from pg_constraint where conname = ''chat_group_messages_reply_to_fk'' and conrelid = ''chat_group_messages''::regclass) then alter table chat_group_messages add constraint chat_group_messages_reply_to_fk foreign key (reply_to_message_id) references chat_group_messages(id) on delete set null; end if; end';
create index if not exists chat_group_messages_reply_idx on chat_group_messages(reply_to_message_id) where reply_to_message_id is not null;
