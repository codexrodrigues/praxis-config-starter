# Domain Rule Change Workspaces v1

Policy Studio authors a governed decision through a change workspace. The workspace is not a
second rule definition and is never executable: it is mutable authoring state anchored to the
canonical fingerprint of one persisted `domain_rule_definition`.

## Ownership and adherence inventory

- Definition lifecycle, approvals, publication and immutable snapshots: `ja-suportado-so-ux`.
- Structural readiness currently named simulation: `ja-suportado-mal-nomeado-ou-mal-materializado`.
- Collaborative draft with base fingerprint and optimistic concurrency:
  `ja-suportado-mal-nomeado-ou-mal-materializado`; o contrato está implementado,
  mas discovery/actions/blockers do workspace ainda não são server-owned.
- Reusable facts, expected five-state outcome and immutable Test Run:
  `suportado-parcialmente`; `expectedOutput` é persistido, mas ainda não participa
  do resultado/gate do host de referência.
- Candidate/active evaluation: `suportado-parcialmente` por adapter host-owned; o
  Config armazena evidência redigida e não executa regras.
- Candidate/legacy evidence and comparison: `lacuna-real-de-contrato`; requer
  provenance, status HTTP/campo, before/after, effects, no-mutation e cleanup.
- Output, reason codes and planned effects as acceptance gates:
  `lacuna-real-de-contrato` no Test Run atual.

The Config Starter owns workspace and scenario persistence. The Rules Engine owns deterministic
evaluation semantics. The host owns fact resolution, executable registries and sandbox execution.
Policy Studio is the experience plane over these contracts.

## HTTP contract

All scope is resolved from the authenticated server principal. Tenant and environment headers are
hints only in explicitly configured local mode.

- `GET /api/praxis/config/domain-rules/definitions/{definitionId}` returns one fully scoped
  Definition, including its condition. Consumers must use this detail read instead of assuming the
  list projection contains every authoring field.
- `POST /api/praxis/config/domain-rules/workspaces` creates an `OPEN` workspace anchored to
  `baseDefinitionId`; it copies the base condition and parameters and records the canonical
  definition SHA-256.
- `GET /api/praxis/config/domain-rules/workspaces` lists only the effective tenant/environment.
- `GET /api/praxis/config/domain-rules/workspaces/{id}` supports private revalidation with ETag.
- `PUT /api/praxis/config/domain-rules/workspaces/{id}/draft` requires a strong `If-Match`, verifies
  that the base fingerprint has not changed, increments `revision` and rotates the ETag.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/scenarios` persists reusable typed facts and
  an expected `ALLOW`, `DENY`, `NOT_APPLICABLE`, `INCONCLUSIVE` or `TECHNICAL_ERROR` result.
- `GET /api/praxis/config/domain-rules/workspaces/{id}/scenarios` returns scenarios in stable key order.
- `PUT /api/praxis/config/domain-rules/workspaces/{id}/scenarios/{scenarioId}` requires the current
  strong scenario ETag.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/test-runs` records immutable host-produced
  evidence only if workspace revision, base fingerprint and scenario identities still match.
- `GET /api/praxis/config/domain-rules/workspaces/{id}/test-runs` lists safe evidence without facts
  or executable snapshot payloads.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/submit` requires the current strong ETag and
  a latest Test Run for the exact workspace revision/fingerprint. The run must cover exactly every
  active scenario; every candidate must match its expected decision and no result may be
  inconclusive or technical.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/reviews` requires
  `RULE_DEFINITION_APPROVER`, a current strong ETag and a reviewer different from the workspace
  author. It appends `APPROVE` or `REJECT` evidence bound to the exact submitted revision and base
  fingerprint, then closes the workspace as `APPROVED` or `REJECTED`.
- `GET /api/praxis/config/domain-rules/workspaces/{id}/reviews` exposes the safe append-only review
  history to readers. A workspace approval does not publish, activate or mutate a rule definition;
  promotion into the existing Definition lifecycle remains a separate governed operation.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/promote` requires
  `RULE_DEFINITION_AUTHOR` and the current strong ETag. It locks the canonical rule-version stream,
  creates the next `proposed` Definition version as the original workspace author, reuses the
  persisted independent reviewer to perform the existing Definition approval transition, and
  closes the workspace as `PROMOTED`. The transaction does not publish, materialize or activate.
  A retry after successful promotion is idempotent and returns the existing promoted definition id.

Reads require `RULE_DEFINITION_READER`; draft, scenario, test-run and submission mutations require
`RULE_DEFINITION_AUTHOR`; reviews require `RULE_DEFINITION_APPROVER`. A cross-scope identifier is
returned as not found. Wildcard `If-Match` is rejected for mutations so a client cannot bypass
reconciliation.

## Sandbox runs implementados e limites restantes

A sandbox run must freeze facts, `nowUtc`, timezone and actor context once, then evaluate:

1. the workspace candidate through a bounded host compiler;
2. the active immutable snapshot through the current last-known-good plan;
3. an independent legacy oracle only where a migration host supplies one.

The implemented run ledger persists immutable per-scenario decisions, exact snapshot/workspace
fingerprints, plan/facts digests and comparison classification. It never stores raw facts or
snapshot content, executes effects or promotes a candidate. Config receives safe host evidence,
but it does not receive Java executors or become the evaluation runtime.

The current record proves only the canonical five-state decision against the
scenario expectation. Although a scenario stores `expectedOutput`, the Test Run
does not carry `candidateOutput`, `outputMatchesExpected`, expected reason codes
or expected planned effects. A corporate submission gate must add those fields at
the canonical Config contract and require the host to derive them from the same
frozen evaluation. A browser-only comparison is not acceptable.

Workspace responses also do not yet expose `availableActions` and `blockers` for
the authenticated principal. Clients must not infer review, promotion, submission
or publication authority from `status`; this remains a pre-production contract
gap shared with the public `@praxisui/core` client.
