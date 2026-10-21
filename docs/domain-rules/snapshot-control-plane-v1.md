# Rule snapshot control plane v1

Operational host alignment with the current active head is exposed through the aggregate contract
in `host-status-v1.md`. Drift remains server-derived; host identities and runtime payloads are not
exposed to browser consumers.

Active-head alignment is not a pre-activation quorum. Candidate promotion requires the separate
two-phase preload contract in `staged-activation-readiness-v1.md`; using current-head heartbeats to
gate a newer snapshot is explicitly invalid.

## Decision

`praxis-config-starter` persists immutable `PublishedRuleSnapshot` envelopes from
`praxis-rules-engine` and owns only their publication lifecycle. It does not load
or execute domain-host Java implementations. During publication it uses the
engine's planning-only registry to validate the deterministic decision graph.
That registry is supplied by the host-governed `DomainRuleImplementationCatalog`;
Java coordinates declared only by the candidate are not admitted implicitly.
The domain host must still compile the snapshot again with its executable
registry and fail closed before atomic activation.

Java coordinates are never allowlisted from the publication payload itself.
The host supplies a `DomainRuleImplementationCatalog`, scoped by tenant,
environment and `ownerServiceKey`. The default catalog is empty and therefore
fails closed for every Java-backed RuleSet. Product entries identify code shipped
by the host; customer entries must also carry the `RuleExtensionTrust` generated
after external signature, artifact-digest and policy verification.

Each scoped RuleSet has three independent records:

- immutable snapshot content, addressed by `snapshotKey` and canonical
  `snapshotContentHash`;
- zero or one mutable active head, protected by an opaque `headEtag`;
- append-only publication/activation/rollback events with monotonic
  `activationRevision`.

The RuleSet is also the atomic composition boundary. When multiple reactive
determinations have an ordering dependency or must roll forward/back together,
all bindings belong to one immutable snapshot and one scoped head. Publishing
separate materialization heads for each step would permit mixed revisions inside
one business operation and is therefore not a valid corporate runtime design.
The colocated host must pin one verified head for the complete operation.

Publication persists an approved immutable candidate and a `PUBLISHED` event only. It returns
`DomainRuleSnapshotStoredResponse` with the content hash as HTTP ETag; it never selects a head,
rotates its validator or completes a rollout. A published candidate is not execution authority.
Selection is always an explicit governed activation. Head absence is a valid initial state, not
an incomplete head row. New `PUBLISHED` events always omit activation revision/head ETag,
even when an existing head was observed: recording those as selection evidence would falsely
admit execution observations for an inactive candidate. Historical publication events retain
their original identity and meaning.

The content hash is deliberately not the head ETag. Every activation, including
rollback from v2 to v1, rotates the opaque head ETag. This prevents an ABA race
where a stale client could mistake the reselected v1 head for the original v1
head.

`RuleSetRef.version` is immutable and unique inside the tenant, environment and
RuleSet key. Changed content must use a new RuleSet version. Reusing prior
content is a head-selection operation (`activate` forward or `rollback`
backward), not a second publication.

Publication of a higher immutable version may supersede a snapshot produced by
an older engine compatibility baseline. The control plane verifies the prior
envelope identity, canonical stored snapshot hash and persisted composition
approvals without asking the current engine to compile obsolete executable
content. The new candidate is still compiled strictly by the current engine and
host admission catalog before activation. Executable head reads (`findActive`/`GET .../head`), activation and rollback continue to
fail closed for an old snapshot that the current engine cannot verify. Immutable detail
reads use the same historical envelope/hash/composition/approval verification as
supersession and do not compile against the current runtime or catalog. Intact
incompatible snapshots and snapshots whose Java implementations were later revoked
remain available to an authorized historical reader, without granting execution.
Neither path rewrites stored payload bytes, hashes, approvals, manifests or events.

This release adopts `praxis-rules-engine:0.1.0-beta.16` (engine contract `1.5`).
Version and head-status projections verify historical integrity first. Intact
pre-manifest snapshots are `REPUBLICATION_REQUIRED`; corrupt pre-manifest snapshots
are `INVALID`. With a verified composition, any exact engine/dialect/corpus tuple
mismatch is `RUNTIME_INCOMPATIBLE`. A matching tuple must also pass the current
compiler and host admission catalog to be `READY`; a compilation or catalog
revocation failure is `INVALID`. Pre-manifest detail remains refused, even when
its envelope/hash are intact. All non-`READY` versions have `availableAction=UNAVAILABLE`,
including a version whose `active=true` records the historical head selection.
`READY` is Config planning admission, not host preload/quorum readiness or permission
to bypass the separate activation gate.

## HTTP contract

- `POST /api/praxis/config/domain-rules/snapshots`
  - requires the exact server-generated `compositionDigest` and loads at least
    two distinct persisted `RULE_COMPOSITION_APPROVER` decisions over it;
  - resolves the publisher from server authentication and requires
    `RULE_SNAPSHOT_PUBLISHER`; publisher identity is not a payload field;
  - rejects source, RuleSet, validity, host-contract or planning-catalog drift;
  - requires `If-None-Match: *` whenever the scoped head is absent, otherwise its strong `If-Match`;
  - returns `201` with immutable `snapshot` and `snapshotContentHash`, and the content hash as ETag;
  - does not mutate/create a head or authorize execution; publication order and supersession follow
    the latest publication, independently of which version is active.
- `POST /api/praxis/config/domain-rules/snapshots/composition-manifest`
  - resolves the authenticated principal and requires `RULE_SNAPSHOT_PUBLISHER`;
  - uses tenant and environment resolved by the server; caller headers are optional routing hints
    and cannot widen the authenticated scope;
  - resolves approved source hashes and the host admission catalog;
  - evaluates opt-in `SNAPSHOT` and `ACTIVATE` Test Run policies for every source and embeds only
    immutable safe references plus an evidence digest, never raw scenario facts;
  - validates the candidate and returns the canonical manifest and SHA-256 to
    present to composition approvers.
  - returns `DomainRuleSnapshotProblemResponse` with `code=TEST_EVIDENCE_BLOCKED` and safe
    `blockers[]` (`code`, `stage`, `definitionId`, `message`) when reviewed evidence is insufficient;
    IAM remains represented by `401/403` and clients must never parse the message to infer either.
- `POST /api/praxis/config/domain-rules/snapshots/composition-approvals`
  - receives the complete candidate, recomputes its canonical manifest and
    appends one approval for the authenticated `RULE_COMPOSITION_APPROVER`;
  - assigns actor and timestamp server-side and is idempotent for the same
    tenant, environment, digest and actor;
  - must be called independently by each approver. A publication payload cannot
    declare or aggregate approver identities.
- `GET /api/praxis/config/domain-rules/snapshots/head?ruleSetKey=...`
  - resolves server authentication and requires `RULE_SNAPSHOT_READER`;
  - uses tenant and environment resolved from the principal, never directly
    from caller headers in corporate mode;
  - returns `Cache-Control: no-cache` and the mutable head ETag;
  - accepts `If-None-Match` and may return `304`.
- `GET /api/praxis/config/domain-rules/snapshots?ruleSetKey=...&limit=50`
  - requires the authenticated `RULE_SNAPSHOT_READER` scope;
  - returns a bounded, newest-first version catalog with immutable identity,
    publication metadata, active-state marker and safe governance state;
  - never returns executable snapshot content or approval evidence;
  - classifies each entry as `READY`, `RUNTIME_INCOMPATIBLE`, `REPUBLICATION_REQUIRED` or `INVALID` and
    publishes its relative `availableAction`: `ACTIVE`, `ACTIVATE`, `ROLLBACK`
    `CREATE_ROLLOUT` or `UNAVAILABLE`;
  - lets policy studios expose only the server-authorized direction instead of
    inferring lifecycle semantics from revision numbers.
- `GET /api/praxis/config/domain-rules/snapshots/head/status?ruleSetKey=...`
  - requires the same authenticated `RULE_SNAPSHOT_READER` scope;
  - returns safe head identity, immutable version/revision, readiness and the mutable head ETag;
  - always re-evaluates readiness and returns `200` with `Cache-Control: no-store`, including
    when `If-None-Match` matches the head ETag: the runtime/catalog can change independently
    of the head. The body/header head ETag remains the concurrency token for governed mutations;
  - never returns unverified executable content;
  - classifies preserved pre-manifest beta content as `REPUBLICATION_REQUIRED`, allowing an
    operator to publish a new immutable RuleSet version with the returned strong `If-Match`.
- `GET /api/praxis/config/domain-rules/snapshots/{snapshotKey}`
  - requires the same authenticated `RULE_SNAPSHOT_READER` scope;
  - verifies historical envelope identity, canonical content hash and composition approvals,
    independently of the current compiler/catalog; intact incompatible history is readable;
  - refuses corrupt or pre-manifest content and never establishes execution readiness;
  - returns the immutable snapshot with its unchanged canonical content hash as ETag;
  - is private-cacheable and immutable.
- `POST /api/praxis/config/domain-rules/snapshots/{snapshotKey}/rollback`
  - requires a strong current-head `If-Match`;
  - resolves the actor from server authentication and requires
    `RULE_SNAPSHOT_OPERATOR`;
  - selects an older, currently valid immutable publication, rotates the head
    ETag and appends an audit event. It never rewrites or republishes the target
    snapshot. Selecting a newer publication is a roll-forward and is rejected by
    this rollback endpoint.
- `POST /api/praxis/config/domain-rules/snapshots/{snapshotKey}/activate`
  - requires a strong current-head `If-Match` for an existing head;
  - for bootstrap, requires `If-None-Match: *`, verified absence under the scope lock, and a
    ready rollout bound to that absence under a maker-checker governed active REQUIRED policy
    with a positive quorum; an absent/default OBSERVE_ONLY policy never grants bootstrap;
  - resolves the actor from server authentication and requires `RULE_SNAPSHOT_OPERATOR`;
  - selects a newer, currently valid immutable publication, rotates the head
    ETag and appends an `ACTIVATED` audit event;
  - rejects an active or older publication. Older content must use the explicit
    rollback operation.
  - accepts optional `X-Rule-Rollout-ID`; under an active `REQUIRED` rollout policy it becomes
    mandatory and is revalidated while both head and rollout are locked. Under `OBSERVE_ONLY`,
    omission is allowed only for an existing head; bootstrap always requires a real ready rollout.

Read operations require explicit `X-Tenant-ID` and `X-Env`. In corporate mode,
approval, publication, activation and rollback derive tenant, environment and actor from the
server principal; header values are only hints and cannot replace authenticated
scope. Publication requires approved source definitions with append-only
approval evidence for each definition's current canonical hash and at least two
distinct composition approvers. Source hashes and authenticated approval
evidence become part of the immutable snapshot envelope.

## Scope concurrency and bootstrap

Publication, head selection, rollout creation/cancellation and rollout-policy mutations share one
PostgreSQL transaction advisory lock for the exact tenant/environment/RuleSet. A dedicated namespace
and length-prefixed scope parts serialize even absent heads without ambiguous delimiter matching.
The active writable Config datasource transaction must use READ COMMITTED. External pending JPA
changes are rejected before querying lifecycle inputs; clean preloaded heads, policies, rollouts and
publication source definitions are refreshed after scope serialization. No global persistence-context
clear or no-op production lock is used. Persistent uniqueness remains an additional invariant.
Observational probe updates use a directed rollout row lock only; they do not acquire the
scope lock or select a head. Lifecycle operations re-evaluate their evidence under the scope lock.

Migration V69 permits a paired null expected head identity on rollout and nullable selection identity
only on publication events; existing records, payloads, hashes and approval evidence are unchanged.
Rollout response fields `expectedActiveSnapshotKey`/`expectedHeadEtag` are null together for bootstrap.
Pending discovery compares that expectation with actual absence; it never substitutes a dummy head.
The version catalog exposes `CREATE_ROLLOUT` for READY content without a head and never claims direct
ACTIVATE is authorized. Final activation validates the expectation again under lock, saves the head,
appends the selection event and completes rollout in one transaction. Concurrent first selectors
cannot both commit. Rollback remains the established emergency operation without candidate quorum,
while retaining current runtime/catalog, scope, validity, authorization and strong head CAS checks.

Manual Java wiring must provide the existing host catalog, a real activation gate, the shared scope
lock and Config-owned `DomainRuleEntityRefresh`. The auto-configured fallback gate is unavailable,
never allow-all. Unit mocks do not count as an operational bootstrap or concurrency proof.

## Approval-to-composition binding

The selected design is an explicit canonical composition manifest. Its digest
covers tenant/environment, owner and host contract, validity, approved source
hashes, reviewed Test Run evidence required for `SNAPSHOT`/`ACTIVATE`, the complete RuleSet and the
host-governed admission catalog. Publication
rebuilds that manifest in the same transaction and loads two distinct,
append-only approvals created in independent authenticated calls; the
authenticated publisher cannot be one of them.

The manifest JSON and digest are persisted beside the immutable snapshot and
verified on read and rollback. Pre-existing beta snapshots are retained for audit by migration V33
and continue to fail closed on runtime reads. Their safe head status remains observable so a newly
approved composition can supersede them with a higher immutable RuleSet version; the legacy payload
itself is never promoted, rewritten or accepted as governed content. Supersession validates the
preserved envelope identity but does not require the current engine to compile an obsolete runtime
baseline; complete compilation, composition and hash checks apply to the new candidate.

This closes both maker-checker layers. Definition and materialization reads,
including safe timelines, require `RULE_DEFINITION_READER` and use the
server-resolved tenant/environment. Definition creation and intake require
`RULE_DEFINITION_AUTHOR`; structural simulation requires either
`RULE_DEFINITION_AUTHOR` or `RULE_DEFINITION_APPROVER`, so the independent
reviewer can recompute canonical readiness without receiving authoring authority;
draft/proposed authoring transitions continue to use the author role;
approval, rejection, activation and retirement require
`RULE_DEFINITION_APPROVER`, whose authenticated actor must differ from the
persisted author. Snapshot reads require `RULE_SNAPSHOT_READER`; composition
approval, publication, activation and rollback actors also
come from server authentication. Corporate mode fails closed unless the request
has the corresponding IAM role. Request payloads never supply governed actor
identity. Migration V36 persists definition approvals append-only and binds each
one to the exact canonical source hash used by snapshot publication.
Definitions created before this contract without `createdByType=authenticated`
fail closed and must be recreated or versioned through the authenticated author
flow; migration does not relabel caller-declared legacy identity as trusted.

Composition contract `praxis-rule-composition/2` binds each required evidence receipt by definition,
stage, workspace, Test Run, request hash, workspace revision and canonical evidence digest. Preparing
or rebuilding a candidate fails closed when the evidence service is unavailable, provenance is
ambiguous or the stage policy is not satisfied. Activation and rollback trust only the already
approved immutable manifest and reverify its digest; they do not query a later mutable workspace.
Version 1 manifests remain historical immutable publications and continue through their original
composition verification, but cannot claim the v2 Test Run binding.

Snapshot control-plane failures use a typed response. Ordinary validation/concurrency failures keep
an empty blocker list; only server-owned governance diagnostics populate it. This allows Policy
Studio and other clients to localize known codes while preserving the sanitized server message as a
fallback. A blocker is not an authorization grant and never replaces `availableAction` or IAM.

## Compatibility and ownership

This contract raises the starter baseline to Java 21 because the canonical
engine contract is Java 21. Duplicating engine DTOs or persisting an untyped JSON
facsimile would create a second source of truth and was rejected. Hosts use the
framework-neutral `PublishedRuleSnapshotHeadReader` contract from
`praxis-config-contracts` for in-process loading, then compile with their
executable registry before atomic activation. The Config Starter supplies the
default adapter from its governed active head; remote hosts may supply an
authenticated HTTP adapter without depending on Starter implementation DTOs.

The admission catalog and executable registry must resolve the same exact
coordinates. The Config Starter does not load plugins or verify signatures, and
the engine is not a sandbox. A host must not register arbitrary tenant code in
the application process.

## PostgreSQL concurrency and host LKG proof

The current proof uses an exclusively assigned remote Neon database, the official
Flyway migrations and distinct migration-owner/runtime identities. Runtime mutations
use ordinary DML privileges. The gate proves absent-head serialization, conditional
publication and selection races, preloaded JPA refresh, required-quorum bootstrap,
and transaction rollback after head insertion or policy supersession flush. It fails
closed without the expected branch/host/database/role attestation and private URI files.
See `g2-publication-activation-validation-2026-10-21.md` for the exact assignment,
command and evidence. No local or embedded database is a substitute for this proof.

```shell
mvn -Dtest=DomainRuleSnapshotPublicationNeonIT test
```

Last-known-good retention and readiness are deliberately host-runtime concerns,
not a second cache owned by the Config Starter. The Quickstart reference runtime
loads through `PublishedRuleSnapshotHeadReader`, compiles and verifies a candidate
before atomically replacing its active reference, and retains the previous
effective plan when the control plane is unavailable. Its focused proof is:

```shell
cd ../praxis-api-quickstart
mvn -Dtest=ExtraordinaryGrantRuleSnapshotRuntimeTest test
```

An outage never makes expired content ready: the runtime recomputes temporal
effectiveness when reporting status or capturing an evaluation session.

## Host catalog example

```java
@Bean
DomainRuleImplementationCatalog ruleImplementationCatalog() {
  return scope -> List.of(
      new RuleImplementationRef("benefits:amount", "1.0.0"),
      new RuleImplementationRef(
          "customer:eligibility",
          "1.0.0",
          externallyVerifiedTrust));
}
```

The catalog implementation must resolve scope from server-owned configuration;
it must not trust tenant, signer, artifact digest or policy evidence copied from
the snapshot request.
