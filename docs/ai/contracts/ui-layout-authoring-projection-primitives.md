# Internal authoring projection and JSON bounds primitives

2026-10-06. Implemented in source and now wired into the lifecycle command by
the [coordinated candidate-only cut](ui-layout-server-compiled-revisions.md).
Table native guard also rejects integer versions outside int range.

UiLayoutAuthoringProjection selects Table/Form by pinned documentType and reuses
requireNative checks from the existing guards for exact target/versions, native
structure, envelope and null policy. Host schema refs are preserved; this data
projection is not a grant, schema attestation or provenance proof. Unsupported
types fail SOURCE_UNAVAILABLE, without root/config inference. Independent config
snapshots and defensive accessors isolate mutable inputs/outputs. compilePatch
calls the one shared UiLayoutAuthoredPatchCompiler.

UiLayoutJsonBounds walks exact built-in JSON node types iteratively before
serialization/copy, rejects cycles, nonfinite numbers, malformed Unicode,
foreign/POJO/binary nodes and depth over 64. Shared acyclic subtrees remain valid.
It measures strict compact JSON using a counting output sink capped at 256 KiB,
without constructing a complete output byte array. UTF-8 and escaping both count.
Number tokens are capped at 256 characters, with a preflight on big integer or
decimal magnitude before converting their token to text.

Document roots must be objects. Invalid baseline data/bounds return INVALID_STATE;
candidate/input/output bounds return INVALID_REQUEST. Native envelope/null/shape
checks remain VALIDATION_FAILED. Checkpoint exceptions, including budget expiry,
keep their lifecycle code through serialization. The callback is required by the
projection; the command binds it to its existing invocation
validation context. Tests use deterministic callbacks, not a grant.

Compiler now preflights config inputs and calculates the exact compact derived
patch size before allocating the patch tree: escaped field names, colon/comma,
object braces, atomic arrays/scalars and B0-only null resets all count. Oversized
output fails without partial return or mutation. It then builds the patch and
checks output bounds and the existing authored-presence/reproduction relation.
Equal pins, native null policies, raw authorship and persisted hash semantics
are unchanged. No defaults/materialization or another merge engine is added.

The subsequent revision-entry package connects the 1 MiB UTF-8 text bound to
the revision decoder and direct createRevision input, using a shared closed
parser. Scoped MVC RequestBodyAdvice checks actual wire bytes before String
conversion; wrapper depth 65 and sanitized 413 are implemented in source.
Projection and patch compilation are now wired. No global mapper was changed.

Focused proof: 165 cases across 11 suites, 20 new in bounds/projection. Includes
exact byte/+1, multibyte/escaping, depth64/65, cycles/shared trees, hostile nodes,
Unicode/numbers, reset-output growth, data isolation, finite Table/Form corpora,
version overflow/fraction, budget callbacks, guards and their service callers.
No HTTP/PG/browser proof, corporate capacity benchmark or new artifact. Frozen
rc.167 does not contain these source changes.

Entry enforcement was proved separately on private rc.168; the coordinated
candidate-only request and command use private rc.169 and the final OpenAPI
correction uses private rc.170 for downstream validation.
See [design](ui-layout-admitted-authoring-projection-plan.md),
[compiler](ui-layout-authored-patch-compiler.md) and
[input boundaries](ui-layout-json-input-boundaries.md).
