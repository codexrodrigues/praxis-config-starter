# Implementation Backlog

Este backlog esta em ordem de execucao. Nao abra as fases finais antes de fechar
as dependencias estruturais das fases iniciais.

## Fase 1 - Turn engine e tools minimas

### Item 1. Extrair `AgenticAuthoringTurnEngine`

**Objetivo**
- tirar a orquestracao principal de `AgenticAuthoringTurnStreamService`;
- centralizar o turno em um engine explicito.

**Arquivos provaveis**
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnEngine.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnState.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnOutcome.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnStreamService.java`

**Definition of Done**
- stream service deixa de ser o orquestrador primario;
- o engine executa pelo menos o fluxo linear atual;
- endpoints e envelopes publicos permanecem compativeis.

### Item 2. Criar o modelo executavel de tools

**Objetivo**
- criar registry/executor internos para tools reais.

**Tipos minimos**
- `AgenticAuthoringToolDefinition`
- `AgenticAuthoringToolCall`
- `AgenticAuthoringToolResult`
- `AgenticAuthoringToolExecutor`
- `AgenticAuthoringToolRegistry`

**Definition of Done**
- engine consegue registrar e executar tool por nome;
- erro de tool tem shape estruturado;
- ha rastreabilidade de chamada e resultado.

### Item 3. Implementar `searchApiResources` como tool real

**Objetivo**
- promover a busca de candidatos de hint textual para execucao canonica.

**Definition of Done**
- tool usa o catalogo/backend canonico;
- resultado traz candidatos, score e provenance;
- o engine pode chama-la sem depender do controller.

### Item 4. Implementar `inspectCurrentPage`

**Objetivo**
- oferecer inspecao estruturada do artefato atual.

**Resposta minima esperada**
- `artifactKind`
- `componentType`
- `boundResource`
- `editableRegions`
- `fields`
- `widgets`
- `serverBindings`
- `transientBindings`

**Definition of Done**
- engine consulta `currentPage` como estrutura;
- inspecao nao depende de resumo opaco;
- existe cobertura com fixtures de form e dashboard.

### Item 5. Primeira politica de decisao de tools

**Objetivo**
- permitir que o engine chame tool antes de gerar resultado.

**Definition of Done**
- ha cenarios reais em que `searchApiResources` roda antes do preview;
- ha cenarios reais em que `inspectCurrentPage` roda antes de decidir `modify`;
- numero maximo de tool calls por turno e limitado.

## Fase 2 - Reducao de summary e retrieval governado

### Item 6. Rebaixar `currentPageSummary` a derivado auxiliar

**Objetivo**
- usar `currentPage` como fonte primaria;
- manter `currentPageSummary` apenas para compatibilidade e compactacao.

**Definition of Done**
- resolver deixa de depender do summary para regras principais;
- summary continua apenas como apoio textual.

### Item 7. Migrar planner/validator para inspecao estruturada

**Objetivo**
- remover dependencia indireta de summary em plan/preview/validator.

**Definition of Done**
- planner e validator operam sobre dados estruturados;
- cenarios de `add_field`, `remove_field` e `add_widget` continuam corretos.

### Item 8. Separar retrieval semantico de fallback lexical

**Objetivo**
- dividir retrieval em componentes claros.

**Componentes esperados**
- `SemanticCandidateRetriever`
- `LexicalFallbackCandidateRetriever`
- `CandidateRankingPolicy`
- opcionalmente `DeterministicOverridePolicy`

**Definition of Done**
- origem do candidato e explicita;
- RAG e a trilha primaria quando disponivel;
- fallback continua funcional sem contaminar a camada semantica.

## Fase 3 - Docs, contrato e UI principal

### Item 9. Atualizar docs para refletir o comportamento real

**Objetivo**
- alinhar docs a implementacao efetiva.

**Definition of Done**
- `docs/ai/agentic-authoring-streaming.md` e `docs/ai/contracts/**` nao vendem
  um comportamento que ainda nao existe;
- este diretorio de implementation continua coerente com o codigo.

### Item 10. Promover tools estabilizadas ao contrato publico

**Objetivo**
- atualizar OpenAPI/contratos somente quando a implementacao estiver madura.

**Definition of Done**
- schema/documentacao bate com o runtime;
- exemplos de payload estao atualizados;
- nao ha drift entre docs e backend.

### Item 11. Fazer `@praxisui/ai` usar o turn stream como fluxo principal

**Objetivo**
- fazer o assistente Angular depender primariamente do fluxo agentic do backend.

**Definition of Done**
- fluxo principal usa `startAgenticAuthoringTurnStream`;
- probe/cancel funcionam;
- fallback antigo so fica onde for indispensavel e explicitado.

## Fase 4 - Self-healing

### Item 12. Classificar falhas recuperaveis

**Tipos minimos**
- `retryable`
- `non_retryable`
- `route_required`
- `user_clarification_required`

**Definition of Done**
- erros de preview/compile sao classificados;
- o engine sabe distinguir retry de falha terminal.

### Item 13. Implementar repair loop limitado

**Objetivo**
- permitir retry automatico para falhas recuperaveis.

**Definition of Done**
- maximo inicial: 1 retry por fase;
- stream mostra `repair.attempt`;
- erro terminal so aparece apos retry esgotado ou falha nao recuperavel.

## Fase 5 - Memoria persistente de projeto

### Item 14. Definir modelo canonico de project knowledge

Plano detalhado: [05-governed-project-knowledge-plan.md](./05-governed-project-knowledge-plan.md)

**Modelo minimo**
- `scope`
- `kind`
- `source`
- `status`
- `payload`
- `visibility`

**Definition of Done**
- memoria nao e historico de chat;
- escopo e governanca sao explicitos.
- Domain Knowledge Layer e usada como fonte canonica, salvo lacuna explicita.
- RAG/vector store e tratado apenas como indice derivado.

### Item 15. Expor retrieval seletivo de knowledge no turno

**Definition of Done**
- engine recupera apenas o conhecimento relevante;
- knowledge influencia o turno de forma auditavel;
- cliente nao e a fonte canonica dessa memoria.

## Fase 6 - Escrita governada de Project Knowledge

Plano detalhado: [07-governed-knowledge-writes-plan.md](./07-governed-knowledge-writes-plan.md)

Status: implementada ate o primeiro corte publicado em `rc.37`.

### Item 16. Persistir propostas como Domain Knowledge change sets

**Definition of Done**
- criacao, listagem e leitura usam `/api/praxis/config/domain-knowledge/change-sets`;
- respostas comuns sao seguras e nao expõem patch bruto;
- escopo tenant/environment e validado.

### Item 17. Validar, aprovar, aplicar e auditar change sets

**Definition of Done**
- validacao deterministica e persistida;
- transicoes de status capturam reviewer;
- aplicacao de `add_evidence` exige `approved` e `valid`;
- timeline segura expõe criacao, validacao, aprovacao e aplicacao sem vazamento.

### Item 18. Provar no quickstart e corpus

**Definition of Done**
- smoke Neon-backed prova create -> validate -> approve -> apply -> readback;
- host publicado passa com `REQUIRE_CHANGE_SET_TIMELINE=true`;
- corpus HTTP protegido registra a timeline como `protectedContract`, nao como
  `llmOperational`.

## Fase 7 - Continuidade governada no Page Builder

Plano detalhado: [08-page-builder-continuity-phase.md](./08-page-builder-continuity-phase.md)

Status: implementada localmente em `praxis-ui-angular/main`; handoff
documental concluido; aguardando consumidor nomeado ou release gate para novo
corte.

### Item 19. Inventariar contratos e modelos atuais do cockpit

**Objetivo**
- mapear os endpoints, services Angular, fixtures e E2E lanes existentes antes
  de implementar novas acoes.

**Definition of Done**
- nenhum endpoint e rota sao inferidos por nome ou duplicados na UI;
- o plano cita arquivos Angular e comandos locais exatos;
- lacunas de client method sao documentadas antes de alterar UX.

**Status**
- Concluido no inventario local do Page Builder.

### Item 20. Consolidar modelo de acoes governadas

**Objetivo**
- representar acoes de continuidade como projeção segura do backend, nao como
  regra de negocio no frontend.

**Definition of Done**
- disponibilidade de acoes vem do handoff/status canonico;
- acoes bloqueadas explicam o motivo;
- nenhum payload bruto de regra, evidencia, patch ou prompt entra no modelo
  comum do cockpit.

**Status**
- Concluido no modelo local de acoes governadas do `@praxisui/page-builder`.

### Item 21. Unificar UX de continuidade para Domain Rules e Project Knowledge

**Objetivo**
- fazer o cockpit guiar create/open, simulate, approve/publish, materialize,
  validate enforcement, create/validate/apply change set e open timeline com a
  mesma linguagem visual de governanca.

**Definition of Done**
- Domain Rules e Project Knowledge compartilham estados visuais de governanca;
- timeline/audit e readback aparecem como prova derivada;
- Page Builder preview/apply nao e usado como materializacao de decisao
  compartilhada.

**Status**
- Concluido para a primeira trilha de continuidade: Shared Rules e Project
  Knowledge usam acoes governadas e Project Knowledge renderiza timeline segura
  derivada da projeção canonica em `@praxisui/core`.

### Item 22. Provar no navegador local

**Objetivo**
- estender as lanes locais versionadas sem criar runners ad hoc.

**Definition of Done**
- browser E2E prova que nao ha mutacao silenciosa antes de approval/apply;
- cockpit nao renderiza patch, prompt, evidencia bruta ou chat history;
- post-apply Project Knowledge pode ser citado de forma segura em turno
  posterior;
- servicos locais sao limpos pelo runner.

**Status**
- Concluido para a lane Project Knowledge cockpit em 2026-05-01:
  `AI_PROVIDER=openai AI_ENV_FILE=../praxis-config-starter/.env.openai.local.sh PRAXIS_E2E_TIMEOUT_MS=900000 ./tools/local-e2e/run-project-knowledge-audit-cockpit-local.sh`
  passou localmente em `praxis-ui-angular` (`1 passed (10.2s)` apos os servicos
  ficarem prontos).
- A prova inclui create, validate, approve, apply, readback e timeline segura
  sem expor `conceptKey`, `sourceSummary`, `sourcePointer`, `patchHash`,
  `assistantMessage`, `materializedPayload` ou resumo bruto de conhecimento.

### Item 23. Handoff documental e decisao de release gate

**Objetivo**
- registrar o estado real da fase, os comandos locais e a politica de nao
  publicar enquanto nenhum consumidor nomeado exigir o corte.

**Definition of Done**
- runbooks locais citam o comando e o resultado da lane verde;
- docs explicam que a projeção de timeline e canonica em `@praxisui/core`;
- docs registram que `@praxisui/page-builder` nao deve criar dependencia
  publica nova em `@praxisui/rich-content` apenas para renderizar cockpit;
- proximo uso de GitHub Actions fica reservado para fechamento de fase,
  release ou smoke publicado.

**Status**
- Concluido em 2026-05-01. Runbooks, handoff e guia do quickstart registram o
  comando local, o resultado da lane verde, a fronteira do renderer
  rich-content e a politica local-first.
- Decisao atual: nao publicar Maven/npm e nao acionar GitHub Actions apenas por
  esta fase. O proximo gate remoto deve acontecer somente quando houver
  consumidor nomeado, release planejada ou smoke publicado a validar.

## Fase 8 - Reversibilidade governada de Domain Knowledge

Plano detalhado: [09-domain-knowledge-revert-phase.md](./09-domain-knowledge-revert-phase.md)

Status: proxima fase recomendada; planejamento arquitetural criado antes de
qualquer contrato publico ou operacao destrutiva.

Inventario inicial concluido em 2026-05-01 no plano detalhado da fase. A
implementacao deve comecar pelo lifecycle canonico de evidencia e pelos pontos
de retrieval que precisarao filtrar evidencia ativa.

Lifecycle baseline iniciado em 2026-05-01: schema e entidade passam a reconhecer
evidencia `active`, `superseded` e `reverted`, mas `revert_evidence` ainda nao
foi promovido.

Validation baseline iniciado em 2026-05-01: `revert_evidence` passa a ser uma
operacao estruturalmente valida quando identifica conceito, evidencia, razao e
provas, mas apply/repository checks ainda ficam no proximo slice.

Apply baseline iniciado em 2026-05-01: `revert_evidence` agora exige evidencia
ativa no mesmo conceito e aplica lifecycle `reverted` sem delete fisico.

Timeline baseline iniciado em 2026-05-01: change sets aplicados com
`revert_evidence` emitem eventos seguros `evidence.reverted` e
`evidence.superseded`, sem expor chaves ou payload bruto.

HTTP smoke preparado em 2026-05-01: o runner local pode exigir
`REQUIRE_EVIDENCE_REVERT=true` para provar `revert_evidence` por HTTP real no
quickstart, junto de `REQUIRE_CHANGE_SET_TIMELINE=true`.

### Item 24. Inventariar lifecycle e retrieval de evidencias

**Objetivo**
- mapear como `domain_knowledge_evidence` e consultada hoje e onde evidencia
  revertida deve deixar de influenciar authoring.

**Definition of Done**
- consumidores de retrieval sao nomeados;
- decisao entre colunas de lifecycle ou tabela de eventos e registrada;
- nenhum `delete_*` e promovido antes desse mapa.

### Item 25. Modelar lifecycle canonico de evidencia

**Objetivo**
- preservar linhas originais e permitir estados como `active`, `superseded` e
  `reverted`.

**Definition of Done**
- migracao define default seguro para evidencias existentes;
- entidades/repositorios permitem filtrar evidencias ativas;
- revert nao depende de estado de UI.

### Item 26. Validar e aplicar `revert_evidence`

**Objetivo**
- tratar revert como operacao governada por change set, nao como delete fisico.

**Definition of Done**
- validator exige reason, scope, evidencia existente, conceito alvo e
  evidenceRefs para LLM;
- apply marca evidencia como revertida/superseded de forma transacional;
- aplicar o mesmo change set continua idempotente.

### Item 27. Provar timeline/readback seguro de revert

**Objetivo**
- expor prova segura de reversao sem payload bruto.

**Definition of Done**
- timeline inclui eventos seguros de revert/supersede;
- resposta nao inclui payload, source pointer, source URI, patch hash, prompt
  ou chat history;
- smoke local protegido cobre create -> validate -> approve -> apply ->
  timeline para revert.

### Item 28. Preparar cockpit somente apos backend estavel

**Objetivo**
- permitir que Page Builder solicite revert governado sem virar fonte de
  memoria.

**Definition of Done**
- UI so oferece acao quando backend expuser contrato estavel;
- browser E2E prova que nao ha delecao local;
- Actions continuam reservadas para gate de fase/release.

## Regras operacionais para qualquer PR desta trilha

- Nao misturar Fase 1 e Fase 4 no mesmo PR.
- Nao promover contrato publico de uma tool antes de estabilizar a execucao.
- Nao remover fallback antigo do frontend antes do backend provar o novo fluxo em
  navegador real.
- Sempre atualizar docs afetados no mesmo ciclo quando mudar comportamento real.
- Se mexer em SSE, validar `start`, `probe`, stream, evento terminal e `cancel`.

## Fechamento obrigatorio por item

Nenhum item acima deve ser marcado como concluido sem:

- testes focais backend verdes;
- docs/contratos sincronizados quando aplicavel;
- validacao E2E de navegador dos cenarios impactados conforme
  `03-browser-e2e-definition-of-done.md`.
