package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnBean({DomainRuleDefinitionRepository.class, DomainRuleMaterializationRepository.class})
public class DomainRuleService {

    private static final List<String> DEFINITION_STATUSES = List.of(
            "draft", "proposed", "approved", "active", "deprecated", "retired", "rejected");
    private static final List<String> MATERIALIZATION_STATUSES = List.of(
            "draft", "pending_review", "applied", "failed", "superseded", "reverted");

    private final DomainRuleDefinitionRepository definitionRepository;
    private final DomainRuleMaterializationRepository materializationRepository;
    private final DomainCatalogReleaseRepository releaseRepository;
    private final DomainKnowledgeChangeSetRepository changeSetRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public DomainRuleDefinitionResponse createDefinition(
            DomainRuleDefinitionRequest request,
            String tenantId,
            String environment) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule definition request is required");
        }
        requireText(request.ruleKey(), "ruleKey");
        requireText(request.ruleType(), "ruleType");

        DomainRuleDefinition definition = new DomainRuleDefinition();
        definition.setTenantId(normalize(tenantId));
        definition.setEnvironment(normalize(environment));
        definition.setRuleKey(request.ruleKey().trim());
        definition.setVersion(request.version());
        definition.setRuleType(request.ruleType().trim());
        definition.setStatus(normalizeOrDefault(request.status(), "draft"));
        definition.setContextKey(normalize(request.contextKey()));
        definition.setResourceKey(normalize(request.resourceKey()));
        definition.setServiceKey(normalize(request.serviceKey()));
        definition.setSemanticOwner(normalize(request.semanticOwner()));
        definition.setSteward(normalize(request.steward()));
        definition.setSourceRelease(resolveRelease(request.sourceReleaseId()));
        definition.setSourceChangeSet(resolveChangeSet(request.sourceChangeSetId()));
        definition.setDefinition(writeOrDefault(request.definition(), "{}"));
        definition.setParameters(writeOrDefault(request.parameters(), "{}"));
        definition.setCondition(writeNullable(request.condition()));
        definition.setGovernance(writeOrDefault(request.governance(), "{}"));
        definition.setValidationResult(writeNullable(request.validationResult()));
        definition.setCreatedByType(normalizeOrDefault(request.createdByType(), "system"));
        definition.setCreatedBy(normalize(request.createdBy()));
        definition.setApprovedBy(normalize(request.approvedBy()));
        return toResponse(definitionRepository.save(definition));
    }

    @Transactional(readOnly = true)
    public List<DomainRuleDefinitionResponse> definitions(
            String tenantId,
            String environment,
            String resourceKey,
            String status,
            String ruleType,
            String ruleKey) {
        String resolvedTenant = normalize(tenantId);
        String resolvedEnvironment = normalize(environment);
        if (StringUtils.hasText(ruleKey)) {
            return definitionRepository
                    .findByTenantIdAndEnvironmentAndRuleKeyOrderByVersionDesc(
                            resolvedTenant,
                            resolvedEnvironment,
                            ruleKey.trim())
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }
        if (StringUtils.hasText(resourceKey)) {
            List<String> statuses = StringUtils.hasText(status)
                    ? List.of(status.trim())
                    : List.of("draft", "proposed", "approved", "active");
            return definitionRepository
                    .findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                            resolvedTenant,
                            resolvedEnvironment,
                            resourceKey.trim(),
                            statuses)
                    .stream()
                    .filter(definition -> !StringUtils.hasText(ruleType) || ruleType.trim().equals(definition.getRuleType()))
                    .map(this::toResponse)
                    .toList();
        }
        if (StringUtils.hasText(ruleType) && StringUtils.hasText(status)) {
            return definitionRepository
                    .findByTenantIdAndEnvironmentAndRuleTypeAndStatus(
                            resolvedTenant,
                            resolvedEnvironment,
                            ruleType.trim(),
                            status.trim())
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }
        return definitionRepository.findAll().stream()
                .filter(definition -> matchesScope(definition.getTenantId(), resolvedTenant))
                .filter(definition -> matchesScope(definition.getEnvironment(), resolvedEnvironment))
                .filter(definition -> !StringUtils.hasText(status) || status.trim().equals(definition.getStatus()))
                .filter(definition -> !StringUtils.hasText(ruleType) || ruleType.trim().equals(definition.getRuleType()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DomainRuleDefinitionResponse transitionDefinitionStatus(
            UUID definitionId,
            DomainRuleStatusTransitionRequest request,
            String tenantId,
            String environment) {
        if (definitionId == null) {
            throw new ConfigurationIngestionException("definitionId is required");
        }
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule status transition request is required");
        }
        String status = requireAllowedStatus(request.status(), "status", DEFINITION_STATUSES);
        DomainRuleDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ConfigurationIngestionException("Rule definition not found: " + definitionId));
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");

        definition.setStatus(status);
        if (request.validationResult() != null && !request.validationResult().isNull()) {
            definition.setValidationResult(write(request.validationResult()));
        }
        if (StringUtils.hasText(request.decidedBy()) && ("approved".equals(status) || "active".equals(status))) {
            definition.setApprovedBy(request.decidedBy().trim());
        }
        Instant now = Instant.now();
        if ("approved".equals(status) && definition.getApprovedAt() == null) {
            definition.setApprovedAt(now);
        }
        if ("active".equals(status)) {
            if (definition.getApprovedAt() == null) {
                definition.setApprovedAt(now);
            }
            if (definition.getActivatedAt() == null) {
                definition.setActivatedAt(now);
            }
        }
        return toResponse(definitionRepository.save(definition));
    }

    @Transactional
    public DomainRuleMaterializationResponse createMaterialization(
            DomainRuleMaterializationRequest request,
            String tenantId,
            String environment) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule materialization request is required");
        }
        if (request.ruleDefinitionId() == null) {
            throw new ConfigurationIngestionException("ruleDefinitionId is required");
        }
        requireText(request.materializationKey(), "materializationKey");
        requireText(request.targetLayer(), "targetLayer");
        requireText(request.targetArtifactType(), "targetArtifactType");
        requireText(request.targetArtifactKey(), "targetArtifactKey");

        DomainRuleDefinition definition = definitionRepository.findById(request.ruleDefinitionId())
                .orElseThrow(() -> new ConfigurationIngestionException("Rule definition not found: " + request.ruleDefinitionId()));
        DomainRuleMaterialization materialization = new DomainRuleMaterialization();
        materialization.setTenantId(normalize(tenantId));
        materialization.setEnvironment(normalize(environment));
        materialization.setRuleDefinition(definition);
        materialization.setMaterializationKey(request.materializationKey().trim());
        materialization.setTargetLayer(request.targetLayer().trim());
        materialization.setTargetArtifactType(request.targetArtifactType().trim());
        materialization.setTargetArtifactKey(request.targetArtifactKey().trim());
        materialization.setTargetPointer(normalize(request.targetPointer()));
        materialization.setTargetReleaseKey(normalize(request.targetReleaseKey()));
        materialization.setMaterializedRuleId(normalize(request.materializedRuleId()));
        materialization.setStatus(normalizeOrDefault(request.status(), "draft"));
        materialization.setMaterializedPayload(writeOrDefault(request.materializedPayload(), "{}"));
        materialization.setSourceHash(normalize(request.sourceHash()));
        if (request.validationResult() != null && !request.validationResult().isNull()) {
            materialization.setValidationResult(write(request.validationResult()));
        }
        materialization.setAppliedByType(normalize(request.appliedByType()));
        materialization.setAppliedBy(normalize(request.appliedBy()));
        return toResponse(materializationRepository.save(materialization));
    }

    @Transactional(readOnly = true)
    public List<DomainRuleMaterializationResponse> materializations(
            String tenantId,
            String environment,
            UUID ruleDefinitionId,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String status) {
        String resolvedTenant = normalize(tenantId);
        String resolvedEnvironment = normalize(environment);
        if (ruleDefinitionId != null) {
            return materializationRepository
                    .findByTenantIdAndEnvironmentAndRuleDefinition_Id(resolvedTenant, resolvedEnvironment, ruleDefinitionId)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }
        if (StringUtils.hasText(targetLayer)
                && StringUtils.hasText(targetArtifactType)
                && StringUtils.hasText(targetArtifactKey)) {
            return materializationRepository
                    .findByTenantIdAndEnvironmentAndTargetLayerAndTargetArtifactTypeAndTargetArtifactKey(
                            resolvedTenant,
                            resolvedEnvironment,
                            targetLayer.trim(),
                            targetArtifactType.trim(),
                            targetArtifactKey.trim())
                    .stream()
                    .filter(materialization -> !StringUtils.hasText(status) || status.trim().equals(materialization.getStatus()))
                    .map(this::toResponse)
                    .toList();
        }
        if (StringUtils.hasText(status)) {
            return materializationRepository
                    .findByTenantIdAndEnvironmentAndStatus(resolvedTenant, resolvedEnvironment, status.trim())
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }
        return materializationRepository.findAll().stream()
                .filter(materialization -> matchesScope(materialization.getTenantId(), resolvedTenant))
                .filter(materialization -> matchesScope(materialization.getEnvironment(), resolvedEnvironment))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DomainRuleMaterializationResponse transitionMaterializationStatus(
            UUID materializationId,
            DomainRuleStatusTransitionRequest request,
            String tenantId,
            String environment) {
        if (materializationId == null) {
            throw new ConfigurationIngestionException("materializationId is required");
        }
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule status transition request is required");
        }
        String status = requireAllowedStatus(request.status(), "status", MATERIALIZATION_STATUSES);
        DomainRuleMaterialization materialization = materializationRepository.findById(materializationId)
                .orElseThrow(() -> new ConfigurationIngestionException("Rule materialization not found: " + materializationId));
        requireScope(materialization.getTenantId(), tenantId, "tenantId");
        requireScope(materialization.getEnvironment(), environment, "environment");

        materialization.setStatus(status);
        materialization.setValidationResult(writeNullable(request.validationResult()));
        if (StringUtils.hasText(request.decidedByType())) {
            materialization.setAppliedByType(request.decidedByType().trim());
        }
        if (StringUtils.hasText(request.decidedBy())) {
            materialization.setAppliedBy(request.decidedBy().trim());
        }
        if ("applied".equals(status) && materialization.getAppliedAt() == null) {
            materialization.setAppliedAt(Instant.now());
        }
        return toResponse(materializationRepository.save(materialization));
    }

    private DomainCatalogRelease resolveRelease(UUID releaseId) {
        if (releaseId == null) {
            return null;
        }
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> new ConfigurationIngestionException("Domain catalog release not found: " + releaseId));
    }

    private DomainKnowledgeChangeSet resolveChangeSet(UUID changeSetId) {
        if (changeSetId == null) {
            return null;
        }
        return changeSetRepository.findById(changeSetId)
                .orElseThrow(() -> new ConfigurationIngestionException("Domain knowledge change set not found: " + changeSetId));
    }

    private DomainRuleDefinitionResponse toResponse(DomainRuleDefinition definition) {
        return new DomainRuleDefinitionResponse(
                definition.getId(),
                definition.getTenantId(),
                definition.getEnvironment(),
                definition.getRuleKey(),
                definition.getVersion(),
                definition.getRuleType(),
                definition.getStatus(),
                definition.getContextKey(),
                definition.getResourceKey(),
                definition.getServiceKey(),
                definition.getSemanticOwner(),
                definition.getSteward(),
                definition.getSourceRelease() != null ? definition.getSourceRelease().getId() : null,
                definition.getSourceChangeSet() != null ? definition.getSourceChangeSet().getId() : null,
                read(definition.getDefinition()),
                read(definition.getParameters()),
                read(definition.getCondition()),
                read(definition.getGovernance()),
                read(definition.getValidationResult()),
                definition.getCreatedByType(),
                definition.getCreatedBy(),
                definition.getApprovedBy(),
                definition.getCreatedAt(),
                definition.getUpdatedAt(),
                definition.getApprovedAt(),
                definition.getActivatedAt());
    }

    private DomainRuleMaterializationResponse toResponse(DomainRuleMaterialization materialization) {
        DomainRuleDefinition definition = materialization.getRuleDefinition();
        return new DomainRuleMaterializationResponse(
                materialization.getId(),
                materialization.getTenantId(),
                materialization.getEnvironment(),
                definition != null ? definition.getId() : null,
                definition != null ? definition.getRuleKey() : null,
                definition != null ? definition.getVersion() : null,
                materialization.getMaterializationKey(),
                materialization.getTargetLayer(),
                materialization.getTargetArtifactType(),
                materialization.getTargetArtifactKey(),
                materialization.getTargetPointer(),
                materialization.getTargetReleaseKey(),
                materialization.getMaterializedRuleId(),
                materialization.getStatus(),
                read(materialization.getMaterializedPayload()),
                materialization.getSourceHash(),
                read(materialization.getValidationResult()),
                materialization.getAppliedByType(),
                materialization.getAppliedBy(),
                materialization.getCreatedAt(),
                materialization.getUpdatedAt(),
                materialization.getAppliedAt());
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new ConfigurationIngestionException(field + " is required");
        }
    }

    private String requireAllowedStatus(String value, String field, List<String> allowedStatuses) {
        requireText(value, field);
        String status = value.trim();
        if (!allowedStatuses.contains(status)) {
            throw new ConfigurationIngestionException(field + " must be one of " + allowedStatuses);
        }
        return status;
    }

    private void requireScope(String actual, String expected, String field) {
        String normalizedExpected = normalize(expected);
        if (normalizedExpected != null && !normalizedExpected.equals(actual)) {
            throw new ConfigurationIngestionException("Rule " + field + " does not match request scope");
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return normalized != null ? normalized : fallback;
    }

    private boolean matchesScope(String actual, String expected) {
        return expected == null || expected.equals(actual);
    }

    private String writeOrDefault(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : write(node);
    }

    private String writeNullable(JsonNode node) {
        return node == null || node.isNull() ? null : write(node);
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to serialize domain rule JSON", ex);
        }
    }

    private JsonNode read(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to read domain rule JSON", ex);
        }
    }
}
