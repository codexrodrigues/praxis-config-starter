# Semantic Decision Governance Public Proof Plan

Status: implementation planning  
Date: 2026-04-25  
Classification: `arquitetural` / `contrato-publico`

## Purpose

This plan maps the current `praxis-config-starter` evidence that can support a public Praxis enterprise proof for governed semantic decisions.

The goal is not to invent a new local demo. The goal is to expose the canonical platform path already present under `/api/praxis/config/**`:

```text
intent -> semantic grounding -> governed rule definition -> simulation -> approval/status -> publication -> materialization
```

## Canonical Boundary

`praxis-config-starter` owns the decision-governance boundary for:

- `ui_user_config`
- `ai_registry`
- `api_metadata`
- domain catalog releases and context retrieval
- domain federation context
- agentic authoring manifests and streaming
- shared semantic rules under `/api/praxis/config/domain-rules/**`
- runtime/backend materializations derived from governed decisions

`praxis-metadata-starter` remains the canonical source for backend semantic grounding. `praxis-ui-angular` remains the runtime/cockpit that consumes materializations. `praxis-api-quickstart` is the operational proof host, not the source of contract semantics.

## Confirmed Surfaces Today

### Grounding And Context

| Surface | Current proof value |
| --- | --- |
| `POST /api/praxis/config/domain-catalog/ingest` | Persists generated semantic releases. |
| `GET /api/praxis/config/domain-catalog/items/latest` | Exposes latest catalog items. |
| `GET /api/praxis/config/domain-catalog/context` | Returns compact context and retrieval guidance for authoring/LLM flows. |
| `GET /api/praxis/config/domain-catalog/relationships/latest` | Exposes explicit relationship edges. |
| `GET /api/praxis/config/domain-federation/context` | Retrieves governed/federated context with retrieval policy report. |
| `POST /api/praxis/config/domain-federation/dry-run` | Validates federation payloads before ingestion. |
| `POST /api/praxis/config/domain-federation/ingest` | Persists federated semantic releases. |

### Agentic Authoring

| Surface | Current proof value |
| --- | --- |
| `POST /api/praxis/config/ai/authoring/intent-resolution` | Classifies user intent and can route shared-rule authoring away from component preview. |
| `POST /api/praxis/config/ai/authoring/turn/stream/start` | Starts governed authoring stream. |
| `GET /api/praxis/config/ai/authoring/turn/stream/{streamId}` | Streams authoring events via SSE. |
| `POST /api/praxis/config/ai/authoring/page-preview` | Produces preview only when the request remains component/page authoring. |
| `POST /api/praxis/config/ai/authoring/page-apply` | Applies eligible page authoring outputs. |
| `GET /api/praxis/config/ai/authoring/manifests/{componentId}` | Reads executable authoring manifests from `ai_registry`. |
| `POST /api/praxis/config/ai/authoring/manifests/{componentId}/validate-plan` | Validates manifest-backed plans before compile/apply. |
| `POST /api/praxis/config/ai/authoring/manifests/{componentId}/compile-patch` | Compiles component effects while leaving domain effects as explicit compiler boundaries. |

### Shared Semantic Rules

| Surface | Current proof value |
| --- | --- |
| `POST /api/praxis/config/domain-rules/intake` | Captures a natural-language decision/rule request as a governed draft. |
| `POST /api/praxis/config/domain-rules/definitions` | Persists explicit rule definitions with semantic owner, steward, source release/change-set and governance payload. |
| `GET /api/praxis/config/domain-rules/definitions` | Lists definitions by tenant/environment, resource, status, rule type or key. |
| `PATCH /api/praxis/config/domain-rules/definitions/{definitionId}/status` | Records lifecycle transitions for definitions. |
| `GET /api/praxis/config/domain-rules/definitions/{definitionId}/timeline` | Projects a safe governance timeline for definitions and materializations without returning prompts, conditions, parameters or materialized payloads. |
| `POST /api/praxis/config/domain-rules/simulations` | Returns diagnostics, existing coverage, predicted materializations, required approvals, warnings and explainability. |
| `POST /api/praxis/config/domain-rules/publications` | Publishes ready definitions and derives/applies eligible materializations. |
| `POST /api/praxis/config/domain-rules/materializations` | Creates explicit materializations for runtime/backend targets. |
| `GET /api/praxis/config/domain-rules/materializations` | Lists materializations by definition and target. |
| `PATCH /api/praxis/config/domain-rules/materializations/{materializationId}/status` | Controls materialization lifecycle without bypassing definition governance. |

## Existing Evidence

The following files already support the proof narrative:

- `docs/ai/agentic-domain-task-envelope.md`
- `docs/ai/release-readiness-2026-04-25-domain-rules.md`
- `docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml`
- `docs/domain-catalog/governed-semantic-layer-plan.md`
- `docs/domain-catalog/domain-knowledge-layer-v1.md`
- `src/test/java/org/praxisplatform/config/controller/DomainRuleControllerTest.java`
- `src/test/java/org/praxisplatform/config/service/DomainRuleServiceTest.java`
- `src/test/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringIntentResolverServiceTest.java`

Important current proof:

- domain-rule endpoints are present in the OpenAPI contract;
- intent resolution tests guard `shared_rule_authoring`;
- release-readiness notes document HTTP smoke evidence and diagnostics checkpoints;
- domain rule service/controller tests cover lifecycle, simulations, publications and materialization diagnostics.

## Claim Matrix

| Claim | Class | Evidence |
| --- | --- | --- |
| Config starter exposes `/domain-rules/**` for shared semantic rules. | Confirmed today | Controller, DTOs, OpenAPI contract and tests. |
| Shared-rule authoring can be routed away from component/page patches. | Confirmed today | `agentic-domain-task-envelope.md` and intent resolver tests. |
| Simulation returns backend-owned diagnostics and explainability. | Confirmed today | `DomainRuleSimulationResponse`, service tests and release-readiness notes. |
| Publication can derive/apply eligible materializations. | Confirmed today | `DomainRulePublicationResponse`, service tests and release-readiness notes. |
| Quickstart can serve as public operational proof for domain-rule lifecycle. | Publicly proved / needs packaging | Release-readiness references HTTP smoke; public HTTP examples still need a compact buyer-facing bundle. |
| Full AI-authored decision lifecycle is ready as first-fold marketing copy. | Architectural direction | Needs curated public proof, stable examples and derived docs before being promoted broadly. |

## Recommended Public Proof Slice

Use a single buyer-readable scenario:

> "Supplier eligibility decision: inactive or blocked suppliers cannot appear as selectable options in purchase workflows."

Why this scenario:

- maps naturally to `selection_eligibility`;
- can derive an `option_source` materialization;
- is understandable to buyers without deep UI vocabulary;
- shows why this is not a component patch;
- demonstrates simulation and publication diagnostics.

Minimum proof flow:

1. Ingest or select domain catalog context for the target resource.
2. Resolve prompt intent as `shared_rule_authoring`.
3. Call `POST /api/praxis/config/domain-rules/intake`.
4. Call `POST /api/praxis/config/domain-rules/simulations`.
5. Transition the definition to `approved`.
6. Call `POST /api/praxis/config/domain-rules/publications`.
7. Read `GET /api/praxis/config/domain-rules/materializations`.
8. Show the derived `option_source` materialization as the runtime/backend projection.

## Published Runtime Evidence

The public quickstart has now confirmed the first governed semantic decision publication path.

Confirmed on `2026-04-26` with:

```bash
SMOKE_RUN_ID=enterprise-proof-http-examples-script-20260426 \
npm run smoke:domain-rules-publication
```

The run confirmed simulation explainability, definition lifecycle, publication, derived `option_source` materialization for `targetArtifactKey=supplier`, materialization readback, and supplier lookup runtime behavior returning `selectable=false` under the published policy.

## Remaining Gaps Before Broader Landing Promotion

- Keep write examples for intake, definition, approval and publication as protected contract evidence; do not promote them to safe-first LLM execution.
- Keep the buyer-facing page linked to read-only operational ids and the published-runtime runbook without implying safe default write execution.
- Keep broader claims about backend validation and workflow-action materializations as `architectural_direction` until their command probes are authenticated and repeatable in the public environment.

## Next Implementation Backlog

1. Add or identify a stable quickstart fixture for supplier eligibility or an equivalent operational rule.
2. Keep `smoke:domain-rules-publication` green on the published backend.
3. Keep the read-only ids `domain-rules-supplier-eligibility-materializations-confirmed` and `procurement-suppliers-governed-domain-rules-lookup` green in `smoke:llm-surface` and `smoke:corpus-promises`.
4. Create a derived landing proof section that links to the HTTP ids and keeps write flows clearly governed.
5. Promote the claim from `architectural_direction` to `publicly_proved` only after the public examples pass validation.

## Derived HTTP Evidence Packaged

The first protected HTTP bundle now lives in `praxisui-http-examples`:

- `domain-rules-supplier-eligibility-intake`
- `domain-rules-supplier-eligibility-simulation`
- `domain-rules-supplier-eligibility-definition`
- `domain-rules-supplier-eligibility-approve`
- `domain-rules-supplier-eligibility-publication`
- `domain-rules-supplier-eligibility-materializations`

The write examples intentionally remain `protectedContract`, not `llmOperational`, because they document the canonical enterprise proof path without making write execution a safe-first default. The read-only proof ids `domain-rules-supplier-eligibility-materializations-confirmed` and `procurement-suppliers-governed-domain-rules-lookup` are now part of the LLM operational surface.

## Validation Guidance

Docs-only changes in this plan require:

- final read-through of this document;
- `git diff --check`.

When this plan turns into endpoint, contract or smoke changes, use at least:

- focal `DomainRuleServiceTest` / `DomainRuleControllerTest`;
- OpenAPI contract validation if the payload changes;
- quickstart HTTP smoke for `/api/praxis/config/domain-rules/**`.
