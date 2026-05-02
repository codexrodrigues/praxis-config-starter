# Runtime Enforcement Consumer Release Checklist

Date: 2026-05-02

Scope: operational checklist for closing the runtime enforcement consumer
checkpoint without spending GitHub Actions during normal iteration.

## Decision

Use this checklist only when the owner explicitly authorizes a named release cut
or hosted smoke.

Do not use it to justify Maven Central or npm publication by itself. The current
state is local-first beta evidence, not a publication request.

Readiness evidence:

- [Release Readiness - Runtime Enforcement Consumer Checkpoint](release-readiness-2026-05-02-runtime-enforcement-consumer.md)

Implementation source:

- [Runtime Enforcement Consumer Proof Plan](agentic-authoring/implementation/13-runtime-enforcement-consumer-proof-plan.md)

## Current Phase State

The phase is complete as local beta evidence:

- `form_config` materialization is consumed by `praxis-dynamic-form` in browser.
- `backend_validation` blocks a mutable procurement command.
- `workflow_action` blocks an existing payroll action.
- `approval_policy` blocks an existing payroll-events bulk action until
  governed approval exists.
- The canonical lifecycle is still `domain-rules` definition, publication and
  applied materialization.
- UI, controllers and runtime services consume the projections and do not become
  the source of the decision.

## Stop Conditions

Do not publish or trigger Actions if any of these are true:

- the owner has not authorized a named release cut;
- no downstream consumer needs a public Maven or npm coordinate;
- the only motivation is documentation bookkeeping;
- quickstart is not freshly packaged with the intended starter version;
- `http://localhost:8088` or another reused local host is running a stale jar;
- local browser proof has not been rerun after relevant UI/runtime changes;
- any focused runtime lane fails locally;
- closing the phase would require repeated Actions reruns.

## Local-First Gates

Run these locally before any remote gate.

### Repository Preflight

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform

git -C praxis-config-starter status --short --branch
git -C praxis-api-quickstart status --short --branch
git -C praxis-ui-angular status --short --branch
```

Expected:

- all relevant repositories are clean or contain only intentional release notes;
- no unrelated work is included in the release branch.

### Quickstart Package Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

./mvnw -q -DskipTests package
jar tf target/praxis-api-quickstart-2.0.0-rc.9.jar | rg 'praxis-config-starter'
```

Expected:

- package succeeds;
- embedded starter version matches the intended release candidate.

### Local Host Startup

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

SERVER_PORT=8091 \
SPRING_PROFILES_ACTIVE=local \
CORS_ALLOWED_ORIGINS=http://localhost:4003 \
APP_SECURITY_CSRF_DISABLE=true \
APP_SECURITY_CONFIG_ORIGIN_RESTRICTION_ALLOWED_ORIGINS=http://localhost:4003 \
java -jar target/praxis-api-quickstart-2.0.0-rc.9.jar
```

Expected:

- host starts on `http://localhost:8091`;
- Neon-backed datasource is used;
- `/api/praxis/config/**` accepts `Origin: http://localhost:4003`;
- port `8091` is stopped and released after the proof.

### Dynamic Form Browser Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-ui-angular

BACKEND_URL=http://localhost:8088 \
./tools/local-e2e/run-dynamic-form-domain-rules-runtime-local.sh
```

Expected:

- backend verifier returns `shared-rule-runtime-ready`;
- Playwright reports `1 passed`;
- the browser consumes the real applied `form_config` materialization.

If the active backend is the isolated `8091` host, set `BACKEND_URL` to that
origin or use the lane's documented backend override.

### Focused Backend Validation Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

BACKEND_URL=http://localhost:8091 \
ORIGIN=http://localhost:4003 \
scripts/verify-domain-rules-backend-validation-runtime.sh
```

Expected:

- final marker: `backend-validation-runtime-ready`;
- `rejectedWith: 409`.

### Focused Workflow Action Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

BACKEND_URL=http://localhost:8091 \
ORIGIN=http://localhost:4003 \
scripts/verify-domain-rules-workflow-action-runtime.sh
```

Expected:

- final marker: `workflow-action-runtime-ready`;
- `rejectedWith: 409`;
- materialization includes derived `sourceHash`.

### Focused Approval Policy Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-api-quickstart

BACKEND_URL=http://localhost:8091 \
ORIGIN=http://localhost:4003 \
scripts/verify-domain-rules-approval-policy-runtime.sh
```

Expected:

- final marker: `approval-policy-runtime-ready`;
- `rejectedWith: 409`;
- materialization includes derived `sourceHash`.

### Documentation Gate

```bash
cd /Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-config-starter

git diff --check
```

Expected:

- whitespace gate passes;
- readiness links point to files that exist;
- release notes still say no publication is authorized unless the owner has
  explicitly authorized a named cut.

## Remote Gate Budget

Default budget during normal iteration:

- GitHub Actions: `0`
- Maven Central publication: `0`
- npm publication: `0`

If a release is explicitly authorized:

- run all local gates first;
- use one remote gate as the phase-closing verification;
- use one publication workflow only after the remote gate passes;
- do not rerun Actions for diagnostics that can be reproduced locally.

## Publication Boundaries

`praxis-config-starter`:

- owns the canonical `domain-rules` semantics and publication/materialization
  contract;
- is the only plausible Maven candidate if a named backend consumer requires the
  checkpoint from a public coordinate.

`praxis-api-quickstart`:

- proves runtime consumption over HTTP and Neon;
- should not redefine the semantic contract;
- does not require Maven publication for this checkpoint.

`praxis-ui-angular`:

- proves browser/runtime consumption of `form_config`;
- should not publish npm packages for this checkpoint unless a separate UI
  release decision is made.

## Release-Ready Definition

The phase can be considered release-ready only when:

- all local gates above pass freshly;
- the active quickstart jar embeds the intended starter version;
- the owner names the release version and authorizes publication;
- release notes include the local evidence and the remote gate id;
- no new materialization family is introduced in the same cut;
- all related repositories are clean after commits.

Until then, keep the checkpoint as local-first beta evidence.
