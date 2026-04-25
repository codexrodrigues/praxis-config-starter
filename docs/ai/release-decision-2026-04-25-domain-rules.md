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
  `d79c3850f0bc9d4b62dd98965be62a3fa989c10c` with readiness documentation
  updated after the integrated gate.
- `praxis-ui-angular` `main` reached commit
  `524f6f7033885c76d29469106ab32d13f2ac923c` with the cockpit projecting the
  governed shared-rule intake endpoint.
- `Agentic Authoring HTTP Smoke` run `24922256769` passed with
  `run_page_builder_full_e2e=true`, `provider=openai`, `quickstart_ref=main`,
  `metadata_ref=main` and `ui_ref=main`.
- The same run validated quickstart HTTP/SSE smoke, Domain Catalog v2 smoke and
  the Page Builder full browser E2E gate against a real LLM-backed flow.

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
projection in the integrated gate. Publish npm only if an external Angular
consumer must install the new handoff contract from the public registry.

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
- the intended Maven version is explicit and not already tagged;
- the downstream consumer that needs the artifact version is named;
- no new contract change has landed after the gate without a new gate run.

## Current Recommendation

Continue implementation without publishing.

The next engineering work should focus on hardening the semantic decision
platform itself: preserve backend-owned decision diagnostics in governed
authoring explainability, keep the HTTP/SSE lifecycle gate green, and only cut
`0.1.0-rc.34` when a real downstream consumer needs Maven Central resolution.
