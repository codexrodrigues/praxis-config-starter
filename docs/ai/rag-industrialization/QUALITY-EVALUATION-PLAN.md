# Plano de Avaliacao de Qualidade do Retrieval/Authoring

Status: bateria inicial implementada para retrieval hibrido de `api_metadata`; expansao para
component corpus/authoring continua incremental.

## Objetivo

Criar um radar pequeno e repetivel para medir se o RAG granular melhora o authoring sem quebrar governanca.

## Principios

- Avaliar evidencia recuperada, nao apenas resposta final do modelo.
- Preferir cenarios reais de componentes oficiais.
- Manter fallback interno obrigatorio.
- Nao usar provider externo como baseline canonico.

## Dataset inicial sugerido

Cada caso deve conter:

- `id`;
- prompt do usuario;
- componente alvo esperado;
- `chunkKind` esperado quando aplicavel;
- source refs esperados;
- sinais minimos no plano/diagnostics;
- criterio de falha.

Casos iniciais:

| Id | Prompt | Componente esperado | Evidencia esperada |
| --- | --- | --- | --- |
| `table-toolbar-button` | Adicionar botao na toolbar da tabela para exportar selecionados | `praxis-table` | recipe ou authoring_manifest de toolbar/actions |
| `table-column-label` | Alterar titulo de uma coluna mantendo contrato da tabela | `praxis-table` | authoring_manifest de columns/header |
| `form-required-field` | Tornar um campo obrigatorio no formulario dinamico | `praxis-dynamic-form` | capabilities ou authoring_manifest de field validation |
| `dynamic-field-color` | Configurar campo de cor com editor apropriado | `pdx-color-input` ou equivalente | summary/context_pack do dynamic field |
| `page-builder-section` | Inserir uma secao com componente selecionado no page builder | `praxis-page-builder` | context_pack ou recipe de composicao |

## Metricas minimas

- `retrieval_hit`: existe evidencia para o componente correto.
- `source_ref_quality`: source refs sao repo-relativos e relevantes.
- `chunk_kind_quality`: pelo menos um chunk recuperado pertence ao tipo esperado.
- `plan_grounding`: `preview.plan` recebeu `authoringEvidence`.
- `governance_preserved`: fluxo ainda passa por preview/compile/apply.
- `noise_rate`: numero de evidencias de componente errado.
- `MRR`: posicao media reciproca do recurso esperado.
- `Recall@K`: presenca do recurso esperado no conjunto semanticamente recuperado.

## Benchmark implementado de API metadata

`HybridSemanticRankerTest` mantem cinco variacoes de fala em portugues sobre funcionarios,
status, foto/codigo, departamentos e folha de pagamento. O corpus e deterministico e nao chama
provider externo.

O baseline controlado posiciona o recurso esperado em terceiro lugar (`MRR = 0,3333`) e o
reranking BM25 + RRF dobra essa qualidade (`MRR = 0,6667`), preservando `Recall@3 = 1,0`.
Os guardrails tambem provam que:

- somente candidatos previamente retornados pela busca vetorial podem aparecer;
- score e proveniencia vetorial permanecem inalterados;
- ausencia de evidencia lexical preserva a ordem semantica;
- filtros de tenant, environment, release, method e tags continuam anteriores ao reranking.

## Expansao futura recomendada

Criar um runner local em fase posterior, preferencialmente em `praxis-config-starter` ou `tools/ai-registry`, que leia um JSON de casos e produza relatorio com:

- resultado por caso;
- source refs usados;
- score quando existir;
- diagnostics do authoring;
- regressao em relacao ao baseline salvo.

## Criterio de pronto

O benchmark deve conseguir reprovar regressao de retrieval sem exigir chamada real a OpenAI/Gemini.

## Bateria humana inicial

A bateria operacional detalhada esta em:

- `docs/ai/agentic-authoring/implementation/16-human-simulation-rag-validation-battery.md`

Primeiro corte implementavel sem provider externo:

- conversa com erro de digitacao e nome impreciso de componente;
- pergunta consultiva antes da materializacao;
- resposta curta a clarificacao;
- trecho copiado da resposta anterior usado como nova pergunta;
- validacao de `contextHints.authoringEvidence` no `PlanRequest`;
- validacao de `decisionDiagnostics.authoringEvidenceSourceRefs`.
