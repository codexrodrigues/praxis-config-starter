package org.praxisplatform.config.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sanitized, read-only evidence for explaining one exact governed rule definition.
 *
 * <p>This projection deliberately has no tenant identifiers, actor identifiers, runtime facts,
 * workspace rationale, or materialized payloads. It is an internal service contract and is not an
 * HTTP DTO.</p>
 */
public record DomainRuleExplanationEvidenceProjection(
        String schemaVersion,
        DecisionRef decisionRef,
        ScopeAttestation scopeAttestation,
        SemanticContext semanticContext,
        ConditionEvidence conditionEvidence,
        LifecycleEvidence lifecycle,
        List<MaterializationEvidence> materializations,
        List<String> sourceRefs,
        RedactionEvidence redaction,
        VersionAttestation versionAttestation) {

    public static final String SCHEMA_VERSION = "praxis-domain-decision-explanation-evidence.v1";

    public DomainRuleExplanationEvidenceProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        materializations = materializations == null ? List.of() : List.copyOf(materializations);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }

    public record DecisionRef(
            UUID definitionId,
            String ruleKey,
            Integer version,
            String definitionHash,
            String conditionHash) {
    }

    public record ScopeAttestation(
            boolean tenantBound,
            boolean environmentBound) {
    }

    public record SemanticContext(
            String ruleType,
            String status,
            String contextKey,
            String resourceKey,
            String serviceKey,
            String semanticOwner) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConditionEvidence(
            String exposureMode,
            JsonNode conditionAst,
            List<String> operators,
            List<String> factPaths,
            boolean valid,
            List<ValidationIssue> validationIssues) {

        public ConditionEvidence {
            conditionAst = conditionAst == null ? null : conditionAst.deepCopy();
            operators = operators == null ? List.of() : List.copyOf(operators);
            factPaths = factPaths == null ? List.of() : List.copyOf(factPaths);
            validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
        }
    }

    public record ValidationIssue(
            String code,
            String path,
            String operator) {
    }

    public record LifecycleEvidence(
            String status,
            List<SafeTimelineEvent> safeEvents) {

        public LifecycleEvidence {
            safeEvents = safeEvents == null ? List.of() : List.copyOf(safeEvents);
        }
    }

    /** Actor fields are intentionally absent even though the canonical timeline contains them. */
    public record SafeTimelineEvent(
            String eventType,
            Instant occurredAt,
            String summary,
            String status,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            UUID materializationId,
            String materializationKey,
            String sourceHash) {
    }

    /** Raw materializedPayload, validation diagnostics, and applied-by fields are intentionally absent. */
    public record MaterializationEvidence(
            UUID id,
            String materializationKey,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String status,
            String sourceHash,
            Instant appliedAt) {
    }

    public record RedactionEvidence(
            String policyRef,
            String mode,
            List<String> omittedFields,
            boolean rawRuntimeFactsCopied,
            boolean rawMaterializedPayloadCopied,
            boolean actorRefsCopied,
            boolean tenantIdCopied,
            boolean workspaceRationaleCopied) {

        public RedactionEvidence {
            omittedFields = omittedFields == null ? List.of() : List.copyOf(omittedFields);
        }
    }

    public record VersionAttestation(
            Integer requestedVersion,
            Integer resolvedVersion,
            boolean exactMatch) {
    }
}
