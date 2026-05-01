# Release Decision: Domain Knowledge Change-Set Timeline

Date: 2026-05-01

Status: implementation merged to `main`; publication deferred until phase-close authorization.

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

## Publication Decision

Do not publish a new Maven Central version only because the implementation
merged. Publication becomes appropriate when at least one of these is true:

- the owner explicitly authorizes closing this Domain Knowledge observability
  phase;
- the quickstart needs to consume the endpoint from Maven Central without a
  local starter override;
- a deployed/published runtime proof is required for public docs, HTTP corpus or
  external consumers.

The next candidate coordinate should be:

- `io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.37`
- tag: `v0.1.0-rc.37`

Use that coordinate only if no newer `v0.1.0-rc.*` tag exists when publication
is authorized.

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

## Maven Publication Path

When publication is authorized:

1. Trigger the `CI and Release Java Starter (praxis-config-starter)` workflow
   manually from `main`.
2. Use explicit `version=0.1.0-rc.37`, unless a newer tag already exists.
3. Let the workflow create tag `v0.1.0-rc.37` and publish to Maven Central.
4. Confirm Maven Central resolution:

```bash
mvn -q dependency:get \
  -Dartifact=io.github.codexrodrigues:praxis-config-starter:0.1.0-rc.37 \
  -Dtransitive=false
```

5. Only after resolution succeeds, update `praxis-api-quickstart` to consume the
   published coordinate and harden published/runtime validation.

## Quickstart Rollout After Publication

After Maven Central resolves the published coordinate:

1. Update `praxis-api-quickstart/pom.xml`:
   - `praxis.config.version=0.1.0-rc.37`
2. Update quickstart docs to reference the published Domain Knowledge timeline
   cut.
3. Run:

```bash
mvn -B verify
```

4. Run the Domain Knowledge change-set runtime smoke with:

```bash
REQUIRE_CHANGE_SET_TIMELINE=true
```

5. Merge one quickstart PR with the validation evidence.
6. Redeploy the published quickstart runtime.
7. Run the published/hosted smoke once as a phase gate.

## HTTP Corpus and Public Docs

Do not promote any protected HTTP example as publicly confirmed until a deployed
quickstart host returns `200` for the timeline endpoint with the expected safe
events.

If an HTTP corpus example is added later:

- keep write examples out of unauthenticated LLM-operational surfaces;
- mark protected governed endpoints explicitly;
- only set `publishedBackendConfirmed=true` with hosted proof evidence;
- regenerate public surfaces only when the endpoint is intentionally part of
  that surface.

## npm Publication

No npm publication is required for this decision by default.

The current phase affects the backend governance/audit contract and quickstart
runtime smoke. Publish Angular packages only if a named external UI consumer
needs a public registry version that renders this specific Domain Knowledge
timeline.
