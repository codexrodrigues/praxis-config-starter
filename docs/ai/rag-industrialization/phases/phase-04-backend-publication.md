# Fase 04: Publicacao Multi-chunk no Vector Store

Status: concluido.

## Objetivo

Publicar o corpus granular no `vector_store` interno como multiplos chunks por componente, com reindex seguro por release/scope/source.

## Escopo

- `praxis-config-starter`
- `RegistryIngestionService`
- `RagVectorStoreService`
- migrations se forem inevitaveis
- testes focais de ingestion/publicacao

## Guardrails

- Nao criar tabela paralela de embedding.
- Nao deixar documentos antigos coexistirem quando o conteudo muda no mesmo release/scope.
- Preservar `tenantId`, `environment`, `releaseId`, source refs e visibilidade.

## Entregas

- Publicacao multi-chunk.
- Estrategia de delete/reconcile antes de republicar.
- Backfill basico ou comando operacional se necessario.
- Testes de stale-doc prevention.

## Implementacao

- `RegistryIngestionService` agora materializa `components[].chunks[]` como multiplos `Document` no `vector_store`, preservando `tenantId`, `environment`, `releaseId`, `sourceKind`, `sourceId`, `sourcePointer`, `chunkKind`, `chunkIndex`, `contentHash`, `corpusVersion`, `aiVisibility`, `embeddingProfile` e chaves legadas (`resourceType`, `resourceId`, `componentId`, `docType`) exigidas pelos indices e retrieval existentes.
- Enquanto o gerador da Fase 03 nao envia visibilidade explicita por chunk, o backend publica `aiVisibility=allow` como default rastreavel, sem transformar o RAG em fonte primaria de regra de acesso.
- A identidade canonica dos chunks publicados segue o contrato da Fase 02:
  `{tenantId}/{environment}/{sourceId}/{releaseId}/{sourceKind}/{chunkKind}/{contentHash}/{chunkIndex}`.
- Antes de publicar os chunks novos, o backend purga documentos existentes por `tenantId + environment + releaseId + sourceId + sourceKind`. A purga e feita a partir dos metadados dos documentos gerados, portanto respeita o `sourceId/sourceKind` canonico do chunk mesmo quando ele diverge da chave do mapa `components`.
- `RagVectorStoreService` passou a expor purga por escopo reutilizando o `vector_store` compartilhado, sem tabela paralela. O nome da tabela respeita `praxis.ai.rag.vector-store.table` e o JDBC e resolvido de forma lazy para manter contextos sem vector store/banco funcionando.
- Backfill separado nao foi necessario nesta fase: a republicacao do mesmo release/scope/source ja remove fatias obsoletas antes do novo upsert.

## Validacao realizada

- `mvn -B -Dtest=RagVectorStoreServiceTest,RegistryIngestionServiceIdentityTest test`
- `mvn -B -Dtest=RagVectorStoreServiceTest,RegistryIngestionServiceIdentityTest,ConfigJdbcTemplateAutoConfigurationTest,UserConfigServiceTest test`
- `git -C praxis-config-starter diff --check`

## Validacao pendente

- `mvn -B -Dtest=RegistryIngestionServiceTest test` ainda nao passa porque o contexto amplo do teste carrega `DomainFederationController`/servicos de federation sem todos os repositorios de federation mockados quando `DataSourceAutoConfiguration` esta excluida. A primeira falha de `configNamedParameterJdbcTemplate` sem `JdbcTemplate` foi corrigida, mas a suite precisa de isolamento adicional fora do escopo direto da publicacao multi-chunk.

## Validacao minima

- Testes focais Java de ingestion/RAG.
- `git diff --check`.
- Sem smoke remoto.

## Criterio de pronto

O backend consegue receber/publicar chunks por componente no indice interno sem acumular documentos obsoletos.

## Handoff para Fase 05

- Entrada pronta: os chunks granulares ja chegam ao `vector_store` com `sourceId`, `sourceKind`, `chunkKind`, `sourcePointer` e `contentHash`.
- Proxima decisao: evoluir retrieval read-only para ranquear e expor evidencias granulares sem tratar o RAG como fonte primaria de contrato.
- Ponto de atencao: manter filtros por `tenantId`, `environment`, `releaseId`, visibilidade e `sourceRef` ao montar o bundle de evidencia para authoring.
