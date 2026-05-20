# RAG Industrialization Program

Documento vivo do monorepo. Criado em 2026-05-19. Classificacao da mudanca: `arquitetural` + `contrato-publico` + `transversal`.

## Objetivo

Industrializar o RAG existente da plataforma Praxis para publicar corpus AI-ready por componente, versionado por release, consultavel pelo fluxo agentic de authoring e projetavel opcionalmente para provedores externos como OpenAI ou Google.

Este programa nao cria uma segunda fonte de verdade. Ele evolui os pipelines e servicos existentes:

- `praxis-ui-angular/tools/ai-registry/**`
- `praxis-config-starter` RAG/vector store
- `ai_registry`
- `AgenticAuthoringTurnEngine`
- manifests, capabilities, context packs e recipes oficiais

## Artefatos

- [Plano mestre](./PLAN.md)
- [Resumo executivo](./EXECUTIVE-SUMMARY.md)
- [Release readiness](./RELEASE-READINESS.md)
- [Plano de avaliacao de qualidade](./QUALITY-EVALUATION-PLAN.md)
- [Relatorio de validacao integrada 2026-05-20](./VALIDATION-REPORT-2026-05-20.md)
- [Agrupamento recomendado de commit/PR](./CHANGESET-GROUPING.md)
- [Pacote de PR](./PR-PACKAGE.md)
- [Runbook do orquestrador](./ORCHESTRATOR.md)
- [Checklist de revisao](./REVIEW-CHECKLIST.md)
- [Template de handoff](./HANDOFF-TEMPLATE.md)
- [Fases executaveis](./phases/)
- [Prompts para chats delegados](./prompts/)

## Regra operacional

Cada fase deve ser executada em um chat separado. Este chat principal permanece como orquestrador e revisor.

Ao concluir uma fase, o chat delegado deve atualizar o arquivo da fase com:

- estado final;
- arquivos alterados;
- decisoes tomadas;
- validacao executada;
- pendencias;
- handoff para a proxima fase.

O plano nao deve viver na memoria do chat. A continuidade deve viver nestes arquivos versionados.
