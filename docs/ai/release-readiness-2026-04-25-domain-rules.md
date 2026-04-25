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

- `praxis-config-starter`: `main` commit `512242772a3714fa22bc5270bdb485dbe9da8d7d`.
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

Operational smoke on `main`:

- `Agentic Authoring HTTP Smoke` run `24919631736` passed for commit
  `512242772a3714fa22bc5270bdb485dbe9da8d7d`.
- The smoke packaged `praxis-api-quickstart` against local starter checkouts.
- `Run quickstart authoring HTTP/SSE smoke suite`: passed.
- `Run quickstart Domain Catalog v2 HTTP smoke`: passed.
- Artifact uploaded: `agentic-authoring-smoke-artifacts`.

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

- Page Builder full browser E2E was intentionally skipped.
- LLM compliance-policy shadow was intentionally skipped.
- Maven Central consumption of these latest commits was not validated because
  no release artifact was published.

## Recommended Next Step

Move from backend publication diagnostics to broader UI/runtime projection:

1. Project `explainability.publicationDiagnostics.materializationOutcomes[]`
   into cockpit/runtime UI so hosts can explain backend publication decisions
   without local heuristics.
2. Keep browser E2E out until the UI projection is intentionally designed.
3. Preserve HTTP lifecycle coverage for `created` and `selected_existing`
   diagnostics; `reused` remains covered by focal backend tests because normal
   HTTP republication selects the existing materialization by definition.
