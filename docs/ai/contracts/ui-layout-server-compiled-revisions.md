# Server-compiled native revisions

Config is the canonical owner of contribution compilation, admission and
persistence. `POST /api/praxis/config/ui-layouts/drafts/{draftId}/revisions` now
accepts `commandRef`, `target`, `authoringDocument` and `reason`. The authoring
document is complete raw C0. Client `patchDocument`, baseline and descriptors are
unknown fields and return `400 INVALID_REQUEST`. There is no compatibility mode.
This is a breaking beta request change; response receipts, context, ETag and
persisted historical patch format are unchanged.

The command resolves its authenticated invocation and admitted editable workspace
within the existing Config transaction. It obtains target descriptors and original
pinned B0 from that workspace, selects the native projection, and compiles once.
The previous working document is never the compilation baseline. The host receives
original B0, raw C0 and the derived patch for validation; the common relation guards
then verify reproduction before revision/draft writes. Authority, context and
budget checks, including beforeCommit, remain mandatory.

Every property present in C0.config contributes an authored pin, including values
equal to B0. Absence of a B0 property contributes a null reset. Objects recurse;
arrays replace atomically. A later revision contains the full contribution against
B0, so a reset retained across two edits is not lost. The selected immutable patch
and raw working document are persisted together; prior assignment selection is
cleared and the draft ETag rotates. Freeze uses the selected revision and assignment.

Mappings are exact: Table editor 1 / praxis-table and Form editor 1 /
praxis-dynamic-form, with patch schema version praxis.ui-layout/v1. Table requires
compact columns:[] and columnProjection.source=schema, and rejects literal null
throughout config. Form rejects null in merged objects while preserving null
inside atomic arrays. Native envelope and bindings cannot change. Unsupported
formats fail SOURCE_UNAVAILABLE; materialized or malformed native documents are
not converted. Historical read/freeze/publication keep their existing relation
and admission checks without recompiling history or recapturing a baseline.

The wire/text quota remains 1 MiB, native document quota 256 KiB/depth64 and
request wrapper depth65, numeric tokens 256 characters. Generated patch output
has its own 256 KiB quota: a valid small candidate can still fail because reset
keys enlarge its contribution. HTTP body overflow returns sanitized 413; candidate
or output bounds return INVALID_REQUEST, baseline bounds INVALID_STATE, and
native policy/envelope violations VALIDATION_FAILED. Nothing is persisted on failure.

Core transports a defensive JSON snapshot and exact context/If-Match. Table/Form
builders retain owner diagnostics and raw authorship, and no longer compile a
patch. Finite corpora keep candidate-only commands and independent explicit
`expectedPatch` values outside the request; those are fixture assertions, not
client runtime compilation or admission evidence.

No UI library, JavaScript producer or JSON materialization runs in Config. The
compiler walks JSON data; JSONLogic business validation remains its separate
backend responsibility. Lab baselines/admission and Form activation are unchanged.
The materialized Table lab baseline still cannot create a compact native revision.

Local source and fixture/downstream proof do not grant integrated acceptance.
Official browser runtime, native source admission, live IAM/PostgreSQL and release
gates remain separate. See [the cutover map](ui-layout-server-compiled-revision-cutover-plan.md).

## Host-generated OpenAPI

The revision body is documented as `UiLayoutRevisionCommandRequest`, rather than
the controller's raw String transport. Its four fields are required, and unknown
request properties are forbidden by the decoder and by `additionalProperties=false`.
`ConfigOpenApiAutoConfiguration` registers a documentation-only ModelConverter
when Swagger Core and Springdoc's registrar exist in the host. It preserves the
explicit FALSE declaration on Config DTOs under OpenAPI 3.0, where the reference
host's Swagger Core 2.2.22 resolver otherwise omits it. It does not close arbitrary
authoring JSON, alter other owners' DTOs, mutate Jackson configuration, or supply
documentation runtime dependencies to hosts. The Swagger Core compile dependency
is provided; the host owns its documentation stack. No configuration flag or
global converter registration is needed. The Quickstart proof reads the actual
Springdoc MVC document, alongside parser-negative tests, without a server socket.

## Shared Java structural checks for hosts

`UiLayoutNativeStructureChecks.requireBaseline` and `requireRevision` expose the
existing bounded native projection and reproduction checks as one minimal Java
surface. Baseline accepts AUTHORING/FROZEN_RELEASE/EVOLUTION_CURRENT_READ; revision
accepts AUTHORING/FROZEN_RELEASE. Both retain the exact validation attempt and
invocation, independently bound original/candidate/patch, and leave inputs intact.
Revision validates the supplied immutable patch; it never recompiles history.
Unsupported formats fail closed. These checks do not confer composition membership,
source provenance, operation permission or host presentation admission. They cover
the JSON invariants currently mapped in Config, not all frontend renderer semantics.
No Spring bean, JavaScript runtime or global host default is added. A host's
`UiLayoutLifecycleStructureValidator` can delegate common checks and retain its
specific policy. Quickstart's inactive Compras policy demonstrates this separation;
Form remains unadmitted and its materialized Table baseline remains unchanged.
