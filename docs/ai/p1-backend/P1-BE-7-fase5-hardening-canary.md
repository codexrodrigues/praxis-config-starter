# P1-BE-7 - Fase 5 (Hardening Corporativo + Readiness de Canary)

Status
- Objetivo: preparar a operacao para rollout controlado com evidencias tecnicas e operacionais.
- Escopo deste backlog: ambiente de desenvolvimento em maquina unica (single-node), com itens distribuidos marcados como bloqueados por ambiente.
- Base tecnica: Fases A, B, C e 4 concluida com gate tecnico GO.

## 1) Premissas

1. Execucao em uma maquina, sem LB e sem cluster real.
2. `praxis-api-quickstart` e `praxis-config-starter` usados para validacao real de endpoint.
3. FE validado via runner WSL estavel (`tools/karma/karma.wsl.conf.cjs`).
4. Nenhum ajuste de negocio fora do escopo de hardening e operacao.

## 2) Definition Of Done da Fase 5

1. Metricas minimas de stream/fallback/cancel/latencia publicadas e consultaveis.
2. Logs correlacionados por `requestId/threadId/turnId/streamId`.
3. Script unico de smoke SSE reproduzivel em host real.
4. Gate FE SSE verde em runner estavel.
5. Scorecard de assertividade de LLM executado e versionado.
6. Runbook de canary/rollback publicado.

## 3) Backlog Executavel (Single-node)

| ID | Squad | Prioridade | Tarefa | Estimativa | Dependencias | Criterio de pronto | Validacao |
|---|---|---|---|---|---|---|---|
| F5-BE-01 | BE | P0 | Expor `ai_stream_fallback_total{provider,reason_kind}` | 0.5d | - | Metricas com labels estaveis em Prometheus | `curl -s http://localhost:8088/actuator/prometheus \| rg ai_stream_fallback_total` |
| F5-BE-02 | BE | P0 | Expor `ai_stream_cancel_inflight_total` | 0.5d | F5-BE-01 | Incremento em cancel real durante `in_progress` | `curl -s http://localhost:8088/actuator/prometheus \| rg ai_stream_cancel_inflight_total` |
| F5-BE-03 | BE | P0 | Expor histograma `ai_stream_duration_ms` (p50/p95) | 1d | F5-BE-01 | Buckets e labels por provider disponiveis | `curl -s http://localhost:8088/actuator/prometheus \| rg ai_stream_duration_ms_bucket` |
| F5-BE-04 | BE | P0 | Correlacao de logs por IDs de fluxo | 1d | F5-BE-01 | Logs de start ate terminal com mesmos IDs | `rg -n \"requestId|threadId|turnId|streamId\" app.log` |
| F5-BE-05 | BE | P1 | Evento de reconciliacao com `reason_code` deterministico | 0.5d | F5-BE-04 | Terminal tecnico sempre com `reason_code` | `mvn -f praxis-config-starter/pom.xml -Dtest=AiTurnEventServiceTest test -q` |
| F5-BE-06 | BE | P0 | Script smoke SSE unico (`start/probe/stream/replay/cancel`) | 1d | F5-BE-01..05 | Script 100% reproduzivel com artefato markdown | `BASE_URL=http://localhost:8088 scripts/ai/e2e-sse-smoke.sh` |
| F5-FE-01 | FE | P1 | Telemetria UX (`stream_started`, `fallback_to_patch`, `stream_cancelled`) | 0.5d | F5-BE-01 | Eventos emitidos com metadata minima | `node_modules/.bin/ng test praxis-ai --watch=false --progress=false --karma-config=tools/karma/karma.wsl.conf.cjs --include=projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts` |
| F5-FE-02 | FE | P1 | Banner tecnico por classe de erro (`transport/schema/contract`) | 1d | F5-FE-01 | UX mostra causa tecnica correta | `node_modules/.bin/ng test praxis-ai --watch=false --progress=false --karma-config=tools/karma/karma.wsl.conf.cjs --include=projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts` |
| F5-FE-03 | FE | P1 | Indicador de modo degradado durante fallback para `/patch` | 0.5d | F5-FE-02 | Indicador entra/sai conforme estado real | `node_modules/.bin/ng test praxis-ai --watch=false --progress=false --karma-config=tools/karma/karma.wsl.conf.cjs --include=projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts` |
| F5-FE-04 | FE | P0 | Expandir matriz reducer SSE (dedupe/reorder/foreign/schema) | 1d | F5-FE-02 | Cobertura adicional sem regressao do gate | `node_modules/.bin/ng test praxis-ai --watch=false --progress=false --karma-config=tools/karma/karma.wsl.conf.cjs --include=projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts` |
| F5-FE-05 | FE | P1 | Ajustes A11y (WCAG AA) para estados de erro e alerta | 1d | F5-FE-02 | Contraste/foco validado em checklist | Checklist + `ng test praxis-ai` |
| F5-QA-01 | QA | P0 | Matriz de 12 prompts x 3 repeticoes (assertividade) | 0.5d | F5-BE-06 | Casos e expected outcomes versionados | `docs/ai/fase5-test-matrix.md` |
| F5-QA-02 | QA | P0 | Scorecard 0-5 por caso + latencia/fallback/cancel | 1d | F5-QA-01 | Relatorio com score medio e variancia | `docs/ai/fase5-llm-report.md` |
| F5-QA-03 | QA | P0 | Regressao FE SSE (`ai-assistant` + `ai-backend-api`) | 0.5d | F5-FE-04 | Suites alvo verdes em runner oficial | `node_modules/.bin/ng test praxis-ai --watch=false --progress=false --karma-config=tools/karma/karma.wsl.conf.cjs --include=projects/praxis-ai/src/lib/ui/ai-assistant/ai-assistant.component.spec.ts` + `node_modules/.bin/ng test praxis-ai --watch=false --progress=false --karma-config=tools/karma/karma.wsl.conf.cjs --include=projects/praxis-ai/src/lib/core/services/ai-backend-api.service.spec.ts` |
| F5-QA-04 | QA | P1 | Stress local reconnect/cancel (20 iteracoes) | 1d | F5-BE-06 | Sem perda de terminal e sem lock zumbi | Script loop + relatorio |
| F5-SRE-01 | SRE | P1 | Dashboard minimo local (fallback/cancel/p95/erros) | 1d | F5-BE-01..03 | Painel exportado em docs | `docs/ai/observability/` |
| F5-SRE-02 | SRE | P1 | Alertas locais de limiar (fallback, p95, erro) | 0.5d | F5-SRE-01 | Regras versionadas + teste manual | arquivo de regras + evidencia |
| F5-SRE-03 | SRE | P0 | Runbook de canary/rollback | 0.5d | F5-BE-06, F5-SRE-01 | Procedimento completo de promote/abort | `docs/ai/runbooks/fase5-canary-rollback.md` |

## 4) Itens Bloqueados por Ambiente (2+ nos + LB)

| ID | Squad | Tarefa | Motivo do bloqueio |
|---|---|---|---|
| F5-DIST-01 | BE/SRE | Reconnect/replay/cancel cross-node sob LB real | Sem ambiente multi-instancia com balanceador |
| F5-DIST-02 | BE/SRE | Limite distribuido por tenant/user | Controle atual e local por instancia |
| F5-DIST-03 | QA/SRE | Soak 2h com failover de no | Necessita ambiente com failover real |

## 4.1) Escopo dos scripts de smoke

1. `scripts/ai/e2e-sse-smoke.sh`: cobre exclusivamente contrato SSE (`start/probe/stream/replay/cancel`).
2. `scripts/ai/e2e-rag-smoke.sh`: cobre snapshot/RAG/registry e nao substitui validacao de stream SSE.

## 5) Ordem Recomendada de Execucao

1. F5-BE-01, F5-BE-02, F5-BE-03, F5-BE-04, F5-BE-05, F5-BE-06
2. F5-FE-01, F5-FE-02, F5-FE-03, F5-FE-04, F5-FE-05
3. F5-QA-01, F5-QA-02, F5-QA-03, F5-QA-04
4. F5-SRE-01, F5-SRE-02, F5-SRE-03
5. F5-DIST-01..03 quando ambiente distribuido estiver disponivel

## 6) Criterio de GO da Fase 5

1. Gate tecnico: todos os itens P0 (single-node) concluidos.
2. Gate qualidade: suites FE SSE alvo verdes e smoke SSE sem regressao.
3. Gate assertividade: score medio >= 4.0 na matriz 12x3, sem falha critica.
4. Gate operacional: dashboard, alertas e runbook publicados.
