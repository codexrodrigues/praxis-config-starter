# Human Simulation RAG Validation Battery

Status: bateria inicial deterministica para validar o ganho das Fases 01-09.
Date: 2026-05-20
Scope: agentic authoring, retrieval granular de componentes, preview governado e diagnostics seguros.

## Classification

- Current change: `arquitetural` + `transversal`.
- Canonical owner: `praxis-config-starter`.
- Corpus producer: `praxis-ui-angular`.
- Runtime proof host: `praxis-api-quickstart`.
- Public contract impact: none in this battery. If a scenario requires a new AI contract field, reclassify the implementation as `contrato-publico`.

## Goal

Prove that the AI authoring flow improved the original problem: when a user asks for screens or modifications using Praxis components, the assistant must retrieve component-specific documentation, keep the conversation semantic, and materialize through governed preview/apply instead of guessing component JSON.

## Human Behavior Model

The battery must include prompts from users who:

- misspell component and business terms;
- do not know component names;
- ask a question before asking for a change;
- answer a clarification with short or vague text;
- quote part of an assistant answer as the next turn;
- paste numbered or partial text from the previous answer;
- change their mind after the assistant recommends a direction;
- mix business intent with UI words such as "grade", "lista", "botao", "tela" and "filtro".

## Flow Points To Verify

Each scenario should capture evidence for these points:

- `conversation`: prompt trimming, clarification carry-over and copied-text handling.
- `intent`: semantic operation, artifact kind, target component and selected resource.
- `retrieval`: query sent to component corpus, `sourceId`, `chunkKind`, `sourceRef`, release and tenant/env scope.
- `planning`: `contextHints.authoringEvidence` reaches `AgenticAuthoringPlanRequest`.
- `governance`: preview/compile/apply gates stay active; no local-only mutation is accepted.
- `diagnostics`: safe diagnostics expose counts and source refs, not raw secrets or payloads.
- `answer`: assistant responds in business/user language unless API details were explicitly requested.

## Initial Deterministic Scenarios

| Id | Human prompt shape | Expected grounding |
| --- | --- | --- |
| `messy-table-toolbar-export` | "bota um botao na toobar da tabela pra exporta selecionado" | `praxis-table`, `recipe` or `authoring_manifest`, toolbar/actions source refs |
| `question-before-change` | "da pra fazer isso na tabela antes de eu pedir?" then a concrete change | First turn can be consultative; second turn must retrieve component evidence before preview |
| `copied-assistant-fragment` | user quotes "source refs" or "evidencias governadas" and asks what it means | Treat as new consultative/user question, not as accidental confirmation of an old clarification |
| `short-clarification-answer` | assistant asks source/domain; user answers "folha memo por departameto" | Preserve original goal and append the answer as clarification context |
| `unknown-component-name` | "naquela grade/lista coloca filtro e acao" | Resolve through context hints/current page when available; retrieve component corpus using selected/target component |
| `business-confusion-repair` | "risco das pessoas da empresa" then "operacional, com responsaveis" | Ask or repair domain choice; do not launder the previous wrong source through memory |

## Pass Criteria

- At least one relevant authoring evidence entry is attached for materialization turns.
- `sourceRef` is repo-relative and points to official component docs, recipes or generated corpus source.
- `chunkKind` is relevant to the requested action.
- The retrieval query preserves the user's natural words instead of collapsing into only normalized filter tokens.
- `preview.valid=true` for supported materializations, or `canApply=false` with a business-safe explanation for unsupported ones.
- `decisionDiagnostics.authoringEvidenceCount > 0` when a component authoring change is planned.
- Diagnostics include source refs but not raw provider keys, absolute local paths or unbounded document payloads.

## Execution Layers

1. Deterministic Java tests in `praxis-config-starter`.
   These validate conversation behavior, retrieval invocation, context injection and diagnostics without provider calls.

2. Local HTTP/browser smoke with `praxis-api-quickstart` and `praxis-ui-angular`.
   This validates SSE events, preview rendering and human transcript capture. It requires the official local datasource and provider configuration.

3. Optional live provider review.
   This is for semantic quality only. It must not be the canonical regression baseline.

## Evidence To Save Per Manual Run

- transcript of user and assistant turns;
- event phases from the stream;
- retrieved `sourceRefs` and `chunkKind`;
- preview/apply result;
- final page model snapshot when available;
- screenshot after accepted materialization;
- notes for any hallucinated component capability or missing clarification.

