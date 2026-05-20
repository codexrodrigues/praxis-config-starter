# Prompt para Chat Delegado: Fase 01

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-01-inventory.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 01: Inventario e Saneamento.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`, com execucao preferencialmente `docs-apenas`.

Objetivo:

- Inventariar todos os artefatos existentes de RAG, registry, AI-ready docs, authoring manifests, capabilities, context packs e recipes.
- Identificar o que ja existe, o que esta duplicado, o que esta defasado e o que deve ser evoluido em vez de recriado.

Arquivos/areas a inspecionar:

- `praxis-ui-angular/tools/ai-registry/**`
- `praxis-ui-angular/dist/*registry*`
- `praxis-ui-angular/dist/ai-ready/**`
- `praxis-ui-angular/examples/ai-recipes/**`
- `praxis-config-starter/src/main/java/**`
- `praxis-config-starter/src/main/resources/db/migration/**`
- `docs/**`

Nao implemente runtime nesta fase. Edicoes permitidas:

- atualizar `docs/ai/rag-industrialization/phases/phase-01-inventory.md`;
- se necessario, atualizar docs de orquestracao com achados comprovados.

Entregue:

- matriz de artefatos;
- classificacao: canonico, derivado, compat, obsoleto, historico;
- decisao recomendada sobre `praxis-component-registry-rag.json`;
- gaps reais;
- validacao executada;
- handoff para Fase 02.

Use `rg`/`find` para varredura. Nao use GitHub Actions. Execute `git diff --check` se editar arquivos.

