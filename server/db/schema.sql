-- Lumo PostgreSQL schema
create table if not exists users (
  id uuid primary key,
  username varchar(24) unique not null,
  display_name varchar(50) not null,
  password_hash text, -- nullable for legacy accounts; new registrations require a hash
  created_at timestamptz not null default now()
);
alter table users add column if not exists password_hash text;
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
create unique index if not exists messages_sender_client_id_uidx on messages(sender_id, client_message_id) where client_message_id is not null;
create index if not exists messages_sender_idx on messages(sender_id, created_at desc);
create index if not exists messages_recipient_idx on messages(recipient_id, created_at desc);

-- Per-user chat organization; intentionally does not modify message history.
create table if not exists conversation_prefs (
  owner_id uuid not null references users(id) on delete cascade,
  peer_id uuid not null references users(id) on delete cascade,
  pinned boolean not null default false,
  primary key (owner_id,peer_id)
);

-- Blocking prevents new direct messages in both directions; existing history is retained.
create table if not exists user_blocks (
  blocker_id uuid not null references users(id) on delete cascade,
  blocked_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id,blocked_id),
  constraint no_self_block check (blocker_id<>blocked_id)
);
create index if not exists user_blocks_blocked_idx on user_blocks(blocked_id,blocker_id);

-- Private groups: only current group members can access future messages.
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
