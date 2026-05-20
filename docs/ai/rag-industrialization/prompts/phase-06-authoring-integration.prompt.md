# Prompt para Chat Delegado: Fase 06

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `praxis-config-starter/AGENTS.md`
- `praxis-ui-angular/projects/praxis-ai/AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-06-authoring-integration.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 06: Integracao no Authoring Turn.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Integrar retrieval granular ao `AgenticAuthoringTurnEngine`.
- Entregar slices/evidencias ao LLM antes do planejamento.
- Preservar gates de governanca: `validate-plan`, `compile-patch`, preview e apply.

Guardrails:

- Retrieval nao substitui capability checks.
- Evidencia deve ser limitada e diagnosticavel.
- Frontend nao vira fonte semantica.

Entregue:

- injecao de corpus slices no turno;
- diagnostics com source refs;
- testes de authoring turn;
- handoff para Fase 07.

Validacao minima:

- testes focais Java;
- specs frontend apenas se contrato cliente mudar;
- `git diff --check`.

