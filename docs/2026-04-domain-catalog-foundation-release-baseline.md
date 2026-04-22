# Domain Catalog Foundation Release Baseline

Date: 2026-04-21

This note records the verified release baseline for the first Praxis Domain
Catalog foundation across the backend starters and the operational quickstart.

## Released Artifacts

| Project | Version / Tag | Commit | Publication |
| --- | --- | --- | --- |
| `praxis-metadata-starter` | `v8.0.0-rc.7` | `40174921` | Maven Central |
| `praxis-config-starter` | `v0.1.0-rc.1` | `9e11656` | Maven Central |
| `praxis-api-quickstart` | `v2.0.0-rc.9` | `f11b547` | GitHub tag |

The Maven Central artifacts were verified through direct repository checks:

- `praxis-metadata-starter:8.0.0-rc.7` returned `HTTP 200`.
- `praxis-config-starter:0.1.0-rc.1` returned `HTTP 200`.

## Scope Confirmed

- `praxis-metadata-starter` exposes the runtime semantic domain catalog through
  `/schemas/domain`.
- `praxis-config-starter` persists and serves the domain catalog through
  `/api/praxis/config/domain-catalog`.
- `praxis-config-starter` includes V17 database support for
  `domain_catalog_release` and `domain_catalog_item`.
- `praxis-api-quickstart` consumes the published starter versions and validates
  the integrated runtime path.

## Validation

- `praxis-api-quickstart` CI passed for commit `f11b547`.
- `praxis-api-quickstart` local `sh ./mvnw -q test` passed.
- `praxis-metadata-starter` latest post-release pipeline check passed on `main`.
- `praxis-config-starter` latest post-release pipeline check passed on `main`.
- The quickstart CI now waits for newly published Praxis starter artifacts to
  become visible on Maven Central before running `mvn verify`.

## Post-Release Authoring Gate

After publishing `praxis-config-starter:0.1.0-rc.2`, the full agentic
authoring gate was executed on `praxis-config-starter/main`.

| Workflow | Run | Ref | Commit | Result |
| --- | --- | --- | --- | --- |
| `Agentic Authoring HTTP Smoke` | [`24754530578`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24754530578) | `main` | `389a0be93f567ee77e2ac2b66b820355f3108c5b` | success |

Confirmed steps:

- checkout of `praxis-config-starter`, `praxis-api-quickstart` and
  `praxis-ui-angular`;
- local install of the checked-out `praxis-config-starter`;
- packaging of `praxis-api-quickstart` against the local starter;
- quickstart authoring HTTP/SSE smoke suite;
- full page-builder agentic Playwright E2E gate;
- smoke artifact upload.

This run verifies the authoring/SSE/page-builder integration path after the
`0.1.0-rc.2` release line and records the operational baseline for subsequent
manifest/backend authoring work.

## Release Pipeline Adjustment

The first publication workflows for both starters uploaded successfully to
Sonatype Central, but failed after the upload because the plugin polling window
timed out before Central completed publication.

Follow-up pipeline commits extended the Central wait window:

- `praxis-metadata-starter`: `5cb98f48`
- `praxis-config-starter`: `d8366d8`

The release workflows now use:

- `waitUntil=PUBLISHED`
- `waitMaxTime=7200`
- `central-publishing-maven-plugin:0.10.0` on both starters

These commits do not change the released runtime artifacts. They harden future
release publication checks.

## Known Residuals

- Historical failed GitHub Actions runs remain visible for the initial release
  attempts because the Sonatype polling timed out after upload.
- The published artifacts are nevertheless available on Maven Central and were
  consumed successfully by the quickstart.
- `praxis-config-starter` may have long-running optional smoke workflows that
  are separate from the starter release baseline.

## Next Architectural Step

The next recommended product/architecture step is to evolve the Domain Catalog
from a technical runtime artifact into a governed semantic layer:

- define stable domain vocabulary ownership;
- map field/entity/API terms to business concepts;
- add governance metadata for privacy, compliance, and LLM visibility;
- make cross-service domain relationships explicit;
- expose a compact runtime context that LLMs can consume without reading code.
