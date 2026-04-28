# Domain Rule Timeline v2 Intake and Simulation Source Plan

Date: 2026-04-28

Status: implementation-ready locally; release publication remains deferred.

## Purpose

Timeline v2 closes the next observability gap for semantic decisions authored by AI by recording the governed authoring handoff itself:

- `intake.received`
- `simulation.requested`
- `simulation.completed`

These events belong to `praxis-config-starter` because `/api/praxis/config/domain-rules/**` is the canonical boundary for decision intake, governance, simulation, publication and materialization.

The timeline remains a safe observability projection. It is not a second source of business truth, and it must not reconstruct decisions from prompts, assistant messages, frontend state or quickstart smoke output.

## Canonical Sources

`intake.received` is sourced from the persisted `DomainRuleDefinition` created by `DomainRuleService.intake`.

Safe metadata is intentionally allowlisted:

- `decisionStage`
- `definitionStatus`
- `existingCoverageCount`
- `predictedMaterializationCount`
- `requiredApprovalCount`
- `warningCount`

`simulation.requested` and `simulation.completed` are sourced only when `DomainRuleService.simulate` resolves a persisted `DomainRuleDefinition` through `ruleDefinitionId`.

`simulation.requested` safe metadata is intentionally minimal:

- `decisionSource=persisted_definition`

`simulation.completed` safe metadata is intentionally allowlisted:

- `result`
- `publicationReadiness`
- `existingCoverageCount`
- `predictedMaterializationCount`
- `requiredApprovalCount`
- `warningCount`

## Deliberate Exclusions

The following remain excluded from public-safe timeline metadata:

- prompt text;
- assistant message;
- rule condition;
- rule parameters;
- materialized payload;
- diagnostics arrays;
- option source keys;
- blocked statuses;
- validation templates;
- required approver identities.

Ad hoc simulations still do not emit public timeline events. They do not yet have a durable governance source independent of the transient request, so exposing them as timeline events would make the platform look more audited than it is.

## Implementation Evidence

`praxis-config-starter`:

- PR #129 records `intake.received` after governed intake persists the draft definition.
- PR #130 records `simulation.requested` and `simulation.completed` for simulations anchored in persisted definitions.
- Unit coverage verifies safe metadata allowlists and protects against prompt, assistant, condition, parameters, materialized payload and diagnostics leakage.
- Focal local validation passed with:
  - `git diff --check`
  - `mvn -Dtest=DomainRuleServiceTest#intakeCreatesDraftDefinitionAndGroundingForSimulation,DomainRuleServiceTest#definitionTimelinePrefersPersistedSafeEventsWhenAvailable,DomainRuleMigrationConstraintTest,AiApiContractOpenApiTest test -q`
  - `mvn -Dtest=DomainRuleServiceTest#simulatesProcurementRuleAndDetectsExistingCoverage,DomainRuleServiceTest#persistedSimulationRecordsSafeTimelineEventsWithoutDiagnosticsPayloads,DomainRuleServiceTest#intakesAndPublishesProcurementSupplierEligibilityDecisionAsOptionSourceProjection,DomainRuleMigrationConstraintTest,AiApiContractOpenApiTest test -q`

`praxis-api-quickstart`:

- PR #40 routes the runtime smoke through `POST /api/praxis/config/domain-rules/intake`.
- The smoke then simulates with the persisted `ruleDefinitionId`.
- `REQUIRE_TIMELINE=true` now requires `intake.received`, `simulation.requested` and `simulation.completed` on the governed `form_config` path.
- Local quickstart + Neon proof passed with:
  - `BACKEND_URL=http://localhost:8088 REQUIRE_TIMELINE=true scripts/verify-domain-rules-runtime.sh`
- The proof returned `eventCount=10` for `form_config` and `eventCount=9` for the published `option_source` path.

Monorepo:

- `scripts/workspace/check-v0-readiness.sh` passed after the v2 smoke gate merge.
- Local backend was stopped cleanly and ports `4003` and `8088` were free.
- No Maven/npm publication and no GitHub Actions were used for implementation work.

## Release Recommendation

Do not publish Maven only because timeline v2 is implementation-ready.

If the platform owner explicitly decides to close the timeline observability phase, prefer releasing the current `praxis-config-starter/main` as a single coordinated artifact containing v1 and v2 persisted event families, rather than publishing v1 and v2 in separate release cycles.

If no newer `v0.1.0-rc.*` tag exists at release time, the next intended coordinate remains:

- `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.36`
- tag: `v0.1.0-rc.36`

After Maven Central resolves the coordinate, update `praxis-api-quickstart` to consume that public version without local overrides and run one published-runtime smoke with `require_timeline=true`.

## Acceptance Criteria

Timeline v2 is implementation-ready when all are true:

- `praxis-config-starter/main` records persisted `intake.received` for governed intake.
- `praxis-config-starter/main` records persisted `simulation.requested` and `simulation.completed` only for simulations anchored in persisted definitions.
- Safe metadata remains allowlisted and excludes prompt, assistant message, condition, parameters and materialized payload.
- `praxis-api-quickstart/main` routes its governed `form_config` smoke through intake before simulation.
- Local quickstart + Neon smoke passes with `REQUIRE_TIMELINE=true` and returns `eventCount=10` for `form_config`.
- Monorepo readiness passes locally.

Timeline v2 is published when all are true:

- Maven Central resolves the selected `praxis-config-starter` coordinate.
- `praxis-api-quickstart` consumes that coordinate without local overrides.
- Published quickstart smoke passes with `require_timeline=true`.
- Public docs and HTTP examples describe timeline as safe observability over persisted governance events, not as a source of decision truth.
