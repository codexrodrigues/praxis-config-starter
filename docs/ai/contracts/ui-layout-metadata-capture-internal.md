# Internal baseline metadata capture value and codec

The package-private `UiLayoutMetadataCapture`, `UiLayoutMetadataCaptureCodec` and
`UiLayoutMetadataCaptureAccess` prepare an immutable **in-memory** value. They are
composed internally by the canonical lifecycle writer/store. They do not expose an HTTP raw-content contract. Real host capture and delivery to Angular remain pending.

The binding identifies an exact source draft, tenant/environment/root/target,
native descriptor, baseline source ref and existing canonical baseline digest.
The baseline is defensively copied. Structural metadata is retained as the exact
input String and SHA-256 of its UTF-8 bytes. The raw digest has a different domain
from `CanonicalJsonHashService.sha256Exact`, which keeps its existing semantics.
Source/publication, reproduction and historical context identities are declared
facts requiring independent host admission; storing them does not attest them.

`seal` validates an internal value; it is not a permission grant. It checks JSON
object syntax, duplicate keys, trailing content, valid UTF-8/Unicode and size,
then the baseline digest. It does not validate structural schema semantics,
resolve references, normalize fields, execute expressions or infer resource
operations. Foreign POJO/binary/missing nodes in the baseline are rejected before
serializing them. Ordinary JSON expression strings remain inert content.

`verifyAndRead` requires the exact expected binding and a server-supplied current
invocation in the same tenant/environment/root. Metadata visibility is denied
when the access callback is absent; the callback runs before validation and again
before return. Errors have a fixed message with no underlying cause or content.
Records and their nested identity values redact `toString`; authorized content
accessors intentionally retain complete content. The future service must resolve
and revalidate session/context and admit the producer/artifact/policy identities.
Two callback checks alone do not prove distributed fencing or session freshness.

Internal bounds:

- 256 KiB per raw metadata body, measured as UTF-8 bytes, without truncation.
- 64 container nesting levels, enforced locally for schema and baseline.
- Existing 256 KiB native baseline bound, independent of metadata.
- 1 MiB aggregate raw metadata bodies per composition; this excludes baseline
  copies and identifier overhead, so the future writer still needs a complete
  workspace/retention budget.
- Number tokens are limited to 256 characters by the local parser.

The composition check requires one source draft, tenant/environment/root and
actor/unit/context across captures, with unique targets and capture UUIDs. Policy
evidence may differ by resource. It does not know the authoritative registry and
cannot prove that all required targets are present. It is not an authorization
gate and must not be used to expose content.

Mapper constraints and duplicate/trailing-token settings apply only to a copied
mapper. No global Jackson settings, public DTOs, ETags, seed formats or lifecycle
behavior change. Missing historical captures must remain unavailable; the codec
cannot reconstruct them from the current API.

Validation is `UiLayoutMetadataCaptureCodecTest` plus the existing canonical hash
and workspace codec suites. These are controlled unit proofs, not storage,
producer provenance, host composition, browser or full B0/C0/B1 reconciliation.
The current package runs Maven `test`, not `package`/`install`: compiled test
classes contain the codec, while the previously packaged private JAR does not.

P3.2h.5c.6a prepares the [internal storage boundary](ui-layout-metadata-capture-storage-internal.md); producer/seed/writer composition is still pending.

P3.2h.5c.6b now composes this codec/store through the [canonical metadata writer](ui-layout-baseline-metadata-writer.md); real producer/DB/runtime-delivery proof remains pending. Source/reproduction/observation types are owned by the public server-only UiLayoutBaselineMetadataSeed.

Origin now uses the sealed Source/Reproduction variants from UiLayoutBaselineMetadataSeed: OperationSource/SchemaProjection and NativeDocumentSource/NativeIdentity. Raw input is mandatory for both. Native identity is compared against B0 after strict parsing, preserving key/array order. Wrong pairs and native input divergence are denied; no source attestation follows from codec success.

The [canonical exact hash boundary](ui-layout-canonical-exact-hash.md) fixes host-dependent identity in 6q/R1. Capture's private mapper alone does not protect the hash service from host null/escaping defaults. Historical digests created under divergent settings are not rewritten or accepted through fallback. The packaging statement above describes the original 6-stage snapshot; later private writer builds package the capture boundary and are tracked by their own verification artifacts.


## Private JSON mapper and downstream bootstrap (6v)

Capture parsing and baseline size measurement use a fresh private JSON mapper. Host modules, tree serializers, null omission, ASCII escaping and property-sort defaults are not inherited. Duplicate/trailing input, syntax/Unicode/depth/size checks remain strict. The constructor retains its existing signature; closed JSON does not require domain serializers.

A real automatic lifecycle activation test on Quickstart/Jackson 2.15.4 exposed a NoSuchFieldError for WRITE_PROPERTIES_SORTED in rc.160. The rc.161 candidate removes the feature reference through the private mapper boundary, without dependency overrides or version branches. Test both an auto-created writer/controller on the actual host baseline and hostile host serializers; manually constructed read services do not exercise writer/store bootstrap. Private candidate validation is not public release evidence.
