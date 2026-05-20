# Relatorio de Validacao Integrada

Data: 2026-05-20.

Escopo: fechamento pos-Fase 09 do programa de industrializacao do RAG AI-ready por componente.

## Resultado geral

Status: aprovado localmente.

O fluxo integrado regenerou o corpus canonico, validou governanca, validou contratos de authoring, gerou projecoes derivadas e passou nos testes focais do backend RAG/authoring.

## Comandos executados

No `praxis-ui-angular`:

```bash
npm run validate:ai
node tools/ai-registry/generate-provider-projection.spec.js
npx ts-node --project tools/tsconfig.tools.json tools/ai-registry/generate-provider-projection.ts
git diff --check
```

No `praxis-config-starter`:

```bash
mvn -B -Dtest=RagVectorStoreServiceTest,RegistryIngestionServiceIdentityTest,ContextRetrievalServiceTest,AgenticAuthoringToolRegistryTest,AgenticAuthoringTurnEngineTest test
git diff --check
```

No monorepo:

- varredura de whitespace, conflitos de merge e URIs locais de arquivo nos artefatos do programa.

Downstream `praxis-api-quickstart`:

```bash
mvn -B -DskipTests install
mvn -B -DskipTests package
mvn -B -Dtest=AgenticAuthoringStreamIsolatedIntegrationTest,DomainAuthoringContextHintsContractTest,AiPatchSchemaResolutionIsolatedIntegrationTest,SecurityConfigAiPatchPolicyTest test
```

## Evidencias

`npm run validate:ai`:

- `praxis-component-registry-ingestion.json` gerado com 102 componentes;
- catalog governance: `PASS`, `errors=0`, `warnings=0`;
- authoring contracts acceptance: `PASS`, `completed=20/20`, `failed=0`;
- `praxis-component-registry-rag.json` regenerado como projecao de compatibilidade;
- `generate-templates-plan.ts` executado;
- readiness matrix externa nao encontrada fora de CI, portanto o gate foi pulado com warning controlado.

Provider projection:

- spec focal: `PASS`;
- projection gerada em `dist/provider-projections/1-0-0`;
- documentos exportados: 468;
- providers: `openai`, `gemini`;
- `security.containsSecrets=false`;
- `security.externalProviderIdsPersisted=false`;
- `invalidation.strategy=replace_release_projection`.

Backend:

- suites focais executadas: `RagVectorStoreServiceTest`, `RegistryIngestionServiceIdentityTest`, `ContextRetrievalServiceTest`, `AgenticAuthoringToolRegistryTest`, `AgenticAuthoringTurnEngineTest`;
- resultado: 81 testes, 0 falhas, 0 erros, 0 skipped.

Downstream quickstart:

- `praxis-config-starter` instalado no Maven local como `0.1.0-rc.39`;
- `praxis-api-quickstart` empacotado contra `praxis.config.version=0.1.0-rc.39`;
- testes focais executados: `AgenticAuthoringStreamIsolatedIntegrationTest`, `DomainAuthoringContextHintsContractTest`, `AiPatchSchemaResolutionIsolatedIntegrationTest`, `SecurityConfigAiPatchPolicyTest`;
- resultado: 5 testes, 0 falhas, 0 erros, 0 skipped.
- tentativa de smoke HTTP manual com jar foi bloqueada por ambiente: nao havia `SPRING_DATASOURCE_URL`/`CONFIG_DATASOURCE_URL` nem Postgres local; tentativa H2 nao e compativel com o jar runtime porque `org.h2.Driver` nao e empacotado.

Resumo de artefatos:

- ingestion components: 102;
- canonical chunks: 468;
- compact RAG role: `compatibility_projection`;
- compact RAG deprecation: `deprecated_as_canonical_corpus`;
- provider documents: 468.

## Nao validado

- Nao houve upload real para OpenAI, Gemini ou Vertex AI.
- Nao houve smoke HTTP real com `praxis-api-quickstart` rodando como jar contra Postgres/config datasource real.
- Nao houve E2E browser de authoring com LLM real.

Esses itens ficam como gates operacionais futuros, nao como bloqueadores deste fechamento local.
