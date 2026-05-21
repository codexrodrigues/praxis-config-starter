# Plano de Avaliacao de Qualidade do Retrieval/Authoring

Status: bateria inicial em implementacao pos-Fase 09.

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

## Comando futuro recomendado

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
