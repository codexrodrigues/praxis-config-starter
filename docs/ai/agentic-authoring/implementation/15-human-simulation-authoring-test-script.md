# Human Simulation Authoring Test Script

Status: roteiro operacional para implementacao de E2E browser
Date: 2026-05-09
Scope: Page Builder agentic authoring, dashboards conectados, tabs internas,
componentes elegiveis e perguntas de dominio.

## Classification

- Current document change: `docs-apenas`.
- Future implementation class: `arquitetural`.
- Canonical backend owner: `praxis-config-starter`.
- Runtime UI consumer: `praxis-ui-angular` / `@praxisui/page-builder`.
- Runtime proof host: `praxis-api-quickstart`.
- Public contract impact: none for this roteiro; if a scenario requires a new
  field in AI contracts, reclassify that future PR as `contrato-publico`.

## Goal

Define a repeatable human-simulation test suite for the Page Builder assistant.
The suite must prove that Praxis behaves like a governed semantic authoring
platform:

- understands natural, incomplete business language;
- retrieves domain resources before materializing;
- creates rich dashboards with connected widgets;
- creates tabs with internal components whose bindings survive refinements;
- modifies eligible component features through the assistant;
- answers domain questions with useful next steps instead of technical payloads;
- blocks or reviews materializations that contradict the semantic decision.

## Non-Goals

- Do not turn this into a frontend-only fixture suite.
- Do not assert success only by checking text in the chat.
- Do not accept local component mutation when the backend decision says review,
  mismatch or route-required.
- Do not require a remote GitHub Action for normal iteration. Local browser proof
  is the default gate.

## Required Runtime Setup

Use the official local browser stack:

- backend: `praxis-api-quickstart` on `http://localhost:8088`;
- UI: `praxis-ui-angular` on `http://localhost:4003`;
- page: `http://localhost:4003/page-builder-ia`;
- real SSE stream unless the scenario explicitly validates fail-closed fallback;
- real metadata from the quickstart host;
- provider mode declared in evidence:
  - `mock` only for deterministic shell and UX checks;
  - real LLM provider for semantic choice, multi-turn refinement and domain
    question quality checks.

Minimum evidence per browser run:

- prompt transcript;
- terminal stream event summary;
- `semanticDecision` summary;
- `decisionDiagnostics` summary;
- preview/apply status;
- screenshot after each accepted materialization;
- JSON snapshot of final page model when available.

## Human Simulation Rules

The test prompts must imitate people who know the business goal but do not know
the API surface, widget names or schema fields.

Use language like:

- "quero acompanhar atrasos e gargalos da folha";
- "ficou bom, mas coloca graficos e filtros";
- "se eu clicar no grafico quero ver os registros embaixo";
- "cria abas para visao geral, detalhes e acoes";
- "troca essa tabela para kanban";
- "quais telas fazem sentido para pessoas da empresa?";

Avoid language like:

- "use praxis-chart";
- "bind chart.selectionChange to table.queryContext";
- "use /schemas/filtered";
- "set widgetKey to incidentes-chart-gravidade";

## Global Pass Criteria

Every scenario below must satisfy these criteria unless it explicitly tests a
failure path:

- assistant response is friendly and explains the next step;
- no raw endpoint/schema path is shown unless the user explicitly asks for API
  details;
- preview compiles technically: `preview.valid=true`;
- semantic decision is coherent with the user goal:
  `decisionDiagnostics.decisionValid=true`;
- if review is required, `canApply=false` and the UI explains why;
- no local-only frontend mutation bypasses backend authoring;
- selected resource and widget bindings are traceable in diagnostics;
- no orphan binding remains after schema or widget reconciliation;
- tabs, widgets, filters and drilldowns remain connected after refinements.

## Suite A - Complex Connected Dashboards

### A1. Payroll Executive Dashboard With Drilldown

Initial prompt:

> Quero um dashboard bonito para acompanhar folha de pagamento, com graficos,
> indicadores e uma tabela de detalhes quando eu clicar em algum grafico.

Expected decision:

- `artifactKind=dashboard`;
- selected resource is an analytics/payroll resource, not a generic table-only
  collection when analytical evidence exists;
- evidence bundle includes API metadata, schema/capability evidence and domain
  catalog evidence when available.

Expected materialization:

- KPI band for total payroll, headcount or average salary;
- at least two `praxis-chart` widgets;
- at least one `praxis-filter`;
- one detail `praxis-table`;
- chart selection drives table query context;
- filter drives charts and table;
- no chart axis is marked schema-verified unless confirmed by schema evidence.

Follow-up prompts:

1. > Gostei, mas separa por departamento e competencia.
2. > Agora quando eu selecionar um departamento no grafico, atualiza a tabela
   de detalhes.
3. > Adiciona uma aba so para ranking de salarios.

Pass criteria:

- resource/source is preserved during visual refinements;
- `refinementKind` reflects visualization/filter/tab changes;
- chart-to-table binding exists after each follow-up;
- tabs are created without losing dashboard widgets;
- no unrelated resource replaces payroll without a data-source refinement.

### A2. Operations Incident Dashboard With Semantic Repair

Initial prompt:

> Cria um painel de incidentes para eu ver gravidade, responsavel, andamento e
> os casos que precisam de acao.

Expected materialization:

- severity/gravity chart when schema supports it;
- responsible/status/andamento chart or review when fields are absent;
- KPI band for open/critical/overdue counts;
- filter by severity/status/responsible when schema supports it;
- auxiliary table for cases.

Repair prompt:

> Se algum campo nao existir, troca pelo campo mais proximo sem inventar dado.

Pass criteria:

- invalid axes are removed or marked pending;
- orphan bindings are pruned;
- assistant explains the repair in business terms;
- decision remains blocked if the semantic intent cannot be satisfied.

### A3. Cross-Domain Dashboard Clarification

Initial prompt:

> Quero um dashboard de risco das pessoas da empresa.

Expected behavior:

- assistant asks a clarification or recommends candidate domains;
- it must not silently pick operations risk or HR people without explanation;
- quick replies must offer domain choices such as people, incidents, reputation
  or payroll depending on candidates.

Follow-up prompt:

> Quero risco operacional, mas com responsaveis e equipes.

Pass criteria:

- source switches through `data_source` refinement;
- previous HR source is not laundered through memory;
- final dashboard has operations-focused widgets and person/team drilldowns.

## Suite B - Tabs With Connected Internal Components

### B1. Tabs For Operational Review

Initial prompt:

> Cria uma tela com abas para acompanhar missoes. Quero uma aba resumo, uma aba
> registros e uma aba participantes.

Expected materialization:

- `praxis-tabs` or canonical tab container;
- "Resumo" tab with KPI/chart widgets;
- "Registros" tab with table or CRUD/list;
- "Participantes" tab with table/list connected to selected mission;
- selection in registros updates participantes.

Follow-up prompts:

1. > Na aba registros, coloca filtros por status e prioridade.
2. > Na aba participantes, mostra detalhes da missao selecionada.
3. > Renomeia resumo para visao geral e nao mexe nas conexoes.

Pass criteria:

- tab rename preserves child widget keys and bindings;
- table selection feeds participant/detail components;
- filters stay scoped to the correct tab unless explicitly global;
- assistant explains what changed and what remained connected.

### B2. Nested Dashboard Inside Tab

Initial prompt:

> Quero uma pagina de folha com abas: resumo executivo, analise por departamento
> e detalhes. A aba de analise precisa ter graficos conectados entre si.

Expected materialization:

- outer tabs;
- dashboard composition inside analysis tab;
- chart/filter/table bindings scoped inside the tab;
- details tab receives context from selected chart/table when requested.

Follow-up prompt:

> Quando eu filtrar competencia no resumo executivo, atualiza tambem a aba de
> analise.

Pass criteria:

- cross-tab filter binding is explicit and traceable;
- no accidental binding to unrelated widgets;
- UI indicates that the filter is global or shared.

## Suite C - Eligible Component Creation And Feature Editing

Run this suite as a component eligibility matrix. For each component, ask the
assistant to create it, then ask it to modify one feature that should be
supported, and one feature that should trigger clarification/review if unsupported.

### C1. Table

Create prompt:

> Cria uma tabela de funcionarios para consultar pessoas da empresa.

Feature modification prompts:

1. > Deixa a tabela mais compacta e com avatar, nome, cargo, departamento e
   status.
2. > Adiciona filtros por departamento e ativo.
3. > Quando clicar em uma linha, abre os detalhes ao lado.

Pass criteria:

- columns are schema-backed or pending review;
- density, filters and row selection are represented in materialization;
- detail binding targets an existing detail widget or asks to create one.

### C2. Chart

Create prompt:

> Cria um grafico de incidentes por gravidade.

Feature modification prompts:

1. > Troca para barras horizontais.
2. > Adiciona legenda e tooltip com contagem.
3. > Ao clicar em uma barra, filtra a tabela de incidentes.

Pass criteria:

- chart type changes without losing resource;
- unsupported axis remains pending/review;
- selection binding to table is present only if table exists or is created.

### C3. KPI Band

Create prompt:

> Cria indicadores principais para folha de pagamento.

Feature modification prompts:

1. > Mostra total, media e quantidade de funcionarios.
2. > Coloca tendencia em relacao ao mes anterior.
3. > Se nao tiver dado de tendencia, deixa isso em revisao.

Pass criteria:

- KPIs use available fields or pending schema verification;
- trend is not invented without evidence;
- review reason is clear when trend data is unavailable.

### C4. Filter Panel

Create prompt:

> Adiciona filtros para eu segmentar funcionarios.

Feature modification prompts:

1. > Quero filtros por departamento, cargo e status.
2. > Esses filtros devem atualizar tabela e graficos.
3. > Remove cargo e mantem o resto conectado.

Pass criteria:

- filter widgets bind to eligible target widgets;
- removing a filter removes only related bindings;
- no orphan binding remains.

### C5. Tabs

Create prompt:

> Organiza essa tela em abas: visao geral, registros e detalhes.

Feature modification prompts:

1. > Move a tabela para registros.
2. > Move os graficos para visao geral.
3. > A aba detalhes deve mostrar o item selecionado na tabela.

Pass criteria:

- widgets move between tabs without duplicate keys;
- bindings update target scopes correctly;
- no component disappears silently.

### C6. Form Or CRUD

Create prompt:

> Cria um formulario para cadastrar beneficios de funcionarios.

Feature modification prompts:

1. > Adiciona validacao de campos obrigatorios.
2. > Mostra uma lista dos beneficios cadastrados abaixo.
3. > Se isso for uma regra compartilhada, me leva para revisao em vez de criar
   regra local.

Pass criteria:

- form/CRUD uses canonical resource/action evidence;
- shared-rule requests route to governed handoff;
- no business rule is stored as local page config.

## Suite D - Domain Questions That Suggest Screens

### D1. People Domain Discovery

Prompt:

> Quais telas fazem sentido para pessoas da empresa?

Expected behavior:

- assistant answers with screen suggestions before creating anything;
- suggestions include table/list, profile/detail, dashboard and related
  operational views when evidence supports them;
- quick replies offer create/refine choices.

Follow-up:

> Comeca pela tabela principal e me mostra os campos antes.

Pass criteria:

- no preview is materialized before the user asks to create;
- field answer is friendly and avoids endpoint/schema jargon;
- creation prompt preserves the chosen source.

### D2. Payroll Domain Discovery

Prompt:

> O que posso construir para acompanhar folha de pagamento?

Expected suggestions:

- executive dashboard;
- operational payroll table;
- department comparison dashboard;
- anomaly/ranking view if domain evidence supports it.

Follow-up:

> Quero o dashboard executivo primeiro.

Pass criteria:

- source is analytical when available;
- materialization includes KPIs, charts, filters and detail table;
- answer explains review requirements if evidence is weak.

### D3. Operations Domain Discovery

Prompt:

> Que telas posso criar para operacoes e missoes?

Expected suggestions:

- mission dashboard;
- mission records table;
- team/participant detail view;
- incident/risk view when related.

Follow-up:

> Cria uma tela com abas e conexoes entre missao, equipe e participantes.

Pass criteria:

- tabs plus connected child components are created;
- relationships are grounded in domain/resource evidence;
- if relationship is unavailable, assistant asks for confirmation or review.

### D4. Modification Advice For Current Page

Start from any generated dashboard or tabbed page.

Prompt:

> O que voce recomenda melhorar nessa tela?

Expected behavior:

- assistant inspects current page/decision context;
- suggestions distinguish layout, filters, drilldowns, missing details and
  governance/review gaps;
- no mutation happens until the user confirms a choice.

Follow-up:

> Aplique a melhoria mais importante.

Pass criteria:

- selected improvement is applied as a semantic refinement;
- previous resource and bindings are preserved unless the improvement requires a
  data-source change;
- assistant explains what changed.

## Suite E - Negative And Governance Scenarios

### E1. Generic Refinement Must Not Preserve Wrong Source

Initial prompt:

> Cria uma tabela de funcionarios.

Follow-up:

> Agora usa clientes como fonte.

Pass criteria:

- `refinementKind=data_source`;
- selected resource changes to customer/client resource when available;
- previous people source is not reused by memory;
- no `semantic-decision-memory-refinement-applied` warning for preservation.

### E2. Unsupported Component Feature

Prompt:

> Adiciona um mapa 3D interativo nessa tabela de funcionarios.

Pass criteria:

- assistant does not fabricate an unsupported widget;
- response asks for alternative or review;
- preview cannot be applied unless a supported component/capability is found.

### E3. Schema Mismatch

Prompt:

> Cria grafico de funcionarios por nivel de magia.

Pass criteria:

- if field does not exist, axis is pending/review or removed;
- assistant explains that the field was not found;
- no chart with fake axis is considered semantically valid.

### E4. Review State UX

Prompt:

> Cria uma tabela bonita para enxergar as pessoas da empresa.

Pass criteria:

- if evidence is weak, preview may render but `canApply=false`;
- assistant says what was created, why it is in review and how to proceed;
- UI does not say "Preview applied" as if the result were saved/applicable.

## Automation Mapping

Recommended browser specs:

- `page-builder-agentic-human-dashboard-connections.playwright.spec.ts`
  - A1, A2, A3.
- `page-builder-agentic-human-tabs-connections.playwright.spec.ts`
  - B1, B2.
- `page-builder-agentic-component-eligibility.playwright.spec.ts`
  - C1 through C6 as a matrix.
- `page-builder-agentic-domain-questioning.playwright.spec.ts`
  - D1 through D4.
- `page-builder-agentic-governance-negative.playwright.spec.ts`
  - E1 through E4.

Recommended backend focal tests:

- intent resolver tests for each natural-language prompt class;
- turn engine tests for memory/refinement/resource preservation;
- preview service tests for semantic mismatch, orphan binding pruning and
  review-blocked `canApply=false`;
- composition provider tests for dashboard/tabs/widget binding structure.

Recommended UI focal tests:

- turn flow tests for friendly messages and quick reply context preservation;
- page-builder runtime tests for connected widget normalization;
- adapter tests proving generated widgets are not mutated locally before backend
  preview/apply.

## Definition Of Done For This Suite

The suite is considered implemented when:

- every scenario has a browser assertion and at least one backend/unit
  assertion for the semantic contract it depends on;
- final page model assertions verify widgets and bindings, not only chat text;
- screenshots prove the page looks coherent after complex materialization;
- review-required paths show useful guidance and block apply;
- domain-question paths answer first and only create after confirmation;
- the local full Page Builder E2E gate passes with the scenarios enabled;
- any new public contract field introduced during implementation is documented,
  hashed and regenerated across OpenAPI/TS bindings.
