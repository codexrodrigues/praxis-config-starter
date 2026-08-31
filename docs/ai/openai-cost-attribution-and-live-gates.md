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
- A paid HTTP/SSE smoke requires `domain_rule_lifecycle_only=false`.
- The apply proof executes one authoring turn by default. If that turn is blocked for governed review,
  it may execute exactly one continuation in the same thread, and only from the unique backend-issued
  `governed-review-revise` quick reply whose structured semantic decision matches the expected
  canonical resource. Labels and prompts never authorize the continuation. A missing, ambiguous or
  still-blocked continuation fails closed after at most two turns.
- Enabling the Page Builder full E2E or the LLM compliance shadow is also an explicit paid-provider
  choice.
- A newer run on the same ref cancels the older in-progress run, avoiding duplicated provider calls.
- GitHub Actions is a release/final gate. Development validation remains local and focal.

The workflow summary records whether the execution is `deterministic` or `external-provider`. The
HTTP smoke receipt also records the apply turn count and whether the governed review continuation was
used, including the backend-issued reply and decision identifiers.

## Provider metadata

OpenAI Responses requests carry only bounded, non-content metadata:

- `praxis_origin_class`: deployment-owned class such as `ci-live-gate`;
- `praxis_environment`: semantic environment already present in `AiCallConfig`;
- `praxis_execution_profile`: canonical execution profile, when available;
- `praxis_call_phase` and `praxis_call_attempt`: sanitized invocation trace fields, when available;
- `praxis_response_mode`: `text` or `structured-json`.

Prompts, responses, credentials, tenant IDs and user IDs are excluded. `store=false` remains enabled.

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
