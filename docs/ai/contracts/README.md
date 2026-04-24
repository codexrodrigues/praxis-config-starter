# AI API Contract (Single Source)

Arquivo fonte unico atual:

- `docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml`

Escopo coberto:

- `POST /api/praxis/config/ai/patch`
- `POST /api/praxis/config/ai/patch/stream/start`
- `GET /api/praxis/config/ai/patch/stream/{streamId}`
- `GET /api/praxis/config/ai/patch/stream/{streamId}/probe`
- `POST /api/praxis/config/ai/patch/stream/{streamId}/cancel`
- `GET /api/praxis/config/ai/authoring/component-capabilities`
- `POST /api/praxis/config/ai/authoring/resource-candidates`
- `GET /api/praxis/config/ai/authoring/manifests/{componentId}`
- `GET /api/praxis/config/ai/authoring/manifests/{componentId}/editable-targets`
- `GET /api/praxis/config/ai/authoring/manifests/{componentId}/operations`
- `POST /api/praxis/config/ai/authoring/manifests/{componentId}/resolve-target`
- `POST /api/praxis/config/ai/authoring/manifests/{componentId}/validate-plan`
- `POST /api/praxis/config/ai/authoring/manifests/{componentId}/compile-patch`
- `POST /api/praxis/config/ai/authoring/turn/stream/start`
- `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}`
- `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}/probe`
- `POST /api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel`
- `POST /api/praxis/config/ai/authoring/intent-resolution`
- `POST /api/praxis/config/ai/authoring/page-preview`
- `POST /api/praxis/config/ai/authoring/page-apply`

Importante:

- `ai/authoring` continua sendo a superfÃƒÂ­cie canÃƒÂ´nica para authoring de
  componente/pÃƒÂ¡gina;
- business-rule authoring nÃƒÂ£o deve ser modelado como destino primÃƒÂ¡rio de
  `componentEditPlan`;
- a evolução canônica para decisão compartilhada deve acontecer na superfície
  `/api/praxis/config/domain-rules/**`, que já concentra intake governado,
  definição versionada, simulation e materialização por target.

Contexto conversacional de authoring:

- `intent-resolution` e `page-preview` compartilham `AgenticAuthoringConversationContext`.
- `sessionId` identifica a conversa do assistente e deve permanecer estavel entre turnos do mesmo painel.
- `clientTurnId` identifica o turno executavel atual para rastreabilidade/idempotencia no cliente.
- `conversationMessages` transporta o historico recente suportado pelo contrato (`user`, `assistant`, `system`).
- `pendingClarification` transporta a pergunta pendente e o prompt de origem; respostas curtas devem chegar como contexto de clarificacao, sem o frontend reescrever semanticamente o prompt.
- `attachmentSummaries` transporta apenas metadados seguros dos anexos do turno (`id`, `name`, `kind`, `mimeType`, `sizeBytes`, `source`, `hasPreview`), sem bytes, base64, `File` ou URLs locais `blob:`.
- Quando um turno com anexos gerar `pendingClarification`, o backend pode ecoar esses metadados em `pendingClarification.diagnostics.attachmentSummaries`; o cliente deve reenviar esse estado no turno seguinte para preservar o contexto sem reenviar o arquivo.

Resolucao de intencao e chips ricos:

- Toda pergunta de authoring deve passar por `intent-resolution`; o frontend nao deve tentar resolver intencao, recurso ou operacao com heuristicas locais antes de consultar o backend canonico.
- Quando `quickReplies[].contextHints.tool=searchApiResources`, o cliente pode chamar `resource-candidates` com `retrievalQuery` e `artifactKind` para recuperar recursos reais do catalogo antes de gerar preview.
- Prompts genericos, como "Crie um dashboard", podem retornar `valid=false` com `failureCodes=["resource-candidate-ambiguous"]`, `candidates` e `quickReplies` para que o usuario escolha o recurso de negocio antes de gerar preview.
- `quickReplies` e a superficie canonica para chips clicaveis do assistente. Alem de `id`, `kind`, `label` e `prompt`, a resposta pode incluir `description`, `icon`, `tone` e `contextHints`.
- `description`, `icon` e `tone` orientam a apresentacao visual do chip; `contextHints` preserva dados estruturados como `resourcePath`, `submitUrl`, `operation` e `schemaUrl` para o proximo turno.
- `contextHints.domainCatalog` e um subcontrato versionado (`schemaVersion=praxis.ai.context-hints.domain-catalog/v0.2`) para solicitar contexto semantico/governanca no proximo turno. Os campos canonicos sao `serviceKey`, `resourceKey`, `releaseId`, `releaseKey`, `type`, `itemTypes`, `intent`, `query`, `contextKey`, `nodeType`, `recommendedAuthoringFlow`, `recommendedRuleType` e `limit`.
- Quando `contextHints.domainCatalog.recommendedAuthoringFlow=shared_rule_authoring`, o backend deve tratar o pedido como authoring governado de regra/decisao compartilhada. Nessa situacao, `intent-resolution` pode responder com `gate.status=route_required` e `failureCodes=["shared-rule-authoring-required"]`, sinalizando que o proximo passo canonico esta em `/api/praxis/config/domain-rules/**`, e nao em `page-preview`/`componentEditPlan`.
- O primeiro passo canônico dessa trilha pode ser `POST /api/praxis/config/domain-rules/intake`, que persiste um draft governado a partir do prompt e devolve grounding suficiente para o host continuar em `simulation`.
- `POST /api/praxis/config/domain-rules/simulations` deve responder com grounding, cobertura existente, materializações previstas, aprovações requeridas e um bloco aditivo de `explainability`, para que a explicação canônica venha do backend e não de heurísticas locais no host.
- `POST /api/praxis/config/domain-rules/publications` passa a ser a fronteira canônica para promover uma definição persistida e aplicar materializações elegíveis quando `simulation.explainability.publicationReadiness=ready_to_publish`. Para `selection_eligibility`, o publish já pode derivar e aplicar uma materialização canônica de `option_source` quando o alvo previsto for `resource-option-source`, sem exigir que o host remonte localmente o payload de `LookupSelectionPolicy`. Para `validation`, `compliance` e `privacy`, o publish também pode derivar uma materialização `backend_validation` com `kind=resource_validation_policy` quando o alvo previsto for `resource-validation`, preservando condição, parâmetros e metadados de governança para o runtime consumidor. Quando a publicação ainda estiver bloqueada, o endpoint deve devolver `publicationStatus=blocked` junto do mesmo bloco de `explainability` usado para governança.
- `contextHints.domainCatalog.relationships` solicita edges explicitas do Domain Catalog para o prompt do proximo turno. Os campos canonicos sao `enabled`, `federated`, `serviceKey`, `sourceNodeKey`, `targetNodeKey`, `edgeType`, `query` e `limit`. Quando `federated=true`, o backend consulta o latest release de cada servico no tenant/environment e nao sintetiza relacoes por labels, aliases ou nomes parecidos.
- Clientes devem preservar esses campos no round-trip e enviar `contextHints` como parte da acao do proximo turno. Reduzir `quickReplies` para texto simples ou recriar opcoes localmente quebra o contrato de authoring.
- Depois que o usuario confirmar um recurso para `operationKind=create`, `artifactKind=dashboard` e `changeKind=create_artifact`, `page-preview` pode retornar `uiCompositionPlan` com `layoutPreset=resource-dashboard`. Esse caminho e canonico para dashboards orientados por recurso e nao deve cair na validacao exclusiva de formulario.
- Respostas longas de clarificacao devem preservar o contexto quando selecionam explicitamente uma opcao ou recurso do turno anterior, mesmo que contenham verbos como `criar`, `gerar` ou `montar`. Sinais como `sim`, `confirmo`, `usar`, `usando`, `mantenha`, `preserve`, `opcao`, `primeira`, `segunda`, `terceira`, `com base` ou uma rota `/api/...` indicam continuacao do `pendingClarification`, nao uma nova conversa isolada.
- Quando houver `pendingClarification`, o resolver LLM interno deve classificar semanticamente o turno como `clarification_answer`, `new_instruction`, `refinement`, `api_catalog_followup` ou `none`. Essa classificacao prevalece sobre heuristicas deterministicas: se a IA classificar como `new_instruction`, o backend deve usar o prompt bruto do usuario e nao concatenar o `sourcePrompt` anterior.

Streaming de authoring:

- O contrato `v1.1` ja cobre stream canonico para `/api/praxis/config/ai/patch/stream/**`, baseado em `AiTurnEventEnvelope`, `eventSchemaVersion=v1`, replay, heartbeat, cancelamento e event log.
- O fluxo `/api/praxis/config/ai/authoring/turn/stream/**` reutiliza `AiTurnEventEnvelope`, replay, cancelamento e event log, mas usa payloads de authoring como `thought.step`, `result`, `error` e `cancelled`.
- `llmDiagnostics` continua sendo diagnostico opt-in de turno concluido; feedback incremental deve vir pelos eventos SSE do turno.
- A decisao canonica e a semantica dos eventos de authoring estao em `docs/ai/agentic-authoring-streaming.md`.

Authoring manifests executaveis:

- Os endpoints `/api/praxis/config/ai/authoring/manifests/{componentId}/**` expÃƒÂµem o contrato executavel versionado projetado em `ai_registry`.
- `resolve-target` usa o `operation.target.resolver` declarado pelo manifest para resolver alvos de forma deterministica.
- `validate-plan` valida plano, schema de input, validators declarados, confirmacao destrutiva e existencia de alvos sem depender de keywords.
- `compile-patch` compila efeitos genericos como `merge-by-key`, `remove-by-key`, `set-value`, `merge-object` e `append` em `compiledOperations` canÃƒÂ´nicas com `proposedConfig` resultante.
- `operations` permanece como alias legado dos efeitos compilados nativos do compilador, inclusive handlers de domÃƒÂ­nio.
- `patchOperations` expÃƒÂµe o patch aplicÃƒÂ¡vel concreto: para efeitos genericos, a lista materializa operaÃƒÂ§ÃƒÂµes com paths resolvidos e aplicÃƒÂ¡veis (`replace`, `add`, `remove`, `move`); para `compile-domain-patch`, a saÃƒÂ­da continua sendo a operaÃƒÂ§ÃƒÂ£o especializada emitida pelo handler.

Trilha API Catalog Q&A:

- Perguntas sobre endpoints, schemas, actions, filtros, APIs relacionadas ou escolha de API antes da geracao de pagina devem ser classificadas como `operationKind=explore`, `artifactKind=api_catalog` e `changeKind=answer_api_catalog_question`.
- Essa trilha responde pelo `assistantMessage` e pode devolver `quickReplies` para criar dashboard, ver schema ou ver actions.
- A mesma resposta deve preencher `apiCatalogAnswer` quando houver dados estruturados, com `questionType`, `selectedApi`, `candidateApis`, `relatedApis`, `schemaFields`, `filterParameters`, `actions`, `recommendations` e `evidence`.
- Quando houver metadados locais, a resposta pode incluir evidencias do catalogo como campos de schema, parametros de filtro, operacoes relacionadas e APIs complementares para composicao/drill-down.
- Enquanto a intencao permanecer em API Catalog Q&A, o backend nao deve iniciar `page-preview`, exigir clarificacao de recurso executavel nem aplicar configuracao de pagina.
- Clientes devem apresentar API Catalog Q&A como resposta informativa, nao como `pendingClarification`, exceto quando o backend retornar explicitamente perguntas de clarificacao.
- Quando o usuario confirmar uma criacao a partir da recomendacao, o turno seguinte deve seguir o fluxo normal de authoring executavel.

Validacao automatizada no backend:

- `src/test/java/org/praxisplatform/config/contract/AiApiContractOpenApiTest.java`
- `src/test/java/org/praxisplatform/config/contract/AiContractSpecConsistencyTest.java`
- `src/test/java/org/praxisplatform/config/contract/AiContractV11RetroCompatibilityTest.java`

Comando recomendado para gate de contrato `v1.1`:

```bash
/opt/maven/bin/mvn -Dtest=AiApiContractOpenApiTest,AiContractSpecConsistencyTest,AiContractV11RetroCompatibilityTest test -q
```

Geracao de bindings (A-02) a partir da mesma fonte:

1. executar `node tools/contracts/generate-ai-contract-bindings.js` no `praxis-config-starter`;
2. arquivo Java gerado:
   - `src/main/java/org/praxisplatform/config/contract/AiContractSpec.java`
3. arquivo TS gerado (quando o monorepo tiver `praxis-ui-angular`):
   - `../praxis-ui-angular/projects/praxis-ai/src/lib/core/contracts/ai-contract.generated.ts`

Observacao:

- o teste `AiContractSpecConsistencyTest` funciona como drift guard entre OpenAPI e constantes geradas no backend.
