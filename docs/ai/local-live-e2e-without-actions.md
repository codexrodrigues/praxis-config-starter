# Local Live E2E Without GitHub Actions

Use this runbook when GitHub Actions quota is constrained. These checks call real local services and real LLM providers, but do not dispatch any GitHub workflow.

## Last Validated

Validated locally on 2026-04-23 with:

- `praxis-config-starter` local commit containing this runbook (`git rev-parse --short HEAD`);
- `praxis-config-starter` published dependency `0.1.0-rc.24`;
- `praxis-ui-angular` commit `b17b74a8`;
- `praxis-api-quickstart` commit `57bfc85`;
- `praxis-api-quickstart` jar `target/praxis-api-quickstart-2.0.0-rc.9.jar`, rebuilt locally against the published `praxis-config-starter:0.1.0-rc.24` dependency with no local starter override;
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
- Domain Federation v0.1 starter focal tests passed locally: `25` tests, `0` failures across controller, query, prompt-context and retrieval policy.
- Domain Federation v0.1 live smoke passed against the locally rebuilt quickstart jar with `schemaVersion=praxis.domain-federation/v0.1`, `dryRunValid=true`, candidate persistence, release audit, persisted validation and activation.
- Domain Federation v0.1 live smoke passed again against the quickstart jar built with the published `praxis-config-starter:0.1.0-rc.24` dependency. The nested starter jar contains `DomainFederationController`, `DomainFederationContractValidator`, `DomainFederationQueryService` and `DomainFederationIngestDryRunService`.
- Domain Federation v0.1 persistent live smoke passed against a quickstart jar packaged with the local starter `0.1.0-rc.5` override and `PRAXIS_DOMAIN_FEDERATION_PERSISTENCE_ENABLED=true`. The persistent ingest returned `dryRun=false`, `releaseStatus=candidate`, `releaseListed=true`, `validationReportValid=true`, `persistedSources=1`, `persistedContexts=2`, `persistedContextRelationships=1`, `persistedContracts=1` and `persistedResolutions=1`. The same smoke now activates the candidate release, verifies `/domain-federation/context` returns `sourceMode=persisted_federation`, and proves that a persisted `contract.visibility=deny_for_llm` relationship is redacted from the HTTP response with `deniedItemCount=1`.
- The original quickstart jar built before the starter federation controller returned `404`.

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

This validates the persisted federation contract with:

- `POST /api/praxis/config/domain-federation/ingest?dryRun=true`;
- `POST /api/praxis/config/domain-federation/ingest?dryRun=false`;
- `GET /api/praxis/config/domain-federation/releases`;
- `GET /api/praxis/config/domain-federation/releases/{releaseKey}/validation`;
- `POST /api/praxis/config/domain-federation/releases/{releaseKey}/activate`;
- explicit `domain_source`, `domain_context`, `domain_context_relationship`, `domain_contract` and `domain_resolution` payloads.

The wrapper delegates to `praxis-api-quickstart/scripts/verify-domain-federation-runtime.sh`, because the quickstart is the operational reference host for published starter contracts.

The runner persists a candidate release and activates it in the selected
`TENANT_ID` + `ENVIRONMENT` scope. It does not execute rules.

It requires the running quickstart jar to include `DomainFederationController`,
V21 to be applied in the config database, and
`praxis.domain-federation.persistence.enabled=true`.
The local backend starter script enables this flag by default.

If the runtime returns `404`, confirm the quickstart jar was packaged against
a starter revision that contains the federation HTTP surface before rerunning
this smoke.

## Domain Federation v0.1 Persistent Smoke

Use this runner when the change must prove the durable read model, release list
and persisted validation report through the quickstart host:

```powershell
cd praxis-config-starter

$env:JAVA_HOME='D:\Developer\JAVA\openjdk-21_windows-x64_bin\jdk-21'
$env:Path="$env:JAVA_HOME\bin;D:\Developer\maven\apache-maven-3.9.6\bin;$env:Path"
mvn.cmd clean install -DskipTests

cd ..\praxis-api-quickstart
mvn.cmd clean package -DskipTests '-Dpraxis.config.version=0.1.0-rc.5'

cd ..\praxis-config-starter
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Invoke-QuickstartDomainFederationPersistentHttpSmoke.ps1 -QuickstartRoot ..\praxis-api-quickstart
```

This validates:

- quickstart startup on `http://localhost:8088`;
- remote database configuration from quickstart;
- Domain Catalog v2 bootstrap for `human-resources.folhas-pagamento`;
- `POST /api/praxis/config/domain-federation/dry-run`;
- `POST /api/praxis/config/domain-federation/ingest?dryRun=false`;
- persisted release lookup through `GET /api/praxis/config/domain-federation/releases?status=candidate`;
- persisted validation report lookup through `GET /api/praxis/config/domain-federation/releases/{releaseKey}/validation`;
- explicit activation through `POST /api/praxis/config/domain-federation/releases/{releaseKey}/activate`;
- active persisted context retrieval through `GET /api/praxis/config/domain-federation/context`;
- persisted redaction of `contract.visibility=deny_for_llm` during LLM-facing context retrieval;
- persisted counts for `domain_source`, `domain_context`, `domain_context_relationship`, `domain_contract` and `domain_resolution`.

The starter version override must match the local starter version being tested.
Do not edit the quickstart `pom.xml` just to validate an unpublished starter;
use the Maven property override and keep the Maven Central publication step
decoupled from local platform validation.

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
