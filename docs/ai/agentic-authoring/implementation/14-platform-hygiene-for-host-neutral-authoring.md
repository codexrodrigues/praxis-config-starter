# Platform Hygiene For Host-Neutral Agentic Authoring

Status: selected hygiene baseline before the Lovable/ChatGPT behavior slice
Date: 2026-05-07
Scope: remove quickstart-shaped intelligence from the canonical agentic authoring path

## Classification

- Change class when implemented: `arquitetural` and potentially `contrato-publico`.
- Current document change: `docs-apenas`.
- Canonical backend owner: `praxis-config-starter`.
- Primary runtime consumer: `praxis-ui-angular` Page Builder.
- Runtime proof host: `praxis-api-quickstart`, as a proof host only.
- Derived corpus owners: `praxisui-http-examples` and public docs only when
  examples or public LLM surfaces change.

## Decision

The next platform hygiene objective is:

> Remove domain-specific, mock-like, quickstart-shaped behavior from the
> canonical Page Builder agentic authoring path before expanding the
> Lovable/ChatGPT-style conversational experience.

Praxis must know semantic authoring primitives, not a specific business domain.
The platform may know about resources, schemas, actions, capabilities, tables,
charts, forms, dashboards, governed decisions, confidence, rationale and
materializations. It must not hardcode that payroll, incidents, suppliers,
missions or employees exist.

Host business language must enter through host-published metadata, domain
catalog, RAG/project knowledge, examples and capabilities, not through Java
branches in the starter.

## Why This Slice

The current Page Builder IA flow can look intelligent while still behaving like
a deterministic demo:

- intent resolution runs keyword fallback before and around LLM resolution;
- candidate discovery includes token-to-path anchors for quickstart domains;
- the reference UI composition provider contains resource-specific dashboard
  and table recipes;
- some dashboard materializations can still be table-first;
- the mock provider is always present as a Spring service;
- OpenAI direct calls bypass the existing RAG advisor path and receive only the
  already-built prompt.

Those behaviors are useful while proving the quickstart, but they will actively
hurt another host with another business domain. A host-neutral platform cannot
carry hidden preferences for `/api/human-resources/**`,
`/api/operations/**`, `/api/procurement/**` or `/api/risk-intelligence/**` in
its canonical authoring path.

## Impact Map

Canonical backend:

- `AgenticAuthoringIntentResolverService`
- `AgenticAuthoringKeywordFallbackResolver`
- `AgenticAuthoringApiMetadataCandidateCatalog`
- `AgenticAuthoringPlanService`
- `AgenticAuthoringPreviewService`
- `AgenticAuthoringTurnEngine`
- `AiProviderManagementService`
- provider adapters such as `SpringAiOpenAiService`
- `MockAiService`

Primary UI consumer:

- `praxis-ui-angular`
- `@praxisui/page-builder`
- `@praxisui/ai`
- `/page-builder-ia` cockpit
- browser E2E proving multi-turn refinement and semantic preview validation

Runtime proof host:

- `praxis-api-quickstart`, only to publish example domain metadata/corpus and
  prove the generic platform against a real host.

Docs and derived artifacts:

- this implementation guide;
- public agentic authoring docs if the user-visible behavior changes;
- `praxisui-http-examples` if public HTTP examples are promoted or renamed;
- Angular examples if quickstart-specific examples move into host corpus.

Minimum validation for this planning commit:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter
git diff --check
```

Minimum validation when code changes start:

- backend focal tests for intent resolution and candidate retrieval;
- backend focal tests for mock provider policy;
- backend focal tests for semantic preview validation;
- Angular focal Page Builder turn-flow tests;
- one local full Page Builder E2E with real provider and stream;
- one host-neutral fixture/domain proof that is not the quickstart domain;
- no GitHub Actions until phase-close gate.

Breaking-change risk:

- low for docs-only inventory;
- medium when default candidate ranking stops using quickstart anchors;
- high if public AI response contracts gain required fields such as
  `semanticDecision`, `confidence`, `evidence` or `materializationAudit`.

## Hygiene Inventory

### Must Remove From Canonical Runtime Path

These are not necessarily deleted in the first PR, but they must stop
participating in the default production authoring path.

1. Domain path anchors in `AgenticAuthoringApiMetadataCandidateCatalog`.

   Current suspicious surfaces include:

   - `preferredResourcePaths(...)`;
   - `domainPathScoreAdjustment(...)`;
   - domain token helpers such as procurement, payroll, mission, people,
     asset, fleet, incident, risk and threat detectors;
   - score caps or penalties tied to specific path families.

   Target replacement:

   - host-neutral structured filters over `api_metadata`;
   - vector or semantic search over endpoint summaries, schemas, actions and
     domain catalog entries;
   - score explanations based on retrieved evidence, not Java domain branches.

2. Quickstart-specific UI composition formerly in the canonical runtime path.

   Current suspicious surfaces include:

   - constants for quickstart paths such as employees, payroll analytics,
     payroll, mission summary and procurement products;
   - selected payroll dashboard/table/drilldown recipes;
   - selected resource dashboard that materializes a dashboard as table plus
     summary without proving chart intent;
   - resource-specific column lists.

   Target replacement:

   - a generic `UiCompositionPlanner` that consumes schema, stats operations,
     component capabilities, user intent and governed context;
   - quickstart examples moved to host corpus/RAG or test fixtures;
   - semantic preview validation that blocks mismatched materializations.

3. Keyword fallback as primary decision authority.

   Current suspicious surfaces include:

   - `AgenticAuthoringKeywordFallbackResolver`;
   - intent fallback promotion in `AgenticAuthoringIntentResolverService`;
   - warnings such as `keyword-fallback-applied` that still allow a successful
     preview.

   Target replacement:

   - fallback only as fail-safe classification when LLM/RAG/tooling is
     unavailable;
   - explicit telemetry and confidence downgrade;
   - no silent success when fallback contradicts the conversation.

4. Business-specific quick replies in platform code.

   Current suspicious surfaces include:

   - assistant choice quick replies such as "Dashboard completo" and
     "Apenas tabela filtravel" with purchase-order-specific text;
   - any quick reply whose label or rationale names a quickstart business
     concept instead of reflecting retrieved host metadata.

   Target replacement:

   - generic quick reply templates populated from retrieved host evidence;
   - business labels from domain catalog or API metadata, not starter source.

5. Mock provider in real runtime.

   Current suspicious surface:

   - `MockAiService` is a `@Service` and appears in the provider registry unless
     policy prevents it.

   Target replacement:

   - test-only/profile-only or explicit opt-in mock provider;
   - production/default authoring fails honestly when no real provider is
     configured;
   - diagnostics must say `provider-unavailable`, not synthesize a plausible
     mocked result.

6. Provider calls that bypass RAG/tool context.

   Current suspicious surface:

   - `SpringAiOpenAiService.callWithOptions(...)` calls OpenAI directly while
     `resolveRagAdvisors()` is not used on that path.

   Target replacement:

   - RAG/tool retrieval belongs in the agentic turn engine before LLM calls, or
     provider calls must receive a canonical retrieved context bundle;
   - the engine must not depend on provider-specific hidden advisor behavior.

### May Stay As Reference/Test Material

The following can remain if they are clearly outside the canonical runtime
path:

- quickstart examples as docs, corpus, fixtures or host-published domain
  catalog;
- deterministic examples used by tests;
- mock provider in unit tests and explicit local demo profiles;
- static examples that prove public contracts without influencing production
  ranking or planning.

## Target Architecture

The default Page Builder agentic turn should become:

```text
conversation
  -> semantic intent state
  -> host-neutral retrieval
  -> semantic authoring decision
  -> UI composition plan
  -> semantic preview validation
  -> repair or honest clarification
  -> governed materialization
```

The canonical unit is a semantic decision, not a component patch.

Recommended contract name:

```text
SemanticAuthoringDecision
```

Minimum fields:

- `decisionId`
- `conversationId`
- `turnId`
- `userGoal`
- `followUpKind`
- `operationKind`
- `artifactKind`
- `visualIntent`
- `resourceSelection`
- `retrievedEvidence`
- `capabilitySelection`
- `rationale`
- `confidence`
- `risk`
- `materializationTargets`
- `semanticValidation`
- `repairAttempts`
- `nextQuestions`

This contract should be introduced only when the first implementation PR needs
it. Until then, this document is the hygiene baseline.

## Required New Capabilities

### `SemanticResourceRetriever`

Host-neutral retrieval over:

- `api_metadata`;
- schema summaries and `/schemas/filtered` references;
- operations/actions/capabilities;
- domain catalog context;
- project knowledge/RAG entries;
- component capability catalogs.

It must return evidence-rich candidates, not just paths:

- `resourcePath`;
- `submitUrl`;
- `submitMethod`;
- `schemaRefs`;
- `statsCapabilities`;
- `actions`;
- `domainLabels`;
- `matchedEvidence`;
- `confidence`.

### `GenericUiCompositionPlanner`

Planner responsibilities:

- choose table/chart/form/page/dashboard from semantic decision and
  capabilities;
- compose dashboards with real chart widgets when the user asks for charts;
- choose metrics/dimensions from schema/stats capabilities;
- include filters and drilldowns only when supported;
- preserve source refs and rationale;
- avoid host-domain constants.

### `SemanticPreviewValidator`

Validator responsibilities:

- compare requested intent with produced materialization;
- block success when artifact or visual intent is not represented;
- require chart widgets for chart/dashboard-with-charts requests;
- require data binding to the selected candidate;
- emit repair instructions when mismatch is recoverable.

Example:

```text
requested.visualIntent = charts
preview.widgets contains no chart-capable component
=> invalid semantic materialization, repair required
```

### `NoMockRuntimePolicy`

Policy responsibilities:

- allow mock provider only in unit tests or explicit local profile;
- expose diagnostics when mock is selected;
- fail closed for real authoring when provider/RAG is unavailable.

## Implementation Sequence

### PR 1 - Hygiene Guardrails

Goal:

- introduce a documented runtime policy around mock usage;
- add tests proving production/default authoring does not silently use mock;
- add telemetry for keyword fallback and domain-anchor use.

No domain behavior should be removed yet unless tests prove the replacement.

### PR 2 - Semantic Resource Retriever

Goal:

- add host-neutral retrieval service beside the existing candidate catalog;
- use structured and vector evidence where available;
- keep quickstart domains as fixture/corpus evidence only.

Exit criteria:

- a prompt in a non-quickstart fixture can retrieve resources without adding
  source-level token branches;
- quickstart still works by published metadata, not by path anchors.

### PR 3 - Disable Domain Anchors In Default Path

Goal:

- remove `preferredResourcePaths` and `domainPathScoreAdjustment` from the
  default runtime path;
- retain any needed quickstart behavior as test fixture or explicit demo
  corpus.

Exit criteria:

- no path family such as `/api/human-resources`, `/api/operations`,
  `/api/procurement` or `/api/risk-intelligence` is preferred by starter code.

### PR 4 - Semantic Decision Contract

Goal:

- introduce `SemanticAuthoringDecision` or equivalent result envelope;
- make the turn engine carry conversation state, retrieved evidence,
  rationale, confidence and materialization intent.

Exit criteria:

- a second turn such as "prefiro graficos" mutates the existing semantic
  decision instead of starting a disconnected table flow.

### PR 5 - Generic UI Composition Planner

Goal:

- replace reference quickstart planning in the canonical path;
- generate dashboards/charts from schema/stats capabilities and user visual
  intent.

Exit criteria:

- dashboard-with-charts requests produce chart widgets or fail honestly.
- generic dashboard planning emits chart axes, series and grouped stats query
  from semantic user axes instead of returning empty chart placeholders.
- semantic axes inferred from the user prompt are marked with provenance and
  `schemaVerified=false` until schema/RAG evidence confirms the concrete field.
- preview validation uses `/schemas/filtered` through `SchemaRetrievalService`
  to promote matching semantic axes to `schemaVerified=true`; unverified axes
  continue to require review.
- quickstart/reference providers are absent from the starter runtime and exist
  only as test fixtures; any externally supplied hardcoded provider must surface
  `uiCompositionPlanUsesHardcodedAnchor=true`, forcing review instead of
  automatic apply.

### PR 6 - Semantic Preview Validator And Repair

Goal:

- block previews that do not satisfy the semantic decision;
- add repair loop before presenting success.

Exit criteria:

- the system cannot answer "created dashboard with charts" when the preview is
  only a table.
- chart/graph intent now requires a `praxis-chart` widget in the
  `UiCompositionPlan`; table-only materialization gets
  `semantic-preview-chart-required` and
  `semantic-preview-materialization-mismatch`.
- operational monitoring intent with analytical axes such as severity, status
  and owner must resolve to a dashboard or fail honestly; table-only
  materialization gets `semantic-preview-operational-dashboard-required` and
  `semantic-preview-materialization-mismatch`.

### PR 7 - Decision Diagnostics Contract

Goal:

- expose safe decision provenance on the terminal authoring stream result;
- make `canApply` depend on both preview validity and decision trust.

Exit criteria:

- terminal `result` includes
  `decisionDiagnostics.schemaVersion=praxis-agentic-authoring-decision-diagnostics.v1`;
- `keywordFallbackApplied=true` forces `requiresReview=true` and
  `canApply=false`;
- `selectedCandidateUsesDomainAnchor=true` forces `requiresReview=true` and
  `canApply=false`;
- frontend consumers are instructed to use `canApply`, not `preview.valid`, as
  the application gate.

## Acceptance Criteria

The hygiene phase is complete when:

- no quickstart domain path is hardcoded in the canonical runtime selection or
  planning path;
- mock provider cannot be selected silently for real authoring;
- fallback classifications are visible, low-confidence and never presented as
  high-confidence intelligence;
- conversational quick replies are authored by the LLM when the model provides
  governed options for the current business context, while deterministic cards
  remain an explicit fallback with telemetry;
- fallback classifications cannot enable application without explicit review;
- RAG/retrieval evidence is present in the decision or the turn fails honestly;
- a host with unrelated business domains can use Page Builder IA without
  inheriting quickstart vocabulary;
- a chart refinement over an existing table/dashboard either creates charts or
  returns an honest repair/clarification state.

## Deferred

- public release or Maven publication;
- broad rewrite of provider adapters;
- deleting quickstart examples from docs;
- adding destructive domain-knowledge operations;
- changing public HTTP contracts before the semantic decision envelope is
  explicitly approved.
