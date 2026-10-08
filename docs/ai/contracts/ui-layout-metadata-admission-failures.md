# Governed metadata admission: denial and technical unavailability

Config classifies initial UiLayoutBaselineMetadataAdmission and current access to pinned captures with one internal policy. The default denyAll remains an intentional DENIED. Hosts must throw UiLayoutLifecycleException(DENIED, ...) for policy refusal/revocation; CONTEXT_STALE for obsolete context, SOURCE_UNAVAILABLE for technical failure, or VALIDATION_FAILED for invalid admitted evidence. Generic exceptions and other lifecycle codes become SOURCE_UNAVAILABLE. Exception text and causes are never forwarded from the hook.

Existing HTTP mapping: DENIED = 403, CONTEXT_STALE = 409, VALIDATION_FAILED = 422, SOURCE_UNAVAILABLE = 503. Every outcome rejects access. Historical producer observations never grant current permission; current access is checked before and after verification. Neither denial nor technical failure triggers recapture, latest-metadata fallback or repair.

A failure during capture append poisons the Config transaction and transaction holder even when an outer caller catches it. Before persistence, no draft save is permitted. Unit transaction fixtures attest the local rejection/rollback marking, not a PostgreSQL commit or distributed rollback.

Corporate migration: providers previously throwing IllegalStateException or another generic exception to signal policy refusal must declare DENIED explicitly. An outage must not be communicated as a missing entitlement; an explicit denial must not suggest that retrying will grant permission. Do not infer a business outcome from exception messages, exception class names or raw provider detail.

Client writes receiving 502/504 have a separate uncertain transport outcome: the gateway response cannot establish whether the canonical command committed. Reconcile under the current authorized scope and retain the original command identity/precondition/idempotency key. No blind retry, new key, automatic rollback or success claim is justified. A read of the latest head alone does not prove that a particular command completed.

Evidence: package 6r uses focused unit/HTTP mock tests, private Maven candidate and Angular package builds. Real PostgreSQL/gateway/browser/provider acceptance remains open.
