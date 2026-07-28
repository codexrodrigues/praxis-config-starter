# Phase 4 — Knowledge, Tools, Skills and Evals

Checkpoint: `CP-4`
Status: pending `CP-3` acceptance

## Objective

Make certified component-operation evidence available to the model progressively and economically,
without copying the whole registry into prompts or transferring canonical authority to OpenAI
Skills, File Search, embeddings or any other provider resource.

This phase integrates foundations that already exist. It does not create another RAG pipeline,
another operation registry or a provider-specific source of truth.

## Required evidence bundle

Retrieval of an authoring operation must return a coherent, release-scoped bundle rather than an
isolated operation name:

```text
semantic operation identity
  + compatible component/profile and target policy
  + arguments/schema and authorization requirements
  + current capability/dependency/conflict evidence
  + deterministic executor/validator references
  + applicable success conditions and observer references
  + provenance, tenant/environment and release/hash
```

The bundle is a derived read model over canonical owners. The backend still validates every
operation and closes or rejects dependencies deterministically.

## Workstreams

### 1. Progressive internal tools

- expose discovery, inspection, planning, preview, apply and observation through bounded tools;
- return summaries first and exact evidence on demand;
- bind every result to tenant, environment, release and authorization context;
- keep tool contracts stable while provider adapters remain replaceable;
- evaluate OpenAI Tool Search/MCP as discovery transport, not as the canonical implementation;
- reject stale or mismatched evidence before compilation.

### 2. Registry and retrieval projection

- extend the existing `ai_registry`/RAG projections only where the certified evidence cannot yet be
  retrieved coherently;
- join operation, dependencies, constraints and observer evidence before model consumption;
- preserve stable source identity and avoid counting profile projections as new source manifests;
- measure whether the current retrieval budgets omit necessary operation dependencies;
- keep PostgreSQL/`ai_registry` as the canonical retrieval boundary and File Search as a derived
  release projection when used.

### 3. Hosted Skills

Skills teach durable procedure, for example how to discover capabilities, request clarification,
preview a change and verify an outcome. They must call canonical tools for mutable facts.

For every Skill used in a test or release claim, prove:

- configured Skill ID and immutable/versioned content;
- attachment to the actual Responses request;
- presence in provider trace/telemetry;
- supported model/provider compatibility;
- exact registry/tool release used by the procedure;
- behavior when the Skill is absent or stale.

Do not paste mutable capability inventories or business rules into a Skill. A published but
unattached Skill contributes no runtime evidence.

### 4. Embedding and retrieval benchmark

The baseline already uses `text-embedding-3-large` at 3072 dimensions. Do not change model,
dimensions, distance strategy or index because another embedding looks newer or cheaper.

Build a Praxis-specific benchmark containing:

- exact, paraphrased, spoken and domain-specific component intents;
- near-neighbor components and operations that must be distinguished;
- operation/dependency bundle retrieval;
- tenant/release filtering and stale projection negatives;
- Portuguese and applicable multilingual terminology;
- hard negatives where lexical similarity conflicts with semantic compatibility.

Compare hybrid retrieval and candidate alternatives using recall@k, nDCG, false-positive rate,
latency, storage and reindex cost. A migration proposal requires a measured improvement and a safe
reindex/rollback plan. Embeddings cannot compensate for missing operation contracts.

### 5. Layered evals

Score these layers independently:

1. semantic intent and domain/resource/action grounding;
2. component and operation selection;
3. argument/target grounding and clarification;
4. deterministic validation, dependency closure and compilation;
5. canonical post-state;
6. runtime outcome observation;
7. explanation fidelity and recovery.

An end-to-end pass must retain the layer results so a fluent message cannot hide a compilation or
runtime failure. Deterministic invariants are release blockers; stochastic model metrics are
reported with thresholds and variance.

### 6. Model/cost comparison

Run Terra and Luna with the same released registry, Skills, tools, effort level, cases and reset
state. Record at least:

- model and explicit effort;
- repeated-run selection and argument variance;
- clarification behavior;
- useful-preview and false-success rates;
- latency, input/output tokens and provider cost;
- terminal failure taxonomy.

Model comparison must not change the deterministic executor between cohorts.

### 7. Security and observability

- redact secrets and private registry rows from prompts, traces and artifacts;
- preserve tenant/environment isolation in retrieval and tool execution;
- distinguish provider failure, retrieval miss, authorization denial and contract failure;
- record source release/hash for every material decision;
- make provider-resource drift visible before a release claim.

## Deliverables

- versioned operation evidence-bundle contract/projection or proof that existing structures suffice;
- progressive tool catalog and provider-adapter conformance tests;
- Skill attachment/version/trace evidence for applicable journeys;
- reproducible retrieval and embedding benchmark;
- layered eval corpus and report schema;
- table C7 qualification report over the labeled Phase 3 corpus;
- Terra/Luna comparison report using identical conditions;
- provider failure and stale-evidence test report;
- updated `CURRENT-STATE.md` and independent review handoff.

## Checkpoint criteria

- [ ] Operation retrieval includes applicable dependencies, validation and outcome references.
- [ ] Tools are progressively discoverable and enforce current tenant/release/authorization.
- [ ] Provider adapters do not replace internal canonical contracts.
- [ ] Every claimed Skill is attached, pinned and visible in trace.
- [ ] Skills contain procedure rather than mutable component/domain truth.
- [ ] No second RAG/vector source of truth was introduced.
- [ ] Current embedding/retrieval and alternatives are compared on a Praxis benchmark.
- [ ] Evals expose failures separately at all seven layers.
- [ ] The labeled table corpus closes C7 under explicit, repeatable thresholds.
- [ ] Terra/Luna results record effort, cost, latency and repeated-run variance.
- [ ] Provider outage/staleness fails closed without false success.
- [ ] Independent review accepts the evidence and reproducibility.

## Non-goals

- certifying a provider model by a small demonstration script;
- increasing context size as a substitute for coherent evidence retrieval;
- moving backend invariants into prompts or Skills;
- replacing the existing `ai_registry` industrialization program;
- selecting an embedding provider without an application-specific benchmark.
