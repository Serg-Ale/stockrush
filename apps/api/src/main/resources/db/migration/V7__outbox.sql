create table outbox (
    id bigint generated always as identity primary key,
    aggregate_id bigint not null,
    type text not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    published_at timestamptz
);

create index outbox_pending_idx on outbox (id) where published_at is null;
