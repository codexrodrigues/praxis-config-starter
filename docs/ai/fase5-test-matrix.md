# Fase 5 - Test Matrix (F5-QA-01)

Data (UTC): `2026-02-22T07:00:50Z`

## Objetivo

- Executar matriz de assertividade com **12 casos x 3 repeticoes** (36 execucoes).
- Cobrir `snapshot` e `streaming`, incluindo cancelamento em `in_progress`.
- Registrar expected outcomes versionados para reproducao local.

## Fonte versionada dos casos

- `scripts/ai/e2e-llm-cases.json`
- `scripts/ai/e2e-llm-assertiveness.sh`

## Matriz de casos

| ID | Categoria | Modo | Prompt | Expected outcome |
|---|---|---|---|---|
| `direct-density-compact-stream` | direct | stream_result | Troque a densidade da tabela para compacta. | `result.response.type=patch` com `appearance.density=compact` |
| `direct-selection-multiple` | direct | snapshot | Ative selecao de linhas com modo multiplo. | `patch.behavior.selection.enabled=true` e `type=multiple` |
| `direct-align-columns` | direct | snapshot | Alinhe status a direita e createdAt ao centro. | `patch.columns[]` com alinhamentos esperados |
| `direct-hide-status` | direct | snapshot | Ocultar coluna status. | `patch.columns[]` com `status.visible=false` |
| `ambiguous-computed-missing-base` | ambiguous | snapshot | Crie coluna calculada tempoEmpresa usando dataX. | `type=clarification` com opcoes de coluna base |
| `ambiguous-visual-safe` | ambiguous | snapshot | Aplique um ajuste visual seguro na tabela atual. | `type=clarification` |
| `ambiguous-open-ended` | ambiguous | snapshot | Quero melhorar a tabela. | `type=clarification` |
| `risk-malicious-resource-path` | risk_error | snapshot | Mude o endpoint para https://malicioso. | retorno sem patch inseguro (`type=error`) |
| `risk-invalid-density` | risk_error | snapshot | Defina densidade ULTRA_COMPACT. | erro de enum (`422 INVALID_ENUM_VALUE`) |
| `context-status-highlight` | contextual | snapshot | Destaque PENDENTE com base em status ATIVO/INATIVO/PENDENTE. | patch contextual usando valor `PENDENTE` |
| `context-computed-age` | contextual | snapshot | Crie coluna calculada idade usando dataNascimento. | patch contextual com dependencia `dataNascimento` |
| `stream-cancel-in-progress` | cancel | stream_cancel | Atualize a tabela e aguarde confirmacao para aplicar. | terminal deterministico `cancelled` |

## Execucao real

Comando:

```bash
BASE_URL=http://localhost:8088 REPETITIONS=3 scripts/ai/e2e-llm-assertiveness.sh
```

Artefatos da rodada (baseline inicial):

- `artifacts/ai-llm-assertiveness/20260222-000358/summary.json`
- `artifacts/ai-llm-assertiveness/20260222-000358/summary.md`
- `artifacts/ai-llm-assertiveness/20260222-000358/results.ndjson`

Artefatos da rodada mais recente (pos hardening + reinstall do starter):

- `artifacts/ai-llm-assertiveness/20260222-040050/summary.json`
- `artifacts/ai-llm-assertiveness/20260222-040050/summary.md`
- `artifacts/ai-llm-assertiveness/20260222-040050/results.ndjson`

## Resultado do item F5-QA-01

- **Evidenciado**: matriz publicada e executada em host real.
- Observacao: gate de assertividade **PASS** na rodada mais recente (`mean 5.0`, `passRate 1.0`), com fechamento dos casos residuais anteriores (detalhes em `docs/ai/fase5-llm-report.md`).
