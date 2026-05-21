# Release Readiness: Corpus RAG AI-ready

Status: checklist operacional pos-Fase 09.

Use este documento para fechar um corte de release do corpus AI-ready antes de PR, merge, publicacao interna ou smoke com quickstart.

## Principio

O release esta pronto quando o corpus canonico pode ser regenerado, validado, publicado no indice interno, reconciliado e consumido pelo authoring sem depender de memoria de chat ou de artefato externo como fonte primaria.

## Ordem recomendada

1. Gerar e validar o corpus canonico no `praxis-ui-angular`.
2. Gerar a projecao compacta deprecated apenas para consumidores legados.
3. Gerar a provider projection opcional, sem upload real.
4. Rodar testes focais do backend RAG/authoring.
5. Conferir hygiene de diff.
6. Registrar qualquer validacao nao executada.

## Comandos locais

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

## Artefatos esperados

Corpus canonico:

- `praxis-ui-angular/dist/praxis-component-registry-ingestion.json`;
- `components[].chunks[]` presente;
- chunks com `sourceKind`, `sourceId`, `chunkKind`, `contentHash`, `sourcePointer` e `corpusVersion`.

Compatibilidade:

- `praxis-ui-angular/dist/praxis-component-registry-rag.json`;
- `artifactRole=compatibility_projection`;
- `deprecation.status=deprecated_as_canonical_corpus`;
- `canonicalCorpus.sourceRegistry=dist/praxis-component-registry-ingestion.json`.

Provider projection opcional:

- `praxis-ui-angular/dist/provider-projections/<releaseId>/provider-projection-manifest.json`;
- `openai/files/*.md`;
- `openai/upload-plan.jsonl`;
- `gemini/search-documents.jsonl`;
- `security.containsSecrets=false`;
- `security.externalProviderIdsPersisted=false`;
- `invalidation.strategy=replace_release_projection`.

Backend:

- `RegistryIngestionService.reindexRegistry(...)` retorna `RegistryReindexResult`;
- `RagVectorStoreService.corpusReleaseStatus(...)` retorna `available=true` e `reconciled=true` para release publicada;
- warnings vazios para release pronta.

## Smoke funcional recomendado

Cenarios minimos:

- tabela: prompt de toolbar/button/action usando `praxis-table`;
- formulario: prompt de campo/config com `praxis-dynamic-form` ou dynamic fields;
- page-builder: prompt de composicao com componente selecionado.

Evidencias esperadas no resultado:

- `contextHints.authoringEvidence` chega ao `preview.plan`;
- diagnostics incluem `authoringEvidenceCount > 0`;
- `authoringEvidenceSourceRefs` contem arquivos repo-relativos;
- nenhum patch livre passa sem `validate-plan`, `compile-patch`, preview/apply governado.

## Criterios de bloqueio

Bloquear o corte se:

- o registry de ingestao nao tiver `components[].chunks[]`;
- `sourcePointer` vazar caminho absoluto local ou URI local de arquivo;
- `praxis-component-registry-rag.json` parecer corpus canonico;
- provider projection contiver segredo, API key ou ID externo persistido;
- reconciliation do backend retornar `corpus-chunk-count-mismatch`;
- retrieval semantico normalizar query natural como token tecnico;
- authoring pular `validate-plan`, `compile-patch`, preview ou apply.

## GitHub Actions

Nao usar Actions como ferramenta exploratoria.

Actions so devem entrar como gate de fase, PR, release ou smoke publicado quando a validacao local ja estiver descrita.

## Gate downstream com quickstart

Antes de declarar release operacional completa, rodar um smoke real com `praxis-api-quickstart` quando houver ambiente disponivel:

- Postgres/config datasource real configurado por `SPRING_DATASOURCE_URL` e `CONFIG_DATASOURCE_URL`;
- provider LLM real configurado por secrets locais ou de CI;
- origin oficial permitido, preferencialmente `http://localhost:4003`;
- starter local instalado no Maven local quando a versao ainda nao estiver publicada.

Sem esses pre-requisitos, use os testes isolados do quickstart como validacao downstream parcial e registre o bloqueio do smoke HTTP real.
