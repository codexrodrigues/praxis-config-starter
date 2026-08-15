# Domain Rule Change Workspaces v1

Policy Studio authors a governed decision through a change workspace. The workspace is not a
second rule definition and is never executable: it is mutable authoring state anchored to the
canonical fingerprint of one persisted `domain_rule_definition`.

## Ownership and adherence inventory

- Definition lifecycle, approvals, publication and immutable snapshots: `ja-suportado-so-ux`.
- Structural readiness currently named simulation: `ja-suportado-mal-nomeado-ou-mal-materializado`.
- Collaborative draft with base fingerprint and optimistic concurrency:
  `ja-suportado-mal-nomeado-ou-mal-materializado`; o contrato, suas ações e
  blockers por principal estão implementados, mas a seleção canônica entre
  workspaces concorrentes ainda não é publicada.
- Reusable facts, expected decision, output, reason codes, planned effect intents
  and immutable Test Run: `ja-suportado-mal-nomeado-ou-mal-materializado`; o
  contrato e o gate estão implementados e aguardam prova HTTP/Neon do corte.
- Candidate/active evaluation: `suportado-parcialmente` por adapter host-owned; o
  Config armazena evidência redigida e não executa regras.
- Candidate/legacy evidence and comparison: `suportado-parcialmente`. Migrations `V57` and `V58`
  persist sanitized baseline provenance, an independent per-scenario baseline result lane and
  optional operational CREATE/UPDATE evidence. A real host still has to collect and register that
  evidence; Config never calls a legacy system or infers eligibility.
- Idempotent Test Run transport and stage-specific operational gates: `lacuna-real-de-contrato`
  closed in `V58`. The lightweight `praxis-config-contracts` artifact owns the host-neutral records;
  Config owns persistence, request hashing and policy evaluation. Hosts do not depend on the
  starter's JPA/autoconfiguration surface.
- Execution of effects remains host-owned and is never performed by a Test Run.

The Config Starter owns workspace and scenario persistence. The Rules Engine owns deterministic
evaluation semantics. The host owns fact resolution, executable registries and sandbox execution.
Policy Studio is the experience plane over these contracts.

The stable semantic identity of this resource is
`praxis.config.domain-rule-change-workspaces`, published by
`DomainRuleChangeWorkspaceContract.RESOURCE_KEY`. Metadata producers that protect a workspace
version must reference this identity instead of deriving it from the HTTP path. The canonical path
is separately published as `DomainRuleChangeWorkspaceContract.RESOURCE_PATH`.

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
- `GET /api/praxis/config/domain-rules/workspaces/{id}/capabilities` publishes the authenticated
  principal's `availableActions` plus stable, action-scoped business blockers. `SUBMIT` reuses the
  same current-revision, active-scenario coverage and passing Test Run invariants as the command;
  `REVIEW` also enforces maker-checker identity, and `PROMOTE` is role- and lifecycle-owned.
- `PUT /api/praxis/config/domain-rules/workspaces/{id}/draft` requires a strong `If-Match`, verifies
  that the base fingerprint has not changed, increments `revision` and rotates the ETag.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/scenarios` persists reusable typed facts and
  an expected `ALLOW`, `DENY`, `NOT_APPLICABLE`, `INCONCLUSIVE` or `TECHNICAL_ERROR` result.
- `GET /api/praxis/config/domain-rules/workspaces/{id}/scenarios` returns scenarios in stable key order.
- `PUT /api/praxis/config/domain-rules/workspaces/{id}/scenarios/{scenarioId}` requires the current
  strong scenario ETag. Creating or changing a scenario rotates the parent workspace revision and
  ETag, invalidating every earlier Test Run. Scenario mutation and Test Run recording serialize on
  the same workspace lock so evidence cannot race an expectation change.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/test-runs` records immutable host-produced
  evidence only if workspace revision, base fingerprint and scenario identities still match. Its
  required `idempotencyKey` may be retried only with the exact same canonical payload: an exact
  replay returns the original run, while key reuse with different evidence returns `409`. The
  command is bounded to 1,000 scenario results so a control-plane write cannot become an unbounded
  evidence payload. The configured suite should normally remain much smaller than this transport
  safety ceiling. Optional `baselineEvidence` identifies `SYNTHETIC_EXPECTED`, `ACTIVE_SNAPSHOT` or
  `LEGACY_ORACLE` authority through an opaque artifact reference, SHA-256, observation time and
  explicit `ELIGIBLE`, `INELIGIBLE` or `PENDING` status. An `ELIGIBLE` authority requires an
  independent `baselineResult` for every scenario; `activeDecision` must never be relabeled as a
  legacy result. Each result may also add sanitized
  `operationalEvidence` for an actual `CREATE` or `UPDATE`, including before/after state digests,
  mutation/no-mutation, cleanup, effect-ledger digest and baseline call count.
- Host orchestrators may resolve an already persisted receipt by the same scoped idempotency key
  before re-running evaluation or operational probes. This lookup remains service-internal in V58;
  remote hosts retry the canonical POST with the identical frozen payload.
- `GET /api/praxis/config/domain-rules/workspaces/{id}/test-runs` lists safe evidence without facts
  or executable snapshot payloads.
- `POST /api/praxis/config/domain-rules/workspaces/{id}/submit` requires the current strong ETag and
  a latest Test Run for the exact workspace revision/fingerprint. The run must cover exactly every
  active scenario; every candidate must match its expected decision and no result may be
  inconclusive or technical. The accepted Test Run id is bound immutably to the submitted workspace
  so review and later stages cannot be switched to a newer, unreviewed run.
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
  When the base Definition declares a `governance.testEvidencePolicy.stages.PROMOTE` policy, Config
  also evaluates the Test Run bound at submission and withholds the capability/command until its
  baseline authority, eligibility, operation/decision matrix, parity and cleanup requirements pass.

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

The immutable record preserves expected/candidate/active output, normalized
reason codes and planned effect-intent identifiers. Config recomputes every match
flag from the persisted scenario instead of trusting booleans sent by the host.
Submission fails closed when any candidate assertion differs. Absence of
`expectedOutput` means output is not asserted; empty reason/effect lists explicitly
expect none. The sandbox never executes an effect.

Migration `V56` gives existing scenarios empty reason/effect expectations. Existing
DENY scenarios that omitted expected reason codes can become blocked until an
author records the intended assertions; this is deliberate fail-closed beta behavior.

Migration `V57` keeps provenance optional for existing generic workspaces. When supplied, Config
validates the closed authority, eligibility and operation vocabularies, every digest and
contradictory mutation/no-mutation claims. Only redacted references and digests are persisted: raw
Oracle rows, facts, credentials, SQL, executable policy and effect payloads remain outside Config.
An `operationMode` without state/effect evidence is not an operational proof; conversely, the
portable synthetic corpus must not claim legacy parity merely because its fixtures contain the
strings `CREATE` and `UPDATE`.

Migration `V58` adds scoped idempotency, a canonical request hash, the independent baseline lane and
the submitted-Test-Run binding. Stage policy is opt-in and server-owned; it does not globally force
Oracle evidence at `SUBMIT`. A definition can govern `SUBMIT`, `PROMOTE`, `PUBLISH`, `SNAPSHOT`,
`ACTIVATE` or a combination
of those stages with this shape:

```json
{
  "testEvidencePolicy": {
    "stages": {
      "PUBLISH": {
        "baselineAuthorityType": "LEGACY_ORACLE",
        "baselineEligibility": "ELIGIBLE",
        "requiredOperationModes": ["CREATE", "UPDATE"],
        "requiredDecisions": ["ALLOW", "DENY"],
        "requireCleanupVerified": true,
        "requireBaselineMatch": true
      }
    }
  }
}
```

The required operation and decision lists form a Cartesian gate. Unknown fields and malformed
policies fail closed instead of silently weakening governance. `SUBMIT`, `PROMOTE`, `PUBLISH`,
`SNAPSHOT` and `ACTIVATE` are accepted stage names. Misspelled stages are invalid rather than inert. At
publication, Config resolves the unique promoted workspace by `promotedDefinitionId`, reuses its
immutable `submittedTestRunId` and returns `blocked_by_test_evidence` plus safe blocker codes when
the reviewed receipt does not satisfy the policy. A definition without a `PUBLISH` stage retains
the existing publication behavior.
For `SNAPSHOT` and `ACTIVATE`, the same evaluator runs while the canonical composition manifest is
prepared. Required evidence is represented only by safe IDs, request hash, workspace revision and a
canonical digest of the immutable Test Run/results; raw facts are never copied into the manifest.
The composition digest and two independent approvals therefore bind the reviewed evidence before
publication. Later activation and rollback verify the persisted manifest and do not reinterpret
mutable workspace state. A definition without either stage remains opt-in compatible. Browsers must
not infer permission or evidence sufficiency.

Workspace actions and blockers are exposed through the dedicated capabilities
read and the public `@praxisui/core` client. Clients must not infer review,
promotion or submission authority from `status`. Publication, rollout creation
and rollout-policy commands use their own server-owned action catalogs. Definition publication,
rollout creation and rollout-policy lifecycle actions are now principal-specific; one capability
is never reused to authorize a different operation.
