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

## Eventos recomendados

Os eventos devem usar os tipos existentes sempre que possivel:

| Tipo | Payload recomendado |
|------|---------------------|
| `status` | `state`, `phase`, `message` |
| `thought.step` | `phase`, `tool`, `summary`, `diagnostics` seguro |
| `heartbeat` | metadados de keep-alive |
| `result` | `intentResolution`, `preview`, `assistantMessage`, `quickReplies`, `canApply` |
| `error` | `message`, `code`, `phase` |
| `cancelled` | `message`, `phase` |

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
