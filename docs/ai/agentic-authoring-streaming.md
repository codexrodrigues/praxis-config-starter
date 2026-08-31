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

Quando `contextHints.includeLlmDiagnostics=true`,
`llmDiagnostics.resolutionTelemetry` tambem projeta a telemetria sanitizada das
invocacoes de provider realizadas durante a resolucao semantica:

- `providerInvocations[]`: fase, tentativa, provider/modelo efetivos,
  transporte, status, classe de falha e latencia;
- tokens de entrada, saida, cache read/write e total somente quando retornados
  pelo provider;
- `providerInvocationAggregate`: contagens e totais limitados ao turno.

A projecao e limitada a 12 invocacoes e nunca inclui prompt, completion, API
key, headers ou payload nativo. `rawPromptCopied=false`,
`rawResponseCopied=false` e `credentialsCopied=false` tornam essa fronteira
auditavel. O envelope SSE e seus eventos terminais permanecem inalterados.

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

## Maquina de estados canonica

O ciclo de vida possui duas projecoes complementares, com fontes canonicas ja
existentes:

- `AiTurn.status` governa a reserva transacional (`PROCESSING`, `DONE` ou
  `CANCELLED`);
- o ultimo `AiTurnEventEnvelope` governa o estado observavel do stream. Somente
  `result`, `error` e `cancelled` sao terminais.

O cliente nao deve inferir terminalidade a partir de `status`, `thought.step`,
`intent.resolved`, heartbeat, fechamento da conexao ou timeout local. A
transicao observavel termina apenas quando um evento terminal persistido pode
ser reproduzido por replay.

| Estado observavel | Entrada aceita | Proximo estado | Invariante |
|---|---|---|---|
| sem turno | `start` novo, com identidade e capacidade validas | processando | reserva o turno e persiste exatamente um evento inicial |
| processando | `status`, `thought.step`, `intent.resolved` | processando | `seq` cresce no `configTransactionManager`; heartbeat nao e persistido |
| processando | `result` | concluido | persiste um unico terminal e conclui a reserva |
| processando | `error`, inclusive timeout | falhou retomavel | persiste um unico terminal; timeout interrompe trabalho tardio e libera/expira a reserva |
| processando | `cancel` | cancelado | persiste `cancelled`, interrompe o trabalho e marca a reserva `CANCELLED` |
| terminal | reconnect com `Last-Event-ID` | mesmo terminal | replay retorna apenas eventos posteriores ao cursor e nunca reexecuta engine, tool ou apply |
| terminal | novo `start` com o mesmo `clientTurnId` e fingerprint | mesmo terminal | devolve `threadId`, `turnId` e `streamId` existentes sem novo processamento |
| qualquer | identidade, token, cursor ou fingerprint divergente | rejeitado | falha antes de executar side effect |

Corridas entre `result`, `error`, `cancelled` e timeout sao resolvidas pelo
marcador terminal e pela sequencia mantidos na linha de `AiTurn`, sob o
`configTransactionManager`. O primeiro terminal persistido vence; tentativas
tardias observam o terminal existente ou recebem conflito e nao materializam um
segundo efeito. Em outra instancia, o event log continua sendo a autoridade e o
poller reconcilia o terminal persistido.

Retomada de transporte significa reconectar/reproduzir o mesmo turno, mantendo
`threadId`, `turnId`, evidencia e budget. Uma nova tentativa semantica depois de
um terminal e um novo turno conversacional e deve receber novo `clientTurnId`;
reutilizar a identidade anterior nunca autoriza reexecucao.

O gate local reproduzivel dessa maquina e:

```bash
tools/local-e2e/run-authoring-turn-state-machine-gate-local.sh
```

Ele certifica, em uma unica matriz, retomada idempotente, replay SSE,
cancelamento, timeout, schema canonico indisponivel com falha fechada,
reconciliacao entre instancias, ownership e ausencia de terminal/side effect
duplicado.

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

`clientTurnId` e uma identidade idempotente do turno. Quando o mesmo
`clientTurnId` resolve para o mesmo `threadId`/`turnId`, o backend so reutiliza
o stream existente se o fingerprint canonico do request bater com o evento
inicial persistido. O fingerprint usa `sha256` sobre os campos que podem alterar
o resultado do authoring, como prompt, alvo, rota, pagina atual,
conversacao/clarificacao/anexos resumidos, `contextHints`, capabilities,
diagnostics seguros, `activeSemanticDecision` e as autoridades server-owned
ordenadas do principal. O schema de fingerprint v2 impede que um retry do mesmo
`clientTurnId` reutilize trabalho iniciado antes de uma concessao ou revogacao de
papel. Ele exclui `apiKey`,
observacoes runtime brutas e `requestBaseUrl`; observacoes de frontend so entram
indiretamente quando o backend as aterra em
`contextHints.groundedRuntimeComponentContext` com campos permitidos. Se o
mesmo `clientTurnId` chegar com prompt, alvo, decisao semantica ou contexto
material diferente, o endpoint `start` responde `409` com razao publica
`agentic-authoring-idempotency-conflict`, em vez de devolver trabalho antigo.

O start tambem executa admissao canônica antes de reservar processamento novo.
Starts idempotentes que encontram o evento inicial persistido nao consomem nova
capacidade. Starts novos podem ser rejeitados com:

- `429 agentic-authoring-stream-capacity-exceeded` quando o limite por tenant ou
  por usuario estiver esgotado;
- `503 agentic-authoring-stream-capacity-exceeded` quando o limite global estiver
  esgotado;
- `503 agentic-authoring-stream-executor-saturated` quando o executor bounded de
  authoring nao aceitar novo trabalho.

Essas rejeicoes sao transientes e devem ser apresentadas como retry seguro pelo
cliente. Quando o trabalho ja iniciou mas o executor rejeita antes de processar,
o backend tenta persistir um evento terminal `error` com `retryable=true`,
`phase=capacity.rejected` e codigo publico estavel. Cancelamento, timeout,
resultado terminal e replay terminal liberam a capacidade exatamente uma vez.

O endpoint de conexao SSE tambem possui limites de protecao: excesso de emitters
por stream responde `429 agentic-authoring-stream-emitter-limit-exceeded`; excesso
de pollers de replay responde `503 agentic-authoring-stream-replay-capacity-exceeded`.
Esses limites nao alteram o envelope `AiTurnEventEnvelope`; eles governam somente
admissao, filas, emissores e pollers do transporte.

`runtimeComponentObservations` e um envelope opcional de evidencia de runtime
enviado por componentes do cockpit. O backend aceita essa evidencia somente com
`runtimeComponentObservationTrustBoundary=untrusted_frontend_observation`, copia
apenas campos permitidos para `contextHints.groundedRuntimeComponentContext`
(`GroundedRuntimeComponentContext`) e nunca promove observacoes de frontend a
capabilities, permissoes ou execucao de actions. Dados crus de linhas, valores
completos, `sampleRows`, `rawRows`, `dataSource`, segredos e decisoes de intencao
nao fazem parte do contexto aterrado.
O bloco allowlisted `affordances.visualMaterialization` pode declarar
`inlineStyle=supported|blocked|unknown`, `governedClass=supported|unknown` e refs
de restricao seguras. Para superficies condicionais de tabela, ele tambem pode
publicar `surfacePresetCatalog` com o ID `praxis-table.conditional-surface`, a
versao instalada, o `themeRef`, o modo de tema observado e as listas allowlisted
de escopos e presets. O authoring usa a referencia semantica fechada
`surfacePresetRef={id,catalogVersion}`; nomes de classes CSS permanecem privados
do runtime. O catalogo `0.2.0` admite `success`, `warning`, `danger` e `highlight`
em `row` e `cell`; `muted` foi excluido porque opacidade composta nao preserva
uma garantia deterministica de contraste. `high-contrast` e observado, mas nao
e certificado por essa versao e portanto falha fechado no preview.
Essa evidencia serve apenas para restringir a
materializacao: `blocked` impede preview/apply que dependa de estilos inline e
`unknown` tambem falha fechado como materializacao nao verificada. Nonces, headers CSP e strings de politica
arbitrarias nunca sao copiados. A capability nao e persistida no config do
componente e nao concede autorizacao.
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
| `status` | `state`, `phase`, `message`, `summary`, `diagnostics` seguro quando for progresso persistido |
| `thought.step` | `phase`, `tool`, `message` user-facing quando houver texto curado, `summary` seguro/auditavel, `diagnostics` seguro, `streamEventDiagnostics` quando houver dedupe auditavel |
| `heartbeat` | metadados de keep-alive |
| `intent.resolved` | `schemaVersion`, `semanticDecisionRef`, `routeClass`, `resolved`, `userFacingUnderstanding`, `requiresClarification`, `canMaterialize`, `fallbackKind`, `requiredTools`, `evidenceRefs`, `confidence`, `warnings` |
| `result` | `intentResolution`, `preview`, `assistantMessage`, `quickReplies`, `canApply`, `decisionDiagnostics`, `streamEventDiagnostics` quando houver dedupe auditavel |
| `error` | `code`, `assistantMessage`, `message`, `phase` |
| `cancelled` | `message`, `phase` |

`intent.resolved` e um evento persistido, replay-safe e nao terminal. Ele deve
alimentar a UI com a interpretacao segura da intencao do usuario, por exemplo
`userFacingUnderstanding`, sem expor chain-of-thought nem autorizar aplicacao.
Clientes devem continuar aguardando `result`, `error` ou `cancelled` para
encerrar o estado de processamento.

Em eventos de progresso, `message` e o texto curado para apresentacao ao usuario.
Ele pode ser produzido diretamente pela LLM quando a fase ja tiver resolvido uma
intencao semanticamente apresentavel, ou por uma projecao backend-authored
baseada na decisao/ferramenta governada. `summary` permanece uma trilha segura
para auditoria, replay e fallback de clientes antigos; ele nao deve ser tratado
como fonte primaria de UX quando `message` ou `userFacingUnderstanding`
estiverem presentes. O texto bruto do usuario permanece no historico do turno;
a UI deve preferir a intencao curada em `intent.resolved.userFacingUnderstanding`
e mensagens de progresso curadas nos eventos seguintes.

Quando o turno seguir para resposta consultiva ou preview, o backend pode
preservar a mesma decisao em `contextHints.resolvedIntent`
(`schemaVersion=praxis-agentic-authoring-resolved-intent-context.v1`). Esse
hint e backend-authored, replay/round-trip safe e serve para continuidade da
rota ja resolvida, especialmente `explore/api_catalog/answer_api_catalog_question`.
Clientes nao devem construir esse envelope localmente nem trata-lo como
autorizacao para leitura, preview ou aplicacao; qualquer acao continua exigindo
reconciliacao backend contra catalogo, runtime e contexto governado atuais.

Durante turnos longos, especialmente quando a LLM esta resolvendo intencao ou
revisando recursos recuperados por RAG/catalogos governados, o backend deve
emitir fases conversacionais suficientes para evitar uma UI parada em um unico
estado generico. As fases canonicas atuais de `thought.step` incluem:

- `context.bundle`: contexto do turno recebido e normalizado.
- `runtime.context.grounding`: observacoes runtime aterradas como evidencia nao
  confiavel, com contagens, claims aceitas/rejeitadas, superficies e refs
  seguros.
- `intent.resolve`: preparacao da resolucao semantica.
- `component.capabilities`: carregamento das capacidades governadas dos
  componentes antes da confirmacao semantica e materializacao.
- `intent.resolve.llm`: chamada ou revisao da LLM sobre a intencao do usuario.
- `intent.resolve.grounding`: checagem da decisao contra evidencias governadas.
- `resource.discovery`: recuperacao de recursos, schemas, capabilities ou
  catalogos backend.
- `projectKnowledge.retrieve`: recuperacao de Project Knowledge/RAG governado.
- `preview.plan`: planejamento da materializacao governada.
- `preview.compile`: compilacao ou reparo da preview materializada.

Project Knowledge segue uma sequencia progressiva. O pack macro pode ser lido
antes da orientacao semantica; depois que `resource.discovery` retorna candidatos
governados, o backend consulta Project Knowledge separadamente para os escopos
canonicos desses candidatos, sem promover nenhum deles a `selectedCandidate`.
Se a decisao terminar em clarificacao antes de materializar preview, o resultado
terminal ainda inclui `preview.diagnostics.projectKnowledgeAudit` para auditar o
conhecimento consultado. Nesse caso as entradas sao `cited=false`, porque consulta
nao equivale a influencia materializada. Somente `sourceRefs=projectKnowledge:*`
emitidos pelo plano ou patch podem marcar uma entrada como citada.

O ranking vetorial permanece derivado: hits vetoriais e o pool estruturado sao
mesclados antes do limite final. O limite de projecoes e aplicado apenas depois
que `AgenticAuthoringProjectKnowledgeService` revalida lifecycle, curation,
`aiVisibility`, tenant, environment, escopo, kind e evidencia ativa. Portanto,
um hit stale, fora de escopo ou sem evidencia ativa nao pode consumir sozinho a
janela final nem ocultar um candidato canonico valido.

`heartbeat` e out-of-band, nao persistido no event log, e deve carregar pelo
menos `state=alive`, `phase`, `summary` e `lastEventType`. O `phase` deve
refletir o ultimo evento nao terminal conhecido, permitindo que clientes mostrem
mensagens como "a LLM ainda esta resolvendo a intencao" sem inventar logica
local ou depender de timers opacos no frontend.

No produtor local ativo, checagens internas de terminalidade e o watchdog usam a
projecao em memoria do ultimo evento que ja foi confirmado pelo event store. A
reconciliacao com o tail persistido e limitada a uma janela periodica de cinco
segundos e tambem ocorre no heartbeat. Isso evita que cada etapa semantica dispute
uma conexao do config-store remoto, sem enfraquecer a autoridade do banco: cada
append continua atomico, um terminal concorrente continua rejeitando qualquer
append posterior e cancelamentos de outra instancia sao observados na
reconciliacao periodica.

Além do `heartbeat`, o backend emite progresso persistido (`status`) durante
turnos ainda em processamento. O intervalo padrao de
`praxis.ai.authoring.stream.processing-progress-seconds` e `8s`. Esses eventos
devem ter `message` curado para UX e `diagnostics.source` igual a
`backend-processing-progress-watchdog`; diagnosticos permanecem auditaveis, mas
nao devem ser exibidos como texto conversacional. A mensagem deve explicar a
fase atual em linguagem de produto, por exemplo planejamento de busca governada,
revisao da LLM, validacao de dados confirmados ou compilacao da preview.

O processamento assincrono do turno deve respeitar
`praxis.ai.stream.processing-timeout-seconds` para evitar que o cliente fique
preso em estados intermediarios quando retrieval, provider LLM ou compilacao de
preview nao concluem. O default da plataforma e `360s`, porque turnos reais de
authoring podem envolver discovery, RAG, multiplas chamadas LLM e materializacao
no mesmo ciclo. Smokes e hosts podem reduzir esse valor explicitamente quando
usarem doubles deterministas. Ao estourar esse limite, o backend emite `error`
terminal com `code=agentic-authoring-timeout` e expira a reserva do turno.

A resolucao semantica de intencao tem timeouts proprios, menores que o timeout
global do turno, para impedir que a conversa fique aguardando uma classificacao
LLM lenta antes de conseguir emitir clarificacao ou diagnostico seguro. O passe
compacto usa `praxis.ai.authoring.intent-resolution.fast-timeout-seconds`
(`PRAXIS_AI_AUTHORING_INTENT_RESOLUTION_FAST_TIMEOUT_SECONDS`, default `12s`) e
o passe completo usa
`praxis.ai.authoring.intent-resolution.full-timeout-seconds`
(`PRAXIS_AI_AUTHORING_INTENT_RESOLUTION_FULL_TIMEOUT_SECONDS`, default `30s`).

Separadamente, o planejador pre-intent OpenAI usa
`praxis.ai.authoring.pre-intent.openai-model` (default `gpt-5.6-luna`) para manter
classificacao e planejamento estruturado em uma classe de custo/latencia separada do modelo geral
de autoria. A configuracao nao altera o modelo de providers nao OpenAI nem o modelo usado nas fases
posteriores do turno.

O refinamento semantico de valores atuais de option sources usa a mesma separacao de responsabilidade:
`praxis.ai.authoring.intent-resolution.live-option.openai-model` (default `gpt-5.6-luna`) executa
somente a classificacao estruturada dos candidatos vivos depois que recurso e campo canonicos ja foram
resolvidos. O endpoint `byIds` continua confirmando a selecao antes da materializacao; providers nao
OpenAI preservam o modelo solicitado pelo turno.

O passe pre-intent recebe como baseline somente a projecao compacta das identidades canonicas de
recursos do tenant/ambiente. A selecao das releases atuais continua pertencendo ao Domain Catalog,
mas a leitura multi-release e consolidada em uma unica consulta de itens; ela nao executa uma
consulta remota por recurso. Contexto detalhado e incorporado nessa fase apenas quando existe escopo
de negocio real, como `resourceKey`, `contextKey`, `query` ou `nodeType`. Um envelope amplo contendo
apenas `serviceKey`, disponibilidade ou modo do catalogo nao autoriza varredura detalhada.

Essa projecao compacta e reutilizada por uma cache interna, limitada e escopada por `tenantId`,
`environment`, `serviceKey` e limite de itens. Ela armazena somente o texto derivado da release
canonica, nunca substitui o Domain Catalog e nao faz cache negativo. O TTL default e cinco minutos
(`praxis.domain-catalog.prompt-context.resource-identity-cache-ttl-ms`) e o limite default e 256
entradas (`praxis.domain-catalog.prompt-context.resource-identity-cache-max-entries`). Uma ingestao
que altera a projecao publica evento transacional e invalida, depois do commit, apenas o
tenant/ambiente afetado. As metricas `domain_catalog_prompt_context_cache_total` distinguem
`hit`, `miss` e `invalidated`.

O host Page Builder deve enviar esse escopo amplo como `status=deferred` e
`retrievalPolicy=progressive-after-semantic-orientation`; nao deve carregar `domain-360` de todo o
host ao abrir uma tela vazia. Depois da orientacao semantica, o backend solicita progressivamente
`discoverDomainContexts`, `discoverDomainCapabilities`, `discoverDomainConcepts`,
`inspectDomainBindings`, `verifyDomainOperation` ou `searchApiResources`. Isso preserva grounding
governado sem transformar abertura de tela ou primeiro token em uma varredura do catalogo inteiro.
Bindings aprovados reforcam e habilitam verificacao operacional exata, mas sua ausencia durante a
adocao progressiva do Semantic IR nao apaga candidatos ja governados por Domain Catalog, API Metadata
e schema. Nesse caso `searchApiResources` continua a descoberta e preserva a proveniencia efetiva;
materializacao segue sujeita aos gates de elegibilidade, schema, capability, preview e revisao.

O contrato estruturado `praxis-agentic-authoring-pre-intent-tool-plan.v3` projeta tambem o
`layoutKind` canonico para os quatro arquetipos compactos de recurso: `single-table`,
`resource-master-detail`, `parent-child-related-resource` e `resource-crud`. Layout, tipo de artefato e componente primario sao
decisoes semanticas independentes, mas o trio precisa ser coerente: tabela simples usa
`artifactKind=table` com `praxis-table`, master-detail usa `praxis-table`, parent-child usa
`artifactKind=page` com `praxis-related-resource-outlet` e uma `targetSurfaceId` exata, enquanto um unico host
CRUD usa `praxis-crud`; pares divergentes sao rejeitados pelo plano estruturado. Quando
`single-table` preserva integralmente o pedido, `requiresFullIntentResolution=false` evita um segundo
passe LLM sem relaxar grounding ou apply. A orientacao encaminhada ao resolver usa
`praxis-agentic-authoring-pre-intent-orientation-context.v2`. A presenca de actions governadas nao
decide o layout; metadata e capabilities continuam sendo a fonte exclusiva da descoberta de comandos.
Quando o passe compacto focal retorna `resolved=false`, ele nao encerra a decisao: o resolver executa
uma unica fase `intent_full` e preserva no mesmo `providerInvocations` a sequencia
`intent_fast` -> `intent_full`, distinguindo fallback semantico de retry do provider pelo nome da fase.
Se o passe completo tambem ficar inconclusivo, a resolucao permanece `unknown` e fail-closed; o plano
pre-intent nao e promovido deterministicamente a uma decisao.
Todo plano de autoria que exija resolucao completa ou entre em grounding de recurso (`api_resource`,
`domain_binding` ou `operation_verification`) precisa declarar
`resourceSearchFocus.primaryBusinessEntity`, inclusive quando `layoutKind` ja preservar toda a
semantica visual e `requiresFullIntentResolution=false`. Perfis progressivos de contexto, capacidade,
conceito e decisao sem resolucao completa podem ainda estar descobrindo esse alvo.
O campo identifica o assunto canonico para grounding; nao autoriza a operacao nem substitui bindings,
schema ou capabilities.

O runner production-like pode coletar, apenas em `result.json` de uma execucao falha, attachments
`praxis.page-builder.governed-state-projection/v1` declarados na matriz canonica. O collector valida
shape fechado, limites, IDs/tokens e a whitelist de `canonicalAction`; prompts, `contextHints`, paths
ou payloads livres sao rejeitados. Execucoes bem-sucedidas mantem `diagnosticEvidence=[]`, e o exporter
de evidencia certificada tambem exige essa colecao vazia.

### OpenAI Light reasoning profile

Chamadas compactas com os modelos econômicos listados em
`praxis.ai.openai.light-reasoning-models` enviam `reasoning.effort=low` pela Responses API. O default
governado inclui `gpt-5.6-luna` e `gpt-5.6-terra`, inclusive IDs versionados desses modelos, e pode
ser substituído por `PRAXIS_AI_OPENAI_LIGHT_REASONING_MODELS`. Modelos GPT-5 fora dessa lista
preservam a política de compatibilidade já existente; a seleção do perfil não altera roteamento de
intenção, limites de tokens, gates semânticos ou autorização de materialização.

### OpenAI hosted skills

Chamadas OpenAI pertencentes ao authoring agentico carregam o perfil interno
`AGENTIC_AUTHORING`. Quando o host configura referencias revisadas em
`praxis.ai.openai.hosted-skills.agentic-authoring[*]`, o adapter oficial da Responses API monta um
`shell` com ambiente `container_auto` e anexa essas referencias. Chamadas de catalogo, teste de
conexao e geracao fora desse perfil permanecem sem hosted shell.

Cada `id` pertence a conta OpenAI do host/cliente e deve ser fornecido por ambiente, por exemplo
`PRAXIS_AI_OPENAI_SKILL_COORDINATOR_ID` e
`PRAXIS_AI_OPENAI_SKILL_DOMAIN_GROUNDING_ID`. O conjunto configurável também contempla
`PRAXIS_AI_OPENAI_SKILL_TABLE_AUTHORING_ID`, `PRAXIS_AI_OPENAI_SKILL_FORM_AUTHORING_ID`,
`PRAXIS_AI_OPENAI_SKILL_DASHBOARD_AUTHORING_ID`,
`PRAXIS_AI_OPENAI_SKILL_CURRENT_ARTIFACT_UNDERSTANDING_ID` e
`PRAXIS_AI_OPENAI_SKILL_MULTI_TURN_REFINEMENT_ID`. Versoes devem ser fixadas nos ambientes de teste e
producao; `latest` serve apenas para desenvolvimento controlado. IDs ausentes sao ignorados e nao
ativam um caminho alternativo. O modelo configurado precisa suportar Skills e Hosted Shell.

Skills sao instrucoes aprovadas pelo desenvolvedor, nao plugins escolhidos pelo usuario final. Elas
nao substituem as tools Praxis de dominio, metadata, manifests, materializacao ou validacao, nao
recebem credenciais do cliente e nao autorizam apply. A selecao final continua semantica e todos os
gates backend de evidencia, preview, autorizacao e persistencia permanecem obrigatorios.

Perguntas que enumeram os dominios, temas ou assuntos de negocio efetivamente disponiveis usam a
classe semantica interna `governed_domain_discovery`. Ela nao e orientacao generica nem autoria de
um artefato ainda: seleciona primeiro `discoverDomainContexts`. Enumeracoes sem `contextKey` ou
`resourceKey` consultam deterministicamente o repositorio canonico e priorizam conhecimento macro
sem `resourceKey`; ranking vetorial fica reservado a consultas semanticamente delimitadas. A
telemetria `pre_intent_tool_plan.model` registra o modelo efetivamente selecionado para essa fase,
inclusive quando ele difere do modelo geral solicitado pelo turno.

Esses limites governam apenas a chamada do provider para decidir intencao; eles
nao encerram o stream por si so. Quando o provider falha ou estoura timeout, o
backend deve materializar uma resolucao nao aplicada, com warnings como
`llm-intent-resolution-failed`, `llm-provider-error` e `llm-provider-timeout`,
preservando terminalidade normal do turno. Essa falha tambem e uma fronteira
fail-closed de decisao primaria: o backend nao deve promover fallback lexical
ou keyword para selecionar recurso, nao deve gerar preview e nao deve aplicar
materializacao. O turno deve terminar com `result` clarificativo
`canApply=false`, `gate.status=clarification_required`,
`operationKind=unknown`, `artifactKind=unknown`, `changeKind=provider_error`,
`selectedCandidate=null` e texto user-facing que confirme se o usuario quer
consultar dados, criar tabela/formulario/grafico/painel ou seguir outra rota
canonica.

Erros terminais devem separar texto de usuario e diagnostico tecnico. `code`
deve ser estavel para i18n e tratamento no cliente; `assistantMessage` deve ser
seguro para exibir na conversa; `message` pode conter detalhe tecnico para
diagnostico restrito. Falhas inesperadas de processamento usam
`code=agentic-authoring-processing-failed`.

### Descoberta consultiva de decisoes governadas

Antes de existir uma selecao exata do Policy Studio, o planner LLM pode escolher
`groundingProfile=domain_decision` e executar a tool read-only
`searchDomainRules`. A escolha e semantica e authorada pelo modelo; query e
filtros textuais apenas ranqueiam candidatos depois dessa decisao e nunca roteiam
a intencao primaria.

A tool exige a autoridade server-issued `RULE_DEFINITION_READER`, usa somente o
tenant e environment resolvidos pelo backend e limita a resposta a 12 candidatos
por pagina. O envelope `praxis-domain-rule-search.v1` publica apenas identidade e
contexto seguro (`definitionId`, `ruleKey`, versao, tipo, status, chaves
semanticas, owner semantico e `updatedAt`). Condicao, governanca, facts, steward, rationale,
atores e payloads materializados nao fazem parte da busca. O resultado fornece
candidatos para selecao humana; nao seleciona alvo, nao explica regra e nao
autoriza edicao, publicacao ou ativacao. O turno termina consultivamente com
`routeClass=advisory_authoring`, `canApply=false` e
`evidenceBundle.source=searchDomainRules`. A projecao segura permanece em
`evidenceBundle.domainRuleSearch`, e cada quick reply `kind=domain-decision`
transporta `contextHints.selectedDomainDecisionRef` com `definitionId`, `ruleKey`,
`version` e `source=policy-studio-selection`. Ao escolher um candidato, o cliente
deve iniciar o proximo turno com essa referencia estruturada; labels e prompts da
quick reply continuam sendo apenas apresentacao e nunca autoridade.

Em corporate mode as autoridades sao capturadas do request autenticado; no modo
local a mesma autoridade read-only e emitida pelo resolver backend. O token SSE
continua sendo apenas credencial de transporte e nao transporta nem concede nova
autoridade para tools.

### Explicacao consultiva de decisao governada

Quando o Policy Studio envia `contextHints.selectedDomainDecisionRef` com o
contrato `praxis.ai.context-hints.domain-decision/v1`, o resolvedor LLM pode
produzir `explain/domain_decision/explain_domain_decision`. A referencia e
somente um hint de selecao: em corporate mode o controller exige
`RULE_DEFINITION_READER` antes de enfileirar o turno, e a tool read-only
`inspectDomainDecision` rele `definitionId` e reconcilia `ruleKey`, versao,
tenant e environment no Config.

Os dois estagios semanticos recebem essa referencia compacta. O planner inicial
deve encaminhar a explicacao ao resolvedor completo sem buscar recursos API
genericos; o resolvedor recebe ID, rule key, versao e source no contexto do
provider e continua sendo o autor da classificacao primaria. Isso evita tanto
roteamento por palavras-chave quanto o desvio da decisao para authoring de
pagina/recurso. Quando o planner classifica o turno como `authoring_or_other`,
a presenca da referencia canonica tambem suprime qualquer busca API generica
proposta nessa etapa e exige resolucao completa; esse guard governa apenas a
selecao de tools, nao decide a intencao do usuario.

O resultado termina com `canApply=false` e pode carregar
`evidenceBundle.domainDecision`, contendo hashes, contexto semantico,
operadores/fact paths permitidos, timeline segura, materializacoes sem payload,
source refs, atestado de versao e a politica de redacao aplicada. O fluxo nao
chama `domain-rules/simulations`, porque essa superficie registra eventos e nao
e uma leitura pura. `governance.aiUsage` governa o envio ao provider: ausencia
usa `summary_only`; `visibility=deny` ou `reasoningUse=deny` gera resposta
deterministica sem chamada LLM. Runtime facts, tenant, atores, rationale de
workspace e payload materializado nunca entram na projecao.

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
- `fallbackPolicy`, hoje `semantic_intent_required` quando telemetry de
  resolucao existir;
- `keywordFallbackApplied`, mantido como compatibilidade diagnostica e esperado
  como `false` no caminho primario de resolucao;
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
- Quando a decisao pedir `layoutKind=single-table`, a materializacao derivada deve usar
  `layoutPreset=single-table-page`, canvas/device layouts explicitos e exatamente um `praxis-table`
  vinculado ao recurso canonico. `resource-master-detail` deve resolver o blueprint Core
  `layoutPreset=master-detail-dashboard`; `parent-child-related-resource` usa o mesmo blueprint
  estrutural, mas exige o outlet e `relatedResourceGrounding.status=verified` pela consulta
  principal-aware a `/schemas/surfaces`; `resource-crud` permanece no blueprint explicito de um
  unico host CRUD. Divergencia retorna
  `failureCodes=["semantic-preview-layout-required"]`,
  `reviewReason=semantic-preview-materialization-mismatch` e `canApply=false`.
- `keywordFallbackApplied=true`, quando recebido de payload legado ou fixture
  de compatibilidade, deve forcar `decisionDiagnostics.requiresReview=true`,
  `reviewReason=keyword-fallback-fail-safe` e `canApply=false`. O resolver
  primario nao deve produzir essa condicao como politica suportada.
- Quando a LLM selecionar um recurso abaixo do candidato governado mais forte
  recuperado, sem o prompt nomear explicitamente esse recurso escolhido, a
  decisao deve retornar `reviewReason=llm-selection-lower-ranked-than-governed-candidate`
  e `canApply=false`. Isso preserva a LLM como autora da escolha, mas impede
  materializacao automatica quando a evidencia governada ranqueada contradiz a
  selecao.
- Quando a selecao final escolher uma projecao analitica/perfil para uma
  necessidade operacional generica enquanto existe candidato operacional
  governado no conjunto, a decisao deve retornar
  `reviewReason=resource-selection-role-mismatch-with-governed-candidate` e
  `canApply=false`. Esse bloqueio usa apenas roles/evidencias canonicas ja
  recuperadas; nao introduz aliases de dominio nem roteamento por sinonimos.
- Quando um prompt narrativo e aberto resultar em recurso selecionado sem
  ancoragem explicita no texto e com confianca moderada, a decisao deve
  retornar `reviewReason=resource-selection-unanchored-low-confidence` e
  `canApply=false`. Esse bloqueio preserva a recuperacao semantica como
  evidencia, mas evita aplicar automaticamente uma faceta estreita quando a
  intencao de colecao operacional ainda nao esta confirmada.
- Uma `visualizationDecision` de `single_chart` so deve materializar um
  `artifactKind=chart` quando o prompt carregar intencao analitica ou visual
  explicita, como grafico, indicador, metrica, comparacao, ranking ou agregacao.
  Para pedidos operacionais abertos, o backend deve preservar a decisao como
  pagina/dashboard operacional e emitir
  `llm-single-chart-decision-requires-explicit-analytical-intent`.
- Quando a LLM selecionar um candidato fraco ou amplo abaixo de um candidato
  operacional governado mais forte, sem ancoragem explicita no prompt, o backend
  pode normalizar a selecao para o candidato governado e emitir
  `llm-resource-selection-overridden-by-governed-ranking`. Esse caso nao deve
  forcar review, porque a decisao final foi corrigida por evidencia recuperada
  e ranqueada, nao por alias textual de dominio.
- Quando a LLM classificar como `api_catalog` um pedido que continua sendo de
  criacao de superficie operacional e ja possui candidatos governados, o backend
  deve normalizar para `page/create_artifact` e emitir
  `llm-api-catalog-authoring-drift-normalized`.
- Quando o provider falhar depois de `searchApiResources` ja ter retornado
  candidatos governados fortes para um pedido de authoring de superficie de
  negocio, o backend pode recuperar a rota materializavel usando essa evidencia
  e emitir `llm-provider-failure-recovered-by-grounded-candidates`. Esse caminho
  nao deve reaplicar warnings de fail-safe/clarificacao como se a evidencia
  tivesse desaparecido.
- `page-apply` deve exigir `semanticDecision`, `streamId` e `resultEventId`;
  aplicar apenas `compiledFormPatch` sem decisao canonica e sem referencia ao
  evento terminal persistido e um bypass de contrato.
- Antes de persistir, `page-apply` deve revalidar ownership do stream,
  tenant/usuario/ambiente, expiracao, tipo terminal `result`, `canApply=true` e
  igualdade exata da decisao semantica e do patch compilado emitidos naquele
  evento. A materializacao deve preservar `stream/thread/turn/event/decision`
  como tags auditaveis, sem payload sensivel.
- O evento terminal so pode publicar `canApply=true` quando a preview possuir
  `compiledFormPatch.patch.page`. Quando a materializacao partir de um
  `uiCompositionPlan`, o Config deve compilar o plano antes do resultado
  terminal e preservar ambos no mesmo payload auditavel; a ausencia do plano
  nao invalida, por si so, um patch de pagina completo produzido por outro
  fluxo governado. O diagnostico `terminalPreviewApplyEligible=false` deve
  expor `terminalPreviewApplyBlockReason` quando o patch terminal estiver
  incompleto.
- O consumidor pode projetar localmente uma preview incompleta para revisao,
  mas nao pode regenera-la e reutilizar `resultEventId` do resultado anterior.
  Uma materializacao diferente exige um novo evento terminal backend-owned.
- Um resultado terminal aplicavel tambem publica `applyTarget` com
  `componentType`, `componentId`, `scope`, ambiente backend-owned e o modo de
  concorrencia. `mode=create` exige ausencia da configuracao; `mode=update`
  exige que o `If-Match` coincida com o `baseEtag` atestado. `page-apply`
  rejeita destino, escopo, ambiente ou ETag diferentes, e um segundo uso do
  mesmo evento falha pela precondicao ja consumida.
- `contextHints.agenticApplyTarget` pertence exclusivamente ao request do turn
  stream e nao faz parte dos hints compartilhados por intent/plan/preview. Ele e
  metadado de transporte: o engine o
  remove antes de discovery, planejamento ou qualquer chamada a LLM.
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
- Edicoes multi-turno de um componente existente nao dependem de selecao ativa
  enviada pela UI. Quando a resolucao semantica produzir `operationKind=modify`
  e um `target` com `widgetKey` e `componentId` reconciliaveis com
  `currentPage`, o backend deve recuperar o manifest server-owned daquele
  componente e compilar a operacao canonica. `uiCompositionPlan` permanece
  reservado para criacao ou recomposicao de superficie, nao para substituir
  uma edicao suportada pelo manifest existente.
- Operacoes canonicas como `filter.advanced.fields.add` e
  `filter.advanced.fields.remove` sao transicoes sobre o estado materializado:
  devem adicionar ou remover somente os campos resolvidos e preservar os
  demais filtros e configuracoes. O prompt nao e reinterpretado pelo compiler;
  a operacao semantica resolvida governa a mutacao. Um plano que desabilite ao
  mesmo tempo a transicao de `selectedFieldIds` e de `alwaysVisibleFields` e um
  no-op contraditorio e deve falhar antes de publicar `canApply=true`.
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

### Workspace rico orientado a recurso

Para criacao de pagina sobre um recurso semanticamente selecionado, o provider
generico consome uma projecao interna `contextHints.verifiedDomainOperations`.
Ingressos HTTP removem qualquer valor fornecido pelo cliente, mesmo que ele copie
as strings esperadas de schema e source. Somente o engine de streaming pode
reinserir o envelope a partir de `OperationProjection` produzida pelo backend
apos a selecao semantica do recurso e a execucao da tool canonica
`verifyDomainOperation`, que valida schema, capabilities e o action catalog no escopo do principal.
O recurso precisa estar ancorado por conceitos e bindings `approved` com evidencia ativa. A ingestao
`generated` do Domain Catalog permanece uma projecao derivada e nao vira autoridade operacional ate
ser promovida pelo lifecycle canonico de Domain Knowledge. Uma action escolhida semanticamente pela
IA continua exigindo seu binding governado; o discovery generico da Table, depois que o recurso foi
aceito, vem diretamente de `/schemas/actions?resource=...` e nao exige promover cada action derivada.
Quando existir binding `workflow_action`, sua identidade e preservada de `binding.payload.target.id`;
metodo e path continuam sendo evidencias de reconciliacao e nao podem reduzir a action nativa a um
`create` generico.
Essa verificacao pos-intent fecha a cadeia no mesmo turno mesmo quando o passe
pre-intent precisou usar sua unica leitura para descobrir o recurso. `page-preview` direto
sem esse re-grounding bloqueia discovery de comandos e o gate semantico retorna
`semantic-preview-resource-workspace-grounding-required`; um blueprint master-detail
nao pode ser publicado como preview aplicavel com grounding `unavailable` ou `rejected`.

O primeiro slice materializa Filter, Table master e Dynamic Form detail. Os links
canonicos sao `requestSearch -> queryContext` e
`selectionChange -> state.selectedItem -> initialValue`. Desktop usa composicao
7/5 com filtro superior; tablet e mobile usam variantes empilhadas. O filtro
continua responsavel por schema e option sources metadata-driven, e os widgets
continuam responsaveis por loading, vazio e erro.

Comandos nao sao convertidos em `api.post` ou `api.patch` pelo Java. O envelope interno
`praxis-agentic-authoring-verified-domain-operations.v2` separa operacoes autorizadas por
resource capabilities de actions estruturalmente publicadas em `/schemas/actions`. Para cada
action backend-owned reconciliada por `id + resourceKey + scope + path + method` e schema exato,
o plano habilita apenas o discovery oficial: `ITEM` exige
`availability.reason=resource-context-required` no catalogo sem ID e declara
`availabilityResolution=item-capabilities-at-selection`; `COLLECTION` exige availability atual
permitida. Nenhum desses casos publica endpoint ou botao sintetico. O runtime
resolve a disponibilidade ITEM real em `/{id}/capabilities` ou pelos links HATEOAS, abre a
superficie canonica de Dynamic Form e executa o submit governado. Alem dos specs focais de
allow/deny/open/execute, o Fluxo 3 real HTTP/browser foi executado em modo `full` focal em 2026-08-30:
authoring LLM real gerou `master-detail-dashboard`, `page-apply` persistiu o patch terminal exato,
o browser descobriu action e capabilities de item, carregou o request schema, executou
`POST /api/operations/missoes/{id}/actions/start` com `200`, recebeu
`409 CONFLICT_DEPENDENCY` na repeticao, observou refresh por `/filter` e recarregou o mesmo payload
SHA-256 e ETag. Essa evidencia prova operacionalmente o piloto Fluxo 3; nao equivale a uma execucao da matriz
production-like completa. Envelope ausente, forjado, divergente ou sem comando produz diagnostics
e deixa ambos os scopes desabilitados.

Preview e compilacao seguem o endpoint existente de `page-preview`. Persistencia
segue `page-apply`, o resultado terminal emitido pelo servidor e `If-Match`; uma
tentativa com ETag obsoleto falha antes de alterar a configuracao vencedora. Nao
existe DTO ou endpoint paralelo de workspace neste slice.

## Regras para o Page Builder

O Page Builder deve:

- manter o fluxo sincrono atual como fallback;
- usar streaming apenas quando o backend anunciar suporte ou quando o host habilitar
  explicitamente essa capacidade;
- apresentar `message`/`userFacingUnderstanding` como texto conversacional curado
  e manter payloads de diagnostico apenas como apoio auditavel;
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
  `compiledFormPatch`, `streamId` e `resultEventId` recebidos no envelope
  terminal, para que o backend rejeite materializacoes que nao cumpram a
  decisao canonica authorada ou que nao derivem do resultado revisado.

## Evidencia historica de validacao ponta a ponta

Em 2026-04-23, o fluxo full local foi validado com a versao entao vigente do
runner:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\tools\Invoke-PbAgenticFullE2E.ps1 `
  -Provider openai `
  -QuickstartRoot ..\praxis-api-quickstart `
  -UiRoot ..\praxis-ui-angular
```

Resultado:

- `praxis-api-quickstart` subiu com `PRAXIS_AI_STREAM_AUTH_MODE=signed-url-token`;
- `praxis-ui-angular` subiu em `http://localhost:4003`;
- o Playwright executou a config de validacao disponivel naquele momento;
- os fluxos de dashboard de pagamentos e formulario de funcionarios passaram usando browser real, backend SSE real e provider OpenAI real;
- total: `3 passed`.

Essa contagem e historica e nao descreve a matriz atual. Desde o gate
`praxis.page-builder-agentic-gate-matrix/v1`, evidencia production-like exige a
config `praxis-page-builder-agentic-production-like.playwright.config.ts`,
auditoria estatica que le os servicos reais, `criticalEndpointMocks=0`,
capabilities `source=registry`/`degraded=false`, SHAs imutaveis, PostgreSQL e
pgvector reais, provider/embeddings reais, bind loopback e cleanup comprovado.
Testes deterministas da config `mocked` nao entram nessa contagem.

## Fora de escopo

Esta decisao nao muda os endpoints sincronos existentes. Integracao do Page Builder
Angular com SSE, UI de progresso e retry/cancelamento no cliente permanecem fora
deste primeiro incremento de backend.
