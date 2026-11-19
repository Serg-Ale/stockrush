# 0008. Module boundaries by package; Spring Modulith deferred

- Status: accepted
- Date: 2026-11-19

## Context

The discovery notes planned Spring Modulith *only* to verify boundaries between
the `api` modules, not for its event publication registry
([ADR 0002](0002-hand-written-transactional-outbox.md) explains why the outbox
is hand-written). While building the v1, the dependency was never added.

## Decision

Boundaries in `api` are plain packages, one per feature: `auth`, `catalog`,
`orders`, `outbox`, `ratelimit`, `correlation`, `openapi`. Their separation is
kept by review and by the package-private visibility the code already uses
(controllers, services and listeners are package-private; only `OutboxPublisher` is public). Spring Modulith is **not** a
dependency of v1.

## Consequences

- A cross-package dependency that breaks a boundary is not caught by a test.
  The cheapest guard when it starts to matter is Modulith's
  `ApplicationModules.of(...).verify()` as one test; it needs no event
  publication registry, so it does not reopen ADR 0002.
- `orders` and `outbox` do share a table (`outbox` rows are written inside the
  order transaction), so any boundary check would have to allow that edge on
  purpose.
- Recorded here so the README and the discovery notes do not claim a check that
  does not exist.
