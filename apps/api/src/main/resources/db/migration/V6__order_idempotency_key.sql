alter table orders add column idempotency_key text;

create unique index orders_user_idempotency_key_idx on orders (user_id, idempotency_key);
