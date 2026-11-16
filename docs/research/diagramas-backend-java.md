# Diagramas para um repo back-end Java de portfólio (stockrush)

Pesquisa feita em 2026-11-16. Convenção: **[FONTE]** = afirmado pela fonte citada; **[INFERÊNCIA]** = conclusão minha; **[NÃO VERIFICADO]** = não confirmei em fonte primária ou não testei. Números `#N` são os do GitHub (`gh issue`); o título de cada issue tem numeração própria, deslocada em 1 (ex.: `#32` no GitHub se chama "31 — READMEs...").

## 1. Resumo executivo e recomendação

1. **Use Mermaid dentro do Markdown e mais nada como padrão.** É a única linguagem de diagrama de engenharia que o GitHub renderiza nativamente em README, issue, PR e wiki (além de geoJSON, topoJSON e STL) [FONTE: docs.github.com]. PlantUML, D2 e Structurizr exigem gerar SVG/PNG e commitar [INFERÊNCIA, decorre da lista fechada de sintaxes].
2. **Faça 5 diagramas no v1, não 12.** Container, sequência do fluxo crítico, máquina de estados do pedido, topologia RabbitMQ (retry + DLQ) e a linha do tempo da race condition (no ADR do decremento atômico). Os dois primeiros vão no README; os outros, nos ADRs e em `docs/`.
3. **O que mais pesa para um entrevistador é o diagrama que mostra uma decisão difícil** (o que acontece se o publicador cai depois de publicar, ou se a mensagem vai para a DLQ). Um diagrama de caixas e setas genérico não pesa [INFERÊNCIA].
4. **C4 completo é exagero** para 3 apps. C4 diz que contexto + container "bastam para a maioria das equipes" e que não é preciso usar os 4 níveis [FONTE: c4model.com]. Use a notação C4 (título, legenda, setas rotuladas com tecnologia) num flowchart Mermaid, não o tipo `C4Container` do Mermaid (sem layout automático, sem legenda; ver §3).
5. **Prototipar antes de desenhar** só a máquina de estados do pedido com o caminho da DLQ (política ainda em aberto na issue #1) e o protocolo do outbox. **TLA+ não compensa** neste portfólio (§4).
6. **Claude gera Mermaid bem, mas erra sintaxe e layout de forma previsível; valide sempre renderizando.** Neste ambiente não há `mmdc`, `d2`, `plantuml` nem `structurizr-cli`; há `docker`, `node`, `java` e `google-chrome-stable`. Não consegui rodar o teste de renderização (§5).
7. Gere à parte, sem desenhar: OpenAPI (springdoc, issue #23), ER via SchemaSpy se quiser, diagrama de módulos via Spring Modulith `Documenter` (P3).

## 2. Lista priorizada de diagramas para o stockrush

Pré-requisito: `CONTEXT.md` e `docs/adr/` ainda não existem. Os rótulos dos diagramas devem usar o vocabulário já fixado nas issues (pedido, reserva, outbox, DLQ, `tok_flaky`...), e o glossário deve nascer antes dos diagramas para não haver sinônimos.

| # | Diagrama | Fluxo / issue que documenta | Ferramenta | Prioridade | Custo | Por quê |
|---|---|---|---|---|---|---|
| 1 | **Container (C4 nível 2)**: web, api (módulos pedidos/estoque), payment-service, 2 Postgres, RabbitMQ, Prometheus/Grafana; setas com protocolo (REST+JWT, SSE, AMQP, scrape) | #32 (README); atualizar em #30 (Compose) | Mermaid `flowchart` com `subgraph` | **P0** | Baixo (~1 h; muda pouco) | É o diagrama que o recrutador espera no topo do README; critério de pronto da v1 exige "diagrama de arquitetura (Mermaid versionado)" |
| 2 | **Sequência do fluxo crítico**: `POST /orders` → UPDATE condicional → linha no outbox na mesma transação → publicador (SKIP LOCKED) → RabbitMQ → payment-service → resultado → api confirma/cancela → SSE; com `autonumber` e correlation id anotado | #6, #8, #9, #10, #11, #12, #18, #22 | Mermaid `sequenceDiagram` | **P0** | Médio | Mostra de uma vez reserva atômica, outbox, assincronia e correlation id. Sequência com `alt`/`par` cobre aprovado x recusado |
| 3 | **Máquina de estados do pedido** (e do estoque reservado/devolvido): PENDENTE → CONFIRMADO / CANCELADO; transições por evento (aprovado, recusado, esgotado, DLQ); eventos duplicados ignorados | #6, #12, #13, #15 | Mermaid `stateDiagram-v2` | **P0** (depois do protótipo, §4) | Baixo | Torna explícita a política da DLQ (hoje "a fechar na implementação") e a idempotência do consumidor |
| 4 | **Topologia RabbitMQ**: exchange, fila principal, fila(s) de retry com TTL e backoff, DLX/DLQ, quem publica/consome | #14, #15 (e #10, #11) | Mermaid `flowchart` | **P1** | Médio | A DLQ e o backoff são o diferencial que as vagas de mensageria testam. Só desenhar depois de decidir como o backoff é implementado (§6) |
| 5 | **Linha do tempo da race condition**: ingênuo (SELECT depois UPDATE) vs `UPDATE ... WHERE quantidade >= :n`, dois clientes, última unidade | #7 (teste 200 x 50 e ADR) | Mermaid `sequenceDiagram` com `par` e `Note over` | **P1** | Baixo | Vai dentro do ADR; é o diagrama mais memorável do projeto e custa pouco |
| 6 | **Falha e retentativa** (`tok_flaky`, publicador cai entre publicar e marcar enviado, consumo duplicado) | #9, #14, #15 | Mermaid `sequenceDiagram` (`loop`, `break`, `critical`) | P1 | Médio | Documenta a entrega at-least-once + consumidor idempotente. Pode virar um segundo bloco do diagrama 2 |
| 7 | **ER do modelo de dados** (products/estoque, orders, idempotency keys, outbox; payments e eventos processados no outro banco) | #3, #6, #8, #9, #10 | Mermaid `erDiagram` à mão, ou SchemaSpy a partir do Postgres migrado por Flyway | P2 | Baixo à mão (mas desatualiza) | Útil se a tabela `outbox`/idempotência merecer destaque; senão o SQL do Flyway basta |
| 8 | **Deployment AWS**: EC2 t3.medium + Compose, S3 + CloudFront, HTTPS, Terraform | #33 (Terraform), #34 (CD) | Mermaid `flowchart` (ou `architecture-beta`, que é beta) | P2 | Baixo | Só depois do deploy existir; vale pela história de custo/IaC |
| 9 | **Módulos da `api` gerados** pelo Spring Modulith `Documenter` (C4 componente em PlantUML + canvases) | #2 (esqueleto) / ADR "Modulith só para fronteiras" | `Documenter` + PlantUML (SVG commitado) | P3 | Médio (pipeline de render) | Prova que as fronteiras são verificadas, mas o ganho visual sobre o diagrama 1 é pequeno |
| 10 | AsyncAPI dos eventos | #10, #11 | AsyncAPI + gerador | P3 / V2 | Médio | O binding AMQP descreve exchange, fila e operação, mas não DLX/DLQ [FONTE: repositório asyncapi/bindings]; cobriria só parte do fluxo |

**Não vale fazer (ruído para este projeto) [INFERÊNCIA]:** C4 nível 4/código e diagramas de classes; casos de uso; diagrama de atividade; C4 contexto separado (um bloco "usuários e sistemas externos" no diagrama 1 resolve); diagrama de pacotes à mão (o Modulith gera); diagramas de rede/VPC além do necessário; correlation id como diagrama próprio (anotação no 2 basta).

Regras de ouro que o próprio C4 dá e valem para todos: título, tipo, escopo, legenda, siglas explicadas e cada seta rotulada com intenção (e tecnologia, quando aplicável) [FONTE: c4model.com/diagrams/checklist].

### O que cada tipo comunica, e quando é ruído

| Tipo | Comunica | Vale quando | Vira ruído quando |
|---|---|---|---|
| C4 contexto | Quem usa e com quais sistemas externos | Sistema com muitos integradores | Só há um ator e um sistema |
| C4 container | Deployables, bancos, filas, protocolos | Sempre, em sistema distribuído | Vira lista de classes |
| C4 componente | Estrutura interna de um container | Módulo com fronteiras verificadas (Modulith) | Para um app pequeno, duplica a árvore de pacotes |
| Sequência | Ordem, sincronia, falha | Fluxos críticos e com assincronia | Fluxo trivial CRUD |
| Estados | Ciclo de vida e transições válidas | Entidade com política de cancelamento/idempotência (pedido) | Entidade sem estados reais |
| ER | Esquema e chaves | Constraints relevantes (UK de idempotência, outbox) | Cópia 1:1 do `\d` do Postgres |
| Mensageria/topologia | Exchanges, filas, retry, DLQ | Há retry/DLQ de verdade | Só uma fila |
| Deployment | Onde roda, o que custa | Há IaC | Antes do deploy existir |
| Linha do tempo de concorrência | Por que um bug acontece e como o design evita | ADR de lock/atômico | Código sem concorrência |

## 3. Comparativo de ferramentas ("diagrams as code")

Critério decisivo: o que o GitHub renderiza hoje. A página oficial lista quatro sintaxes em blocos cercados (`mermaid`, `geojson`, `topojson`, `stl`), em issues, discussions, PRs, wikis e arquivos Markdown. A versão do Mermaid usada pelo GitHub não é publicada na página; o jeito oficial de saber é um bloco `mermaid` contendo `info` [FONTE: docs.github.com, acessado 2026-11-16]. A versão atual do Mermaid no GitHub: [NÃO VERIFICADO] (não executei o bloco `info`). Sintaxes recentes (ex.: `architecture-beta`, `autonumber` com início/passo, `align` do v11.16) podem não existir no renderizador do GitHub; teste no próprio GitHub antes de confiar.

| Ferramenta | Renderiza no README do GitHub | Versionamento/diff | C4 | Qualidade visual / layout | Manutenção | Geração a partir do código |
|---|---|---|---|---|---|---|
| **Mermaid** | **Sim, nativo** [FONTE] | Texto, diff limpo | `C4Context/Container/Component/Dynamic/Deployment` existem, mas "layout não usa algoritmo totalmente automático", sem legenda/tags/sprites, posição depende da ordem das instruções [FONTE: docs do Mermaid no GitHub, c4.md] | Boa para sequência/estado/ER; flowchart de arquitetura é só razoável | Baixa | Não nativa; `mmdc` renderiza SVG/PNG/PDF e processa blocos em Markdown [FONTE: mermaid-cli README] |
| **PlantUML** | Não [INFERÊNCIA] (fora da lista do GitHub) | Texto | Via biblioteca/stdlib C4-PlantUML [NÃO VERIFICADO na página plantuml.com]; Modulith emite C4 em PlantUML [FONTE: docs.spring.io/spring-modulith] | UML completa (sequência, componente, deployment, estado); Graphviz como dependência externa, ou Smetana/VizJs/ELK [FONTE: plantuml.com] | Média (Java/Graphviz no CI) | Spring Modulith `Documenter` |
| **Structurizr DSL** | Não | Texto; um modelo, várias views | **Nativo C4** | Exporta PlantUML, C4-PlantUML, Mermaid, WebSequenceDiagrams (só dynamic), HTML estático, PNG, SVG; "nem todas as features/formas" sobrevivem à exportação [FONTE: docs.structurizr.com/export] | Média-alta (ferramenta Java/CLI/servidor) | Há biblioteca Java e "component finder" [FONTE: docs.structurizr.com/dsl]; servidor MCP e geração de DSL por IA [FONTE, mesma página] |
| **D2** | Não [INFERÊNCIA] | Texto | Sem suporte C4 declarado na página consultada [NÃO VERIFICADO] | Layouts dagre (padrão), ELK e TALA (feito para arquitetura de software) [FONTE: d2lang.com/tour/layouts]; status de licença do TALA [NÃO VERIFICADO] | Média (binário `d2`) | Não |
| **LikeC4** | Não (site estático/React) [INFERÊNCIA] | Texto no Git, validação de mudanças | Modelo próprio estilo C4, views navegáveis | Interativo; CLI `likec4 start/build/gen react`; MCP server para agentes [FONTE: likec4.dev]. Export para PNG/Mermaid/PlantUML/D2 não aparece na página [NÃO VERIFICADO] | Média | Não |
| **Excalidraw** | Arquivo nativo não; exporta SVG/PNG, com opção `exportEmbedScene` que embute a cena no arquivo (default false, aumenta o tamanho) [FONTE: docs Excalidraw] | Binário/JSON, diff ruim | Não | Estilo "à mão", bom para explicar | Alta | Não |
| **draw.io** | Não nativo; PNG exportado pode incluir cópia editável do diagrama [FONTE: drawio.com/doc/faq/export-to-png]; SVG: [NÃO VERIFICADO] | XML, diff ruim | Bibliotecas de formas, sem modelo | Alta flexibilidade | Alta | Não |
| **Spring Modulith `Documenter`** | Gera fontes PlantUML + canvases Asciidoc (`all-docs.adoc`) em `spring-modulith-docs` no diretório de build; precisa renderizar [FONTE: docs.spring.io/spring-modulith] | Gerado, não commitar fonte | Estilo C4 por padrão, ou UML via `DiagramStyle.UML` | Aceitável | Zero (gerado) | Sim, é a razão de existir |
| **springdoc-openapi** | Não é diagrama; gera OpenAPI 3 em runtime, Swagger UI/Scalar; plugins Maven/Gradle gravam `openapi.json` no build; Boot 4 suportado [FONTE: springdoc.org]. Matriz exata de versões Boot 4 x springdoc: [NÃO VERIFICADO] (parte da página não lida) | JSON/YAML diffável | n/a | n/a | Baixa | Sim |
| **SchemaSpy** | Gera site HTML + ER a partir de banco vivo, aplicação Java única, imagem Docker [FONTE: schemaspy.org]. Suporte a PostgreSQL e dependência de Graphviz: [NÃO VERIFICADO] na página consultada | HTML gerado, não commitar | n/a | ER automático | Baixa (job de CI) | Sim |
| **AsyncAPI** | Gera site/Markdown; binding AMQP cobre exchange/fila/operação, não DLX [FONTE: asyncapi/bindings] | YAML | n/a | Documentação, não diagrama | Média | Spec escrita à mão |

**Recomendação [INFERÊNCIA]:** Mermaid para os diagramas 1 a 8; Structurizr DSL só se você quiser aprender C4 de verdade como skill separada (tem custo de ferramenta e de renderização, e o stockrush tem 3 apps); Modulith `Documenter` opcional. Não misture 4 ferramentas: um recrutador abre o README, não instala Structurizr.

Limite conhecido do Mermaid a evitar: palavra `end` em minúsculas quebra flowchart; nó começando com `o`/`x` depois de `---` vira círculo/cruz; rótulos com `()` precisam de aspas [FONTE: doc do flowchart do Mermaid].

## 4. Prototipação: o que vale validar antes de desenhar

Alinha com o fluxo do usuário (`/prototype`: app de terminal para lógica). O que dá retorno:

1. **Máquina de estados do pedido (vale muito, é o primeiro).** Protótipo descartável: tabela estado x evento (aprovado, recusado, esgotado, DLQ, evento duplicado, evento tardio) em ~100 linhas. Perguntas que ele responde: (a) resultado "aprovado" chegando *depois* de a DLQ ter cancelado o pedido; (b) cancelar duas vezes devolve estoque em dobro?; (c) o que a política da DLQ faz com estoque reservado. A issue #1 diz que a política da DLQ "deve ser fechada na implementação e registrada em ADR"; o diagrama de estados (3) só deve ser desenhado depois. [INFERÊNCIA a partir das issues #1, #15]
2. **Protocolo do outbox (vale).** Simulação em memória com falhas injetadas: crash entre `publish` e marcar-como-enviado, dois publicadores, consumidor reentregue. O resultado é a evidência para o diagrama 6 e para o ADR "outbox à mão". Custo: baixo.
3. **Diagrama de sequência de falha/retry (vale, mas como saída, não como entrada).** Desenhe depois dos itens 1 e 2, a partir do que o protótipo mostrou; desenhar antes só fixa suposições.
4. **Topologia de retry/backoff no RabbitMQ (vale uma verificação curta).** Decida o mecanismo (filas de retry com TTL + DLX vs retry no cliente) lendo a doc do RabbitMQ: mensagem vai a dead-letter por `rejected`, `expired`, `maxlen` ou `delivery_limit` (quorum queues), com histórico em `x-death`; quorum queues dão dead-lettering at-least-once, classic queues podem perder mensagem se o destino estiver indisponível [FONTE: rabbitmq.com/docs/dlx]. Isso afeta o tipo de fila escolhido e, portanto, o diagrama 4.
5. **TLA+ ou modelo formal: não compensa.** TLA+ serve para modelar sistemas concorrentes/distribuídos e achar erros de projeto cedo, com TLC como verificador e Toolbox/extensão de VS Code [FONTE: lamport.azurewebsites.net/tla/tla.html]. Para este portfólio: (a) as vagas coletadas pedem testes, CI/CD, AWS, mensageria; nada na amostra pede métodos formais [FONTE: docs/discovery.md, evidência de demanda]; (b) o risco de concorrência real (overselling) já é coberto, e provado em Postgres real, pelo teste 200 x 50 do Testcontainers; (c) o custo de aprender TLA+ compete com entregar o v1. Só valeria como hobby ou se uma vaga específica de sistemas distribuídos aparecer. Alternativa barata para o mesmo ganho: teste de propriedade/tabela sobre a máquina de estados [INFERÊNCIA; ferramenta de PBT para Java não pesquisada].
6. **Protótipo do layout visual do diagrama 1.** Vale só renderizar uma vez no GitHub e olhar; não é um protótipo de verdade.

## 5. Capacidades e limites do Claude para diagramas, e fluxo de validação

### O que existe neste ambiente (verificado localmente em 2026-11-16)

- **Tool `Artifact`**: publica HTML como página privada no claude.ai; a descrição manda carregar a skill `artifact-design` antes de escrever, e há uma skill `artifact-diagramming` ("quando um desenho vale a pena, como desenhar o mecanismo real, mecânica de SVG inline legível nos dois temas") [FONTE: descrição da tool e lista de skills desta sessão]. Útil para um diagrama interativo/visual fora do repo, **não** como fonte versionada do README.
- **`mcp__visualize__show_widget`**: renderiza SVG/HTML inline na conversa (flowcharts, diagramas) [FONTE: descrição da tool]. Bom para rascunhar e mostrar ao usuário; o resultado não vai para o repo.
- **Navegador e Playwright MCP, e `google-chrome-stable`, `docker`, `node`, `java` na máquina**: formam um caminho de renderização/inspeção [FONTE: `which`]. Não há `mmdc`, `d2`, `plantuml`, `structurizr-cli` instalados [FONTE: `which` retornou vazio].
- **Skills de diagrama no `~/.claude/skills`**: nenhuma específica (a busca por "mermaid|diagram" nos `SKILL.md` locais não retornou nada). Em `~/.claude/plugins/marketplaces/claude-plugins-official.bak` há plugins que citam "diagram" (`playground`, `code-modernization`), não avaliados [NÃO VERIFICADO quanto à qualidade].
- **MCPs de diagrama**: existem servidores comunitários de Mermaid (ex.: claude-mermaid, mermaid-mcp com Puppeteer, Mindpilot), vistos só em diretórios de terceiros; **não instalados e não avaliados** [NÃO VERIFICADO]. A doc oficial do Claude Code descreve MCP (`claude mcp add`, escopos local/project/user) mas, na parte lida (100 mil dos 157 mil caracteres), não menciona geração ou renderização de diagramas [FONTE: code.claude.com/docs/en/mcp]. A visão geral e as boas práticas também não documentam capacidade de diagramas; as boas práticas recomendam dar evidência de verificação, como captura de tela [FONTE: code.claude.com/docs/en/best-practices].
- **Teste de renderização via Docker: executado e aprovado em 2026-11-16** [FONTE: execução local]. O `npx -p @mermaid-js/mermaid-cli mmdc` foi bloqueado pelo classificador de permissões e não foi contornado; o caminho Docker, autorizado pelo usuário, funcionou com a imagem oficial `ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:latest` (mermaid-cli 12.0.0, docker 29.9.3, pull de ~45 s).
  - **Resultado:** `sequenceDiagram` (fluxo crítico: reserva atômica, outbox, fila, pagamento), `stateDiagram-v2` (estados do pedido) e `flowchart` (container) renderizaram em PNG; sequência e estados foram abertos e conferidos (acentos e setas corretos), o de container só gerou o arquivo. Um diagrama com sintaxe inválida falhou com `Parse error on line 3` e saída 1, então o `mmdc` serve como validador em CI.
  - **Pegadinha:** a imagem não traz o Chrome do Puppeteer (`PUPPETEER_SKIP_DOWNLOAD=true`), só `/usr/bin/chromium`. Rodando com `-u uid:gid`, o padrão falha com "Could not find chrome-headless-shell". Correção: config com `{"executablePath":"/usr/bin/chromium","args":["--no-sandbox"]}` passada com `-p`.
  - **Comando validado:**
    ```bash
    docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/data" \
      ghcr.io/mermaid-js/mermaid-cli/mermaid-cli:latest \
      -p /data/pp.json -i /data/x.mmd -o /data/x.png -s 2 -b white
    ```
  - **Limite:** valida o renderizador do `mermaid-cli`, **não** o do GitHub. A versão do Mermaid do GitHub continua [NÃO VERIFICADO]; sintaxe aceita aqui pode falhar lá (passo 5 do fluxo abaixo). Os estados do diagrama de teste (ex.: `EM_ANALISE`) eram placeholders, não design, pois a política da DLQ (#14, #15) segue sem decisão.

### Onde o Claude acerta e erra (observações; sem fonte primária de benchmark, [INFERÊNCIA/NÃO VERIFICADO como estatística])

- **Confiável**: Mermaid `sequenceDiagram`, `stateDiagram-v2`, `erDiagram`, flowchart simples; PlantUML de sequência; SVG direto para figuras pequenas.
- **Costuma errar**: (1) sintaxe sutil e específica de versão (palavras reservadas, ids, aspas, recursos novos que o Mermaid do GitHub talvez não tenha); (2) layout: nenhuma ferramenta de texto dá controle fino, e Claude não vê o resultado sem renderizar; Mermaid C4 e `architecture-beta` são os mais frágeis (beta, sem layout automático); (3) **alucinar componentes** (exchange, fila ou módulo que não existe no código, arestas "porque faria sentido"); (4) D2, Structurizr DSL e LikeC4 têm menos exemplos públicos que Mermaid/PlantUML, então erro de sintaxe é mais provável [INFERÊNCIA].

### Fluxo de validação recomendado

1. **Fonte de verdade antes do desenho**: o código/ADR/issue. Para cada nó e aresta, o Claude cita o arquivo ou a issue de onde veio. Antes de existir código, o diagrama é "alvo", marcado como tal no README.
2. **Claude escreve o `.md` com bloco `mermaid`** (arquivo em `docs/diagrams/` ou direto no README) com `accTitle`/`accDescr` (o mermaid-cli os usa como título/alt no modo Markdown [FONTE: README do mermaid-cli]).
3. **Validar sintaxe**: `mmdc -i x.md -o x-out.md` (modo Markdown renderiza cada bloco e falha em erro de parse) via Docker (validado, ver pegadinha do Chromium acima) ou devDependency. O modo Markdown em si ainda não foi testado, só arquivos `.mmd`. Rodar isso no CI (issue #34 já terá workflow) trava diagramas quebrados [INFERÊNCIA].
4. **Olhar a imagem**: renderizar para PNG/SVG e abrir (o Claude lê imagens via `Read`; o Browser MCP/Playwright também tira captura). Conferir sobreposição, legibilidade em largura de README e no tema escuro.
5. **Teste final no GitHub**: abrir o README no branch (ou numa issue de teste) e conferir; executar um bloco `info` para saber a versão do Mermaid do GitHub [FONTE: docs.github.com].
6. **Revisão humana de fidelidade** com a checklist C4 (título, escopo, legenda, setas rotuladas) [FONTE: c4model.com].

## 6. Riscos e questões em aberto para o usuário decidir

1. **Política da DLQ e mecanismo de backoff ainda não decididos** (issues #1, #14, #15): bloqueiam os diagramas 3 e 4. Decidir via protótipo (§4) antes de desenhar.
2. **Um diagrama só de arquitetura ou vários?** Proposta: 2 no README (container + sequência), o resto em `docs/` e nos ADRs. Confirmar.
3. **Mermaid `C4Container` vs flowchart com notação C4.** Mermaid C4 existe mas sem legenda/tags e com layout manual; recomendo flowchart. Aceita perder o "selo" C4 nativo?
4. **Quer aprender Structurizr/C4 como skill** (útil em vagas de arquitetura) ou só entregar o portfólio? Se sim, custo de CI e renderização sobe.
5. **Diagramas gerados (Modulith, SchemaSpy, springdoc/OpenAPI) entram no v1?** Recomendo só springdoc (já planejado em #23); o resto P2/P3.
6. **Versão do Mermaid no GitHub e dos tipos novos**: [NÃO VERIFICADO]; testar antes de usar `architecture-beta` ou `autonumber` com passo.
7. **Autorização de ferramentas**: validar diagramas exige `docker run`/`npm i -D @mermaid-js/mermaid-cli`; o classificador bloqueou `npx` ad hoc. Decidir como instalar (devDependency fixada, ou imagem Docker fixada por tag).
8. **Convenção de notas de pesquisa**: o repo ainda não tinha pasta de pesquisa; este arquivo criou `docs/research/`. Confirmar se esse é o lugar, ou se prefere `docs/notes/`.
9. **Manutenção**: diagramas desatualizam. Mitigação: poucos diagramas, rótulos no vocabulário do `CONTEXT.md`, e um item de checklist no PR "mudou fluxo/estados/filas? atualize o diagrama".
10. **Idioma**: README em inglês e pt-BR (issue #32); rótulos dos diagramas precisam de duas versões ou ficar em inglês (consistente com código e ADRs). Decidir.

## 7. Fontes (acessadas em 2026-11-16)

- GitHub Docs, "Creating diagrams": https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/creating-diagrams
- Mermaid (repositório oficial, docs-fonte): C4 https://raw.githubusercontent.com/mermaid-js/mermaid/develop/packages/mermaid/src/docs/syntax/c4.md · Architecture https://raw.githubusercontent.com/mermaid-js/mermaid/develop/packages/mermaid/src/docs/syntax/architecture.md · Estado https://raw.githubusercontent.com/mermaid-js/mermaid/develop/packages/mermaid/src/docs/syntax/stateDiagram.md · Sequência .../sequenceDiagram.md · ER .../entityRelationshipDiagram.md · Flowchart .../flowchart.md (o site mermaid.js.org estava inacessível por erro de DNS no momento da coleta)
- Mermaid CLI: https://raw.githubusercontent.com/mermaid-js/mermaid-cli/master/README.md
- C4 model: https://c4model.com/diagrams · https://c4model.com/diagrams/checklist
- Structurizr: https://docs.structurizr.com/dsl · https://docs.structurizr.com/export
- Spring Modulith, documentação: https://docs.spring.io/spring-modulith/reference/documentation.html
- PlantUML: https://plantuml.com/
- D2: https://d2lang.com/tour/intro · https://d2lang.com/tour/layouts
- LikeC4: https://likec4.dev/
- AsyncAPI: https://www.asyncapi.com/docs/tools/generator · https://raw.githubusercontent.com/asyncapi/bindings/master/amqp/README.md
- SchemaSpy: https://schemaspy.org/
- springdoc-openapi: https://springdoc.org/
- RabbitMQ, dead letter exchanges: https://www.rabbitmq.com/docs/dlx
- TLA+: https://lamport.azurewebsites.net/tla/tla.html
- Excalidraw (export, `exportEmbedScene`): https://plus.excalidraw.com/docs/sdk/excalidraw/api/utils/export
- draw.io, exportar PNG: https://www.drawio.com/doc/faq/export-to-png (a página de embed no GitHub deu 404)
- Claude Code: https://code.claude.com/docs/en/overview · https://code.claude.com/docs/en/mcp (lidos 100 mil de 157 mil caracteres) · https://code.claude.com/docs/en/best-practices
- Repo local: README.md, docs/discovery.md, docs/BACKLOG.md, docs/agents/*.md, `gh issue view 1` e `gh issue view 32`, lista `gh issue list` (34 issues)
- Busca web (apenas para localizar MCPs comunitários de Mermaid, nada tratado como fato): resultado de WebSearch em diretórios de terceiros (glama.ai, lobehub, etc.)
