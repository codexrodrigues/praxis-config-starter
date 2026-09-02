# OpenAI cost attribution and live gates

This runbook separates paid provider traffic by operational owner. It does not introduce a second
AI contract: every runtime still supplies its environment-specific credential through the canonical
`PRAXIS_AI_OPENAI_API_KEY` variable.

## Required OpenAI projects

Create one OpenAI project and one key per environment. Never reuse a key between rows.

| OpenAI project | Runtime owner | `PRAXIS_AI_USAGE_ORIGIN_CLASS` | Suggested monthly limit |
| --- | --- | --- | --- |
| `praxis-ci-live-gates` | GitHub release/live-provider gates | `ci-live-gate` | lowest; enough for the release gate |
| `praxis-local-development` | developer machines | `local-development` | low, with alerts before the hard limit |
| `praxis-production-landing` | public quickstart used by praxisui.dev | `production-landing` | explicit public budget |

The limit values belong to the OpenAI project configuration. Keep them outside source control and
review them after observing one normal release cycle.

## Gate policy

- The `Agentic Authoring HTTP Smoke` workflow is deterministic by default.
- `paid_gate_lane` is the single canonical paid-provider selector. Its default is `none`; the other
  mutually exclusive values are `http-sse`, `page-builder` and `llm-compliance`. Independent paid
  toggles are intentionally not supported.
- Every non-`none` lane must pass the protected GitHub Environment `ai-paid-gates`. Dispatching a
  workflow selects a candidate lane but does not authorize provider use; the configured reviewer must
  approve the deployment before the main job starts or any repository secret becomes available.
- A paid HTTP/SSE smoke requires `paid_gate_lane=http-sse`. Local execution additionally requires
  `-ConfirmPaidProviderRun`; deterministic local validation uses `-DomainRuleLifecycleOnly`.
- The paid gate runs one canonical `governed-authoring-apply` journey. That journey already resolves
  intent, plans and compiles the materialization, streams the result, persists it with lineage,
  reads it back and cleans it up. The older isolated intent/plan/compile/preview/provider probes remain
  available as focal diagnostics, but the release gate does not repeat them because independent LLM
  classifications add cost and nondeterministic false negatives without strengthening the journey.
- The apply proof executes one authoring turn by default. If that turn is blocked for governed review,
  it may execute exactly one continuation in the same thread, and only from the unique backend-issued
  `governed-review-revise` quick reply whose structured semantic decision matches the expected
  canonical resource. Labels and prompts never authorize the continuation. A missing, ambiguous or
  still-blocked continuation fails closed after at most two turns.
- `paid_gate_lane=page-builder` runs the canonical `smoke` profile with one live authoring journey
  and zero automatic retries. Focused and full profiles also inherit zero retries; rerunning after a
  failure is a separate, deliberate cost decision.
- `paid_gate_lane=llm-compliance` runs only the external compliance-policy shadow as the paid lane.
- Export or publication failures after a successful paid journey must be diagnosed from sanitized
  artifacts. They do not justify repeating the provider call.
- A failure in a paid prerequisite, including Domain Catalog embedding publication or reconciliation,
  must be classified and repaired before another paid run. A run that never reached the authoring
  journey is not functional evidence and does not justify a blind provider retry.
- A newer run on the same ref cancels the older in-progress run, avoiding duplicated provider calls.
- GitHub Actions is a release/final gate. Development validation remains local and focal.

The workflow summary records whether the execution is `deterministic` or `external-provider`, the
exclusive lane and its bounded journey budget. The
HTTP smoke receipt also records the apply turn count and whether the governed review continuation was
used, including the backend-issued reply and decision identifiers.

## Single-call embeddings quota diagnostic

Use `OpenAI Single Embedding Quota Probe` only when a non-generative `GET /models` key probe has
already succeeded but OpenAI support or an observed failure requires the effective embeddings quota
to be identified. This workflow is an operational diagnostic, not a release gate and not functional
evidence for agentic authoring.

The operator must type `ONE_PAID_EMBEDDING_REQUEST`, then approve the protected
`ai-paid-gates` environment. One run executes exactly one `POST /v1/embeddings` request with
`text-embedding-3-large`, a short fixed input, 768 dimensions, no redirect and zero retries. The
workflow uses only `PRAXIS_AI_OPENAI_API_KEY`; it does not start Quickstart, publish a Domain Catalog
or invoke an authoring turn.

The log is intentionally bounded to HTTP status, model, dimensions, a safe client request id, the
provider `x-request-id`, token usage on success, or `error.type`, `error.code` and `error.param` on
failure. The raw response, provider message, embedding vector, organization/project identifiers and
credential are never uploaded or printed. Forward only those sanitized fields to provider support.
Do not rerun the workflow merely because a quota error was observed: the first result is the evidence
the diagnostic exists to collect.

## Provider metadata

OpenAI Responses requests carry only bounded, non-content metadata:

- `praxis_origin_class`: deployment-owned class such as `ci-live-gate`;
- `praxis_environment`: semantic environment already present in `AiCallConfig`;
- `praxis_execution_profile`: canonical execution profile, when available;
- `praxis_call_phase` and `praxis_call_attempt`: sanitized invocation trace fields, when available;
- `praxis_response_mode`: `text` or `structured-json`.

Prompts, responses, credentials, tenant IDs and user IDs are excluded. `store=false` remains enabled.

## Provider failure correlation

When an OpenAI SDK failure is normalized as an `AiProviderCallException`, the Config Starter retains
only the provider-owned `x-request-id` needed for operational correlation. The value is accepted
only when it is a single safe identifier of at most 128 characters; raw headers and malformed values
are discarded.

The sanitized identifier is written to the API metadata indexing failure log together with the
provider, normalized failure kind and HTTP status. It is not added to prompts, responses, metrics,
canonical status messages or provider request metadata. Use it when escalating a failed live gate
to OpenAI support; never copy the API key or raw provider response into an issue or artifact.

## Public host protection

The quickstart applies a dedicated per-client rate limit before the broader config limit for
`/api/praxis/config/ai/**`:

- `APP_RATE_LIMIT_AI_LIMIT` (default `30`);
- `APP_RATE_LIMIT_AI_WINDOW_MS` (default `60000`).

For the public deployment, tune this limit together with the `praxis-production-landing` OpenAI
project budget. The in-memory limiter is the reference-host baseline; a production gateway/WAF is
still the durable enforcement point.

## Rotation checklist

1. Create the three projects and keys in OpenAI.
2. Configure the CI key only in the GitHub secret `PRAXIS_AI_OPENAI_API_KEY`.
3. Configure the local key only in ignored local env files.
4. Configure the production key only in the public quickstart deployment.
5. Set the matching `PRAXIS_AI_USAGE_ORIGIN_CLASS` in each environment.
6. Configure project budgets and alerts.
7. Verify one request per environment in OpenAI logs using the metadata above.
8. Revoke the two previously shared keys only after all three environments pass their smoke.

Key creation, budget changes, deployment-secret changes and key revocation are external operational
actions and must not be performed by source-control automation.
