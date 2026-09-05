# Real LLM journey under the user-confirmed account limit

Run: [33995681951](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/33995681951).
The existing protected environment was approved using the user's authorization
to rely on their configured OpenAI account spending limit, without a numeric
USD ceiling. The account setting was not independently verified or modified.

## Observed result

The real provider returned three responses with usage, identifying
`gpt-5-mini-2025-08-07`; the configured model remained `gpt-5-mini`.
The browser executed three human turns, with no Playwright retry or second
paid journey. Backend phases and internal attempts are separate from the
human-turn count. The interception guard and registry provenance checks passed.
The mission workspace journey failed before persistence and command execution.
The runner reported cleanup verified and exact equality between the reviewed
Config JAR and the JAR nested in Quickstart.

In turn one, the LLM selected a master-detail workspace and the backend verified
nine domain operations. The existing weak-evidence policy correctly required
review. In turns two and three, the server-issued review continuation lost its
visualization decision. Consequently, the engine did not request operational
verification for a resource workspace. Materialization stayed blocked with
`verified-domain-operations-missing` and
`semantic-preview-resource-workspace-grounding-required`.

The sanitized [receipt](LIVE-ACCOUNT-LIMIT-RUN-2026-09-05.receipt.json) records
the immutable revisions, artifact equality, test counts and turn diagnostics.
Known response usage totals 47,341 input tokens and 4,010 output tokens.
Other invocation records lack token counters and embedding cost is not attributed,
so total usage and total cost remain unknown. No zero-cost claim is made.

## Correction and impact

Classification: `local-pequena` in the Config resolver, using existing semantic
decision fields. Adherence: `ja-suportado-mal-nomeado-ou-mal-materializado`.
No new contract is required. The server-issued child already contains its layout,
excluded components, target surface and query constraints; the continuation was
discarding them when no new LLM intent was requested.

The resolver now preserves the issued visualization and copies existing
constraints before updating continuation provenance. The original decision is
not mutated. Resource operation verification remains backend-owned and is
performed again by the existing turn-engine path. Weak-evidence review and
missing-grounding rejection remain enabled. The regression test first reproduced
the null visualization, then verifies layout, resource, filters, exclusions,
lineage and absence of a new LLM classification.

Focused validation passed: 492 tests across the resolver, turn engine,
free-intent continuity, free composer, portfolio and materialization policy,
with zero failures, errors or skipped tests. The existing conservative
weak-evidence review test also passed. No second paid execution was performed.

Consumers: the Page Builder continuation and its existing operational verifier.
No Angular runtime, public type, schema, endpoint, registry manifest or HTTP
example changes are required for this correction. This report is the derived
operational evidence. A new successful live gate is still required before the
Config release and the dependent landing deployment can be certified; the
failed journey was not rerun.

## UI release completed independently

The official Angular release published `9.0.64`. npm accepted `manual-form`
asynchronously with HTTP 202, temporarily causing the public-install postflight
to fail. After registry availability was verified, the local public-install
validator passed for all 22 packages and only the failed non-generative
postflight was repeated successfully. No package was republished.

The landing consumed those public packages and passed 14 deterministic browser
tests and its development build. UI Quickstart PR 86 passed 11 local tests,
production build and CI, was merged and deployed; its hosted manifest confirms
`9.0.64` and nine examples. Landing PR 214 remains draft pending Config's live
gate and deployment. Its examples and component documentation were synchronized
from the canonical owners.
