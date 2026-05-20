# Fase 07: Operacao de Release, Reindex e Observabilidade

Status: concluido em 2026-05-19.

## Objetivo

Tornar o corpus operacionalmente industrial: versionado por release, reindexavel, observavel e reconciliavel.

## Escopo

- manifesto de corpus/release;
- status de publicacao;
- contadores por release/componente/chunk;
- retry/backfill/reindex;
- diagnostics locais;
- docs operacionais.

## Guardrails

- Nao usar GitHub Actions como mecanismo exploratorio.
- Nao acoplar release externo npm/Maven ao reindex interno sem consumidor nomeado.
- Falhas de publicacao nao devem ficar silenciosas se quebrarem authoring.

## Entregas

- Modelo interno de status por release em `RagVectorStoreService.RagCorpusReleaseStatus`.
- Status agrega `documentCount`, `sourceCount`, contadores por `chunkKind`, contadores por `aiVisibility`, `latestPublishedAt`, sources publicados e warnings.
- Reconciliation compara `expectedChunkCount` com documentos publicados no `vector_store` para a release/tenant/environment.
- `RegistryIngestionService.reindexRegistry(...)` funciona como comando/job interno de backfill/reindex e retorna `RegistryReindexResult`.
- `ingestRegistry(...)` permanece compatível e delega para `reindexRegistry(...)`.
- Falhas de status não ficam silenciosas: status indisponível ou query quebrada retorna `available=false` com warning operacional.
- Não foi criado endpoint HTTP nesta fase; o contrato interno é suficiente para job/status e evita ampliar superfície pública antes de um consumidor operacional nomeado.

## Readiness operacional

Para uma release estar pronta para authoring:

- `RegistryReindexResult.expectedChunkCount` deve ser maior que zero quando o corpus tiver componentes publicados.
- `RegistryReindexResult.publishedChunkCount` deve bater com `expectedChunkCount`.
- `RegistryReindexResult.corpusStatus.available` deve ser `true`.
- `RegistryReindexResult.corpusStatus.reconciled` deve ser `true`.
- `corpusStatus.warnings` deve estar vazio.
- `corpusStatus.sources` deve listar os componentes esperados da release.
- `corpusStatus.chunkKindCounts` deve refletir os tipos de chunk esperados, por exemplo `summary`, `authoring_manifest`, `capabilities`, `recipe` ou equivalentes publicados.
- `corpusStatus.visibilityCounts` deve ser revisado para garantir que chunks usados por authoring tenham `aiVisibility=allow`.

Reindex/backfill seguro:

1. Executar `RegistryIngestionService.reindexRegistry(request, tenantId, environment)` com o manifesto/corpus da release.
2. Conferir `RegistryReindexResult.corpusStatus`.
3. Se houver `corpus-chunk-count-mismatch`, reprocessar a release e investigar purge/upsert por source scope.
4. Se houver `corpus-release-empty`, bloquear authoring dependente dessa release ou acionar fallback controlado para release default, quando configurado.

## Validacao minima

- Testes focais de job/status.
- Smoke local se houver endpoint operacional.
- `git diff --check`.

Validado em 2026-05-19:

- `mvn -B -Dtest=RagVectorStoreServiceTest,RegistryIngestionServiceIdentityTest test`
- `mvn -B -Dtest=RagVectorStoreServiceTest,RegistryIngestionServiceIdentityTest,ContextRetrievalServiceTest,AgenticAuthoringTurnEngineTest test`
- `git diff --check`

## Criterio de pronto

Uma release consegue declarar qual corpus esta ativo, quando foi indexado, quantos chunks publicou e como reindexar com seguranca.

## Handoff para Fase 08

A Fase 08 pode usar `RagCorpusReleaseStatus` para identificar duplicidades por release/source/chunk e orientar a migracao de contratos antigos. O status operacional tambem fornece os contadores necessarios para decidir quando remover artefatos legados sem perder cobertura de retrieval.
