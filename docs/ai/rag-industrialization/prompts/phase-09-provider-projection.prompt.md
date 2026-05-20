# Prompt para Chat Delegado: Fase 09

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-09-provider-projection.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 09: Projecao Opcional OpenAI/Gemini.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Avaliar e, se fizer sentido, implementar projecao derivada do corpus canonico para OpenAI ou Google.
- Provider externo deve ser acelerador/read model, nunca fonte de verdade.

Atencao:

- Informacoes de OpenAI/Gemini podem mudar. Use apenas documentacao oficial e atualizada antes de implementar.
- Nao registrar secrets em docs ou codigo.
- A projecao deve ser apagavel/recriavel a partir do corpus interno.

Entregue:

- decisao: usar ou nao provider externo no primeiro corte;
- desenho de sincronizacao por release;
- export/projection se aprovado;
- plano de invalidacao/reindex;
- validacao executada.

Validacao minima:

- docs-only: `git diff --check`;
- codigo: testes focais/mocks;
- chamadas reais apenas com credenciais e decisao operacional explicita.

