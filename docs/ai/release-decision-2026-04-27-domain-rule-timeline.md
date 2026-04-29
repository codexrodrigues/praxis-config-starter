# Release Decision: Domain Rule Governed Timeline

Date: 2026-04-27
Closed: 2026-04-29

Status: published and closed as the coordinated `praxis-config-starter:0.1.0-rc.36` timeline release.

## Scope

This decision covers the read-only governed timeline endpoint:

- `GET /api/praxis/config/domain-rules/definitions/{definitionId}/timeline`

The endpoint belongs to `praxis-config-starter` because `/api/praxis/config/**` is the canonical boundary for semantic decisions authored by AI, governance, materialization and safe observability.

The timeline is not a new source of truth. It is a safe observability projection over persisted domain-rule lifecycle events, with compatibility fallback to canonical definition and materialization state for older records.

## Current Evidence

- `praxis-config-starter` PR #118 added the timeline endpoint and safe lifecycle derivation.
- `praxis-config-starter` PR #119 added OpenAPI contract protection for the endpoint and DTO schemas.
- `praxis-config-starter` PR #122 added the append-only `domain_rule_event` persistence source.
- `praxis-config-starter` PR #123 added transactional writes for definition and materialization lifecycle events.
- `praxis-config-starter` PR #124 made the public endpoint read persisted events when available, preserving derived fallback for older records.
- `praxis-config-starter` PR #125 added durable `publication.requested` and `publication.completed` events on successful publication.
- `praxis-config-starter` PR #126 added durable `approval.requested` and `approval.completed` events from persisted governance and approval timestamps.
- `praxis-ui-angular` PRs #81 to #86 added types, service consumption, rich-content projection, cockpit rendering, host spec alignment and a local managed E2E runner.
- `praxis-api-quickstart` PR #37 documented the rollout and exposed the manual `require_timeline` workflow input.
- `praxis-api-quickstart` PR #38 requires persisted publication events when `REQUIRE_TIMELINE=true` on the published `option_source` path.
- `praxis-api-quickstart` PR #39 requires persisted approval events when `REQUIRE_TIMELINE=true` on the governed `form_config` path.
- `praxis-config-starter` PR #129 added durable `intake.received` events after governed intake persists the draft definition.
- `praxis-config-starter` PR #130 added durable `simulation.requested` and `simulation.completed` events for simulations anchored in persisted definitions.
- `praxis-api-quickstart` PR #40 routes the governed `form_config` runtime smoke through intake, simulates with the persisted `ruleDefinitionId` and requires intake/simulation events when `REQUIRE_TIMELINE=true`.
- The 2026-04-28 local browser cockpit E2E proved governed create, simulate, approve and activate from the shared rules handoff, and identified two additional post-`rc.35` requirements for the same coordinated starter cut: canonical shared-rule type hints must not emit non-contract values, and business/shared-rule authoring must preserve explicit `/api/{context}/{resource}` paths during intent resolution.
- `praxis-ui-landing-page` PR #28 sharpened the public Home positioning around governed semantic decisions and enterprise proof.
- `praxisui-http-examples` PR #2 added a protected HTTP example and kept it marked as a known published-runtime failure while the deployed quickstart still returns `404`.
- Local quickstart + Neon proof passed with `BACKEND_URL=http://localhost:8088 REQUIRE_TIMELINE=true scripts/verify-domain-rules-runtime.sh`, returning `eventCount=7` for both the approval-backed `form_config` path and the publication-backed `option_source` path.
- After timeline v2, local quickstart + Neon proof passed with the same command, returning `eventCount=10` for the intake/simulation-backed `form_config` path and `eventCount=9` for the publication-backed `option_source` path.
- Monorepo readiness passed locally with `scripts/workspace/check-v0-readiness.sh` after all related PRs were merged and local ports were free.
- After PRs #132, `praxis-ui-angular` #87 and `praxis-api-quickstart` #42 were merged on 2026-04-29, the integrated `main` branches passed the local-first rc.36 gates without Maven/npm publication or GitHub Actions:
  - `mvn -Dtest=AgenticAuthoringIntentResolverServiceTest,AgenticAuthoringDomainCatalogHintsTest,AiApiContractOpenApiTest test -q`
  - `mvn -Dtest=DomainRuleServiceTest#definitionTimelineReturnsSafeDerivedLifecycleWithoutPromptPayloads,DomainRuleControllerTest#returnsDefinitionTimelineWithTenantAndEnvironmentHeaders,AiApiContractOpenApiTest test -q`
  - quickstart `DomainAuthoringContextHintsContractTest`
  - `praxis-ui-angular` page-builder focal spec and `ng build praxis-page-builder`
  - `scripts/workspace/check-v0-readiness.sh`
  - `scripts/workspace/run-local-readiness-lane.sh domain-rules-timeline-runtime`, proving `eventCount=10` for `form_config` and `eventCount=9` for `option_source` against Neon
  - `scripts/workspace/run-local-readiness-lane.sh shared-rule-timeline-cockpit`, proving the governed timeline rendered in the Angular cockpit with real local services
- Manual pre-release gate for `praxis-config-starter` passed in GitHub Actions run `25085122699`.
- The coordinated release tag was created by run `25086067964`, and Maven Central publication passed in run `25086073591`.
- Maven Central resolved `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.36` with `mvn -q dependency:get -Dartifact=io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.36 -Dtransitive=false`.
- `praxis-api-quickstart` PR #44 consumed `0.1.0-rc.36` without local overrides, passed `./mvnw -B verify`, and passed the Neon-backed local readiness lane with `REQUIRE_TIMELINE=true`.
- The published quickstart runtime deployed the rc.36 consumer build, and `/actuator/info` reported build time `2026-04-29T01:39:46.288Z`.
- The published `Domain Rules Runtime Smoke` passed in run `25086775013` with `require_timeline=true`, proving `form_config` timeline `eventCount=10`, `option_source` timeline `eventCount=9`, and safe runtime readiness across publication, backend validation, workflow action and approval policy paths.
- `praxisui-http-examples` PR #4 promoted the timeline corpus example with `runtimeRecordConfirmed=true`, `publishedBackendConfirmed=true`, `knownPublishedFailure=false`, `protectedContract=true`, and kept `llmOperational=false` because the endpoint remains a protected governed contract rather than an unauthenticated LLM surface.

The closed Maven tag for this phase is:

- `v0.1.0-rc.36`

The released Maven coordinate is:

- `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.36`

Do not create another Maven Central version for this phase. Any next release must be justified by a new named phase, contract change or downstream need.

## Why Release Was Deferred

The release was intentionally deferred while implementation readiness was still only local.

Publication became appropriate only after all of these were true:

- the platform owner explicitly decided to close the observability phase;
- the quickstart needed a public Maven coordinate instead of local overrides;
- a published quickstart/runtime proof was required for corpus promotion and external documentation confidence.

This preserves the local-first policy: Maven Central and GitHub Actions were used as phase-closure gates, not as normal iteration tools.

As of 2026-04-29, implementation readiness and published-runtime readiness for timeline v1+v2 are both closed for `rc.36`.

## Gates Used Before Publishing Maven

The following local-first gates were used before release closure and remain the recommended pattern for future phases:

1. From the monorepo root, run `scripts/workspace/check-v0-readiness.sh`.
2. In `praxis-config-starter`, run `git diff --check`.
3. In `praxis-config-starter`, run the focal timeline tests:
   - `mvn -Dtest=DomainRuleServiceTest#definitionTimelineReturnsSafeDerivedLifecycleWithoutPromptPayloads test`
   - `mvn -Dtest=DomainRuleControllerTest#returnsDefinitionTimelineWithTenantAndEnvironmentHeaders test`
   - `mvn -Dtest=AiApiContractOpenApiTest test`
4. In `praxis-config-starter`, run the focal authoring contract tests:
   - `mvn -Dtest=AgenticAuthoringIntentResolverServiceTest,AgenticAuthoringDomainCatalogHintsTest,AiApiContractOpenApiTest test`
5. Against a quickstart packaged with the unreleased starter, run the local timeline proof:
   - `scripts/workspace/run-local-readiness-lane.sh domain-rules-timeline-runtime`
   - or, when validating the quickstart directly, `BACKEND_URL=http://localhost:8088 REQUIRE_TIMELINE=true scripts/verify-domain-rules-runtime.sh`
6. When a visual cockpit proof is required, run:
   - `scripts/workspace/run-local-readiness-lane.sh shared-rule-timeline-cockpit`

Use GitHub Actions only as the final release gate, not for normal iteration.

## Maven Publication Closure

Completed closure:

1. `praxis-config-starter/main` was clean and contained the intended post-`rc.35` release commits.
2. No newer `v0.1.0-rc.*` tag existed.
3. Manual release workflow passed the pre-release gate.
4. The release used explicit `version=0.1.0-rc.36`.
5. Tag `v0.1.0-rc.36` was created.
6. Maven Central publication completed.
7. Dependency resolution for `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.36` was confirmed.

For future phases, if publication stalls in Central Portal after upload, validate dependency resolution before deciding whether to retry. Do not create another version only to work around propagation lag.

## Quickstart Rollout Closure

Completed closure:

1. `praxis-api-quickstart/pom.xml` now uses `praxis.config.version=0.1.0-rc.36`.
2. Quickstart README and rollout documentation reference the same version.
3. Quickstart local validation passed with Maven resolution, `./mvnw -B verify`, and the Neon-backed `domain-rules-timeline-runtime` lane.
4. Quickstart PR #44 was merged to `main`.
5. The published quickstart runtime deployed the rc.36 consumer build.
6. The manual `Domain Rules Runtime Smoke` ran once for phase closure with `require_timeline=true` and passed against the published host.

## HTTP Corpus Rollout Closure

Completed closure:

1. `praxisui-http-examples/examples.manifest.json` now records the timeline example as published-backend confirmed.
2. `knownPublishedFailure=true` was removed for the timeline example.
3. `llmOperational=false` was intentionally preserved because the timeline endpoint is protected and tenant/header-governed.
4. `LLM_SURFACE.md` was regenerated without promoting the protected timeline endpoint into the unauthenticated LLM surface.
5. Corpus focal validations and published smoke checks passed.

## npm Publication

Do not publish `@praxisui/*` for this release decision by default.

The Angular cockpit support is already validated as source-level runtime evidence. Publish npm only if an external Angular consumer is explicitly named and needs to install the timeline types/projection/runtime from the public registry.

If npm publication is requested, create a separate coordinated release decision for `praxis-ui-angular`.

## Acceptance Criteria

The observability phase is implementation-ready because all are true:

- `praxis-config-starter/main` contains the durable event source and lifecycle writes.
- `praxis-api-quickstart/main` requires approval and publication events in `REQUIRE_TIMELINE=true`.
- Local quickstart + Neon smoke passes with `REQUIRE_TIMELINE=true`.
- `scripts/workspace/check-v0-readiness.sh` passes locally.

The observability phase is published for `rc.36` because all are true:

- Maven Central resolves the intended `praxis-config-starter` coordinate.
- `praxis-api-quickstart` consumes that coordinate without local overrides.
- Published quickstart returns `200` for `GET /api/praxis/config/domain-rules/definitions/{definitionId}/timeline`.
- Shared-rule authoring keeps explicit `/api/{context}/{resource}` paths as the selected target during intent resolution.
- Runtime smoke with `REQUIRE_TIMELINE=true` passes against the published host.
- The response includes only `visibility=safe` events.
- The response does not expose prompt, assistant message, condition, parameters or materialized payload.
- `praxisui-http-examples` no longer marks the timeline example as a known published failure.
- Public docs and LLM surfaces describe timeline as observability projection, not as a second source of decision truth.

## Current Recommendation

Treat `rc.36` as closed. Do not republish this phase, do not create another Maven version for documentation drift, and do not use Actions unless a new phase is being closed.

The next recommended work is not another release pass. It is to start the next observability increment only if there is a persisted source for rejected, blocked or failed governance attempts.

For the next observability increment beyond v2, continue the same rule: any rejected/blocked governance event requires a persisted governance source and must not be reconstructed from transient responses or UI state.
