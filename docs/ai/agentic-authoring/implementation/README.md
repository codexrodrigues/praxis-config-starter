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
materializacao. A proxima capacidade recomendada e planejar e implementar
reversibilidade governada de Domain Knowledge (`revert_evidence`) antes de
ampliar operacoes destrutivas ou novos tipos de escrita.

## Ordem de leitura

1. [04-implementation-ready-plan.md](./04-implementation-ready-plan.md)
2. [05-governed-project-knowledge-plan.md](./05-governed-project-knowledge-plan.md)
3. [06-next-cut-decision.md](./06-next-cut-decision.md)
4. [release-readiness-2026-04-30-project-knowledge.md](../../release-readiness-2026-04-30-project-knowledge.md)
5. [project-knowledge-release-checklist-2026-04-30.md](../../project-knowledge-release-checklist-2026-04-30.md)
6. [07-governed-knowledge-writes-plan.md](./07-governed-knowledge-writes-plan.md)
7. [08-page-builder-continuity-phase.md](./08-page-builder-continuity-phase.md)
8. [09-domain-knowledge-revert-phase.md](./09-domain-knowledge-revert-phase.md)
9. [01-current-state-and-target.md](./01-current-state-and-target.md)
10. [02-implementation-backlog.md](./02-implementation-backlog.md)
11. [03-browser-e2e-definition-of-done.md](./03-browser-e2e-definition-of-done.md)

`04-implementation-ready-plan.md` e a fonte ativa para preparar novos PRs,
`05-governed-project-knowledge-plan.md` detalha a Phase 7, e
`06-next-cut-decision.md` registra a recomendacao pos-checkpoint. O relatorio de
release-readiness e o checklist operacional registram como fechar a fase sem
transformar docs-only em publicacao ou em uso repetido de GitHub Actions.
`07-governed-knowledge-writes-plan.md` abre o proximo corte de capacidade:
LLM pode propor mudancas de conhecimento, mas a plataforma deve persistir isso
como change set governado, validado e aprovado antes de aplicar.
`08-page-builder-continuity-phase.md` registra a fase localmente concluida de
continuidade governada no cockpit. `09-domain-knowledge-revert-phase.md` e o
plano ativo seguinte: antes de ampliar operacoes alem de `add_evidence`, a
plataforma deve suportar reversibilidade governada sem deletar evidencia ou
expor payload bruto. Os documentos anteriores continuam uteis como historico e
diagnostico, mas devem ser interpretados pela direcao atual: Page Builder
authora componentes/paginas, decisoes compartilhadas de negocio devem seguir
por `/api/praxis/config/domain-rules/**`, conhecimento persistente deve seguir
pela fronteira `domain_knowledge_change_set`, e correcoes de conhecimento
aplicado devem seguir por revert/supersede governado.

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
