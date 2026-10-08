# Effective UI layout composition receipt

`GET /api/praxis/config/ui-layouts/effective-composition` is the coherent read for a registered
root target and its registered children. It is additive; `/effective` remains the unit-target read
and does not promise coherence across multiple calls.

## Host composition

The aggregate and lifecycle controllers are optional surfaces owned by
`UiLayoutResolutionAutoConfiguration`. Both their factories and component-scan
registration require their canonical services. A host scanning the Config package
must not instantiate these controllers before composition/lifecycle collaborators
are available. No permissive service is created to make an incomplete host start.
Focused bootstrap tests cover incomplete and complete host scans with a single
controller instance; absence of this facade is not proof of operational readiness.

```http
GET /api/praxis/config/ui-layouts/effective-composition?rootComponentType=praxis-dynamic-page&rootComponentId=procurement-master-detail HTTP/1.1
X-Praxis-Context-Version: ctx-8f3c
If-None-Match: W/"previous-receipt"
```

```json
{
  "schemaVersion": "praxis.effective-ui-layout-composition/v1",
  "releaseRef": "opaque-release-ref",
  "rootTarget": {"componentType": "praxis-dynamic-page", "componentId": "procurement-master-detail"},
  "contextVersion": "ctx-8f3c",
  "receiptEtag": "sha256-of-effective-receipt",
  "members": [
    {
      "memberOrder": 0,
      "target": {"componentType": "praxis-dynamic-page", "componentId": "procurement-master-detail"},
      "appliedContributions": [{"memberOrder": 0, "assignmentRevisionRef": "opaque", "contentRevisionRef": "opaque", "contentHash": "sha256"}],
      "effectiveLayout": {"schemaVersion": "praxis.effective-ui-layout/v1", "compositeEtag": "sha256"}
    },
    {
      "memberOrder": 1,
      "target": {"componentType": "praxis-table", "componentId": "procurement-lines"},
      "appliedContributions": [],
      "effectiveLayout": null
    }
  ]
}
```

`releaseRef: null` means no active corporate release; the registered structure still resolves
permitted baseline and current overlays. A target with `effectiveLayout: null` has no permitted
governed overlay. It is not an authorization failure only when the host snapshot explicitly grants
that target in `authorizedTargets`; omitted grants default to denial and fail the entire aggregate.
Denial, corruption, stale context or a missing registered root fail the aggregate and never return
a partial receipt. A missing registry is intentionally reported as `403`, not `404`, so the endpoint
does not disclose whether a private composition exists outside the current authorized context.

The server authorizes and resolves context before it compares `If-None-Match`. A matching receipt
returns `304` with `ETag` and `Cache-Control: private, no-cache`. `receiptEtag` excludes
`resolvedAt` and all unapplied/private-audience provenance; it changes with the sanitized effective
member representation, context, active release state, or an effective baseline/overlay change.
Applied contribution receipts are derived from the exact candidates selected by the resolution
engine, not from a shared content revision reference, so an unmatched assignment cannot inherit
the provenance of another assignment that pins the same revision.

The `ETag` header has the same canonical value as `receiptEtag`; individual `contentHash` values
identify immutable applied patches and are not aggregate validators. Error responses are sanitized
RFC 9457 problem details with `Cache-Control: no-store`: `400` for malformed inputs, `401` for no
principal, `403` for denied or unregistered composition access, `409` for stale context, `422` for
ambiguous/protected presentation inputs, and `503` for unavailable authoritative sources or pinned
release integrity failure. Authorization and context validation always precede a `304` decision.

Host integration must provide the native composition registry and current permitted baseline/overlay
snapshot. Config fixes the release head once and combines its immutable contributions by one stable
`contributionKey` per tenant/environment/target, avoiding duplicate application. `ui_user_config`
is not a current input to this read; a later authorized integration must supply an effective
per-request contribution rather than copy a user preference into a corporate release.
