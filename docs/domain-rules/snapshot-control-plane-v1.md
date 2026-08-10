# Rule snapshot control plane v1

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
- one mutable active head, protected by an opaque `headEtag`;
- append-only publication/rollback events with monotonic `activationRevision`.

The content hash is deliberately not the head ETag. Every activation, including
rollback from v2 to v1, rotates the opaque head ETag. This prevents an ABA race
where a stale client could mistake the reactivated v1 head for the original v1
head.

`RuleSetRef.version` is immutable and unique inside the tenant, environment and
RuleSet key. Changed content must use a new RuleSet version. Reusing prior
content is an activation/rollback operation, not a second publication.

Publication of a higher immutable version may supersede a snapshot produced by
an older engine compatibility baseline. The control plane verifies the prior
envelope identity, canonical stored snapshot hash and persisted composition
approvals without asking the current engine to compile obsolete executable
content. The new candidate is still compiled strictly by the current engine and
host admission catalog before activation. Runtime reads and rollback continue to
fail closed for an old snapshot that the current engine cannot verify.

## HTTP contract

- `POST /api/praxis/config/domain-rules/snapshots`
  - requires the exact server-generated `compositionDigest` and loads at least
    two distinct persisted `RULE_COMPOSITION_APPROVER` decisions over it;
  - resolves the publisher from server authentication and requires
    `RULE_SNAPSHOT_PUBLISHER`; publisher identity is not a payload field;
  - rejects source, RuleSet, validity, host-contract or planning-catalog drift;
  - initial publication requires `If-None-Match: *`;
  - later publications require a strong `If-Match` with the current head ETag.
- `POST /api/praxis/config/domain-rules/snapshots/composition-manifest`
  - resolves approved source hashes and the host admission catalog;
  - validates the candidate and returns the canonical manifest and SHA-256 to
    present to composition approvers.
- `POST /api/praxis/config/domain-rules/snapshots/composition-approvals`
  - receives the complete candidate, recomputes its canonical manifest and
    appends one approval for the authenticated `RULE_COMPOSITION_APPROVER`;
  - assigns actor and timestamp server-side and is idempotent for the same
    tenant, environment, digest and actor;
  - must be called independently by each approver. A publication payload cannot
    declare or aggregate approver identities.
- `GET /api/praxis/config/domain-rules/snapshots/head?ruleSetKey=...`
  - returns `Cache-Control: no-cache` and the mutable head ETag;
  - accepts `If-None-Match` and may return `304`.
- `GET /api/praxis/config/domain-rules/snapshots/head/status?ruleSetKey=...`
  - returns safe head identity, immutable version/revision, readiness and the mutable head ETag;
  - never returns unverified executable content;
  - classifies preserved pre-manifest beta content as `REPUBLICATION_REQUIRED`, allowing an
    operator to publish a new immutable RuleSet version with the returned strong `If-Match`.
- `GET /api/praxis/config/domain-rules/snapshots/{snapshotKey}`
  - returns the immutable snapshot with its canonical content hash as ETag;
  - is private-cacheable and immutable.
- `POST /api/praxis/config/domain-rules/snapshots/{snapshotKey}/rollback`
  - requires a strong current-head `If-Match`;
  - resolves the actor from server authentication and requires
    `RULE_SNAPSHOT_OPERATOR`;
  - selects an older, currently valid immutable publication, rotates the head
    ETag and appends an audit event. It never rewrites or republishes the target
    snapshot. Selecting a newer publication is a roll-forward and is rejected by
    this rollback endpoint.

Read operations require explicit `X-Tenant-ID` and `X-Env`. In corporate mode,
approval, publication and rollback derive tenant, environment and actor from the
server principal; header values are only hints and cannot replace authenticated
scope. Publication requires approved source definitions with append-only
approval evidence for each definition's current canonical hash and at least two
distinct composition approvers. Source hashes and authenticated approval
evidence become part of the immutable snapshot envelope.

## Approval-to-composition binding

The selected design is an explicit canonical composition manifest. Its digest
covers tenant/environment, owner and host contract, validity, approved source
hashes, the complete RuleSet and the host-governed admission catalog. Publication
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

This closes both maker-checker layers. Definition creation and intake require
`RULE_DEFINITION_AUTHOR`; draft/proposed authoring transitions use the same role;
approval, rejection, activation and retirement require
`RULE_DEFINITION_APPROVER`, whose authenticated actor must differ from the
persisted author. Composition approval, publication and rollback actors also
come from server authentication. Corporate mode fails closed unless the request
has the corresponding IAM role. Request payloads never supply governed actor
identity. Migration V36 persists definition approvals append-only and binds each
one to the exact canonical source hash used by snapshot publication.
Definitions created before this contract without `createdByType=authenticated`
fail closed and must be recreated or versioned through the authenticated author
flow; migration does not relabel caller-declared legacy identity as trusted.

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
