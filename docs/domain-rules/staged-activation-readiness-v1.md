# Staged activation readiness v1

Status: architectural decision and implemented backend contract. Runtime enforcement remains
explicitly opt-in per governed RuleSet policy.

Implementation status:

- persistence slice implemented by migration V53: versioned policy, scoped rollout, monotonic
  candidate probes and append-only safe events;
- observational service/API is implemented for create, cancel, candidate probes, aggregate
  readiness and reader-scoped human rediscovery after workstation reload;
- the Quickstart reference host implements isolated candidate fetch/hash/scope/compatibility
  verification and compilation, then publishes a probe without receiving the active runtime;
- scoped pending-rollout discovery and the Quickstart one-shot supervisor are implemented;
- governed policy authoring, maker-checker approval, conditional activation and append-only
  timeline are implemented; deployment job scheduling remains pending;
- activation enforcement is implemented transactionally and remains backward-compatible while
  the active policy is `OBSERVE_ONLY`;
- no existing activation behavior changes until those slices are complete and enforcement is
  explicitly configured as `REQUIRED`.

## Problem

The current host-status projection answers whether deployments are aligned with the active
RuleSet head. It is suitable for drift detection after activation, but it cannot safely decide
whether a newer immutable snapshot may become active.

Blocking `activate` with `alignedHosts` from the current summary would create a circular gate:

1. hosts discover and load the current active head;
2. the candidate is not the head yet;
3. no host can truthfully report that the candidate is active;
4. activation waits for reports that cannot exist without activation.

Allowing a host to replace its active runtime with the candidate merely to satisfy the gate is
also invalid. It would move execution authority to individual hosts before the control-plane head
and its ETag are changed.

Classification: `lacuna-real-de-contrato`. The platform has immutable candidates, active heads,
ETag activation and active-host telemetry, but it has no canonical, non-authoritative candidate
preload lane.

## Decision

Activation readiness is a two-phase control-plane workflow:

```text
published immutable candidate
        |
        v
server-owned rollout request (candidate + expected current head ETag)
        |
        v
hosts fetch, verify and compile candidate in an isolated preload lane
        |
        v
redacted candidate probe reports
        |
        v
Config derives quorum/readiness
        |
        v
operator activates with If-Match and rollout identity
        |
        v
hosts atomically adopt the new active head
```

The active runtime and the candidate preload must coexist. A candidate probe never authorizes
business evaluation and never changes the host's last-known-good active reference.

## Canonical ownership

- Config Starter owns rollout request, policy, candidate probe persistence, aggregate readiness,
  ETag binding, activation gate and append-only rollout events.
- Rules Engine owns deterministic candidate verification/compilation contracts.
- Host owns executable implementations and the isolated preload attempt. It reports only safe
  compatibility coordinates and never claims that a preload is active.
- Policy Studio explains readiness and invokes server-published actions. It does not calculate
  quorum, enumerate hosts or override the gate.

## Minimum canonical model

### Rollout request

A rollout is scoped by tenant, environment and RuleSet and binds:

- stable `rolloutId`;
- candidate immutable snapshot id/key/hash/publication revision;
- expected current active snapshot id and strong head ETag;
- rollout policy id/version;
- lifecycle: `PREPARING`, `READY`, `BLOCKED`, `ACTIVATED`, `CANCELLED`, `EXPIRED`;
- creator and timestamps resolved server-side.

Only one non-terminal rollout may exist per scoped RuleSet. Creating a rollout does not mutate the
head.

### Candidate probe

Each authenticated `RULE_EXECUTION_OBSERVER` may publish one monotonic probe per rollout. The
server supplies tenant/environment/actor from the principal. A probe contains:

- `rolloutId` and candidate snapshot identity;
- `preloadReady`;
- host contract, engine contract, JSON Logic dialect/corpus and implementation-catalog digest;
- bounded failure code and observed timestamp.

It contains no hostname, IP, facts, input/output, executable payload or business identifier. It
must not overwrite the host's active-head heartbeat.

### Policy and aggregate

Policy is server-owned and versioned. The minimum policy supports:

- enforcement mode: `OBSERVE_ONLY` or `REQUIRED`;
- minimum distinct fresh probes;
- minimum ready ratio;
- stale duration;
- whether any incompatible fresh probe blocks readiness;
- optional maximum rollout age.

Policy versions follow `DRAFT -> APPROVED -> ACTIVE -> SUPERSEDED`. Creation requires
`RULE_DEFINITION_AUTHOR`, approval requires a distinct authenticated actor with
`RULE_DEFINITION_APPROVER`, and activation requires `RULE_SNAPSHOT_OPERATOR` plus the strong
policy-head `If-Match`. The policy head has an independent monotonic activation revision and
rotating ETag, so changing rollout safety does not mutate the snapshot head and cannot suffer an
ABA transition. A policy cannot change while a rollout is open.

The aggregate exposes counts only: total, ready, incompatible, unavailable and stale, plus the
candidate identity, policy version and derived state. Browser consumers never receive actors.

## Activation invariants

`POST .../{snapshotKey}/activate` may accept a rollout identity only after these checks execute in
the same Config transaction that locks the head:

1. snapshot, rollout and current head share tenant/environment/RuleSet;
2. rollout candidate equals the requested immutable snapshot;
3. rollout expected-head ETag equals the currently locked head;
4. candidate remains verified and inside its validity interval;
5. policy is `OBSERVE_ONLY`, or the server-derived aggregate is `READY`;
6. caller still has `RULE_SNAPSHOT_OPERATOR`;
7. supplied `If-Match` still strongly matches the head.

On success, Config rotates the head ETag, appends the ordinary activation event and closes the
rollout as `ACTIVATED`. A stale head, superseded candidate, expired rollout or insufficient quorum
fails without mutating head or rollout evidence.

Rollback remains an emergency recovery operation and must not depend on candidate quorum. It
continues to require a strong current-head ETag and a verified, valid prior snapshot. Requiring
fleet readiness before rollback would make recovery depend on the unhealthy fleet it is intended
to repair.

## Rollout and compatibility behavior

- Default enforcement is `OBSERVE_ONLY`; introducing the model must not silently break existing
  beta consumers.
- `REQUIRED` is enabled explicitly per governed environment after hosts implement preload probes.
- Unknown policy versions, missing candidate compatibility coordinates and unknown statuses fail
  closed under `REQUIRED`.
- A candidate probe is monotonic by server scope, rollout and actor. Delayed observations cannot
  replace newer ones.
- Cancelling or expiring a rollout does not delete probes; aggregate responses stop treating them
  as actionable, while append-only audit remains available.

## Required implementation slices

1. **Implemented:** persist rollout, versioned policy, candidate probes and append-only events in
   Config.
2. **Implemented:** publish create/cancel/readiness, a human catalog with server-derived
   `availableActions`, and candidate probe endpoints with server-owned scope. The catalog exposes
   counts and immutable identities but never host actors.
3. **Implemented in the Quickstart reference host:** add an isolated host preload SPI that compiles
   without changing the active reference.
   Discovery is fail-closed for expired rollouts and whenever the expected active snapshot or head
   ETag has changed. The Quickstart exposes only `pollOnce()`; deployment infrastructure owns the
   supervised cadence, retry and shutdown policy.
4. **Implemented:** bind `activate` to rollout readiness under `REQUIRED`, preserving
   ETag/transaction semantics. `X-Rule-Rollout-ID` identifies the rollout; the service locks the
   head and rollout, rechecks candidate/head/policy/expiry/quorum, rotates the ETag, appends the
   activation event and closes the rollout in one transaction.
5. **Implemented:** author immutable policy versions, approve them through maker-checker, activate
   them with a separate strong policy-head ETag and expose a safe append-only timeline. The active
   policy's maximum rollout age is enforced during rollout creation.
6. **Implemented:** materialize safe policy/rollout/readiness actions in `@praxisui/core` and
   Policy Studio. The Studio can recover an open rollout after reload, create/cancel it and pass
   its identity when promoting the candidate; it does not infer quorum or lifecycle commands.
7. Add operations cleanup/expiry and metrics without actor or RuleSet-cardinality leaks.

## Minimum proof

- candidate preload never changes active evaluation;
- two tenants using the same RuleSet/snapshot labels never share probes;
- delayed probe cannot replace a newer probe;
- incompatible and stale probes affect the configured policy deterministically;
- stale `If-Match` or changed expected head blocks activation even after quorum;
- concurrent activate calls produce one head transition;
- candidate supersession invalidates the prior rollout;
- `OBSERVE_ONLY` preserves current activation behavior;
- `REQUIRED` blocks insufficient quorum and permits exact quorum;
- the author cannot approve the same policy version;
- stale policy-head `If-Match` and an open rollout block policy activation;
- policy v1 -> v2 -> v1 selection rotates the policy-head ETag and prevents ABA;
- rollback remains available when rollout readiness is unhealthy;
- Studio receives counts/capabilities only, never host identities.
- Human catalogs publish principal-specific actions instead of requiring clients to infer
  authority from lifecycle status. Definition capabilities include `PUBLISH` only for an
  approved/active definition and an authenticated publisher. Rollout catalogs publish
  `CREATE_ROLLOUT`, `CANCEL`, and `ACTIVATE_CANDIDATE` only to snapshot operators. Rollout-policy
  catalogs publish `CREATE_POLICY_VERSION`, `APPROVE`, and `ACTIVATE`, preserving maker-checker,
  open-rollout blockers, and the server-resolved principal. An empty policy catalog remains
  readable before the first policy head exists so an authorized author can discover the initial
  creation command.

The Quickstart reference host implements the persisted happy-path proof in
`RuleStagedRolloutPostgresIntegrationTest`: real PostgreSQL constraints, immutable RuleSet versions,
`REQUIRED` readiness, host-side preload/probe, server-derived activation capability, ETag-bound head
promotion and exactly one append-only activation event are exercised in one integration flow.

## Rejected alternatives

- Gate the candidate using active-head `alignedHosts`: circular and semantically false.
- Let the Studio calculate quorum: duplicates control-plane authority in the browser.
- Let a host activate the candidate locally before the head changes: creates split authority.
- Reuse the active heartbeat row for candidate preload: loses simultaneous active/candidate truth.
- Enable a required global default immediately: breaks hosts that cannot yet publish probes.
