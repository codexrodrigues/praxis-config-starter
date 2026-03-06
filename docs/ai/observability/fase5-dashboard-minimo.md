# Fase 5 - Dashboard Minimo Local (F5-SRE-01)

Status
- Objetivo: prover visibilidade operacional minima para canary local de stream AI.
- Escopo: single-node, sem LB real.
- Artefato principal: `docs/ai/observability/fase5-dashboard-minimo.grafana.json`.

## 1) Painel e metricas

Dashboard
- Titulo: `AI Stream Fase5 Canary - Local`
- UID: `ai-stream-fase5-local`
- Arquivo: `docs/ai/observability/fase5-dashboard-minimo.grafana.json`

Metricas/paineis
1. `Fallback stream->sync (5m)`
- Query: `sum by (provider, reason_kind) (increase(ai_stream_fallback_total[5m]))`
- Uso: detectar degradação de provider (capacity/timeout/transport/rate_limit).

2. `Cancel in-flight (5m)`
- Query: `sum(increase(ai_stream_cancel_inflight_total[5m]))`
- Uso: acompanhar volume de abortos reais durante execução.

3. `Stream duration p95 (ms)`
- Query: `histogram_quantile(0.95, sum by (le, provider) (rate(ai_stream_duration_ms_bucket[5m])))`
- Uso: latência operacional por provider.

4. `HTTP 5xx ratio (stream endpoints)`
- Query: `sum(rate(http_server_requests_seconds_count{uri=~"/api/praxis/config/ai/patch/stream.*",status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{uri=~"/api/praxis/config/ai/patch/stream.*"}[5m])), 0.001)`
- Uso: detectar erro técnico de endpoint no handshake/stream/cancel.

## 2) Como importar

1. Abrir Grafana local.
2. Importar arquivo `docs/ai/observability/fase5-dashboard-minimo.grafana.json`.
3. Associar datasource Prometheus com UID `prometheus`.
4. Confirmar janela de tempo (`now-3h`) e refresh (`30s`).

## 3) Validacao minima

Comandos
```bash
curl -s http://localhost:8088/actuator/prometheus | rg "ai_stream_fallback_total|ai_stream_cancel_inflight_total|ai_stream_duration_ms_bucket"
curl -s http://localhost:8088/actuator/prometheus | rg "http_server_requests_seconds_count"
```

Resultado esperado
- Pelo menos uma série para cada métrica `ai_stream_*` acima.
- Série de `http_server_requests_seconds_count` para rotas `/api/praxis/config/ai/patch/stream*` após smoke.

## 4) Evidencia de base de tráfego

- `docs/ai/fase5-sse-smoke-report.md`
- `artifacts/ai-sse-smoke/20260221-232941/summary.md`

