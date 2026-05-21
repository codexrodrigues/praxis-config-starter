# Pacote de PR: RAG AI-ready por Componente

Status: pronto para preparar PRs separados por repositorio.

Data: 2026-05-20.

## Branches locais criadas

- `praxis-ui-angular`: `feat/rag-ai-ready-corpus`
- `praxis-config-starter`: `feat/rag-ai-ready-authoring`

`praxis-api-quickstart` ficou sem mudancas locais.

## PR 1: `praxis-ui-angular`

Titulo sugerido:

```text
Industrialize AI-ready component corpus and provider projections
```

Resumo:

- Gera corpus canonico de componentes em `praxis-component-registry-ingestion.json` com `components[].chunks[]`.
- Adiciona chunk metadata: `sourceKind`, `sourceId`, `chunkKind`, `sourcePointer`, `contentHash`, `corpusVersion`.
- Evolui validacao de governanca para chunks, source pointers, hashes e drift de recipes.
- Reclassifica `praxis-component-registry-rag.json` como `compatibility_projection` deprecated.
- Adiciona `generate-provider-projection.ts` para export opcional OpenAI/Gemini sem upload real.
- Mantem provider projection como read model apagavel, sem secrets nem IDs externos.

Arquivos principais:

- `tools/ai-registry/generate-registry-ingestion.ts`
- `tools/ai-registry/schemas/praxis-component-registry-ingestion.schema.json`
- `tools/ai-registry/validate-catalog-governance.js`
- `tools/ai-registry/generate-registry-rag.ts`
- `tools/ai-registry/generate-provider-projection.ts`
- `tools/ai-registry/README.md`

Validacao executada:

```bash
npm run validate:ai
node tools/ai-registry/generate-provider-projection.spec.js
npx ts-node --project tools/tsconfig.tools.json tools/ai-registry/generate-provider-projection.ts
git diff --check
```

Resultado:

- ingestion registry: 102 componentes;
- canonical chunks: 468;
- catalog governance: `PASS`, `errors=0`, `warnings=0`;
- authoring contracts: `PASS`, `completed=20/20`, `failed=0`;
- provider projection: 468 documentos, `containsSecrets=false`, `externalProviderIdsPersisted=false`.

Observacoes para reviewer:

- `praxis-component-registry-rag.json` nao deve voltar a ser fonte canonica.
- `generate-templates-plan.ts` ainda consome registry compacto por compatibilidade; migracao direta para ingestion registry fica como proxima divida.
- O export OpenAI/Gemini nao faz upload e nao deve persistir IDs externos.

## PR 2: `praxis-config-starter`

Titulo sugerido:

```text
Publish granular component corpus into RAG authoring flow
```

Resumo:

- Publica chunks do registry no `vector_store` compartilhado.
- Adiciona identidade deterministica por tenant/env/source/release/chunk/hash.
- Adiciona purga por escopo antes de upsert.
- Expõe retrieval granular read-only para authoring.
- Injeta `authoringEvidence` antes do `preview.plan`.
- Adiciona status operacional de release e comando interno `reindexRegistry(...)`.
- Mantem `/ai/patch`, `/ai/patch/stream`, `componentEditPlan` e contratos vivos sem remocao prematura.

Arquivos principais:

- `src/main/java/org/praxisplatform/config/service/RegistryIngestionService.java`
- `src/main/java/org/praxisplatform/config/rag/RagVectorStoreService.java`
- `src/main/java/org/praxisplatform/config/service/ContextRetrievalService.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringToolRegistry.java`
- `src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTurnEngine.java`
- `src/main/java/org/praxisplatform/config/dto/RegistryIngestionRequest.java`
- `src/main/java/org/praxisplatform/config/rag/RagMetadataKeys.java`

Validacao executada:

```bash
mvn -B -Dtest=RagVectorStoreServiceTest,RegistryIngestionServiceIdentityTest,ContextRetrievalServiceTest,AgenticAuthoringToolRegistryTest,AgenticAuthoringTurnEngineTest test
git diff --check
```

Resultado:

- 81 testes;
- 0 falhas;
- 0 erros;
- 0 skipped.

Validacao downstream parcial:

```bash
mvn -B -DskipTests install
mvn -B -DskipTests package
mvn -B -Dtest=AgenticAuthoringStreamIsolatedIntegrationTest,DomainAuthoringContextHintsContractTest,AiPatchSchemaResolutionIsolatedIntegrationTest,SecurityConfigAiPatchPolicyTest test
```

Resultado:

- `praxis-config-starter` instalado localmente como `0.1.0-rc.39`;
- `praxis-api-quickstart` empacotado contra `praxis.config.version=0.1.0-rc.39`;
- quickstart focal: 5 testes, 0 falhas.

Observacoes para reviewer:

- Smoke HTTP real com quickstart nao foi executado por falta de `SPRING_DATASOURCE_URL`/`CONFIG_DATASOURCE_URL`, Postgres local e API key OpenAI/Gemini.
- Tentativa H2 foi bloqueada porque o jar runtime do quickstart nao empacota `org.h2.Driver`.
- O fallback interno continua obrigatorio; provider externo nao participa do runtime nesta fase.

## Docs do programa

Os artefatos do programa ficam em:

- `docs/ai/rag-industrialization/EXECUTIVE-SUMMARY.md`
- `docs/ai/rag-industrialization/RELEASE-READINESS.md`
- `docs/ai/rag-industrialization/QUALITY-EVALUATION-PLAN.md`
- `docs/ai/rag-industrialization/VALIDATION-REPORT-2026-05-20.md`
- `docs/ai/rag-industrialization/CHANGESET-GROUPING.md`

Observacao: a raiz do workspace nao e um repositorio Git unico; estes docs foram versionados no `praxis-config-starter` em `docs/ai/rag-industrialization/**`, junto da fronteira canonica de RAG/authoring backend.

## Pendencias pos-PR

- Migrar `generate-templates-plan.ts` para consumir diretamente `praxis-component-registry-ingestion.json`.
- Rodar smoke HTTP real com quickstart quando houver Postgres/config datasource e provider LLM.
- Criar benchmark automatizado do `QUALITY-EVALUATION-PLAN.md`.
- Decidir se provider projection sera apenas artefato local ou se tera rotina operacional de upload em cofre/config externo.
