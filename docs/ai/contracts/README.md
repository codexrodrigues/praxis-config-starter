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
- `POST /api/praxis/config/domain-rules/intake`
- `POST /api/praxis/config/domain-knowledge/change-sets`
- `GET /api/praxis/config/domain-knowledge/change-sets`
- `GET /api/praxis/config/domain-knowledge/change-sets/{changeSetId}`
- `GET /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/timeline`
- `POST /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/validate`
- `PATCH /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/status`
- `POST /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/apply`
- `POST /api/praxis/config/domain-rules/definitions`
- `GET /api/praxis/config/domain-rules/definitions`
- `PATCH /api/praxis/config/domain-rules/definitions/{definitionId}/status`
- `GET /api/praxis/config/domain-rules/definitions/{definitionId}/timeline`
- `POST /api/praxis/config/domain-rules/simulations`
- `POST /api/praxis/config/domain-rules/publications`
- `POST /api/praxis/config/domain-rules/materializations`
- `GET /api/praxis/config/domain-rules/materializations`
- `PATCH /api/praxis/config/domain-rules/materializations/{materializationId}/status`

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
- Antes de qualquer preview ou patch, `intent-resolution` deve aplicar a matriz canonica de routing: pedidos de regra, politica, elegibilidade, validacao, compliance, privacidade ou decisao compartilhada seguem para `/api/praxis/config/domain-rules/**`; pedidos de composicao visual, formulario, tabela, dashboard, widget ou campo continuam em `/api/praxis/config/ai/authoring/**` quando nao expressarem uma regra de negocio.
- Quando `quickReplies[].contextHints.tool=searchApiResources`, o cliente pode chamar `resource-candidates` com `retrievalQuery` e `artifactKind` para recuperar recursos reais do catalogo antes de gerar preview.
- Prompts genericos, como "Crie um dashboard", podem retornar `valid=false` com `failureCodes=["resource-candidate-ambiguous"]`, `candidates` e `quickReplies` para que o usuario escolha o recurso de negocio antes de gerar preview.
- `quickReplies` e a superficie canonica para chips clicaveis do assistente. Alem de `id`, `kind`, `label` e `prompt`, a resposta pode incluir `description`, `icon`, `tone` e `contextHints`.
- `description`, `icon` e `tone` orientam a apresentacao visual do chip; `contextHints` preserva dados estruturados como `resourcePath`, `submitUrl`, `operation` e `schemaUrl` para o proximo turno.
- `contextHints.domainCatalog` e um subcontrato versionado (`schemaVersion=praxis.ai.context-hints.domain-catalog/v0.2`) para solicitar contexto semantico/governanca no proximo turno. Os campos canonicos sao `serviceKey`, `resourceKey`, `releaseId`, `releaseKey`, `type`, `itemTypes`, `intent`, `query`, `contextKey`, `nodeType`, `recommendedAuthoringFlow`, `recommendedRuleType` e `limit`.
- O resolver LLM interno deve projetar esse contexto no `contextBundle.governedDomainContext` (`schemaVersion=praxis-agentic-authoring-governed-domain-context.v1`) antes do planejamento, usando `domain-catalog/context` como grounding semantico governado. O bloco deve declarar pelo menos `source=domain-catalog/context`, `policyProfile`, `available`, `resolutionStatus`, `requested` e `promptBlock`. `resolutionStatus` deve distinguir `resolved`, `requested_but_unavailable` e `not_requested`, para que a IA nao confunda ausencia de contexto governado com autorizacao para inferir regra por superficie de UI. Quando disponivel, esse bloco e a fonte canonica para linguagem de dominio, politicas, restricoes e campos regulados usados no authoring; superficies de UI continuam derivadas e nao viram fonte primaria da regra de negocio.
- Quando `contextHints.domainCatalog.recommendedAuthoringFlow=shared_rule_authoring`, o backend deve tratar o pedido como authoring governado de regra/decisao compartilhada. O backend tambem pode inferir essa rota quando o prompt pedir regra, politica, validacao, elegibilidade, compliance ou decisao de negocio sobre um recurso resolvido, mesmo que a UI ainda nao tenha enviado o hint explicito. Nessa situacao, `intent-resolution` pode responder com `gate.status=route_required` e `failureCodes=["shared-rule-authoring-required"]`, sinalizando que o proximo passo canonico esta em `/api/praxis/config/domain-rules/**`, e nao em `page-preview`/`componentEditPlan`.
- Se o prompt misturar palavras de componente e regra de negocio, a semantica de decisao prevalece. Exemplo: "crie uma regra para CPF no formulario" deve seguir para `domain-rules`; "adicione o campo CPF no formulario" continua elegivel para preview de componente.
- O primeiro passo canônico dessa trilha pode ser `POST /api/praxis/config/domain-rules/intake`, que persiste um draft governado a partir do prompt e devolve grounding suficiente para o host continuar em `simulation`. Esse grounding deve incluir `grounding.decisionDiagnostics` com `decisionKind=semantic_domain_rule`, `authoringMode=governed`, `decisionStage=intake`, `decisionSource=persisted_definition`, `canonicalOwner=praxis-config-starter`, contagens de cobertura/materialização/aprovação/alertas e `runtimeSurfacesAreDerived=true`, para que o cockpit consiga explicar desde o intake que a UI e os artefatos runtime são projeções derivadas da decisão semântica governada.
- `POST /api/praxis/config/domain-rules/simulations` deve responder com grounding, cobertura existente, materializações previstas, aprovações requeridas e um bloco aditivo de `explainability`, para que a explicação canônica venha do backend e não de heurísticas locais no host. Esse bloco deve incluir `explainability.decisionDiagnostics` com pelo menos `decisionKind=semantic_domain_rule`, `authoringMode=governed`, `decisionSource`, `canonicalOwner=praxis-config-starter`, contagens de cobertura/materialização/aprovação/alertas e `runtimeSurfacesAreDerived=true`, deixando explícito que UI, option sources e validações são projeções da decisão semântica governada.
- `POST /api/praxis/config/domain-rules/publications` passa a ser a fronteira canônica para promover uma definição persistida e aplicar materializações elegíveis quando `simulation.explainability.publicationReadiness=ready_to_publish` e o status da definição ainda for publicável (`draft`, `proposed`, `approved` ou `active`). Para `selection_eligibility`, o publish já pode derivar e aplicar uma materialização canônica de `option_source` quando o alvo previsto for `resource-option-source`, sem exigir que o host remonte localmente o payload de `LookupSelectionPolicy`; também pode derivar uma materialização canônica de `backend_validation` com `kind=resource_validation_policy` para o mesmo recurso, preservando a condição governada para enforcement backend. Para `validation`, `compliance` e `privacy`, o publish também pode derivar uma materialização `backend_validation` com `kind=resource_validation_policy` quando o alvo previsto for `resource-validation`, preservando condição, parâmetros e metadados de governança para o runtime consumidor. Para `workflow_action_policy`, o publish pode derivar uma materialização `workflow_action` com `kind=workflow_action_policy` e `targetArtifactType=resource-workflow-action`, governando uma action de recurso existente sem criar engine de workflow paralela. A próxima expansão canônica é `approval_policy`: ela deve projetar decisões de aprovação sobre actions de recurso já existentes por `targetLayer=approval_policy`, `kind=approval_policy` e `targetArtifactType=resource-action-approval`, sem criar inbox genérico ou motor BPM paralelo. Quando a publicação ainda estiver bloqueada, incluindo `publicationReadiness=blocked_by_definition_status`, o endpoint deve devolver `publicationStatus=blocked` junto do mesmo bloco de `explainability` usado para governança.
- Respostas de materialização devem incluir `decisionDiagnostics` com `decisionStage=materialization`, `decisionSource=materialization_record`, `canonicalOwner=praxis-config-starter`, `materializationModel=derived_projection`, identidade da regra de origem, `materializationKey`, coordenadas de alvo, `sourceHashPresent`, `sourceHash` quando disponível e `runtimeSurfacesAreDerived=true`. O cliente deve exibir essa explicação como projeção governada, não tratar a materialização como nova regra de negócio primária.
- Quando uma publicação for processada, `explainability.publicationDiagnostics.materializationOutcomes[]` deve explicar a resolução de cada projeção (`created`, `reused`, `selected_existing`, `selected_explicit`, `skipped` ou `blocked`) com `materializationKey`, coordenadas de alvo, `statusAtResolution` e `sourceHash` quando disponível. Quando a publicação for bloqueada antes da resolução de materializações, `explainability.publicationDiagnostics` deve ainda publicar `publicationStatus=blocked`, `publicationReadiness`, `blockedReason`, `definitionStatusAtResolution` e `materializationOutcomes=[]`. Clientes devem exibir esses diagnostics em vez de recriar heurísticas locais de publication/materialization.
- As transições de status em `/api/praxis/config/domain-rules/definitions/{definitionId}/status` e `/api/praxis/config/domain-rules/materializations/{materializationId}/status` são direcionais. Definições `rejected` e `retired` são terminais; materializações `failed`, `superseded` e `reverted` precisam voltar explicitamente para `draft` ou `pending_review` antes de nova tentativa de aplicação. Essa regra impede que o host reative decisões semânticas ou projeções runtime sem passar pela governança canônica.
- Em `/api/praxis/config/domain-knowledge/change-sets/{changeSetId}/apply`, a aplicação de Domain Knowledge é uma fronteira separada da aprovação: o change set precisa estar `approved`, manter `validation_result=valid` e passar por escopo tenant/environment antes de materializar alterações. O corte atual aplica `add_evidence` contra conceito existente no mesmo escopo e `revert_evidence` como transição governada de lifecycle para evidência `active`, preservando a linha original e registrando `reverted_by_change_set_id`, `reverted_at`, `revert_reason` e, quando houver substituição governada, `superseded_by_evidence_id`. Operações `delete_*` e `replace_*` continuam bloqueadas até terem semântica transacional própria.
- `GET /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/timeline` expõe uma timeline segura derivada do change set, validação, revisão, aplicação e lifecycle de evidência. A resposta pode incluir eventos `evidence.reverted` e `evidence.superseded`, mas deve permanecer `visibility=safe`, sem retornar patch bruto, payload de evidência, evidence keys, replacement keys, `sourcePointer`, `sourceUri`, `patchHash`, prompt ou histórico de chat.
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
mvn -Dtest=AiApiContractOpenApiTest,AiContractSpecConsistencyTest,AiContractV11RetroCompatibilityTest test -q
```

Geracao de bindings (A-02) a partir da mesma fonte:

1. executar `node tools/contracts/generate-ai-contract-bindings.js` no `praxis-config-starter`;
2. arquivo Java gerado:
   - `src/main/java/org/praxisplatform/config/contract/AiContractSpec.java`
3. arquivo TS gerado (quando o monorepo tiver `praxis-ui-angular`):
   - `../praxis-ui-angular/projects/praxis-ai/src/lib/core/contracts/ai-contract.generated.ts`

Observacao:

- o teste `AiContractSpecConsistencyTest` funciona como drift guard entre OpenAPI e constantes geradas no backend.
