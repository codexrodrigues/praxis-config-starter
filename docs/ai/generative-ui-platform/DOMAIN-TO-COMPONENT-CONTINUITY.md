# Domain-to-Component Continuity

## Goal

Allow Praxis to determine which UI composition best serves a domain request, then select only
components whose certified capabilities can materialize it.

The existing Semantic IR RFC already implements the first selection envelope. This document
defines how that envelope must connect to certified component operations and outcome evidence.

## Decision pipeline

```text
human objective
  -> semantic task mode
  -> governed domain context
  -> concepts, relationships and business capabilities
  -> canonical resource bindings
  -> schemas, operations, surfaces, actions and availability
  -> interaction and information requirements
  -> component capability requirements
  -> compatible certified component candidates
  -> semantic UI decision / UiCompositionPlan
  -> component operations and dependency closure
  -> materialization, observation and explanation
```

Technical discovery becomes progressively more specific. A global domain question should not load
component manifests or OpenAPI schemas. An explicit UI materialization may inspect only accepted
resources, operations and component candidates.

## Inputs to component selection

Selection should consider governed facts such as:

- task mode: consult, explore, create, edit, approve, compare, monitor or navigate;
- resource cardinality: collection, single record, relation or aggregate;
- mutability and writable fields;
- available collection/item operations and HATEOAS links;
- workflow actions, approval requirements and risk;
- surfaces and related-resource navigation;
- metrics, dimensions, statistics support and temporal semantics;
- selection, filtering, sorting, grouping and pagination needs;
- file, lookup, option-source and validation requirements;
- user/device/accessibility context;
- current page composition and certified component compatibility;
- required authoring operations and their certification level.

Labels, aliases and textual similarity may rank already-scoped candidates. They do not decide the
primary intent or authorize a component.

## Selection evidence

Every material component selection must preserve:

- domain concept/context IDs;
- resource key and binding/release evidence;
- relevant operation/surface/action/capability IDs;
- semantic UI requirement IDs;
- selected and rejected component capability refs;
- manifest version and certification status;
- permission/availability evidence;
- reason for multi-component composition;
- unresolved limitations or required clarification.

A component that advertises a capability but lacks the certified authoring operation required by
the decision must be rejected or kept consult-only.

## Reference journeys

### Employee collection

Request: show employees with photo, code, status, filters and a detail action.

Required continuity:

```text
employee concept
  -> funcionarios resource binding
  -> collection/read/filter/detail surfaces
  -> table/list capability requirements
  -> certified columns/renderers/filter/row-action operations
  -> observed list and detail affordance
```

### Employee update

Request: change a specific employee's name or sex.

Required continuity:

```text
employee concept
  -> exact entity lookup
  -> writable field and update capability
  -> form/CRUD host
  -> prefilled proposed value in preview
  -> explicit governed mutation
```

A name in conversation does not prove a unique entity. A requested value does not prove field
writeability.

### Hours or vacation approval

Request: approve hours or enable vacation for an employee.

Required continuity:

```text
domain process/policy
  -> resource/action availability
  -> required approval/authorization
  -> workflow action plus appropriate form/detail surface
  -> reviewable action payload
  -> governed apply and outcome
```

A generated button is insufficient when no canonical workflow action exists.

### Analytical dashboard

Request: analyze employee indicators by department and period.

Required continuity:

```text
governed metrics/dimensions
  -> verified stats capabilities and fields
  -> chart/dashboard/table composition
  -> query/materialization validation
  -> observed analytical surface
```

### File-backed workflow

Request: attach employee documents during onboarding.

Required continuity:

```text
document requirement
  -> writable/upload backend contract
  -> file-upload capability
  -> form composition and validation
  -> upload lifecycle observation
```

## Multi-component composition

The platform must support one semantic decision materialized by several compatible components. The
page plan owns composition; individual component manifests own their internal operations.

Examples:

- table + filter + dialog/drawer;
- form + dynamic fields + file upload;
- rich content + chart + list/table;
- stepper + forms + governed workflow actions;
- collection + expansion/related-resource outlet.

Cross-component effects require explicit page/event/state contracts. Do not overload a component
manifest to author another component's state or use root public APIs as transitive facades.

## Domain decision boundary

Shared eligibility, approval, validation, privacy, access or workflow policy is a governed domain
decision. UI components consume its materializations. A local layout, label, renderer or component
configuration remains component authoring unless it changes reusable business meaning.

When `requiresGovernedAuthoring=true`, UI preview/apply must stop and hand off to the canonical
domain-rule workflow. The assistant must not downgrade the request to a local form/table rule.

## Acceptance cases

The domain-to-component gate must include:

- correct single-component selection;
- correct multi-component composition;
- two plausible candidates with evidence-based disambiguation;
- component advertised but required operation uncertified;
- resource available but action denied;
- missing binding or stale release;
- ambiguous entity or unwritable field;
- workflow request with no canonical action;
- vector index unavailable with structured fallback;
- contradictory spoken request;
- consult-only question that produces no patch;
- component explanation from current grounded runtime context.

Success requires zero invented resource, field, action, input or component and 100% provenance for
material selections.
