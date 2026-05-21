# Prompt para Chat Delegado: Fase 04

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `praxis-config-starter/AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-04-backend-publication.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 04: Publicacao Multi-chunk no Vector Store.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Fazer `RegistryIngestionService` publicar multiplos chunks por componente no `vector_store`.
- Corrigir risco de stale docs por republicacao no mesmo release/scope/source.
- Reaproveitar `RagVectorStoreService`.

Guardrails:

- Nao criar tabela paralela de embedding.
- Nao duplicar payload canonico em `api_metadata` ou `ai_registry`.
- RAG e indice derivado, nao fonte primaria.

Entregue:

- publicacao multi-chunk;
- delete/reconcile por release/scope/source;
- testes focais de ingestion/RAG;
- handoff para Fase 05.

Validacao minima:

- testes focais Java;
- `git diff --check`;
- sem smoke remoto.

