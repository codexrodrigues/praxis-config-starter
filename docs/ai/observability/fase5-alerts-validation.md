# Fase 5 - Validacao de Alertas Locais (F5-SRE-02)

Status
- Escopo: validacao manual em ambiente local/single-node.
- Regras fonte: `docs/ai/observability/fase5-alert-rules.prometheus.yml`.

## 1) Sanidade de metrica

```bash
curl -s http://localhost:8088/actuator/prometheus | rg "ai_stream_fallback_total|ai_stream_cancel_inflight_total|ai_stream_duration_ms_bucket"
curl -s http://localhost:8088/actuator/prometheus | rg "http_server_requests_seconds_count"
```

Critério
- Todas as métricas usadas nas regras estão presentes no scrape.

## 2) Smoke para gerar tráfego

```bash
BASE_URL=http://localhost:8088 scripts/ai/e2e-sse-smoke.sh
```

Critério
- Artefato `artifacts/ai-sse-smoke/<timestamp>/summary.md` gerado.
- Stream flow finaliza com `result` e fluxo de cancel finaliza com `cancelled`.

## 3) Verificacao de queries (manual)

Fallback
```bash
curl -s http://localhost:8088/actuator/prometheus | rg "ai_stream_fallback_total"
```

Cancel in-flight
```bash
curl -s http://localhost:8088/actuator/prometheus | rg "ai_stream_cancel_inflight_total"
```

Duration histogram
```bash
curl -s http://localhost:8088/actuator/prometheus | rg "ai_stream_duration_ms_bucket"
```

5xx stream endpoints
```bash
curl -s http://localhost:8088/actuator/prometheus | rg "/api/praxis/config/ai/patch/stream"
```

## 4) Resultado desta rodada

- Base de evidencia de tráfego: `artifacts/ai-sse-smoke/20260221-232941/summary.md`.
- Regras versionadas: `docs/ai/observability/fase5-alert-rules.prometheus.yml`.
- Limitação: disparo real de alerta depende de Prometheus/Grafana Alerting ativos no ambiente.

