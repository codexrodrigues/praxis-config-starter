# P1-BE-6A Backlog Executavel - Fase A (Contrato + Seguranca)

Status
- Objetivo: transformar o `NO-GO` em base `GO` para iniciar timeline snapshot e SSE de etapas.
- Escopo desta fase: contrato de transporte testavel, identidade server-side, logs seguros por padrao, base de stream/replay preparada.
- Fora de escopo desta fase: streaming token-a-token por provider.

Status de execucao (2026-02-20)
- A2 implementado: `AiPrincipalContextResolver` aplicado ao `/patch` e aos endpoints de stream; modo corporativo nao confia em header cliente.
- A3 implementado: defaults de logging sensivel OFF + redaction central em logs/diagnosticos.
- A4 implementado: migration `V15__create_ai_turn_event.sql` + `AiTurnEventService` com append/replay transacional.
- A5 implementado: `start/stream/cancel` com idempotencia por `(tenant,user,thread,clientTurnId)` e evento terminal `cancelled`.
- Hash de idempotencia do `start` passou a forma canonica por allowlist de campos de contrato, ignorando flags internas/transientes de stream para manter compatibilidade em rollout entre versoes.
- Retry sem `sessionId` no primeiro turno reaproveita `threadId` deterministico baseado em `(tenant,user,environment,clientTurnId)`.
- Endpoint `GET /patch/stream/{streamId}/probe` adicionado para classificar acesso/expiracao antes da conexao SSE no FE.
- `probe/stream/cancel` aceitam `accessToken` em query quando `praxis.ai.stream.auth.mode=signed-url-token`; token emitido em formato opaco/cifrado e com TTL curto.
- Parser de token legado foi desativado por default (`praxis.ai.stream.auth.allow-legacy-signed-token=false`), mantendo flag temporaria apenas para migracao controlada.
- Nesses endpoints de stream, o escopo de identidade pode ser resolvido a partir do token apenas em `signed-url-token`; em `cookie` a semantica `401/403` da autenticacao server-side e preservada.
- Operacao corporativa exige redaction de query string em gateway/proxy/APM para evitar exposicao de `accessToken` em logs de infraestrutura.
- Configuracao `signed-url-token` agora valida segredo no startup (fail-fast), evitando erro 500 tardio na primeira requisicao.
- A6 implementado: FE com `start/connect/cancel` + fallback automatico para `/patch`.
- Fallback do FE ajustado para degradar apenas em indisponibilidade tecnica do stream (nao em `403/409` de contrato/autorizacao nem em expiracao/cancelamento de stream).
- Timeout de inatividade de SSE passou a erro classificado de transporte (com fallback controlado para `/patch`, mantendo bloqueio de fallback para erros de contrato/autorizacao), evitando travamento de UX.
- A7 implementado: state machine FE com estado interno terminal (`completed|failed|cancelled|expired`) e sem falso erro em `in_progress`.
- Consistencia terminal reforcada: apos `cancelled`, o backend nao publica `result/error` tardio para o mesmo stream.
- Reconciliacao de streams legados orfaos distingue status real do turno (`DONE` vs `CANCELLED`) para nao mascarar cancelamentos em auditoria.
- Append de evento agora usa lock pessimista no `ai_turn` para serializar concorrencia cross-node por `(threadId,turnId)`.
- Limites de capacidade locais por `tenant/user` com aquisicao atomica de permit (incluindo caminho de retomada idempotente) e rejeicao explicita (`429/503`) sem `CallerRunsPolicy`; no scale-out atual e `best effort` por instancia.
- Heartbeat de stream ajustado para evento SSE consumivel no FE, sem persistencia no event-store por default.
- Contrato SSE formalizado: campos obrigatorios e `seq` monotono aplicam-se aos eventos persistidos; `heartbeat` e out-of-band (`eventId` nulo e `seq=-1` permitidos).
- Timeout de UX separado em FE: timeout absoluto (`expiresAt`) + timeout de inatividade de eventos.
- Politica de dados do `ai_turn_event` explicitada: payload funcional pode conter PII de negocio; exige retencao/criptografia por ambiente e trilha de purge auditavel.
- Suite de integracao HTTP/SSE adicionada em `AiPatchStreamHttpSseIntegrationTest` (corporate-mode=true) cobrindo `Last-Event-ID`, reconnect cross-node com replay incremental, corrida `cancelled` vs `result/error` e matriz de erro `400/403/404/409/410`.
- Fase B (2026-02-21): reforco multi-TransactionManager concluido no starter com:
  - `transactionManager=configTransactionManager` em servicos/repositorios de stream;
  - alias de compatibilidade para host single-TM via auto-config.
- Fase B (2026-02-21): evidencia de integracao endurecida:
  - teste cross-node com no secundario em contexto Spring separado (beans proxied, sem `new` manual de servicos transacionais);
  - teste runtime multi-TM validando append/replay no `configTransactionManager` com contadores de execucao;
  - security integration com `corporate-mode=true` e resolver real (sem mock) para matriz `401/403`.
- Fase B (2026-02-21): smoke real no `praxis-api-quickstart` executado com evidencias de `start/probe/stream/cancel` e erros reais `400/403/404`.
- Pendencia para gate corporativo final: executar a mesma matriz em ambiente distribuido real (>=2 instancias com LB) para evidencia operacional.
- Pendencia de governanca: validar com produto/auditoria a semantica do evento tecnico de reconciliacao legado quando turno real estiver `DONE`.
- Fase C (2026-02-21): stream coarse-grained consolidado com `status`, `thought.step`, `result`, `error`, `cancelled` e `heartbeat` (heartbeat segue out-of-band, nao persistido).
- Fase C (2026-02-21): timeline FE passa a priorizar eventos reais de stream (step 1-3) com fallback heuristico apenas em snapshot.
- Fase C (2026-02-21): reducer FE endurecido para idempotencia/reordenação por `(streamId,eventId,seq)` com ignoração de eventos de stream estrangeiro.
- Fase C (2026-02-21): reconnect SSE endurecido para ignorar `Last-Event-ID: null` e heartbeat sem `id` SSE (evita poluicao de cursor de replay).
- Fase C (2026-02-21): fallback FE alinhado: queda de transporte de stream e schema de evento nao suportado degradam para `/patch`; erros de contrato/autorizacao (`400/403/409/410`) seguem sem fallback.
- Fase 4 (2026-02-21): iniciado opt-in de provider text streaming no orquestrador (`praxis.ai.provider.text-stream.enabled`) com cancelamento cooperativo por contexto de stream (thread-local).
- Fase 4 (2026-02-21): Gemini ganhou caminho SSE (`streamGenerateContent?alt=sse`) com fallback sync automatico quando streaming nao estiver disponivel.
- Fase 4 (2026-02-21): catalogo de providers passa a expor capacidades `supportsTextStreaming` e `supportsTurnCancellation`.
- Fase 4 (2026-02-21): `supports*` no catalogo foi alinhado para capacidade tecnica do provider (independente de credencial); estado de configuracao permanece tratado separadamente por status/config.
- Fase 4 (2026-02-21): fallback sync em erro de stream ficou explicito no orquestrador para falhas de transporte/capacidade (`praxis.ai.provider.text-stream.fallback-sync-on-error=true`).
- Fase 4 (2026-02-21): cancelamento ganhou abort in-flight best-effort (cancel do future + fechamento do stream HTTP) para liberar worker/capacidade sem esperar timeout de rede.
- Fase 4 (2026-02-21): adicionada suite de integracao local (stub provider in-memory) para provar fallback deterministico do stream e cancelamento in-flight sem dependencia de rede externa.
- Fase 4 (2026-02-21): classificacao de fallback foi endurecida com excecao tipada de stream (`AiProviderStreamException`) e matriz local para `timeout/connect/reset/capacity`, reduzindo dependencia de `message contains`.
- Fase 5 (2026-02-21): backlog executavel consolidado em `docs/ai/p1-backend/P1-BE-7-fase5-hardening-canary.md` com tarefas curtas por squad (BE/FE/QA/SRE), DoD e comandos de validacao.
- Fase 5 (2026-02-21): smoke de stream SSE separado de RAG em `scripts/ai/e2e-sse-smoke.sh`; `e2e-rag-smoke.sh` permanece para snapshot/RAG.
- Fase 5 (2026-02-21): naming de metricas padronizado para `ai_stream_*` (evita drift com `ai.stream.*`).
- Fase 5 (2026-02-21): hardening de assertividade no orquestrador com fallback deterministico para intents diretas de tabela (densidade, selecao de linhas, alinhamento de colunas e destaque contextual de status).
- Fase 5 (2026-02-21): adicionada guarda de relevancia de patch para evitar resposta fora do escopo do prompt (ex.: prompt de densidade recebendo patch de coluna calculada), com fallback deterministico e cobertura de teste dedicada.
- Atualizacao 2026-02-22 (Trilha A-04/A-05): suite retro versionada `v1.1` adicionada para boundary de contrato (`/patch`, `/patch/stream/start`, SSE) com execucao automatizada e relatorio em `docs/ai/contract-v11-retro-compat-report.md`.
- Atualizacao 2026-02-22 (Trilha B-01/B-02): catalogo FE ganhou schemas formais + validador de governanca com relatorio (`errors=0`) e gate bloqueante acoplado ao `ci:verify` no `praxis-ui-angular`.
- Atualizacao 2026-02-22 (Trilha C-01/C-02): RAG ganhou IDs compostas por escopo/release/hash, migration com indice unico composto + dedupe pre-index e upsert deterministico com dedupe por `contentHash`.
- Atualizacao 2026-02-22 (Trilha C-03/C-04): retrieval RAG passou a aplicar filtro obrigatorio de `releaseId` (com fallback controlado para chave legada `version`) e ganhou prova automatizada de isolamento entre releases.
- Atualizacao 2026-02-22 (D-01 diagnostico host real): `e2e-rag-smoke` ficou `PASS=3 FAIL=6` em modo corporativo por bloqueio de identidade (`403 Corporate identity is required...`); com `PRAXIS_AI_SECURITY_CORPORATE_MODE=false` o mesmo smoke ficou `PASS=9 FAIL=0` e `e2e-sse-smoke` passou, mantendo pendencia de D-01 no eixo identidade server-side + ambiente 2-nos/LB.
- Atualizacao 2026-02-22 (D-01 diagnostico host real): smokes ganharam bootstrap de autenticacao (`AUTH_LOGIN_URL` + credenciais + cookie auto) e `corporate-mode=true` ficou verde no single-node (`PASS=9`, SSE ok) com principal server-side autenticado e tenant corporativo default; pendencia residual de D-01 ficou restrita ao ambiente distribuido 2-nos + LB.
- Atualizacao 2026-02-23 (D-01 hardening corporativo): novo gate `scripts/ai/e2e-corporate-hardening.sh` executado com `11/11 PASS` em host real (identity gate, anti-bypass, contract `409`, guardrails e estabilidade SSE em repeticao); D-01 permanece pendente apenas no eixo distribuido real 2+ nos + LB.

## 1) Principios de implementacao

1. Manter `POST /api/praxis/config/ai/patch` retrocompativel.
2. Nenhum endpoint SSE depende de headers custom no browser.
3. Tenant/user/thread/turn sempre validados no servidor.
4. Persistencia de evento antes de emissao SSE.
5. Prompt/resposta bruta nunca em log default de producao.

## 2) Stories executaveis (por ordem)

### A1 - Contrato publico e semantica HTTP fechados

Objetivo
- Congelar contrato para `start/stream/cancel` com codigos HTTP e erros deterministas.

Entregaveis
- Documento de contrato versionado (`eventSchemaVersion=v1`).
- Tabela de erros por endpoint.
- Matriz idempotencia e replay.

Arquivos alvo
- `praxis-config-starter/docs/ai/p1-backend/P1-BE-6-streaming-robusto-e-escalavel.md`
- `praxis-config-starter/docs/ai/p1-backend/P1-BE-6A-backlog-fase-a-contrato-seguranca.md`

Tarefas
1. Formalizar payload de `POST /patch/stream/start` (input e output).
2. Formalizar envelope SSE e schema por `type`.
3. Formalizar `Last-Event-ID`: invalido (`400`), sem permissao (`403`), expirado (`410`).
4. Formalizar idempotencia por `(tenant,user,thread,clientTurnId)` com `200/201/409`.

Criterios de aceite
1. Qualquer dev consegue implementar e testar sem interpretacao subjetiva.
2. Regras de erro e retry ficam identicas em backend e frontend.

Esforco
- 1 a 2 dias.

Dependencias
- Nenhuma.

### A2 - Identidade server-side obrigatoria

Objetivo
- Remover dependencia de headers opcionais como identidade principal em ambiente corporativo.

Entregaveis
- Resolver de contexto autenticado no backend.
- Rejeicao explicita de requests sem identidade valida (fora de modo local flagado).

Arquivos alvo
- `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiOrchestratorController.java`
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiThreadService.java`
- `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts`
- `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.spec.ts`

Tarefas
1. Introduzir `AiPrincipalContextResolver` (novo service) para derivar `tenantId/userId/environment` do principal.
2. No controller, usar contexto resolvido do servidor como fonte primaria.
3. Em `AiThreadService`, remover caminho permissivo com `thread.userId == null`.
4. No frontend, descontinuar fallback automatico `demo/local` por default corporativo.
5. Manter modo local somente via flag explicita de desenvolvimento.

Criterios de aceite
1. Sem principal valido: `401/403` consistente.
2. Replay e acesso de thread negados quando `tenant/user` nao correspondem.
3. Testes de FE nao assumem mais headers demo por default.

Testes obrigatorios
- Backend integration:
  - acesso permitido para mesmo principal.
  - acesso negado para principal diferente.
- Frontend unit:
  - `buildHeaders` nao injeta `demo/local` quando flag corporativa ativa.

Esforco
- 2 a 3 dias.

Dependencias
- A1.

### A3 - Hardening de logging e redaction

Objetivo
- Eliminar vazamento de prompt/resposta/snapshot bruto em logs default.

Entregaveis
- Defaults seguros em propriedades.
- Redaction central para logs e eventos.
- Teste automatico "no sensitive log".

Arquivos alvo
- `praxis-config-starter/src/main/resources/praxis-config-defaults.properties`
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiInteractionLogger.java`
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java`
- `praxis-config-starter/docs/ai/memory-and-pii.md`

Tarefas
1. Alterar defaults para:
   - `praxis.ai.logging.include-prompt=false`
   - `praxis.ai.logging.include-response=false`
   - `praxis.ai.logging.include-front-response=false`
2. Criar `AiSensitiveDataRedactor` (novo service utilitario).
3. Aplicar redaction em `logLlmInteraction`, `logFrontendResponse` e logs diagnosticos.
4. Bloquear envio de prompt bruto/currentState bruto em eventos SSE.
5. Adicionar cenarios de teste com regex de segredo/PII.

Criterios de aceite
1. Em configuracao default, prompt e resposta bruta nao aparecem em logs.
2. Redaction cobre pelo menos: emails, tokens, chaves, numeros longos suspeitos.

Testes obrigatorios
- Unit `AiInteractionLogger`.
- Integration de endpoint com appender de teste validando ausencia de campos sensiveis.

Esforco
- 2 a 3 dias.

Dependencias
- A1.

### A4 - Base de dados para stream/replay auditavel

Objetivo
- Criar fonte de verdade para replay multi-instancia.

Entregaveis
- Tabela `ai_turn_event` com constraints e indices.
- Repositorio + service de append/replay transacional.

Arquivos alvo
- `praxis-config-starter/src/main/resources/db/migration/V15__create_ai_turn_event.sql` (novo)
- `praxis-config-starter/src/main/java/org/praxisplatform/config/domain/*` (novas entidades)
- `praxis-config-starter/src/main/java/org/praxisplatform/config/repository/*` (novo repositorio)
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnEventService.java` (novo)

Tarefas
1. Criar tabela com colunas:
   - `tenant_id`, `user_id`, `environment`, `stream_id`, `thread_id`, `turn_id`, `seq`, `event_id`, `event_type`, `payload`, `created_at`.
2. Adicionar `unique(thread_id, turn_id, seq)` e `unique(event_id)`.
3. Implementar append transacional com `seq` monotono por turno.
4. Implementar replay por `Last-Event-ID` com filtro de autorizacao.
5. Definir politica de retencao e purge.

Criterios de aceite
1. Eventos duplicados por `event_id` sao rejeitados deterministicamente.
2. Replay retorna ordem correta sem reordenacao.

Testes obrigatorios
- Concorrencia de append.
- Replay com `Last-Event-ID` valido/invalido/expirado.

Esforco
- 3 a 4 dias.

Dependencias
- A2.

### A5 - Endpoints `start/stream/cancel` com estados terminais

Objetivo
- Entregar handshake funcional e cancelamento deterministico.

Entregaveis
- `POST /patch/stream/start` idempotente.
- `GET /patch/stream/{streamId}` com SSE + heartbeat.
- `POST /patch/stream/{streamId}/cancel` retornando estado terminal.

Arquivos alvo
- `praxis-config-starter/src/main/java/org/praxisplatform/config/controller/AiPatchStreamController.java` (novo)
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiStreamService.java` (novo)
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java`
- `praxis-config-starter/src/main/java/org/praxisplatform/config/service/AiTurnService.java`

Tarefas
1. Implementar `start` com idempotencia por `clientTurnId`.
2. Implementar stream SSE com eventos:
   - `status`, `heartbeat`, `result`, `error`, `cancelled`.
3. Implementar `cancel` nao-opaco:
   - `cancelled`, `completed` ou `not_found`.
4. Persistir evento antes de enviar ao emitter.
5. Garantir cleanup de emitter por timeout/close.

Criterios de aceite
1. Estado terminal sempre observado pelo FE.
2. Reconnect em outro no recupera eventos via banco.

Testes obrigatorios
- Controller integration com `SseEmitter`.
- Cancel em corrida com conclusao (`cancel vs result`).

Esforco
- 4 a 5 dias.

Dependencias
- A1, A2, A4.

### A6 - FE transporte de stream + fallback seguro

Objetivo
- Preparar FE para usar stream sem quebrar `/patch`.

Entregaveis
- API client para `start/connect/cancel`.
- Parser de evento versionado.
- Fallback automatico para snapshot.

Arquivos alvo
- `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts`
- `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.spec.ts`

Tarefas
1. Adicionar metodos:
   - `startPatchStream(...)`
   - `connectPatchStream(streamId, lastEventId?)`
   - `cancelPatchStream(streamId)`
2. Definir tipagem do envelope SSE (`eventSchemaVersion`, `eventId`, `seq`, `type`, `payload`).
3. Implementar fallback para `getPatch(...)` em erro de stream/open.
4. Garantir `withCredentials` quando autenticacao via cookie for usada.

Criterios de aceite
1. Stream falhou -> FE degrada para snapshot sem travar UX.
2. Mesmo fluxo continua funcional quando backend de stream estiver desativado.

Esforco
- 2 a 3 dias.

Dependencias
- A1, A5.

### A7 - FE state machine deterministica (`in_progress` sem falso erro)

Objetivo
- Eliminar falso erro no `finally` durante turn em processamento/reconnect.

Entregaveis
- Tabela de transicoes de estado no componente.
- Reducer idempotente por `eventId/seq`.

Arquivos alvo
- `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.ts`
- `praxis-ui-angular/projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts`

Tarefas
1. Corrigir ramo de `result.type === 'info'` para separar `in_progress` de sucesso.
2. Remover queda para erro no bloco `finally` quando estado for `processing/in_progress`.
3. Adicionar de-dup por `eventId/seq`.
4. Definir estados terminais explicitos:
   - `completed`, `failed`, `cancelled`, `expired`.

Criterios de aceite
1. Nenhum falso erro em reenvio/reconnect.
2. Duplicacao e reordenacao de evento nao quebram UI.

Esforco
- 2 a 3 dias.

Dependencias
- A6.

### A8 - Suite de validacao corporativa (gate de GO)

Objetivo
- Fechar criterio de producao para sair de `NO-GO`.

Entregaveis
- Testes e evidencias para seguranca, contrato, resiliencia e observabilidade.

Arquivos alvo
- `praxis-config-starter/src/test/java/org/praxisplatform/config/controller/*`
- `praxis-config-starter/src/test/java/org/praxisplatform/config/service/*`
- `praxis-ui-angular/projects/praxis-ai/src/lib/**/*.spec.ts`
- `praxis-config-starter/docs/ai/p1-backend/P1-BE-6-streaming-robusto-e-escalavel.md` (status gate)

Tarefas
1. Testes de contrato HTTP + SSE (inclui `401/403/404/409/410`).
2. Testes de replay (`Last-Event-ID`) com autorizacao por tenant/user.
3. Soak test curto (conexao longa) sem leak evidente.
4. Validacao de metricas e correlacao (`requestId/streamId/threadId/turnId/seq`).
5. Checklist final de aceite funcional e nao funcional assinado.

Status parcial (2026-02-21)
- Itens 1 e 2 cobertos por integracao backend (`AiPatchStreamHttpSseIntegrationTest`) com event-store real e fluxo SSE via MockMvc, incluindo matriz `400/403/404/409/410` e replay `Last-Event-ID`.
- Semantica `401` coberta por integracao dedicada com `SecurityFilterChain` real no starter (`AiPatchStreamSecurityChainIntegrationTest`), validando bloqueio sem autenticacao e passagem autenticada.
- Validacao distribuida real (LB/multi-instancia externa) segue pendente para fechamento do gate corporativo.

Criterios de aceite
1. Todos os bloqueadores criticos fechados por evidencia de teste.
2. Plano pode mudar de `NO-GO` para `GO com ajustes`.

Esforco
- 3 a 4 dias.

Dependencias
- A2 a A7.

## 3) Sequencia recomendada (roadmap curto)

Semana 1
1. A1 contrato.
2. A2 identidade.
3. A3 logging/redaction.

Semana 2
1. A4 event store.
2. A5 endpoints start/stream/cancel.

Semana 3
1. A6 FE transporte + fallback.
2. A7 FE state machine.
3. A8 testes gate.

## 4) Definicao de pronto (DoD da Fase A)

1. Endpoints `start/stream/cancel` implementados com contrato versionado.
2. Identidade validada server-side sem dependencia de header cliente no modo corporativo.
3. Logging sensivel desativado por default + redaction ativa.
4. Replay autorizado e ordenado por event store.
5. FE sem falso erro em `in_progress` e com fallback snapshot funcional.
6. Evidencias de teste anexadas aos PRs.

## 5) Owners sugeridos

1. BE: A2, A3, A4, A5, A8.
2. FE: A6, A7, parte de A8.
3. Sec/Platform: revisao de A2, A3, A4.
