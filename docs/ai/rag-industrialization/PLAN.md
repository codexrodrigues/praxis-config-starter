# Plano Mestre: Industrializacao do RAG AI-ready por Componente

Documento vivo do monorepo. Criado em 2026-05-19. Classificacao da mudanca: `arquitetural` + `contrato-publico` + `transversal`.

## Premissa canonica

Praxis e uma plataforma de decisoes semanticas authoradas por IA.

O RAG industrializado deve servir como grounding e recuperacao de evidencia para authoring, nao como fonte primaria de regra de negocio, contrato publico ou materializacao. A fonte canonica permanece nos modulos donos:

- `praxis-config-starter`: config store, `ai_registry`, RAG interno, authoring governado e publicacao de indices derivados.
- `praxis-metadata-starter`: grounding metadata-driven, `/schemas/filtered`, resources, actions, capabilities e HATEOAS.
- `praxis-ui-angular`: runtime oficial, manifests/capabilities/context packs e tooling de registry das bibliotecas `@praxisui/*`.
- `praxis-api-quickstart`: prova operacional por HTTP real.
- `praxis-ui-landing-page` e `praxisui-http-examples`: documentacao e corpus derivados.

## Decisao central

Nao criar um novo RAG paralelo.

Evoluir o que ja existe:

- `vector_store` compartilhado;
- `RagVectorStoreService`;
- `RagMetadataKeys`;
- `RagResourceTypes`;
- `RegistryIngestionService`;
- `ContextRetrievalService`;
- `AiRagContextService`;
- `AgenticAuthoringTurnEngine`;
- `AgenticAuthoringToolRegistry`;
- `generate-registry-ingestion.ts`;
- `generate-registry-rag.ts`;
- `generate-ai-ready-docs.ts`;
- manifests, capabilities, context packs e recipes.

## Estado conhecido em 2026-05-19

Ja existe RAG interno, mas ainda nao ha corpus granular AI-ready por componente como contrato operacional first-class.

Ja existem documentos e scripts que parecem sobrepostos:

- `dist/praxis-component-registry.json`;
- `dist/praxis-component-registry-ingestion.json`;
- `dist/praxis-component-registry-rag.json`;
- `dist/ai-ready/llms.txt`;
- `dist/ai-ready/docs-index.json`;
- `tools/ai-registry/component-docs.json`;
- `examples/ai-recipes/**`;
- manifests, context packs e capability files.

Hipotese a validar na fase 1: `praxis-component-registry-rag.json` pode estar defasado em relacao aos artefatos principais e precisa ser classificado como compat, deprecated ou evoluido para projecao compacta do novo corpus.

## Fases

1. [x] [Inventario e saneamento](./phases/phase-01-inventory.md) (Concluído)
2. [x] [Contrato canonico de corpus chunk](./phases/phase-02-corpus-contract.md)
3. [x] [Geracao AI-ready por componente](./phases/phase-03-component-corpus-generation.md)
4. [x] [Publicacao multi-chunk no vector store](./phases/phase-04-backend-publication.md)
5. [x] [Retrieval granular read-only](./phases/phase-05-readonly-retrieval.md)
6. [x] [Integracao no authoring turn](./phases/phase-06-authoring-integration.md)
7. [x] [Operacao de release, reindex e observabilidade](./phases/phase-07-release-operations.md)
8. [x] [Migracao de contratos antigos e remocao de duplicidades](./phases/phase-08-legacy-migration.md)
9. [x] [Projecao opcional OpenAI/Gemini](./phases/phase-09-provider-projection.md)

## Mapa minimo de impacto

Subprojeto canonico afetado:

- `praxis-config-starter`
- `praxis-ui-angular`

Consumidores impactados:

- `@praxisui/ai`
- `@praxisui/page-builder`
- `@praxisui/core`
- fluxos `ai/patch` e `ai/authoring`
- `praxis-api-quickstart`

Docs publicas potencialmente afetadas:

- `praxis-ui-landing-page`
- `docs/ai/**`
- docs de registry/authoring em `praxis-ui-angular`

Exemplos, playgrounds e recipes potencialmente afetados:

- `praxis-ui-angular/examples/ai-recipes/**`
- manifests e context packs de componentes
- `praxisui-http-examples`

Validacoes minimas por escopo:

- `git diff --check`
- specs focais de tooling AI registry quando scripts mudarem
- testes focais Java em `praxis-config-starter` quando RAG/retrieval mudar
- validacao focal de registry/AI quando corpus mudar
- smoke HTTP apenas quando houver prova operacional necessaria

Risco de breaking change:

- Alto em fases 2, 4, 5, 6 e 8.
- Medio em fases 3 e 7.
- Baixo em fases 1 e 9 se mantidas como documentacao/projecao opcional.

## Criterio de sucesso do programa

O programa esta concluido quando:

- cada componente publico possui corpus AI-ready versionado e rastreavel;
- o corpus e publicado no indice interno por release;
- o authoring backend consegue recuperar slices relevantes sem depender de parsing frontend;
- a IA recebe evidencia com `sourceRef`, `releaseId`, `chunkKind` e visibilidade;
- `validate-plan`, `compile-patch`, preview e apply continuam sendo as fronteiras de governanca;
- artefatos antigos foram migrados, deprecados ou removidos com justificativa;
- OpenAI/Gemini, se usados, sao apenas projecoes derivadas.
