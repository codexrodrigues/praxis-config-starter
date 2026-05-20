# Checklist de Revisao

Use este checklist ao revisar qualquer fase do programa.

## Arquitetura

- [ ] A mudanca preserva Praxis como plataforma de decisoes semanticas authoradas por IA.
- [ ] A fonte canonica foi identificada antes de editar.
- [ ] Nao ha novo RAG paralelo.
- [ ] Nao ha nova tabela de embedding fora do `vector_store` compartilhado sem justificativa explicita.
- [ ] RAG e tratado como evidencia/grounding, nao como contrato executavel.
- [ ] UI, forms, tables, manifests e recipes continuam como superficies derivadas.

## Contratos

- [ ] Mudancas em contrato publico possuem mapa de impacto.
- [ ] Campos novos sao versionados ou compatibilidade beta-clean foi justificada.
- [ ] `targetKind`, `componentEditPlan`, `/ai/patch` e outros contratos existentes foram migrados com cautela.
- [ ] `validate-plan`, `compile-patch`, preview e apply continuam como gates de governanca.

## Tooling e corpus

- [ ] `generate-registry-ingestion.ts` foi considerado antes de criar gerador novo.
- [ ] `generate-registry-rag.ts` foi evoluido, substituido formalmente ou classificado.
- [ ] `generate-ai-ready-docs.ts` nao duplicou corpus granular sem necessidade.
- [ ] Manifests, capabilities, context packs e recipes foram referenciados de forma rastreavel.
- [ ] Drift entre artefatos gerados foi detectado ou prevenido.

## Backend/RAG

- [ ] `RagVectorStoreService`, `RagMetadataKeys`, `RagResourceTypes` e `RegistryIngestionService` foram reaproveitados.
- [ ] Publicacao remove ou reconcilia documentos antigos por escopo/release/source.
- [ ] `tenantId`, `environment`, `releaseId`, visibilidade e source refs foram preservados.
- [ ] Retrieval granular e read-only.

## Authoring

- [ ] `AgenticAuthoringTurnEngine` e a fronteira backend concentram retrieval semantico.
- [ ] `@praxisui/ai` continua cliente/UX.
- [ ] `AgenticAuthoringToolRegistry` expõe apenas ferramentas governadas.
- [ ] Evidencias recuperadas chegam ao LLM com limite, source refs e diagnosticos.

## Validacao

- [ ] `git diff --check` foi executado quando houve edicao.
- [ ] Testes focais foram escolhidos conforme o escopo.
- [ ] GitHub Actions nao foram usadas como ferramenta exploratoria.
- [ ] O que nao foi validado esta declarado.

## Handoff

- [ ] O arquivo da fase foi atualizado.
- [ ] O chat delegado registrou decisoes e pendencias.
- [ ] A proxima fase tem entrada clara.

