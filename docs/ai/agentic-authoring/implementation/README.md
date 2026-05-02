# Agentic Authoring Implementation Guide

Este diretorio existe para manter um trilho canonico de implementacao do
agentic authoring do Praxis. Ele deve ser lido antes de abrir PRs que mexam em:

- `POST /api/praxis/config/ai/authoring/**`
- stream SSE de authoring
- contratos AI em `docs/ai/contracts/**`
- consumo Angular em `@praxisui/ai`
- fluxos do Page Builder dependentes de authoring backend-driven

## Objetivo

Sair do estado atual, em que o fluxo de authoring ainda e majoritariamente
linear (`intent-resolution -> page-preview`), para o estado alvo em que o
backend opera um turno agentic real:

1. resolve intencao inicial;
2. decide se precisa de tools;
3. executa tools internas canonicas do backend, ainda fora de OpenAPI ate
   promocao explicita;
4. observa os resultados;
5. gera preview/resultado;
6. valida e tenta repair quando a falha for recuperavel;
7. expoe progresso e resultado por stream SSE canonico.

Status em 2026-05-01: a extracao do engine, o primeiro tool interno
`searchApiResources`, a inspecao estrutural de `currentPage`, a separacao de
retrieval/provenance, o repair loop backend-owned e a primeira trilha de
project knowledge governado ja estao implementados em `main`. O checkpoint de
observabilidade/governanca do Project Knowledge tambem esta fechado em `main`:
o backend deriva contagens de auditoria a partir de entradas seguras, a UI
exibe apenas citacoes seguras, e a lane local versionada do Page Builder prova
o fluxo real com LLM/browser. A escrita governada de Project Knowledge
authorada por IA tambem avancou pela fronteira canonica
`domain_knowledge_change_set`, incluindo create, validate, approve, apply,
readback, safe timeline, quickstart proof e public-runtime proof em `rc.37`.
A fase de continuidade governada do Page Builder tambem esta concluida
localmente em `main`: o cockpit transforma handoffs seguros em acoes governadas
claras, sem fazer a UI virar fonte primaria de regra, memoria ou
materializacao. A reversibilidade governada de Domain Knowledge
(`revert_evidence`) tambem ja tem baseline funcional local-first: lifecycle de
evidencia, validacao, apply transacional, timeline segura, smoke HTTP com Neon,
retrieval filtering e prova browser no Page Builder confirmando que evidencia
revertida deixa de aparecer como Project Knowledge ativo. O checkpoint
documental/release-readiness dessa fase tambem esta fechado: contrato OpenAPI,
bindings gerados e corpus HTTP derivado estao alinhados sem promover o exemplo
mutavel para `llmOperational` e sem publicar novo Maven/npm. Em 2026-05-02, o
checkpoint opt-in de Project Knowledge Vector RAG tambem foi provado
localmente: publicacao derivada no `vector_store`, ranking vetorial apenas como
candidate retrieval, reload canonico com `sourceRelease`, revert removendo
influencia e supersession preservando apenas a evidencia substituta ativa. O
proximo slice escolhido e tornar o Page Builder stream-first para authoring de
componente/pagina e fail-closed para decisoes semanticas governadas, mantendo o
checkpoint Vector RAG fechado localmente e nao publicado. Esse slice tambem
passou por prova browser focal local: prompt governado com stream indisponivel
falha fechado para cockpit de Shared Rules, sem chamar `intent-resolution`,
`page-preview` ou `page-apply`. Em seguida, a prova de runtime consumidor
tambem passou localmente: uma decisao governada foi materializada como
`form_config` aplicada no backend canonico e consumida por
`/funcionarios-form-demo` via browser real. Antes de ampliar
operacoes alem de `add_evidence`/`revert_evidence`, a plataforma deve preservar
essa semantica de revert/supersede sem deletar evidencia ou expor payload bruto.

## Ordem de leitura

1. [04-implementation-ready-plan.md](./04-implementation-ready-plan.md)
2. [05-governed-project-knowledge-plan.md](./05-governed-project-knowledge-plan.md)
3. [06-next-cut-decision.md](./06-next-cut-decision.md)
4. [release-readiness-2026-04-30-project-knowledge.md](../../release-readiness-2026-04-30-project-knowledge.md)
5. [project-knowledge-release-checklist-2026-04-30.md](../../project-knowledge-release-checklist-2026-04-30.md)
6. [07-governed-knowledge-writes-plan.md](./07-governed-knowledge-writes-plan.md)
7. [08-page-builder-continuity-phase.md](./08-page-builder-continuity-phase.md)
8. [09-domain-knowledge-revert-phase.md](./09-domain-knowledge-revert-phase.md)
9. [release-readiness-2026-05-01-domain-knowledge-revert.md](../../release-readiness-2026-05-01-domain-knowledge-revert.md)
10. [release-readiness-2026-05-01-domain-knowledge-contract-corpus.md](../../release-readiness-2026-05-01-domain-knowledge-contract-corpus.md)
11. [10-domain-knowledge-supersede-evidence-plan.md](./10-domain-knowledge-supersede-evidence-plan.md)
12. [11-vector-rag-active-evidence-filtering-plan.md](./11-vector-rag-active-evidence-filtering-plan.md)
13. [release-readiness-2026-05-02-project-knowledge-vector-rag.md](../../release-readiness-2026-05-02-project-knowledge-vector-rag.md)
14. [release-decision-2026-05-02-project-knowledge-vector-rag.md](../../release-decision-2026-05-02-project-knowledge-vector-rag.md)
15. [project-knowledge-vector-rag-release-checklist-2026-05-02.md](../../project-knowledge-vector-rag-release-checklist-2026-05-02.md)
16. [12-page-builder-stream-first-routing-plan.md](./12-page-builder-stream-first-routing-plan.md)
17. [13-runtime-enforcement-consumer-proof-plan.md](./13-runtime-enforcement-consumer-proof-plan.md)
17. [01-current-state-and-target.md](./01-current-state-and-target.md)
18. [02-implementation-backlog.md](./02-implementation-backlog.md)
19. [03-browser-e2e-definition-of-done.md](./03-browser-e2e-definition-of-done.md)

`04-implementation-ready-plan.md` e a fonte ativa para preparar novos PRs,
`05-governed-project-knowledge-plan.md` detalha a Phase 7, e
`06-next-cut-decision.md` registra a recomendacao pos-checkpoint. O relatorio de
release-readiness e o checklist operacional registram como fechar a fase sem
transformar docs-only em publicacao ou em uso repetido de GitHub Actions.
`07-governed-knowledge-writes-plan.md` abre o proximo corte de capacidade:
LLM pode propor mudancas de conhecimento, mas a plataforma deve persistir isso
como change set governado, validado e aprovado antes de aplicar.
`08-page-builder-continuity-phase.md` registra a fase localmente concluida de
continuidade governada no cockpit. `09-domain-knowledge-revert-phase.md`
registra o baseline local-first de reversibilidade governada, e
`release-readiness-2026-05-01-domain-knowledge-revert.md` registra o checkpoint
operacional dessa fase sem transformar o fechamento em publicacao.
`release-readiness-2026-05-01-domain-knowledge-contract-corpus.md` fecha o
checkpoint posterior de contrato/corpus: OpenAPI, binding Angular e corpus HTTP
estao sincronizados, mas o exemplo mutavel permanece `referenceOnly`.
`10-domain-knowledge-supersede-evidence-plan.md` registra a decisao beta de nao
promover `supersede_evidence` agora e o hardening concluido de
`revert_evidence + replacementEvidenceKey`. O plano ativo seguinte e
`11-vector-rag-active-evidence-filtering-plan.md`: ele define como preparar
ranking vector/RAG sem permitir que indice derivado substitua a fonte canonica
de Domain Knowledge ou contorne evidencia ativa.
`release-readiness-2026-05-02-project-knowledge-vector-rag.md` registra a prova
local desse plano, incluindo os comandos de gate e a decisao de manter
publicacao/retrieval desabilitadas por padrao.
`release-decision-2026-05-02-project-knowledge-vector-rag.md` registra a
decisao atual de nao publicar ainda: a fase esta pronta como evidencia local,
mas Maven Central so deve ser usado com autorizacao explicita, consumidor
nomeado e um gate remoto unico de fechamento.
`project-knowledge-vector-rag-release-checklist-2026-05-02.md` transforma essa
decisao em passos operacionais para um futuro RC autorizado, sem gastar Actions
durante iteracao normal. `12-page-builder-stream-first-routing-plan.md`
registra o proximo slice ativo: inventariar o Page Builder e tornar o stream do
backend o caminho principal para authoring de componente/pagina, enquanto
prompts governados falham fechados para handoffs canonicos. Os documentos
anteriores continuam uteis como historico e diagnostico, mas devem ser
interpretados pela direcao atual: Page Builder authora componentes/paginas,
decisoes compartilhadas de negocio devem seguir por
`/api/praxis/config/domain-rules/**`, conhecimento persistente deve seguir pela
fronteira `domain_knowledge_change_set`, correcoes de conhecimento aplicado
devem seguir por revert/supersede governado, e ranking RAG deve permanecer uma
projecao derivada revalidada contra o estado canonico.

## Baseline de reaproveitamento

A investigacao do monorepo em 2026-04-29 mostrou que a proxima fase deve
reaproveitar e extrair capacidades ja existentes, nao reconstruir o fluxo do
zero:

- `AgenticAuthoringTurnStreamService` ja possui ciclo SSE, replay, probe,
  cancelamento, timeout, ownership e append de eventos; ele deve continuar
  dono do transporte enquanto `AgenticAuthoringTurnEngine` concentra a
  orquestracao de negocio.
- `AiTurnEventService`, `AiTurnEventEnvelope` e `AiSensitiveDataRedactor` ja
  formam a trilha canonica de eventos, replay e redaction segura.
- `AgenticAuthoringResourceDiscoveryService.search` ja e o comportamento
  backend executado pelo primeiro tool interno `searchApiResources`.
- `AgenticAuthoringCurrentPageAnalyzer` ja oferece inspecao estrutural de
  `currentPage`, reduzindo dependencia de `currentPageSummary`.
- `DomainRuleService` e `DomainRuleController` ja sao a fronteira canonica para
  intake, definition, simulation, publication, materialization, timeline e
  transicoes de status.
- O Page Builder Angular ja possui stream turn, cockpit de shared-rule handoff
  e chamadas ao `DomainRuleService`; a UI deve continuar cockpit/runtime, nao
  fonte primaria da decisao.
- `praxis-api-quickstart`, `praxisui-http-examples` e
  `scripts/workspace/run-local-readiness-lane.sh` ja contem corpus e lanes
  locais para provar domain-rules, timeline, cockpit e runtime enforcement.
- A prova local de `backend_validation` ja demonstrou que uma decisao governada
  publicada em `domain-rules` pode ser consumida pelo quickstart para bloquear
  um comando real com `409 Conflict`, desde que o host ativo esteja empacotado
  com o starter correto e a origem configurada para `/api/praxis/config/**`.

## Invariantes canonicos

- O dono canonico do fluxo e o backend em `praxis-config-starter`.
- O frontend nao deve virar orquestrador de tool calls.
- `currentPage` e o artefato primario; `currentPageSummary` e derivado auxiliar.
- O envelope SSE canonico continua sendo `AiTurnEventEnvelope`.
- Tools de authoring devem ser tratadas como comportamento executavel interno do
  backend, nao como hint textual nem contrato publico automatico.
- Heuristica lexical pode existir como fallback, mas nao como semantica primaria.
- Repair loop deve ser backend-owned, limitado e auditavel.
- Project knowledge persistente, quando entrar, deve ser governado, escopado e
  ancorado na Domain Knowledge Layer; nunca um blob opaco de prompt salvo no
  cliente.

## O que nao fazer

- Nao criar um segundo formato de stream para authoring.
- Nao empurrar logica de selecao de recurso para o Angular quando o backend ja
  tiver superficie canonica para isso.
- Nao adicionar feature flags ou trilhas paralelas permanentes para preservar o
  fluxo antigo, salvo necessidade operacional explicita.
- Nao tratar `currentPageSummary` como fonte de verdade quando `currentPage`
  estiver disponivel.
- Nao declarar tool como "implementada" se ela ainda for apenas um item textual
  do prompt ou `quickReply`.
- Nao usar `AiOrchestratorService`, estado Angular, browser storage,
  `WidgetPageDefinition`, quick replies ou timeline segura como fonte primaria
  de regras de negocio governadas.
- Nao usar Page Builder preview/apply como caminho de materializacao para
  decisoes compartilhadas que pertencem a `/api/praxis/config/domain-rules/**`.

## Prova minima de conclusao por incremento

Cada incremento so pode ser considerado concluido quando:

- os testes focais do backend passarem;
- os docs e contratos afetados estiverem atualizados;
- o fluxo E2E de navegador real definido em
  [03-browser-e2e-definition-of-done.md](./03-browser-e2e-definition-of-done.md)
  estiver verde para os cenarios impactados;
- o resultado final vier do backend canonico e nao de fallback mockado no
  frontend.
