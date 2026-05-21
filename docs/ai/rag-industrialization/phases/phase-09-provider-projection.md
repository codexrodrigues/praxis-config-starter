# Fase 09: Projecao Opcional OpenAI/Gemini

Status: concluido.

## Objetivo

Projetar o corpus canonico para provedores externos somente como read model derivado, acelerando retrieval sem entregar a fonte de verdade ao provedor.

## Escopo

- OpenAI Vector Stores/File Search, se escolhido.
- Google Vertex AI Search/Gemini File Search, se escolhido.
- Export versionado por release.
- Sincronizacao e invalidacao.
- Docs de operacao e seguranca.

## Guardrails

- Provider externo nao e fonte canonica.
- Projecao deve ser apagavel/recriavel a partir do corpus interno.
- Secrets e IDs externos nao devem entrar em docs publicas.
- Nao bloquear authoring interno se provider externo estiver indisponivel, salvo decisao explicita.

## Entregas

- Decisao: usar ou nao usar provider externo no primeiro corte.
- Export/projection opcional.
- Mapeamento de release interno para indice externo.
- Plano de invalidacao/reindex.

## Validacao minima

- Docs-only: `git diff --check`.
- Codigo: testes focais/mocks; chamada real somente com credenciais e aprovacao operacional.

## Criterio de pronto

OpenAI/Gemini podem acelerar consulta, mas a plataforma continua capaz de reconstruir e governar o corpus pelo indice interno.

## Classificacao e mapa de impacto

Classificacao executada: `arquitetural` + `contrato-publico` + `transversal`.

Subprojetos canonicos afetados:

- `praxis-ui-angular`: geracao do export derivado a partir do registry de ingestao canonico.
- `praxis-config-starter`: permanece dono do indice interno, retrieval e governanca; nao foi alterado nesta fase.

Consumidores impactados:

- tooling `tools/ai-registry`;
- futura rotina operacional de publicacao externa;
- authoring/retrieval backend apenas como consumidor opcional de read model externo, sem dependencia obrigatoria.

Docs e artefatos derivados considerados:

- `docs/ai/rag-industrialization/**`;
- `praxis-ui-angular/tools/ai-registry/README.md`;
- artefatos gerados em `dist/provider-projections/<releaseId>/**`.

Risco de breaking change: baixo no primeiro corte, porque nao ha chamada real a provider, nao ha segredo, nao ha ID externo persistido e o authoring interno continua operando pelo indice interno.

## Documentacao oficial consultada

- OpenAI File Search usa vector stores e arquivos previamente carregados; permite filtros por metadata/attributes em consultas de file search. Referencias: <https://developers.openai.com/api/docs/guides/tools-file-search> e <https://developers.openai.com/api/docs/guides/retrieval>.
- OpenAI vector stores automaticamente fazem chunking, embeddings e indexacao quando arquivos sao adicionados; remocao de arquivos pode ser eventualmente consistente. Referencia: <https://developers.openai.com/api/docs/guides/retrieval>.
- Google oferece Gemini API File Search para RAG com stores de arquivos e metadata, e Grounding with your search API para usar um endpoint externo que retorna `snippet` e `uri`. Vertex AI Search/Grounding segue documentado para data stores, mas a propria pagina indica que Vertex AI services estao migrando para Gemini Enterprise Agent Platform; por isso o primeiro corte gera apenas artefato neutro e apagavel. Referencias: <https://ai.google.dev/gemini-api/docs/file-search>, <https://docs.cloud.google.com/vertex-ai/generative-ai/docs/grounding/grounding-with-your-search-api> e <https://docs.cloud.google.com/vertex-ai/generative-ai/docs/grounding/grounding-with-vertex-ai-search>.

## Decisao do primeiro corte

Usar provider externo apenas como export opcional, nao como integracao runtime obrigatoria.

Motivos:

- o corpus interno ja e suficiente para authoring governado;
- OpenAI/Google adicionam custo, politica de retencao, limites operacionais e eventual consistency;
- a plataforma precisa preservar auditoria, releaseId, sourceRef e invalidacao propria;
- chamadas reais exigem credenciais e decisao operacional explicita.

## Desenho de sincronizacao por release

Fonte canonica:

- `dist/praxis-component-registry-ingestion.json`;
- caminho canonico: `components[].chunks[]`;
- chaves preservadas: `releaseId`, `sourceKind`, `sourceId`, `componentId`, `chunkKind`, `chunkIndex`, `corpusVersion`, `contentHash`, `sourcePointer`.

Export derivado:

- `provider-projection-manifest.json`: manifest de release, origem canonica, invalidacao e arquivos gerados;
- `openai/files/*.md`: um arquivo Markdown por chunk, para permitir atributos por arquivo no upload;
- `openai/upload-plan.jsonl`: plano local de upload com `attributes`, sem executar chamada real;
- `gemini/search-documents.jsonl`: documentos `{ uri, snippet, metadata }` para uma Search API wrapper para Gemini ou pipeline posterior para Gemini File Search/Vertex AI Search; nao e upload real para Google.

Nenhum artefato gerado contem secrets, API keys, vector store IDs, data store IDs ou IDs de provider externo.

## Invalidacao e reindex

Estrategia: `replace_release_projection`.

Para cada novo release:

1. Gerar `praxis-component-registry-ingestion.json`.
2. Gerar `dist/provider-projections/<releaseId>/**`.
3. Criar novo indice externo ou substituir completamente a projecao do release.
4. Atualizar mapeamento operacional fora do repositorio com os IDs externos, se a organizacao decidir usar provider externo.
5. Remover o indice externo do release antigo depois de smoke e janela de rollback.

Se `contentHash`, `chunkKind`, `sourceId` ou `releaseId` mudarem, o chunk deve ser tratado como novo documento externo. A exclusao do provider deve ser segura porque a fonte de verdade permanece no corpus interno.

## Patches executados

- `praxis-ui-angular/tools/ai-registry/generate-provider-projection.ts`: novo export derivado por release para OpenAI/Gemini, a partir de `components[].chunks`.
- `praxis-ui-angular/tools/ai-registry/generate-provider-projection.spec.js`: self-test cobrindo export OpenAI/Gemini e rejeicao de registry legado sem chunks.
- `praxis-ui-angular/tools/ai-registry/README.md`: comandos e saidas do export opcional.

## Validacao

- `node tools/ai-registry/generate-provider-projection.spec.js`
- `git -C praxis-ui-angular diff --check`
- `git -C praxis-config-starter diff --check`

Nao houve chamada real a OpenAI, Google ou Vertex AI porque a fase nao recebeu credenciais nem aprovacao operacional para publicar indices externos.

## Handoff operacional

- Antes de publicar em OpenAI, decidir se a organizacao quer usar File Search/Vector Stores como acelerador pago e configurar expiracao/retencao conforme politica interna.
- Antes de publicar em Google, escolher entre Gemini API File Search, Vertex AI/Gemini Enterprise data store, ou Gemini Grounding with your search API apontando para uma API interna de retrieval.
- Persistir IDs externos em cofre/config operacional, nao em docs publicas nem no repo.
- Manter fallback interno obrigatorio: se provider externo falhar, `ContextRetrievalService` e `vector_store` interno continuam sendo a trilha canonica.
