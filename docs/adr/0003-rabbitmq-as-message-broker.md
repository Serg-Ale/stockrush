# 0003. RabbitMQ as the message broker

- Status: accepted
- Date: 2026-11-17

## Context

`api` and `payment-service` talk asynchronously. The flow needs per-message
acknowledgement, retries with backoff, and a dead-letter queue (ADR 0001).

## Decision

RabbitMQ, with a durable direct exchange `orders` and one durable queue per
event type, bound by routing key (`order.created`). Messages are persistent
JSON, with the outbox row id as `messageId`. The publisher uses publisher
confirms, so a row is marked published only after the broker accepted it.

## Why not Kafka or SQS

- Kafka: a log is the wrong tool for work items that need individual retry
  and a dead-letter path. Per-message TTL wait queues and `x-death` counting
  give the ADR 0001 retry policy with no extra infrastructure.
- SQS: ties the demo to AWS and cannot run in `docker compose` without an
  emulator.
- RabbitMQ is also simple to run locally and to explain, and is common in
  Java/Spring job postings.

## Consequences

- Retry and DLQ topology lives in the broker declarations, owned by the
  consuming service.
- Ordering is only per queue; consumers must not depend on cross-event order.
