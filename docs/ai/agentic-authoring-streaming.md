# Agentic Authoring Streaming

## Status atual

O `praxis-config-starter` ja possui uma superficie canonica de stream em:

- `POST /api/praxis/config/ai/patch/stream/start`
- `GET /api/praxis/config/ai/patch/stream/{streamId}`
- `GET /api/praxis/config/ai/patch/stream/{streamId}/probe`
- `POST /api/praxis/config/ai/patch/stream/{streamId}/cancel`

Essa superficie usa `AiTurnEventEnvelope` com `eventSchemaVersion=v1`, `threadId`,
`turnId`, `streamId`, `seq`, replay por `Last-Event-ID`, cancelamento, heartbeat,
controle de ownership e persistencia em event log.

O fluxo agentic authoring do Page Builder possui endpoints sincronicos:

- `POST /api/praxis/config/ai/authoring/intent-resolution`
- `POST /api/praxis/config/ai/authoring/page-preview`
- `POST /api/praxis/config/ai/authoring/page-apply`

O diagnostico `llmDiagnostics` e opt-in e serve para auditoria/debug do prompt,
context bundle e tool catalog de um turno ja concluido. Ele nao substitui stream.

O backend tambem expõe o primeiro incremento canonico de stream para turnos de
authoring em:

- `POST /api/praxis/config/ai/authoring/turn/stream/start`
- `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}`
- `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}/probe`
- `POST /api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel`

## Decisao canonica

Agentic authoring nao deve criar um segundo formato de stream. O fluxo reutiliza a
familia `AiTurnEventEnvelope` e os mesmos conceitos
de ciclo de vida ja governados pelo stream de patch:

- `streamId` identifica a conexao logica do turno.
- `threadId` identifica a conversa do assistente.
- `turnId` identifica o turno executavel.
- `seq` ordena eventos e permite replay.
- `eventSchemaVersion` governa compatibilidade do envelope.
- tipos terminais continuam sendo `result`, `error` e `cancelled`.

A diferenca deve ficar no payload, nao no envelope.

## Superficie canonica

A superficie de authoring streaming e:

- `POST /api/praxis/config/ai/authoring/turn/stream/start`
- `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}`
- `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}/probe`
- `POST /api/praxis/config/ai/authoring/turn/stream/{streamId}/cancel`

O endpoint `start` recebe um request de turno agentic que contenha, no minimo:

- `userPrompt`
- `targetApp`
- `targetComponentId`
- `currentPage`
- `selectedWidgetKey`
- `componentCapabilities`
- `conversationMessages`
- `pendingClarification`
- `attachmentSummaries`
- `contextHints`
- `runtimeComponentObservations`
- `runtimeComponentObservationTrustBoundary`
- `provider`, `model` e `apiKey` quando aplicavel

`runtimeComponentObservations` e um envelope opcional de evidencia de runtime
enviado por componentes do cockpit. O backend aceita essa evidencia somente com
`runtimeComponentObservationTrustBoundary=untrusted_frontend_observation`, copia
apenas campos permitidos para `contextHints.groundedRuntimeComponentContext`
(`GroundedRuntimeComponentContext`) e nunca promove observacoes de frontend a
capabilities, permissoes ou execucao de actions. Dados crus de linhas, valores
completos, `sampleRows`, `rawRows`, `dataSource`, segredos e decisoes de intencao
nao fazem parte do contexto aterrado.
Quando esse contexto confirma uma superficie relacionada consultavel, o fluxo
consultivo pode reconhecer a superficie e a selecao como evidencia governada,
mas deve declarar a ausencia de uma tool backend read-only antes de listar dados
relacionados. Confirmar `missionTeam` e diferente de inventar nomes de
participantes.
Quando `resolveRuntimeRelatedSurface` conseguir reconciliar `relationSurfaceRefs`,
`queryMapping`, `selectionDigest` e um `resourcePath` backend governado, a
resposta pode listar os registros relacionados retornados por essa leitura
read-only. O filtro enviado ao backend deve vir de
`relationSurfaceRefs[].queryMapping.targetFilterField`; `selectionDigest.idField`
apenas confirma o campo de origem selecionado e `selectedIds[0]` fornece o valor.
Sem `queryMapping` declarado, sem `idField`, com selecao multipla ou com
`targetWidget` divergente quando ele for necessario para descobrir o recurso, a
tool deve falhar antes do HTTP. Quando a composicao publicar
`runtimeSurfaceInstanceRef`/`targetRuntimeSurfaceInstanceRef`, essa identidade
canonica governa a escolha da superficie alvo; se varias superficies runtime
compartilharem o mesmo `resourcePath` e nao houver `runtimeSurfaceInstanceRef`
nem `targetWidget` reconciliavel, o backend deve bloquear com diagnostico de
ambiguidade em vez de escolher a primeira ocorrencia por recurso.
Quando a superficie alvo estiver presente no contexto runtime aterrado,
`targetFilterField` tambem deve estar em `schemaFieldRefs` desse alvo ou
declarado como filtro/queryContext canonico por
`queryMapping.targetPath=filters.<campo>`; registros retornados sao projetados por
campos declarados e por redaction de escalares sensíveis. O endurecimento
seguinte deve reconciliar esses campos contra o schema backend governado, nao
apenas contra observacao frontend aterrada.
Quando componentes publicam `snapshot.schemaFieldDescriptors[]`, o grounding
preserva somente `fieldRef`, tipos/formats/controlType seguros. Esses descritores
podem informar a reconciliacao backend-owned de dimensoes temporais, mas nao
autorizam reads sem a mesma validacao de superficie, projection e redaction.
Nessas respostas consultivas guardadas, o `result` terminal pode incluir
`evidenceBundle.runtimeConsultableContext`, uma projecao sanitizada do contexto
runtime aterrado com refs seguras de superficies, actions, campos e claims
aceitas/rejeitadas. Quando a leitura relacionada ocorrer, o `result` tambem pode
incluir `evidenceBundle.runtimeRelatedSurfaceReads[]`, com registros projetados
por allowlist e `rawRuntimeValuesCopied=false`; durante o beta,
`evidenceBundle.runtimeRelatedSurfaceRead` permanece apenas como alias derivado
de `runtimeRelatedSurfaceReads[0]`. O mesmo bundle pode incluir
`evidenceBundle.runtimeToolPlan`, com `schemaVersion=praxis-runtime-tool-plan.v1`,
budget, steps e aggregate diagnostics. O plano tambem pode incluir
`planner.schemaVersion=praxis-runtime-tool-planner.v1` como cabecalho/politica
do planejador. `steps[]`, `candidateSteps[]` e `blockedSteps[]` sao arrays irmaos
diretamente sob `runtimeToolPlan`: `steps[]` descreve o que pode executar no
policy atual, `candidateSteps[]` audita candidatos/ranking sem autorizar execucao
e `blockedSteps[]` explica intents ou candidatos bloqueados. O plano tambem pode
carregar `aggregationPolicy`, `stepBudget`, `projectionPolicyRef` e
`redactionPolicyRef` por step/candidato. O plano deve declarar
`multiToolAuthorization.source=backend_policy`. A policy default
`runtime-tool-policy:single-read-beta` preserva no maximo uma tool read-only. A
intencao `runtime_related_surface_availability` deve gerar plano
`readMode=none`, `maxToolCalls=0`, sem step executavel e sem chamada HTTP, e
`runtime_surface_disambiguation` deve aparecer como
`blockedSteps[]`/`candidateSteps[]` com orcamento zero. O resultado terminal
pode incluir `runtimeRelatedSurfaceDisambiguation` com `options[]` sanitizados
para superficies aceitas, `readMode=none`, `backendReadsPerformed=false` e sem
`runtimeRelatedSurfaceReads[]`, para que o cliente peca escolha de alvo sem
executar tool. Nesses casos, o `result.quickReplies[]` pode projetar cada
opcao como chip clicavel com `semanticDecision.constraints.runtimeRelatedSurfaceDisambiguationSelection`
e `value` derivados do backend; o cliente deve reenviar essa decisao como
`activeSemanticDecision`, nao recriar refs localmente nem trata-la como
`contextHints` autoritativo. `runtime_related_surface_detail` continua read-free quando o
alvo estiver ausente ou ambiguo, mas sob `runtime-tool-policy:multi-tool-readonly-beta`
pode executar exatamente um read governado quando houver uma unica superficie
relacionada aceita ou quando a decisao semantica retornar
`DETAIL_TARGET_SURFACE_REF` e o backend reconciliar esse surfaceRef contra um
candidato aceito. Um follow-up de desambiguacao tambem pode carregar
`activeSemanticDecision.constraints.runtimeRelatedSurfaceDisambiguationSelection`
com `optionRef`, `candidateRef` e `surfaceRef` emitidos anteriormente; essa
selecao so vira alvo quando o backend reconciliar os tres refs contra um
candidato aceito no contexto runtime atual. Em todos os casos aceitos, o plano
usa `readMode=detail`,
`aggregationPolicy.mode=governed_detail` e `detailTarget.provenance=backend_reconciled`.
O cliente tambem pode preservar uma projecao sanitizada de
`runtimeRelatedSurfaceDisambiguation` em
`diagnostics.runtimeRelatedSurfaceDisambiguationContext` para o turno seguinte.
Esse contexto historico e grounding read-free para o classificador semantico:
ele pode ajudar a interpretar follow-ups naturais como "mostre os eventos",
mas nao autoriza leitura, nao substitui `activeSemanticDecision` e nao pode
carregar dados de registros. Para entrar no prompt do turno seguinte, esse
contexto precisa declarar `sessionId`, `sourceTurnId`, `pageId`, `capturedAt` e
`ttlMs`; o backend descarta o bloco se ele estiver expirado, vier do mesmo
`clientTurnId`, pertencer a outra sessao/pagina ou se qualquer opcao deixar de
reconciliar contra candidatos aceitos no contexto runtime atual. Para executar
detalhe, o backend ainda precisa de uma decisao semantica ativa ou resolvida por
LLM que produza `DETAIL_TARGET_SURFACE_REF`, seguida de reconciliacao contra
candidatos aceitos no contexto runtime atual. Para executar uma lista direcionada, a decisao
semantica deve produzir `LIST_TARGET_SURFACE_REF`; quando reconciliado, o
terminal pode expor `runtimeRelatedSurfaceResolution.listTarget` com
`source=semantic_decision`, `provenance=backend_reconciled`,
`runtimeToolPlan.readMode=list_targeted` e
`aggregationPolicy.mode=governed_list_targeted`, executando exatamente um read
governado na superficie alvo. Se o alvo de listagem for ausente, divergente ou
forjado, o backend bloqueia antes de HTTP com diagnostics fail-closed; se nao
houver alvo, `runtime_related_surface_list` preserva o comportamento multi-read
governado existente. Para executar um resumo direcionado, vale a mesma fronteira:
a decisao semantica deve produzir `SUMMARY_TARGET_SURFACE_REF`; quando
reconciliado, o terminal pode expor
`runtimeRelatedSurfaceResolution.summaryTarget` com `source=semantic_decision`,
`provenance=backend_reconciled`, `runtimeToolPlan.readMode=summary_targeted` e
`aggregationPolicy.mode=governed_summary_targeted`, executando exatamente um read
governado e derivando `runtimeRelatedSurfaceSummary` apenas desse read
sanitizado. Se o alvo de resumo for ausente, `runtime_related_surface_summary`
preserva o resumo multi-superficie governado existente; se for divergente ou
forjado, bloqueia antes de HTTP com diagnostics fail-closed.
Quando a classificacao semantica inicial ficar conservadora em
`runtime_surface_disambiguation`, resolver uma intent direcionavel sem alvo, ou
quando o classificador falhar e o fallback conservador detectar um pedido de
detalhe/foco, o backend pode primeiro reconciliar o alvo contra um catalogo
backend-owned dos candidatos aceitos, usando apenas `surfaceRef`,
`candidateRef`, `runtimeSurfaceInstanceRef`, `label` e `semanticAliases`
sanitizados como grounding. Esse passo so roda depois de uma intent semantica
targetable (`list`, `summary` ou `detail`) com
`TARGET_RESOLUTION_MODE=optional|required`, ou depois do fallback
`runtime_surface_disambiguation` com `TARGET_RESOLUTION_MODE=optional` para
detalhe focado; ele nao decide a intencao primaria, nao usa hints de frontend
como autorizacao e nao executa tool. Termos de alvo encontrados em escopo
simples de negacao do prompt, como `nao detalhe participantes`, nao pontuam no
ranking do catalogo. Quando aceito, o terminal pode expor
`runtimeRelatedSurfaceResolution.targetCandidateResolution` com
`schemaVersion=praxis-runtime-related-surface-target-candidate-resolution.v1`,
`source=backend_runtime_target_catalog`, `targetResolutionMode`, `intentKind`,
`targetSurfaceRef`, `candidateRef`, `runtimeSurfaceInstanceRef`, `matchedTermKind`,
`provenance=backend_reconciled`, `accepted=true` e `score`. Quando rejeitado ou
ambiguo, o mesmo diagnostico pode declarar `provenance=backend_rejected`,
`failureCode` e `evaluatedCandidates[]`, com itens sanitizados contendo apenas
`surfaceRef`, `candidateRef`, `runtimeSurfaceInstanceRef`, `matched`, `score`,
`matchedTermKind`, `ignoredNegatedTermCount` e `failureCode`, sem termo cru do
prompt nem valores de registros. O caminho `accepted=true` deve permanecer
enxuto e nao precisa emitir `evaluatedCandidates[]`.

Quando a primeira decisao ficar em `runtime_surface_disambiguation` com
`TARGET_RESOLUTION_MODE=required`, ou com `optional` e objetivo explicitamente de
detalhe/foco, o catalogo tambem pode resolver somente o alvo e materializar
`runtime_related_surface_detail`, porque a classificacao semantica ja declarou
que ha um alvo runtime-related a reconciliar antes da leitura. Esse caminho
continua proibido para disponibilidade, compare, listagem ou resumo sem kind
semanticamente resolvido. Se o catalogo nao aceitar um unico alvo, o backend so
pode fazer a segunda decisao semantica focada em
`KIND + TARGET_SURFACE_REF` se a primeira decisao declarar
`TARGET_RESOLUTION_MODE=optional|required`. O modo `none` e canonico para
multi-read e resumo multi-superficie naturais, portanto nao deve disparar
refinamento nem emitir `targetRefinementDiagnostics`. A segunda decisao usa
candidatos aceitos, labels e aliases sanitizados como grounding, nao executa
tool e nao autoriza leitura por si so: o alvo ainda precisa reconciliar contra
os candidatos runtime atuais antes de qualquer HTTP. Quando esse refinamento for
tentado, o terminal pode expor
`runtimeRelatedSurfaceResolution.targetRefinementDiagnostics` com
`schemaVersion=praxis-runtime-related-surface-target-refinement.v1`,
`targetResolutionMode`, `initialKind`, `refinedKind`, `targetSurfaceRef`,
`provenance`, `confidence`, `accepted` e `failureCode` quando rejeitado. O
diagnostico e sanitizado e serve apenas para auditoria; `accepted=true` exige
`provenance=backend_reconciled`, e rejeicoes continuam fail-closed sem HTTP.
`runtime_related_surface_compare`, sem dimensao
comparavel canonica aceita, mesmo sob readonly-beta, deve emitir
`aggregationPolicy.mode=compare_planning_only`,
`failureCode=runtime-related-surface-compare-not-enabled`, `steps[]=[]`,
`runtimeRelatedSurfaceReads[]=[]` e `executionDiagnostics.planningOnly=true`.
Com `runtime-tool-policy:multi-tool-readonly-beta`, se a decisao semantica
retornar `COMPARISON_DIMENSION_FIELD` e o backend reconciliar esse campo contra
as superficies aceitas, ou se o backend inferir exatamente uma dimensao comum
nao sensivel a partir de contratos reconciliados, o backend pode executar o
compare governado: ate dois reads governados, `readMode=compare`,
`aggregationPolicy.mode=governed_compare`,
`aggregationPolicy.comparisonDimension.source=semantic_decision|backend_contract`,
`provenance=backend_reconciled`,
`executionDiagnostics.compareEvidenceEmitted=true`,
`runtimeRelatedSurfaceCompare` presente com fatos de contagem por superficie,
distribuicao categorica, cobertura de projection/redaction, delta de contagem,
overlap categorico, matriz de presenca de registros e `temporal_coverage`
apenas quando a dimensao aceita for temporal por tipo reconciliado
(`fieldType=date|date-time`), e sem alias singular em multi-read.
O starter tambem pode ser iniciado, exclusivamente em ambiente de smoke, com
`praxis.ai.authoring.runtime-related-surface.intent-policy-ref=runtime-related-surface-intent-policy:temporal-compare-smoke`
e `praxis.ai.authoring.runtime-related-surface.temporal-comparison-field-ref=<fieldRef>`
para substituir o classificador LLM por uma decisao backend-owned deterministica
de compare temporal. Essa policy so produz `runtime_related_surface_compare`
quando o campo configurado aparece em pelo menos duas superficies aterradas e nao
redigidas, exige `fieldType=date|date-time` backend-reconciled antes de qualquer
read, e valores desconhecidos voltam para `runtime-related-surface-intent-policy:llm`.
`contextHints.runtimeRelatedSurfaceComparisonDimension` e hints equivalentes de
frontend nao autorizam a dimensao. Campos nao declarados, ambiguos,
omitidos/redigidos, sensiveis ou com tipo temporal divergente/incompleto
bloqueiam antes de qualquer read.
Se a resolucao semantica LLM da intencao runtime-related falhar e nao houver
decisao semantica ativa governada, o fallback deve ser read-free
(`runtime_surface_disambiguation`), sem `steps[]`, sem chamada HTTP e sem
`runtimeRelatedSurfaceReads[]`; quando houver mais de uma superficie aceita,
pode emitir `runtimeRelatedSurfaceDisambiguation.options[]` com refs,
projection/redaction policy refs e claim refs aceitas, mas sem dados de
registros. O backend nao deve converter falha do classificador em `list`
executavel.
`summary` tambem fica bloqueado/read-free por padrao, mas pode executar como
agregacao governada quando a policy backend
`runtime-tool-policy:multi-tool-readonly-beta` estiver ativa e todos os reads
relacionados forem aceitos. `maxToolCalls > 1` so pode existir quando
`planner.multiToolExecutionEnabled=true`, `planner.maxToolCallsMayExceedOne=true`
e `multiToolAuthorization.allowed=true`; enquanto qualquer desses valores for
`false`, o backend deve clamp/arrecusar o plano multi-tool e registrar
`multiToolGuardrail.failureCode=runtime-multi-tool-policy-not-enabled`.
Uma politica backend de preparacao pode ser selecionada apenas por configuracao
do starter (`praxis.ai.authoring.runtime-tool.policy-ref`) usando
`policyRef=runtime-tool-policy:multi-tool-dry-run-beta` com
`planner.executionMode=dry_run`: nesse modo o plano pode apresentar multiplos
`candidateSteps[]` e `aggregationPolicy.mode=dry_run_multi_read`, mas
`budget.maxToolCalls=0`, `steps[]=[]` e `runtimeRelatedSurfaceReads[]=[]`.
Dry-run autoriza simulacao/auditoria do plano, nao chamadas HTTP nem evidencia
agregada real. `runtimeToolPlan.executionDiagnostics` deve declarar
`dryRun=true`, `multiToolExecutionEnabled=false`, `authorizedCandidateCount`,
`maxPlannedSteps`, `maxExecutableSteps=0`, `usedToolCalls=0`,
`backendReadsPerformed=false` e o motivo de nao execucao. Hints de frontend nao
podem ativar essa politica. A politica backend
`runtime-tool-policy:multi-tool-readonly-beta` libera execucao read-only limitada
somente quando selecionada por configuracao backend. Nesse modo,
`planner.backendPolicyRef=runtime-tool-policy:multi-tool-readonly-beta`,
`planner.executionMode=read_only`, `planner.multiToolExecutionEnabled=true` e
as intencoes semanticas `runtime_related_surface_list` e
`runtime_related_surface_summary` podem executar ate dois `steps[]` governados,
enquanto `runtime_related_surface_detail` pode executar exatamente um step
governado quando houver uma unica superficie aceita ou um `DETAIL_TARGET_SURFACE_REF`
semanticamente resolvido e backend-reconciled. Cada step carrega
`toolName`, `stepBudget`, `projectionPolicyRef`, `redactionPolicyRef` e
`acceptedClaimRefs`. Cada leitura gera uma entrada em
`runtimeRelatedSurfaceReads[]`; `usedToolCalls` deve ser igual ao numero de
steps executados. Para `summary`, `aggregationPolicy.mode=governed_summary` e o
resultado terminal pode incluir `runtimeRelatedSurfaceSummary`, derivado apenas
dos reads sanitizados; quando houver `SUMMARY_TARGET_SURFACE_REF`
backend-reconciled, `readMode=summary_targeted`,
`aggregationPolicy.mode=governed_summary_targeted` e exatamente um step/read sao
usados. Para `detail`, `aggregationPolicy.mode=governed_detail`
e o alias beta singular pode apontar para `runtimeRelatedSurfaceReads[0]`.
Se qualquer step falhar, a agregacao falha fechada e
`runtimeRelatedSurfaceReads[]` terminal fica vazio. O alias beta singular
`runtimeRelatedSurfaceRead` nao deve ser emitido quando houver mais de uma
leitura. `runtime_related_surface_compare` entra nessa lista quando houver
`comparisonDimension` aceita; ele chama os mesmos reads governados e emite
`runtimeRelatedSurfaceCompare` terminal derivado apenas dos reads sanitizados.

Quando `runtimeToolPlan` existir, o stream pode emitir fases tecnicas
`runtime.tool-plan.intent`, `runtime.tool-plan.candidates`,
`runtime.tool-plan.created`, `runtime.tool-plan.step` e
`runtime.tool-plan.aggregate` antes de `consultative.answer`. Esses eventos nao
devem carregar `records`, `sampleRows`, `rawRows`, `dataSource`, CPF, email,
segredos ou valores runtime crus; registros sanitizados permanecem apenas no
`evidenceBundle` terminal. Eventos tecnicos podem aparecer mais de uma vez no
transporte SSE por replay/reconexao ou por envelopes intermediarios do stream,
mas isso nao implica nova execucao. Quando o evento carregar
`streamEventDiagnostics.schemaVersion=praxis-authoring-stream-event-diagnostics.v1`,
consumidores devem agrupar por `streamEventDiagnostics.dedupeKey` ou
`eventUniquenessKey`, e validar execucao por `stepRef`, `budget.usedToolCalls`,
`runtimeRelatedSurfaceReads.length` e `aggregateStatus`, nao por contagem bruta
de `phase`. O campo `duplicatesDoNotIndicateExecution=true` declara
explicitamente que duplicatas tecnicas sao replay-safe.

`conversationMessages` e usado apenas como evidencia de continuidade
conversacional: referencias curtas como "1" ou "primeira opcao" podem ser
resolvidas semanticamente contra a ultima resposta do assistente. O historico
nao e tratado como instrucao privilegiada; somente papeis `user` e `assistant`
sao considerados, com limite de janela/tamanho, e qualquer escolha executavel
deve continuar sendo validada pelos contratos canonicos de authoring.

Quando o turno for sensivel a dominio, privacidade, compliance, validacao ou
terminologia de negocio, `contextHints.domainCatalog` deve seguir o envelope em
[`agentic-domain-task-envelope.md`](agentic-domain-task-envelope.md). Em especial,
turnos criados a partir de discovery de recurso devem preservar `resourceKey`
para que o contexto LLM seja recuperado da release correta do Domain Catalog.

O resultado terminal entrega o mesmo contrato funcional que o frontend hoje obtem
pela combinacao de `intent-resolution` e `page-preview`, preservando fallback
sincrono para clientes que ainda nao consomem SSE via `fallbackAuthoringUrl`.

Quando `praxis.ai.stream.auth.mode=signed-url-token`, o token emitido no `start`
e a identidade canonica do `GET` de SSE e do `probe` quando o cliente nao envia
headers de tenant/usuario. Isso e necessario porque o browser `EventSource` nao
permite headers customizados. Se o caller enviar headers explicitos de identidade,
o backend ainda valida o token contra esse escopo antes de abrir o stream.
Nesse modo, `praxis.ai.stream.auth.token-secret` e obrigatorio e deve conter ao
menos 32 bytes para evitar tokens assinados com segredo fraco em ambientes
corporativos.

## Eventos recomendados

Os eventos devem usar os tipos existentes sempre que possivel:

| Tipo | Payload recomendado |
|------|---------------------|
| `status` | `state`, `phase`, `message` |
| `thought.step` | `phase`, `tool`, `summary`, `diagnostics` seguro, `streamEventDiagnostics` quando houver dedupe auditavel |
| `heartbeat` | metadados de keep-alive |
| `result` | `intentResolution`, `preview`, `assistantMessage`, `quickReplies`, `canApply`, `decisionDiagnostics`, `streamEventDiagnostics` quando houver dedupe auditavel |
| `error` | `code`, `assistantMessage`, `message`, `phase` |
| `cancelled` | `message`, `phase` |

Durante turnos longos, especialmente quando a LLM esta resolvendo intencao ou
revisando recursos recuperados por RAG/catalogos governados, o backend deve
emitir fases conversacionais suficientes para evitar uma UI parada em um unico
estado generico. As fases canonicas atuais de `thought.step` incluem:

- `context.bundle`: contexto do turno recebido e normalizado.
- `runtime.context.grounding`: observacoes runtime aterradas como evidencia nao
  confiavel, com contagens, claims aceitas/rejeitadas, superficies e refs
  seguros.
- `intent.resolve`: preparacao da resolucao semantica.
- `intent.resolve.llm`: chamada ou revisao da LLM sobre a intencao do usuario.
- `intent.resolve.grounding`: checagem da decisao contra evidencias governadas.
- `resource.discovery`: recuperacao de recursos, schemas, capabilities ou
  catalogos backend.
- `projectKnowledge.retrieve`: recuperacao de Project Knowledge/RAG governado.
- `preview.plan`: planejamento da materializacao governada.
- `preview.compile`: compilacao ou reparo da preview materializada.

`heartbeat` e out-of-band, nao persistido no event log, e deve carregar pelo
menos `state=alive`, `phase`, `summary` e `lastEventType`. O `phase` deve
refletir o ultimo evento nao terminal conhecido, permitindo que clientes mostrem
mensagens como "a LLM ainda esta resolvendo a intencao" sem inventar logica
local ou depender de timers opacos no frontend.

O processamento assincrono do turno deve respeitar
`praxis.ai.stream.processing-timeout-seconds` para evitar que o cliente fique
preso em estados intermediarios quando retrieval, provider LLM ou compilacao de
preview nao concluem. O default da plataforma e `360s`, porque turnos reais de
authoring podem envolver discovery, RAG, multiplas chamadas LLM e materializacao
no mesmo ciclo. Smokes e hosts podem reduzir esse valor explicitamente quando
usarem doubles deterministas. Ao estourar esse limite, o backend emite `error`
terminal com `code=agentic-authoring-timeout` e expira a reserva do turno.

Erros terminais devem separar texto de usuario e diagnostico tecnico. `code`
deve ser estavel para i18n e tratamento no cliente; `assistantMessage` deve ser
seguro para exibir na conversa; `message` pode conter detalhe tecnico para
diagnostico restrito. Falhas inesperadas de processamento usam
`code=agentic-authoring-processing-failed`.

### Diagnostico de decisao

O evento terminal `result` deve incluir `decisionDiagnostics` com
`schemaVersion=praxis-agentic-authoring-decision-diagnostics.v1`. Esse objeto e
a trilha segura para diferenciar uma decisao authorada com LLM/RAG/contexto de
um resultado que apenas compilou por fallback deterministico.

O mesmo `result` deve transportar `intentResolution.semanticDecision` com
`schemaVersion=praxis-agentic-authoring-semantic-decision.v1`. Essa e a decisao
semantica canonica do turno; `operationKind`, `artifactKind`, `changeKind`,
`selectedCandidate` e `visualizationDecision` permanecem como projecoes
compatíveis para consumidores existentes, nao como fonte primaria da decisao.

Quando houver resultado anterior na mesma thread, o backend pode carregar a
ultima `semanticDecision` ativa e transporta-la como `activeSemanticDecision`
no estado interno do turno. Refinamentos como "gostei, mas prefiro graficos"
devem preservar `selectedResource` da decisao anterior e produzir uma nova
`semanticDecision` com `refinementOf`/`previousDecisionId` apontando para o
`decisionId` anterior, alterando apenas a intencao visual/materializavel.
O refinamento deve ser modelado como diff semantico em
`semanticDecision.refinement`, com `preserve`, `replace`, `add` e `remove`.
Assim, pedidos como "mantem os dados, so muda a visualizacao" preservam a
fonte/recurso anterior e trocam apenas `artifactKind`, `visualIntent` ou
`chartType` pela politica canonica.

Antes da selecao de recurso, o backend deve montar um pacote canonico de
evidencias em `semanticDecision.retrievedEvidence`. Esse bundle deve registrar
evidencias recuperadas de `api_metadata`, `/schemas/filtered`, `capabilities`,
`actions`, catalogo de dominio e, quando disponivel, conhecimento de
projeto/RAG e exemplos/recipes. Fallback lexical deve aparecer como evidencia
fraca (`kind=weak_lexical_match`), nao como decisao semanticamente confiavel.

Campos canonicos atuais:

- `operationKind`, `artifactKind` e `valid`;
- `retrievalSource`, por exemplo `semantic_retrieval`, `lexical_fallback`,
  `context_hint`, `broad_artifact_discovery`, `deterministic_override`,
  `none` ou `unknown`;
- `retrievedEvidence`, com `source`, `kind`, `ref`, `summary`, `confidence`,
  `matchedTerms`, `tenantId`, `environment` e `releaseId` por evidencia;
- `refinement`, quando o turno for um diff semantico sobre decisao anterior ou
  pagina atual, com `refinementKind`, `preserve`, `replace`, `add`, `remove`,
  `rationale` e `confidence`;
- `selectedResourcePath`, quando houver recurso selecionado;
- `llmResolutionAttempted` e `llmResolved`;
- `fallbackPolicy`, hoje `fail-safe` quando telemetry de resolucao existir;
- `keywordFallbackApplied`;
- `semanticPolicyApplied`, quando uma politica semantica governada ajustou a
  decisao sem promover fallback de keyword a autoridade;
- `selectedCandidateUsesLexicalFallback`;
- `selectedCandidateUsesDomainAnchor`;
- `candidateSetContainsLexicalFallback`;
- `candidateSetContainsDomainAnchor`;
- `previewTechnicallyValid`, que indica apenas compilacao tecnica do preview;
- `previewResourceSchemaVerified`, que indica grounding estrutural do recurso em
  `/schemas/filtered`;
- `decisionValid`, que indica se a materializacao satisfaz a decisao semantica;
- `semanticDecisionReviewGroundedByPreview`, quando uma decisao marcada como
  `weak-lexical-evidence` foi re-grounded pela materializacao verificada;
- `toolLoopCompleted`, `toolLoopTerminalReason` e `toolLoopStepCount`, quando o
  turno executou o loop governado de ferramentas;
- `requiresReview`;
- `reviewReason`, quando `requiresReview=true`.
- memoria de decisao: `conversationId`, `turnId`, `userGoal`,
  `activeObjective`, `artifactIntent`, `visualIntent`, `constraints`,
  `previousDecisionId`, `refinementOf`, `rationale` e `confidence`.

Regra de aplicacao:

- `canApply=true` somente quando a preview compila tecnicamente, a decisao
  materializada e semanticamente valida e `decisionDiagnostics.requiresReview`
  nao e `true`.
- `preview.valid=true` nao implica `canApply=true`; uma tabela tecnicamente
  valida que contradiz `visualIntent=charts` deve retornar
  `decisionDiagnostics.decisionValid=false`,
  `reviewReason=semantic-preview-materialization-mismatch` e `canApply=false`.
- Quando a decisao pedir um `visualizationDecision.primaryComponent`
  governado, a materializacao precisa conter esse componente. Se o preview
  compilar, mas trocar o componente pedido por outro, deve retornar
  `failureCodes=["semantic-preview-primary-component-required"]`,
  `reviewReason=semantic-preview-materialization-mismatch` e `canApply=false`.
- `keywordFallbackApplied=true` deve forcar
  `decisionDiagnostics.requiresReview=true`,
  `reviewReason=keyword-fallback-fail-safe` e `canApply=false`.
- `page-apply` deve exigir `semanticDecision`; aplicar apenas
  `compiledFormPatch` sem decisao canonica e um bypass de contrato.
- `page-apply` deve rejeitar `semanticDecision.reviewRequired=true`, mesmo que
  a materializacao seja estruturalmente valida, exceto pelo caso estrito
  `reviewReason=weak-lexical-evidence` quando o `compiledFormPatch` carrega
  `diagnostics.resourceSchemaGrounding.verified=true` com
  `source=schemas.filtered`. Essa excecao representa re-grounding real por
  schema canonico, nao laundering de memoria/fallback.
- `semanticPolicyApplied=true` nao deve, por si so, forcar revisao. Ele marca
  que a plataforma aplicou uma regra semantica auditavel, por exemplo corrigir
  uma resposta operacional do LLM para dashboard analitico quando o objetivo
  conversacional pede ranking/comparacao.
- Refinamentos visuais de segundo turno, como "prefiro graficos", devem
  preservar a fonte de dados do artefato atual, trocar a projecao visual na
  `semanticDecision` e registrar politica semantica auditavel, nao fallback de
  keyword.
- `selectedCandidateUsesDomainAnchor=true` deve forcar
  `decisionDiagnostics.requiresReview=true`,
  `reviewReason=resource-selection-domain-anchor` e `canApply=false`.

Essa regra existe para impedir sucesso silencioso: um fallback pode ajudar a
manter a conversa viva, mas nao deve liberar materializacao como se fosse uma
decisao semanticamente governada.

Fases recomendadas para authoring:

- `context.bundle`
- `tool.catalog`
- `intent.resolve`
- `resource.discovery`
- `preview.plan`
- `preview.compile`
- `preview.apply-local`
- `review`

## Regras para o Page Builder

O Page Builder deve:

- manter o fluxo sincrono atual como fallback;
- usar streaming apenas quando o backend anunciar suporte ou quando o host habilitar
  explicitamente essa capacidade;
- apresentar eventos de progresso como estado tecnico/operacional, sem misturar
  payload de diagnostico com mensagens conversacionais;
- preservar `quickReplies[].contextHints` em todos os caminhos;
- preservar `quickReplies[].semanticDecision` e, quando presente, reenviar a
  decisao selecionada como `activeSemanticDecision` no turno seguinte;
- cancelar o stream quando o usuario cancelar o turno ou fechar o painel, se o stream
  ainda estiver ativo;
- tratar `result` como a unica fonte para aplicar preview local;
- nunca habilitar aplicacao local apenas porque `preview.valid=true`; a UI deve
  respeitar `canApply` e, quando
  `decisionDiagnostics.requiresReview=true`, apresentar revisao/clarificacao em
  vez de aplicar a materializacao;
- reenviar `intentResolution.semanticDecision` em `page-apply` junto com o
  `compiledFormPatch`, para que o backend rejeite materializacoes que nao
  cumpram a decisao canonica authorada.

## Evidencia de validacao ponta a ponta

Em 2026-04-23, o fluxo full local foi validado com o runner canonico:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-PbAgenticFullE2E.ps1 `
  -Provider openai `
  -QuickstartRoot ..\praxis-api-quickstart `
  -UiRoot ..\praxis-ui-angular `
  -StreamProcessingTimeoutSeconds 360
```

Resultado:

- `praxis-api-quickstart` subiu em `http://localhost:8088` com `PRAXIS_AI_STREAM_AUTH_MODE=signed-url-token`;
- `praxis-ui-angular` subiu em `http://localhost:4003`;
- o Playwright executou `praxis-page-builder-agentic-validation.playwright.config.ts`;
- os fluxos de dashboard de pagamentos e formulario de funcionarios passaram usando browser real, backend SSE real e provider OpenAI real;
- a auditoria confirmou que `praxis-ai.service.ts` nao continha `getMockPatch` nem `extractUserIntent`;
- total: `3 passed`.

Essa validacao fecha o marco operacional do primeiro ciclo backend-driven: o frontend nao dependeu de caminho mockado de authoring e o resultado aplicado veio do contrato retornado pelo backend.

## Fora de escopo

Esta decisao nao muda os endpoints sincronos existentes. Integracao do Page Builder
Angular com SSE, UI de progresso e retry/cancelamento no cliente permanecem fora
deste primeiro incremento de backend.
