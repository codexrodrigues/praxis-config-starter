# Fase 5 - Runbook de Canary e Rollback (F5-SRE-03)

Status
- Escopo operacional atual: single-node.
- Objetivo: procedimento padrão de promote/abort para stream AI com reversão rápida.

## 1) Pre-check antes de canary

1. Health
```bash
curl -sS http://localhost:8088/actuator/health
```

2. Métricas mínimas expostas
```bash
curl -s http://localhost:8088/actuator/prometheus | rg "ai_stream_fallback_total|ai_stream_cancel_inflight_total|ai_stream_duration_ms_bucket"
```

3. Smoke SSE obrigatório
```bash
BASE_URL=http://localhost:8088 scripts/ai/e2e-sse-smoke.sh
```

4. Gate corporativo de hardening (identidade/contrato/guardrails)
```bash
BASE_URL=http://localhost:8088 \
AUTH_LOGIN_URL=http://localhost:8088/auth/login \
AUTH_USERNAME=admin \
AUTH_PASSWORD=<senha> \
AUTH_COOKIE_NAME=praxis_heroes_dev \
CORPORATE_EXPECTATION=ready \
scripts/ai/e2e-corporate-hardening.sh
```

5. Conferir artefatos
- `artifacts/ai-sse-smoke/<timestamp>/summary.md`
- `artifacts/ai-corporate-hardening/<timestamp>/summary.md`

Go/No-Go do pre-check
- GO: health OK, smoke SSE completo, hardening corporativo PASS e métricas visíveis.
- NO-GO: qualquer falha no smoke/hardening, health != UP ou métricas ausentes.

## 2) Promote controlado

1. Janela inicial (single-node): tráfego restrito a usuários internos.
2. Monitorar por 30-60 minutos:
- `ai_stream_fallback_total` (slope)
- `ai_stream_cancel_inflight_total` (slope)
- `ai_stream_duration_ms` p95
- taxa 5xx de `/api/praxis/config/ai/patch/stream*`
3. Critérios de continuidade:
- sem erro crítico contínuo,
- p95 estável,
- fallback/cancel dentro do baseline local.

## 3) Critérios de abort imediato

Abortar imediatamente se qualquer condição ocorrer por mais de 10 minutos:
1. `http 5xx ratio` stream > 5%.
2. `ai_stream_duration_ms` p95 > 15s.
3. `ai_stream_fallback_total` em burst persistente sem estabilização.
4. Incidente de segurança/isolamento de tenant.

## 4) Rollback técnico

Passo 1 - degradar para caminho síncrono (desabilitar provider stream)
- Ajustar configuração:
  - `praxis.ai.provider.text-stream.enabled=false`
- Reiniciar aplicação (ou rollout com configuração atualizada).

Passo 2 - confirmar recuperação
```bash
curl -sS http://localhost:8088/actuator/health
BASE_URL=http://localhost:8088 scripts/ai/e2e-sse-smoke.sh
```

Passo 3 - registrar incidente
- Data/hora do abort.
- Trigger (alerta/erro).
- Evidências (logs + artefatos de smoke + métricas).
- Ação corretiva e owner.

## 5) Evidências desta fase

- Smoke real: `docs/ai/fase5-sse-smoke-report.md`
- Artefato: `artifacts/ai-sse-smoke/20260221-232941/summary.md`
- Hardening corporativo: `docs/ai/fase5-corporate-hardening-report.md`
- Artefato: `artifacts/ai-corporate-hardening/20260222-211721/summary.md`
- Dashboard: `docs/ai/observability/fase5-dashboard-minimo.grafana.json`
- Alertas: `docs/ai/observability/fase5-alert-rules.prometheus.yml`

## 6) Limitações conhecidas

1. Ambiente sem LB/cluster; validações distribuídas seguem bloqueadas por ambiente.
2. Runbook cobre promote/abort local, não cobre failover multi-node real.
