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

-- Upload metadata never stores media bytes. Private S3 objects remain inaccessible
-- except through short-lived signed upload/download URLs.
create table if not exists media_assets (
  id uuid primary key,
  owner_id uuid not null references users(id) on delete cascade,
  recipient_id uuid not null references users(id) on delete cascade,
  object_key text not null unique,
  mime varchar(80) not null,
  file_name varchar(80) not null,
  byte_length integer not null check(byte_length>0 and byte_length<=26214400),
  expires_at timestamptz not null default (now() + interval '1 day'),
  created_at timestamptz not null default now(),
  uploaded_at timestamptz,
  claimed_message_id uuid unique
);
create index if not exists media_assets_owner_idx on media_assets(owner_id,created_at desc);
alter table messages add column if not exists media_id uuid references media_assets(id);
