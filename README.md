# stockrush

Order and payment platform with limited stock, built to show how a system behaves when many orders fight for the last units: no overselling, asynchronous payments, retries and a dead-letter queue.

**Status:** in development. The `api` skeleton (Spring Boot, Postgres, Flyway, CI) and the product catalog are done; work is tracked as GitHub issues (spec in #1, tickets #2 to #37). Not deployed yet. See [docs/discovery.md](docs/discovery.md) (decisions, pt-BR), [CONTEXT.md](CONTEXT.md) (glossary), [docs/adr](docs/adr) (architecture decisions) and [docs/BACKLOG.md](docs/BACKLOG.md) (V2).

Planned stack: Java 25 · Spring Boot 4 · Angular 22 · PostgreSQL · RabbitMQ · Docker · Terraform · AWS.
