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

After `0.1.0-rc.5` was published and verified through Maven Central, the
`praxis-api-quickstart` default dependency was advanced to
`praxis-config-starter:0.1.0-rc.5` and validated without a local override.

| Project | Branch | Commit | Evidence |
| --- | --- | --- | --- |
| `praxis-config-starter` | `v0.1.0-rc.5` | `6e0d932` | published to Maven Central |
| `praxis-api-quickstart` | `main` | `619625e` | consumed `0.1.0-rc.5` without override and passed CI |

Follow-up workflow maintenance also moved official GitHub Actions in
`praxis-config-starter` and `praxis-api-quickstart` to their Node 24 runtime
major versions (`actions/checkout@v5`, `actions/setup-java@v5`, and
`actions/setup-node@v5` where applicable).

## Current Remote End-to-End Gate

After publishing `praxis-config-starter:0.1.0-rc.5`, updating
`praxis-api-quickstart` to consume it by default, and switching the authoring
smoke workflow defaults to current `main` refs, the remote cross-repository gate
was executed again with the full page-builder E2E enabled.

| Workflow | Run | Ref | Result |
| --- | --- | --- | --- |
| `Agentic Authoring HTTP Smoke` | [`24758264901`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24758264901) | `main` | success |

Confirmed steps:

- checkout of `praxis-config-starter/main`, `praxis-api-quickstart/main` and
  `praxis-ui-angular/main`;
- setup of Java 21 and Node.js 20 runtime dependencies;
- local install of the checked-out `praxis-config-starter`;
- packaging of `praxis-api-quickstart` against the local starter;
- installation of `praxis-ui-angular` dependencies and Playwright Chromium;
- quickstart authoring HTTP/SSE smoke suite;
- full page-builder agentic Playwright E2E gate;
- smoke artifact upload.

This is the accepted remote end-to-end evidence for the current authoring stack
after the `0.1.0-rc.5` release line.

## Governed Domain Catalog v0.2 Local Integration Gate

After the governed semantic layer follow-up, the metadata and config starters
were installed locally and the operational quickstart was packaged against those
local artifacts without waiting for a Maven Central publication.

| Project | Branch | Commit |
| --- | --- | --- |
| `praxis-metadata-starter` | `main` | `91822ac5` |
| `praxis-config-starter` | `main` | `424855b` |
| `praxis-api-quickstart` | `main` | local package with starter overrides |

Validation commands:

```powershell
mvn -B -DskipTests install
mvn -B -DskipTests "-Dpraxis.core.version=8.0.0-rc.7" "-Dpraxis.config.version=0.1.0-rc.5" package
java -jar target\praxis-api-quickstart-2.0.0-rc.9.jar
```

Confirmed HTTP smoke results on `http://localhost:8088`:

- `/actuator/health` returned `UP`;
- `/schemas/domain?resourceKey=human-resources.folhas-pagamento` returned
  `praxis.domain-catalog/v0.2`;
- the returned catalog included `45` aliases, `1` context and `24` nodes;
- `POST /api/praxis/config/domain-catalog/ingest` accepted the v0.2 catalog
  and persisted `175` projected items;
- `GET /api/praxis/config/domain-catalog/context` returned projected context
  items for governance queries such as `folha`, `pagamento` and `salario`.

This confirms the end-to-end local path for the governed domain catalog:
metadata runtime emission, config runtime schema validation, persistence, and
queryable projection.

The same path is now covered by the repeatable smoke script:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartDomainCatalogV2HttpSmoke.ps1 -JavaHome "D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21" -QuickstartRoot "D:\Developer\praxis-plataform\praxis-api-quickstart"
```

The first script run against the locally packaged quickstart confirmed:

- catalog schema version `praxis.domain-catalog/v0.2`;
- `45` aliases, `1` context and `24` nodes from metadata runtime emission;
- `175` persisted projection items;
- projected node, alias and governance retrieval from
  `/api/praxis/config/domain-catalog/context`;
- governed semantic payload present in node projection;
- alias payload present in alias projection.

The remote `Agentic Authoring HTTP Smoke` workflow now installs
`praxis-metadata-starter` locally, packages `praxis-api-quickstart` against both
local starters and runs this Domain Catalog v0.2 smoke in the same remote gate as
the authoring HTTP/SSE validation. This keeps the platform gate from silently
falling back to an older published metadata starter that still emits
`praxis.domain-catalog/v0.1`.

The updated remote gate was dispatched and completed successfully:

| Workflow | Run | Ref | Result |
| --- | --- | --- | --- |
| `Agentic Authoring HTTP Smoke` | [`24760282410`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24760282410) | `main` | success |

Confirmed steps:

- checkout of `praxis-config-starter/main`, `praxis-api-quickstart/main` and
  `praxis-metadata-starter/main`;
- local install of `praxis-metadata-starter`;
- local install of the checked-out `praxis-config-starter`;
- packaging of `praxis-api-quickstart` against both local starters;
- quickstart authoring HTTP/SSE smoke suite;
- quickstart Domain Catalog v0.2 HTTP smoke;
- smoke artifact upload.

The same updated gate was then executed with the full page-builder E2E enabled:

| Workflow | Run | Ref | Result |
| --- | --- | --- | --- |
| `Agentic Authoring HTTP Smoke` | [`24760628862`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24760628862) | `main` | success |

Confirmed additional steps:

- checkout of `praxis-ui-angular/main`;
- setup of Node.js 20;
- installation of `praxis-ui-angular` dependencies;
- installation of Playwright Chromium;
- page-builder agentic full E2E gate.

## Published Domain Catalog v0.2 Starter Line

After the local and remote gates passed against checked-out `main` sources, the
governed Domain Catalog v0.2 line was published and consumed without local
overrides.

| Project | Version / Commit | Evidence |
| --- | --- | --- |
| `praxis-metadata-starter` | `8.0.0-rc.9` | published to Maven Central by [`24761151253`](https://github.com/codexrodrigues/praxis-metadata-starter/actions/runs/24761151253) |
| `praxis-config-starter` | `0.1.0-rc.6` | published to Maven Central by [`24761285613`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24761285613) |
| `praxis-api-quickstart` | `16f1807` | consumes `praxis-metadata-starter:8.0.0-rc.9` and `praxis-config-starter:0.1.0-rc.6`; CI passed in [`24761864949`](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/24761864949) |

Local validation before the quickstart push also passed with:

```powershell
mvn -B verify
```

This verifies the published starter coordinates, not only the local override
path used by the cross-repository authoring smoke workflow.

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
