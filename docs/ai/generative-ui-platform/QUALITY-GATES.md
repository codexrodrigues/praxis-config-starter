# Quality Gates

## Principle

The system must prove each layer independently. A later layer cannot hide a failure in an earlier
one, and fluent model output cannot substitute for deterministic evidence.

## Gate stack

| Gate | Question | Required authority |
| --- | --- | --- |
| G0 provenance | Did all evidence come from the current governed release/tenant/environment? | canonical IDs, release and policy |
| G1 semantic grounding | Did the model resolve the correct domain/UI intent? | Semantic IR and governed tools |
| G2 component selection | Can the selected component set satisfy the requirements? | capability catalog and certification state |
| G3 operation selection | Are operation, target and arguments canonical? | projected source manifest and schemas |
| G4 execution | Are resolver, validator and effect/handler implemented? | backend registries and focused tests |
| G5 state safety | Are dependencies, post-state and invariants satisfied? | deterministic simulation/validation |
| G6 outcome | Did the materialized runtime/state exhibit the requested result? | applicable state/DOM/event observer |
| G7 explanation | Does every success claim follow from verified evidence? | proof bundle and claim policy |
| G8 language | Can human phrasing select the same governed intent safely? | repeatable eval dataset |
| G9 integration | Does the reference host prove the whole flow? | quickstart HTTP/SSE and focal browser gate |

## False-success prohibition

The assistant may say an outcome is prepared, visible, enabled, filtered, saved, approved or applied
only when the corresponding authority exists.

Examples:

- operation ID present is not runtime visibility;
- non-empty proposed config is not semantic fidelity;
- valid JSON is not a valid component state;
- `simulation.result=pass` is not execution against business records;
- current frontend observation is not apply authorization;
- a predicted materialization is not a persisted materialization;
- a Skill invocation is not validator execution.

Every terminal result should expose the strongest achieved state and any missing gate.

## Required evidence bundle at a checkpoint

Every phase close records:

- repository, branch and HEAD for each source owner;
- pre-existing dirty files and files changed by the phase;
- generated artifact identity/hash when applicable;
- commands executed and exact result counts;
- deterministic tests and negative cases;
- provider/model/effort/Skill versions for real-LLM cases;
- runtime/HTTP evidence refs, sanitized of secrets and private rows;
- metrics and comparison baseline;
- incomplete or unvalidated work;
- adherence classification and canonical owner for each gap;
- next phase prompt.

## Phase 1 gate

- counts reconcile with `B-001...B-003` or a source delta explains the difference;
- all public paths are classified;
- no manually curated list is presented as generated coverage;
- operation and dependency joins preserve stable IDs and source refs;
- generated family projections are deduplicated by canonical manifest identity;
- the report distinguishes absence, intentional non-authorability and projection drift;
- no public contract change is mixed into inventory work.

## Phase 2 gate

- `rowAction.add` starts with `actions.row.enabled=false` or absent;
- deterministic output contains the dependency closure;
- repeat is idempotent and duplicate IDs fail/preserve according to contract;
- invalid/ambiguous/conflicting cases fail predictably;
- table runtime renders and exposes the intended affordance;
- negative observation detects an absent/hidden action;
- message synthesis distinguishes compiled from observed;
- source manifest, backend registry and generated corpus agree.

## Phase 3 gate

- every table operation has minimal, boundary and negative state evidence;
- dependency clusters drive combinatorial cases;
- round-trip preserves unrelated configuration;
- visual operations have observer coverage;
- consult questions do not fabricate edit plans;
- spoken variants do not change deterministic semantics;
- report does not call structural card recall “functional completeness.”

## Phase 4 gate

- trace proves which tools/chunks/Skills were actually available and used;
- exact operation evidence includes relevant dependency/state evidence;
- provider projection is tied to registry release/hash;
- vector outage has structured fallback or honest clarification;
- retrieval eval uses labeled Praxis queries and reports recall/ranking, not anecdotes;
- model comparison uses identical cases and separates quality, latency, cost and variance;
- no secret or unrestricted private record enters provider storage or logs.

## Phase 5 gate

- domain concept and resource binding precede operation/schema inspection;
- current capabilities/availability constrain component selection;
- shared rule requests do not become local UI patches;
- entity identity and field writeability are proven separately;
- multi-component plan preserves ownership and event/state contracts;
- selected operations have required certification maturity;
- no lexical primary routing.

## Phase 6/7 gate

- certification method runs unchanged across representative families;
- non-authorable components remain explicit, not silently missing;
- new public surface creates actionable drift;
- integrated flows include failure and recovery;
- public readiness claims match the certified matrix exactly;
- release gates are local-first and reproducible.

## Model and provider evaluation

Use deterministic fixtures to prove execution. Use real models to measure semantic selection and
conversation quality. At minimum record:

- model and explicit reasoning effort;
- request/case IDs;
- selected tool/operation/target;
- argument accuracy;
- clarification result;
- terminal state achieved;
- input/output/reasoning tokens when available;
- latency and estimated cost;
- repeated-run pass rate.

Do not select Terra, Luna, an embedding model or a provider configuration by one successful demo.

OpenAI traces/graders/datasets may store evaluation evidence, but the acceptance source remains the
versioned Praxis corpus and deterministic platform assertions.
