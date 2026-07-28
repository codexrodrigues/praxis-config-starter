# Praxis Generative UI Platform Program

Status: active program anchor
Created: 2026-07-27
Current milestone: Phase 1 — reproducible component inventory
Canonical program owner: `praxis-config-starter`
Change class of this package: `docs-apenas`
Git persistence at handoff: validated in the repository worktree; not committed or pushed

## Mission

Enable a user to consult, create, refine, validate, apply, observe and explain Praxis interfaces
from natural language, including spoken and imperfect language, without making the LLM a generator
of arbitrary JSON, Angular code or business rules.

In this program, a fully generative Praxis interface means:

> A UI composed and changed from semantic intent, governed domain evidence and declared Praxis
> capabilities, with explainable component selection, deterministic transformation, authorization,
> preview, apply, runtime observation, evidence-based explanation and recovery.

The table is the first complexity pilot. It is not a special architecture and is not the boundary
of the program. The method proven with `praxis-table` must be reusable for every Praxis component
family and for compositions involving several components.

## Why this program exists

The platform already has substantial foundations:

- governed Domain Catalog, Domain Knowledge, bindings, releases and evidence;
- progressive semantic tools and retrieval profiles;
- component capabilities, authoring manifests and registry ingestion;
- `UiCompositionPlan` and a component-selection evidence envelope;
- backend validation, compilation, preview/apply and SSE orchestration;
- runtime component observation as untrusted evidence;
- RAG, OpenAI Responses, hosted Skills support, provider telemetry and eval corpora.

The table investigation proved a continuity gap between those foundations. A model can select the
right semantic operation and the operation can compile, while a required dependency is not
materialized and the UI remains unchanged. The current system can then emit a successful-sounding
message without proof of the visible outcome.

This program closes the following chain:

```text
governed domain meaning
  -> semantic UI decision
  -> capability-based component selection
  -> executable component operation
  -> dependency closure
  -> deterministic compilation
  -> validation and materialization
  -> runtime outcome observation
  -> explanation supported by evidence
```

## Existing architecture to preserve

This package extends and connects existing work. It must not replace it.

1. [Machine-First Semantic IR RFC](../../2026-07-machine-first-semantic-ir-rfc.md) owns the
   progressive domain-to-resource-to-component grounding direction.
2. [Machine-First acceptance corpus](../2026-07-machine-first-generative-ui-acceptance-corpus.md)
   owns the falsifiable grounding, retrieval-economy and UI-selection evaluation baseline.
3. [Human Resources baseline inventory](../2026-07-machine-first-hr-baseline-inventory.md) is the
   reference pattern for auditing what the domain layer already knows.
4. [RAG industrialization](../rag-industrialization/README.md) owns corpus generation, publication,
   retrieval and provider projections. Do not create a parallel RAG pipeline.
5. [Agentic authoring implementation guide](../agentic-authoring/implementation/README.md) owns the
   turn engine, transport, apply lineage, provider gates and historical implementation evidence.
6. `praxis-ui-angular` owns public component runtime/editor contracts, capabilities and source
   authoring manifests.
7. `praxis-metadata-starter` owns canonical backend resource schemas, surfaces, actions,
   capabilities and availability.
8. `praxis-api-quickstart` remains the downstream operational proof host.

The component capability certification graph described here is a derived conformance view over
those owners. It must not become another source of component, domain or business truth.

## Documents in this package

Read these in order when opening a new Codex session:

1. [Baseline evidence](BASELINE-EVIDENCE-2026-07-27.md)
2. [Architecture continuity](ARCHITECTURE-CONTINUITY.md)
3. [Capability coverage model](CAPABILITY-COVERAGE-MODEL.md)
4. [Domain-to-component continuity](DOMAIN-TO-COMPONENT-CONTINUITY.md)
5. [Program plan and milestones](PROGRAM-PLAN.md)
6. [Quality gates](QUALITY-GATES.md)
7. [Current state](CURRENT-STATE.md)
8. [Orchestrator rules](ORCHESTRATOR.md)
9. The active phase in [`phases/`](phases/phase-01-component-inventory.md)
10. [Review checklist](REVIEW-CHECKLIST.md)

The ready-to-copy prompt for the next session is
[`prompts/phase-01-execute.prompt.md`](prompts/phase-01-execute.prompt.md). Its independent review
prompt is [`prompts/phase-01-review.prompt.md`](prompts/phase-01-review.prompt.md).

Only the currently authorized phase has a final prompt pair. Later prompts are intentionally not
pre-frozen: each phase's evidence and independent review determine the exact inputs and guardrails
for the next one. The checkpoint-recording step must create and review the next execution/review
pair before `CURRENT-STATE.md` advances. The durable specifications for all seven phases already
exist under [`phases/`](phases/phase-01-component-inventory.md).

## Program phases

| Phase | Outcome | Status |
| --- | --- | --- |
| 0 | Evidence and program package frozen locally | complete locally; Git commit/push pending |
| 1 | Reproducible component-capability inventory | next |
| 2 | Executable operation contract and vertical proof | pending |
| 3 | Complete `praxis-table` certification pilot | pending |
| 4 | Knowledge, tools, Skills, retrieval and eval integration | pending |
| 5 | Domain -> component -> operation continuity | pending |
| 6 | Multi-component rollout by certified waves | pending |
| 7 | Generative UI platform readiness gate | pending |

Each phase has a blocking checkpoint. A phase may not be marked complete by prose, a generated
card, a valid JSON shape or an assistant message. Its quality gate requires deterministic evidence
at the applicable layers.

## Non-goals

- Do not generate arbitrary component JSON or source code as the primary authoring model.
- Do not copy all registry content into every prompt.
- Do not use keywords, regexes or aliases as primary intent routing.
- Do not make Skills, embeddings, vector rows, examples or frontend observations authoritative.
- Do not recreate Semantic IR, Domain Catalog, `UiCompositionPlan`, RAG or runtime observation in
  a second contract.
- Do not make every registry entry authorable. Every entry must be classified, but runtime helpers,
  probes and derived surfaces may intentionally remain read-only or non-authorable.
- Do not fix isolated table cases as a substitute for the reusable certification method.

## Durable definition of success

The program is complete only when:

- every public component capability is classified;
- every authorable operation has grounded targets, deterministic effects, dependency closure,
  validation and explicit success conditions;
- every visible claim of success is supported by runtime outcome evidence;
- domain meaning and current capabilities select compatible components without invented fields,
  operations, inputs or actions;
- creating, refining, explaining, applying and recovering are proven across representative
  component families and multi-component pages;
- a public component change produces detectable certification drift;
- human-language evals are separated from deterministic execution correctness;
- provider-specific resources improve discovery and procedure without becoming canonical truth.

## Session continuity rule

The baseline evidence IDs `B-001` through `B-014` are accepted findings. A future session must not
repeat the full investigation. It revalidates an item only when its source changed since the
recorded revision, a focused test contradicts it, or the item is explicitly marked as a hypothesis.
See [ORCHESTRATOR.md](ORCHESTRATOR.md).
