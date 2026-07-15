# Rule snapshot control plane v1

## Decision

`praxis-config-starter` persists immutable `PublishedRuleSnapshot` envelopes from
`praxis-rules-engine` and owns only their publication lifecycle. It does not load
or execute domain-host Java implementations. During publication it uses the
engine's planning-only registry to validate exact implementation coordinates and
the deterministic decision graph.

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

## HTTP contract

- `POST /api/praxis/config/domain-rules/snapshots`
  - initial publication requires `If-None-Match: *`;
  - later publications require a strong `If-Match` with the current head ETag.
- `GET /api/praxis/config/domain-rules/snapshots/head?ruleSetKey=...`
  - returns `Cache-Control: no-cache` and the mutable head ETag;
  - accepts `If-None-Match` and may return `304`.
- `GET /api/praxis/config/domain-rules/snapshots/{snapshotKey}`
  - returns the immutable snapshot with its canonical content hash as ETag;
  - is private-cacheable and immutable.
- `POST /api/praxis/config/domain-rules/snapshots/{snapshotKey}/rollback`
  - requires a strong current-head `If-Match`;
  - selects existing immutable content, rotates the head ETag and appends an
    audit event. It never rewrites or republishes the target snapshot.

All operations require explicit `X-Tenant-ID` and `X-Env`. Publication requires
approved source definitions with approval timestamps and at least two distinct
approvers. Source hashes and safe approval evidence become part of the immutable
snapshot envelope.

## Compatibility and ownership

This contract raises the starter baseline to Java 21 because the canonical
engine contract is Java 21. Duplicating engine DTOs or persisting an untyped JSON
facsimile would create a second source of truth and was rejected. Hosts use the
public `DomainRuleSnapshotReader` boundary for in-process loading, then compile
with their executable registry before atomic activation.

The planning catalog and executable registry must resolve the same exact
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
