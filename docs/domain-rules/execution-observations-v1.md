# Domain Rule execution observations v1

## Boundary

Execution observations are append-only, redacted evidence that an authenticated host evaluated an
exact governed snapshot. They complement, but never replace:

- definition/materialization timelines, which explain authoring and projection;
- snapshot events, which explain publication, activation and rollback;
- host metrics, which provide high-volume operational monitoring.

The Config Starter owns persistence and safe aggregation. The Rules Engine remains deterministic
and has no I/O. Hosts must deliver observations asynchronously, outside the business transaction;
an unavailable control plane must not block evaluation or effects.

## HTTP contract

`POST /api/praxis/config/domain-rules/snapshots/execution-observations`

- requires `RULE_EXECUTION_OBSERVER`;
- resolves tenant, environment and `hostActorRef` from the authenticated server principal;
- accepts between 1 and 100 observations;
- is idempotent by `observationId`; exact retries are counted as duplicates and conflicting reuse is
  rejected;
- validates the immutable snapshot hash and proves that `activationRevision` selected that snapshot
  in the same governed scope;
- returns only `acceptedCount` and `duplicateCount`.

Each observation contains only:

- random `observationId`;
- `snapshotKey`, uppercase `snapshotContentHash` and `activationRevision`;
- one of `ALLOW`, `DENY`, `NOT_APPLICABLE`, `INCONCLUSIVE`, `TECHNICAL_ERROR`;
- bounded `durationMicros` and `observedAtUtc`.

Facts, facts digest, reason codes, matched bindings, actor of the business operation, request
reference, input/output, effect payload and executable snapshot content are not accepted.

`GET /api/praxis/config/domain-rules/snapshots/{snapshotKey}/execution-summary`

- requires `RULE_SNAPSHOT_READER`;
- returns snapshot identity, total observations, distinct-host count, outcome counts and the first
  and last observed instants;
- never returns host identities or individual observations.

## Persistence and privacy

`domain_rule_execution_observation` has a composite foreign key to the snapshot's tenant,
environment and RuleSet. Observations cannot be moved across scope or attached to an arbitrary
content hash. The table is append-only through the application contract; no update or delete HTTP
operation exists.

Physical retention remains an operator-owned database policy in v1. Before production rollout, the
platform deployment must declare its retention interval, legal hold policy and archival strategy.
The application must not silently delete audit evidence or retain it indefinitely by default.

## Deliberate next increments

1. Quickstart outbox with bounded retry/backoff and a dedicated service principal.
2. Host heartbeat/status for current-head alignment and last-known-good drift is now defined by
   `host-status-v1.md`; engine-coordinate compatibility remains a separate future increment.
3. `@praxisui/core` read client and Policy Studio projection after the backend contract is consumed
   by at least two real hosts.
4. Production retention/archival adapter and load/cardinality validation.
