# Prompt para Chat Delegado: Fase 05

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `praxis-config-starter/AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-05-readonly-retrieval.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 05: Retrieval Granular Read-only.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Expor ferramentas/servicos read-only para buscar corpus granular durante authoring.
- Delegar para servicos existentes como `ContextRetrievalService`, `AgenticAuthoringManifestService`, `SchemaRetrievalService` e project knowledge governado.

Ferramentas candidatas:

- `searchComponentCorpus`
- `getComponentAuthoringContext`
- `getManifestSlice`
- `searchConfigPathDocs`
- `searchExamples`
- `searchSchemaFields`

Guardrails:

- Tools nao aplicam patch.
- Tools retornam evidencia, source refs e release.
- `@praxisui/ai` nao implementa retrieval semantico.

Entregue:

- tools read-only registradas;
- testes de ranking/fallback/visibilidade;
- handoff para Fase 06.

Validacao minima:

- testes focais Java;
- `git diff --check`.

