# Release Decision: Domain Knowledge Change-Set Timeline

Date: 2026-05-01
Closed: 2026-05-01

Status: published and closed as the coordinated `praxis-config-starter:0.1.0-rc.37` Domain Knowledge timeline release.

## Scope

This decision covers the read-only safe audit endpoint:

- `GET /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/timeline`

The endpoint belongs to `praxis-config-starter` because `/api/praxis/config/**`
is the canonical boundary for AI-authored semantic decisions, governance,
knowledge curation and safe observability. The quickstart proves the behavior
over HTTP, but must not redefine this contract locally.

The timeline is not a second source of truth. It is a safe projection derived
from persisted Domain Knowledge change-set state: creation, validation, review
and application metadata.

## Safety Contract

The response must remain safe for governance inspection:

- events use `visibility=safe`;
- lifecycle points include `change_set.created`, `validation.completed`,
  `review.approved` or `review.rejected`, and `change_set.applied` when present;
- events may expose safe status, validation status, operation counts, operation
  types and target concept keys;
- events must not expose raw patch payloads, evidence payload text,
  `sourcePointer`, `sourceUri`, `patchHash`, prompts, assistant messages or chat
  history.

This keeps Praxis aligned with the platform premise: AI authors semantic
decisions under governance, and runtimes consume safe projections or
materializations instead of treating UI state or raw LLM output as the business
rule source.

## Current Evidence

- `praxis-config-starter` PR #177 added the endpoint, response DTOs, OpenAPI
  contract entries, docs and focal tests.
- `praxis-config-starter/main` advanced to commit `e32d6ff`.
- Main CI passed in GitHub Actions run `25199896978`.
- Local starter validation passed before merge:
  - `mvn -q -Dtest=DomainKnowledgeChangeSetServiceTest,DomainKnowledgeChangeSetControllerTest,DomainKnowledgeChangeSetValidatorTest test`
  - `mvn -q -DskipTests install`
  - `git diff --check`
- `praxis-api-quickstart` PR #48 added the downstream strict smoke gate
  `REQUIRE_CHANGE_SET_TIMELINE=true`.
- `praxis-api-quickstart/main` advanced to commit `3cb74ef`.
- Quickstart PR CI passed in run `25199824379`; main CI passed in run
  `25199903432`.
- Local quickstart + Neon proof passed against a quickstart packaged with the
  locally installed starter:

```bash
BASE_URL=http://localhost:8099 TENANT_ID=desenv ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
tools/local-e2e/run-domain-knowledge-change-set-local.sh
```

The strict smoke returned `domain-knowledge-change-set-timeline-ready` with
`eventCount=4` and completed the lifecycle:

```text
created -> validated -> approved -> applied -> readback-confirmed -> timeline-confirmed-or-skipped
```
- `praxis-config-starter:0.1.0-rc.37` was published from `main` and Maven
  Central resolution was confirmed.
- `praxis-api-quickstart` PR #49 consumed `0.1.0-rc.37` without local
  overrides, passed `mvn -B verify`, and passed the Neon-backed strict
  Domain Knowledge timeline smoke.
- The published quickstart runtime deployed the rc.37 consumer build, and
  `/actuator/info` reported build time `2026-05-01T03:40:15.468Z`.
- The hosted strict Domain Knowledge smoke passed against
  `https://praxis-api-quickstart.onrender.com` with
  `REQUIRE_CHANGE_SET_TIMELINE=true`, returning
  `domain-knowledge-change-set-timeline-ready`, `eventCount=4`, and change set
  `01300db8-119c-4925-9d5f-1049c31cf4cc`.
- `praxisui-http-examples` commit `271b13e` added the protected timeline corpus
  proof with `runtimeRecordConfirmed=true`, `publishedBackendConfirmed=true`,
  `knownPublishedFailure=false`, `protectedContract=true`, and kept
  `llmOperational=false`.

## Publication Decision

Publication became appropriate only after all of these were true:

- the owner explicitly authorizes closing this Domain Knowledge observability
  phase;
- the quickstart needs to consume the endpoint from Maven Central without a
  local starter override;
- a deployed/published runtime proof is required for public docs, HTTP corpus or
  external consumers.

The closed Maven coordinate is:

- `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.37`

The closed Maven tag is:

- `v0.1.0-rc.37`

Do not create another Maven Central version for this phase. Any next release
must be justified by a new named phase, contract change or downstream need.

## Gates Before Publishing Maven

Use local-first validation before any remote release workflow:

1. Confirm `praxis-config-starter/main` is clean and contains PR #177.
2. Confirm no newer `v0.1.0-rc.*` tag already exists.
3. Run the focal starter tests:

```bash
mvn -q -Dtest=DomainKnowledgeChangeSetServiceTest,DomainKnowledgeChangeSetControllerTest,DomainKnowledgeChangeSetValidatorTest test
```

4. Install the starter locally and package quickstart against it:

```bash
mvn -q -DskipTests install
```

5. Run the quickstart Domain Knowledge smoke against Neon with a strict timeline
   gate:

```bash
BASE_URL=http://localhost:8099 TENANT_ID=desenv ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
tools/local-e2e/run-domain-knowledge-change-set-local.sh
```

Use GitHub Actions only as the phase-closing release gate, not as an iteration
loop.

## Maven Publication Closure

Completed closure:

1. `praxis-config-starter/main` was clean and contained the intended timeline
   implementation.
2. No newer `v0.1.0-rc.*` tag existed.
3. The release used explicit `version=0.1.0-rc.37`.
4. Tag `v0.1.0-rc.37` was created.
5. Maven Central publication completed.
6. Dependency resolution was confirmed:

```bash
mvn -q dependency:get \
  -Dartifact=io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.37 \
  -Dtransitive=false
```

For future phases, if publication stalls in Central Portal after upload,
validate dependency resolution before deciding whether to retry. Do not create
another version only to work around propagation lag.

## Quickstart Rollout Closure

Completed closure:

1. `praxis-api-quickstart/pom.xml` now uses
   `praxis.config.version=0.1.0-rc.37`.
2. Quickstart README and rollout documentation reference the same version.
3. Quickstart local validation passed with Maven resolution:

```bash
mvn -B verify
```

4. The Neon-backed strict runtime proof passed:

```bash
BASE_URL=http://localhost:8099 TENANT_ID=desenv ENVIRONMENT=local \
REQUIRE_CHANGE_SET_TIMELINE=true \
tools/local-e2e/run-domain-knowledge-change-set-local.sh
```

5. Quickstart PR #49 was merged to `main`.
6. The published quickstart runtime deployed the rc.37 consumer build.
7. The hosted strict smoke passed against the published host.

## HTTP Corpus and Public Docs

Completed closure:

1. `praxisui-http-examples/examples.manifest.json` now records
   `domain-knowledge-change-set-timeline` as published-backend confirmed.
2. The example is marked `protectedContract=true` and `llmOperational=false`.
3. `DOMAIN_KNOWLEDGE_TIMELINE_RUNBOOK.md` records the hosted proof and safety
   boundary.
4. `LLM_SURFACE.md` was regenerated without promoting the protected endpoint
   into the unauthenticated operational LLM surface.
5. Corpus validation passed with:
   - `npm run verify:manifest`
   - `npm run smoke:corpus-promises`
6. Direct hosted response inspection returned `eventCount=4`, `unsafeCount=0`
   and `leakCount=0`.

## npm Publication

No npm publication is required for this decision by default.

The current phase affects the backend governance/audit contract and quickstart
runtime smoke. Publish Angular packages only if a named external UI consumer
needs a public registry version that renders this specific Domain Knowledge
timeline.

## Acceptance Criteria

The Domain Knowledge timeline phase is published for `rc.37` because all are
true:

- Maven Central resolves the intended `praxis-config-starter` coordinate.
- `praxis-api-quickstart` consumes that coordinate without local overrides.
- Published quickstart returns `200` for
  `GET /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/timeline`.
- Runtime smoke with `REQUIRE_CHANGE_SET_TIMELINE=true` passes against the
  published host.
- The response includes only `visibility=safe` events.
- The response includes creation, validation, approval and application events
  for the governed change-set lifecycle.
- The response does not expose raw evidence text, source pointers, source URIs,
  patch hashes, prompt content or chat history.
- `praxisui-http-examples` records the timeline as a protected, published
  contract proof and not as an unauthenticated LLM operation.

## Current Recommendation

Treat `rc.37` as closed. Do not republish this phase, do not create another
Maven version for documentation drift, and do not use Actions unless a new phase
is being closed.

The next recommended work is to start the next functional phase around cockpit
and Page Builder continuity only when it has a named scope and local-first test
lane. The Domain Knowledge timeline itself should remain a safe observability
projection, not a new source of business truth.
