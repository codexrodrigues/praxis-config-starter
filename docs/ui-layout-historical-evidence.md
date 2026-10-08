# Authorized historical UI layout evidence

Private candidate rc.167 reconsults the complete invocation and
READ_HISTORICAL_EVIDENCE after the final target/content access callbacks, before
returning documents. A normal callback return does not prove another grant or
context is still current. Direct and nested reads keep the same attempt and
their separate historical permission; removed targets remain historical evidence.
This recheck does not provide distributed fencing or atomic revocation/delivery.

`UiLayoutHistoricalEvidenceService.recover` is a read-only Java boundary for the original native
base B0, accepted customization C0 and exact revision patch of one frozen release. It does not create a draft,
update a head, calculate B1 compatibility or perform an upgrade. No HTTP endpoint or AI tool is
published by this addition.

## Host composition and visibility

Auto-configuration requires explicit `UiLayoutLifecycleInvocationProvider`,
`UiLayoutLifecycleAdmission`, `UiLayoutHistoricalEvidenceAccess` and the release/draft/member/
definition/revision/assignment repositories. Without the content-access provider no service bean
is registered. Direct construction with absent admission or content access fails closed.

Admission must explicitly permit `READ_HISTORICAL_EVIDENCE`; permission to read a current draft
does not imply this operation. The content-access provider must separately authorize **every**
historical target, including removed targets, and confirm visibility/safety of the complete native
documents, revision patch and opaque provenance for the actor. `requireTarget` is an explicit
preflight opt-in to this expanded content boundary and defaults to DENIED. Hosts must override
it and implement `require` for the complete B0/C0/patch evidence; an old document-only lambda
does not authorize patch recovery. Target and full content checks repeat before return.
Reject content that must not be exposed; do not
redact it and retain the original hash. Do not log or retain denied candidates. The starter does
not implement a domain-specific visibility policy or claim that every historical document is safe.

The requested root must still resolve through the host's currently confirmed invocation provider.
When a removed root cannot resolve, recovery fails closed. The service does not invent a current
composition to enable access. Child targets removed from today's composition remain in the
historical evidence only when explicitly admitted by the content provider.

## Integrity and result

The service resolves the requested release ID in exact tenant/environment/root scope, follows its
`sourceDraftId` to the retained RELEASED workspace, and verifies its freeze correlation and ETag.
A missing source link/workspace or legacy `{}` document is unavailable evidence; current HEAD is
never a substitute. Invalid envelopes/hashes and inconsistent references fail closed.

Historical targets must match all frozen members in order, target identity, revision, assignment
and contribution key. Selected definitions, revisions and assignments are checked using shared
workspace invariants; recovery also rehashes the persisted patches. Context and access are checked
again before returning. These checks do not provide distributed fencing or database snapshot
proof: records rely on the existing frozen-record immutability contract; real persistence and
concurrency still need downstream verification.

`UiLayoutHistoricalEvidenceReceipt` returns immutable refs and the host-approved native documents,
descriptors and stored hashes. It excludes audience selectors, actor fields, private grants and
JPA entities. JsonNode accessors return defensive copies. Failures use a constant sanitized
message and the lifecycle failure vocabulary; private provider/repository messages do not escape.
Hash coherence is not independent proof that the original external source was authentic.

Use the recovered evidence for a subsequent governed diagnosis with separately attested B1,
policy and native intent. A pin equal to B0 is not inferred from equality; ambiguous materialized
defaults still require review. A removed target is a composition conflict, not a reason to discard
history. Apply, approvals, publication and rollback remain separate lifecycle operations.

## Verification limits

Each target now carries `revisionPatch.contentHash` and `revisionPatch.document`, paired with
its frozen revision reference and pinned patch schema. The content is the same parsed patch
checked by the immutable-selection verifier, with no second revision fetch. Duplicate JSON
members, trailing values, non-object patches and source text over 256 KiB are rejected. Receipt
documents are defensive copies. Patch visibility is part of full content authorization; private
selectors, grants and actor metadata remain excluded.

A verified patch is a candidate source of authored intent, not an attestation of it. The patch
schema/owner must still establish what each operation means and whether it preserves explicit
pins, literal null or resets and the semantic B0-to-C0 relationship. No inferred delta or new
native intent format is introduced. Readiness retains its provenance and fencing warnings.

The producer audit confirms that Core transports caller-provided authoring and patch documents;
Table/Form widget editors currently save native widget inputs, without a governed revision
producer. These outputs must not be treated automatically as resolution patches. The current
resolver removes properties for null and replaces arrays as a whole, whereas native Form
bindings can use literal null for a disabled empty state. Executable resolver counterexamples
cover this distinction, whole-array replacement and explicit pin versus inheritance. They
characterize current behavior; they do not attest a native B0-to-C0 revision relationship.

Focused unit tests use mocked repositories/providers and cover access ordering, removed targets,
corrupt evidence, frozen membership and safe receipts. Auto-configuration tests check absent and
complete host composition. There is no real-database, historical HTTP or browser proof in those
tests. Hosts must supply and validate their real visibility policy before exposing this boundary.

## Validation context (B2c2)

The host must provide UiLayoutValidationBudgetPolicy; auto-configuration does not
invent a production default. Java structure/source/access SPIs take the shared
UiLayoutValidationContext as their last argument. Nested historical reads retain
the initiating attempt and independent historical grants. Actual beforeCommit
rechecks mutation acceptance; afterCompletion closes a participating attempt.
Read acceptance also rechecks current invocation/authority. See
[validation context](ai/contracts/ui-layout-validation-attempt.md) for migration,
purposes and cooperative enforcement limits. No HTTP payload or endpoint changed.
