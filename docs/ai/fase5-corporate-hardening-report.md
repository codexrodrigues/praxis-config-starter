# Fase 5 - Corporate Hardening Report (F5-BE-CORP-01)

Data (UTC): `2026-02-23T00:19:08Z`

## Escopo

- Ambiente real: `praxis-api-quickstart` em `http://localhost:8088`
- Script: `scripts/ai/e2e-corporate-hardening.sh`
- Objetivo: validar gate corporativo single-node para identidade server-side, contrato e guardrails.

## Comando executado

```bash
BASE_URL='http://localhost:8088' \
AUTH_LOGIN_URL='http://localhost:8088/auth/login' \
AUTH_USERNAME='admin' \
AUTH_PASSWORD='***' \
AUTH_COOKIE_NAME='praxis_heroes_dev' \
STABILITY_RUNS=2 \
STREAM_TIMEOUT_SECONDS=120 \
CORPORATE_EXPECTATION=ready \
scripts/ai/e2e-corporate-hardening.sh
```

## Resultado consolidado

- `detectedMode=ready`
- `checkCount=11`
- `passCount=11`
- `failCount=0`
- Gate: **PASS**

Checks aprovados:

1. `health_up`
2. `unauth_denied`
3. `corporate_mode_probe`
4. `corporate_expectation_gate`
5. `anti_bypass_header_hints`
6. `contract_mismatch_gate`
7. `sse_stability_run_1`
8. `sse_stability_run_2`
9. `guardrail_resourcepath`
10. `guardrail_invalid_density`
11. `guardrail_unknown_component`

## Evidencias

- `artifacts/ai-corporate-hardening/20260222-211721/summary.json`
- `artifacts/ai-corporate-hardening/20260222-211721/summary.md`
- Reexecucao de sanity:
  - `artifacts/ai-corporate-hardening/20260222-211931/summary.md`

## Status de D-01

- Single-node corporativo: **evidenciado**
- Pendencia residual: ambiente distribuido real (`2+ nos + LB`) para concluir `D-01`.
