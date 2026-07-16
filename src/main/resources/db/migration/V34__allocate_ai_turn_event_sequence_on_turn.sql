alter table ai_turn
    add column if not exists next_event_seq bigint;

update ai_turn turn_state
set next_event_seq = coalesce((
    select max(event.seq) + 1
    from ai_turn_event event
    where event.thread_id = turn_state.thread_id
      and event.turn_id = turn_state.turn_id
), 1)
where next_event_seq is null;

alter table ai_turn
    alter column next_event_seq set default 1;

alter table ai_turn
    alter column next_event_seq set not null;

alter table ai_turn
    add column if not exists terminal_event_type varchar(64);

update ai_turn turn_state
set terminal_event_type = (
    select event.event_type
    from ai_turn_event event
    where event.thread_id = turn_state.thread_id
      and event.turn_id = turn_state.turn_id
      and lower(event.event_type) in ('result', 'error', 'cancelled')
    order by event.seq desc
    limit 1
)
where turn_state.terminal_event_type is null
  and exists (
      select 1
      from ai_turn_event event
      where event.thread_id = turn_state.thread_id
        and event.turn_id = turn_state.turn_id
        and lower(event.event_type) in ('result', 'error', 'cancelled')
  );

alter table ai_turn
    add constraint ck_ai_turn_next_event_seq_positive
    check (next_event_seq >= 1);
