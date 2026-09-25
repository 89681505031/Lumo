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
alter table users add column if not exists supabase_user_id uuid;
alter table users add column if not exists phone_hash varchar(64);
create unique index if not exists users_supabase_user_uidx on users(supabase_user_id) where supabase_user_id is not null;
create unique index if not exists users_phone_hash_uidx on users(phone_hash) where phone_hash is not null;
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
alter table media_assets alter column recipient_id drop not null;
alter table media_assets add column if not exists group_id uuid;
alter table media_assets add column if not exists claimed_group_message_id uuid;
do 'begin if not exists (select 1 from pg_constraint where conname = ''media_assets_group_fk'' and conrelid = ''media_assets''::regclass) then alter table media_assets add constraint media_assets_group_fk foreign key(group_id) references chat_groups(id) on delete cascade; end if; end';
do 'begin if not exists (select 1 from pg_constraint where conname = ''media_assets_scope_ck'' and conrelid = ''media_assets''::regclass) then alter table media_assets add constraint media_assets_scope_ck check ((recipient_id is null) <> (group_id is null)); end if; end';
create unique index if not exists media_assets_claimed_group_uidx on media_assets(claimed_group_message_id) where claimed_group_message_id is not null;
do 'begin if not exists (select 1 from pg_constraint where conname = ''media_assets_claimed_group_fk'' and conrelid = ''media_assets''::regclass) then alter table media_assets add constraint media_assets_claimed_group_fk foreign key(claimed_group_message_id) references chat_group_messages(id) on delete set null; end if; end';
alter table chat_group_messages add column if not exists media_id uuid references media_assets(id) on delete set null;
create index if not exists chat_group_messages_media_idx on chat_group_messages(media_id) where media_id is not null;
do 'begin if not exists (select 1 from pg_constraint where conname = ''chat_group_messages_reply_to_fk'' and conrelid = ''chat_group_messages''::regclass) then alter table chat_group_messages add constraint chat_group_messages_reply_to_fk foreign key (reply_to_message_id) references chat_group_messages(id) on delete set null; end if; end';
create index if not exists chat_group_messages_reply_idx on chat_group_messages(reply_to_message_id) where reply_to_message_id is not null;
create table if not exists push_devices (
  session_token uuid primary key references sessions(token) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  token_hash varchar(64) not null unique,
  fcm_token text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists push_devices_user_idx on push_devices(user_id);
create table if not exists push_outbox (
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
);
/* Compatibility with the earlier direct-only experimental push schema. */
alter table push_outbox add column if not exists direct_message_id uuid references messages(id) on delete cascade;
alter table push_outbox add column if not exists group_message_id uuid references chat_group_messages(id) on delete cascade;
do 'begin if exists (
  select 1 from information_schema.columns
  where table_schema=current_schema() and table_name=''push_outbox'' and column_name=''message_id''
) then
  execute ''alter table push_outbox alter column message_id drop not null'';
  execute ''update push_outbox set direct_message_id=message_id where direct_message_id is null and group_message_id is null and message_id is not null'';
end if; end';
do 'begin if not exists (
  select 1 from pg_constraint
  where conname=''push_outbox_scope_ck'' and conrelid=''push_outbox''::regclass
) then
  alter table push_outbox add constraint push_outbox_scope_ck
    check ((direct_message_id is null) <> (group_message_id is null));
end if; end';
create unique index if not exists push_outbox_direct_session_uidx
  on push_outbox(direct_message_id,session_token)
  where direct_message_id is not null;
create unique index if not exists push_outbox_group_session_uidx
  on push_outbox(group_message_id,session_token)
  where group_message_id is not null;
create index if not exists push_outbox_claim_idx
  on push_outbox(available_at,id) where status='pending';

create or replace function lumo_enqueue_direct_push() returns trigger as '
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
' language plpgsql;
do 'begin if exists (
  select 1 from pg_trigger t join pg_proc p on p.oid=t.tgfoid
  where t.tgname = ''lumo_message_push_outbox''
    and t.tgrelid = ''messages''::regclass
    and not t.tgisinternal
    and p.proname <> ''lumo_enqueue_direct_push''
) then execute ''drop trigger lumo_message_push_outbox on messages''; end if; end';
do 'begin if not exists (
  select 1 from pg_trigger
  where tgname = ''lumo_message_push_outbox''
    and tgrelid = ''messages''::regclass and not tgisinternal
) then execute ''create trigger lumo_message_push_outbox after insert on messages for each row execute function lumo_enqueue_direct_push()''; end if; end';

create or replace function lumo_enqueue_group_push() returns trigger as '
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
' language plpgsql;
do 'begin if not exists (
  select 1 from pg_trigger
  where tgname = ''lumo_group_message_push_outbox''
    and tgrelid = ''chat_group_messages''::regclass and not tgisinternal
) then execute ''create trigger lumo_group_message_push_outbox after insert on chat_group_messages for each row execute function lumo_enqueue_group_push()''; end if; end';
