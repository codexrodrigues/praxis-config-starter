# Phase 6 — Multi-Component Rollout Factory

Checkpoint: `CP-6`
Status: pending `CP-5` acceptance

## Objective

Turn the table pilot into a repeatable certification factory for every Praxis component family,
without a table-specific architecture and without assuming that every registry entry should be
authorable.

## Baseline registry shape

At baseline, the generated ingestion registry contains 105 entries. Ninety-five entries carry a
manifest projection, but those projections reduce to 20 source manifest families and 289 canonical
family-operation definitions. They use 278 globally distinct `operationId` strings; repeated IDs
across owners must not erase ownership. Generated component/profile copies are not independent
manifest implementations.

A naive sum across the 95 projections produces 889 operations. The extra 600 are duplicate
projections, primarily because `praxis-dynamic-fields` appears in 76 entries and repeats its eight
operations. Source-family counting must deduplicate by at least
`(componentId, manifestVersion, ownerPackage)` and confirm the owner entry whose key equals
`authoringManifest.componentId`.

| Manifest family | Owner operation definitions |
| --- | ---: |
| `pdx-cron-builder` | 6 |
| `praxis-chart` | 13 |
| `praxis-crud` | 10 |
| `praxis-dialog` | 8 |
| `praxis-dynamic-fields` | 8 |
| `praxis-dynamic-form` | 28 |
| `praxis-editorial-forms` | 7 |
| `praxis-expansion` | 10 |
| `praxis-files-upload` | 8 |
| `praxis-list` | 30 |
| `praxis-manual-form` | 8 |
| `praxis-metadata-editor` | 9 |
| `praxis-page-builder` | 13 |
| `praxis-rich-content` | 17 |
| `praxis-settings-panel` | 8 |
| `praxis-stepper` | 10 |
| `praxis-table` | 68 |
| `praxis-table-rule-builder` | 9 |
| `praxis-tabs` | 10 |
| `praxis-visual-builder` | 9 |
| **Total** | **289** |

`praxis-dynamic-fields` is present in 76 entries in the baseline: one owner and 75 child
projections. Its eight source operations must not be multiplied by the number of profiles. The
registry has 76 entries carrying profile data but only 18 distinct `profileId` values; neither
number represents independent canonical manifests.

The ten entries without a manifest projection are:

- `praxis-chart-state-probe`;
- `praxis-domain-catalog`;
- `praxis-dynamic-form-dialog-host`;
- `praxis-dynamic-page`;
- `praxis-editorial-form-runtime`;
- `praxis-filter`;
- `praxis-filter-form`;
- `praxis-filter-form-dialog-host`;
- `praxis-related-resource-outlet`;
- `praxis-runtime-component-observation`.

Absence of a manifest is not automatically a defect. Phase 6 must classify whether each entry is
`manifest-owner`, `family-profile`, `delegate-host`, `runtime-projection`, `governance-contract`,
`diagnostic-only` or `not-authorable`, and then apply the public-path authoring classes from
`CAPABILITY-COVERAGE-MODEL.md`. Only evidence can establish a real authoring gap.

These are baseline counts, not hand-maintained release truth. Phase 6 must regenerate them from the
current registry and explain every delta.

Terminology matters: these are 105 ingestion/corpus entries, not necessarily 105 runtime
components. The baseline component-docs catalog has 101 entries; ingestion adds
`praxis-domain-catalog`, `praxis-dynamic-fields`, `praxis-editorial-forms` and
`praxis-runtime-component-observation` as aggregate/governance/runtime identities.

## Rollout waves

### Wave 1 — Transactional/data-entry dependency chain

Certify in dependency order: dynamic fields, dynamic form, CRUD, manual/editorial forms and file
upload.

Prove schema/field grounding, option sources, validation, submit/action hooks, record actions,
availability, authorization, persistence, upload association and observable outcomes.

### Wave 2 — Reading, analytics and presentation

Candidate families: list, chart and rich content.

Prove resource/metric/content grounding, collection presentation, filters/actions, data
provenance, interactions and runtime observation.

### Wave 3 — Composition and navigation

Candidate families: dialog, expansion, tabs, stepper, settings panel and Page Builder. Certify Page
Builder last because it delegates to the other families.

Prove child composition, overlay/navigation modes, interaction/event linkage, responsive layout,
state and multi-component observation.

### Wave 4 — Authoring and specialized builders

Candidate families: metadata editor, visual builder, table-rule builder and cron builder.

Prove constrained document editing, round-trip fidelity, rule/schedule validation, preview and
explanation without promoting editor-local concepts to business truth.

If table-rule builder is required by the table pilot, certify it with that dependency before this
wave; do not defer a prerequisite merely to preserve the wave number.

### Wave 5 — Runtime helpers and unmanifested entries

Classify hosts, probes, filters, outlets, runtime observation and other non-authoring surfaces.
Where a real gap exists, assign the canonical owner and minimum vertical proof before adding a
manifest.

## Factory inputs and outputs

For each source component family, the factory consumes existing source owners:

```text
public API/config + editor metadata + capabilities + manifest
+ backend executors/validators + runtime behavior + docs/examples/tests
```

It produces derived, reproducible evidence:

```text
classification matrix + certification graph + dependency/state cases
+ observer/explanation cases + registry/corpus projection + drift report
```

The factory may generate tests and reports, but it must not invent semantics absent from the source
owners.

## Per-family workflow

1. Capture current source/profile identities and deduplicate projections.
2. Inventory public paths and classify authoring status.
3. Reconcile operations, dependencies, validators, handlers and affected paths.
4. Apply the approved executable-operation model.
5. Generate dependency/state/outcome/explanation tests.
6. Run at least one domain-grounded positive journey and negative/ambiguous variants.
7. Regenerate registry, corpus and certification evidence.
8. Run independent review before claiming the family certified.
9. Add all new public capabilities to the drift gate.

Use one representative family per wave to prove that the factory applies without a special fork,
then run it across every remaining current family. Representative coverage is an intermediate
control, not the `CP-6` completion criterion.

## Cross-family safeguards

- Do not make a table path or row-action convention universal.
- Reuse shared contract semantics only after two or more family owners prove the abstraction.
- Avoid root `public-api` reexports that turn one library into another library's facade.
- Keep composition contracts in existing composition owners.
- Preserve beta cleanup: migrate source and consumers together instead of adding parallel v1/v2
  paths without an operational need.
- Keep runtime-only helpers explicit rather than forcing artificial authoring operations.

## Deliverables

- regenerated inventory of all registry entries and source manifest families;
- certification dashboard/report by entry, family, wave and C0–C8 level;
- reusable generator/checker and drift gate;
- per-family evidence bundles and independent review records;
- explicit classification/rationale for every non-manifest entry;
- cross-component composition test set;
- public docs/examples matrix stating certified and unsupported behavior;
- updated `CURRENT-STATE.md` and handoff.

## Checkpoint criteria

- [ ] All current registry entries are classified; baseline deltas are explained.
- [ ] Source manifests/profiles are deduplicated correctly.
- [ ] No report sums the 889 projected copies as canonical operations.
- [ ] Every current manifest family has a certification state and gap owner.
- [ ] Every non-manifest entry has an explicit rationale or proven gap.
- [ ] The factory is proven on at least one representative family in every wave.
- [ ] Every current `authorable` family/operation reaches its required certification level.
- [ ] Consult-only, runtime-derived and unsupported surfaces remain explicit with reasons.
- [ ] New public paths/operations fail the drift gate until classified and tested.
- [ ] Registry, tools, chunks, docs and tests derive from their canonical owners.
- [ ] Cross-component journeys pass without transitive public API shortcuts.
- [ ] Table-specific assumptions do not appear as generic contracts without evidence.
- [ ] Independent review accepts the rollout factory and reports.

## Non-goal

`CP-6` does not require every runtime helper to become authorable. It requires every public registry
entry and component family to be understood, classified and governed by a repeatable process.

The current generated acceptance `PASS` proves declared structural conformance only. It cannot be
used as executable certification, especially when semantic/documentation checks were skipped
because their source root was unavailable.
