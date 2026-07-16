package org.praxisplatform.config.dto;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import org.praxisplatform.rules.contract.RuleSetDefinition;

/** Deliberate request to publish and activate an immutable governed RuleSet snapshot. */
@Schema(description = "Governed request that composes approved rule definitions into one immutable executable RuleSet snapshot.")
public record DomainRuleSnapshotPublicationRequest(
    @Schema(description = "Complete deterministic RuleSet graph to validate and publish; slots, dependencies and executors become immutable snapshot content.", requiredMode = Schema.RequiredMode.REQUIRED)
    RuleSetDefinition ruleSet,
    @Schema(description = "Distinct identifiers of approved domain-rule definitions that provide provenance and approval evidence for this publication.", requiredMode = Schema.RequiredMode.REQUIRED)
    List<UUID> sourceDefinitionIds,
    @Schema(description = "Stable service identity of the domain host authorized and expected to activate this snapshot.", example = "praxis-api-quickstart", requiredMode = Schema.RequiredMode.REQUIRED)
    String ownerServiceKey,
    @Schema(description = "Exact host contract version required for safe activation; hosts with another contract must reject the snapshot.", example = "quickstart/1.0", requiredMode = Schema.RequiredMode.REQUIRED)
    String requiredHostContractVersion,
    @Schema(description = "UTC instant from which evaluations may use this snapshot.", example = "2026-07-13T20:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    String validFromUtc,
    @Schema(description = "Optional exclusive UTC instant after which the snapshot is no longer eligible for evaluation.", example = "2027-01-01T00:00:00Z")
    String validUntilUtc,
    @Schema(description = "Exact SHA-256 returned by the composition-manifest endpoint for this unchanged candidate.", requiredMode = Schema.RequiredMode.REQUIRED)
    String compositionDigest) {}
