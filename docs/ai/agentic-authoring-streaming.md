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
| `result` | `intentResolution`, `preview`, `assistantMessage`, `quickReplies`, `canApply` |
| `error` | `code`, `assistantMessage`, `message`, `phase` |
| `cancelled` | `message`, `phase` |

O processamento assincrono do turno deve respeitar
`praxis.ai.stream.processing-timeout-seconds` para evitar que o cliente fique
preso em estados intermediarios quando retrieval, provider LLM ou compilacao de
preview nao concluem. O default da plataforma e `180s`, porque turnos reais de
authoring podem envolver discovery, RAG, chamada LLM e compilacao de preview no
mesmo ciclo. Smokes e hosts podem reduzir esse valor explicitamente quando
usarem doubles deterministas. Ao estourar esse limite, o backend emite `error`
terminal com `code=agentic-authoring-timeout` e expira a reserva do turno.

Erros terminais devem separar texto de usuario e diagnostico tecnico. `code`
deve ser estavel para i18n e tratamento no cliente; `assistantMessage` deve ser
seguro para exibir na conversa; `message` pode conter detalhe tecnico para
diagnostico restrito. Falhas inesperadas de processamento usam
`code=agentic-authoring-processing-failed`.

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
- tratar `result` como a unica fonte para aplicar preview local.

## Fora de escopo

Esta decisao nao muda os endpoints sincronos existentes. Integracao do Page Builder
Angular com SSE, UI de progresso e retry/cancelamento no cliente permanecem fora
deste primeiro incremento de backend.
