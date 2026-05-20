# Prompt para Chat Delegado: Fase 02

Voce esta trabalhando no monorepo Praxis em `/Users/rodrigo/Dev/pessoal/praxis-plataform`.

Leia primeiro:

- `AGENTS.md`
- `docs/ai/rag-industrialization/PLAN.md`
- `docs/ai/rag-industrialization/phases/phase-01-inventory.md`
- `docs/ai/rag-industrialization/phases/phase-02-corpus-contract.md`
- `docs/ai/rag-industrialization/REVIEW-CHECKLIST.md`

Sua tarefa e executar a Fase 02: Contrato Canonico de Corpus Chunk.

Classificacao: `arquitetural` + `contrato-publico` + `transversal`.

Objetivo:

- Definir o contrato canonico para chunks AI-ready publicados no indice interno.
- Reaproveitar `vector_store`, `RagMetadataKeys`, `RagResourceTypes`, `RagDocumentIdentity` e contratos existentes.
- Evitar nova tabela de embedding ou RAG paralelo.

Antes de editar:

- mapear os metadados RAG existentes;
- confirmar onde o contrato deve viver;
- produzir mapa de impacto.

Entregue:

- contrato documentado;
- lista inicial de `chunkKind`;
- regra de versionamento;
- compatibilidade com documentos existentes;
- testes focais se houver codigo;
- handoff para Fase 03.

Validacao minima:

- `git diff --check`;
- testes focais Java apenas se codigo mudar.

