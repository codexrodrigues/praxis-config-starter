# Recovering a draft after an uncertain creation response

Private local candidate, not a claim of availability on the published host.

`GET /api/praxis/config/ui-layouts/drafts/by-creation-key` requires authenticated principal, current `X-Praxis-Context-Version`, the original `Idempotency-Key` and `rootComponentType`/`rootComponentId` query parameters. The key is trimmed, case-sensitive and limited to 180 characters. Tenant, environment and actor come exclusively from the authenticated invocation. READ_DRAFT is required before lookup and rechecked before returning, including full invocation equality. These rechecks do not provide atomic revocation ordering.

200 returns `UiLayoutDraftReceipt` with the current state/validator, strong ETag, canonical draft Location and `Cache-Control: no-store`. The lookup ignores conditional validators and never returns 304. It does not load or establish integrity of the workspace, execute producers, acquire creation locks, write, publish or apply anything. Read the canonical workspace using the returned locator and current context/root before editing.

404 (`NOT_FOUND`) means no association observed in this authorized read. It does not prove a failed command, rollback, expiration or permission to create again. 403 denies this read; 503 or network/gateway failures cannot confirm creation. Preserve the original uncertain write separately. Do not retry POST, generate another key, poll or recreate automatically.

The lookup uses the existing six-field repository identity and draft retention. Historical rows with null creation keys remain untouched. No ledger, TTL, migration, provider reservation or fingerprint is introduced. An independent request must read the authoritative committed store; invoking this lookup within a creation transaction is not commit evidence. Replica lag also prevents conclusions from absence. PostgreSQL concurrency and rollback visibility remain a separate admitted environment gate.

Keep keys out of URLs and error details; deployment logging must redact Idempotency-Key headers. Header transport alone is not proof of log redaction. The existing Origin and host authentication policy still apply.
