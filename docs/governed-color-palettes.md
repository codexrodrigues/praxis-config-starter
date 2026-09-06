# Governed color palettes

Governed color palettes are canonical design-token decisions authored, reviewed and published through the existing domain-rule lifecycle. The Config Starter does not introduce a parallel palette store: it projects applied `DomainRuleMaterialization` records whose coordinates are:

- `ruleType`: `design_token_palette`
- `targetLayer`: `design_token_catalog`
- `targetArtifactType`: `governed-color-palette`
- `targetArtifactKey`: the stable palette key

## HTTP projection

All endpoints live under `/api/praxis/config/color-palettes` and use the same tenant/environment resolution and security boundary as the other Config surfaces.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/praxis/config/color-palettes?familyKey=...` | List the latest applied projection of each palette, optionally restricted to an exact family. Results are ordered by family, variant key and palette key. |
| `GET` | `/api/praxis/config/color-palettes/{paletteKey}` | Read the latest projection, or a pinned `?version=`. Supports `If-None-Match`. |
| `POST` | `/api/praxis/config/color-palettes/previews` | Validate and normalize a candidate without persisting it. |
| `GET` | `/api/praxis/config/color-palettes/capabilities` | Discover supported purposes, formats and operations. |

Published responses include `version`, `etag`, provenance, validation evidence and `Cache-Control: private, no-cache, must-revalidate`. A consumer that pins `version` or `etag` must reject a different projection instead of silently falling back to unrelated local colors.

## Authoring envelope

The palette belongs in `parameters.palette` of a `design_token_palette` domain rule. `parameters.paletteKey` remains accepted when present, but the materialization target is also derived from `parameters.palette.paletteKey`, keeping nested authoring envelopes deterministic.

```json
{
  "ruleKey": "design.tokens.palette.corporate-main",
  "ruleType": "design_token_palette",
  "resourceKey": "design.tokens",
  "parameters": {
    "palette": {
      "paletteKey": "corporate-main",
      "displayName": "Corporate Main",
      "familyKey": "corporate",
      "variant": {
        "key": "light",
        "displayName": "Light",
        "dimensions": { "colorScheme": "light", "contrast": "standard" }
      },
      "entries": [
        {
          "tokenId": "content.primary",
          "displayName": "Primary content",
          "aliases": ["Body text"],
          "semanticRole": "content",
          "cssReference": "var(--content-primary)",
          "fallback": "#1b1b1f",
          "purposes": ["text"]
        }
      ],
      "contrastEvidence": []
    }
  }
}
```

Text tokens require contrast evidence. The server resolves fallback chains, recomputes WCAG ratios, rejects unknown/cyclic fallbacks and blocks AA/AAA failures. A ratio supplied by the caller is evidence for comparison only and is never authoritative.

Each entry must specify exactly one of `fallback` or `fallbackTokenId`. The same parser validates literal fallbacks and calculates contrast: HEX (3/4/6/8 digits), RGB(A), HSL(A), modern space/slash syntax and `transparent`. Malformed values, non-finite numbers and ambiguous fallback sources are rejected. Contrast approval uses the unrounded ratio; the response rounds only its display value. These are fallback-color measurements, not certification of host CSS overrides.

Palettes also require `familyKey` and a variant with stable `key`, human `displayName` and optional structured `dimensions`. Every token requires its own human `displayName` and an explicit `aliases` array, which may be empty. Aliases are trimmed, cannot repeat case-insensitively and cannot equal the canonical `tokenId`; they support discovery only after semantic scope resolution.

## Version lifecycle

Create subsequent versions with the existing `POST /domain-rules/definitions` contract and an explicit increasing `version`, keeping the same `ruleKey` and palette target. Each version starts as draft and follows the author/approver/publisher lifecycle. An older version of that same decision is not competing coverage for the new version; other decisions and attempts to republish an older version remain blocked by coverage review.

Palette materialization keys include the definition version. Publication atomically supersedes the previous applied target head using the existing lifecycle and uniqueness constraint. A read without `version` returns the applied head. A pinned read may return a previously applied, superseded version while its source definition remains active. Retired, rejected or deprecated source definitions are not exposed as published palettes. The old version keeps its body and ETag; an old ETag on the latest endpoint yields HTTP 200 after an upgrade, while revalidation of the pinned historical version can still yield HTTP 304.

## Focused proofs

- `GovernedColorPaletteContractValidatorTest`: color grammar, exclusive fallbacks and calculated contrast.
- `GovernedColorPalettePostgresMigrationTest`: selected canonical rule migrations through V55, upgrade to V62 via Flyway on isolated PostgreSQL, JSONB constraints and one applied head with retained history. Foreign domain prerequisites are fixtures; this is not a full production database upgrade.
- Quickstart `GovernedColorPaletteHttpIntegrationTest`: real HTTP publication of light/dark/high-contrast siblings, exact family listing, v1 → v2 lifecycle, approval gate, pinned history, ETags/304 and blocked old-version republication. It uses an isolated H2 store and test security configuration; it does not prove browser CORS or production authentication.
- Angular core and Color Lab: cache/pin validation and UI/editor interaction tests. The default lab uses an explicitly identified local fixture; `?paletteKey=<key>&version=<version>` uses the real Quickstart projection, without fixture fallback on failure. Metadata edits remain in memory, not remote configuration persistence.
- Quickstart `GovernedColorPaletteBrowserPostgresTest` runs Angular `color-components-lab-real-backend.playwright.spec.ts` against managed disposable Chromium → Angular → Quickstart → PostgreSQL processes. It proves host login, browser CORS/Origin, separated author/approver/publisher identities, persisted approvals, v1 → v2 publication, wire-level 304, historical pin and a narrow metadata-editor round-trip. The managed gate passed locally with zero retries; it remains distinct from the H2 HTTP proof and is not a deployed IdP test or a complete Config/pgvector database upgrade. See `praxis-api-quickstart/docs/governed-color-palette-browser-proof.md` for its exact migration scope and cleanup guarantees.

## Contract ownership

Praxis Config owns authoring, governance, publication and the stable projection. `@praxisui/core` owns the Angular client/cache contract. Color controls consume the projection and never become the source of truth for corporate color policy.

The clean-beta extension for human token identity and correlated palette variants is specified in
[governed-color-palette-token-identity-rfc.md](governed-color-palette-token-identity-rfc.md). The
Config DTO, validator, preview and published projection implement its first slice. Core, UI and
Quickstart adoption remain explicit later slices; consumers must not infer missing presentation or
family identity from token IDs, semantic roles, resolved colors or `scope.theme`.
