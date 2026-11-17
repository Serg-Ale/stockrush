alter table orders add column cancellation_reason text
    check (cancellation_reason in ('PAYMENT_DECLINED', 'PAYMENT_UNAVAILABLE', 'EXPIRED'));
