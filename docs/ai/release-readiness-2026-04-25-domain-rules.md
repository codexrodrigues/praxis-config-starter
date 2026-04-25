# Release Readiness - Domain Rules Publication And Materialization

Date: 2026-04-25

## Scope

This report records the readiness checkpoint for the governed domain-rule
authoring path in `praxis-config-starter`, aligned with the platform premise
that Praxis is a semantic decision platform authored by AI.

The validated path covers:

- shared semantic rule intake, simulation, publication and materialization;
- publication-derived `option_source` and `backend_validation` materializations;
- stable `materialization_key` reuse and collision protection;
- semantic `sourceHash` fingerprints for derived runtime projections;
- quickstart HTTP/SSE and Domain Catalog v2 operational smoke against the
  current `main` starter code, without publishing Maven/npm artifacts.

## Version Set

- `praxis-config-starter`: `main` commit `fc7478d998366f0c2452022f15926e485f41551e`.
- `praxis-ui-angular`: `main` commit `524f6f7033885c76d29469106ab32d13f2ac923c`.
- `praxis-api-quickstart`: `main`, packaged in GitHub Actions against the local
  `praxis-config-starter` and `praxis-metadata-starter` checkouts.
- `praxis-metadata-starter`: `main`, installed locally by the smoke workflow.
- Quickstart runtime jar in the smoke:
  `praxis-api-quickstart-2.0.0-rc.9.jar`.
- GitHub Actions runner: Windows Server 2025 with Java 21.

## Merged Changes

- PR #48, `Reuse domain rule materializations by stable key`,
  commit `b63b89fc262fa352d330cbc2556e879ec5ae57f7`.
- PR #49, `Reuse derived domain rule materializations by key`,
  commit `6a143ce71e741fe4f7d74e206325a6696a3b8d7b`.
- PR #50, `Require source hash for derived materialization reuse`,
  commit `512242772a3714fa22bc5270bdb485dbe9da8d7d`.
- PR #52, `Add domain rule publication diagnostics`,
  commit `c24de432983966526cdfcdf3e06d878b942572aa`.
- PR #53, `Assert domain rule publication diagnostics in HTTP smoke`,
  commit `2056bc06094b7200ef7cfb7a7169aaf52df512f9`.
- PR #54, `Fix publication diagnostics JSON object creation`,
  commit `d96ff02f965b2e4d27b9c8800a601df2a52a673e`.
- PR #57, `Clarify domain rule route endpoints`,
  commit `d8d4d7b1788b71cc8707c6301c222eb05d888a6c`.
- PR #58, `Document domain rule route smoke`,
  commit `fc7478d998366f0c2452022f15926e485f41551e`.
- `praxis-ui-angular` PR #52, `Project domain rule publication diagnostics in cockpit`,
  commit `a754afd091125f1ab152c64e87dbd02e4654202d`.
- `praxis-ui-angular` PR #53, `Keep page-builder resource discovery on SSE`,
  commit `04020a2ee43323f0d5473387bc83ca838b4aa757`.
- `praxis-ui-angular` PR #54, `Expose shared rule intake handoff`,
  commit `524f6f7033885c76d29469106ab32d13f2ac923c`.

## Contract State

The current canonical behavior is:

- `materialization_key` is the stable identity for a target projection inside
  the same tenant and environment.
- Explicit materialization creation reuses an existing row when the same key,
  definition and target are compatible.
- Explicit materialization creation rejects attempts to bind the same key to
  another definition, incompatible target or incompatible provided `sourceHash`.
- Publication-derived materialization creation resolves the stable key before
  inserting.
- Publication-derived retries reuse an existing row only when the stored
  `sourceHash` proves the same canonical decision and derived payload.
- Publication-derived collisions against another definition, incompatible
  target, incompatible `sourceHash` or missing fingerprint are rejected before
  duplicate runtime projections can be inserted.
- Terminal materializations such as `failed`, `superseded` and `reverted` remain
  non-publishable until moved through governance.
- Publication responses include additive
  `explainability.publicationDiagnostics.materializationOutcomes[]` so clients
  can explain whether each projection was `created`, `reused`,
  `selected_existing`, `selected_explicit`, `skipped` or `blocked` without
  reconstructing publication heuristics locally.
- `praxis-ui-angular` projects this diagnostic envelope in the shared-rule
  handoff cockpit through typed `@praxisui/core` contracts, keeping Angular as
  a cockpit/runtime of governed semantic decisions rather than a source of
  business-rule truth.
- Page Builder agentic resource-discovery turns remain on the canonical SSE
  turn contract when streaming is enabled. The local resource-candidate fallback
  remains available only for non-stream mode or unavailable stream endpoint
  fallback, preventing browser E2E turns from silently falling back to legacy
  intent-resolution/page-preview calls.
- Shared-rule handoff now carries both canonical governed route endpoints:
  `endpoint` and `intakeEndpoint` point to
  `/api/praxis/config/domain-rules/intake`, while `nextEndpoint` points to
  `/api/praxis/config/domain-rules/simulations`. Angular exposes these as
  context for the AI-authored decision flow, without making the cockpit a
  parallel source of route truth.

## Validation

Focal local validation after each code change:

- `mvn -B -Dtest=DomainRuleServiceTest test`
- `git diff --check`

Latest focal result:

- `DomainRuleServiceTest`: 27 tests, 0 failures, 0 errors, 0 skipped.

GitHub Actions CI on `main`:

- `CI and Release Java Starter (praxis-config-starter)` run `24919416823` passed
  for commit `b63b89fc262fa352d330cbc2556e879ec5ae57f7`.
- `CI and Release Java Starter (praxis-config-starter)` run `24919519156` passed
  for commit `6a143ce71e741fe4f7d74e206325a6696a3b8d7b`.
- `CI and Release Java Starter (praxis-config-starter)` run `24919594531` passed
  for commit `512242772a3714fa22bc5270bdb485dbe9da8d7d`.
- `CI and Release Java Starter (praxis-config-starter)` run `24919874591` passed
  for commit `c24de432983966526cdfcdf3e06d878b942572aa`.
- `CI and Release Java Starter (praxis-config-starter)` run `24919943378` passed
  for commit `2056bc06094b7200ef7cfb7a7169aaf52df512f9`.

Operational smoke on `main`:

- `Agentic Authoring HTTP Smoke` run `24919631736` passed for commit
  `512242772a3714fa22bc5270bdb485dbe9da8d7d`.
- `Agentic Authoring HTTP Smoke` run `24920194633` passed for commit
  `d96ff02f965b2e4d27b9c8800a601df2a52a673e`.
- `Agentic Authoring HTTP Smoke` run `24921488946` passed for commit
  `1115f49a5475b8486a335550d69a787f0a01a668` with `ui_ref=main` at
  `04020a2ee43323f0d5473387bc83ca838b4aa757` and
  `run_page_builder_full_e2e=true`.
- `Agentic Authoring HTTP Smoke` run `24921897738` passed for commit
  `d8d4d7b1788b71cc8707c6301c222eb05d888a6c` after PR #57 clarified the
  governed shared-rule route to name the canonical
  `/api/praxis/config/domain-rules/intake` and
  `/api/praxis/config/domain-rules/simulations` endpoints. This run kept
  `run_page_builder_full_e2e=false` and validated the proportional backend
  surface: quickstart HTTP/SSE smoke plus Domain Catalog v2 smoke.
- `Agentic Authoring HTTP Smoke` run `24922256769` passed for commit
  `fc7478d998366f0c2452022f15926e485f41551e` with `ui_ref=main` at
  `524f6f7033885c76d29469106ab32d13f2ac923c` and
  `run_page_builder_full_e2e=true`. This run validated the integrated
  backend-to-Angular handoff after the cockpit started projecting the canonical
  shared-rule intake endpoint.
- `Agentic Authoring HTTP Smoke` run `24922743567` passed for commit
  `27dc980c58613b25ebe09894c7044f13326b5fb1` with
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=false` and
  `run_page_builder_full_e2e=false`. This proportional run validated the
  hardened domain-rule lifecycle smoke on the Windows runner after `reused`
  diagnostics were promoted into the HTTP gate.
- `Agentic Authoring HTTP Smoke` run `24922877907` passed for commit
  `4a1acc8bfaabdeaeea4f619579cd1c7687cd11d3` with
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=true` and
  `run_page_builder_full_e2e=true`. This full integrated gate revalidated the
  current `main` after the reused diagnostics hardening and readiness
  documentation update.
- The smoke packaged `praxis-api-quickstart` against local starter checkouts.
- `Run quickstart authoring HTTP/SSE smoke suite`: passed.
- `Run quickstart Domain Catalog v2 HTTP smoke`: passed.
- `Run page-builder agentic full E2E gate`: passed.
- Artifact uploaded: `agentic-authoring-smoke-artifacts`.
- Smoke run `24922256769` confirmed SSE health `UP`, `eventCount=6`,
  `terminalSeen=true`, `replayChecked=true` and `cancelStatus=200`.
- Smoke run `24921897738` confirmed
  `domainRulePublicationCreatedDiagnosticsSeen=true` and
  `domainRulePublicationSelectedExistingDiagnosticsSeen=true`.
- Smoke run `24922743567` confirmed
  `domainRulePublicationCreatedDiagnosticsSeen=true`,
  `domainRulePublicationSelectedExistingDiagnosticsSeen=true` and
  `domainRulePublicationReusedDiagnosticsSeen=true`.
- Smoke run `24922877907` confirmed the same three publication diagnostics,
  Domain Catalog v2 `catalogSchemaVersion=praxis.domain-catalog/v0.2`, Page
  Builder full E2E `3 passed` and `fullE2EPassed=true`.
- Page Builder full E2E in smoke run `24922256769` ran 3 tests and passed:
  - Flow 1: payroll dashboard with imperfect language, backend-driven contract.
  - Flow 2: employee form with imperfect language, backend-driven contract.
  - Code audit: `praxis-ai.service.ts` contains neither `getMockPatch` nor
    `extractUserIntent`.
- Smoke run `24921488946` recorded `fullE2EPassed=true` and uploaded artifact
  `6637137679`; run `24922256769` preserved the same full E2E gate on the
  newer Angular handoff commit.

Angular cockpit/runtime projection:

- `praxis-ui-angular` PR #52 merged to `main` at
  `a754afd091125f1ab152c64e87dbd02e4654202d`.
- `praxis-ui-angular` PR #53 merged to `main` at
  `04020a2ee43323f0d5473387bc83ca838b4aa757`.
- `praxis-ui-angular` PR #54 merged to `main` at
  `524f6f7033885c76d29469106ab32d13f2ac923c`.
- Local focal validation passed:
  - `npx ng test praxis-core --watch=false --browsers=ChromeHeadless --include='projects/praxis-core/src/lib/services/domain-rule.service.spec.ts'`
  - `npx ng test praxis-ui-workspace --watch=false --browsers=ChromeHeadless --include='src/app/features/shared-rule-handoff/shared-rule-handoff-surface.component.spec.ts'`
  - `npm run build:praxis-core`
  - `CHROME_BIN="$HOME/Library/Caches/ms-playwright/chromium-1181/chrome-mac/Chromium.app/Contents/MacOS/Chromium" npx ng test praxis-page-builder --watch=false --browsers=ChromeHeadless --include='projects/praxis-page-builder/src/lib/ai/page-builder-agentic-authoring-turn-flow.spec.ts'`
  - `npx ng build praxis-page-builder`
- Additional local focal validation for PR #54 passed:
  - `CHROME_BIN="$HOME/Library/Caches/ms-playwright/chromium-1181/chrome-mac/Chromium.app/Contents/MacOS/Chromium" npx ng test praxis-page-builder --watch=false --browsers=ChromeHeadless --include='projects/praxis-page-builder/src/lib/dynamic-page-builder.component.spec.ts' --include='projects/praxis-page-builder/src/lib/ai/page-builder-agentic-authoring-turn-flow.spec.ts'`
  - `npx ng test praxis-ui-workspace --watch=false --browsers=ChromeHeadless --include='src/app/features/shared-rule-handoff/shared-rule-handoff-surface.component.spec.ts' --include='src/app/features/llm-tests/llm-tests.page.spec.ts' --include='src/app/features/dynamic-page-lab/dynamic-page-lab.page.spec.ts' --include='src/app/features/page-builder-json-logic-lab/page-builder-json-logic-lab.page.spec.ts'`
  - `npx ng build praxis-page-builder`
  - `npx ng build praxis-ui-workspace --configuration development`
- GitHub Actions `CI - Build Praxis Angular Libs` run `24920424506` passed for
  head SHA `3c45fee91f580ec9148f373b4b4333e7dd8deb4b`.
- GitHub Actions `CI - Build Praxis Angular Libs` run `24921204229` passed for
  head SHA `e92f3a1414f7e48580904da2206d8b0d2f5d3157`; release/tag job skipped.
- GitHub Actions `CI - Build Praxis Angular Libs` run `24922169138` passed for
  head SHA `d5fbd97623f8f5b34f6e8c558c0abfb342da395c`; release/tag job
  skipped.

Domain Catalog v2 smoke summary:

- `health=UP`
- `catalogSchemaVersion=praxis.domain-catalog/v0.2`
- `serviceKey=praxis-service`
- `resourceKey=human-resources.folhas-pagamento`
- `aliasCount=45`
- `contextCount=1`
- `nodeCount=24`
- `ingestItemCount=176`
- `projectedNodeCount=2`
- `projectedAliasCount=4`
- `projectedGovernanceCount=4`
- `projectedRelationshipCount=10`
- `semanticPayloadSeen=true`
- `aliasPayloadSeen=true`
- `explicitRelationshipSeen=true`

## Publication Discipline

No Maven Central or npm publication was triggered for this checkpoint. The
GitHub Actions release/tag/publication jobs remained skipped on push CI.

The operational smoke intentionally used local starter installation in Actions
so the platform contract could be validated before any external publication.

## Not Validated In This Checkpoint

- LLM compliance-policy shadow was intentionally skipped.
- Maven Central consumption of these latest commits was not validated because
  no release artifact was published.
- npm package consumption of the latest Angular projection was not validated
  because no npm artifact was published.

## Recommended Next Step

Move from browser-level readiness evidence to release discipline:

1. Preserve the HTTP lifecycle gate for `created`, `selected_existing` and
   `reused` diagnostics so release readiness proves all publication/materialization
   resolution branches over the quickstart host.
2. Defer Maven/npm publication until a release
   cut explicitly requires external artifact consumption.
3. If a release is requested, publish once after confirming the external
   artifact version set, rather than repeatedly publishing during validation.
