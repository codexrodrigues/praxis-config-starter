# Domain Rule Timeline v1 Source Plan

Date: 2026-04-27

Status: v1 implementation-ready locally; release publication remains deferred.

## Purpose

The initial governed timeline intentionally derived events only from persisted domain-rule definitions and materializations.

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

These two entities were enough for the original read-only endpoint:

- `GET /api/praxis/config/domain-rules/definitions/{definitionId}/timeline`

## Deliberate Gaps Before v1

The following conceptual lifecycle points are not yet safe to expose as timeline events because they do not have a dedicated persisted governance source:

- `intake.received`
- `simulation.requested`
- `simulation.completed`
- `publication.requested`
- `publication.completed`
- `approval.requested`
- `approval.completed`

Some of these moments appear in request/response DTOs or diagnostics, but those are not durable audit records. Adding them to the timeline today would either invent state, depend on transient payloads, or make clients believe the platform has an audit trail that it does not persist yet.

This constraint has now been resolved for successful publication and approval events through the append-only `domain_rule_event` source. It remains true for intake, simulation and rejected/blocked governance attempts.

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

## Implementation Status

First source increment:

- `V25__create_domain_rule_event.sql` creates the append-only `domain_rule_event` table.
- `DomainRuleEvent` maps the persisted event source.
- `DomainRuleEventRepository` exposes ordered lookup by definition.
- This increment does not yet write events from `DomainRuleService` and does not change the public timeline endpoint.

Second source increment:

- `DomainRuleService` writes v0-equivalent events transactionally for definition creation, definition approval/activation, materialization creation and materialization application.
- The public timeline endpoint still uses the existing derived v0 projection.
- No `simulation`, `publication` or `approval` timeline events are emitted yet, because those still require a durable governance source.

Third source increment:

- The public timeline endpoint reads persisted `domain_rule_event` rows when they exist.
- The endpoint keeps the existing derived v0 projection as fallback for older data without persisted events.
- The DTO remains unchanged; this is a source-of-truth switch, not a public response expansion.

Fourth source increment:

- `DomainRuleService.publish` writes `publication.requested` and `publication.completed` events transactionally on successful publication.
- Publication event metadata is an allowlist: publication id, publication readiness and materialization count.
- `publicationNotes`, prompt, assistant content, condition, parameters and materialized payload remain excluded from public-safe event metadata.
- Blocked publication responses still do not become persisted audit events until there is a durable governance model for rejected/blocked attempts.

Fifth source increment:

- `DomainRuleService.createDefinition` writes `approval.requested` when persisted governance requires approval.
- `DomainRuleService.transitionDefinitionStatus` writes `approval.completed` when the persisted definition receives its first approval timestamp.
- Approval event metadata is an allowlist: required approval count only.
- Required approver identities, validation details, prompt, assistant content, condition, parameters and materialized payload remain excluded from public-safe event metadata.

Operational proof increment:

- `praxis-api-quickstart` requires `publication.requested` and `publication.completed` when `REQUIRE_TIMELINE=true` on the published `option_source` path.
- `praxis-api-quickstart` requires `approval.requested` and `approval.completed` when `REQUIRE_TIMELINE=true` on the governed `form_config` path.
- Local quickstart + Neon proof passed with `eventCount=7` for both paths.
- Monorepo readiness passed locally with `scripts/workspace/check-v0-readiness.sh`.

Next release increment: publish a coordinated Maven release only after explicit phase-close approval or a named downstream consumer requires the public coordinate.

## Non-Goals

- Do not create a second decision source outside `/api/praxis/config/domain-rules/**`.
- Do not turn `AiTurnEvent` replay into public domain-rule audit by default.
- Do not expose prompt or assistant content in the governed timeline.
- Do not publish Maven/npm only for this planning note.

## Next Recommended Work

Keep the published runtime on the existing public artifact until a real need appears for the durable timeline v1 artifact.

When that need is explicit, release `praxis-config-starter:0.1.0-rc.36`, update quickstart to consume it without local overrides, run one published-runtime smoke, and then promote HTTP corpus status. For new event families beyond v1, start with persisted governance source design in `praxis-config-starter`, not with UI reconstruction or quickstart-only smoke changes.
