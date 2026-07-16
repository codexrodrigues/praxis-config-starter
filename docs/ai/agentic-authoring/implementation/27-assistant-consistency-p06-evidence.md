# Assistant consistency and P0.6 evidence — 2026-07-15

## Resultado

O baseline basico do assistente deixou de ser intermitente no corpus P0
avaliado: o corte comparavel passou de `9/18` para `18/18`, com P50 terminal
reduzido de `30,0455s` para `17,5375s` e P95 de `48,655s` para `42,798s`.
Depois do slice de budget, o mesmo corpus permaneceu `18/18` e o P95 caiu para
`31,457s`.

A state machine P0.6 foi certificada separadamente por uma matriz deterministica
de `76/76` testes. Ela cobre retomada idempotente, replay SSE, corrida entre
cancelamento e resultado, timeout que bloqueia conclusao tardia, schema
indisponivel fail-closed, terminal unico, reconciliacao entre instancias,
fronteira transacional e ownership por token assinado.

Nenhum endpoint, DTO, envelope SSE ou public API foi alterado neste corte.

## Metodologia

- Provider/modelo dos gates de assertividade: `openai` / `gpt-4.1-mini`.
- P50: mediana dos tempos terminais por execucao.
- P95: nearest-rank (`ceil(0,95 * N)`) sobre os tempos terminais por execucao.
- Acuracia: execucoes aprovadas / execucoes totais segundo o corpus versionado.
- Transacao: apply, readback exato, replay condicional, bloqueio de ETag stale e
  cleanup do caso persistente.
- P0.6: testes locais com doubles deterministas; nao mede latencia ou qualidade
  do provider.

## Comparacao antes/depois

| Corte comparavel | Runs | Aprovados | Acuracia | P50 terminal | P95 terminal | Transacoes |
|---|---:|---:|---:|---:|---:|---:|
| Employee core inicial | 3 | 1 | 33,33% | 30,195s | 35,692s | n/a |
| Employee core corrigido | 3 | 3 | 100% | 29,531s | 36,519s | n/a |
| Platform discovery inicial | 9 | 4 | 44,44% | 9,622s | 10,638s | 0/0 |
| Platform discovery corrigido | 9 | 9 | 100% | 9,261s | 9,881s | 0/0 |
| P0 integral inicial | 18 | 9 | 50% | 30,0455s | 48,655s | 3/3 |
| P0 integral corrigido | 18 | 18 | 100% | 17,5375s | 42,798s | 3/3 |
| P0 integral apos budget | 18 | 18 | 100% | 18,549s | 31,457s | 3/3 |

No P0 integral, o primeiro corte verde reduziu o P50 em `41,63%` e o P95 em
`12,04%` contra o baseline. O corte posterior de budget preservou 100% de
acuracia e reduziu o P95 em mais `26,50%` contra o primeiro corte verde
(`35,35%` contra o baseline). A pequena variacao de P50 entre os dois cortes
verdes e aceitavel; o ganho principal do budget aparece na cauda P95.

O recorte Employee core mostra que acuracia e latencia devem permanecer gates
independentes: a assertividade passou de 33,33% para 100%, enquanto o P95 variou
de 35,692s para 36,519s. O produto nao deve esconder essa regressao de cauda por
ter corrigido a decisao semantica.

## Evidencia reproduzivel P0.6

Comando:

```bash
tools/local-e2e/run-authoring-turn-state-machine-gate-local.sh
```

Resultado observado:

| Metrica | Valor |
|---|---:|
| Testes | 76 |
| Falhas | 0 |
| Erros | 0 |
| Ignorados | 0 |
| Tempo agregado Surefire | 12,175s |

O runner tambem verifica a presenca nominal das nove provas obrigatorias. Assim,
o gate falha mesmo que a suite fique verde depois da remocao acidental de um
cenario essencial.

## Proveniencia dos artefatos

Os artefatos locais sao deliberadamente ignorados pelo Git; os resultados
essenciais e hashes ficam registrados aqui para auditoria.

| Evidencia | SHA-256 |
|---|---|
| `assistant-consistency-employee-core-3cases-20260715/gate-result.json` | `5ea42dc7543010843b6e193dc63a5868c1400ebf87050552580c1f1a280a18ac` |
| `assistant-consistency-employee-core-3cases-final-20260715/gate-result.json` | `1b11c961d594105dee7ed805c96e77a006c6095aa891b9b907333af7e5e83acc` |
| `assistant-consistency-platform-release-20260715-202359/gate-result.json` | `95a8669312f02799e1023ab73755eb1c12e91f52b6bd27bb11201261d37c31c9` |
| `assistant-consistency-platform-release-20260715-202956/gate-result.json` | `99b433b4c0d902bc4f193697fe90cb7875e4a0e596a8ca81e70f363d500ee8d9` |
| `assistant-consistency-p0-integral-release-20260715-195738/gate-result.json` | `84bd0dff9b7b61cd0c94062f36c9ef59b29265f609f72aae5c5c8f5208dc97b0` |
| `assistant-consistency-p0-integral-release-20260715-205358/gate-result.json` | `989ad5b3b1841749d2b1a7cc9597718de3df3ba08d41cb501d56d8f3176e8a21` |
| `assistant-consistency-planner-budget-integral-20260715-211530/gate-result.json` | `1ea2ca694c676ecbee24fef3c34a50a81ca0000388a8ccb80da3b5b3729cfee7` |
| `authoring-turn-state-machine-20260715-235018/summary.json` | `8c810552dfd23fd8ab2363aeea77fc3e1ae07f557a4ddda904357c7a2dd0e1cd` |

Codigo-base dos gates reais: commit `6c3bec5795e832be074cc8240c1f5bb7d5127355`.

## Inventario de aderencia da observabilidade

| Dimensao | Classificacao | Evidencia atual | Proximo ajuste correto |
|---|---|---|---|
| Acuracia, P50 e latencia por caso | `ja-suportado-so-ux` | `gate-result.json` ja contem runs e timing terminal | Publicar tendencia versionada/visual sem duplicar o corpus |
| Provider e modelo | `ja-suportado-so-ux` | presentes no envelope raiz do gate | Usar como dimensoes do historico |
| Retry de persistencia/ETag | `suportado-parcialmente` | `staleRetryBlocked` e replay condicional sao verificados | Separar tentativa, conflito esperado e retry efetivo na metrica |
| Retry de provider/fase | `ja-suportado-mal-nomeado-ou-mal-materializado` | `AiInteractionLogger` recebe `attempt`, mas o dado nao chega ao resultado do turno/gate | Projetar telemetria segura por fase a partir do backend |
| Tokens de entrada/saida/cache | `lacuna-real-de-contrato` | adapters reduzem `ChatResponse` a texto e descartam usage | Preservar usage canonico no boundary de provider e agrega-lo ao turno |
| Custo estimado/real | `lacuna-real-de-contrato` | nao ha usage agregado nem pricing versionado | Criar politica de pricing/model snapshot e calcular custo auditavel |

`retries`, `tokens` e `cost` nao aparecem nos artefatos atuais; portanto seus
valores sao **indisponiveis**, nao zero. Inventar estimativa a partir de tamanho
de prompt ou preco corrente invalidaria a comparacao.

## Mapa de impacto do proximo slice de telemetria

- Fonte canonica: boundary de provider e observabilidade do
  `praxis-config-starter`.
- Consumidores: event/decision diagnostics seguros, runner de consistencia e
  futura visualizacao operacional.
- Docs/examples: este relatorio, plano de excelencia e eventual contrato de
  diagnostics; nenhum componente Angular deve calcular tokens ou custo.
- Validacao minima: fake provider com usage/retry conhecido, agregacao por
  turno, redacao/tenant isolation, gate real OpenAI e verificacao de custo por
  snapshot de modelo.
- Breaking change: deve ser aditivo em diagnostics internos; qualquer promocao
  ao envelope publico exige ciclo separado de contrato e bindings.

## Proximo passo recomendado

Antes de modernizar SDK/modelo, fechar o lineage seguro da decisao semantica ate
preview/apply e desenhar a telemetria canonica de provider. Isso permite que o
spike Spring AI 1.1.8 seja comparado com evidencia de assertividade, P95,
retries, tokens e custo, e nao apenas por percepcao visual.
