# 0002. Transactional outbox written by hand

- Status: accepted
- Date: 2026-11-17

## Context

Creating an order reserves stock in Postgres and must tell `payment-service`
through the broker. Writing to both in one step is a dual write: a crash
between them leaves a reservation nobody pays for, or a payment for stock
that was never reserved.

## Decision

1. **Outbox table.** `api` inserts an `order.created` row into `outbox` in the
   same transaction as the order and the stock decrement. Either all three
   commit or none does.
2. **Publisher.** A scheduled `OutboxPublisher` claims unpublished rows with
   `select ... for update skip locked`, publishes each one with a broker
   confirm, then sets `published_at`, all in one transaction. Several `api`
   instances can run it at once without sending the same row twice in
   parallel.
3. **At-least-once.** If the process dies after the broker confirms but before
   the commit, the row goes out again. Consumers are idempotent, so this is
   accepted instead of chasing exactly-once.
4. **By hand, not Spring Modulith.** Spring Modulith ships an event
   publication registry that does the same job. Writing it by hand costs about
   100 lines, keeps the mechanism visible in the code and in this repo's
   story, and avoids tying the delivery guarantee to a framework's table
   layout. Modulith is still used, but only to verify module boundaries.

## Consequences

- Delivery latency is the poll interval (default 0.5 s).
- Published rows are kept; cleaning them up is not in v1.
- The poller holds a row lock while it talks to the broker, so a slow broker
  slows the publisher, never the order API.
