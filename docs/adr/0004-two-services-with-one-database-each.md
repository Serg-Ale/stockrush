# 0004. Two services, one database each

- Status: accepted
- Date: 2026-11-17

## Context

The project must show stock that is never oversold and payments that fail
partially, without tripling the infrastructure a demo has to run.

## Decision

1. **`api`** owns orders and stock in one Postgres database. Reserving stock
   and creating the order must be a single transaction, so they cannot be
   split across services.
2. **`payment-service`** owns payment decisions in its own Postgres database
   (`payments`, one row per order). It shares no tables with `api`.
3. **Only messages cross the boundary.** `api` publishes `order.created`
   (through the outbox, ADR 0002); `payment-service` replies with
   `payment.result` on the `payments` exchange. Both payloads are small JSON
   documents keyed by `orderId`.
4. **`paymentToken` travels in the event.** The client sends a magic token
   with the order (default `tok_approve`); `api` only forwards it and never
   interprets it. `payment-service` decides, and rejects tokens it does not
   know without requeue.
5. **Idempotent decision.** `payment-service` stores the first decision per
   order and answers every redelivery with the stored one, so a changed token
   on a retry cannot flip a result.

## Why not one service or three

- One service would remove the failure the project wants to demonstrate: a
  slow or broken payment step behind a queue.
- A separate order and stock service would turn the central invariant into a
  distributed transaction, the problem this design avoids.

## Consequences

- Two deployables, two pipelines and two sets of migrations.
- The broker topology is declared by both services (same arguments), until
  the retry and DLQ queues of ADR 0001 make `payment-service` its owner.
