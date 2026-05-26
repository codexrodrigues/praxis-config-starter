# Categorical Field Semantics v1

Status: implementation planning
Date: 2026-05-25
Classification: `arquitetural` / `contrato-publico`

## Purpose

This document defines the next canonical Praxis direction for categorical field
semantics: values such as `CONFRONTO`, `EM_OBSERVACAO`, `ACTIVE`, `BLOCKED`,
`PENDING` or `APPROVED` must not be converted into colors, icons, badges or
chips by local keyword rules or by free LLM invention.

Praxis should model this as an AI-authored governed semantic decision:

```text
field value discovery -> semantic enrichment draft -> review/approval
  -> versioned decision -> derived runtime materializations
```

The goal is to make the `/ameacas` status example reusable as a platform
pattern for any host and any domain, including domains where values are not
static enums and are instead discovered from database-backed option sources or
observed data.

## Problem Statement

The table runtime can already render badges, chips, icons and labels. However,
these are final presentation details. If an LLM directly decides that
`CONFRONTO` should be `success`, or that `LIVRE` should be `warning`, the
platform has allowed the model to improvise a business visual policy.

That is not a component configuration problem. It is a missing governed
semantic decision.

The platform must distinguish:

- raw categorical values, as stored or returned by a host;
- human labels and descriptions;
- domain meaning, severity, state and lifecycle;
- accessible explanation;
- approved visual/materialization tokens;
- runtime projections such as badges, chips, filters, sorting and export labels.

## Canonical Ownership

`praxis-metadata-starter` owns the structural discovery of where values can be
found. Examples:

- enum/schema values published through metadata;
- field option endpoints;
- option sources such as `DISTINCT_DIMENSION` or `CATEGORICAL_BUCKET`;
- safe aggregate discovery such as stats/group-by when the field is allowlisted;
- capabilities and `/schemas/filtered` evidence that identifies the field,
  resource and supported operations.

`praxis-config-starter` owns the governed semantic decision:

- draft semantic interpretation;
- evidence used for the interpretation;
- human review and approval;
- lifecycle, versioning and auditability;
- publication and materialization records under `/api/praxis/config/**`;
- diagnostics that prove runtime surfaces are derived projections.

`praxis-ui-angular` owns only consumption and rendering of published
materializations. It must not decide that a domain value means `danger`,
`warning`, `success` or `neutral` by itself.

`praxis-api-quickstart` is the operational proof host. It can demonstrate the
pattern with `/ameacas`, procurement, HR or operations resources, but it must not
become the source of the canonical contract.

## Proposed Contract

The canonical decision payload should be expressed as
`categorical-field-semantics.v1`.

Example shape:

```json
{
  "schemaVersion": "praxis-categorical-field-semantics.v1",
  "resourcePath": "/api/risk-intelligence/ameacas",
  "resourceKey": "ameacas",
  "field": "status",
  "source": {
    "type": "option_source",
    "optionSourceType": "DISTINCT_DIMENSION",
    "optionSourceKey": "ameacas.status",
    "origin": "metadata_declared"
  },
  "values": {
    "CONFRONTO": {
      "rawValue": "CONFRONTO",
      "label": "Confronto",
      "description": "Ameaca em enfrentamento ativo.",
      "semanticState": "active_risk",
      "severity": "high",
      "tone": "danger",
      "icon": "warning",
      "renderer": "badge",
      "variant": "filled",
      "accessibilityLabel": "Status: Confronto, ameaca ativa",
      "governanceStatus": "approved",
      "confidence": 0.91,
      "evidenceRefs": [
        "api_metadata:/api/risk-intelligence/ameacas#status",
        "option_source:ameacas.status"
      ]
    }
  },
  "fallback": {
    "labelPolicy": "humanize_raw_value",
    "tone": "neutral",
    "icon": "help",
    "renderer": "badge",
    "variant": "soft",
    "governanceStatus": "ungoverned"
  },
  "materializationTargets": [
    "table-column-renderer",
    "filter-chip",
    "export-label"
  ]
}
```

The payload above is not meant to be copied into a component as business truth.
It is the governed decision payload from which runtime-specific materializations
are derived.

## Dynamic Discovery

The platform must support values that are not known at build time. Discovery
must use declared and governed sources only:

- enum/schema values when the backend publishes them;
- `/options/filter` and `/options/by-ids` when the resource exposes option
  endpoints;
- named option sources for dynamic dimensions or categorical buckets;
- safe aggregate discovery such as stats/group-by when the field is allowlisted
  and the caller is authorized;
- selected records only as contextual evidence, never as the canonical value
  universe.

The LLM must not invent the value set from labels, screenshots or examples when
a declared source exists. When no declared source exists, the correct outcome is
to request or model the missing source contract, not to route by keywords.

## AI Enrichment And Governance

The authoring flow for a field such as `status` should be:

1. Resolve the user's intent semantically, for example "render `status` as
   badges".
2. Discover whether the field is categorical and which value sources are
   declared.
3. Retrieve observed or allowed values from those sources.
4. Ask the LLM to propose semantic enrichment for each value:
   label, meaning, semantic state, severity, tone, icon and accessibility label.
5. Persist the proposal as a governed draft, including evidence and confidence.
6. Require review when values are new, evidence is weak, or the proposed visual
   policy carries business meaning.
7. Publish approved decisions into materializations for each runtime target.

The LLM may propose semantics. It must not silently publish semantics as final
truth.

## Runtime Materializations

Approved categorical semantics can derive multiple runtime projections:

- table cells: `valueMapping` plus badge/chip/icon renderers;
- filters: consistent chips and labels;
- sort/grouping: optional semantic order when approved;
- export: human labels, never HTML or icon-only values;
- accessibility: `aria-label` or equivalent text for every materialized value.

For `@praxisui/table`, the derived projection can populate existing final
configuration such as:

- `columns[].valueMapping` for labels;
- `columns[].conditionalRenderers[]` for badge/chip per value;
- `presentation.valueMapping` for export labels.

The table remains a consumer. It should prefer approved materializations and use
fallback neutral rendering for unknown values.

## Unknown Values

When the database or option source returns a value not present in the approved
decision, the runtime must not invent a color or icon.

Required behavior:

- display the raw or humanized label;
- use fallback neutral tone and safe icon if a visual renderer is required;
- mark the value as `ungoverned` in diagnostics where available;
- create or suggest a new governed enrichment draft for the missing value.

Example:

```json
{
  "rawValue": "FORAGIDO",
  "label": "Foragido",
  "tone": "neutral",
  "icon": "help",
  "governanceStatus": "ungoverned",
  "nextAction": "propose_semantic_enrichment"
}
```

## Diagnostics

Materialized responses should make the derivation explicit. A runtime projection
should be able to explain:

- `decisionKind=categorical_field_semantics`;
- `authoringMode=governed`;
- `canonicalOwner=praxis-config-starter`;
- `sourceValueDiscovery` with enum, option source or aggregate evidence;
- `materializationModel=derived_projection`;
- `runtimeSurfacesAreDerived=true`;
- `unmappedValueCount` and `unmappedValues` when applicable.

These diagnostics prevent UI clients from re-deriving policy locally.

## Example: Ameacas Status

The `/ameacas` quickstart route is a good first proof because it exposes a
business categorical field with values such as:

- `LIVRE`;
- `EM_OBSERVACAO`;
- `CONFRONTO`;
- `CAPTURADO`;
- `ELIMINADO`.

The proof should demonstrate that a prompt such as "use badges for the status
column" does not cause the LLM to invent colors in the table plan. Instead:

1. the platform discovers `status` values from metadata/options;
2. the LLM proposes domain semantics for each value;
3. the user reviews the semantic proposal;
4. publication derives the table renderer materialization;
5. the table renders the approved projection.

The same proof should later be repeated in a second domain, such as procurement
supplier status or operations mission status, to prove that the pattern is not
hardcoded to risk intelligence.

## Proof Slice: Operations Missions

The next reuse proof should use the quickstart operations domain because it
contains both static enum-backed categories and database/view-backed categories.
This makes it a better platform example than another simple table-only status
field.

Recommended proof resources:

- `operations.missoes`, exposed at `/api/operations/missoes`, for enum-backed
  values:
  - `status`: `PLANEJADA`, `EM_ANDAMENTO`, `PAUSADA`, `CONCLUIDA`, `FALHOU`;
  - `prioridade`: `BAIXA`, `MEDIA`, `ALTA`, `CRITICA`.
- `operations.vw-resumo-missoes`, exposed at
  `/api/operations/vw-resumo-missoes`, for view/database-backed category
  discovery:
  - `status` as a string dimension in the read model;
  - `prioridade` as a string dimension in the read model.

The `/api/operations/vw-resumo-missoes/stats/group-by` examples should be used
as safe discovery evidence for these view values. They prove that the value
universe can come from data-backed aggregate surfaces without requiring the LLM
to infer values from screenshots, selected rows or prompt wording.

Acceptance criteria for this proof:

1. The assistant resolves prompts such as "show mission status as badges" and
   "highlight critical priorities" semantically, not through keyword routing.
2. The value set is discovered from enum/schema evidence for `missoes` and from
   stats or option-source evidence for `vw-resumo-missoes`.
3. The LLM proposes labels, meanings, severity/tone, icon and accessibility text
   as a governed draft.
4. The runtime table materialization is a derived projection of the approved
   decision.
5. Unknown or newly observed view values render with the neutral governed
   fallback and create a follow-up enrichment draft instead of receiving
   invented colors or icons.

## Contract Risks

This is a public-contract area because it can touch:

- `x-ui` or `/schemas/filtered` metadata;
- option-source contracts;
- `ai_registry` authoring manifests;
- domain-rules definitions and materializations;
- `@praxisui/core` exported types;
- `@praxisui/table` renderer inputs;
- examples and public docs that teach AI/runtime behavior.

Do not add a frontend-only `categoricalSemantics` field as an incidental table
setting. If the field carries business meaning, it must be tied to governed
decision lifecycle and publication.

## Incremental Plan

1. Document this contract and confirm ownership boundaries.
2. Add a canonical materialization target for categorical field semantics, for
   example `targetArtifactType=field-value-semantics` with runtime projections
   such as `table-column-renderer`, `filter-chip` and `export-label`.
3. Define a safe value discovery service using schema, enum, option-source and
   aggregate evidence.
4. Update authoring prompts/orchestration so badge/chip requests for categorical
   fields consult approved semantics first; when absent, they create a governed
   draft instead of inventing visual policy.
5. Update `praxis-ui-angular` to consume approved materializations and fallback
   neutrally for unmapped values.
6. Prove the flow in `/ameacas`.
7. Prove reuse in a second host/domain scenario.

## Validation Guidance

Docs-only changes to this planning document require:

- final read-through;
- `git diff --check`.

When this becomes code or contract implementation, use at least:

- focal tests for value discovery and governed draft creation in
  `praxis-config-starter`;
- OpenAPI/contract drift checks if new endpoints or payloads are exposed;
- focal `@praxisui/table` tests for derived badge/chip/valueMapping/export
  materialization;
- a real quickstart smoke for `/ameacas` and one second-domain proof.
