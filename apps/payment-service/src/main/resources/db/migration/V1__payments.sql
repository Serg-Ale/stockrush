create table payments (
    order_id bigint primary key,
    token text not null,
    result text not null check (result in ('APPROVED', 'DECLINED')),
    created_at timestamptz not null default now()
);
