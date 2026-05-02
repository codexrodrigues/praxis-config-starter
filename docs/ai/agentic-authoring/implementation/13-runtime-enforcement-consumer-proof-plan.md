# Runtime Enforcement Consumer Proof

Status: local checkpoint completed on 2026-05-02.

## Purpose

Prove that a governed semantic decision owned by `praxis-config-starter` can be
materialized into a runtime consumer without making Page Builder, Dynamic Form
or browser state the source of truth.

This slice follows the central Praxis premise: semantic decisions are authored
and governed canonically by the platform; UI runtimes consume derived
materializations and expose enforcement evidence.

## Canonical Boundary

- Source of truth: `/api/praxis/config/domain-rules/**` in
  `praxis-config-starter`.
- Operational host: `praxis-api-quickstart`.
- Runtime consumer: `praxis-ui-angular` / `@praxisui/dynamic-form`.
- Runtime target: `/funcionarios-form-demo` with `domainRules.enabled=true`.

## Local Proof

Backend canonical proof:

```bash
SMOKE_RUN_ID=runtime-browser-$(date -u +%Y%m%d%H%M%S) \
BACKEND_URL=http://localhost:8088 \
ORIGIN=http://localhost:4003 \
REQUIRE_PUBLICATION=false \
REQUIRE_BACKEND_VALIDATION=false \
REQUIRE_WORKFLOW_ACTION=false \
REQUIRE_APPROVAL_POLICY=false \
REQUIRE_TIMELINE=auto \
scripts/verify-domain-rules-runtime.sh
```

Browser consumer proof:

```bash
PAX_PROXY_TARGET=http://localhost:8088 \
PLAYWRIGHT_BASE_URL=http://localhost:4003 \
node scripts/run-playwright-with-dev-host.js \
  --port 4003 \
  --path /funcionarios-form-demo \
  --spec projects/praxis-dynamic-form/test-dev/e2e/funcionarios-form-demo-domain-rules.playwright.spec.ts \
  --no-start
```

Versioned UI runner:

```bash
BACKEND_URL=http://localhost:8088 \
./tools/local-e2e/run-dynamic-form-domain-rules-runtime-local.sh
```

## Evidence

- `verify-domain-rules-runtime.sh` created a governed `visual_guidance`
  definition for `human-resources.funcionarios`, simulated it, approved it,
  activated it and applied a `form_config` materialization to
  `funcionarios-form-demo`.
- The backend returned `status: "shared-rule-runtime-ready"` with
  `definitionStatus: "active"`, `materializationStatus: "applied"` and one
  filtered materialization match.
- The browser E2E passed: `1 passed`.
- The versioned runner
  `tools/local-e2e/run-dynamic-form-domain-rules-runtime-local.sh` also passed
  end-to-end: backend verifier completed with `shared-rule-runtime-ready`, then
  Playwright reported `1 passed`.
- The browser loaded
  `/api/praxis/config/domain-rules/materializations` with
  `targetArtifactKey=funcionarios-form-demo` and `status=applied`.
- `praxis-dynamic-form` rendered the derived LGPD/GDPR guidance after the CPF
  field was filled.

## Acceptance Criteria

- The runtime consumer reads applied materializations from the canonical
  `domain-rules` API.
- The consumed materialization remains a derived projection, not a copied
  business rule inside `FormConfig`.
- The browser proof depends on a real API response, not a frontend mock.
- GitHub Actions, npm publication and Maven Central publication are not used
  during local implementation.

## Recommended Next Action

Extend enforcement evidence from visual guidance into one non-UI runtime
surface, preferably `backend_validation` or `workflow_action`, using the same
local-first discipline. The goal is to prove an applied semantic decision blocks
or permits an actual runtime command, while keeping the rule definition and
materialization lifecycle canonical in `domain-rules`.
