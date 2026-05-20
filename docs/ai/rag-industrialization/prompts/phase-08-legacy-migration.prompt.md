# Prompt para Chat Delegado: Fase 08

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-08-legacy-migration.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 08: Migracao de Contratos Antigos e Remocao de Duplicidades.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Classificar, migrar, deprecar ou remover superficies antigas depois que o corpus/retrieval granular estiver provado.

Areas de atencao:

- `/ai/patch`
- `/ai/patch/stream`
- `componentEditPlan`
- contrato especifico da tabela
- `targetKind`
- `praxis-component-registry-rag.json`
- docs normativas antigas

Guardrails:

- Nao remover contrato ainda consumido.
- Em beta, preferir migracao limpa quando substituto estiver provado.
- Diferenciar compat temporaria de fonte canonica.

Entregue:

- matriz manter/migrar/deprecar/remover;
- patches de migracao quando seguro;
- docs sincronizadas;
- testes focais;
- handoff para Fase 09.

Validacao minima:

- builds/testes focais das libs afetadas;
- testes Java se endpoints mudarem;
- validacao registry/corpus se artefatos mudarem;
- `git diff --check`.

