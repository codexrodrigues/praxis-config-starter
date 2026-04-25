# Release Decision - Domain Rules Semantic Authoring

Date: 2026-04-25

## Decision

Do not publish Maven Central or npm artifacts immediately after the current
readiness checkpoint.

The domain-rule semantic authoring path is ready for a coordinated release cut,
but publication should happen only when an external consumer explicitly needs a
new artifact version. Until then, keep using the validated GitHub Actions gate
and local starter installation path to avoid wasting release numbers and waiting
on external registries during implementation.

## Rationale

The platform has enough evidence to support a release candidate:

- `praxis-config-starter` `main` reached commit
  `bffbd3e59405d1efbdb86ba17c893d7e8c8024f0`, with contract source commit
  `529cd0b06ef25ec5a26a9c84c900e33c841bcf77` carrying materialization
  decision diagnostics and readiness documentation updated after the smoke
  retry.
- `praxis-ui-angular` `main` reached commit
  `e8e0e295893acda5c60955c87ecc8ff88f90ec31` with the cockpit projecting the
  governed shared-rule intake endpoint, rendering backend-owned
  simulation/publication `decisionDiagnostics` in the shared-rule handoff
  surface, and `@praxisui/dynamic-form` preserving backend-owned
  materialization diagnostics in `metadata.domainRule.decisionDiagnostics`.
- `Agentic Authoring HTTP Smoke` run `24922256769` passed with
  `run_page_builder_full_e2e=true`, `provider=openai`, `quickstart_ref=main`,
  `metadata_ref=main` and `ui_ref=main`.
- The same run validated quickstart HTTP/SSE smoke, Domain Catalog v2 smoke and
  the Page Builder full browser E2E gate against a real LLM-backed flow.
- `Agentic Authoring HTTP Smoke` run `24925700913` later passed the
  proportional quickstart HTTP lifecycle gate and confirmed
  `domainRuleMaterializationDecisionDiagnosticsSeen=true` after
  materialization diagnostics entered the backend public contract.
- `Agentic Authoring HTTP Smoke` run `24926394899` then passed the full
  integrated gate with `quickstart_ref=main`, `metadata_ref=main`,
  `ui_ref=main`, `run_quickstart_http_smoke=true`,
  `run_domain_catalog_v2_smoke=true` and `run_page_builder_full_e2e=true`.
  It confirmed all publication diagnostics, intake/simulation/materialization
  decision diagnostics, Domain Catalog v2 semantic/alias/relationship payloads
  and Page Builder full E2E `3 passed`.
- `Agentic Authoring HTTP Smoke` run `24930631085` passed the full integrated
  gate again after Angular PR #57 reached `main`, with `quickstart_ref=main`,
  `metadata_ref=main`, `ui_ref=main`, `run_quickstart_http_smoke=true`,
  `run_domain_catalog_v2_smoke=true` and `run_page_builder_full_e2e=true`. It
  confirmed the same diagnostics gate, Domain Catalog v2 semantic/alias/
  relationship payloads and Page Builder full E2E `3 passed`.
- `praxis-config-starter` PR #83 reached `main` at
  `3557d51beacca456e05a18acfae5dc19335c0dbb` with a service-level proof that
  an AI-authored procurement supplier eligibility decision can be intaken,
  simulated, published and materialized as a derived `option_source`
  `lookup_selection_policy`.
- `praxis-config-starter` PR #84 reached `main` at
  `eba8575791ecec71c3e4f6eedbb2c038b58ad848` and hardened the quickstart HTTP
  smoke to require `domainRuleProcurementOptionSourcePolicySeen=true`, proving
  over HTTP that the derived supplier option-source policy blocks both
  `INACTIVE` and `BLOCKED` suppliers.
- `CI and Release Java Starter (praxis-config-starter)` runs `24931194317` and
  `24931255327` passed for those commits. The release/tag and Maven Central
  jobs remained skipped.
- Proportional `Agentic Authoring HTTP Smoke` run `24931281838` passed on
  `main` with `run_quickstart_http_smoke=true`,
  `run_domain_catalog_v2_smoke=false`, `run_page_builder_full_e2e=false` and
  `run_llm_compliance_policy_shadow=false`. This was intentionally smaller
  than a full integrated gate because the change only hardened the
  domain-rule lifecycle HTTP smoke.
- `praxis-config-starter` PR #86 reached `main` at
  `184903e7ab80218393b8f7da4446be42df1e0884` and promoted
  `selection_eligibility` publication from lookup-only projection to dual
  derived materialization: `option_source` plus `backend_validation`.
- `CI and Release Java Starter (praxis-config-starter)` run `24931556695`
  passed for PR #86. Release/tag and Maven Central jobs remained skipped.
- Proportional `Agentic Authoring HTTP Smoke` run `24931585519` passed on
  `main` and confirmed `domainRuleProcurementOptionSourcePolicySeen=true`,
  `domainRuleProcurementBackendValidationPolicySeen=true` and
  `domainRuleBackendValidationSemanticSourceHashesDiffer=true`.
- `praxis-ui-angular` CI runs `24926048274` and `24926222174` passed after the
  Angular diagnostics projection and README alignment reached `main`; both kept
  release/tag publication skipped. PR #57 then passed focal local validation
  for the host handoff projection and merged to `main` at
  `e8e0e295893acda5c60955c87ecc8ff88f90ec31`.
- `praxis-ui-angular` PR #58 then reached `main` at
  `8e4ccba4ed6262679e79b51ae0acf230ea4c709e`, declaring `option_source` and
  `backend_validation` as explicit `DomainRuleTargetLayer` values and rendering
  both predicted/published materialization outcomes as sibling projections of
  one governed semantic decision in the shared-rule handoff cockpit.
- `praxis-ui-angular` CI runs `24931880584` and `24931981608` passed for PR #58
  and for `main` after merge. No npm publication was performed.

Publication is still intentionally deferred because:

- the validated path used local starter installation in GitHub Actions, not
  Maven Central or npm consumption;
- no downstream consumer has yet required the new artifact version;
- repeated Maven/npm publication would slow the long-running platform work and
  create avoidable coordination overhead;
- Praxis is still in beta, so clean canonical migration and validated gates are
  more valuable than accumulating many external prerelease artifacts.

## If A Release Is Requested

Use a single coordinated release attempt, not repeated exploratory publishes.

Before creating a tag:

1. Re-run `Agentic Authoring HTTP Smoke` on `main`.
2. Use `provider=openai`.
3. Use `quickstart_ref=main`.
4. Use `metadata_ref=main`.
5. Use `ui_ref=main`.
6. Set `run_quickstart_http_smoke=true`.
7. Set `run_domain_catalog_v2_smoke=true`.
8. Set `run_page_builder_full_e2e=true`.
9. Keep `run_llm_compliance_policy_shadow=false` unless the release explicitly
   changes compliance-policy behavior.

After the gate passes, create the Maven release tag through
`CI and Release Java Starter (praxis-config-starter)` using an explicit
`version` value.

Do not rely on automatic `bump=prerelease` for this release line without first
checking the current tag series. The repository already has tags through
`v0.1.0-rc.33`, and an explicit version avoids accidental drift in the release
numbering strategy.

Recommended Maven version if no newer tag exists at release time:

- `0.1.0-rc.34`

The release workflow should then create tag:

- `v0.1.0-rc.34`

## npm Publication

Do not publish `@praxisui/*` packages as part of this decision by default.

The Angular changes have already been validated as source-level runtime/cockpit
projection in CI, including PR #58's sibling projection checkpoint. Integrated
smoke run `24930631085` remains the latest full Page Builder E2E checkpoint.
Publish npm only if an external Angular consumer must install the new handoff/
materialization diagnostics contract from the public registry.

If npm publication is requested, treat it as a separate coordinated release
decision for `praxis-ui-angular`, with its own package version set, tarball
verification and post-publication consumption check.

## Acceptance For "Ready To Publish"

The release can move from deferred to publishable when all of these are true:

- the latest config `main` commit is the intended release source;
- the latest integrated `Agentic Authoring HTTP Smoke` run passes with Page
  Builder full E2E enabled;
- the smoke summary confirms `domainRulePublicationCreatedDiagnosticsSeen=true`,
  `domainRulePublicationSelectedExistingDiagnosticsSeen=true`,
  `domainRulePublicationReusedDiagnosticsSeen=true`,
  `domainRulePublicationBlockedDiagnosticsSeen=true`,
  `domainRuleIntakeDecisionDiagnosticsSeen=true`,
  `domainRuleMaterializationDecisionDiagnosticsSeen=true` and
  `domainRuleDecisionDiagnosticsSeen=true`;
- the smoke summary confirms
  `domainRuleProcurementOptionSourcePolicySeen=true`;
- the smoke summary confirms
  `domainRuleProcurementBackendValidationPolicySeen=true`;
- the intended Maven version is explicit and not already tagged;
- the downstream consumer that needs the artifact version is named;
- no new contract change has landed after the gate without a new gate run.

## Current Recommendation

Continue implementation without publishing.

The next engineering work should focus on hardening the semantic decision
platform itself: preserve backend-owned decision diagnostics in governed
authoring explainability, keep sibling runtime materializations clearly
projected in downstream consumers, run the smallest proportional integrated
smoke after Angular PR #58, and only cut `0.1.0-rc.34` when a real downstream
consumer needs Maven Central resolution.
