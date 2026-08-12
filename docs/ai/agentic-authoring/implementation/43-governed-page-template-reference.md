# Governed page template references

Status: phases 1 and 2 implemented with focal parity and Quickstart HTTP host proof; immutable
historical resolution and governed overrides remain deferred.

## Problem

Large `UiCompositionPlan` documents are useful as executable golden fixtures, but repetitive page
families should not require an author to copy the complete plan. A compact reference is only safe if
the referenced material is governed, immutable for the duration of compilation, resolved by the
canonical backend, and expanded to the same `WidgetPageDefinition` by server and Angular consumers.

A client-only `presetId`, a filesystem `$ref`, or a lookup by mutable registry key would make preview,
apply and persistence depend on ambient state. Those options are rejected.

## Adherence inventory

| Need | Existing evidence | Classification | Decision |
| --- | --- | --- | --- |
| Stable template identity | `ai_registry` identity tuple and template `registryKey` | `ja-suportado-so-ux` | Reuse the canonical template key. |
| Revision tracking | `AiRegistry.version`, `AiRegistry.etag` and `applyMaterialState` rotation policy | `ja-suportado-mal-nomeado-ou-mal-materializado` | Expose the existing revision in template responses. |
| Configured document identity | `CanonicalJsonHashService` already produces canonical SHA-256 | `ja-suportado-mal-nomeado-ou-mal-materializado` | Publish `configSha256` derived from the complete `configJson`; do not mislabel it as an artifact-only digest. |
| Template selection/ranking | Semantic template search after component scope is known | `ja-suportado-so-ux` | Search may rank candidates, but cannot authorize or silently resolve one. |
| Exact reference resolution | No compiler input currently pins and verifies registry content | `lacuna-real-de-contrato` | Add only after phase 1 revision evidence is public and tested. |
| Historical template retrieval | `ai_registry` currently stores the active head, not immutable historical bodies | `lacuna-real-de-contrato` | Not required for phase 1. Do not claim historical resolution. |
| Angular/server compilation parity | Both compilers support the same explicit `UiCompositionPlan`, but neither resolves refs | `suportado-parcialmente` | A future ref must be expanded before the existing pure compilation stage in both paths. |

## Canonical ownership

- `praxis-config-starter` owns template persistence, revision evidence, resolution and fail-closed
  diagnostics.
- `praxis-ui-angular` owns the `UiCompositionPlan` authoring shape and the pure page compiler. It must
  not fetch or select templates inside the pure compiler.
- `WidgetPageDefinition` remains the persisted executable contract. It never persists an unresolved
  template reference.
- Recipes may publish authoring intent and executable output as separate artifacts, but filesystem
  references are publication tooling, not runtime contracts.

## Phase 1 contract

The existing template read and upsert responses expose:

```json
{
  "componentId": "praxis-dynamic-page:employee-operations-casework",
  "configJson": {},
  "revision": {
    "version": 3,
    "etag": "123e4567-e89b-12d3-a456-426614174000",
    "configSha256": "64 lowercase hexadecimal characters"
  }
}
```

Semantics:

- `version` and `etag` identify the current persisted material revision.
- `configSha256` identifies the complete canonical `configJson`, independent of JSON object key
  order and fields stored outside it (`aiDescription` and `templateMeta`). A recipe can still carry
  editorial evidence inside `configJson`, so this is not an artifact-only digest.
- an identical material upsert preserves `version` and `etag`;
- changing `aiDescription`, `templateMeta` or registry metadata may rotate the registry revision
  without changing `configSha256`;
- changing `configJson` changes `configSha256`;
- the GET response publishes the same registry `etag` through the HTTP `ETag` header.

Phase 1 adds evidence; it does not yet authorize a compact page reference.

## Phase 2 authoring shape

The additive intermediate contract accepts:

```json
{
  "version": "1.0",
  "kind": "praxis.ui-composition-plan",
  "templateRef": {
    "registryKey": "praxis-dynamic-page:employee-operations-casework",
    "configSha256": "64 lowercase hexadecimal characters"
  },
  "overrides": {}
}
```

`configSha256` is mandatory for the first exact envelope reference. `version` and `etag` are
returned as audit evidence, but neither replaces the content hash. The backend resolves the current
active SYSTEM/GLOBAL template by exact key, verifies its hash, validates
`configJson.authoringPlan` as `praxis.ui-composition-plan@1.0`, records revision evidence in
non-executable plan diagnostics, and only then invokes the existing compiler. A future
artifact-only digest may be added only if the publication envelope needs independent artifact
lifecycles; it must not be inferred from the full-document hash.

The Angular path receives the resolved template body plus revision evidence from the backend or in
an explicitly supplied `UiCompositionPlanTemplateMaterialization`. Its pure
`resolveUiCompositionPlanTemplate` helper performs the same exact-reference checks before
`compileUiCompositionPlan`; neither function performs HTTP, registry search or semantic selection.

## Merge and override policy

Phase 2 must define typed, bounded overrides. Generic JSON Merge Patch over the complete plan is
rejected because it can bypass stable widget/link identities and manifest-declared authorable paths.
The implemented first form supports:

1. an expanded complete plan; or
2. a reference to a complete plan with absent or empty `overrides`.

Non-empty overrides fail with `ui-composition-template-overrides-unsupported`. Manifest-governed
override operations remain a separate phase because generic merging would weaken stable identities
and the authoring manifest boundary.

Templates seed material. They do not override manifests, resource metadata, capabilities,
authorization, field-access policy or domain decisions.

## Failure policy

Resolution must fail closed with stable diagnostics when:

- the registry key is missing or inactive;
- `configSha256` is absent, malformed or does not match current content;
- the template does not contain the declared artifact kind;
- an override is outside the Page Builder authoring manifest;
- server and Angular materialization signatures differ.

No resolver may fall back to the latest mutable template, fuzzy key match, local recipe file or a
host-owned default after a pinned reference fails.

## Impact map

| Surface | Phase 1 | Phase 2 |
| --- | --- | --- |
| `praxis-config-starter` | DTO/service/controller revision projection | exact resolver, diagnostics and pre-compiler expansion |
| `praxis-ui-angular` | no runtime change | additive `templateRef` authoring type and resolved-template compiler input |
| AI registry tools | no upload shape change | optional publication receipt containing returned revision |
| Quickstart | no change | HTTP proof for valid, stale, missing and inactive refs |
| Landing | no change | inspect both compact source ref and expanded executable page |
| Public docs | document revision semantics | document authoring/ref lifecycle and diagnostics |

## Compatibility and persistence

Phase 1 is an additive response change and requires no database migration. Existing clients may
ignore `revision`. Phase 2 remains intermediate-authoring-only: persistence continues to store the
expanded `WidgetPageDefinition`, so runtime availability does not depend on registry access.

Historical resolution is deliberately deferred. If long-lived uncompiled plans must continue to
resolve after the head changes, the correct next step is an immutable revision store keyed by
`registryKey + configSha256`; keeping parallel mutable template copies or silently accepting a new
head is not acceptable.

## Acceptance gates

Phase 1:

- record and upsert responses expose the same revision evidence;
- `configSha256` is stable under object-key reorder and changes with the configured document;
- HTTP `ETag` equals the returned registry `etag`;
- AI context reuses the canonical service mapper instead of dropping revision data;
- existing template validation, semantic search and identity-preserving upsert tests remain green.

Phase 2 gates:

- valid references resolve only through the exact active key and complete `configJson` hash;
- stale, missing, inactive, malformed, mixed-plan and non-empty-override inputs fail closed with
  stable diagnostics;
- Java integrates resolution between the provider and the existing pure compiler;
- Angular normalizes only an explicitly supplied governed materialization;
- Java and Angular tests prove that referenced and expanded plans compile to the same executable
  page;
- `WidgetPageDefinition` remains the only persisted runtime artifact.

## Quickstart HTTP host proof

`GovernedUiCompositionTemplateReferenceQuickstartIntegrationTest` starts the reference host on a
random HTTP port with the documented local origin and scope headers. It exercises the canonical
template `PUT` and `GET` endpoints, obtains the published `revision.configSha256` and `ETag`, and
then calls the canonical `page-preview` endpoint through a test-only provider that emits the exact
compact reference. The host, controller, template service, resolver and page compiler are real;
only the repository is replaced by a deterministic in-memory fixture so this gate does not require
PostgreSQL or alter an external config store.

The focused proof covers:

- active exact key plus matching hash: valid preview, revision evidence and compiled executable
  page;
- stale hash after a material update: `ui-composition-template-hash-mismatch`;
- missing exact key: `ui-composition-template-not-found`;
- inactive current head: `ui-composition-template-inactive`;
- no unresolved `templateRef` or template-resolution diagnostics in the compiled runtime page.

This is downstream HTTP integration evidence, not a published-host or PostgreSQL persistence
claim. After the first release containing this contract is published, a release gate may add an
isolated real-store smoke against that exact artifact, but it must reuse these endpoints and must
not recreate resolution semantics in the Quickstart.

## Phase 1 re-evaluation

Phase 1 passed its service, controller, AI-context and compatibility gates. The publisher audit then
found a separate prerequisite before a compilable reference can be added:

- `loadRecipeDocument` materialized `pageRef`, but preserved `authoringPlanRef` only as a string;
- the raw Employee Operations `*.ui-composition-plan.json` satisfied the old recipe filename
  predicate and could therefore be published implicitly as an unrelated variant derived from its
  filename, while an intentional `praxis-page-builder.ui-composition-plan.json` recipe uses the same
  suffix;
- `buildConfigJson` intentionally keeps recipe guidance and evidence, so `configSha256` pins the
  complete governed document rather than just the authoring plan.

The canonical publisher must therefore use an unambiguous
`*.ui-composition-plan.artifact.json` suffix for raw referenced plans, exclude only that artifact
suffix from implicit recipe discovery, materialize a referenced `authoringPlan`, validate
`kind=praxis.ui-composition-plan`, and publish it only through the owning recipe. Phase 2 remains
gated until regenerated registry evidence proves that the owning template contains the plan, the
intentional Page Builder recipe remains discoverable, and no filename-derived Employee Operations
plan variant exists.

That prerequisite has now passed: the publisher materializes `authoringPlanRef`, excludes only raw
`*.ui-composition-plan.artifact.json` files from recipe discovery, preserves the intentional Page
Builder recipe, and the regenerated registry template contains the governed authoring plan. Phase 2
therefore resolves the published envelope rather than introducing a parallel page-template store.

## Stable phase 2 diagnostics

- `ui-composition-template-resolver-unavailable`
- `ui-composition-template-not-found`
- `ui-composition-template-inactive`
- `ui-composition-template-hash-mismatch`
- `ui-composition-template-revision-invalid`
- `ui-composition-template-config-invalid`
- `ui-composition-template-authoring-plan-missing`
- `ui-composition-template-authoring-plan-kind-invalid`
- `ui-composition-template-authoring-plan-version-invalid`
- `ui-composition-template-reference-object-required`
- `ui-composition-template-reference-kind-invalid`
- `ui-composition-template-reference-version-invalid`
- `ui-composition-template-registry-key-invalid`
- `ui-composition-template-config-sha256-invalid`
- `ui-composition-template-reference-fields-invalid`
- `ui-composition-template-reference-mixed-plan`
- `ui-composition-template-overrides-unsupported`

Successful resolution adds `ui-composition-template-reference-resolved` and publishes the exact
key, hash, version and ETag under `uiCompositionPlan.diagnostics.templateResolution`; those
diagnostics are intentionally not copied into the executable `WidgetPageDefinition`.
