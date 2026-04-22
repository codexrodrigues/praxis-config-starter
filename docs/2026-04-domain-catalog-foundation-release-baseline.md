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

## Post-Publication Integrated Authoring Gate

After the first post-publication full smoke exposed two gate weaknesses, the
authoring smoke line was hardened without waiting for another Maven Central
publication:

- quickstart startup timeout was raised to `180` seconds for CI stability;
- checked-out starter versions are aligned to the quickstart Maven properties
  before local install;
- the SSE smoke now fails on terminal `error` events instead of treating any
  terminal event as success;
- the SSE request explicitly carries the selected AI provider;
- the SSE smoke prompt uses a deterministic, enum-valid table density request;
- `praxis-ui-angular/main` clears stale agentic quick replies when a backend
  semantic quick reply starts a new turn.

The complete cross-repository gate then passed against checked-out `main`
sources:

| Workflow | Run | Ref | Result |
| --- | --- | --- | --- |
| `Agentic Authoring HTTP Smoke` | [`24764478409`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24764478409) | `main` | success |

Confirmed steps:

- local install of `praxis-metadata-starter/main` at the quickstart metadata
  version;
- local install of `praxis-config-starter/main` at `0.1.0-rc.6`;
- packaging of `praxis-api-quickstart/main` against those local starter builds;
- quickstart authoring HTTP/SSE smoke suite with real OpenAI provider;
- quickstart Domain Catalog v0.2 HTTP smoke;
- page-builder agentic full Playwright E2E gate against
  `praxis-ui-angular/main`.

## Published Relationship Hint Contract Line

After the relationship-aware authoring prompt context was validated, the AI API
contract was advanced so consumers can send typed domain catalog hint payloads
instead of relying on loose JSON shapes.

| Project | Version / Commit | Evidence |
| --- | --- | --- |
| `praxis-config-starter` | `0.1.0-rc.8` / `b7870ba` | published to Maven Central and tagged `v0.1.0-rc.8` |
| `praxis-api-quickstart` | `fffc11e` | consumes `praxis-config-starter:0.1.0-rc.8`; CI passed in [`24770883403`](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/24770883403) |
| `praxis-ui-angular` | `b4b42963` | includes generated AI contract/domain catalog consumer updates; CI passed in [`24770877526`](https://github.com/codexrodrigues/praxis-ui-angular/actions/runs/24770877526) |

The Maven Central coordinate was verified from a temporary empty Maven
repository:

```powershell
mvn -B -U "-Dmaven.repo.local=$tempRepo" dependency:get "-Dartifact=io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.8"
```

The quickstart host then passed:

```powershell
mvn -B verify
```

Finally, the full cross-repository authoring gate passed against current
`main` refs:

| Workflow | Run | Ref | Result |
| --- | --- | --- | --- |
| `Agentic Authoring HTTP Smoke` | [`24771109354`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24771109354) | `main` | success |

Confirmed steps:

- checkout of `praxis-config-starter/main`, `praxis-api-quickstart/main`,
  `praxis-metadata-starter/main` and `praxis-ui-angular/main`;
- local install of `praxis-metadata-starter` and `praxis-config-starter`;
- packaging of `praxis-api-quickstart` against the checked-out starters;
- quickstart authoring HTTP/SSE smoke suite with OpenAI;
- quickstart Domain Catalog v2 HTTP smoke;
- page-builder agentic full Playwright E2E gate.

## Published AI Visibility Governance Line

After hardening Domain Catalog LLM context visibility, the config starter was
published again and the operational quickstart was advanced to consume the new
coordinate directly from Maven Central.

| Project | Version / Commit | Evidence |
| --- | --- | --- |
| `praxis-config-starter` | `0.1.0-rc.9` / `412a24b` | published to Maven Central by [`24779789126`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24779789126) |
| `praxis-api-quickstart` | `3abb9c9` | consumes `praxis-config-starter:0.1.0-rc.9`; CI passed in [`24780498818`](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/24780498818) |

This line confirms that:

- `aiUsage.visibility=deny` items are excluded from LLM prompt context and RAG
  publication;
- `aiUsage.visibility=mask` and `summarize_only` items are exposed to LLMs only
  as governed summaries;
- raw deterministic catalog reads remain available through `/items`;
- the quickstart consumed the published artifact without local overrides;
- the Render-hosted catalog context remained ready for
  `human-resources.funcionarios`, `human-resources.folhas-pagamento` and
  `operations.missoes`.

## Published Resource-Scoped Context Line

After validating the Render-hosted quickstart with multiple resource catalogs
under the same `serviceKey`, a runtime defect was found in the LLM context
lookup: `/api/praxis/config/domain-catalog/context` selected the latest release
for the service, which could return `operations.missoes` context for a query
that belonged to `human-resources.funcionarios`.

The config starter now supports `resourceKey` scoping for latest-release
retrieval APIs while preserving the previous service-wide behavior when
`resourceKey` is omitted.

| Project | Version / Commit | Evidence |
| --- | --- | --- |
| `praxis-config-starter` | `0.1.0-rc.10` / `4e5aec4` | published to Maven Central by [`24781078326`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24781078326) |
| `praxis-api-quickstart` | `7e42d37` | consumes `praxis-config-starter:0.1.0-rc.10`; CI passed in [`24781951059`](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/24781951059) |

This line confirms that:

- `/context`, `/items/latest` and `/relationships/latest` can be scoped by
  `resourceKey`;
- prompt context retrieval can read `contextHints.domainCatalog.resourceKey` or
  a top-level `domainResourceKey`;
- service-wide latest-release lookup remains available for broad discovery;
- no database migration was required for this release;
- local quickstart validation passed against the remote database with Flyway at
  version `17` and the schema already up to date.

## Published Resource-Aware Authoring Envelope Line

After resource-scoped runtime context was published, the authoring hint contract
was advanced so LLM task envelopes can carry the canonical domain resource key
derived from the selected REST resource.

| Project | Version / Commit | Evidence |
| --- | --- | --- |
| `praxis-config-starter` | `0.1.0-rc.11` / `3f39725` | published to Maven Central by [`24782442986`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24782442986) |
| `praxis-api-quickstart` | `d3932a0` | consumes `praxis-config-starter:0.1.0-rc.11`; CI passed in [`24784518411`](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/24784518411) |

This line confirms that:

- authoring discovery now carries `contextHints.domainCatalog.resourceKey`;
- `/api/{context}/{resource}` paths resolve to canonical keys such as
  `human-resources.funcionarios`;
- the agentic domain task envelope is documented in
  `docs/ai/agentic-domain-task-envelope.md`;
- local quickstart validation passed against the remote database with Flyway at
  version `17`; the schema was already up to date and no migration was applied;
- the quickstart consumed the published `0.1.0-rc.11` artifact from Maven
  Central without local overrides.

The Render-hosted quickstart was also validated after publication:

- `/api/praxis/config/domain-catalog/context` returned resource-scoped releases
  for `human-resources.funcionarios`, `human-resources.folhas-pagamento` and
  `operations.missoes`;
- CPF and salary governance context remained exposed only as
  `payloadMode=governed-summary`;
- `/api/praxis/config/ai/authoring/resource-candidates` returned resource
  quick replies whose `contextHints.domainCatalog.resourceKey` values included
  `human-resources.folhas-pagamento` and `human-resources.eventos-folha`;
- returned authoring hints included relationship retrieval with
  `relationships.enabled=true` and `relationships.federated=true`.

The same read-only runtime check is now repeatable from
`praxis-api-quickstart` commit `ef54722` through:

```bash
BACKEND_URL=https://praxis-api-quickstart.onrender.com scripts/verify-domain-catalog-authoring-runtime.sh
```

The quickstart CI for this script passed in
[`24785032531`](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/24785032531).

The quickstart then added the manual/scheduled `Domain Catalog Runtime Smoke`
workflow in commit `aa8ce93`. The first manual dispatch against the Render
host passed in
[`24785680070`](https://github.com/codexrodrigues/praxis-api-quickstart/actions/runs/24785680070).

The authoring envelope derivation is also locked by
`AgenticAuthoringDomainCatalogHintsTest` in commit `a855838`. It covers
collection paths such as `/api/human-resources/funcionarios`, action paths such
as `/api/operations/missoes/{id}/actions/start`, canonical `resourceKey`,
`contextKey`, service override and federated relationship hints. The CI for
that guard passed in
[`24785870646`](https://github.com/codexrodrigues/praxis-config-starter/actions/runs/24785870646).

The next implementation design is captured in
`docs/domain-catalog/domain-knowledge-layer-v1.md`, which separates immutable
catalog releases from curated domain knowledge and future executable
rule/policy artifacts.

The first database foundation for that design is staged as
`V18__create_domain_knowledge_layer.sql`. This migration must not be applied
manually to the shared remote config database without validating the remote
Flyway history first.

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
