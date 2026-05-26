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
- `provider`, `model` e `apiKey` quando aplicavel

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
| `thought.step` | `phase`, `tool`, `summary`, `diagnostics` seguro |
| `heartbeat` | metadados de keep-alive |
| `result` | `intentResolution`, `preview`, `assistantMessage`, `quickReplies`, `canApply`, `decisionDiagnostics` |
| `error` | `code`, `assistantMessage`, `message`, `phase` |
| `cancelled` | `message`, `phase` |

Durante turnos longos, especialmente quando a LLM esta resolvendo intencao ou
revisando recursos recuperados por RAG/catalogos governados, o backend deve
emitir fases conversacionais suficientes para evitar uma UI parada em um unico
estado generico. As fases canonicas atuais de `thought.step` incluem:

- `context.bundle`: contexto do turno recebido e normalizado.
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
