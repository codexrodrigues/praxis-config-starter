# Domain-rule host status v1

## Purpose

This surface answers whether the hosts expected to execute a RuleSet are currently ready and
aligned with the server-owned active snapshot head. Config remains the control-plane authority.

## Write contract

`POST /api/praxis/config/domain-rules/snapshots/host-status`

Required role: `RULE_EXECUTION_OBSERVER`.

The host reports `ruleSetKey`, its loaded snapshot key/hash/revision, readiness, host contract,
engine contract, JSON Logic dialect, normative corpus SHA-256, admitted implementation-catalog
digest, an optional bounded failure code and `observedAtUtc`. Tenant, environment and internal host
identity come exclusively from the server principal. Older or exact replays do not regress the
current row. A ready report without the complete compatibility evidence is rejected.

## Read contract

`GET /api/praxis/config/domain-rules/snapshots/head/host-status-summary?ruleSetKey=...`

Required role: `RULE_SNAPSHOT_READER`.

Config resolves the active immutable snapshot and mutable activation revision, then returns mutually
exclusive aggregate counts: `alignedHosts`, `snapshotDriftedHosts`, `incompatibleHosts`,
`unavailableHosts` and `staleHosts`. Snapshot drift is evaluated before runtime compatibility;
staleness takes precedence over every other state. The response also includes the compatibility
coordinates expected by the active approved snapshot, total hosts, last observation and the stale
cutoff. The default cutoff is `PT2M`, configurable with
`praxis.config.domain-rules.host-status.stale-after`.

## Boundaries

- Heartbeat delivery is outside the evaluation transaction and cannot make evaluation fail open.
- The browser receives no host actor, hostname, IP, facts, decision input or snapshot payload.
- Execution observations remain the source for outcome counts; they do not imply host readiness.
- Host compatibility is derived against coordinates already governed by the immutable RuleSet and
  approved composition manifest; the heartbeat does not create a second compatibility authority.

## Multi-host drill

`DomainRuleHostStatusServiceTest#drillsMultipleHostsAcrossAlignmentSnapshotDriftRuntimeIncompatibilityUnavailabilityAndStaleness`
proves five concurrent host states in one scoped RuleSet. The categories are mutually exclusive and
sum to `totalHosts`. This deterministic drill does not replace PostgreSQL/HTTP/load evidence for a
release deployment.
