create table if not exists ai_turn_event (
    tenant_id varchar(64) not null,
    user_id varchar(128) not null,
    environment varchar(64),
    stream_id uuid not null,
    thread_id uuid not null,
    turn_id uuid not null,
    seq bigint not null,
    event_id uuid not null,
    event_type varchar(64) not null,
    payload jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    primary key (thread_id, turn_id, seq),
    constraint uk_ai_turn_event_event_id unique (event_id),
    constraint fk_ai_turn_event_turn foreign key (thread_id, turn_id) references ai_turn(thread_id, turn_id)
);

create index if not exists idx_ai_turn_event_stream_seq
    on ai_turn_event (stream_id, seq);

create index if not exists idx_ai_turn_event_tenant_user_created
    on ai_turn_event (tenant_id, user_id, created_at desc);
