# Operational policy resolution for embedded hosts

Config owns governed definitions, publication and materialization history. `OperationalPolicyService.resolveOperationalPolicy(OperationalPolicyTarget, DomainRuleGovernancePrincipal)` exposes a public Java boundary for a colocated host. It does not add an HTTP endpoint or depend on Metadata Starter. Keep using the existing authenticated authoring/review/publication HTTP routes.

## Exact identity and outcomes

| Family | Layer | Artifact type | Artifact key | Definition parameters slot |
| --- | --- | --- | --- | --- |
| Approval | `approval_policy` | `resource-action-approval` | `resourceKey:actionId` | `approvalPolicy` |
| Workflow | `workflow_action` | `resource-workflow-action` | `resourceKey:actionId` | `availabilityPolicy` |
| Validation | `backend_validation` | `resource-validation` | `resourceKey` | `validationPolicy` |

The target validates an exact family pair and identifier grammar. An action ID cannot contain `:`. It does not discover whether a resource/action exists or whether an actor may use it; those checks belong to the host's canonical registry and authorization. Pass a principal already resolved from authenticated context. There are no header defaults in this reader.

`OperationalPolicyResolution` contains `resolutionState`, `target`, `resolutionFingerprint`, `policy` and `observedAt`. The policy exists only for an eligible applied head and includes definition/materialization IDs, definition version, source hash, typed effect and a defensive copy of the validated payload.

| State | Meaning and required consumer behavior |
| --- | --- |
| `NEVER_APPLIED` | No retained application or inconclusive history at this coordinate. Continue only if the operation's provider explicitly declares `ALLOW_IF_NEVER_APPLIED`; the lookup is still mandatory. |
| `ELIGIBLE_APPLIED_HEAD` | Exactly one applied projection, active source definition, matching identity, canonical payload and source hash. Evaluate its typed effect and every other applicable gate. |
| `PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD` | Retained application history but no current head, including retirement/reversion. Block and require a governed replacement. |
| `INCONSISTENT_OR_UNAVAILABLE` | Inconclusive legacy history, malformed or mismatched projection, ineligible/duplicate head, query/transaction failure or evidence bound exceeded. Block; never convert this to empty/initial absence. |

`ALLOW` only means this gate does not prevent the operation. It cannot override authorization, domain invariants, other policies, segregation of duties or version checks. `BLOCK` prevents the gate. This SDK does not implement bulk admission or migrate a host's existing policy resolvers automatically.

## Authoring and application

New creation/publication/application of the three operational families requires `effect: "BLOCK" | "ALLOW"` in the corresponding **definition parameters** slot. For example, `parameters.approvalPolicy.effect` produces `payload.approvalPolicy.effect`. This is an intentional beta authoring change: specialized rules in these families must declare BLOCK when publishing a restrictive policy; their specialized conditions and evaluators are preserved. Simulation/inspection of legacy definitions without effect remains possible.

The canonical producers derive the payload. A supplied payload must exactly match that projection; a supplied hash must match the derived hash. Both publication of existing drafts and explicit application recheck source, payload, hash and scope before replacing a head. Coordinates with surrounding whitespace are rejected at creation, not normalized after dispatch. Cross-resource overrides are not accepted as an operational policy; author the definition for its actual target resource.

An ALLOW definition has a deliberately narrow shape: no condition, approval requirements/groups, blockedWhen or unrecognized execution parameters, including false, zero, null or empty restrictions. Nested action descriptors carry only resourceKey/actionId, not hidden requirements. Publication governance such as `governance.requiredApprovals` remains applicable and is distinct from an execution requirement; ALLOW never bypasses author/reviewer/publisher checks. Unsupported specialized semantics must be rejected by the consuming bulk provider rather than ignored.

Historical payloads without effect resolve to conservative BLOCK **only when their identity, payload and source hash are verifiable**. Old hashes that cannot be reconstructed after JSONB normalization produce `INCONSISTENT_OR_UNAVAILABLE`. Do not guess the original object order or rewrite application events to make them pass. Publish an approved replacement definition with a distinct rule/materialization identity for the same target. Reusing a key whose source hash differs is a conflict, not an update of historical content. The new canonical hash applies only to these three families; other projection families keep their existing digest semantics.

## Snapshot, history and fingerprint

The reader uses the Config transaction manager, an independent read-only READ COMMITTED transaction and one exact PostgreSQL statement joining materializations, definitions and retained/orphan application evidence. It does not call the UX `materializations(...)` list, whose eligibility filtering would hide inconsistencies. A single statement observes either the committed old state or the committed new state, without mixing a head and definition from separate moments.

V55 retains uniqueness of an applied head; V63 retains irreversible application history and optimistic row versions. Never-applied drafts (`everApplied=false`) do not enter operational evidence or change the fingerprint of an active policy. Null history is inconclusive. Applied events with missing materializations remain evidence; ambiguous links, including different materialization keys or application hashes, block resolution.

The fingerprint binds scope, exact target, resolution state and deterministically ordered evidence identity/revision/status/source hash (including definition linkage and history flags). It excludes observedAt and the actor. The source hash separately binds canonical JSON source semantics and payload, surviving JSONB key ordering and numeric normalization. Neither digest is a signature against a database administrator or a coherent direct SQL rewrite. Governed services are the supported writer; database privileges, retention and restore must preserve that boundary.

The read bound is 4,096 relevant history rows. A 4,097th row produces an unavailable/inconsistent result; history is never silently truncated into permission. Individual returned JSON evidence over 8 Mi characters also blocks. These are failure bounds, not a throughput certification. The database still needs to build/read evidence; validate realistic history size, statement timeouts, pool capacity and latency before production. Returned policy data is for a trusted backend consumer and must not be exposed wholesale in user diagnostics or logs.

## Lifecycle serialization and transaction composition

`publish`, definition status changes, materialization status changes and workspace promotion acquire a PostgreSQL transaction-scoped advisory lock for tenant/environment before loading lifecycle inputs. The lock is held through the Config commit; all targets of one publication use that same scope. This closes apply-versus-retire and opposite-order replacement races without introducing another head table or FK lock cycles. Hash collisions can cause additional serialization, not cross-scope access.

These mutators require PostgreSQL READ COMMITTED and a Config persistence context **without pending external changes** on entry. A preloaded but clean definition/materialization is refreshed after locking in the three DomainRuleService entries, and scope is checked again. Workspace promotion uses pessimistic locking and the workspace optimistic version. Dirty external Config entities are rejected before autoflush can resurrect stale lifecycle state. Workspace promotion explicitly flushes its own inserted version under the mutex before calling the nested transition. Do not mutate managed Config entities in a caller and then invoke lifecycle services; keep those writes within the canonical methods. The host's separately owned domain persistence context is not refreshed or flushed by Config.

A reader never acquires the write mutex. An independent read may require an additional Config pool connection when called inside another transaction; size the pool accordingly. The snapshot does not lock business data or promise instantaneous revocation between the Config read and a later domain commit. The bulk orchestrator must bind/revalidate this evidence at its documented unit boundaries.

## Focused proof and adoption

```sh
mvn -B -ntp -Dtest=DomainRuleLifecycleConcurrencyPostgresTest,OperationalPolicyContractTest,OperationalPolicyServiceTest,DomainRuleServiceTest,DomainRuleChangeWorkspaceServiceTest,DomainRuleApplicationHistoryPostgresTest,DomainRuleConcurrencyResponseTest test
```

The lifecycle/snapshot proof runs real proxied services against PostgreSQL with canonical rule migrations through V63. The reference Quickstart's `GovernedColorPaletteHttpIntegrationTest` uses separate domain H2 and Config PostgreSQL stores. `-Dpraxis.palette.proof=true -Dpraxis.operational.policy.proof=true` additionally requires the candidate Java API and proves authenticated publication, withdrawal, replacement and resolution of all three families. Its deliberately small schema fixture proves the embedded boundary; Config's migration tests prove DDL/upgrade/guards. An isolated local SNAPSHOT and identical nested JAR prove candidate consumption, not a Maven Central release or completion of backend bulk readiness.
