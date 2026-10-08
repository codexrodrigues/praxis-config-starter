# UI layout workspace foundation

The Config workspace codec stores an exact native baseline and a working
document with separate content hashes, native authoring/patch descriptors and
host-registered target identities. `UiLayoutLifecycleInvocation` is a
server-resolved scope; it is not an HTTP request contract. The seed sourceRef
identifies the host source but does not by itself provide a historical resolver.

`CanonicalJsonHashService.sha256Exact` preserves explicit null properties,
including nested objects. It distinguishes an omitted native binding from
`emptyState: null`. Object key order does not change the digest; array order
does. The existing `sha256` contract continues to omit null object properties.
Hashing does not interpret null: native document and patch codecs own its
meaning, which may differ between contracts.

The codec copies input/output documents defensively, rejects unknown properties
and duplicate JSON keys, checks baseline and working hashes independently and
enforces the 256 KiB limit per native document. Current decode requires the
historical target list to match the current registration exactly. A target
removed from registration is rejected; this entry point cannot be used to
silently recover an old workspace under a changed composition.

The isolated integration also includes the canonical resolution engine,
composition reader, host-owned admission/policy seams and endpoint-local strict
request decoder. The reader fixes one release snapshot, requires authorization
for all registered targets and rejects a partially authorized composition.
Admission/policy defaults deny access; decoding JSON is not authorization.

The official `UiLayoutResolutionAutoConfiguration` is registered in the starter
imports. Controllers, repositories, allocation locks and release/head commands
are integrated; host-owned invocation, admission, structure/release validation,
workspace source, explicit host validation budget policy and Config transaction
dependencies govern their availability. The shared validation context crosses
source/structure/access SPIs, nested evidence reads and actual beforeCommit;
see [validation context](ai/contracts/ui-layout-validation-attempt.md) for the
clean Java migration and cooperative enforcement limits. No operational budget
default or executor is supplied by the starter.
Missing authoritative audience providers fail closed, and the lifecycle writer
is not materialized with incomplete host dependencies. No database mutation is
performed by the codec itself.

The main migration catalogue keeps `V63` domain-rule history unchanged. Layout
creation and workspace idempotency are `V64` and `V65`. A local laboratory with
a different V63/V64 history cannot consume this catalogue without reconciling
its own Flyway history. The isolated integration does not rewrite or migrate
that laboratory database.

See [authoring workspace](ui-layout-authoring-workspace.md) and
[effective composition](ui-layout-effective-composition.md) for lifecycle and
host contracts. Registration and mock HTTP checks are not downstream runtime
acceptance, and this lifecycle does not yet implement governed rebase.

The resolution engine applies the Config merge-patch contract: null removes an
object property and arrays replace the whole value. This is distinct from a
native Form document where `bindings.emptyState: null` is a literal disable
value. Neither codec may silently reinterpret the other's null semantics.

Baseline and working values do not alone prove authored intent. An existing
Form pin can equal the baseline; a materialized snapshot can contain the same
value without a pin. A future governed diagnostic must consume authorized
historical evidence and the native authored projection, preserve original
ambiguous documents for review and revalidate current authority. It must not
derive an automatic preservation recipe from a generic JSON diff.

Focused checks: `UiLayoutDraftWorkspaceCodecTest`,
`CanonicalJsonHashServiceTest`, `AiRegistrySnapshotContractTest`,
`UiLayoutResolutionServiceTest`, `UiLayoutCompositionReadServiceTest` and
`UiLayoutLifecycleRequestDecoderTest`. The versioned resolution corpus tests
overlay behavior; it is separate from the planned upgrade/rebase corpus.
Local checks do not establish HTTP/PostgreSQL, downstream runtime or rebase
acceptance.
