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

- `praxis-config-starter`: `main` commit `bffbd3e59405d1efbdb86ba17c893d7e8c8024f0`,
  with contract source commit `529cd0b06ef25ec5a26a9c84c900e33c841bcf77`.
- `praxis-ui-angular`: `main` commit `aa80680373973a0532f73d7bc3d6bdd3df5b641b`.
- `praxis-api-quickstart`: `main` commit `8e67215cc0a8a8d1b9ac6ff07843cede056d5223`,
  packaged in GitHub Actions against the local `praxis-config-starter` and
  `praxis-metadata-starter` checkouts.
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
- PR #59, `Document integrated page-builder smoke readiness`,
  commit `d79c3850f0bc9d4b62dd98965be62a3fa989c10c`.
- PR #60, `Document domain rules release decision`,
  commit `3d07080c04327d05bef5bd7b97b1d250d9816014`.
- PR #61, `Harden domain rule reuse diagnostics gate`,
  commit `27dc980c58613b25ebe09894c7044f13326b5fb1`.
- PR #62, `Document reused diagnostics smoke run`,
  commit `4a1acc8bfaabdeaeea4f619579cd1c7687cd11d3`.
- PR #63, `Document full gate after reuse diagnostics`,
  commit `4db6c7e9afb9578ba0b3c1c69dc5a089ffde82ce`.
- PR #64, `Add blocked publication diagnostics`,
  commit `e0772cac10d3769640a546fb6d96c596009aeef0`.
- PR #65, `Gate blocked publication diagnostics in smoke`,
  commit `020f882e61b7d4031bbc2d155cb755b2320e865f`.
- PR #66, `Document blocked diagnostics smoke run`,
  commit `0a5723f4b8fc96a6f6efdb4d60b5f65e04fe2281`.
- PR #67, `Document full gate with blocked diagnostics`,
  commit `3b828e9878f8dbf4b15a924f232636e7b5af727b`.
- PR #68, `Update domain rules recommended next step`,
  commit `8a6b670291ec06587572c0887c0c4139675995f1`.
- PR #69, `Add domain rule decision diagnostics`,
  commit `621ed8c9c64740f081206cc6d0bc99aa1ae360d4`.
- PR #70, `Gate domain rule decision diagnostics in smoke`,
  commit `45486a085d0b02e03ca87f5174f91331908ff648`.
- PR #71, `Document decision diagnostics smoke run`,
  commit `24fe78bf088f025363594f259eee02ff844cf729`.
- PR #72, `Document full gate with decision diagnostics`,
  commit `cffbac9f5942c1502273fe3b2ac4e8250268033c`.
- PR #73, `Require domain-rule decision diagnostics for release`,
  commit `42bcfb270a83e11ee6ea4a2e48a04ceefa222cc6`.
- PR #74, `Refresh domain-rules readiness checkpoint`,
  commit `40675fd8b42d032b62c248e30cf87409512d72b5`.
- PR #75, `Add domain-rule intake decision diagnostics`,
  commit `64f3d41b5805915ea7d5e67dcd909e0fa39c1626`.
- PR #76, `Document domain-rule intake diagnostics smoke`,
  commit `106074ef1d41d09b0bf2c3efb70e1aa1b81a7f55`.
- PR #77, `Add domain-rule materialization decision diagnostics`,
  commit `529cd0b06ef25ec5a26a9c84c900e33c841bcf77`.
- `praxis-api-quickstart` PR #25,
  `Adapt materialization response tests for diagnostics field`,
  commit `8e67215cc0a8a8d1b9ac6ff07843cede056d5223`.
- `praxis-ui-angular` PR #52, `Project domain rule publication diagnostics in cockpit`,
  commit `a754afd091125f1ab152c64e87dbd02e4654202d`.
- `praxis-ui-angular` PR #53, `Keep page-builder resource discovery on SSE`,
  commit `04020a2ee43323f0d5473387bc83ca838b4aa757`.
- `praxis-ui-angular` PR #54, `Expose shared rule intake handoff`,
  commit `524f6f7033885c76d29469106ab32d13f2ac923c`.
- `praxis-ui-angular` PR #55,
  `Project domain rule materialization diagnostics`,
  commit `2182f8d806520f9b1628a1789fab3ecd5dcb7d54`.
- `praxis-ui-angular` PR #56,
  `Document dynamic form materialization diagnostics`,
  commit `aa80680373973a0532f73d7bc3d6bdd3df5b641b`.

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
- Blocked publication responses also include
  `explainability.publicationDiagnostics` with `publicationStatus=blocked`,
  `publicationReadiness`, `blockedReason`, `definitionStatusAtResolution` and
  an empty `materializationOutcomes[]`, so governance explanations remain
  canonical even before any target projection is resolved.
- Simulation explainability now includes additive
  `explainability.decisionDiagnostics` with the canonical semantic decision
  kind, governed authoring mode, source, owner, diagnostic counts and
  `runtimeSurfacesAreDerived=true`, so hosts can present the backend-owned
  decision explanation without inferring platform semantics locally.
- Intake grounding now includes additive `grounding.decisionDiagnostics` with
  `decisionStage=intake`, so the first persisted draft handoff already tells
  hosts that the request is governed semantic authoring and that runtime
  surfaces are derived projections.
- Materialization responses now include additive `decisionDiagnostics` with
  `decisionStage=materialization`, so concrete runtime targets remain explained
  as derived projections of the governed semantic decision.
- The quickstart HTTP smoke treats this decision diagnostics envelope as a
  release gate through `domainRuleDecisionDiagnosticsSeen=true`.
- The quickstart HTTP smoke treats the intake diagnostics envelope as a release
  gate through `domainRuleIntakeDecisionDiagnosticsSeen=true`.
- The quickstart HTTP smoke treats the materialization diagnostics envelope as
  a release gate through `domainRuleMaterializationDecisionDiagnosticsSeen=true`.
- The quickstart HTTP smoke now treats this blocked diagnostic envelope as a
  release gate through `domainRulePublicationBlockedDiagnosticsSeen=true`,
  preventing future regressions where blocked governance would still succeed
  but lose canonical explainability.
- `praxis-ui-angular` projects this diagnostic envelope in the shared-rule
  handoff cockpit through typed `@praxisui/core` contracts, keeping Angular as
  a cockpit/runtime of governed semantic decisions rather than a source of
  business-rule truth.
- `praxis-ui-angular` now also types the backend-owned
  `DomainRuleDecisionDiagnostics` contract and preserves materialization
  diagnostics in `@praxisui/dynamic-form` as
  `metadata.domainRule.decisionDiagnostics`, keeping form rules as derived
  runtime projections rather than a parallel source of business-rule truth.
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

- `DomainRuleServiceTest` and `DomainRuleControllerTest`: 36 tests,
  0 failures, 0 errors, 0 skipped.
- `praxis-api-quickstart`
  `DomainRuleOptionSourcePolicyResolverTest` and
  `DomainRuleBackendValidationPolicyResolverTest`: 7 tests, 0 failures,
  0 errors, 0 skipped, after installing the current starter locally as
  `0.1.0-rc.33` for compatibility validation only.

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
- `CI and Release Java Starter (praxis-config-starter)` run `24925065297` passed
  for commit `cffbac9f5942c1502273fe3b2ac4e8250268033c`, after the full
  integrated decision diagnostics gate was documented.
- `CI and Release Java Starter (praxis-config-starter)` run `24925130100` passed
  for commit `42bcfb270a83e11ee6ea4a2e48a04ceefa222cc6`, after the release
  decision and knowledge-layer docs were aligned with
  `domainRuleDecisionDiagnosticsSeen=true`.
- `CI and Release Java Starter (praxis-config-starter)` run `24925182860` passed
  for commit `40675fd8b42d032b62c248e30cf87409512d72b5`, after the readiness
  checkpoint was refreshed through PR #74.
- `CI and Release Java Starter (praxis-config-starter)` run `24925270702` passed
  for commit `64f3d41b5805915ea7d5e67dcd909e0fa39c1626`, after intake
  diagnostics were added to the domain-rule contract and smoke gate.
- `CI and Release Java Starter (praxis-config-starter)` run `24925401016` passed
  for commit `106074ef1d41d09b0bf2c3efb70e1aa1b81a7f55`, after the intake
  diagnostics smoke checkpoint was documented.
- `CI and Release Java Starter (praxis-config-starter)` run `24925493269` passed
  for commit `529cd0b06ef25ec5a26a9c84c900e33c841bcf77`, after materialization
  decision diagnostics were added to the public response contract and smoke
  gate.
- `praxis-api-quickstart` `build-test` run `24925649471` passed for commit
  `8e67215cc0a8a8d1b9ac6ff07843cede056d5223`, after quickstart tests were made
  compatible with both the published materialization response constructor and
  the new local starter constructor with `decisionDiagnostics`.
- `praxis-ui-angular` `CI - Build Praxis Angular Libs` run `24925972239` passed
  for PR #55 head commit `0fdcdd132f09fcff12dde8f158acbbcc4368fe5c`,
  validating the Angular source-level diagnostics projection before merge.
- `praxis-ui-angular` `CI - Build Praxis Angular Libs` run `24926048274` passed
  for main commit `2182f8d806520f9b1628a1789fab3ecd5dcb7d54`, validating
  production Angular library build, tarball dry-run and artifact upload after
  materialization diagnostics projection reached `main`.
- `praxis-ui-angular` `CI - Build Praxis Angular Libs` run `24926151061` passed
  for PR #56 head commit `638ce7aab083eb76f512d9498ada6b545e0ba10a`,
  validating the dynamic-form README alignment before merge.
- `praxis-ui-angular` `CI - Build Praxis Angular Libs` run `24926222174` passed
  for main commit `aa80680373973a0532f73d7bc3d6bdd3df5b641b`, validating
  production Angular library build, tarball dry-run and artifact upload after
  the documentation alignment reached `main`.

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
- `Agentic Authoring HTTP Smoke` run `24923319803` passed for commit
  `020f882e61b7d4031bbc2d155cb755b2320e865f` with
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=false` and
  `run_page_builder_full_e2e=false`. This proportional run confirmed
  `domainRulePublicationBlockedDiagnosticsSeen=true` after the blocked
  publication diagnostics contract was promoted into the HTTP gate.
- `Agentic Authoring HTTP Smoke` run `24923468741` passed for commit
  `0a5723f4b8fc96a6f6efdb4d60b5f65e04fe2281` with
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=true` and
  `run_page_builder_full_e2e=true`. This full integrated gate revalidated the
  current `main` after blocked publication diagnostics entered the HTTP gate
  and the readiness documentation recorded the proportional proof.
- Smoke run `24923468741` confirmed all four publication diagnostics
  (`created`, `selected_existing`, `reused` and `blocked`), Domain Catalog v2
  `catalogSchemaVersion=praxis.domain-catalog/v0.2`, Page Builder full E2E
  `2 passed` and `fullE2EPassed=true`.
- `Agentic Authoring HTTP Smoke` run `24924233544` passed for commit
  `45486a085d0b02e03ca87f5174f91331908ff648` with
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=false` and
  `run_page_builder_full_e2e=false`. This proportional run confirmed
  `domainRuleDecisionDiagnosticsSeen=true` after backend-owned semantic
  decision diagnostics were promoted into the HTTP gate.
- `Agentic Authoring HTTP Smoke` run `24924505019` passed for commit
  `24fe78bf088f025363594f259eee02ff844cf729` with
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=true` and
  `run_page_builder_full_e2e=true`. This full integrated gate revalidated the
  current `main` after backend-owned semantic decision diagnostics entered the
  HTTP gate and readiness documentation recorded the proportional proof.
- Smoke run `24924505019` confirmed all four publication diagnostics plus
  `domainRuleDecisionDiagnosticsSeen=true`, Domain Catalog v2
  `catalogSchemaVersion=praxis.domain-catalog/v0.2`, Page Builder full E2E
  `2 passed` and `fullE2EPassed=true`.
- `Agentic Authoring HTTP Smoke` run `24925298855` passed for commit
  `64f3d41b5805915ea7d5e67dcd909e0fa39c1626` with
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=false` and
  `run_page_builder_full_e2e=false`. This proportional run confirmed the
  Windows HTTP lifecycle smoke after intake diagnostics entered the canonical
  contract.
- Smoke run `24925298855` confirmed all four publication diagnostics plus
  `domainRuleIntakeDecisionDiagnosticsSeen=true` and
  `domainRuleDecisionDiagnosticsSeen=true`.
- `Agentic Authoring HTTP Smoke` run `24925520881` failed before executing the
  HTTP lifecycle because quickstart test packaging still called the old
  `DomainRuleMaterializationResponse` constructor while the smoke had installed
  the new local starter contract.
- `praxis-api-quickstart` PR #25 resolved that packaging compatibility gap with
  a reflection-based test fixture factory, keeping quickstart tests compatible
  with both the published starter artifact and the local starter checkout used
  by smoke validation.
- `Agentic Authoring HTTP Smoke` run `24925700913` passed for commit
  `529cd0b06ef25ec5a26a9c84c900e33c841bcf77` with
  `quickstart_ref=main` at
  `8e67215cc0a8a8d1b9ac6ff07843cede056d5223`,
  `run_quickstart_http_smoke=true`, `run_domain_catalog_v2_smoke=false` and
  `run_page_builder_full_e2e=false`.
- Smoke run `24925700913` confirmed all four publication diagnostics plus
  `domainRuleIntakeDecisionDiagnosticsSeen=true`,
  `domainRuleDecisionDiagnosticsSeen=true` and
  `domainRuleMaterializationDecisionDiagnosticsSeen=true`.
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
- `praxis-ui-angular` PR #55 merged to `main` at
  `2182f8d806520f9b1628a1789fab3ecd5dcb7d54`.
- `praxis-ui-angular` PR #56 merged to `main` at
  `aa80680373973a0532f73d7bc3d6bdd3df5b641b`.
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
- Local focal validation for PR #55 passed:
  - `npx -y @angular/cli mcp --help`
  - `npx ng test praxis-core --watch=false --browsers=ChromeHeadless --include='projects/praxis-core/src/lib/services/domain-rule.service.spec.ts'`
  - `CHROME_BIN="$HOME/Library/Caches/ms-playwright/chromium-1181/chrome-mac/Chromium.app/Contents/MacOS/Chromium" npx ng test praxis-dynamic-form --watch=false --browsers=ChromeHeadless --include='projects/praxis-dynamic-form/src/lib/services/domain-rule-form-rules.service.spec.ts'`
  - `npm run build:praxis-core`
  - `npm run build:praxis-dynamic-form`
- Local focal validation for PR #56 passed:
  - `git diff --check -- projects/praxis-dynamic-form/README.md`
- GitHub Actions `CI - Build Praxis Angular Libs` run `24920424506` passed for
  head SHA `3c45fee91f580ec9148f373b4b4333e7dd8deb4b`.
- GitHub Actions `CI - Build Praxis Angular Libs` run `24921204229` passed for
  head SHA `e92f3a1414f7e48580904da2206d8b0d2f5d3157`; release/tag job skipped.
- GitHub Actions `CI - Build Praxis Angular Libs` run `24922169138` passed for
  head SHA `d5fbd97623f8f5b34f6e8c558c0abfb342da395c`; release/tag job
  skipped.
- GitHub Actions `CI - Build Praxis Angular Libs` run `24926048274` passed for
  head SHA `2182f8d806520f9b1628a1789fab3ecd5dcb7d54`; release/tag job
  skipped.
- GitHub Actions `CI - Build Praxis Angular Libs` run `24926222174` passed for
  head SHA `aa80680373973a0532f73d7bc3d6bdd3df5b641b`; release/tag job
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

Keep the release deferred and move back to platform hardening:

1. Treat run `24924505019` as the current integrated readiness checkpoint for
   domain-rule publication diagnostics, backend-owned simulation diagnostics,
   Domain Catalog v2 and Page Builder runtime projection, and treat config
   contract source commit `529cd0b06ef25ec5a26a9c84c900e33c841bcf77`,
   quickstart `main` commit `8e67215cc0a8a8d1b9ac6ff07843cede056d5223` and
   Angular `main` commit `aa80680373973a0532f73d7bc3d6bdd3df5b641b` as the current
   source checkpoint for intake, simulation, publication and materialization
   diagnostics after proportional HTTP smoke run `24925700913`.
2. Defer Maven/npm publication until a named downstream consumer explicitly
   needs external artifact resolution.
3. Use the next implementation cycle to strengthen governed semantic decision
   authoring itself: preserve canonical explainability in the intake,
   simulation, publication and materialization path, and prefer backend-owned
   diagnostics over cockpit-side inference.
4. If a release is requested, publish once after confirming the external
   artifact version set, rather than repeatedly publishing during validation.
