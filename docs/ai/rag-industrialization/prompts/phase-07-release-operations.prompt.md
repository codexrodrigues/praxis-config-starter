# Prompt para Chat Delegado: Fase 07

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `praxis-config-starter/AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-07-release-operations.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 07: Operacao de Release, Reindex e Observabilidade.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Tornar o corpus operacional: release ativo, status de publicacao, reindex, backfill, reconciliation e contadores.

Guardrails:

- Nao usar GitHub Actions como ferramenta exploratoria.
- Nao acoplar publicacao npm/Maven ao reindex interno sem consumidor nomeado.
- Falha de indexacao nao deve ficar invisivel se afetar authoring.

Entregue:

- manifesto/status de corpus por release;
- comando/job de backfill ou reindex;
- contadores/diagnostics;
- testes focais;
- handoff para Fase 08.

Validacao minima:

- testes focais de job/status;
- smoke local apenas se houver endpoint operacional;
- `git diff --check`.

