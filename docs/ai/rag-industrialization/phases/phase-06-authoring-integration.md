# Fase 06: Integracao no Authoring Turn

Status: concluido em 2026-05-19.

## Objetivo

Integrar retrieval granular ao `AgenticAuthoringTurnEngine`, entregando evidencias ao LLM antes de planejamento/materializacao.

## Escopo

- `AgenticAuthoringTurnEngine`
- prompt/bundle de authoring
- tool loop se existente
- diagnostics do turno
- testes de fluxo agentic

## Guardrails

- `validate-plan`, `compile-patch`, preview e apply continuam mandatorios.
- Retrieval nao substitui capability checks.
- Evidencias devem ser diagnosticaveis e limitadas.
- `@praxisui/ai` nao deve implementar retrieval semantico.

## Entregas

- `AgenticAuthoringTurnEngine` recupera evidencia granular via `getComponentAuthoringContext` na fase `retrieveEvidence`, depois da resolucao de intencao e antes de `preview.plan`.
- Evidencia e injetada em `contextHints.authoringEvidence` com limite de 6 entradas, conteudo truncado, `sourceRef`, `releaseId`, `chunkKind`, `contentHash`, `corpusVersion` e score.
- Diagnostics do turno incluem `authoringEvidenceCount` e `authoringEvidenceSourceRefs`.
- Eventos `thought.step` registram `authoringEvidence.retrieve` e `authoringEvidence.result/error` com diagnosticos seguros.
- `AgenticAuthoringContextBundle.toolCatalog` declara as ferramentas read-only relevantes para grounding: `getComponentAuthoringContext`, `getManifestSlice` e `searchSchemaFields`.
- `validate-plan`, `compile-patch`, preview e apply permanecem como gates de governanca; retrieval e apenas evidencia.
- `@praxisui/ai` permanece cliente/UX e nao implementa retrieval semantico.

## Validacao minima

- Testes focais Java do authoring turn.
- Specs frontend apenas se contrato cliente mudar.
- `git diff --check`.

Validado em 2026-05-19:

- `mvn -B -Dtest=AgenticAuthoringTurnEngineTest,AgenticAuthoringToolRegistryTest,ContextRetrievalServiceTest test`
- `mvn -B -Dtest=AgenticAuthoringContextBundleTest,AgenticAuthoringLlmIntentResolverServiceTest test`
- `git diff --check`

## Criterio de pronto

O LLM usa corpus granular como grounding, mas a plataforma ainda governa decisao e materializacao.

## Handoff para Fase 07

A Fase 07 pode focar operacao de release, reindex e observabilidade do corpus publicado. Pontos naturais de telemetria agora existem no turno: quantidade de evidencia recuperada, source refs, release/chunk nos `contextHints.authoringEvidence` e eventos `authoringEvidence.*`.
