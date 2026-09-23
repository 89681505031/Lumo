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


-- Feature-gated cross-instance WebRTC signaling; not media storage.
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
