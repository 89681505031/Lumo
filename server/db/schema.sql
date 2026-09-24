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
do 'begin if not exists (select 1 from pg_constraint where conname = ''messages_reply_to_fk'' and conrelid = ''messages''::regclass) then alter table messages add constraint messages_reply_to_fk foreign key (reply_to_message_id) references messages(id) on delete set null; end if; end';
create index if not exists messages_reply_to_idx on messages(reply_to_message_id) where reply_to_message_id is not null;
create unique index if not exists messages_sender_client_id_uidx on messages(sender_id, client_message_id) where client_message_id is not null;
create index if not exists messages_sender_idx on messages(sender_id, created_at desc);
create index if not exists messages_recipient_idx on messages(recipient_id, created_at desc);
-- Authenticated, idempotent direct-message reactions. Schema-only until feature flag.
create table if not exists message_reactions (
  message_id uuid not null references messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  emoji varchar(12) not null,
  created_at timestamptz not null default now(),
  primary key (message_id,user_id,emoji)
);
create index if not exists message_reactions_user_idx on message_reactions(user_id,created_at desc);
