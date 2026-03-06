# Fase 5 - SSE Smoke Report (F5-BE-06)

Data (UTC): `2026-02-22T02:30:13Z`

## Ambiente

- Host real: `praxis-api-quickstart` em `http://localhost:8088`
- Script: `scripts/ai/e2e-sse-smoke.sh`
- Artefatos brutos: `artifacts/ai-sse-smoke/20260221-232941/`

## Execucao

Comando:

```bash
BASE_URL=http://localhost:8088 scripts/ai/e2e-sse-smoke.sh
```

## Evidencias

Flow 1 (`start/probe/stream/replay`)
- `start` HTTP `201`: `artifacts/ai-sse-smoke/20260221-232941/flow1.start.response.headers`
- `eventSchemaVersion=v1`: `artifacts/ai-sse-smoke/20260221-232941/flow1.start.response.json`
- Sequencia com terminal `result`: `artifacts/ai-sse-smoke/20260221-232941/flow1.stream.types.txt`
- Replay apos `Last-Event-ID` passou (`count=5`): `artifacts/ai-sse-smoke/20260221-232941/flow1.replay.count.txt`

Flow 2 (`start/cancel`)
- `start` HTTP `201`: `artifacts/ai-sse-smoke/20260221-232941/flow2.start.response.headers`
- `cancel` HTTP `200`: `artifacts/ai-sse-smoke/20260221-232941/flow2.cancel.response.headers`
- Estado terminal `cancelled`: `artifacts/ai-sse-smoke/20260221-232941/flow2.cancel.response.json`

Resumo automatizado:
- `artifacts/ai-sse-smoke/20260221-232941/summary.md`

## Resultado

- Status: **PASS**
- Criterio F5-BE-06 atendido em single-node para contrato SSE (`start/probe/stream/replay/cancel`) com artefato markdown versionado.
