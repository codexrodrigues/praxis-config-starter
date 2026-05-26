# Dashboard Blueprint Planner v1

Status: implementation draft
Date: 2026-05-26
Classification: `arquitetural` / `contrato-publico`

## Purpose

The generic Page Builder planner must create dashboards as a platform capability,
not as a host-specific shortcut. A request such as "painel 360", "visao geral",
"overview", "acompanhar fornecedores" or "dashboard de incidentes" should be
resolved from semantic intent, selected resource evidence and host-published
field capabilities.

The quickstart HR API is only an operational proof host. Its fields must not be
embedded as planner rules.

## Contract

The generic planner emits a `praxis-dashboard-blueprint.v1` diagnostic block
inside `uiCompositionPlan.diagnostics.dashboardBlueprint`.

Required properties:

- `domainSpecific=false`
- `fieldSelectionPolicy=semantic-field-candidates-from-host-context`
- `requiresResolvedCategoricalAxes=true`

The planner may use:

- axes explicitly returned by the semantic/LLM intent resolver;
- field candidates published in `contextHints`, such as `schemaFields`,
  `filterableFields`, `fieldCatalog`, `fieldMetadata`, `columns` or
  `properties`;
- field declarations found in selected candidate evidence, including generic
  `fieldName` values published by domain catalog bindings.

The planner must not use:

- hard-coded domain field names;
- hard-coded quickstart resource names;
- UI-only keyword rules that invent business semantics.

## Behavior

When axes are present and usable, they win.

When axes are missing but host field candidates are available, the planner
matches the human request against field labels and field ids. It prefers
categorical/dimensional hints such as enum, select, option, category, dimension,
string or text. It downranks technical and measure-like fields such as ids,
dates, amounts, totals and salaries.

If the user names a specific axis, only strongly matched fields should be
materialized. Extra categorical fields are only fallback candidates when the
request is broad and the host has not resolved clearer axes.

When no resolved categorical axis is available, the planner must not invent a
domain-specific field. It may render a pending analytic placeholder and must
include diagnostics showing `schema-grounding-required`.

## Proofs

The current unit proof covers two important cases:

- HR fixture fields `departamentoNome` and `cargoNome` are inferred only because
  they are provided as host field candidates, not because the planner knows HR.
- A procurement host can request a supplier overview by status and category and
  receive chart-to-table dashboard orchestration without any HR-specific logic.
- A host can publish technical field names through `target.fieldName` or
  `metadata.fieldName` in domain catalog evidence; the planner can use those
  generic bindings without knowing the host domain.
