-- Finer reason kept for operators; the customer only ever sees cancellation_reason.
alter table orders add column internal_cancellation_reason text;
