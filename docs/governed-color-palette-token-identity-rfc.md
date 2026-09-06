# Governed color palette token identity and variants

Status: accepted; slices 1–4 implemented  
Date: 2026-09-06  
Canonical owner: `praxis-config-starter`

## Context

The published `governed-color-palette` projection already provides palette identity, display name,
version, ETag, scope, provenance, token identity, semantic role, CSS reference, fallback and
purposes. Color Input and Color Picker can therefore search and group technical token data without
inventing identity.

The projection cannot currently express a human-facing name or aliases for an individual token,
or correlate light, dark, high-contrast, brand and tenant variants as members of one palette
family. Frontend inference from `tokenId`, `semanticRole`, color equality or `scope.theme` would be
ambiguous and would make a runtime consumer the source of business semantics.

## Classification

`platform-gap`. The missing data is needed by at least Color Input and Color Picker and is expected
to serve future design-token catalogs, authoring tools and theme governance. The Config Starter is
the owner because the data participates in authoring, approval, publication, versioning and the
immutable runtime projection.

## Decision

Extend the authorable palette and its published projection with the following required beta
contract:

```json
{
  "paletteKey": "praxis.corporate.dark",
  "displayName": "Praxis Corporate — Dark",
  "familyKey": "praxis.corporate",
  "variant": {
    "key": "dark",
    "displayName": "Dark",
    "dimensions": {
      "colorScheme": "dark",
      "contrast": "standard",
      "brand": "praxis"
    }
  },
  "entries": [
    {
      "tokenId": "brand.primary",
      "displayName": "Primary brand",
      "aliases": ["Main brand", "Primary action"],
      "semanticRole": "primary",
      "cssReference": "var(--md-sys-color-primary)",
      "fallback": "#6750a4",
      "purposes": ["fill", "focus"]
    }
  ]
}
```

`familyKey` is the stable identity shared by correlated palettes. `variant.key` is unique within a
family. `variant.dimensions` is a governed map of exact dimension IDs and values; consumers must not
route primary intent by matching its text. `displayName` is presentation copy. `aliases` improve
discovery after the palette and token scope are already resolved and never replace `tokenId` as
persisted identity.

## Invariants

- `familyKey`, `variant.key`, `variant.displayName` and every token `displayName` are non-blank.
- A token has an explicit `aliases` array, which may be empty.
- Token IDs remain unique within a palette and canonical across variants of the same family.
- Aliases are trimmed, unique case-insensitively within the token and cannot equal its `tokenId`.
- The pair `familyKey + variant.key` identifies one published head per tenant and environment.
- Variant dimensions are identifiers, not localized labels or free-form rules.
- Color controls continue to emit their existing color payloads; adding token identity to a form
  payload requires a separate explicit contract.
- ETag covers the normalized names, aliases and variant identity so a published copy change is
  observable and pinnable.

## Rejected alternatives

- Derive names from `tokenId`: loses domain language and localization ownership.
- Treat `semanticRole` or `purposes` as aliases: confuses classification with human discovery.
- Match tokens by resolved color: different tokens may intentionally share a value.
- Store variants only in `scope.theme`: it does not establish a stable family or variant identity.
- Add frontend-only metadata: creates a second source of truth and breaks authoring/publication.
- Introduce v1/v2 projection paths: unnecessary parallel contract while the platform is beta.

## Impact map

| Surface | Required change |
| --- | --- |
| Config DTO, validator and projection | Normalize and validate the new required fields; include them in preview/list/get and ETag evidence. |
| Domain-rule examples and tests | Migrate all palette fixtures in the same change; reject missing or ambiguous identities. |
| `@praxisui/core` | Extend the canonical models and validate/cache the complete projection. |
| Color Input and Color Picker | Display/search published names and aliases; offer explicit variant navigation without changing color payloads. |
| Color Lab | Demonstrate at least light/dark/high-contrast siblings and editor/runtime round-trip. |
| Quickstart | Prove HTTP publication, family listing, pinning and ETag behavior against the real projection. |
| Docs and registry | Regenerate component docs, AI registry snapshot and public examples. |

No new Angular package dependency edge is expected. The Core remains the sole Angular owner of the
transport model; Dynamic Fields consumes it directly.

## Beta migration

This is a clean migration, not a compatibility layer. Update the Config validator, all stored test
fixtures, Quickstart examples and Angular fixtures in one branch. Old candidate payloads missing the
new fields fail preview/publication with actionable paths. Previously published development data
must be republished from its governed decision; the runtime must not synthesize names or variant
identity. No deprecated aliases, feature flags or parallel endpoints are introduced.

## Implementation slices

1. Config contract: DTOs, validator, preview/projection, normalized ETag and focused tests.
2. Core transport: TypeScript models, response acceptance and direct-consumer build.
3. Runtime UX: names/aliases in discovery and an explicit family/variant selector.
4. Operational proof: Quickstart publication/history/ETag plus Color Lab desktop and narrow flows.
5. Derived artifacts: public docs, catalogs, registry ingestion and snapshots.

Each slice must keep the repository buildable. Slices may share one branch, but the Config contract
must land before consumer inference is removed.

Implementation status: slices 1–4 cover DTOs, validation/normalization, preview, published
projection, ETag evidence, repository-level family/variant uniqueness, Core transport, runtime UX,
responsive Color Lab interaction, the isolated Quickstart HTTP proof and the managed disposable
Chromium → Angular → Quickstart → PostgreSQL gate. Public landing-page publication outside the
component registry remains part of slice 5.

## Acceptance criteria

- Preview rejects missing family, variant or token presentation identity with exact field paths.
- Preview rejects duplicate aliases and duplicate variant identity within the governed family scope.
- Published list/get responses expose names, aliases and variant identity unchanged after reload.
- A family query returns deterministic variant order without using fuzzy or keyword intent routing.
- ETag changes when a published name, alias or variant dimension changes.
- Core preserves the projection byte semantics and rejects a pinned identity mismatch.
- Color Input and Color Picker find a token by published name or alias and still emit their current
  documented color value.
- Two tokens sharing the same CSS reference remain separately discoverable.
- Color Lab proves 390 px and desktop variant switching, search, cancel/apply and editor reopen.
- Config, Core, Dynamic Fields and Quickstart focused suites pass; registry and snapshots are synced.

## Success metric

An equivalent consumer can offer governed, human-readable token and variant discovery using only
the published projection, with no host-specific label map, color matching or duplicated variant
configuration.
