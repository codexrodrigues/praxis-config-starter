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

## Post-Merge Local Authoring Gate

After merging the generated AI contract synchronization into
`praxis-ui-angular/main` and the matching generator fix into
`praxis-config-starter/main`, the same integration path was validated locally
without waiting for a new Maven Central publication.

| Project | Branch | Commit |
| --- | --- | --- |
| `praxis-config-starter` | `main` | `1d64099b45bf13a38449676f1b1aa7408ea2ae24` |
| `praxis-ui-angular` | `main` | `4cc6c4b379e408ea30c03eda71deaa6d5fe34943` |
| `praxis-api-quickstart` | `main` | `8be53c04e2b6bf407c1de66f4349d4c874291c4a` |

Validation commands:

```powershell
mvn -B -DskipTests install
mvn -B -DskipTests "-Dpraxis.config.version=0.1.0-rc.1" package
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartAgenticAuthoringHttpSmokeSuite.ps1 -Provider openai -JavaHome "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21" -QuickstartRoot "D:\Developer\praxis-plataform\praxis-api-quickstart" -EnvFile "D:\Developer\praxis-plataform\praxis-config-starter\.env.openai.local.ps1" -StreamProcessingTimeoutSeconds 180
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-PbAgenticFullE2E.ps1 -Provider openai -JavaHome "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21" -QuickstartRoot "D:\Developer\praxis-plataform\praxis-api-quickstart" -UiRoot "D:\Developer\praxis-plataform\praxis-ui-angular" -EnvFile "D:\Developer\praxis-plataform\praxis-config-starter\.env.openai.local.ps1" -StreamProcessingTimeoutSeconds 180 -Retries 0
```

Confirmed results:

- local install of `praxis-config-starter:0.1.0-rc.1` passed;
- `praxis-api-quickstart` packaged against the local starter override passed;
- quickstart authoring HTTP/SSE smoke returned `planValid`,
  `compileValid`, `previewValid`, `applyPersisted`, `applyCleanupDeleted`,
  `streamTerminalSeen` and `streamReplayChecked` as `true`;
- page-builder agentic full E2E passed cleanly with `-Retries 0`
  (`3 passed`);
- backend and Angular dev server ports were released after the scripts
  completed.

One earlier full E2E run passed on retry after a provider-dependent
clarification turn. The clean `-Retries 0` rerun is the accepted local gate
evidence for this baseline.

The remote workflow was not dispatched from the local workstation because no
`GH_TOKEN`, `GITHUB_TOKEN` or `GITHUB_PAT` was available to
`Invoke-GitHubAgenticAuthoringSmokeWorkflow.ps1`.

## Version Alignment After Post-Merge Gate

Because `v0.1.0-rc.2` already points to commit `70c19c1`, `v0.1.0-rc.3`
already points to commit `740ea1b`, and `v0.1.0-rc.4` already points to commit
`1d64099`, the post-merge `main` line was advanced to `0.1.0-rc.5` instead of
reusing an existing release coordinate for different code.

For subsequent local downstream validation, install the checked-out starter and
package `praxis-api-quickstart` with:

```powershell
mvn -B -DskipTests install
mvn -B -DskipTests "-Dpraxis.config.version=0.1.0-rc.5" package
```

Keep `praxis-api-quickstart` on `0.1.0-rc.2` until `0.1.0-rc.5` is published
and verified through Maven Central or through the local starter override above.

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
