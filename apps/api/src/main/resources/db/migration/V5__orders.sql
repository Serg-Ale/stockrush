create table orders (
    id bigint generated always as identity primary key,
    user_id bigint not null references users (id),
    product_id bigint not null references products (id),
    quantity integer not null check (quantity > 0),
    status text not null check (status in ('PENDING', 'CONFIRMED', 'CANCELLED')),
    created_at timestamptz not null default now()
);

create index orders_user_id_idx on orders (user_id);
