# Phase 2 — Executable Operation Contract

Checkpoint: `CP-2`
Status: pending `CP-1` acceptance
Pilot operation: `praxis-table/rowAction.add`

## Objective

Use the Phase 1 evidence to implement the smallest platform-correct representation/derivation of
dependency closure, success conditions and outcome proof. Validate it vertically from semantic
operation through visible runtime behavior.

## Inventory before contract

Before editing, explicitly decide whether each requirement is already represented by:

- capability `dependsOn`;
- operation `preconditions`;
- declared effects/handler contracts;
- existing validators;
- runtime affordance/observation contracts;
- compiler/adapter behavior;
- page-builder evidence bundle;
- explanation/preview diagnostics.

Only requirements that cannot be expressed reliably may introduce a minimal extension to an
existing canonical owner. Do not create a separate operation graph service or table-only contract.

## Vertical scenario

Initial state:

```text
actions.row.enabled = false or absent
actions.row.actions = empty or absent
```

Request: add a governed row action that opens employee details.

Required result:

```text
semantic intent selects rowAction.add
  -> resource/detail surface and authorization are grounded
  -> dependency closure enables row actions
  -> action is appended exactly once
  -> unrelated configuration is preserved
  -> backend validation passes
  -> table displays the action affordance
  -> runtime observer proves the outcome
  -> explanation claims only the proven state
```

## Boundary cases

- disabled/absent dependency;
- already enabled with no actions;
- already enabled with other actions;
- repeated same action ID;
- same label with different canonical ID;
- missing/ambiguous target surface;
- denied/unavailable action;
- invalid action payload;
- visible/disabled/hidden conditional action;
- conflicting display/trigger configuration;
- removal/reset/undo if the public operation promises it;
- preview succeeds structurally but runtime observer fails.

## Expected owners

Potentially affected, subject to Phase 1 decision:

- `praxis-ui-angular` table capability/manifest/runtime/tests;
- `praxis-core` authoring types only if a real shared gap is proven;
- registry generator/validation derived artifacts;
- `praxis-config-starter` manifest validation/compiler/preview evidence;
- page-builder focal browser tests;
- table authoring Skill only if its procedure is stale after the source correction.

## Prohibited shortcuts

- enabling actions only in the playground fixture;
- hardcoding `rowAction.add` in generic backend code;
- teaching the dependency only in a prompt or Skill;
- marking success from a non-empty patch;
- weakening current validation to accept the case;
- adding a second manifest version/path during beta;
- using keyword routing to detect “button” or “detail.”

## Minimum validation

- focused table manifest/adapter/runtime specs;
- authoring contract/registry generation validation;
- focused backend resolver/validator/effect compiler tests;
- preview explanation test distinguishing compiled/observed;
- focal Page Builder browser case starting from disabled actions;
- negative browser/state case showing no false success;
- `git diff --check` in every edited repository.

## Checkpoint criteria

- [ ] Phase 1 adherence decision is referenced.
- [ ] Dependency closes deterministically or blocks explicitly.
- [ ] State and runtime postconditions are proven.
- [ ] Explanation is evidence-based.
- [ ] Boundary/negative cases pass.
- [ ] Source, backend and generated corpus remain aligned.
- [ ] No table-specific global abstraction was introduced without evidence.
- [ ] Independent review accepts the vertical proof.
