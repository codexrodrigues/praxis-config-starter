# Prompt para Chat Delegado: Fase 03

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `praxis-ui-angular/AGENTS.md`
- `praxis-ui-angular/tools/ai-registry/AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-03-component-corpus-generation.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 03: Geracao AI-ready por Componente.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Evoluir os generators existentes para produzir corpus granular por componente.
- Nao criar terceiro pipeline.
- Usar registry, manifests, capabilities, context packs, recipes e docs como entradas rastreaveis.

Areas provaveis:

- `praxis-ui-angular/tools/ai-registry/generate-registry-ingestion.ts`
- `praxis-ui-angular/tools/ai-registry/generate-registry-rag.ts`
- `praxis-ui-angular/tools/ai-registry/generate-ai-ready-docs.ts`
- `praxis-ui-angular/tools/ai-registry/extract-component-docs.js`
- `praxis-ui-angular/tools/ai-registry/run-all.sh`
- `praxis-ui-angular/examples/ai-recipes/**`

Antes de editar, verifique se `node_modules` pertence a macOS/Windows/Linux conforme regras do repo.

Entregue:

- corpus granular gerado por componente;
- indice por componente e `chunkKind`;
- drift detection;
- testes focais do tooling;
- handoff para Fase 04.

Validacao minima:

- specs focais de `tools/ai-registry`;
- comando focal de geracao/validacao AI registry;
- `git diff --check`.

