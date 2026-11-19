# Architecture

The backends, their databases and RabbitMQ are drawn here, with the order
lifecycle and the data model. The container view (web, Prometheus, Grafana) and
the full critical-flow sequence, with their sources, are in the
[README](../README.md#architecture). Decisions behind these pictures are in
[`docs/adr`](adr/); terms are defined in [`CONTEXT.md`](../CONTEXT.md).

## 1. System context

Two services, one database each, talking only through RabbitMQ
([ADR 0004](adr/0004-two-services-with-one-database-each.md)). The customer
talks only to `api`.

```mermaid
flowchart LR
    client(["Customer / admin<br/>browser or HTTP client"])

    subgraph api["api (Spring Boot)"]
        rest["REST + SSE<br/>orders, catalog, auth"]
        pub["Outbox publisher"]
        cons["Payment result consumer"]
    end
    subgraph pay["payment-service (Spring Boot)"]
        listener["order.created consumer<br/>decides the payment"]
        chaos["Chaos control<br/>GET/PUT /chaos, ADMIN only"]
    end

    apidb[("api database<br/>users, products,<br/>orders, outbox")]
    paydb[("payments database<br/>one decision per order")]
    mq{{"RabbitMQ<br/>exchanges: orders, payments"}}

    client -->|"HTTPS: create order, list orders"| rest
    rest -.->|"SSE: order-status events"| client
    client -->|"admin JWT: tune failure rate and latency"| chaos

    rest --> apidb
    pub --> apidb
    cons --> apidb
    pub -->|"order.created"| mq
    mq -->|"order.created"| listener
    listener --> paydb
    listener -->|"payment.result"| mq
    mq -->|"payment.result"| cons
    chaos -.->|"in-memory settings"| listener

    classDef store fill:#eef6ee,stroke:#2e7d32,color:#10300f
    classDef broker fill:#e8f1ff,stroke:#3b6fd4,color:#12233f
    class apidb,paydb store
    class mq broker
```

## 2. Order lifecycle, happy path

From the click to a confirmed order. The reservation and the outbox row commit
together ([ADR 0002](adr/0002-hand-written-transactional-outbox.md),
[ADR 0005](adr/0005-atomic-conditional-stock-decrement.md)); everything after
that is asynchronous. Declined, exhausted and expired paths are in
[ADR 0001](adr/0001-payment-retry-and-dlq-policy.md).

```mermaid
sequenceDiagram
    participant C as Customer
    participant A as api
    participant DB as api database
    participant MQ as RabbitMQ
    participant P as payment-service

    C->>A: POST /orders (Idempotency-Key, productId, quantity, paymentToken)
    A->>DB: one transaction
    Note over A,DB: insert order PENDING, decrement stock, insert outbox row
    A-->>C: 201 order PENDING
    C->>A: GET /orders/stream (SSE, already open)

    loop every 0.5 s
        A->>DB: select unpublished outbox rows for update skip locked
    end
    A->>MQ: order.created (publisher confirm)
    A->>DB: mark outbox row published

    MQ->>P: order.created
    P->>P: decide from paymentToken
    P->>MQ: payment.result APPROVED
    MQ->>A: payment.result

    A->>DB: update order to CONFIRMED where status is PENDING
    A-->>C: SSE order-status CONFIRMED
```

## 3. Data model

Two databases that share no tables. `orders.product_id` and `orders.user_id`
are real foreign keys inside the `api` database; `payments.order_id` only
refers to an order by value, because it lives in another service.

```mermaid
erDiagram
    users ||--o{ orders : places
    products ||--o{ orders : "is reserved by"
    orders ||--o| outbox : "announces creation (by aggregate_id)"
    orders }o..o| payments : "decided in payment-service (by value)"

    users {
        bigint id PK
        text email UK
        text password_hash
        text role "CLIENTE or ADMIN"
        timestamptz created_at
    }
    products {
        bigint id PK
        text name UK
        integer available_quantity "never below 0"
    }
    orders {
        bigint id PK
        bigint user_id FK
        bigint product_id FK
        integer quantity
        text status "PENDING, CONFIRMED or CANCELLED"
        text idempotency_key "unique per user"
        text cancellation_reason "customer-facing"
        text internal_cancellation_reason "operators only"
        timestamptz created_at
    }
    outbox {
        bigint id PK
        bigint aggregate_id "the order id"
        text type "order.created"
        jsonb payload
        timestamptz created_at
        timestamptz published_at "null until sent"
    }
    payments {
        bigint order_id PK "no foreign key: other database"
        text token
        text result "APPROVED or DECLINED"
        timestamptz created_at
    }
```

## 4. Deployment on AWS

What `terraform apply` in [`infra/terraform`](../infra/terraform) creates. Visitors only ever talk to CloudFront;
the instance admits only CloudFront's address range. The cost is about US$ 37 a month while it runs and zero after
`terraform destroy`.

```mermaid
flowchart LR
    visitor(["Visitor<br/>browser"])

    subgraph aws["AWS account"]
        cf["CloudFront<br/>HTTPS, ACM certificate"]
        s3[("S3 bucket<br/>Angular build, private")]
        subgraph ec2["EC2 t3.medium (Docker Compose)"]
            api["api :8080"]
            pay["payment-service :8081"]
            infra["PostgreSQL, RabbitMQ,<br/>Prometheus, Grafana"]
        end
        ssm[("SSM Parameter Store<br/>JWT secret, admin password")]
    end

    visitor -->|"HTTPS: app and api paths"| cf
    cf -->|"OAC: static files"| s3
    cf -->|"HTTP: /auth /orders /products /me /admin /v3"| api
    cf -->|"HTTP: /chaos"| pay
    api --> infra
    pay --> infra
    ec2 -.->|"boot: read secrets"| ssm
```
