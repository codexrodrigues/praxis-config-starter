# Local Live E2E Without GitHub Actions

Use this runbook when GitHub Actions quota is constrained. These checks call real local services and real LLM providers, but do not dispatch any GitHub workflow.

## Last Validated

Validated locally on 2026-04-23 with:

- `praxis-config-starter` local commit containing this runbook (`git rev-parse --short HEAD`);
- `praxis-ui-angular` commit `03e5b9ce`;
- `praxis-api-quickstart` commit `85a2d5c`;
- `praxis-api-quickstart` jar `target/praxis-api-quickstart-2.0.0-rc.9.jar`, rebuilt locally with `-Dpraxis.config.version=0.1.0-rc.5` after installing the current local starter;
- Java `21.0.10`;
- remote PostgreSQL via quickstart configuration;
- real OpenAI provider `gpt-5.4-mini`;
- real Gemini provider `gemini-2.5-flash`;
- GitHub Actions not used.

Observed backend startup:

- Flyway validated `20` migrations;
- current schema version was `20`;
- schema was up to date;
- no migration was applied during the local E2E run.

Validated results:

- Agentic HTTP/SSE smoke passed with `planValid=true`, `compileValid=true`, `previewValid=true`, `applyPersisted=true`, `applyCleanupDeleted=true`, `streamTerminalSeen=true`, `streamReplayChecked=true`, `streamEventCount=6`.
- Domain Catalog v2 passed with schema `praxis.domain-catalog/v0.2`, `nodeCount=2`, `aliasCount=4`, `governanceCount=3`, `relationshipCount=10`, `semanticNodeCount=2`.
- LLM compliance passed with OpenAI and Gemini; both reported `providerStatus=ok`, `mayUseDeniedContent=false`, CPF governance guidance, and salary exclusion due to confidence.
- Browser Playwright passed `3/3` against Angular, backend, remote DB and real LLM.
- Domain Federation v0.1 starter tests passed locally: `18` tests, `0` failures across controller, validator, dry-run ingest, query and retrieval policy.
- Domain Federation v0.1 live smoke passed against the locally rebuilt quickstart jar with `schemaVersion=praxis.domain-federation/v0.1`, `dryRunValid=true`, `ingestDryRunValid=true`, `contextFederated=true`, `contextItemCount=2`, `relationshipCount=2`.
- The original quickstart jar built before this starter change returned `404`; package quickstart with a starter build containing `DomainFederationController` before running the live federation smoke.

## Prerequisites

- `java` 21
- `mvn`
- `jq`
- `python3`
- `curl`
- `uuidgen`
- `node`, `npm` and Playwright dependencies for browser E2E
- `praxis-api-quickstart` packaged under `../praxis-api-quickstart/target`
- local provider secrets in `.env.openai.local.sh`

The local env loader tolerates BOM/CRLF in `.env.openai.local.sh` and never modifies the file.

## Backend

Terminal 1:

```bash
cd praxis-config-starter
PROVIDER=openai ./tools/local-e2e/start-quickstart-local-e2e.sh
```

The backend starts on `http://localhost:8088` with:

- remote database configuration from quickstart;
- `EMBEDDING_PROVIDER=mock`;
- `PRAXIS_AI_STREAM_AUTH_MODE=signed-url-token`;
- local tenant/user/env defaults for browser EventSource.

Before running E2E, confirm startup logs say Flyway validated migrations and did not unexpectedly apply new migrations.

## Backend HTTP/SSE Smoke

Terminal 2:

```bash
cd praxis-config-starter
PROVIDER=openai ./tools/local-e2e/run-agentic-http-sse-smoke-local.sh
```

This validates:

- health;
- minimal form plan;
- compiled form patch;
- page preview;
- page apply/get/delete cleanup;
- SSE start/probe/read/replay/cancel.

Artifacts are written to `artifacts/local-e2e/`.

## Domain Catalog v2

```bash
cd praxis-config-starter
./tools/local-e2e/run-domain-catalog-v2-local.sh
```

This validates projected `praxis.domain-catalog/v0.2`, ingest, node/alias/governance context, semantic payload and latest relationships.

## Domain Federation v0.1

```bash
cd praxis-config-starter
./tools/local-e2e/run-domain-federation-v01-local.sh
```

This validates the read-only federation contract with:

- Domain Catalog v2 bootstrap for `human-resources.folhas-pagamento`;
- `POST /api/praxis/config/domain-federation/dry-run`;
- `POST /api/praxis/config/domain-federation/ingest?dryRun=true`;
- `GET /api/praxis/config/domain-federation/context`;
- explicit `domain_source`, `domain_context`, `domain_context_relationship`, `domain_contract` and `domain_resolution` payloads.

The runner does not persist federation records and does not execute rules.

It requires the running quickstart jar to include `DomainFederationController`.
If the endpoint probe returns `404`, package `praxis-api-quickstart` with a
`praxis-config-starter` build that contains the federation controller before
rerunning this smoke.

## Compliance Shadow

```bash
cd praxis-config-starter
PROVIDER=openai FAIL_ON_PROVIDER_UNAVAILABLE=true ./tools/local-e2e/run-llm-compliance-local.sh
PROVIDER=gemini ./tools/local-e2e/run-llm-compliance-local.sh
```

For Gemini, leave `FAIL_ON_PROVIDER_UNAVAILABLE=false` unless the goal is to fail hard on quota or transient provider outage.

## Browser Full E2E

Terminal 3:

```bash
cd praxis-ui-angular
PAX_PROXY_TARGET=http://localhost:8088 PLAYWRIGHT_BASE_URL=http://localhost:4003 npm start
```

Terminal 4:

```bash
cd praxis-ui-angular
./tools/local-e2e/run-page-builder-agentic-full-local.sh
```

The browser E2E requires the backend stream auth mode to be `signed-url-token`; plain header-only stream auth is not enough for browser `EventSource`.

## Cleanup

Stop the backend and Angular terminals with `Ctrl-C`, or kill ports:

```bash
for port in 4003 8088; do
  pids="$(lsof -tiTCP:$port -sTCP:LISTEN || true)"
  [ -n "$pids" ] && kill $pids
done
```

## Pre-Publish Safety Checks

Before pushing local E2E changes, verify that committed files do not include live provider secrets:

```bash
cd praxis-config-starter
git diff --name-only origin/main..HEAD \
  | xargs git grep -n -E '(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|apiKey[[:space:]]*[:=][[:space:]]*["'\"'"][^"'\"'"]{8,}|OPENAI_API_KEY[[:space:]]*=[[:space:]]*[^$[:space:]]|GEMINI_API_KEY[[:space:]]*=[[:space:]]*[^$[:space:]])' HEAD -- \
  || true

git check-ignore -v .env.openai.local.sh artifacts/local-e2e/example/summary.json

cd ../praxis-ui-angular
git diff --name-only origin/main..HEAD \
  | xargs git grep -n -E '(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|apiKey[[:space:]]*[:=][[:space:]]*["'\"'"][^"'\"'"]{8,}|OPENAI_API_KEY[[:space:]]*=[[:space:]]*[^$[:space:]]|GEMINI_API_KEY[[:space:]]*=[[:space:]]*[^$[:space:]])' HEAD -- \
  || true

git check-ignore -v tools/e2e/playwright/playwright-agentic-report/index.html test-results/example.txt
```

For the 2026-04-23 validation, the restricted committed-file scan returned no findings for either repository, and ignore checks confirmed local secrets, local E2E artifacts, Playwright reports and `test-results` are ignored.

## Publication Checklist

When GitHub Actions quota is available again, publish in this order:

1. Push `praxis-config-starter`.
2. Push `praxis-api-quickstart`.
3. Push `praxis-ui-angular`.

Recommended pre-push commands:

```bash
cd praxis-config-starter
git status --branch --short
git log --oneline origin/main..HEAD

cd ../praxis-api-quickstart
git status --branch --short
git log --oneline origin/main..HEAD

cd ../praxis-ui-angular
git status --branch --short
git log --oneline origin/main..HEAD
```

Keep the live E2E evidence local. Do not commit `.env.openai.local.sh`, `artifacts/`, Playwright reports or `test-results/`.
