# Fase 05: Retrieval Granular Read-only

Status: concluido em 2026-05-19.

## Objetivo

Expor retrieval granular e governado para authoring, sem permitir que a busca aplique patch ou redefina contrato.

## Escopo

- `ContextRetrievalService`
- `AgenticAuthoringToolRegistry`
- `AgenticAuthoringManifestService`
- `SchemaRetrievalService`
- DTOs/contratos internos necessarios

## Ferramentas candidatas

- `searchComponentCorpus`
- `getComponentAuthoringContext`
- `getManifestSlice`
- `searchConfigPathDocs`
- `searchExamples`
- `searchSchemaFields`

## Guardrails

- Ferramentas devem ser read-only.
- Retrieval deve devolver evidencia limitada, source refs e release.
- Manifest slice deve vir do backend/registry, nao de parsing frontend.
- Project knowledge deve respeitar filtros existentes de visibilidade/aprovacao.

## Entregas

- Tools read-only registradas no `AgenticAuthoringToolRegistry`:
  - `searchComponentCorpus`
  - `getComponentAuthoringContext`
  - `getManifestSlice`
  - `searchConfigPathDocs`
  - `searchExamples`
  - `searchSchemaFields`
- `ContextRetrievalService.searchComponentCorpus(...)` adicionado como retrieval granular do corpus AI-ready por componente.
- Retrieval usa `vector_store` existente, filtros de release/tenant/environment, fallback controlado para release default e `aiVisibility=allow`.
- `getManifestSlice` delega ao `AgenticAuthoringManifestService`, mantendo o backend/registry como fonte do manifesto.
- `searchSchemaFields` delega ao `SchemaRetrievalService`, mantendo schemas como evidencia read-only.
- Ferramentas retornam evidencia, source refs, release e metadados; nenhuma aplica patch ou redefine contrato.
- Testes cobrem registro de ferramentas, fase permitida, indisponibilidade de servico, delegation read-only, filtro granular, visibilidade e fallback.

## Validacao minima

- Testes focais Java de tool registry/retrieval.
- `git diff --check`.

Validado em 2026-05-19:

- `mvn -B -Dtest=ContextRetrievalServiceTest,AgenticAuthoringToolRegistryTest test`
- `git diff --check`

## Criterio de pronto

O authoring backend consegue buscar slices granulares por componente/schema/exemplo sem depender de contexto gigante no prompt inicial.

## Handoff para Fase 06

A Fase 06 pode integrar essas ferramentas no turno de authoring como retrieval sob demanda, preservando `validate-plan`, `compile-patch`, preview e apply como fronteiras de governanca. `@praxisui/ai` deve continuar consumidor/orquestrador de chamadas, sem implementar retrieval semantico proprio.
