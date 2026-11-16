# Backlog — V2 e além

Itens que **precisam acontecer**, mas ficaram fora da v1 por decisão consciente de escopo (ver [discovery.md](discovery.md)). Cada item diz o que muda e por que vale.

## Arquitetura e dados

- **Estoque como terceiro serviço**, com saga de compensação. Hoje pedidos e estoque ficam juntos porque a regra de concorrência exige uma transação só; separar prova coordenação distribuída de verdade.
- **Outbox do Spring Modulith** no lugar do outbox feito à mão, e/ou **Debezium (CDC)**. Comparar com a implementação da v1 num ADR.
- **Replay da DLQ com reconciliação de pagamento.** Na v1 a DLQ é só de inspeção: o pedido já está cancelado quando a mensagem chega lá, e um resultado tardio é descartado (o primeiro vence). Replay só faz sentido junto com reconciliação (reabrir ou estornar um pagamento aprovado depois do cancelamento). Ver `docs/adr/0001-payment-retry-and-dlq-policy.md`.
- **Kafka como comparação de streaming** de eventos (log, partições, consumer groups) ao lado do RabbitMQ.

## Funcionalidades

- **Carrinho com vários itens** (hoje o pedido é de item único).
- **Cadastro de produtos e área administrativa** pela interface.
- **Notificações por e-mail** e **relatórios**.

## Segurança

- **Keycloak com OAuth2/OIDC** no lugar do JWT próprio, com papéis gerenciados.

## Infraestrutura e operação

- **Migração para ECS/Fargate** (ou Kubernetes), com banco e broker gerenciados, e infraestrutura inteira como código. A v1 sobe simples (EC2 + Compose); a V2 conta a história de evolução.
- **Tracing distribuído com OpenTelemetry** atravessando a fila (Tempo ou Jaeger), aproveitando o starter do Boot 4 (a verificar).
- **Teste de carga com k6**, com resultados publicados no README.
- **Testes de contrato** entre os serviços (Pact ou Spring Cloud Contract).
- **Virtual threads**: avaliar com medição própria (os números que circulam em blogs não têm benchmark).

## Front-end

- **Armazém 3D com Three.js** (via Angular Three ou Three.js puro em componente), visualizando o estoque em tempo real. Verificar compatibilidade com Angular sem zone.js antes de comprometer.
- **Domínio próprio** e URL definitiva (cerca de R$ 40 por ano), depois do deploy no ar.
- **Distribuição dos eventos SSE entre várias instâncias** da `api` (hoje uma instância só).
