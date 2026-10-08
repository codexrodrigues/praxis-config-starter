# Admitted authoring projection and JSON budget — implementation plan

2026-10-06. Original design; implementation checkpoint:
[internal projection and bounds primitives](ui-layout-authoring-projection-primitives.md)
are now implemented in source. The subsequent entry package connects revision
HTTP stream quota, wrapper depth 65 and sanitized 413 plus direct Java parsing.
Projection/compiler command wiring and the candidate-only public migration are
now implemented locally; [current contract](ui-layout-server-compiled-revisions.md).
The design discussion below records the original sequencing; candidate+patch
references describe the prior request, not a supported compatibility path.
Private rc.168 proved these primitives/entry bounds
downstream on Quickstart/Jackson 2.15.4, with automatic MVC composition and host
filters; not live IAM/PG/browser proof. The coordinated cut is specified in
[the server-compiled revision plan](ui-layout-server-compiled-revision-cutover-plan.md).

## Internal projection contract

Config owns a package-private projection selector and native data adapters.
Inputs are the server-resolved target, pinned authoring/patch descriptors and
B0 native document, plus original raw C0. No client-supplied baseline, descriptor
override, projection path or null policy is accepted. No new public SPI, DTO
field, registry service or endpoint is needed for the initial Table/Form scope.

The proposed projection value contains independent ObjectNode snapshots of
B0.config and C0.config, produced only after bounded data checks. Constructor
and exposure must prevent mutable aliasing. It is short-lived and internal,
not serialized/persisted, a grant, a provenance token or a schema-validation
receipt. Do not detach it from the invocation and use it to skip revalidation.

Selection uses the combination of registered target.componentType,
descriptor.documentType, descriptor.schemaVersion and patch schemaVersion.
Schema refs remain the exact host-attested refs pinned in the workspace; they
must not be guessed from fixture URNs, hardcoded to lab refs or rewritten.
Host metadata admission and baseline hash/source checks precede acceptance.
The request cannot swap candidate descriptors; candidate uses the pinned ones.

| Selected mapping | Document checks before projection | Null policy |
| --- | --- | --- |
| praxis-table + praxis.table.editor + 1 + praxis.ui-layout/v1 | Object, exact kind, integral version exactly 1 within int range, config object; existing compact schema columnProjection and identity checks on B0/C0; unchanged non-config envelope | Reject null anywhere in C0.config, including arrays |
| praxis-dynamic-form + praxis.dynamic-form.editor + 1 + praxis.ui-layout/v1 | Object, exact kind, integral version exactly 1 within int range, config object; unchanged non-config envelope; preserve raw input | Reject null in merged objects; preserve every value inside atomic arrays |

This mapping does not assert that every host schemaRef is supported or every
Form target is authorized. Host structure validation still receives the exact
descriptors, B0, C0 and generated patch. A data adapter only validates/selects
the projection; there is one UiLayoutAuthoredPatchCompiler and one canonical
merge/reproduction relation. Extract native checks from current guards for
reuse by projection and existing reproduction guards, rather than copy them.
Do not call a guard with an empty fabricated patch just to validate C0.

Unmapped descriptors or mismatched target/version fail SOURCE_UNAVAILABLE for
new compilation. Invalid native shape/envelope/null/reproduction fails
VALIDATION_FAILED. No root/config fallback, aliases for old Table documents or
automatic Page support. History/codec/capture can retain synthetic/older
descriptors; this new write-time mapping must not rewrite stored evidence.
Old native guards currently skip other documentTypes; that skip cannot serve
as admission for a new compiler selector.

The earlier Table asInt-only predicate finding was fixed by native-check
extraction: it now requires canConvertToInt and exact version 1, including focal
tests for overflow/fraction. This is source/test evidence, not a runtime exploit.

## Proposed limits and counting rules

The entry quotas are now implemented in source; command projection/compilation
and the candidate-only migration remain design. No capacity benchmark or live
host enforcement has been proved. Preserve the existing 256 KiB serialized
document budget. Revision parsing reuses depth 64 and number-token length 256,
without equating the
two entry points. The HTTP wire budget is a new, separately scoped choice.

| Boundary | Proposed maximum | Where to enforce |
| --- | --- | --- |
| Revision HTTP body | 1 MiB actual bytes read | Bounded request stream before framework String allocation; Content-Length only an early hint, actual bytes authoritative, including chunked input |
| Direct Java revision input strings | 1 MiB UTF-8 per authoring/patch input text | Before readTree; String already exists, so this is not pre-allocation protection |
| B0 native and C0 native documents | 256 KiB each in strict compact JSON UTF-8 | Before recursive adapter inspection, deepCopy, hash or compilation, using bounded measurement |
| Generated patch | 256 KiB strict compact JSON UTF-8 | Incremental bounded generation/measurement plus final check before validation/persistence; resets count |
| B0/C0/patch container depth | 64, root object/array is depth 1 | Strict parse constraints and iterative tree validation for programmatic nodes before recursion |
| Revision body container depth | 65 | One request wrapper around a native document of depth 64; validate documents independently too |
| Numeric token length | 256 characters | Closed parser and programmatic numeric-value checks |

Strings/property names may occupy the document byte budget; lexical token limits
must not exceed their enclosing text budget. Token length and UTF-8 byte length
are different measures. Reject malformed Unicode/unpaired surrogate data and
non-JSON/foreign nodes before serialization or recursive work, reusing the
existing capture syntax conventions where their semantics match. Do not change
the global host ObjectMapper or import capture provenance rules into C0 input.

The wire/text limit intentionally caps whitespace/escape overhead independently
of compact document size. A semantically small but over-budget raw body is
rejected, not normalized to bypass the wire budget. The 1 MiB body is sufficient
for ordinary compact current candidate+patch submissions (two 256 KiB trees
plus a bounded envelope), but does not promise acceptance of all arbitrarily
escaped/whitespace-expanded representations. Its acceptance and error mapping
need boundary tests before rollout. No benchmark establishes optimal throughput.

For a future candidate-only request, keep independent patch output quota: a
tiny C0 can remove many keys from B0 and produce a large patch. B0 and C0 each
fitting 256 KiB does not prove their derived patch fits 256 KiB. Reject oversized
output without truncation, dropping resets/pins or persisting a partial result.
Avoid constructing unbounded byte arrays just to measure input or output:
preflight the tree iteratively and count serialization through a bounded sink.
Compiler and copy/reproduction passes must share bounded prerequisites.

Use INVALID_REQUEST for candidate/body/internal input syntax or size/depth
failure and generated-patch quota failure; sanitize the message. Stored B0
violating integrity/bounds is INVALID_STATE, not a rewritten baseline. A
pre-controller body-size rejection uses a sanitized 413 response; it needs
explicit controller/advice/docs tests, and must not expose payload. Context,
permission, target and ETag failures retain their current vocabulary/statuses.
Do not add quota constants to HTTP examples before implementation/proof.

No new timeout value is selected here. Reuse the invocation's monotonic
UiLayoutValidationAttempt and host budget policy, with checks before/after
projection/compilation and during bounded traversal. call/run already checks
before/after but does not interrupt blocked work or bound parsing allocations.
Do not describe those calls as worker cancellation, transaction timeout or
corporate capacity proof. Parsing happens before the command attempt exists;
wire/parse bounds provide that separate entry control.

Workspace/composition total-size policy is outside this revision-only change.
The existing 1 MiB capture-text aggregate is not a universal workspace limit.
Reads/history/frozen/internal low-level writes need an entry-by-entry follow-up;
do not assert that changing this command secures every lifecycle service method.

## Integration order after primitives are proven

1. HTTP stream/text bound and closed decoder parse; check C0 JSON shape and bounds.
2. Start/retain existing invocation budget and authorized Config transaction;
   validate EDIT_DRAFT, context and strong If-Match; read pinned workspace/B0.
3. Verify current metadata/source/hash/descriptor admission and B0 bounds;
   select exact projection adapter and verify envelope/native/null policy.
4. Compile once from B0.config and raw C0.config under bounds/budget. Never use
   previous working as B0; never substitute a normalized effective document.
5. Validate original B0/C0 and generated patch with host structure/schema;
   verify shared native reproduction. Recheck budget/authority at acceptance.
6. Persist immutable patch revision and raw working document/selection together
   through the existing transaction, retaining hash semantics and ETag rotation.
   Candidate/output validation failures occur before writes; final failures roll back.

Until the coordinated public migration, current command/client patch flow stays
unchanged. Proving an internal projection does not permit silently replacing or
ignoring the supplied patch in today's request. No feature flag or dual command.

## Minimal implementation packages and gates

First package: projection selector/native-check reuse and internal JSON bounds
primitives with focused tests; no operational wiring or request change. Read
guards, compiler and capture codecs before sharing code; keep one algorithm.
Test descriptor/target/schema/version mismatch, changed envelope, null policies,
Table compact constraints, old flat/Page/unmapped inputs, strict version range,
input/output copy independence and unchanged current guards/corpora.

Bounds tests must cover exact limit and +1 byte, multibyte UTF-8, escaped text,
depth 64/65 with wrapper offset, numeric tokens, foreign/programmatic nodes,
malformed Unicode, patch growth due to many removals and budget expiry. Quota
tests must assert failure before mutation, without huge hostile allocation.

Second package: canonical scoped HTTP stream enforcement and decoder/internal
entry integration under current request, including declared/chunked sizes and
host security/filter ordering. Requires public error/docs impact plan, focal
Config HTTP tests and downstream Quickstart proof. No current HTTP filter is
introduced by this design document.

Then coordinated candidate-only public cut: DTO/decoder/controller/RevisionInput,
Core model/transport, Table/Form producers/fixtures, docs and derivatives from
the inventory. Remove duplicated TS patch compilation in that same cut; keep
raw authoring safeguards and interactions. Preserve synthetic codec/history
tests, migrate revision fixtures to valid native contracts, and retain expected
patches separately from request-shaped fixture commands.

The known lab Table baseline has six materialized columns/no columnProjection;
it is not evidence of compact native B0. Do not fix that by heuristic conversion
or bypass a refused baseline publication action. Form availability, operational
Save, host/PG/browser proof, publication and rebase remain separate gates.

See [compiler](ui-layout-authored-patch-compiler.md),
[input boundaries](ui-layout-json-input-boundaries.md) and
[shared relation](ui-layout-native-revision-relation.md).
