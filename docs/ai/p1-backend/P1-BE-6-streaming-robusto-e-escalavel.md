# P1-BE-6 Streaming Robusto e Escalavel (Revisado v2)

Status atual
- Veredito tecnico consolidado: `NO-GO` para implementar streaming no formato atual.
- Gate para virar `GO`: corrigir contrato testavel, seguranca/logging, isolamento tenant/user, cancelamento deterministico e operacao multi-instancia.

Atualizacao de implementacao (Fase A concluida em 2026-02-20)
- Endpoints publicados:
  - `POST /api/praxis/config/ai/patch/stream/start`
  - `GET /api/praxis/config/ai/patch/stream/{streamId}`
  - `GET /api/praxis/config/ai/patch/stream/{streamId}/probe`
  - `POST /api/praxis/config/ai/patch/stream/{streamId}/cancel`
- `clientTurnId` e obrigatorio em `start` para garantir idempotencia corporativa.
- Retry sem `sessionId` no primeiro turno usa thread deterministica por `clientTurnId` para evitar duplicacao de thread/stream.
- Hash idempotente de `start` foi canonizado por allowlist de campos de contrato para desconsiderar flags internas/transientes de stream, evitando `409` falso em rollout entre versoes.
- Contrato SSE aplicado com envelope `eventSchemaVersion=v1` e campos: `eventId,streamId,threadId,turnId,seq,timestamp,type,payload`.
- Regras de replay ativas:
  - `Last-Event-ID` invalido -> `400`
  - `Last-Event-ID` fora do escopo autorizado -> `403`
  - stream expirado -> `410`
- Persistencia de evento antes da emissao SSE implementada via `ai_turn_event`.
- Cancelamento terminal `cancelled` implementado e persistido.
- Reserva de turno para stream ajustada para garantir FK do event-store sem bloquear o processamento real do turno.
- Reconnect para stream ja terminal fecha imediatamente mesmo sem eventos novos no replay.
- Guarda de cancelamento evita emissao de `result/error` apos evento terminal `cancelled`.
- Reconciliacao de stream legado orfao agora distingue status real do turno e preserva terminal `cancelled` quando aplicavel.
- Transicao terminal no event-store ficou atomica: apos `result/error/cancelled`, novos eventos do turno recebem `409`.
- Append do event-store agora serializa por `ai_turn` com lock pessimista (`PESSIMISTIC_WRITE`) para reduzir corrida cross-node em terminalizacao.
- Cancelamento deixou de marcar `ai_turn` como `CANCELLED` antes do append terminal; o status do turno agora e reconciliado pelo terminal efetivo persistido.
- Heartbeat de conexao passou a evento SSE `heartbeat` nao persistido e consumivel pelo FE (reduz falso timeout e custo de replay).
- Heartbeat SSE nao envia `id` (somente eventos persistidos carregam `id`), evitando `Last-Event-ID: null` em reconnect automatico.
- `Last-Event-ID: null` (literal) e tratado como ausente no backend para robustez de transporte.
- Backpressure corporativo ativo com limites locais por `tenant/user` e rejeicao explicita (`429/503`) quando a capacidade e excedida; no scale-out atual o controle e `best effort` por instancia.
- Suite de integracao HTTP/SSE adicionada (`AiPatchStreamHttpSseIntegrationTest`, corporate-mode=true) cobrindo replay com `Last-Event-ID`, reconnect cross-node com replay incremental, corrida `cancelled` vs `result/error` e erros `400/403/404/409/410`.
- Auth de stream suporta:
  - `cookie` (padrao)
  - `signed_url_token` (token opaco/cifrado, TTL curto, em query para `probe/stream/cancel`; quando nao ha cookie, a identidade desses endpoints e resolvida pelo escopo do token)
- Formato legado de token assinado somente HMAC foi mantido sob flag de migracao e permanece desativado por default.
- `signed_url_token` requer redaction de query string em logs de borda (gateway/proxy/APM); sem esse controle, nao e modo recomendado.
- Sem `signed_url_token`, endpoints de stream preservam semantica de autenticacao server-side (`401/403`) sem fallback token-only.
- `POST /api/praxis/config/ai/patch` mantido retrocompativel.
- Gate operacional pendente: reexecutar a matriz SSE/replay em ambiente distribuido real (>=2 instancias com LB) para evidencia final corporativa.
- Cobertura de `401` adicionada no starter com security chain real (`AiPatchStreamSecurityChainIntegrationTest`); no host, a resposta final segue a politica da cadeia de seguranca da aplicacao consumidora.
- Isolamento transacional multi-TM reforcado:
  - servicos/repositorios de stream/event-store usam `transactionManager=configTransactionManager`.
  - starter registra alias `configTransactionManager` para host single-TM (prioriza `transactionManager`; em ausencia, usa candidato unico) e falha no startup quando a resolucao e ambigua/ausente.

## 1) Bloqueadores de entrada (must-fix)

1. Contrato SSE invalido para o fluxo atual
- O fluxo de patch precisa de body grande (estado/config/contexto), mas EventSource usa GET.
- Acao obrigatoria: handshake em 2 etapas:
  - `POST /api/praxis/config/ai/patch/stream/start` (recebe body completo).
  - `GET /api/praxis/config/ai/patch/stream/{streamId}` (somente eventos SSE).
- Regra adicional obrigatoria: EventSource no browser nao aceita headers custom; auth deve ser por cookie/session ou token padrao do app host.

2. Baseline de seguranca de logs inadequado
- Bloquear por padrao logs de prompt/resposta bruta em producao.
- Redacao obrigatoria de payload textual antes de log/evento.

3. Isolamento tenant/user fragil
- Nao confiar em headers opcionais como fonte primaria de identidade.
- Derivar tenant/user do contexto autenticado no servidor.
- Remover caminho permissivo de thread com `userId` nulo para cenarios corporativos.

4. Idempotencia "in progress" com risco de regressao de UX
- Ajustar state machine FE para estado explicito `in_progress` sem queda para `error` no `finally`.

5. Escala horizontal/replay sem garantias transacionais
- Persistir eventos com seq monotono antes da emissao.
- Reconnect/replay sempre autorizado por tenant/user/thread/turn.

6. Camada provider ainda apenas sync
- Expandir contrato para streaming e cancelamento antes da fase de stream real por modelo.

7. Cancelamento ainda opcional
- Em modo corporativo streaming, `cancel` deixa de ser opcional.
- Deve existir evento terminal `cancelled` para encerrar state machine no FE e auditoria.

8. Contrato de idempotencia/replay incompleto para auditoria
- Especificar codigos HTTP e semantica para duplicidade, `Last-Event-ID` invalido/expirado e stream expirado.

## 2) Arquitetura revisada (backend + frontend)

### 2.1 Transporte (handshake)

Etapa A - Start (POST)
- Endpoint: `POST /api/praxis/config/ai/patch/stream/start`
- Input: mesmo payload hoje usado em `/patch`.
- Chave de idempotencia: `(tenantId, userId, threadId, clientTurnId)`.
- Output:
  - `streamId`
  - `threadId`
  - `turnId`
  - `eventSchemaVersion`
  - `expiresAt`
  - `fallbackPatchUrl` (mantem compatibilidade)

Etapa B - Stream (GET SSE)
- Endpoint: `GET /api/praxis/config/ai/patch/stream/{streamId}`
- Autenticacao por mecanismo nativo do host (cookie ou token padrao do app).
- Suporte a `Last-Event-ID` para replay.

Etapa C - Cancel (POST obrigatorio no modo streaming corporativo)
- Endpoint: `POST /api/praxis/config/ai/patch/stream/{streamId}/cancel`
- Cancela processamento no orquestrador/provider quando possivel.
- Deve produzir estado terminal observavel (`cancelled`, `completed` ou `not_found`) com semantica fixa.

Compatibilidade
- `POST /api/praxis/config/ai/patch` permanece ativo e retrocompativel.
- Fallback do FE: se stream indisponivel, usar snapshot atual.

### 2.2 Matriz HTTP minima (testavel)

Start
- `201`: stream/turn criado.
- `200`: requisicao idempotente retornando mesmo `streamId/turnId`.
- `400`: payload invalido.
- `401/403`: autenticacao/autorizacao.
- `409`: conflito de idempotencia com payload divergente para mesmo `clientTurnId`.
- `429`: limite por tenant/user excedido.
- `503`: saturacao global do executor de stream.

Stream
- `200`: SSE aberto.
- sem novos eventos: manter conexao aberta com `heartbeat` periodico.
- `401/403`: acesso negado.
- `404`: stream inexistente.
- `403`: stream/`Last-Event-ID` fora de escopo autorizado.
- `410`: stream expirado (cliente deve reiniciar via `/start`).

Cancel
- `200`: `cancelled` ou `completed` com payload final de estado.
- `404`: stream/turn nao encontrado para o principal.
- `409`: estado terminal ja atingido sem possibilidade de cancelar.

### 2.3 Envelope SSE versionado

Campos obrigatorios (eventos persistidos em `ai_turn_event`)
- `eventId`
- `streamId`
- `threadId`
- `turnId`
- `seq`
- `eventSchemaVersion`
- `timestamp`
- `type`
- `payload`

Heartbeat (`type=heartbeat`) e keepalive out-of-band
- Nao e persistido no event-store por default.
- Pode trafegar com `eventId=null` e `seq=-1`.
- Nao participa de idempotencia/replay por `Last-Event-ID`.

Eventos MVP
- `status`
- `thought.step`
- `thought.check`
- `review.summary`
- `review.diff.preview`
- `result`
- `error`
- `heartbeat`
- `cancelled`

Regras
- `seq` monotono por `turnId` para eventos persistidos.
- `result`, `error` e `cancelled` sao terminais.
- FE deve ser idempotente por `eventId`/`seq`.
- `Last-Event-ID` invalido -> `400`; fora de tenant/user/thread/turn -> `403`; expirado -> `410`.
- Schema por tipo de `payload` deve ser versionado e documentado (nao apenas o envelope).

## 3) Modelo de seguranca corporativo (obrigatorio)

### 3.1 Identidade e autorizacao
- Resolver tenant/user/env no servidor a partir de principal autenticado.
- Headers do cliente servem apenas como hint em ambiente local controlado e sob flag explicita.
- Em local mode com fallback por header, identidade anonima do host (`anonymousUser`/equivalente) nao deve sobrescrever `X-User-ID`.
- Quando tenant nao estiver no principal, a infraestrutura deve projetar atributo server-side confiavel
  (filtro/gateway); fallback por default tenant corporativo fica desativado por padrao e e opt-in explicito.
- Ao abrir stream e ao fazer replay:
  - validar associacao `tenantId + userId + threadId + turnId`.
  - negar acesso cruzado.
- Em ambiente corporativo: rejeitar request quando identidade obrigatoria nao estiver resolvida server-side.

### 3.2 Logging e dados sensiveis
- Defaults de producao:
  - `include-prompt=false`
  - `include-response=false`
  - `include-front-response=false` (ou sanitizado)
- Redacao obrigatoria de PII/segredos em:
  - logs
  - eventos SSE
  - diagnosticos de erro
- Proibir envio de prompt bruto/currentState bruto no payload de evento.
- Adicionar testes automatizados de "no sensitive log" no CI.

### 3.3 Persistencia de eventos

Tabela nova: `ai_turn_event`
- colunas minimas:
  - `tenant_id`
  - `user_id`
  - `environment`
  - `stream_id`
  - `thread_id`
  - `turn_id`
  - `seq`
  - `event_id`
  - `event_type`
  - `payload jsonb`
  - `created_at`
- constraints/indices:
  - `unique(thread_id, turn_id, seq)`
  - `unique(event_id)`
  - indices por `(stream_id, seq)` e `(tenant_id, user_id, created_at desc)`
- politicas obrigatorias:
  - retencao (TTL) por ambiente.
  - criptografia em repouso para payload de evento.
  - purge/arquivamento com trilha de auditoria.
  - classificacao de PII no payload funcional: campos de negocio mantidos por contrato devem seguir politica corporativa de minimizacao e mascaramento em exportacoes/analytics.

Requisito transacional
- Persistir evento com `seq` antes de emitir para `SseEmitter`.
- Replay sempre a partir da base, nunca apenas de memoria local.
- Em multi-instancia, o event store e a fonte de verdade; emitter local nao e fonte de reconstrucao.

## 4) Mudancas de codigo obrigatorias por area

Backend API
- `AiOrchestratorController`: manter `/patch` e adicionar endpoints de handshake/stream/cancel.
- Novo `AiPatchStreamController` (ou equivalente) para SSE.

Servicos
- Novo `AiTurnEventService`:
  - append event transacional
  - replay por `Last-Event-ID`
  - cleanup/retencao
- Novo `AiStreamService`:
  - lifecycle de `SseEmitter`
  - heartbeat
  - timeout/cleanup
  - policy de ownership por instancia para conexao ativa (evitar estado ambiguo em reconnect)
- `AiOrchestratorService`:
  - publicar eventos de etapa em pontos chave
  - evento terminal antes de qualquer retorno antecipado

Providers
- `AiProvider` expandido com streaming/cancelamento:
  - exemplo de direcao:
    - `Flux<AiProviderChunk> generateTextStream(...)`
    - `Flux<AiProviderChunk> generateJsonStream(...)`
    - `void cancelTurn(UUID threadId, UUID turnId)` (ou handle equivalente)
- Implementacoes:
  - `SpringAiOpenAiService`
  - `SpringAiGeminiService`
  - `SpringAiXaiService`
- Fallback sync explicito quando stream nao suportado/modelo indisponivel.
- Definir matriz de capacidade por provider/modelo: `stream`, `cancel`, `retry`, `maxDuration`.

Frontend
- `AiBackendApiService`:
  - `startPatchStream(...)`
  - `connectPatchStream(streamId, lastEventId?)`
  - fallback `/patch`
- `ai-assistant.component.ts`:
  - estado explicito `in_progress`
  - reducer idempotente por `eventId`/`seq`
  - nao cair em `error` automaticamente ao receber "turn em processamento"
  - tabela de transicao formal para estados terminais `completed|failed|cancelled|expired`

## 5) Fases revisadas (gate-driven)

### Fase 0 - Contrato + seguranca (bloqueador)
Entregas
- Handshake POST + SSE GET definidos e implementados.
- Identidade validada server-side.
- Logging sensivel bloqueado por padrao.
- OpenAPI + contrato SSE documentados com matriz HTTP e regras de erro.
Saida
- `GO` para thought snapshot.

### Fase 1 - Thought snapshot (sem SSE)
Entregas
- `thought` opcional em `AiOrchestratorResponse`.
- FE backend-first para thought, com fallback heuristico.
Saida
- Timeline coerente no snapshot.

### Fase 2 - SSE coarse-grained
Entregas
- `status/thought.step/result/error/heartbeat`.
- timeout/cleanup/executor dedicado.
Saida
- fluxo realtime funcional em ambiente de teste.

### Fase 3 - Replay + reconnect corporativo
Entregas
- `Last-Event-ID` com replay consistente e autorizado.
- comportamento formal para `invalid|expired|forbidden`.
Saida
- reconnect sem perda/duplicacao observavel.

### Fase 4 - Cancelamento deterministico + streaming por provider (opt-in)
Entregas
- `cancelled` terminal em contrato e UI.
- OpenAI/Gemini com stream + cancelamento (xAI conforme suporte real).
- fallback sync estavel.
Saida
- throughput e latencia dentro de meta definida.

### Fase 5 - Hardening multi-instancia + canario
Entregas
- testes carga/reconnect/falha provider
- dashboards/alertas/SLO
- rollout canario + rollback
Saida
- pronto para producao gradual.

## 6) Observabilidade e operacao

Metricas minimas
- `ai.stream.open.count`
- `ai.stream.active.count`
- `ai.stream.duration.ms`
- `ai.stream.error.count`
- `ai.stream.replay.count`
- `ai.step.duration.ms{step=...}`
- `ai.stream.heartbeat.miss.count`

Correlacao
- `requestId`, `streamId`, `threadId`, `turnId`, `seq`, `provider`, `model`.

Escalabilidade
- executor dedicado para async MVC/SSE.
- limites por instancia/tenant/user.
- heartbeat curto e timeout alinhado com proxy/LB.
- testes de reconnect em no diferente (A->B) com replay da base.

## 7) Esforco revisado

Premissas
- 1 backend senior + 1 frontend senior + QA parcial.

Cenario otimista
- 8-10 semanas (contrato robusto + SSE etapas + replay + cancelamento + canario basico).

Cenario conservador
- 12-16 semanas (stream real OpenAI/Gemini + hardening multi-instancia + carga/chaos + rollout completo).

## 8) Checklist de aceite

Funcional
1. `/patch` permanece retrocompativel.
2. `POST /start` e idempotente por `(tenant,user,thread,clientTurnId)` com resposta deterministica.
3. timeline da UI e guiada por evento real quando stream ativo.
4. `Detalhes` e `Previa` preservam semantica operacional.
5. reconnect com `Last-Event-ID` recupera lacunas.
6. falha de stream degrada para snapshot sem travar UX.
7. `POST /cancel` sempre encerra com estado terminal observavel (`cancelled|completed|not_found`).

Nao funcional
1. sem prompt/PII/segredo em logs/eventos por padrao.
2. isolamento tenant/user validado no servidor em abertura e replay.
3. limites de stream por instancia/tenant/user aplicados.
4. sem vazamento de memoria/threads em conexoes longas.
5. observabilidade com metricas + logs correlacionados + trace.
6. testes de carga/reconnect/falha provider executados antes de rollout amplo.
7. politicas de retencao/criptografia de `ai_turn_event` aplicadas e auditaveis.

## 9) Referencias oficiais

Spring AI
- Chat Client (`call`/`stream`):
  - https://docs.spring.io/spring-ai/reference/api/chatclient.html

Spring MVC / Boot
- Async e SSE (`SseEmitter`):
  - https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
- Task execution tuning:
  - https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html

Browser SSE
- EventSource:
  - https://developer.mozilla.org/en-US/docs/Web/API/EventSource/EventSource
- SSE reconnect / `Last-Event-ID`:
  - https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events
- WHATWG SSE:
  - https://html.spec.whatwg.org/dev/server-sent-events.html

Providers
- OpenAI streaming:
  - https://developers.openai.com/api/docs/guides/streaming-responses
  - https://platform.openai.com/docs/guides/background
- Gemini streaming (REST):
  - https://ai.google.dev/api

## 10) ADRs recomendadas (curtas)

1. `ADR-01`: handshake obrigatorio `POST /start` + `GET /stream`; SSE nunca recebe payload de negocio.
2. `ADR-02`: identidade sempre server-side; header cliente apenas hint local sob flag.
3. `ADR-03`: `ai_turn_event` e fonte de verdade para replay; emit apenas apos persistencia.
4. `ADR-04`: `cancel` obrigatorio no modo streaming corporativo; `cancelled` e terminal.
5. `ADR-05`: FE com reducer deterministico por `(streamId,eventId,seq)` e tabela de transicoes explicita.
6. `ADR-06`: adapter por provider com `stream/cancel/fallback` e testes de contrato por provider/modelo.

## 11) Backlog executavel

- Backlog detalhado da Fase A (`contrato + seguranca`):
  - `docs/ai/p1-backend/P1-BE-6A-backlog-fase-a-contrato-seguranca.md`
