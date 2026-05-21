# Resumo Executivo: RAG AI-ready por Componente

Status: programa de 9 fases concluido.

Data de fechamento: 2026-05-20.

## Resultado

O RAG da plataforma Praxis foi industrializado sem criar um indice paralelo nem deslocar a fonte canonica de semantica da plataforma.

A fonte de verdade operacional para corpus de componentes agora e:

- `praxis-ui-angular/dist/praxis-component-registry-ingestion.json`;
- caminho canonico: `components[].chunks[]`;
- publicacao interna: `praxis-config-starter` via `RegistryIngestionService` e `RagVectorStoreService`;
- indice interno: `vector_store`;
- consumo no authoring: `ContextRetrievalService`, `AgenticAuthoringToolRegistry` e `AgenticAuthoringTurnEngine`.

## Decisoes canonicas

- Praxis continua sendo uma plataforma de decisoes semanticas authoradas por IA.
- RAG e grounding/evidencia para authoring, nao fonte primaria de regra de negocio.
- `praxis-component-registry-rag.json` deixa de ser corpus canonico e passa a ser projecao compacta de compatibilidade.
- Providers externos, como OpenAI e Google/Gemini, sao apenas read models derivados e apagaveis.
- `/ai/patch`, `/ai/patch/stream`, `componentEditPlan`, contrato especifico de tabela e `targetKind` continuam vivos enquanto houver consumidores reais.

## Entregas por camada

Tooling/corpus:

- corpus AI-ready granular por componente;
- chunks com `sourceKind`, `sourceId`, `chunkKind`, `sourcePointer`, `contentHash`, `corpusVersion`, `releaseId` e visibilidade;
- validacao de governanca para chunks, source pointers e drift de recipes;
- projecao compacta deprecated para planner legado;
- export opcional para OpenAI/Gemini sem upload real.

Backend/RAG:

- publicacao multi-chunk no `vector_store`;
- identidade deterministica por tenant, ambiente, source, release, kind, chunk, hash e indice;
- purga por escopo antes de upsert;
- retrieval granular read-only por componente, manifest, examples, schema fields e config path docs;
- status operacional de release com reconciliation.

Authoring:

- evidencia granular injetada antes do `preview.plan`;
- `contextHints.authoringEvidence` com limite, `sourceRef`, release, chunk kind, hash, score e versao;
- diagnostics seguros com contadores e source refs.

Operacao:

- `RegistryIngestionService.reindexRegistry(...)` como comando/job interno de backfill/reindex;
- `RagVectorStoreService.corpusReleaseStatus(...)` como readiness/reconciliation;
- provider projection versionada em `dist/provider-projections/<releaseId>/**`.

## Fronteiras preservadas

- `praxis-config-starter` permanece dono do RAG interno, authoring governado, config store e contratos AI backend.
- `praxis-ui-angular` permanece dono do runtime oficial e tooling de registry das bibliotecas `@praxisui/*`.
- `praxis-api-quickstart` continua sendo prova operacional, nao fonte canonica.
- Docs, recipes, manifests e provider projections sao derivados ou evidencias, nao fontes primarias isoladas.

## Proximas decisoes

- Rodar smoke real de authoring em cenarios tabela/form/page-builder.
- Criar benchmark de qualidade de retrieval/authoring.
- Migrar `generate-templates-plan.ts` para consumir diretamente o registry de ingestao.
- Definir se provider externo sera publicado agora ou mantido como export preparado.

