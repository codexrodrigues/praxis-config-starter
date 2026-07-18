# Comparacao de custo e qualidade entre GPT-5.4 Mini e GPT-5.6 Terra

Data do corte: 2026-07-18.

## Decisao

- `gpt-5.4-mini` permanece como baseline economico para desenvolvimento local e diagnostico frequente.
- `gpt-5.6-terra` passa a ser o candidato explicito para gates de assertividade, demo e fechamento de release.
- O modelo de release nao muda silenciosamente: Terra deve ser selecionado por `MODEL=gpt-5.6-terra`, com custo e limites deliberadamente revisados.
- `gpt-5.6-sol` nao entra na matriz cotidiana; seu custo nao se justifica sem um caso de qualidade que Terra nao resolva.

A decisao usa a orientacao oficial de selecao da familia GPT-5.6 e o pricing
standard publicado pela OpenAI:

- <https://developers.openai.com/api/docs/guides/model-selection>
- <https://developers.openai.com/api/docs/guides/upgrading-to-gpt-5p6-sol.md>
- <https://developers.openai.com/api/docs/pricing>

## Aderencia e escopo

- classificacao: `transversal`, pois corpus, runner, pricing e prova HTTP/SSE sao usados pelo Config Starter e pelo Quickstart;
- jornada consultiva sem preview seguida de materializacao: `suportado-parcialmente`;
- verificacao de eixos semanticos no gate: `ja-suportado-mal-nomeado-ou-mal-materializado`;
- pricing GPT-5.6: `ja-suportado-mal-nomeado-ou-mal-materializado` no snapshot versionado;
- lacuna de endpoint, DTO, evento SSE ou contrato de runtime: nenhuma.

O runner agora preserva a pagina de contexto quando um turno consultivo
legitimamente nao gera preview, mantendo thread e conversa para o turno
seguinte. Turnos que exigem `previous-preview` continuam falhando fechados se
o preview anterior nao existir.

## Jornada bloqueante

O corpus ganhou `platform-guidance-to-employee-dashboard-pt`:

1. `O que posso fazer aqui?`
   - resposta amigavel;
   - formulario, tabela, grafico/dashboard e filtro;
   - pelo menos tres continuacoes;
   - sem preview ou mutacao.
2. `Otimo. A partir dessa recomendacao, crie um dashboard para acompanhar os funcionarios por departamento.`
   - mesma thread e novo turn;
   - recurso `/api/human-resources/funcionarios`;
   - preview aplicavel;
   - pelo menos um eixo semantico;
   - todos os eixos verificados.

O contexto inclui o `agenticApplyTarget` canonico que o Page Builder real envia.
Sem ele, a prova direta por HTTP criava preview tecnicamente valido, mas o
backend corretamente bloqueava apply com `apply-target-missing`.

## Resultado comparavel

Cada modelo executou tres repeticoes da jornada, totalizando seis turnos.
Latencia e eficiencia foram medidas, mas nao usadas para mascarar falhas de
qualidade.

| Modelo | Aprovacao | Mediana | P95 | Tokens | Custo estimado |
| --- | ---: | ---: | ---: | ---: | ---: |
| `gpt-5.4-mini` | `5/6` (`83,3%`) | `22,6805 s` | `37,080 s` | `101.375` | `US$ 0,062845` |
| `gpt-5.6-terra` | `6/6` (`100%`) | `28,1425 s` | `49,852 s` | `105.880` | `US$ 0,254666` |

`gpt-5.4-mini` materializou os tres dashboards com eixos verificados, mas uma
das tres orientacoes omitiu filtros. Terra cobriu todos os conceitos nas tres
orientacoes e materializou os tres dashboards. Terra foi aproximadamente
quatro vezes mais caro e 24% mais lento na mediana, mas foi o unico a atingir
consistencia total nesta amostra.

## Comandos

```bash
CASE_IDS=platform-guidance-to-employee-dashboard-pt \
PROFILE=must-pass REPETITIONS=3 PROVIDER=openai MODEL=gpt-5.4-mini \
STREAM_TIMEOUT_SECONDS=240 ENFORCE_LATENCY=false ENFORCE_EFFICIENCY=false \
ARTIFACTS_DIR=artifacts/local-e2e/model-comparison-gpt-5.4-mini-apply-target-20260718 \
tools/local-e2e/run-assistant-consistency-gate-local.sh

CASE_IDS=platform-guidance-to-employee-dashboard-pt \
PROFILE=must-pass REPETITIONS=3 PROVIDER=openai MODEL=gpt-5.6-terra \
STREAM_TIMEOUT_SECONDS=240 ENFORCE_LATENCY=false ENFORCE_EFFICIENCY=false \
ARTIFACTS_DIR=artifacts/local-e2e/model-comparison-gpt-5.6-terra-20260718 \
tools/local-e2e/run-assistant-consistency-gate-local.sh
```

## Proximo gate

Antes de promover Terra como modelo geral do produto, executar `extended x3`
com limites de custo explicitamente aprovados. O corte atual autoriza Terra
para qualidade e apresentacao, nao para substituir indiscriminadamente o
baseline economico em todo smoke local.
