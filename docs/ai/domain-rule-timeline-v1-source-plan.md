# Domain Rule Timeline v1 Source Plan

Date: 2026-04-27

Status: planning note for the next observability increment.

## Purpose

The v0 governed timeline intentionally derives events only from persisted domain-rule definitions and materializations.

That keeps the public timeline safe and truthful:

- `definition.created`
- `definition.approved`
- `definition.activated`
- `materialization.created`
- `materialization.applied`

The timeline must not reconstruct lifecycle from transient HTTP responses, logs, prompts, assistant messages or frontend state. Praxis is a platform of semantic decisions authored by AI; observability must explain governed decisions from canonical persisted state, not from incidental runtime traces.

## Current Canonical Sources

`DomainRuleDefinition` is the source for definition lifecycle events:

- `createdAt`
- `createdByType`
- `createdBy`
- `approvedAt`
- `approvedBy`
- `activatedAt`
- `status`

`DomainRuleMaterialization` is the source for materialization lifecycle events:

- `createdAt`
- `appliedAt`
- `appliedByType`
- `appliedBy`
- `status`
- `targetLayer`
- `targetArtifactType`
- `targetArtifactKey`
- `sourceHash`

These two entities are enough for the v0 read-only endpoint:

- `GET /api/praxis/config/domain-rules/definitions/{definitionId}/timeline`

## Deliberate v0 Gaps

The following conceptual lifecycle points are not yet safe to expose as timeline events because they do not have a dedicated persisted governance source:

- `intake.received`
- `simulation.requested`
- `simulation.completed`
- `publication.requested`
- `publication.completed`
- `approval.requested`
- `approval.completed`

Some of these moments appear in request/response DTOs or diagnostics, but those are not durable audit records. Adding them to the timeline today would either invent state, depend on transient payloads, or make clients believe the platform has an audit trail that it does not persist yet.

## v1 Rule

Only add a new timeline event family after one of these exists:

- a dedicated persisted event table owned by `praxis-config-starter`;
- a persisted domain-rule publication/approval entity with explicit timestamps, actors and safe summaries;
- a canonical persisted command ledger for `/api/praxis/config/domain-rules/**`.

Do not derive v1 events from:

- `DomainRulePublicationResponse.processedAt` alone;
- simulation response IDs;
- `AiTurnEvent` logs without a stable link to the domain-rule definition and an explicit safe projection;
- frontend handoff state;
- quickstart smoke output;
- prompt text, assistant messages, conditions, parameters or materialized payloads.

## Candidate v1 Model

If a v1 event table is introduced, prefer a generic append-only model scoped to domain rules:

- `id`
- `tenant_id`
- `environment`
- `rule_definition_id`
- `event_type`
- `occurred_at`
- `actor_type`
- `actor`
- `summary`
- `status`
- `target_layer`
- `target_artifact_type`
- `target_artifact_key`
- `materialization_id`
- `materialization_key`
- `source_hash`
- `visibility`
- `safe_metadata`

Guardrails:

- `visibility` must default to `safe`.
- `safe_metadata` must be allowlisted and JSON-object-only.
- Raw prompt, assistant message, condition, parameters and materialized payload must remain out of the default timeline.
- Any privileged/debug timeline must be a separate endpoint or explicit parameter with its own permission model.

## Implementation Sequence

1. Add a source-of-truth persistence model for domain-rule events.
2. Write events inside the same transaction as definition transitions, publication and materialization changes.
3. Keep the existing derived v0 events as compatibility projection while the event log is backfilled or introduced.
4. Extend `DomainRuleTimelineResponse` only additively while Praxis remains beta.
5. Update `AiApiContractOpenApiTest` to protect new event types and visibility guarantees.
6. Update quickstart runtime smoke to require the new events only after local proof against Neon.
7. Update Angular/landing only after the backend contract and HTTP proof are stable.

## Non-Goals

- Do not create a second decision source outside `/api/praxis/config/domain-rules/**`.
- Do not turn `AiTurnEvent` replay into public domain-rule audit by default.
- Do not expose prompt or assistant content in the governed timeline.
- Do not publish Maven/npm only for this planning note.

## Next Recommended Work

Keep v0 as the public published target until a real need appears for durable simulation/publication/approval audit.

When that need is explicit, start with a persistence design PR in `praxis-config-starter`, not with UI reconstruction or quickstart-only smoke changes.
