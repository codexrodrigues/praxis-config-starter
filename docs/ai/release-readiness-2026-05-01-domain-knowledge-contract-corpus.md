# Release Readiness - Domain Knowledge Contract and Corpus Checkpoint

Date: 2026-05-01

## Scope

This report records the local-first readiness checkpoint after the governed
Domain Knowledge evidence lifecycle became explicit in the public AI contract
and in the derived HTTP corpus.

The checkpoint covers:

- the public OpenAPI contract for the protected Domain Knowledge change-set
  lifecycle;
- generated backend and Angular AI contract bindings;
- derived HTTP corpus examples for `add_evidence` and `revert_evidence`;
- corpus safety classification that keeps mutating protected examples outside
  `llmOperational`;
- local validations across the canonical starter, Angular binding consumer and
  HTTP examples corpus.

This checkpoint does not publish a new Maven Central artifact, npm package,
hosted quickstart build or GitHub Actions gate.

## Version Set

- `praxis-config-starter`: `main` commit `6d7d978`
  (`Expose Domain Knowledge change-set contract [skip ci]`).
- `praxis-ui-angular`: `main` commit `b1f38469`
  (`Sync AI contract binding hash [skip ci]`).
- `praxis-api-quickstart`: `main` commit `60d8f12`
  (`Prove project knowledge retrieval after evidence revert [skip ci]`).
- `praxisui-http-examples`: `main` commit `ac463d4`
  (`Add Domain Knowledge change-set lifecycle corpus [skip ci]`).

## Contract State

The current public AI contract now documents the protected Domain Knowledge
change-set lifecycle:

```http
POST  /api/praxis/config/domain-knowledge/change-sets
GET   /api/praxis/config/domain-knowledge/change-sets
GET   /api/praxis/config/domain-knowledge/change-sets/{changeSetId}
POST  /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/validate
PATCH /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/status
POST  /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/apply
GET   /api/praxis/config/domain-knowledge/change-sets/{changeSetId}/timeline
```

The contract remains intentionally narrow:

- `add_evidence` is the governed additive Project Knowledge write path.
- `revert_evidence` is the governed lifecycle correction path.
- destructive `delete_*` and broad `replace_*` operations remain blocked.
- apply remains a separate step after approval and validation.
- safe timeline/readback projections must not expose raw patch payloads,
  evidence keys, replacement keys, source pointers, source URIs, patch hashes,
  prompts, transcripts or chat history.
- Project Knowledge influence continues to require active evidence.

## Derived Artifacts

Updated artifacts:

- `docs/ai/contracts/praxis-ai-api-contract-v1.1.openapi.yaml`
- `src/main/java/org/praxisplatform/config/contract/AiContractSpec.java`
- `../praxis-ui-angular/projects/praxis-ai/src/lib/core/contracts/ai-contract.generated.ts`
- `../praxisui-http-examples/http/config/domain_knowledge_change_set_lifecycle.http`
- `../praxisui-http-examples/payloads/config/domain_knowledge_change_set_add_evidence.json`
- `../praxisui-http-examples/payloads/config/domain_knowledge_change_set_revert_evidence.json`
- `../praxisui-http-examples/examples.manifest.json`
- `../praxisui-http-examples/DOMAIN_KNOWLEDGE_TIMELINE_RUNBOOK.md`

No `LLM_SURFACE.md` operational expansion was produced by the corpus generator.
That is expected: the new lifecycle example is `referenceOnly`, protected,
tenant-scoped and destructive.

## Validated Locally

Config-starter contract gate:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

mvn -Dtest=AiApiContractOpenApiTest,AiContractSpecConsistencyTest,AiContractV11RetroCompatibilityTest test -q
git diff --check
```

Result:

- OpenAPI parsed successfully;
- generated Java binding stayed consistent with the contract hash;
- retro-compatibility gate passed with the required Domain Knowledge
  controller/service test fixture;
- whitespace gate passed.

Angular binding consumer:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

npx ng build praxis-ai
git diff --check
```

Result:

- `@praxisui/ai` built successfully against the generated contract binding;
- whitespace gate passed.

HTTP examples corpus:

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxisui-http-examples

npm run verify:manifest
npm run generate:llm-surface
npm run smoke:corpus-promises
git diff --check
```

Result:

- `Manifest OK: 96 examples validated.`
- `LLM_SURFACE.md` generated without promoting the lifecycle write example to
  `llmOperational`;
- published-host corpus promises passed:
  `31 llmOperational examples, 22 operational bootstrap ids`;
- whitespace gate passed.

## GitHub Actions Usage

No GitHub Actions were used for this checkpoint.

The release budget remains:

- zero Actions for normal iteration;
- one phase-close remote gate only if a named release or hosted smoke needs it;
- one publication action only when the owner explicitly authorizes a Maven/npm
  cut.

## Release State

Ready as an internal source-level beta checkpoint for contract/corpus alignment.

Not ready for a standalone publication by itself.

Do not create a new Maven Central version, npm package or hosted release only
because the contract documentation and derived corpus were synchronized. The
next publication should be a named phase cut that also includes a fresh local
runtime proof and an intentionally selected remote gate.

## Recommended Next Step

Treat this contract/corpus checkpoint as closed.

The next recommended functional phase is not another documentation cleanup. It
should be one explicit capability slice, chosen before implementation:

1. `supersede_evidence` UX and contract semantics, if the platform needs a
   first-class replacement flow distinct from revert-with-replacement.
2. vector/RAG lifecycle filtering, if future Project Knowledge retrieval needs
   ranking over active evidence only.
3. richer runtime enforcement validation, if downstream consumers need proof
   that materialized decisions are enforced beyond the current Project
   Knowledge/browser checks.

Any of these should start with a short architecture plan and impact map before
new public contract or UI work.
