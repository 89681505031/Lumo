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

-- Push tokens are scoped to the authenticated session and disappear on logout.
-- The future sender MUST join sessions and require expires_at > now().
create table if not exists push_devices (
  session_token uuid primary key references sessions(token) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  token_hash char(64) unique not null,
  fcm_token text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists push_devices_user_idx on push_devices(user_id);

-- Durable, session-scoped notification jobs. Never store message content here.
-- A trigger enqueues only for devices with a currently valid session.
create table if not exists push_outbox (
  id bigserial primary key,
  message_id uuid not null references messages(id) on delete cascade,
  session_token uuid not null references sessions(token) on delete cascade,
  recipient_id uuid not null references users(id) on delete cascade,
  status varchar(16) not null default 'pending'
    check (status in ('pending','sent','dropped')),
  attempts int not null default 0,
  available_at timestamptz not null default now(),
  lease_until timestamptz,
  processed_at timestamptz,
  created_at timestamptz not null default now(),
  last_error_code varchar(40),
  unique (message_id,session_token)
);
create index if not exists push_outbox_claim_idx
  on push_outbox(available_at,id) where status='pending';

create or replace function lumo_enqueue_private_push() returns trigger as $$
begin
  insert into push_outbox(message_id,session_token,recipient_id)
  select new.id,p.session_token,new.recipient_id
  from push_devices p
  join sessions s on s.token=p.session_token and s.user_id=p.user_id and s.expires_at>now()
  where p.user_id=new.recipient_id
  on conflict (message_id,session_token) do nothing;
  return new;
end;
$$ language plpgsql;
drop trigger if exists lumo_message_push_outbox on messages;
create trigger lumo_message_push_outbox after insert on messages
  for each row execute function lumo_enqueue_private_push();
