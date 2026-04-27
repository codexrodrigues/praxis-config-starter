# Release Decision: Domain Rule Governed Timeline

Date: 2026-04-27

Status: deferred until a coordinated Maven + quickstart rollout is explicitly requested; implementation readiness is closed locally.

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
- `praxis-ui-landing-page` PR #28 sharpened the public Home positioning around governed semantic decisions and enterprise proof.
- `praxisui-http-examples` PR #2 added a protected HTTP example and kept it marked as a known published-runtime failure while the deployed quickstart still returns `404`.
- Local quickstart + Neon proof passed with `BACKEND_URL=http://localhost:8088 REQUIRE_TIMELINE=true scripts/verify-domain-rules-runtime.sh`, returning `eventCount=7` for both the approval-backed `form_config` path and the publication-backed `option_source` path.
- Monorepo readiness passed locally with `scripts/workspace/check-v0-readiness.sh` after all related PRs were merged and local ports were free.

The latest published Maven tag at the time of this note is:

- `v0.1.0-rc.35`

The timeline endpoint was added after that tag. If no newer tag exists when release is requested, the next intended Maven coordinate should be explicit:

- `0.1.0-rc.36`
- tag: `v0.1.0-rc.36`

Do not rely on automatic prerelease bumping for this release line without checking tags first.

## Why Release Is Deferred

Do not publish Maven Central only because the endpoint and durable v1 events exist on `main`.

Publication should happen only when one of these is true:

- a named downstream consumer needs the Maven artifact from Central;
- the platform owner explicitly decides to close the observability phase;
- a published quickstart/runtime proof is required for external documentation, demos or corpus promotion.

Until then, local-first evidence is preferred. This avoids repeated Maven Central waits and keeps GitHub Actions usage reserved for phase closure.

As of 2026-04-27, local evidence is sufficient to close implementation readiness for timeline v1, but not sufficient to claim published-runtime readiness. Publishing remains a product/release decision, not an automatic next patch.

## Required Gates Before Publishing Maven

Run these locally first whenever possible:

1. From the monorepo root, run `scripts/workspace/check-v0-readiness.sh`.
2. In `praxis-config-starter`, run `git diff --check`.
3. In `praxis-config-starter`, run the focal timeline tests:
   - `mvn -Dtest=DomainRuleServiceTest#definitionTimelineReturnsSafeDerivedLifecycleWithoutPromptPayloads test`
   - `mvn -Dtest=DomainRuleControllerTest#returnsDefinitionTimelineWithTenantAndEnvironmentHeaders test`
   - `mvn -Dtest=AiApiContractOpenApiTest test`
4. Against a quickstart packaged with the unreleased starter, run the local timeline proof:
   - `scripts/workspace/run-local-readiness-lane.sh domain-rules-timeline-runtime`
   - or, when validating the quickstart directly, `BACKEND_URL=http://localhost:8088 REQUIRE_TIMELINE=true scripts/verify-domain-rules-runtime.sh`
5. When a visual cockpit proof is required, run:
   - `scripts/workspace/run-local-readiness-lane.sh shared-rule-timeline-cockpit`

Use GitHub Actions only as the final release gate, not for normal iteration.

## Maven Publication Plan

When release is explicitly requested:

1. Confirm `praxis-config-starter/main` is clean and contains only the intended post-`rc.35` release commits.
2. Confirm no newer `v0.1.0-rc.*` tag exists.
3. Run the manual workflow `CI and Release Java Starter (praxis-config-starter)`.
4. Use `create_tag=true`.
5. Use explicit `version=0.1.0-rc.36` if no newer tag exists.
6. Do not use the default `bump=patch` or automatic prerelease selection for this line.
7. Wait until Maven Central resolves `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.36`.

If publication stalls in Central Portal after upload, validate dependency resolution before deciding whether to retry. Do not create another version only to work around propagation lag.

## Quickstart Rollout Plan

After Maven Central resolves the new coordinate:

1. Update `praxis-api-quickstart/pom.xml` property `praxis.config.version` to the released version.
2. Update quickstart README references to the same version.
3. Run focal downstream validation locally before pushing:
   - Maven resolution/build for quickstart.
   - Domain-rules runtime smoke with `REQUIRE_TIMELINE=true` against a quickstart using the new starter.
4. Open and merge one quickstart PR with `[skip ci]` only if local validation is sufficient and no protected CI gate is required.
5. Deploy/redeploy the published quickstart runtime.
6. Run the manual `Domain Rules Runtime Smoke` only once for phase closure, with:
   - `require_publication=true`
   - `require_timeline=true`
   - other gates set according to the phase being closed.

## HTTP Corpus Rollout Plan

After the published quickstart returns `200` for the timeline endpoint:

1. Update `praxisui-http-examples/examples.manifest.json` for the timeline example.
2. Remove `knownPublishedFailure=true` from the timeline example.
3. Set `llmOperational=true` only after the published smoke confirms the endpoint and safe event envelope.
4. Regenerate `LLM_SURFACE.md`.
5. Run the corpus focal validations.

## npm Publication

Do not publish `@praxisui/*` for this release decision by default.

The Angular cockpit support is already validated as source-level runtime evidence. Publish npm only if an external Angular consumer is explicitly named and needs to install the timeline types/projection/runtime from the public registry.

If npm publication is requested, create a separate coordinated release decision for `praxis-ui-angular`.

## Acceptance Criteria

The observability phase can be considered implementation-ready when all are true:

- `praxis-config-starter/main` contains the durable event source and lifecycle writes.
- `praxis-api-quickstart/main` requires approval and publication events in `REQUIRE_TIMELINE=true`.
- Local quickstart + Neon smoke passes with `REQUIRE_TIMELINE=true`.
- `scripts/workspace/check-v0-readiness.sh` passes locally.

The observability phase can be considered published when all are true:

- Maven Central resolves the intended `praxis-config-starter` coordinate.
- `praxis-api-quickstart` consumes that coordinate without local overrides.
- Published quickstart returns `200` for `GET /api/praxis/config/domain-rules/definitions/{definitionId}/timeline`.
- Runtime smoke with `REQUIRE_TIMELINE=true` passes against the published host.
- The response includes only `visibility=safe` events.
- The response does not expose prompt, assistant message, condition, parameters or materialized payload.
- `praxisui-http-examples` no longer marks the timeline example as a known published failure.
- Public docs and LLM surfaces describe timeline as observability projection, not as a second source of decision truth.

## Current Recommendation

Implementation readiness is closed locally. Continue without publishing unless the platform owner explicitly decides to close the release phase or a named downstream consumer needs the Maven artifact.

If release is requested, publish `praxis-config-starter:0.1.0-rc.36` via the manual release workflow, then update `praxis-api-quickstart` to consume that coordinate without local overrides and run one published-runtime smoke for phase closure.

For the next observability increment beyond v1, follow [`domain-rule-timeline-v1-source-plan.md`](domain-rule-timeline-v1-source-plan.md): any `intake`, `simulation` or rejected/blocked governance event requires a persisted governance source and must not be reconstructed from transient responses or UI state.
