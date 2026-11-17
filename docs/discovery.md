# stockrush — discovery (resultado do /grill-me)

Status: registro histórico das decisões da entrevista (2026-11-16). Elas já viraram a spec (issue #1) e os tickets (#2 a #37). **A fonte de verdade agora é a spec, o `CONTEXT.md` e os ADRs**; onde divergem deste arquivo, vale a spec. Divergências conhecidas: a spec acrescentou o token `tok_dead`, o resultado `EXHAUSTED` e a política de DLQ do ADR 0001; e trata o Vitest no Angular como opcional, enquanto aqui ele foi combinado como parte da estratégia de testes.
Data: 2026-11-16. Idioma deste arquivo: pt-BR (documento de trabalho); o repositório em si segue a regra de idioma abaixo.

## Objetivo

Projeto de portfólio para vaga de full-stack pleno (segunda frente: AI Engineer). Prova Java/Spring + Angular, a "capacidade de aprender" sem experiência comercial nessas stacks, e cobre os requisitos de arquitetura e cloud mais pedidos nas vagas.

Evidência de demanda (981 vagas coletadas pelo `job-agent`, 183 no alvo, 131 aceitas pelas regras do agente; contagem de menções, não de requisitos obrigatórios):

- CI/CD 54%, AWS 40%, testes automatizados 40%, microsserviços 30%, filas/mensageria 27%, observabilidade 32%.
- Vagas com Java/Spring: 94 de 286; Kafka 29, RabbitMQ 25, SQS/SNS 16; PostgreSQL 26, Oracle 23.
- Vagas com Angular: 40; quase sempre junto de Java ou .NET (60% Java), REST (80%), Docker, CI/CD. Pedem "Angular 15+/17+". Nenhuma pede motion/3D.
- Limites: amostra quase toda do Remotar (viés remoto), poucas faixas salariais, muitas vagas de consultoria que citam Angular de passagem.

## Domínio

Pedidos e pagamento com estoque limitado. A disputa pelas últimas unidades é a regra central: muitos pedidos simultâneos, nenhuma venda além do estoque.

## Escopo da v1

1. Catálogo e estoque: listar produtos com quantidade disponível (carga inicial por seed, sem tela de cadastro).
2. Criar pedido (item único): reserva o estoque na hora e publica o evento.
3. Pagamento assíncrono no `payment-service`: aprovado, recusado ou falha temporária (retentativa e dead-letter).
4. Efeito do resultado: aprovado confirma o pedido; recusado ou esgotado cancela e devolve o estoque.
5. Acompanhar pedidos: lista com status em tempo real.
6. Painel de demonstração: dispara N pedidos simultâneos sobre um produto com estoque limitado e mostra vendidos, rejeitados e estoque final.

Fora da v1: cadastro de produtos pela interface, carrinho com vários itens, endereço, frete, e-mail, relatórios. Tudo isso está em [BACKLOG.md](BACKLOG.md).

## Decisões

| Área | Decisão | Por quê |
|---|---|---|
| Arquitetura | Dois serviços: `api` (pedidos + estoque, módulos isolados) e `payment-service`; um banco por serviço | Pedidos e estoque precisam de transação no mesmo banco; separar o pagamento prova mensageria e falha parcial sem triplicar infraestrutura |
| Stack | Java 25 (LTS), Spring Boot 4.1.x (Spring Framework 7), Angular 22 | Versões atuais; confirmar a versão exata de cada uma no dia de criar os projetos |
| Mensageria | RabbitMQ; retentativa com backoff exponencial e limite; dead-letter queue | 2ª tecnologia mais citada; simples de explicar; cobre confirmação, retentativa e DLQ |
| Dados | PostgreSQL; Spring Data JPA/Hibernate; Flyway | JPA é o que as vagas Java cobram; Flyway versiona o schema |
| Estoque | Decremento atômico condicional (`UPDATE ... WHERE quantidade >= :n`); teste de concorrência (200 threads x 50 unidades) com Postgres real via Testcontainers; ADR comparando com lock pessimista e otimista | Mais simples, correto e escalável; o teste é a prova |
| Entrega de eventos | Outbox transacional feito à mão (tabela `outbox`, publicador com `FOR UPDATE SKIP LOCKED`), consumidor idempotente; Spring Modulith **só** para verificar fronteiras entre módulos | Evita o problema de escrita dupla banco/fila; o ADR registra que o Modulith oferece outbox pronto |
| API | `Idempotency-Key` no `POST /orders`; erros em Problem Details (RFC 9457); OpenAPI code-first (springdoc) com cliente Angular gerado | Idempotência na entrada; contrato tipado entre back e front |
| Pagamento simulado | Tokens mágicos no estilo Stripe (`tok_approve`, `tok_decline`, `tok_flaky`, `tok_slow`) + controle de caos no painel (falhas e latência em tempo de execução) | Testes determinísticos e resiliência visível na demonstração |
| Segurança | Spring Security, JWT stateless, BCrypt, papéis `CLIENTE` e `ADMIN`, `@PreAuthorize`, limite de taxa; usuário `ADMIN` de demonstração limitado ao painel | Painel público não pode ser abusado; prova Spring Security |
| Front-end | Angular 22, Angular Material (tema Material 3 próprio), Chart.js, signals nos serviços (sem NgRx), SSE consumido com RxJS, GSAP no painel respeitando `prefers-reduced-motion`, UI em português | Material é o esperado em vagas corporativas; RxJS é onde o Angular mais difere do React; GSAP é gratuito e dá a cara de produto ao painel |
| Observabilidade | Micrometer + Prometheus + Grafana (dashboard com métricas de negócio) e correlation id nos logs, atravessando a fila | 32% das vagas; tracing completo fica para a V2 |
| Testes | JUnit 5 + Mockito; Testcontainers com `@ServiceConnection`; testes de fatia onde bastar; Vitest no Angular; um end-to-end com Playwright; JaCoCo com piso de 80% **só** em domínio e aplicação | Testes e CI/CD são as duas maiores demandas |
| CI/CD e deploy | GitHub Actions (build, testes, cobertura, imagem, deploy no merge para `main`); Terraform; EC2 t3.medium com Docker Compose; Angular em S3 + CloudFront | Prova AWS, IaC e CI/CD com custo baixo (estimativa de US$ 30 a 40 por mês, desligável) |
| Repositório | Monorepo público: `apps/api`, `apps/payment-service`, `apps/web`, `infra/`, `docs/` | Um clone sobe tudo; recrutador vê o sistema inteiro |
| Idioma | Código, commits e ADRs em inglês; `README.md` em inglês e `README.pt-BR.md` | Padrão do ecossistema Java e vagas internacionais, sem perder o público brasileiro |

## Critério de pronto da v1

1. No ar, com HTTPS, painel de demonstração funcionando e usuário `ADMIN` de demonstração no README.
2. `docker compose up` sobe `api`, `payment-service`, Postgres, RabbitMQ, Prometheus, Grafana e o front.
3. `README.md` e `README.pt-BR.md`: problema em uma frase, diagrama de arquitetura (Mermaid versionado), GIF do painel, tabela de tecnologias, como rodar os testes.
4. ADRs curtos: dois serviços, decremento atômico, outbox à mão, RabbitMQ, SSE, JWT próprio, Modulith só para fronteiras.
5. `docs/BACKLOG.md` com a V2.
6. CI verde, cobertura publicada e dashboard do Grafana mostrado no README.

Ordem sugerida de construção: o deploy no ar vem por último, depois que tudo funciona localmente; mas o esqueleto com CI e um endpoint testado vem primeiro (tracer bullet).

## Pendências

- Conta na AWS: não foi respondido se já existe; só trava a hora do Terraform.
- Confirmar no spring.io a versão exata do Spring Boot (a 4.2 era esperada em novembro de 2026).
- Confirmar no guia de migração do Boot 4 se o `@MockBean` foi removido em favor do `@MockitoBean` (visto só em fonte secundária).
- Confirmar no Angular 22 o Vitest como runner padrão e o comportamento sem zone.js (visto só em fontes secundárias).
- Domínio próprio: depois do deploy no ar (opcional, cerca de R$ 40 por ano).

## Fontes consultadas

- [OpenJDK: JDK 25](https://openjdk.org/projects/jdk/25/) e [roteiro de suporte da Oracle](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Spring Boot 4.1.0](https://spring.io/blog/2026/06/10/spring-boot-4/) e [4.1.1](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/)
- [Spring Boot 4.0 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes) e [Jackson 3 no Spring](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/)
- [Angular v22](https://angular.dev/events/v22) e [versões do Angular](https://angular.dev/reference/releases)
- [Spring Boot: Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Spring Modulith: eventos](https://docs.spring.io/spring-modulith/reference/events.html)
- [Angular MCP](https://angular.dev/ai/mcp)
- [GSAP gratuito (Webflow)](https://webflow.com/blog/gsap-becomes-free), [licença](https://gsap.com/community/standard-license/) e [GSAP em frameworks](https://gsap.com/resources/frameworks/)
- [Angular Three](https://angularthree.netlify.app/learn/getting-started/introduction)
