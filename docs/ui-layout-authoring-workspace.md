# UI layout authoring workspace

The Config Starter owns the persisted, governed workspace behind the existing
`/api/praxis/config/ui-layouts/drafts` routes. It does not own Table, Form or Page Builder codecs.
The host registers the exact composition, captures each native authoring document through
`UiLayoutDraftWorkspaceSource`, and validates it through `UiLayoutLifecycleStructureValidator`.

This is a beta contract replacement. There is no legacy request alias, `/workspace` route, client
member list, or client-selected revision reference.

## Public workspace shape

`POST /drafts` and `GET /drafts/{draftId}` return:

```json
{
  "schemaVersion": "praxis.ui-layout-draft-workspace/v1",
  "draft": {
    "draftRef": "5f8db0ca-0f34-4e74-836b-1913560547d0",
    "rootTarget": {"componentType": "praxis-dynamic-page", "componentId": "orders-page"},
    "state": "DRAFT",
    "draftEtag": "a2160431-d6ca-4dc8-8b2b-bd23491fba09",
    "createdAt": "2026-09-10T18:00:00Z",
    "updatedAt": "2026-09-10T18:00:00Z"
  },
  "targets": [{
    "target": {"componentType": "praxis-table", "componentId": "orders"},
    "authoringDocumentType": "praxis.table.editor",
    "authoringSchemaRef": "urn:praxis:table:authoring",
    "authoringSchemaVersion": "1",
    "patchSchemaRef": "urn:praxis:table:layout-patch",
    "patchSchemaVersion": "praxis.ui-layout/v1",
    "baseline": {"sourceRef": "opaque-host-ref", "contentHash": "sha256", "document": {"kind": "praxis.table.editor", "version": 1, "config": {"columns": [], "columnProjection": {"source": "schema"}}}},
    "working": {"sourceRef": null, "contentHash": "sha256", "document": {"kind": "praxis.table.editor", "version": 1, "config": {"columns": [], "columnProjection": {"source": "schema"}}}},
    "selectedRevision": null,
    "selectedAssignment": null
  }],
  "frozenReleaseRef": null,
  "freezeCommandRef": null
}
```

`schemaVersion` identifies the Config envelope. `authoringDocumentType` is the real adapter
discriminator. The authoring descriptor identifies the complete native document used to reopen the
editor; the patch descriptor independently identifies the only document eligible for publication.
Config never derives one schema version from the other.

ETag, state and timestamps are projected only from `ui_layout_draft`. `frozenReleaseRef` is derived
only from `ui_layout_release.source_draft_id` and its tenant, environment and root relationship is
verified. They are not duplicated inside `draft_document`. `freezeCommandRef` is different: it is
the correlation of the accepted freeze and is persisted once in the workspace envelope during the
same transaction that creates the release.

## Controlled HTTP sequence

Create is the only idempotent lifecycle command in this cut:

```http
POST /api/praxis/config/ui-layouts/drafts?rootComponentType=praxis-dynamic-page&rootComponentId=orders-page
X-Praxis-Context-Version: ctx-42
Idempotency-Key: 90f79e7a-26fb-47da-bdfa-629304cd860e
```

Config opens its transaction, takes a scoped creation lock over tenant, environment, root, actor and
key, and checks replay before calling the source. A replay returns the current authorized workspace
for the same draft without recapturing the baseline. The V65 partial unique index is a final defense;
the implementation does not catch a uniqueness failure and query again inside an aborted PostgreSQL
transaction.

Create a revision using the current strong draft ETag:

```http
POST /api/praxis/config/ui-layouts/drafts/{draftId}/revisions
X-Praxis-Context-Version: ctx-42
If-Match: "a2160431-d6ca-4dc8-8b2b-bd23491fba09"
Content-Type: application/json

{
  "commandRef": "c3c2c8db-7453-40a8-95e2-6a59a95ef1bd",
  "target": {"componentType": "praxis-table", "componentId": "orders"},
  "authoringDocument": {"kind": "praxis.table.editor", "version": 1, "config": {"columns": [], "columnProjection": {"source": "schema"}}},
  "reason": "Adjust the governed orders view"
}
```

Revision bodies must be strict UTF-8 JSON, with at most 1 MiB of actual wire bytes.
An oversized declared or actual body returns a sanitized `413` problem with code
`REVISION_BODY_TOO_LARGE` and `Cache-Control: no-store`, before String conversion
in MVC, after host servlet security filters. Invalid encoding returns `400`.
Only complete raw authorship is submitted; `patchDocument` is an unknown field
and returns `400 INVALID_REQUEST`. Config compiles against original pinned B0,
never previous working C0. Candidate and generated patch each allow an independent 256 KiB of compact JSON,
64 container levels and number tokens up to 256 characters. The request envelope
allows 65 levels. Document-budget failures return `400 INVALID_REQUEST`.
Direct Java revision input uses the same closed parser and document checks; its
already allocated input strings are capped at 1 MiB UTF-8 each before parsing.
These limits do not extend to every lifecycle operation or persisted workspace.

The validator receives the pinned baseline descriptor and document together with the candidate
descriptor/document and patch descriptor/document. In one Config transaction, the immutable patch
revision is created, the complete working document is stored, that revision is selected, any previous
assignment selection for the target is cleared, and the draft ETag rotates.

Create an assignment without sending a revision ref:

```json
{
  "commandRef": "76314657-1a44-4f97-b837-68350e78a83d",
  "target": {"componentType": "praxis-table", "componentId": "orders"},
  "layerClass": "TENANT",
  "selector": {"tenant": "tenant-a"},
  "priority": 10,
  "contributionKey": "orders-default"
}
```

Config derives the currently selected revision. Freeze likewise receives only correlation:

```json
{"commandRef": "ba8b7316-59ba-40d8-942b-a7bb99358f8b"}
```

It freezes every registered target in exact registry order from the selected revision and assignment
refs. Missing or inconsistent selections fail closed. The same Config mutation persists
`freezeCommandRef`, creates release/members/review, changes the workspace to `RELEASED`, and rotates
its ETag. No second mutation is attempted after the workspace becomes read-only. Review and head
retain their independent ETags and routes.

All lifecycle command bodies are decoded by an endpoint-local strict mapper. JSON `null`, arrays,
scalars, incomplete objects, null required fields and structurally invalid required values return a
sanitized `400 INVALID_REQUEST` with `Cache-Control: no-store` before host context, source, lock or
repository access. Revision `authoringDocument` must be a JSON object; the derived
patch remains internal and is checked before any immutable write.
Unknown and duplicate fields remain rejected; the application's global Jackson configuration is not
changed.

## Reconciliation and limits

`commandRef` is observable on the last selected revision or assignment and, after freeze, as
`freezeCommandRef`. It is correlation, not authorization or idempotency. A client that lost the
freeze response reads the workspace: equality confirms which call won, while a different value does
not confirm the caller's attempt. If another revision or assignment command replaces a selection, an
older command remains uncertain even when its content hash is equal. Clients must not resend
revision, assignment or freeze automatically.

A `DRAFT` workspace must have null `freezeCommandRef` and no related release. A `RELEASED` workspace
must have a valid UUID correlation and exactly one related release in the same tenant, environment and
root scope. Reopen fails closed on any mismatch. Submit, approve, publish, withdraw and rollback do
not alter the persisted freeze correlation or the draft ETag.

Stored envelopes reject unknown fields, duplicate JSON keys, non-object documents, target/order drift,
hash drift, cross-scope refs and documents over the 256 KiB per-document Config limit. Historical
drafts whose `draft_document` is `{}` have no pinned evidence and fail closed; reopen never substitutes
the current host HEAD for their missing baseline.

The package-private codec also separates historical envelope verification from current target
admission. `decodeHistorical` preserves the stored target identities, descriptors, baseline/working
documents and hashes even when today's registry changed. It retains the same closed format,
duplicate-key/target checks, schema version and size/hash validation. Current workspace decode still
requires the exact registered target list and order; no current read or mutation bypasses that gate.
This internal primitive does not authorize historical reads, verify repository refs or release
membership, or establish compatibility with current native contracts. The separate
[historical evidence service](ui-layout-historical-evidence.md) checks the release/source-draft
relationship, tenant/environment/root scope, frozen membership and immutable selected records,
requiring explicit host operation and content access. No historical HTTP endpoint or rebase is
provided. Generic USER/TENANT configs without verified B0 remain outside
automatic intent-preserving upgrade.

The starter source and tests prove the contract and controlled HTTP roundtrip. A concrete host source,
native Table/Form validators, PostgreSQL concurrency against a real remote database, Angular Core
client adoption and browser authoring proof remain downstream gates. No lifecycle state authorizes
publication by itself; admission, review and head transitions remain separate.

### Native Table revision reproduction (P3.2h.5b)

For host-admitted `praxis.table.editor` version `1` with patch version
`praxis.ui-layout/v1`, revision admission additionally checks compact schema
`columnProjection`, stable field identities, unchanged native envelope/bindings,
authored presence in the patch (including pins equal to B0), known reset paths,
and exact reproduction of candidate config using the resolver's shared merge.
Host authorization and native schema validation still run first. Other native
formats remain the host validator's responsibility and are not attested by this gate.

This compiler accepts compact native Table config with no literal null assignment.
Null in its patch denotes a reset/removal; a literal null in candidate config is
rejected. `columns: []` is required; a materialized column snapshot is not accepted
as compact authorship. The command carries the whole authored candidate, not only
changed values. This is document reproduction, not current metadata/schema
rematerialization, historical origin attestation, upgrade diagnosis or apply.

The public Table widget method `createRevisionCommand(workspace, commandRef, reason)`
produces a command without sending it. It requires the admitted working config to
match the opened editor, a valid idle editor, and no unsupported binding or source
change. The host sends it through the existing Core lifecycle client using the
returned draft ETag and current context. On 409/412, reload and review the working
receipt; never overwrite or automatically resend. A lost response must be recovered
through the accepted command reference, not inferred from content equality.

Finite TypeScript-generated fixtures and MockMvc repository doubles prove controlled
admission and rejection before writes. They do not demonstrate corporate deployment,
real PostgreSQL concurrency, current-schema compatibility or integrated browser UX.

Compatibilidade: a admissão Table fica mais estrita mesmo sem novo DTO/endpoint.
Patches esparsos que omitem presença autorada igual a B0 ou usam snapshots/literal
null poderão ser recusados. Adotar o produtor no mesmo ciclo de integração; a
prova local não autoriza promoção pública ou migração de consumidores existentes.

## Correlated metadata writer SPI

TargetSeed now requires the genuine structural metadata used for native B0. The writer requires
host metadata admission plus Config JDBC, persists the capture after draft flush in the same
transaction, and reads stored evidence on replay without recapturing.
See [writer contract, beta migration and pending host/DB/runtime gates](ai/contracts/ui-layout-baseline-metadata-writer.md).
The HTTP receipt does not expose raw metadata; hosts without genuine source/admission remain unsupported.

## Recovering a creation locator

Use the [creation-key lookup contract](ai/contracts/ui-layout-create-key-lookup.md) to read the existing association after an uncertain create response. Follow the canonical workspace read before editing; absence is not proof of failed creation.

## Validation context (B2c2)

The host must provide UiLayoutValidationBudgetPolicy; auto-configuration does not
invent a production default. Java structure/source/access SPIs take the shared
UiLayoutValidationContext as their last argument. Nested historical reads retain
the initiating attempt and independent historical grants. Actual beforeCommit
rechecks mutation acceptance; afterCompletion closes a participating attempt.
Read acceptance also rechecks current invocation/authority. See
[validation context](ai/contracts/ui-layout-validation-attempt.md) for migration,
purposes and cooperative enforcement limits. No HTTP payload or endpoint changed.
