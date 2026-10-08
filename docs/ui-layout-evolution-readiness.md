# Advisory B0/C0/B1 readiness

Private candidate rc.167 checks historical evidence and current content access
after the final native validation, then reconsults the complete invocation and
DIAGNOSE_EVOLUTION before accepting the receipt. The same attempt/budget spans
all these checks, without recapturing the source or requiring CREATE_DRAFT.
The final-check/delivery gap remains; this advisory snapshot is not IAM fencing.

`UiLayoutEvolutionReadinessService.diagnose` reads historical B0/C0 through the authorized
historical evidence service and current B1 through the existing native workspace source.
It is the composition/contract preflight of evolution, not field-level semantic reconciliation.
There is no HTTP endpoint, candidate, resolution command or apply operation in this addition.

## Evidence and admission

The host must explicitly admit `DIAGNOSE_EVOLUTION` and provide
`UiLayoutCurrentEvolutionEvidenceAccess` for every current target/content. Historical access
remains separately governed by `READ_HISTORICAL_EVIDENCE` and the historical content provider.
Absent admission/access denies direct construction; absent host dependencies prevent bean
registration. `UiLayoutLifecycleStructureValidator` is also mandatory for readiness registration.
The Java constructor now requires that existing validator; hosts must supply it explicitly.
Targets are matched by their canonical type/id, never labels or array position.

The existing workspace codec checks that B1 contains the exact registered targets in order,
with complete descriptors, object documents, canonical hashes and bounded size. The source
attests the documents; a hash is not independent authentication of that source. Source or access
failure produces a sanitized lifecycle error and withholds the whole result.

After current content authorization, the service invokes `validateTarget` and
`validateAuthoringBaseline` for every B1 target, then `validateReleaseTargets` for the complete
current composition. It repeats native validation before returning. Missing validation,
invalid native input or rejected composition policy withholds the entire receipt. Validators
receive defensive document copies. Hosts must provide concrete native validation: a mock
passing this orchestration test does not establish Table/Form semantic conformance.
Historical removed targets and B0/C0 are not submitted as current B1: interpreting an old
contract under its native semantics remains a separate step.

Context/current access and historical evidence/access are checked again before return. This
read-only snapshot does not prove source fencing or eliminate TOCTOU. It cannot authorize a
commit, even if every document hash remained equal.

## Readiness reasons

| Code | Meaning |
| --- | --- |
| `TARGET_REMOVED` | A historical target is absent from the current composition; its history stays in the result. |
| `TARGET_ADDED` | A current target has no B0/C0 in the requested historical release. |
| `NATIVE_CONTRACT_CHANGED` | Native authoring or public patch descriptor changed; compatibility needs its canonical owner. |
| `INTENT_PROVENANCE_UNVERIFIED` | This preflight has not verified native intent provenance; a complete document/hash alone does not distinguish explicit pins from materialized defaults. |
| `SOURCE_FENCING_UNAVAILABLE` | This capture API offers no commit fence for current base/policy; apply remains unavailable. |

The receipt contains the ordered target union and hashes, not native values, source refs,
private selectors or grants. `applyAllowed` is always false. Equality B0=C0=B1 does not suppress
the provenance warning: an explicit pin can equal its baseline.

## Native owner findings

The Table and Dynamic Form widgets distinguish authored `inputs.config` from
`context.effectiveConfig`. Form save projects changes over authored config, preserving existing
pins and removing overrides explicitly. Table keeps schema selection, order and overrides
distinct in its native projection. Their complete editor documents are sufficient for native
apply/round-trip, but mere property presence in an old snapshot does not attest intent.

No alternate native intent schema is introduced here. This warning does not classify every historical
record as lacking intent: native contracts may already represent explicit overrides. The next semantic
diagnosis must verify those existing semantics and owner-attested provenance, preserving null/reset,
explicit pins, historical allowlist ambiguity and stable layout IDs. It must surface removed
fields, type incompatibility and current authority restrictions; renderer recovery is not a
resolved upgrade conflict. The documentary P3.0 corpus remains outside this preflight suite.

Focused tests cover readiness/admission, native validator orchestration and composition. Real historical persistence, host
visibility policy, fenced apply and browser proof remain independent downstream gates.

## Historical Table document relation diagnosis (P3.2h.5c.1)

The preflight reuses the supported revision reproduction gate for historical
`praxis.table.editor` evidence after historical/current content authorization.
`NATIVE_DOCUMENT_RELATION_REPRODUCED` means only that the supported historical
B0 and patch reproduce C0 and preserve all authored presence, including pins
whose value equals B0. `NATIVE_DOCUMENT_RELATION_UNVERIFIABLE` means the native
version or document relation cannot be verified by that gate. Rehashing an
inconsistent patch does not establish this relation. Both codes expose only a
target identity, never document values or validation reasons.

The historical descriptor selects this gate, including for a removed target.
A changed B1 descriptor remains a separate `NATIVE_CONTRACT_CHANGED` diagnostic;
current-schema validation is not used to reinterpret historical documents.
Unknown native families retain their previous provenance warning without an
invented Table interpreter. The historical recovery API still returns authorized
immutable evidence; this addition does not rewrite or filter that evidence.

A reproduced relation still carries `INTENT_PROVENANCE_UNVERIFIED` and
`SOURCE_FENCING_UNAVAILABLE`, and `applyAllowed` stays false. Do not display this
as "upgrade compatible" or "ready to apply". Suggested product meaning is
"Historical document relation reproduced; source and current-schema review
pending", or "Historical relation requires review". Canonical UI materialization,
i18n, keyboard/focus and integrated browser validation remain future gates.

The workspace source currently retains native authoring document descriptors,
sourceRef and document hashes, but no correlated immutable FieldDefinition
snapshot. ApiMetadata release-scoped schemas are existing platform evidence;
they are not automatically the schema materialized by Table or a historical B0
snapshot. Resolve and admit that binding before field-level B0/C0/B1 diagnosis.
No endpoint, native format, source provider, candidate or apply path is added.

## Validation context (B2c2)

The host must provide UiLayoutValidationBudgetPolicy; auto-configuration does not
invent a production default. Java structure/source/access SPIs take the shared
UiLayoutValidationContext as their last argument. Nested historical reads retain
the initiating attempt and independent historical grants. Actual beforeCommit
rechecks mutation acceptance; afterCompletion closes a participating attempt.
Read acceptance also rechecks current invocation/authority. See
[validation context](ai/contracts/ui-layout-validation-attempt.md) for migration,
purposes and cooperative enforcement limits. No HTTP payload or endpoint changed.
