create table if not exists ai_thread (
    thread_id uuid primary key,
    tenant_id varchar(64) not null,
    environment varchar(64),
    user_id varchar(128),
    component_type varchar(64) not null,
    component_id varchar(255) not null,
    route_key varchar(255),
    title varchar(120),
    status varchar(16) not null,
    summary text,
    schema_hash varchar(128),
    variant_id varchar(128),
    last_config_etag varchar(128),
    created_at timestamptz not null,
    last_used_at timestamptz not null,
    version bigint not null default 1
);

create table if not exists ai_message (
    thread_id uuid not null,
    seq int not null,
    role varchar(16) not null,
    turn_id uuid,
    content text,
    token_est int,
    redacted boolean not null default false,
    created_at timestamptz not null,
    primary key (thread_id, seq),
    constraint fk_ai_message_thread foreign key (thread_id) references ai_thread(thread_id)
);

create index if not exists idx_ai_message_thread_turn_role
    on ai_message (thread_id, turn_id, role);

create index if not exists idx_ai_message_thread_created
    on ai_message (thread_id, created_at desc);

create table if not exists ai_action (
    thread_id uuid not null,
    turn_id uuid not null,
    action_type varchar(64) not null,
    payload jsonb,
    created_at timestamptz not null,
    primary key (thread_id, turn_id, action_type),
    constraint fk_ai_action_thread foreign key (thread_id) references ai_thread(thread_id)
);

create index if not exists idx_ai_action_thread_turn
    on ai_action (thread_id, turn_id);
