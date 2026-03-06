# Fase 5 - LLM Scorecard (F5-QA-02)

Data (UTC): `2026-02-22T07:00:50Z`

## Escopo

- Ambiente real: `praxis-api-quickstart` em `http://localhost:8088`
- Matriz: `12 casos x 3 repeticoes` (36 execucoes)
- Fonte dos casos: `scripts/ai/e2e-llm-cases.json`
- Executor: `scripts/ai/e2e-llm-assertiveness.sh`

## Resultado consolidado

- `overallScoreMean`: **5.0 / 5.0**
- `overallScoreVariance`: **0.0**
- `overallPassRate`: **1.0**
- `ai_stream_fallback_total delta`: **0.0**
- `ai_stream_cancel_inflight_total delta`: **0.0**
- Gate (`minAverageScore=4.0` e sem caso medio < 3.0): **PASS**

Evidencia bruta (rodada mais recente):

- `artifacts/ai-llm-assertiveness/20260222-040050/summary.json`
- `artifacts/ai-llm-assertiveness/20260222-040050/summary.md`
- `artifacts/ai-llm-assertiveness/20260222-040050/results.ndjson`

Comparativo com baseline inicial:

- Baseline: `artifacts/ai-llm-assertiveness/20260222-000358/summary.json`
- Delta de score medio: `+2.6` (de `2.4` para `5.0`)
- Delta de pass-rate: `+0.75` (de `0.25` para `1.0`)

## Score por caso (media)

| Case | Categoria | Modo | Mean |
|---|---|---|---:|
| `ambiguous-computed-missing-base` | ambiguous | snapshot | 5.00 |
| `ambiguous-open-ended` | ambiguous | snapshot | 5.00 |
| `ambiguous-visual-safe` | ambiguous | snapshot | 5.00 |
| `context-computed-age` | contextual | snapshot | 5.00 |
| `context-status-highlight` | contextual | snapshot | 5.00 |
| `direct-align-columns` | direct | snapshot | 5.00 |
| `direct-density-compact-stream` | direct | stream_result | 5.00 |
| `direct-hide-status` | direct | snapshot | 5.00 |
| `direct-selection-multiple` | direct | snapshot | 5.00 |
| `risk-invalid-density` | risk_error | snapshot | 5.00 |
| `risk-malicious-resource-path` | risk_error | snapshot | 5.00 |
| `stream-cancel-in-progress` | cancel | stream_cancel | 5.00 |

## Principais achados de assertividade

1. Os 3 gaps remanescentes foram fechados nesta rodada:
   - `ambiguous-computed-missing-base` voltou para `clarification`.
   - `direct-density-compact-stream` voltou para `result.patch.appearance.density=compact`.
   - `risk-invalid-density` voltou para `422 INVALID_ENUM_VALUE`.
2. Não houve incremento inesperado nas métricas de fallback/cancel in-flight durante o lote.
3. A matriz 12x3 está apta para uso como regressão de gate da Fase 5.

## Veredito desta rodada

- **GO** para gate de assertividade da Fase 5 (score e cobertura atendidos nesta execução).
