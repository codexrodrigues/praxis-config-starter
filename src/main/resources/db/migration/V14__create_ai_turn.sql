create table if not exists ai_turn (
    thread_id uuid not null,
    turn_id uuid not null,
    status varchar(16) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (thread_id, turn_id),
    constraint fk_ai_turn_thread foreign key (thread_id) references ai_thread(thread_id)
);

create index if not exists idx_ai_turn_status_expires
    on ai_turn (status, expires_at);
