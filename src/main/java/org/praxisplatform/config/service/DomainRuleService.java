package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleIntakeRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRulePublicationRequest;
import org.praxisplatform.config.dto.DomainRulePublicationResponse;
import org.praxisplatform.config.dto.DomainRuleSimulationRequest;
import org.praxisplatform.config.dto.DomainRuleSimulationResponse;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleTimelineEventResponse;
import org.praxisplatform.config.dto.DomainRuleTimelineResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
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
    private static final List<String> COVERAGE_STATUSES = List.of("approved", "active");

    private final DomainRuleDefinitionRepository definitionRepository;
    private final DomainRuleMaterializationRepository materializationRepository;
    private final DomainCatalogReleaseRepository releaseRepository;
    private final DomainKnowledgeChangeSetRepository changeSetRepository;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainRuleIntakeResponse intake(
            DomainRuleIntakeRequest request,
            String tenantId,
            String environment) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule intake request is required");
        }
        requireText(request.prompt(), "prompt");
        requireText(request.ruleType(), "ruleType");
        requireText(request.resourceKey(), "resourceKey");

        ObjectNode definition = request.definition() != null && request.definition().isObject()
                ? request.definition().deepCopy()
                : objectMapper.createObjectNode();
        if (!StringUtils.hasText(definition.path("summary").asText(null))) {
            definition.put("summary", request.prompt().trim());
        }
        definition.put("sourcePrompt", request.prompt().trim());
        if (StringUtils.hasText(request.assistantMessage())) {
            definition.put("assistantMessage", request.assistantMessage().trim());
        }

        DomainRuleDefinitionResponse persisted = createDefinition(
                new DomainRuleDefinitionRequest(
                        StringUtils.hasText(request.ruleKey())
                                ? request.ruleKey().trim()
                                : deriveRuleKey(request.resourceKey(), request.ruleType()),
                        null,
                        request.ruleType().trim(),
                        "draft",
                        normalize(request.contextKey()),
                        request.resourceKey().trim(),
                        normalize(request.serviceKey()),
                        null,
                        null,
                        null,
                        null,
                        definition,
                        request.parameters(),
                        request.condition(),
                        request.governance(),
                        null,
                        normalizeOrDefault(request.createdByType(), "llm"),
                        normalize(request.createdBy()),
                        null),
                tenantId,
                environment);

        ObjectNode grounding = objectMapper.createObjectNode();
        grounding.put("source", "intake");
        grounding.put("nextEndpoint", "/api/praxis/config/domain-rules/simulations");
        grounding.put("ruleDefinitionId", persisted.id().toString());
        grounding.put("ruleKey", persisted.ruleKey());
        grounding.put("ruleType", persisted.ruleType());
        grounding.put("resourceKey", persisted.resourceKey());
        if (StringUtils.hasText(persisted.contextKey())) {
            grounding.put("contextKey", persisted.contextKey());
        }
        if (StringUtils.hasText(persisted.serviceKey())) {
            grounding.put("serviceKey", persisted.serviceKey());
        }
        ArrayNode existingCoverage = buildExistingCoverage(
                persisted.tenantId(),
                persisted.environment(),
                persisted.resourceKey(),
                persisted.ruleType(),
                persisted.id(),
                persisted.ruleKey());
        ArrayNode predictedMaterializations = buildPredictedMaterializations(
                persisted.resourceKey(),
                persisted.ruleType(),
                persisted.definition(),
                persisted.parameters());
        ArrayNode requiredApprovals = buildRequiredApprovals(persisted.governance());
        ArrayNode warnings = buildWarnings(
                persisted.ruleType(),
                predictedMaterializations,
                existingCoverage,
                persisted.governance());
        ObjectNode decisionDiagnostics = buildDecisionDiagnostics(
                persisted.ruleKey(),
                persisted.ruleType(),
                persisted.contextKey(),
                persisted.resourceKey(),
                persisted.serviceKey(),
                existingCoverage,
                predictedMaterializations,
                requiredApprovals,
                warnings,
                true);
        decisionDiagnostics.put("decisionStage", "intake");
        grounding.set("decisionDiagnostics", decisionDiagnostics);

        return new DomainRuleIntakeResponse(
                UUID.randomUUID(),
                persisted.tenantId(),
                persisted.environment(),
                persisted.ruleKey(),
                persisted.ruleType(),
                persisted.contextKey(),
                persisted.resourceKey(),
                persisted.serviceKey(),
                persisted.status(),
                grounding,
                persisted,
                Instant.now());
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
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
        definition.setStatus(requireAllowedStatus(
                normalizeOrDefault(request.status(), "draft"),
                "status",
                DEFINITION_STATUSES));
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

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
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

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
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
        requireAllowedDefinitionTransition(definition.getStatus(), status);

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

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainRuleSimulationResponse simulate(
            DomainRuleSimulationRequest request,
            String tenantId,
            String environment) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule simulation request is required");
        }

        DomainRuleDefinition persistedDefinition = resolveDefinitionForSimulation(request, tenantId, environment);
        String resolvedTenant = normalize(tenantId);
        String resolvedEnvironment = normalize(environment);

        UUID ruleDefinitionId = persistedDefinition != null ? persistedDefinition.getId() : request.ruleDefinitionId();
        String ruleKey = persistedDefinition != null ? persistedDefinition.getRuleKey() : normalize(request.ruleKey());
        Integer ruleVersion = persistedDefinition != null ? persistedDefinition.getVersion() : null;
        String ruleType = persistedDefinition != null ? persistedDefinition.getRuleType() : normalize(request.ruleType());
        String contextKey = persistedDefinition != null ? persistedDefinition.getContextKey() : normalize(request.contextKey());
        String resourceKey = persistedDefinition != null ? persistedDefinition.getResourceKey() : normalize(request.resourceKey());
        String serviceKey = persistedDefinition != null ? persistedDefinition.getServiceKey() : normalize(request.serviceKey());
        JsonNode definition = persistedDefinition != null ? read(persistedDefinition.getDefinition()) : request.definition();
        JsonNode parameters = persistedDefinition != null ? read(persistedDefinition.getParameters()) : request.parameters();
        JsonNode condition = persistedDefinition != null ? read(persistedDefinition.getCondition()) : request.condition();
        JsonNode governance = persistedDefinition != null ? read(persistedDefinition.getGovernance()) : request.governance();

        if (!StringUtils.hasText(ruleType)) {
            throw new ConfigurationIngestionException("ruleType is required for simulation");
        }
        if (!StringUtils.hasText(resourceKey)) {
            throw new ConfigurationIngestionException("resourceKey is required for simulation");
        }

        ArrayNode existingCoverage = buildExistingCoverage(
                resolvedTenant,
                resolvedEnvironment,
                resourceKey,
                ruleType,
                ruleDefinitionId,
                ruleKey);
        ArrayNode predictedMaterializations = buildPredictedMaterializations(
                resourceKey,
                ruleType,
                definition,
                parameters);
        ArrayNode requiredApprovals = buildRequiredApprovals(governance);
        ArrayNode warnings = buildWarnings(ruleType, predictedMaterializations, existingCoverage, governance);
        ObjectNode explainability = buildExplainability(
                ruleKey,
                ruleType,
                contextKey,
                resourceKey,
                serviceKey,
                existingCoverage,
                predictedMaterializations,
                requiredApprovals,
                warnings,
                persistedDefinition != null);

        ObjectNode grounding = objectMapper.createObjectNode();
        if (StringUtils.hasText(serviceKey)) {
            grounding.put("serviceKey", serviceKey);
        }
        if (StringUtils.hasText(contextKey)) {
            grounding.put("contextKey", contextKey);
        }
        grounding.put("resourceKey", resourceKey);
        grounding.put("ruleType", ruleType);
        grounding.put("source", persistedDefinition != null ? "persisted_definition" : "ad_hoc_request");
        grounding.put("conditionPresent", condition != null && !condition.isNull());

        String result = existingCoverage.isEmpty() ? "pass" : "pass_with_existing_coverage";
        return new DomainRuleSimulationResponse(
                UUID.randomUUID(),
                ruleDefinitionId,
                resolvedTenant,
                resolvedEnvironment,
                ruleKey,
                ruleVersion,
                ruleType,
                contextKey,
                resourceKey,
                serviceKey,
                result,
                grounding,
                existingCoverage,
                predictedMaterializations,
                requiredApprovals,
                warnings,
                explainability,
                Instant.now());
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainRulePublicationResponse publish(
            DomainRulePublicationRequest request,
            String tenantId,
            String environment) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule publication request is required");
        }
        if (request.ruleDefinitionId() == null) {
            throw new ConfigurationIngestionException("ruleDefinitionId is required");
        }

        DomainRuleDefinition definition = definitionRepository.findById(request.ruleDefinitionId())
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Rule definition not found: " + request.ruleDefinitionId()));
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");

        DomainRuleSimulationResponse simulation = simulate(
                new DomainRuleSimulationRequest(
                        definition.getId(),
                        definition.getRuleKey(),
                        definition.getRuleType(),
                        definition.getContextKey(),
                        definition.getResourceKey(),
                        definition.getServiceKey(),
                        null,
                        null,
                        null,
                        null),
                tenantId,
                environment);

        String readiness = simulation.explainability() != null
                ? normalize(simulation.explainability().path("publicationReadiness").asText(null))
                : null;
        if ("ready_to_publish".equals(readiness) && !isPublishableDefinitionStatus(definition.getStatus())) {
            readiness = "blocked_by_definition_status";
        }

        if (!"ready_to_publish".equals(readiness)) {
            return new DomainRulePublicationResponse(
                    UUID.randomUUID(),
                    definition.getTenantId(),
                    definition.getEnvironment(),
                    "blocked",
                    readiness,
                    definition.getId(),
                    definition.getRuleKey(),
                    definition.getVersion(),
                    definition.getRuleType(),
                    definition.getResourceKey(),
                    definition.getServiceKey(),
                    toResponse(definition),
                    List.of(),
                    withBlockedPublicationDiagnostics(simulation.explainability(), readiness, definition),
                    Instant.now());
        }

        Instant now = Instant.now();
        if (isPublishableDefinitionStatus(definition.getStatus())) {
            definition.setStatus("active");
            if (!StringUtils.hasText(definition.getApprovedBy()) && StringUtils.hasText(request.publishedBy())) {
                definition.setApprovedBy(request.publishedBy().trim());
            }
            if (definition.getApprovedAt() == null) {
                definition.setApprovedAt(now);
            }
            if (definition.getActivatedAt() == null) {
                definition.setActivatedAt(now);
            }
            definition = definitionRepository.save(definition);
        }

        ArrayNode materializationOutcomes = objectMapper.createArrayNode();
        List<DomainRuleMaterializationResponse> publishedMaterializations = publishMaterializations(
                definition,
                request,
                simulation.predictedMaterializations(),
                tenantId,
                environment,
                now,
                materializationOutcomes);

        return new DomainRulePublicationResponse(
                UUID.randomUUID(),
                definition.getTenantId(),
                definition.getEnvironment(),
                "published",
                readiness,
                definition.getId(),
                definition.getRuleKey(),
                definition.getVersion(),
                definition.getRuleType(),
                definition.getResourceKey(),
                definition.getServiceKey(),
                toResponse(definition),
                publishedMaterializations,
                withPublicationDiagnostics(simulation.explainability(), materializationOutcomes),
                now);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
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
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");
        String status = requireAllowedStatus(
                normalizeOrDefault(request.status(), "draft"),
                "status",
                MATERIALIZATION_STATUSES);
        if ("applied".equals(status) && !"active".equals(definition.getStatus())) {
            throw new ConfigurationIngestionException("Rule materialization can only be applied when its definition is active");
        }
        String materializationKey = request.materializationKey().trim();
        DomainRuleMaterialization existing = materializationRepository
                .findByTenantIdAndEnvironmentAndMaterializationKey(
                        normalize(tenantId),
                        normalize(environment),
                        materializationKey)
                .orElse(null);
        if (existing != null) {
            requireReusableMaterialization(existing, definition, request);
            return toResponse(existing);
        }
        DomainRuleMaterialization materialization = new DomainRuleMaterialization();
        materialization.setTenantId(normalize(tenantId));
        materialization.setEnvironment(normalize(environment));
        materialization.setRuleDefinition(definition);
        materialization.setMaterializationKey(materializationKey);
        materialization.setTargetLayer(request.targetLayer().trim());
        materialization.setTargetArtifactType(request.targetArtifactType().trim());
        materialization.setTargetArtifactKey(request.targetArtifactKey().trim());
        materialization.setTargetPointer(normalize(request.targetPointer()));
        materialization.setTargetReleaseKey(normalize(request.targetReleaseKey()));
        materialization.setMaterializedRuleId(normalize(request.materializedRuleId()));
        materialization.setStatus(status);
        materialization.setMaterializedPayload(write(deriveMaterializedPayload(definition, request)));
        materialization.setSourceHash(normalize(request.sourceHash()));
        if (request.validationResult() != null && !request.validationResult().isNull()) {
            materialization.setValidationResult(write(request.validationResult()));
        }
        materialization.setAppliedByType(normalize(request.appliedByType()));
        materialization.setAppliedBy(normalize(request.appliedBy()));
        if ("applied".equals(status)) {
            materialization.setAppliedAt(Instant.now());
        }
        return toResponse(materializationRepository.save(materialization));
    }

    private void requireReusableMaterialization(
            DomainRuleMaterialization existing,
            DomainRuleDefinition requestedDefinition,
            DomainRuleMaterializationRequest request) {
        DomainRuleDefinition existingDefinition = existing.getRuleDefinition();
        if (existingDefinition == null || !requestedDefinition.getId().equals(existingDefinition.getId())) {
            throw new ConfigurationIngestionException(
                    "Rule materialization key already belongs to another definition: "
                            + existing.getMaterializationKey());
        }
        requireReusableMaterializationTarget(
                existing,
                request.targetLayer(),
                request.targetArtifactType(),
                request.targetArtifactKey(),
                request.sourceHash());
    }

    private void requireReusableMaterialization(
            DomainRuleMaterialization existing,
            DomainRuleDefinition requestedDefinition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String sourceHash) {
        DomainRuleDefinition existingDefinition = existing.getRuleDefinition();
        if (existingDefinition == null || !requestedDefinition.getId().equals(existingDefinition.getId())) {
            throw new ConfigurationIngestionException(
                    "Rule materialization key already belongs to another definition: "
                            + existing.getMaterializationKey());
        }
        requireReusableMaterializationTarget(existing, targetLayer, targetArtifactType, targetArtifactKey, sourceHash);
    }

    private void requireReusableMaterializationTarget(
            DomainRuleMaterialization existing,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String sourceHash) {
        requireSameMaterializationField(existing.getTargetLayer(), targetLayer, "targetLayer");
        requireSameMaterializationField(existing.getTargetArtifactType(), targetArtifactType, "targetArtifactType");
        requireSameMaterializationField(existing.getTargetArtifactKey(), targetArtifactKey, "targetArtifactKey");
        if (StringUtils.hasText(sourceHash)
                && StringUtils.hasText(existing.getSourceHash())
                && !sourceHash.trim().equals(existing.getSourceHash())) {
            throw new ConfigurationIngestionException(
                    "Rule materialization key already exists with a different sourceHash: "
                            + existing.getMaterializationKey());
        }
    }

    private void requireSameMaterializationField(String existingValue, String requestedValue, String field) {
        if (!normalizeOrDefault(existingValue, "").equals(normalizeOrDefault(requestedValue, ""))) {
            throw new ConfigurationIngestionException(
                    "Rule materialization key already exists with a different " + field);
        }
    }

    private DomainRuleDefinition resolveDefinitionForSimulation(
            DomainRuleSimulationRequest request,
            String tenantId,
            String environment) {
        if (request.ruleDefinitionId() == null) {
            return null;
        }
        DomainRuleDefinition definition = definitionRepository.findById(request.ruleDefinitionId())
                .orElseThrow(() -> new ConfigurationIngestionException("Rule definition not found: " + request.ruleDefinitionId()));
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");
        return definition;
    }

    private ArrayNode buildExistingCoverage(
            String tenantId,
            String environment,
            String resourceKey,
            String ruleType,
            UUID currentDefinitionId,
            String currentRuleKey) {
        ArrayNode coverage = objectMapper.createArrayNode();
        List<DomainRuleDefinition> candidates = findCoverageCandidates(tenantId, environment, resourceKey);
        for (DomainRuleDefinition candidate : candidates) {
            if (!ruleType.equals(candidate.getRuleType())) {
                continue;
            }
            if (currentDefinitionId != null && currentDefinitionId.equals(candidate.getId())) {
                continue;
            }
            if (currentDefinitionId == null
                    && StringUtils.hasText(currentRuleKey)
                    && currentRuleKey.equals(candidate.getRuleKey())) {
                continue;
            }
            ObjectNode item = coverage.addObject();
            item.put("ruleDefinitionId", candidate.getId().toString());
            item.put("ruleKey", candidate.getRuleKey());
            item.put("ruleType", candidate.getRuleType());
            item.put("status", candidate.getStatus());
            item.put("version", candidate.getVersion());
            JsonNode definition = read(candidate.getDefinition());
            if (definition != null && definition.isObject()) {
                String summary = definition.path("summary").asText(null);
                if (StringUtils.hasText(summary)) {
                    item.put("summary", summary);
                }
                String operation = definition.path("recommendedOperation").asText(null);
                if (StringUtils.hasText(operation)) {
                    item.put("recommendedOperation", operation);
                }
            }
        }
        return coverage;
    }

    private List<DomainRuleDefinition> findCoverageCandidates(
            String tenantId,
            String environment,
            String resourceKey) {
        if (tenantId != null && environment != null) {
            return definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                    tenantId,
                    environment,
                    resourceKey,
                    COVERAGE_STATUSES);
        }
        return definitionRepository.findAll().stream()
                .filter(definition -> matchesScope(definition.getTenantId(), tenantId))
                .filter(definition -> matchesScope(definition.getEnvironment(), environment))
                .filter(definition -> resourceKey.equals(definition.getResourceKey()))
                .filter(definition -> COVERAGE_STATUSES.contains(definition.getStatus()))
                .toList();
    }

    private ArrayNode buildPredictedMaterializations(
            String resourceKey,
            String ruleType,
            JsonNode definition,
            JsonNode parameters) {
        ArrayNode targets = objectMapper.createArrayNode();
        String recommendedOperation = definition != null ? normalize(definition.path("recommendedOperation").asText(null)) : null;
        JsonNode params = parameters == null || parameters.isNull() ? null : parameters;

        if (looksLikeOptionSourceTarget(resourceKey, ruleType, definition, params)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "option_source");
            target.put("targetArtifactType", "resource-option-source");
            target.put("targetArtifactKey", deriveOptionSourceKey(resourceKey, params));
            target.put("operation", "selection_policy.review");
        }

        if (isBackendValidationRuleType(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "backend_validation");
            target.put("targetArtifactType", "resource-validation");
            target.put("targetArtifactKey", resourceKey);
            target.put("operation", "validation_rule.review");
        }

        if (isWorkflowActionRuleType(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "workflow_action");
            target.put("targetArtifactType", "resource-workflow-action");
            target.put("targetArtifactKey", deriveWorkflowActionKey(resourceKey, params));
            target.put("operation", "workflow_action_policy.review");
        }

        if (isApprovalPolicyRuleType(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "approval_policy");
            target.put("targetArtifactType", "resource-action-approval");
            target.put("targetArtifactKey", deriveActionApprovalKey(resourceKey, params));
            target.put("operation", "approval_policy.review");
        }

        if ("visual_guidance".equals(ruleType)
                || "form_rule".equals(ruleType)
                || "rule.visualBlockGuidance.add".equals(recommendedOperation)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "form_config");
            target.put("targetArtifactType", "praxis-dynamic-form");
            target.put("targetArtifactKey", resourceKey);
            target.put("operation", recommendedOperation != null ? recommendedOperation : "rule.review");
        }

        if (targets.isEmpty()) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "shared_rule_review");
            target.put("targetArtifactType", "domain-rule-definition");
            target.put("targetArtifactKey", resourceKey);
            target.put("operation", "governance.review");
        }
        return targets;
    }

    private boolean looksLikeOptionSourceTarget(
            String resourceKey,
            String ruleType,
            JsonNode definition,
            JsonNode parameters) {
        boolean explicitlyTargetsOptionSource = false;
        if (parameters != null && parameters.isObject()) {
            if (StringUtils.hasText(parameters.path("optionSourceKey").asText(null))
                    || StringUtils.hasText(parameters.path("lookupSource").asText(null))) {
                explicitlyTargetsOptionSource = true;
            }
        }
        String summary = definition != null ? normalize(definition.path("summary").asText(null)) : null;
        if (explicitlyTargetsOptionSource || "selection_eligibility".equals(ruleType)) {
            return true;
        }
        return "policy_reference".equals(ruleType)
                && (resourceKey.contains("procurement.suppliers")
                || (summary != null && (summary.contains("sele") || summary.contains("fornecedor") || summary.contains("supplier"))));
    }

    private String deriveOptionSourceKey(String resourceKey, JsonNode parameters) {
        if (parameters != null && parameters.isObject()) {
            String explicit = normalize(parameters.path("optionSourceKey").asText(null));
            if (StringUtils.hasText(explicit)) {
                return explicit;
            }
            explicit = normalize(parameters.path("lookupSource").asText(null));
            if (StringUtils.hasText(explicit)) {
                return explicit;
            }
        }
        if (resourceKey.contains("procurement.suppliers")) {
            return "supplier";
        }
        return resourceKey;
    }

    private JsonNode deriveMaterializedPayload(
            DomainRuleDefinition definition,
            DomainRuleMaterializationRequest request) {
        if (request.materializedPayload() != null && !request.materializedPayload().isNull()) {
            return request.materializedPayload();
        }
        if ("option_source".equals(request.targetLayer())
                && "resource-option-source".equals(request.targetArtifactType())
                && definition != null
                && "selection_eligibility".equals(definition.getRuleType())) {
            return buildOptionSourceMaterializedPayload(definition, request.targetArtifactKey());
        }
        if ("backend_validation".equals(request.targetLayer())
                && "resource-validation".equals(request.targetArtifactType())
                && definition != null
                && isBackendValidationRuleType(definition.getRuleType())) {
            return buildBackendValidationMaterializedPayload(definition, request.targetArtifactKey());
        }
        if ("workflow_action".equals(request.targetLayer())
                && "resource-workflow-action".equals(request.targetArtifactType())
                && definition != null
                && isWorkflowActionRuleType(definition.getRuleType())) {
            return buildWorkflowActionMaterializedPayload(definition, request.targetArtifactKey());
        }
        if ("approval_policy".equals(request.targetLayer())
                && "resource-action-approval".equals(request.targetArtifactType())
                && definition != null
                && isApprovalPolicyRuleType(definition.getRuleType())) {
            return buildApprovalPolicyMaterializedPayload(definition, request.targetArtifactKey());
        }
        return objectMapper.createObjectNode();
    }

    private ObjectNode buildOptionSourceMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = read(definition.getParameters());
        JsonNode condition = read(definition.getCondition());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kind", "lookup_selection_policy");
        payload.put("optionSourceKey", StringUtils.hasText(targetArtifactKey)
                ? targetArtifactKey
                : deriveOptionSourceKey(definition.getResourceKey(), parameters));

        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("origin", "domain_rule_definition");
        metadata.put("ruleType", normalizeOrDefault(definition.getRuleType(), "selection_eligibility"));
        metadata.put("reviewStatus", "pending");

        ObjectNode selectionPolicy = payload.putObject("selectionPolicy");
        selectionPolicy.put("statusPropertyPath", deriveSelectionStatusPropertyPath(parameters, condition));
        ArrayNode blockedStatuses = selectionPolicy.putArray("blockedStatuses");
        deriveBlockedStatuses(parameters, condition).forEach(blockedStatuses::add);

        boolean retainInvalidExistingValue = parameters != null
                && parameters.isObject()
                && parameters.path("allowRetainInvalidExistingValue").isBoolean()
                ? parameters.path("allowRetainInvalidExistingValue").asBoolean()
                : true;
        selectionPolicy.put("allowRetainInvalidExistingValue", retainInvalidExistingValue);

        if (parameters != null && parameters.isObject()) {
            copyText(parameters, selectionPolicy, "validationMessageTemplate");
            copyText(parameters, selectionPolicy, "disabledReasonTemplate");
            JsonNode allowedStatuses = parameters.get("allowedStatuses");
            if (allowedStatuses != null && allowedStatuses.isArray() && !allowedStatuses.isEmpty()) {
                ArrayNode allowed = selectionPolicy.putArray("allowedStatuses");
                allowedStatuses.forEach(item -> {
                    if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                        allowed.add(item.asText().trim());
                    }
                });
            }
        }
        return payload;
    }

    private ObjectNode buildBackendValidationMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = read(definition.getParameters());
        JsonNode condition = read(definition.getCondition());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kind", "resource_validation_policy");
        payload.put("resourceKey", StringUtils.hasText(targetArtifactKey)
                ? targetArtifactKey
                : normalize(definition.getResourceKey()));

        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("origin", "domain_rule_definition");
        metadata.put("ruleType", normalizeOrDefault(definition.getRuleType(), "validation"));
        metadata.put("reviewStatus", "pending");

        ObjectNode validationPolicy = payload.putObject("validationPolicy");
        validationPolicy.put("ruleKey", definition.getRuleKey());
        validationPolicy.put("ruleVersion", definition.getVersion());
        validationPolicy.put("resourceKey", StringUtils.hasText(targetArtifactKey)
                ? targetArtifactKey
                : normalize(definition.getResourceKey()));
        if (StringUtils.hasText(definition.getServiceKey())) {
            validationPolicy.put("serviceKey", definition.getServiceKey());
        }
        if (StringUtils.hasText(definition.getContextKey())) {
            validationPolicy.put("contextKey", definition.getContextKey());
        }
        if (condition != null && !condition.isNull()) {
            validationPolicy.set("condition", condition);
        }
        if (parameters != null && !parameters.isNull()) {
            validationPolicy.set("parameters", parameters);
            copyText(parameters, validationPolicy, "validationMessageTemplate");
            copyText(parameters, validationPolicy, "severity");
        }
        return payload;
    }

    private ObjectNode buildWorkflowActionMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = read(definition.getParameters());
        JsonNode condition = read(definition.getCondition());
        String resourceKey = deriveWorkflowActionResourceKey(definition, parameters);
        String actionId = deriveWorkflowActionId(targetArtifactKey, parameters);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kind", "workflow_action_policy");

        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("origin", "domain_rule_definition");
        metadata.put("ruleType", normalizeOrDefault(definition.getRuleType(), "workflow_action_policy"));
        metadata.put("reviewStatus", "pending");

        ObjectNode workflowAction = payload.putObject("workflowAction");
        workflowAction.put("resourceKey", resourceKey);
        workflowAction.put("actionId", actionId);

        ObjectNode policy = payload.putObject("availabilityPolicy");
        policy.put("ruleKey", definition.getRuleKey());
        policy.put("ruleVersion", definition.getVersion());
        policy.put("resourceKey", resourceKey);
        policy.put("actionId", actionId);
        if (condition != null && !condition.isNull()) {
            policy.set("condition", condition);
        }
        if (parameters != null && parameters.isObject()) {
            copyText(parameters, policy, "message");
            copyText(parameters, policy, "severity");
            JsonNode requiredStates = parameters.get("requiredStates");
            if (requiredStates != null && requiredStates.isArray() && !requiredStates.isEmpty()) {
                ArrayNode states = policy.putArray("requiredStates");
                collectTextArray(requiredStates).forEach(states::add);
            }
            JsonNode blockedWhen = parameters.get("blockedWhen");
            if (blockedWhen != null && !blockedWhen.isNull()) {
                policy.set("blockedWhen", blockedWhen);
            }
            JsonNode approvalPolicy = parameters.get("approvalPolicy");
            if (approvalPolicy != null && approvalPolicy.isObject()) {
                payload.set("approvalPolicy", approvalPolicy);
            } else {
                JsonNode requiredApprovals = parameters.get("requiredApprovals");
                if (requiredApprovals != null && requiredApprovals.isArray() && !requiredApprovals.isEmpty()) {
                    ObjectNode approvals = payload.putObject("approvalPolicy");
                    ArrayNode values = approvals.putArray("requiredApprovals");
                    collectTextArray(requiredApprovals).forEach(values::add);
                }
            }
        }
        return payload;
    }

    private ObjectNode buildApprovalPolicyMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = read(definition.getParameters());
        JsonNode condition = read(definition.getCondition());
        String resourceKey = deriveActionApprovalResourceKey(definition, parameters);
        String actionId = deriveActionApprovalId(targetArtifactKey, parameters);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kind", "approval_policy");

        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("origin", "domain_rule_definition");
        metadata.put("ruleType", normalizeOrDefault(definition.getRuleType(), "approval_policy"));
        metadata.put("reviewStatus", "pending");

        ObjectNode action = payload.putObject("resourceAction");
        action.put("resourceKey", resourceKey);
        action.put("actionId", actionId);

        ObjectNode policy = payload.putObject("approvalPolicy");
        policy.put("ruleKey", definition.getRuleKey());
        policy.put("ruleVersion", definition.getVersion());
        policy.put("resourceKey", resourceKey);
        policy.put("actionId", actionId);
        if (condition != null && !condition.isNull()) {
            policy.set("condition", condition);
        }
        if (parameters != null && parameters.isObject()) {
            copyText(parameters, policy, "message");
            copyText(parameters, policy, "severity");
            copyText(parameters, policy, "approverContext");
            JsonNode explicitPolicy = parameters.get("approvalPolicy");
            if (explicitPolicy != null && explicitPolicy.isObject()) {
                policy.setAll((ObjectNode) explicitPolicy);
            }
            JsonNode requiredApprovals = parameters.get("requiredApprovals");
            if (requiredApprovals != null && requiredApprovals.isArray() && !requiredApprovals.isEmpty()) {
                ArrayNode values = policy.putArray("requiredApprovals");
                collectTextArray(requiredApprovals).forEach(values::add);
            }
            JsonNode approvalGroups = parameters.get("approvalGroups");
            if (approvalGroups != null && approvalGroups.isArray() && !approvalGroups.isEmpty()) {
                ArrayNode values = policy.putArray("approvalGroups");
                collectTextArray(approvalGroups).forEach(values::add);
            }
            JsonNode blockedWhen = parameters.get("blockedWhen");
            if (blockedWhen != null && !blockedWhen.isNull()) {
                policy.set("blockedWhen", blockedWhen);
            }
        }
        return payload;
    }

    private String deriveSelectionStatusPropertyPath(JsonNode parameters, JsonNode condition) {
        if (parameters != null && parameters.isObject()) {
            String explicit = normalize(parameters.path("statusPropertyPath").asText(null));
            if (StringUtils.hasText(explicit)) {
                return explicit;
            }
        }
        if (condition != null && condition.isObject()) {
            JsonNode inNode = condition.get("in");
            if (inNode != null && inNode.isArray() && !inNode.isEmpty()) {
                JsonNode candidate = inNode.get(0);
                if (candidate != null && candidate.isObject()) {
                    String statusVar = normalize(candidate.path("var").asText(null));
                    if (StringUtils.hasText(statusVar)) {
                        return statusVar;
                    }
                }
            }
        }
        return "status";
    }

    private List<String> deriveBlockedStatuses(JsonNode parameters, JsonNode condition) {
        if (parameters != null && parameters.isObject()) {
            JsonNode explicit = parameters.get("blockedStatuses");
            if (explicit != null && explicit.isArray() && !explicit.isEmpty()) {
                return collectTextArray(explicit);
            }
        }
        if (condition != null && condition.isObject()) {
            JsonNode inNode = condition.get("in");
            if (inNode != null && inNode.isArray() && inNode.size() >= 2) {
                JsonNode statuses = inNode.get(1);
                if (statuses != null && statuses.isArray() && !statuses.isEmpty()) {
                    return collectTextArray(statuses);
                }
            }
        }
        return List.of("INACTIVE", "BLOCKED");
    }

    private List<String> collectTextArray(JsonNode arrayNode) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        arrayNode.forEach(item -> {
            if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                values.add(item.asText().trim());
            }
        });
        return values;
    }

    private void copyText(JsonNode source, ObjectNode target, String fieldName) {
        String value = normalize(source.path(fieldName).asText(null));
        if (StringUtils.hasText(value)) {
            target.put(fieldName, value);
        }
    }

    private String deriveRuleKey(String resourceKey, String ruleType) {
        String normalizedResource = resourceKey.trim();
        String normalizedRuleType = ruleType.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        normalizedRuleType = normalizedRuleType.replaceAll("(^-+|-+$)", "");
        if (!StringUtils.hasText(normalizedRuleType)) {
            normalizedRuleType = "draft";
        }
        return normalizedResource + ".rule." + normalizedRuleType;
    }

    private ArrayNode buildRequiredApprovals(JsonNode governance) {
        ArrayNode approvals = objectMapper.createArrayNode();
        if (governance != null && governance.isObject()) {
            JsonNode explicit = governance.get("requiredApprovals");
            if (explicit != null && explicit.isArray()) {
                explicit.forEach(item -> {
                    if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                        approvals.add(item.asText().trim());
                    }
                });
            }
            if (approvals.isEmpty() && "review_required".equals(governance.path("ruleAuthoring").asText())) {
                approvals.add("human-review");
            }
        }
        return approvals;
    }

    private ArrayNode buildWarnings(
            String ruleType,
            ArrayNode predictedMaterializations,
            ArrayNode existingCoverage,
            JsonNode governance) {
        ArrayNode warnings = objectMapper.createArrayNode();
        if (!existingCoverage.isEmpty()) {
            warnings.add("Simulation found active or approved shared rules covering the same resource and rule type.");
        }
        if (governance != null && governance.isObject() && "review_required".equals(governance.path("ruleAuthoring").asText())) {
            warnings.add("Governance requires human review before publication or target-specific materialization.");
        }
        if ("visual_guidance".equals(ruleType) && predictedMaterializations.size() == 1) {
            warnings.add("This rule currently projects only to form guidance; business validation may still require a non-UI target.");
        }
        return warnings;
    }

    private ObjectNode buildExplainability(
            String ruleKey,
            String ruleType,
            String contextKey,
            String resourceKey,
            String serviceKey,
            ArrayNode existingCoverage,
            ArrayNode predictedMaterializations,
            ArrayNode requiredApprovals,
            ArrayNode warnings,
            boolean persistedDefinition) {
        ObjectNode explainability = objectMapper.createObjectNode();
        explainability.put(
                "summary",
                buildExplainabilitySummary(ruleType, resourceKey, existingCoverage, predictedMaterializations, requiredApprovals));
        explainability.put(
                "recommendedAction",
                buildRecommendedAction(existingCoverage, requiredApprovals, persistedDefinition));
        explainability.put(
                "publicationReadiness",
                buildPublicationReadiness(existingCoverage, requiredApprovals, persistedDefinition));
        explainability.set("decisionDiagnostics", buildDecisionDiagnostics(
                ruleKey,
                ruleType,
                contextKey,
                resourceKey,
                serviceKey,
                existingCoverage,
                predictedMaterializations,
                requiredApprovals,
                warnings,
                persistedDefinition));

        ArrayNode highlights = explainability.putArray("highlights");
        highlights.add("ruleKey=" + (StringUtils.hasText(ruleKey) ? ruleKey : resourceKey + ".rule.draft"));
        highlights.add("ruleType=" + ruleType);
        highlights.add("predictedTargets=" + predictedMaterializations.size());
        if (!existingCoverage.isEmpty()) {
            highlights.add("existingCoverage=" + existingCoverage.size());
        }
        if (!requiredApprovals.isEmpty()) {
            highlights.add("requiredApprovals=" + requiredApprovals.size());
        }

        ArrayNode nextSteps = explainability.putArray("nextSteps");
        if (!existingCoverage.isEmpty()) {
            ObjectNode step = nextSteps.addObject();
            step.put("kind", "review_existing_coverage");
            step.put("title", "Review active shared-rule coverage before publishing a duplicate decision.");
            step.put("endpoint", "/api/praxis/config/domain-rules/definitions");
        }
        if (!requiredApprovals.isEmpty()) {
            ObjectNode step = nextSteps.addObject();
            step.put("kind", "request_approval");
            step.put("title", "Collect the required approvals before publication or target-specific materialization.");
            step.set("approvers", requiredApprovals.deepCopy());
        }
        if (persistedDefinition) {
            ObjectNode step = nextSteps.addObject();
            step.put("kind", "materialize_or_activate");
            step.put("title", "Advance the persisted shared rule through status review and target-specific materialization.");
            step.put("definitionStatusEndpoint", "/api/praxis/config/domain-rules/definitions/{definitionId}/status");
            step.put("materializationsEndpoint", "/api/praxis/config/domain-rules/materializations");
        } else {
            ObjectNode step = nextSteps.addObject();
            step.put("kind", "persist_definition");
            step.put("title", "Persist the shared-rule definition after reviewing the simulation grounding.");
            step.put("endpoint", "/api/praxis/config/domain-rules/definitions");
        }
        if (!warnings.isEmpty()) {
            ObjectNode step = nextSteps.addObject();
            step.put("kind", "resolve_warnings");
            step.put("title", "Review simulation warnings before applying downstream targets.");
            step.set("warnings", warnings.deepCopy());
        }
        return explainability;
    }

    private ObjectNode buildDecisionDiagnostics(
            String ruleKey,
            String ruleType,
            String contextKey,
            String resourceKey,
            String serviceKey,
            ArrayNode existingCoverage,
            ArrayNode predictedMaterializations,
            ArrayNode requiredApprovals,
            ArrayNode warnings,
            boolean persistedDefinition) {
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("decisionKind", "semantic_domain_rule");
        diagnostics.put("authoringMode", "governed");
        diagnostics.put("decisionSource", persistedDefinition ? "persisted_definition" : "ad_hoc_request");
        diagnostics.put("ruleKey", StringUtils.hasText(ruleKey) ? ruleKey : resourceKey + ".rule.draft");
        diagnostics.put("ruleType", ruleType);
        diagnostics.put("resourceKey", resourceKey);
        if (StringUtils.hasText(contextKey)) {
            diagnostics.put("contextKey", contextKey);
        }
        if (StringUtils.hasText(serviceKey)) {
            diagnostics.put("serviceKey", serviceKey);
        }
        diagnostics.put("existingCoverageCount", existingCoverage.size());
        diagnostics.put("predictedMaterializationCount", predictedMaterializations.size());
        diagnostics.put("requiredApprovalCount", requiredApprovals.size());
        diagnostics.put("warningCount", warnings.size());
        diagnostics.put("canonicalOwner", "praxis-config-starter");
        diagnostics.put("materializationModel", "derived_projection");
        diagnostics.put("runtimeSurfacesAreDerived", true);
        return diagnostics;
    }

    private String buildExplainabilitySummary(
            String ruleType,
            String resourceKey,
            ArrayNode existingCoverage,
            ArrayNode predictedMaterializations,
            ArrayNode requiredApprovals) {
        StringBuilder summary = new StringBuilder();
        summary.append("Simulation resolved a shared ");
        summary.append(ruleType);
        summary.append(" rule for ");
        summary.append(resourceKey);
        summary.append(" with ");
        summary.append(predictedMaterializations.size());
        summary.append(predictedMaterializations.size() == 1 ? " predicted target." : " predicted targets.");
        if (!existingCoverage.isEmpty()) {
            summary.append(" Existing approved or active coverage was found for the same resource and rule type.");
        } else {
            summary.append(" No approved or active shared-rule coverage was found for the same scope.");
        }
        if (!requiredApprovals.isEmpty()) {
            summary.append(" Human approval is required before publication or target-specific materialization.");
        }
        return summary.toString();
    }

    private String buildRecommendedAction(
            ArrayNode existingCoverage,
            ArrayNode requiredApprovals,
            boolean persistedDefinition) {
        if (!existingCoverage.isEmpty()) {
            return "review_existing_coverage";
        }
        if (!requiredApprovals.isEmpty()) {
            return "request_approval";
        }
        return persistedDefinition ? "materialize_or_activate" : "persist_definition";
    }

    private String buildPublicationReadiness(
            ArrayNode existingCoverage,
            ArrayNode requiredApprovals,
            boolean persistedDefinition) {
        if (!existingCoverage.isEmpty()) {
            return "blocked_by_existing_coverage";
        }
        if (!requiredApprovals.isEmpty()) {
            return "approval_required";
        }
        return persistedDefinition ? "ready_to_publish" : "ready_for_definition_review";
    }

    private boolean isPublishableDefinitionStatus(String status) {
        return "draft".equals(status) || "proposed".equals(status) || "approved".equals(status) || "active".equals(status);
    }

    private JsonNode withPublicationDiagnostics(JsonNode explainability, ArrayNode materializationOutcomes) {
        ObjectNode copy = explainability != null && explainability.isObject()
                ? ((ObjectNode) explainability).deepCopy()
                : objectMapper.createObjectNode();
        JsonNode existingDiagnostics = copy.get("publicationDiagnostics");
        ObjectNode diagnostics = existingDiagnostics != null && existingDiagnostics.isObject()
                ? (ObjectNode) existingDiagnostics
                : copy.putObject("publicationDiagnostics");
        diagnostics.set("materializationOutcomes", materializationOutcomes.deepCopy());
        return copy;
    }

    private JsonNode withBlockedPublicationDiagnostics(
            JsonNode explainability,
            String publicationReadiness,
            DomainRuleDefinition definition) {
        ObjectNode copy = explainability != null && explainability.isObject()
                ? ((ObjectNode) explainability).deepCopy()
                : objectMapper.createObjectNode();
        JsonNode existingDiagnostics = copy.get("publicationDiagnostics");
        ObjectNode diagnostics = existingDiagnostics != null && existingDiagnostics.isObject()
                ? (ObjectNode) existingDiagnostics
                : copy.putObject("publicationDiagnostics");
        diagnostics.put("publicationStatus", "blocked");
        if (StringUtils.hasText(publicationReadiness)) {
            diagnostics.put("publicationReadiness", publicationReadiness);
            diagnostics.put("blockedReason", blockedPublicationReason(publicationReadiness));
        }
        if (definition != null && StringUtils.hasText(definition.getStatus())) {
            diagnostics.put("definitionStatusAtResolution", definition.getStatus());
        }
        diagnostics.set("materializationOutcomes", objectMapper.createArrayNode());
        return copy;
    }

    private String blockedPublicationReason(String publicationReadiness) {
        return switch (publicationReadiness) {
            case "blocked_by_definition_status" -> "definition_status_not_publishable";
            case "blocked_by_existing_coverage" -> "existing_coverage";
            case "approval_required" -> "approval_required";
            case "ready_for_definition_review" -> "definition_not_persisted";
            default -> publicationReadiness;
        };
    }

    private void addMaterializationOutcome(
            ArrayNode materializationOutcomes,
            DomainRuleMaterialization materialization,
            String resolution,
            String reason) {
        ObjectNode outcome = materializationOutcomes.addObject();
        outcome.put("resolution", resolution);
        if (StringUtils.hasText(reason)) {
            outcome.put("reason", reason);
        }
        if (materialization.getId() != null) {
            outcome.put("materializationId", materialization.getId().toString());
        }
        outcome.put("materializationKey", materialization.getMaterializationKey());
        outcome.put("targetLayer", materialization.getTargetLayer());
        outcome.put("targetArtifactType", materialization.getTargetArtifactType());
        outcome.put("targetArtifactKey", materialization.getTargetArtifactKey());
        outcome.put("statusAtResolution", materialization.getStatus());
        if (StringUtils.hasText(materialization.getSourceHash())) {
            outcome.put("sourceHash", materialization.getSourceHash());
        }
    }

    private List<DomainRuleMaterializationResponse> publishMaterializations(
            DomainRuleDefinition definition,
            DomainRulePublicationRequest request,
            JsonNode predictedMaterializations,
            String tenantId,
            String environment,
            Instant now,
            ArrayNode materializationOutcomes) {
        boolean applyEligibleMaterializations = request.applyEligibleMaterializations() == null
                || request.applyEligibleMaterializations();
        if (!applyEligibleMaterializations && (request.materializationIds() == null || request.materializationIds().isEmpty())) {
            ObjectNode outcome = materializationOutcomes.addObject();
            outcome.put("resolution", "skipped");
            outcome.put("reason", "applyEligibleMaterializations=false");
            return List.of();
        }

        List<DomainRuleMaterialization> candidates;
        if (request.materializationIds() != null && !request.materializationIds().isEmpty()) {
            candidates = request.materializationIds().stream()
                    .map(id -> materializationRepository.findById(id)
                            .orElseThrow(() -> new ConfigurationIngestionException("Rule materialization not found: " + id)))
                    .toList();
            candidates.forEach(materialization ->
                    addMaterializationOutcome(materializationOutcomes, materialization, "selected_explicit", null));
        } else {
            candidates = materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                    normalize(tenantId),
                    normalize(environment),
                    definition.getId());
            if (candidates.isEmpty()) {
                candidates = createEligibleMaterializationsFromPredictions(
                        definition,
                        predictedMaterializations,
                        tenantId,
                        environment,
                        materializationOutcomes);
            } else {
                candidates.forEach(materialization ->
                        addMaterializationOutcome(
                                materializationOutcomes,
                                materialization,
                                isDerivedMaterialization(materialization) ? "reused" : "selected_existing",
                                null));
            }
        }

        return candidates.stream()
                .peek(materialization -> {
                    requireScope(materialization.getTenantId(), tenantId, "tenantId");
                    requireScope(materialization.getEnvironment(), environment, "environment");
                    if (!definition.getId().equals(materialization.getRuleDefinition().getId())) {
                        throw new ConfigurationIngestionException(
                                "Rule materialization does not belong to definition " + definition.getId());
                    }
                    if (!isPublishableMaterializationStatus(materialization.getStatus())) {
                        addMaterializationOutcome(
                                materializationOutcomes,
                                materialization,
                                "blocked",
                                "status_not_publishable:" + materialization.getStatus());
                        throw new ConfigurationIngestionException(
                                "Rule materialization status is not publishable: " + materialization.getStatus());
                    }
                })
                .map(materialization -> maybeApplyMaterialization(materialization, request, now))
                .map(this::toResponse)
                .toList();
    }

    private boolean isPublishableMaterializationStatus(String status) {
        return "draft".equals(status) || "pending_review".equals(status) || "applied".equals(status);
    }

    private boolean isDerivedMaterialization(DomainRuleMaterialization materialization) {
        return materialization != null
                && StringUtils.hasText(materialization.getSourceHash())
                && materialization.getSourceHash().startsWith("derived:sha256:");
    }

    private List<DomainRuleMaterialization> createEligibleMaterializationsFromPredictions(
            DomainRuleDefinition definition,
            JsonNode predictedMaterializations,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        if (predictedMaterializations == null || !predictedMaterializations.isArray()) {
            return List.of();
        }
        return predictedMaterializations.findParents("targetLayer").stream()
                .filter(JsonNode::isObject)
                .map(target -> createEligibleMaterialization(definition, target, tenantId, environment, materializationOutcomes))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private DomainRuleMaterialization createEligibleMaterialization(
            DomainRuleDefinition definition,
            JsonNode target,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        String targetLayer = normalize(target.path("targetLayer").asText(null));
        String targetArtifactType = normalize(target.path("targetArtifactType").asText(null));
        String targetArtifactKey = normalize(target.path("targetArtifactKey").asText(null));
        if (!StringUtils.hasText(targetArtifactKey)) {
            return null;
        }
        if ("option_source".equals(targetLayer) && "resource-option-source".equals(targetArtifactType)) {
            return createOptionSourceMaterialization(
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey,
                    tenantId,
                    environment,
                    materializationOutcomes);
        }
        if ("backend_validation".equals(targetLayer)
                && "resource-validation".equals(targetArtifactType)
                && isBackendValidationRuleType(definition.getRuleType())) {
            return createBackendValidationMaterialization(
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey,
                    tenantId,
                    environment,
                    materializationOutcomes);
        }
        if ("workflow_action".equals(targetLayer)
                && "resource-workflow-action".equals(targetArtifactType)
                && isWorkflowActionRuleType(definition.getRuleType())) {
            return createWorkflowActionMaterialization(
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey,
                    tenantId,
                    environment,
                    materializationOutcomes);
        }
        if ("approval_policy".equals(targetLayer)
                && "resource-action-approval".equals(targetArtifactType)
                && isApprovalPolicyRuleType(definition.getRuleType())) {
            return createApprovalPolicyMaterialization(
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey,
                    tenantId,
                    environment,
                    materializationOutcomes);
        }
        return null;
    }

    private DomainRuleMaterialization createOptionSourceMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        ObjectNode payload = buildOptionSourceMaterializedPayload(
                definition,
                targetArtifactKey);
        return createOrReuseDerivedMaterialization(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                "/selectionPolicy",
                "selection-policy",
                payload,
                tenantId,
                environment,
                materializationOutcomes);
    }

    private DomainRuleMaterialization createBackendValidationMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        ObjectNode payload = buildBackendValidationMaterializedPayload(
                definition,
                targetArtifactKey);
        return createOrReuseDerivedMaterialization(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                "/validationPolicy",
                "backend-validation-policy",
                payload,
                tenantId,
                environment,
                materializationOutcomes);
    }

    private DomainRuleMaterialization createWorkflowActionMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        ObjectNode payload = buildWorkflowActionMaterializedPayload(
                definition,
                targetArtifactKey);
        return createOrReuseDerivedMaterialization(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                "/availabilityPolicy",
                "workflow-action-policy",
                payload,
                tenantId,
                environment,
                materializationOutcomes);
    }

    private DomainRuleMaterialization createApprovalPolicyMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        ObjectNode payload = buildApprovalPolicyMaterializedPayload(
                definition,
                targetArtifactKey);
        return createOrReuseDerivedMaterialization(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                "/approvalPolicy",
                "approval-policy",
                payload,
                tenantId,
                environment,
                materializationOutcomes);
    }

    private DomainRuleMaterialization createOrReuseDerivedMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String targetPointer,
            String materializedRuleId,
            JsonNode payload,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        String materializationKey = definition.getRuleKey() + ":" + targetLayer + ":" + targetArtifactKey;
        String sourceHash = derivedSourceHash(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                targetPointer,
                materializedRuleId,
                payload);
        var existing = materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                normalize(tenantId),
                normalize(environment),
                materializationKey);
        if (existing.isPresent()) {
            requireReusableDerivedMaterialization(
                    existing.get(),
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey,
                    sourceHash);
            addMaterializationOutcome(materializationOutcomes, existing.get(), "reused", null);
            return existing.get();
        }
        DomainRuleMaterialization materialization = new DomainRuleMaterialization();
        materialization.setTenantId(normalize(tenantId));
        materialization.setEnvironment(normalize(environment));
        materialization.setRuleDefinition(definition);
        materialization.setMaterializationKey(materializationKey);
        materialization.setTargetLayer(targetLayer);
        materialization.setTargetArtifactType(targetArtifactType);
        materialization.setTargetArtifactKey(targetArtifactKey);
        materialization.setTargetPointer(targetPointer);
        materialization.setMaterializedRuleId(materializedRuleId);
        materialization.setStatus("pending_review");
        materialization.setMaterializedPayload(write(payload));
        materialization.setSourceHash(sourceHash);
        DomainRuleMaterialization saved = materializationRepository.save(materialization);
        addMaterializationOutcome(materializationOutcomes, saved, "created", null);
        return saved;
    }

    private void requireReusableDerivedMaterialization(
            DomainRuleMaterialization existing,
            DomainRuleDefinition requestedDefinition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String sourceHash) {
        requireReusableMaterialization(
                existing,
                requestedDefinition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                sourceHash);
        if (!StringUtils.hasText(existing.getSourceHash())) {
            throw new ConfigurationIngestionException(
                    "Derived rule materialization key already exists without a sourceHash: "
                            + existing.getMaterializationKey());
        }
    }

    private static String derivedSourceHash(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String targetPointer,
            String materializedRuleId,
            JsonNode materializedPayload) {
        String source = String.join(
                "\n",
                nullToEmpty(definition.getRuleKey()),
                nullToEmpty(definition.getVersion()),
                nullToEmpty(definition.getRuleType()),
                nullToEmpty(definition.getContextKey()),
                nullToEmpty(definition.getResourceKey()),
                nullToEmpty(definition.getServiceKey()),
                nullToEmpty(definition.getDefinition()),
                nullToEmpty(definition.getParameters()),
                nullToEmpty(definition.getCondition()),
                nullToEmpty(definition.getGovernance()),
                nullToEmpty(targetLayer),
                nullToEmpty(targetArtifactType),
                nullToEmpty(targetArtifactKey),
                nullToEmpty(targetPointer),
                nullToEmpty(materializedRuleId),
                materializedPayload != null ? materializedPayload.toString() : "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "derived:sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private static String nullToEmpty(Object value) {
        return value != null ? value.toString() : "";
    }

    private boolean isBackendValidationRuleType(String ruleType) {
        return "validation".equals(ruleType)
                || "compliance".equals(ruleType)
                || "privacy".equals(ruleType)
                || "selection_eligibility".equals(ruleType);
    }

    private boolean isWorkflowActionRuleType(String ruleType) {
        return "workflow_action_policy".equals(ruleType);
    }

    private boolean isApprovalPolicyRuleType(String ruleType) {
        return "approval_policy".equals(ruleType);
    }

    private String deriveActionApprovalKey(String resourceKey, JsonNode parameters) {
        String actionResourceKey = resourceKey;
        if (parameters != null && parameters.isObject()) {
            String explicitResource = normalize(parameters.path("resourceKey").asText(null));
            if (StringUtils.hasText(explicitResource)) {
                actionResourceKey = explicitResource;
            }
            JsonNode approvalAction = parameters.path("approvalAction");
            if (approvalAction.isObject()) {
                explicitResource = normalize(approvalAction.path("resourceKey").asText(null));
                if (StringUtils.hasText(explicitResource)) {
                    actionResourceKey = explicitResource;
                }
            }
            JsonNode workflowAction = parameters.path("workflowAction");
            if (workflowAction.isObject()) {
                explicitResource = normalize(workflowAction.path("resourceKey").asText(null));
                if (StringUtils.hasText(explicitResource)) {
                    actionResourceKey = explicitResource;
                }
            }
        }
        return normalizeOrDefault(actionResourceKey, resourceKey) + ":" + deriveActionApprovalId(null, parameters);
    }

    private String deriveActionApprovalResourceKey(DomainRuleDefinition definition, JsonNode parameters) {
        if (parameters != null && parameters.isObject()) {
            String explicit = normalize(parameters.path("resourceKey").asText(null));
            if (StringUtils.hasText(explicit)) {
                return explicit;
            }
            JsonNode approvalAction = parameters.path("approvalAction");
            if (approvalAction.isObject()) {
                explicit = normalize(approvalAction.path("resourceKey").asText(null));
                if (StringUtils.hasText(explicit)) {
                    return explicit;
                }
            }
            JsonNode workflowAction = parameters.path("workflowAction");
            if (workflowAction.isObject()) {
                explicit = normalize(workflowAction.path("resourceKey").asText(null));
                if (StringUtils.hasText(explicit)) {
                    return explicit;
                }
            }
        }
        return normalize(definition.getResourceKey());
    }

    private String deriveActionApprovalId(String targetArtifactKey, JsonNode parameters) {
        if (StringUtils.hasText(targetArtifactKey)) {
            int separator = targetArtifactKey.lastIndexOf(':');
            return separator >= 0 && separator + 1 < targetArtifactKey.length()
                    ? targetArtifactKey.substring(separator + 1)
                    : targetArtifactKey;
        }
        if (parameters != null && parameters.isObject()) {
            String explicit = normalize(parameters.path("actionId").asText(null));
            if (StringUtils.hasText(explicit)) {
                return explicit;
            }
            JsonNode approvalAction = parameters.path("approvalAction");
            if (approvalAction.isObject()) {
                explicit = normalize(approvalAction.path("actionId").asText(null));
                if (StringUtils.hasText(explicit)) {
                    return explicit;
                }
            }
            JsonNode workflowAction = parameters.path("workflowAction");
            if (workflowAction.isObject()) {
                explicit = normalize(workflowAction.path("actionId").asText(null));
                if (StringUtils.hasText(explicit)) {
                    return explicit;
                }
            }
        }
        return "default";
    }

    private String deriveWorkflowActionKey(String resourceKey, JsonNode parameters) {
        String actionResourceKey = resourceKey;
        if (parameters != null && parameters.isObject()) {
            String explicitResource = normalize(parameters.path("resourceKey").asText(null));
            if (StringUtils.hasText(explicitResource)) {
                actionResourceKey = explicitResource;
            }
            JsonNode workflowAction = parameters.path("workflowAction");
            if (workflowAction.isObject()) {
                explicitResource = normalize(workflowAction.path("resourceKey").asText(null));
                if (StringUtils.hasText(explicitResource)) {
                    actionResourceKey = explicitResource;
                }
            }
        }
        return normalizeOrDefault(actionResourceKey, resourceKey) + ":" + deriveWorkflowActionId(null, parameters);
    }

    private String deriveWorkflowActionResourceKey(DomainRuleDefinition definition, JsonNode parameters) {
        if (parameters != null && parameters.isObject()) {
            String explicit = normalize(parameters.path("resourceKey").asText(null));
            if (StringUtils.hasText(explicit)) {
                return explicit;
            }
            JsonNode workflowAction = parameters.path("workflowAction");
            if (workflowAction.isObject()) {
                explicit = normalize(workflowAction.path("resourceKey").asText(null));
                if (StringUtils.hasText(explicit)) {
                    return explicit;
                }
            }
        }
        return normalize(definition.getResourceKey());
    }

    private String deriveWorkflowActionId(String targetArtifactKey, JsonNode parameters) {
        if (StringUtils.hasText(targetArtifactKey)) {
            int separator = targetArtifactKey.lastIndexOf(':');
            return separator >= 0 && separator + 1 < targetArtifactKey.length()
                    ? targetArtifactKey.substring(separator + 1)
                    : targetArtifactKey;
        }
        if (parameters != null && parameters.isObject()) {
            String explicit = normalize(parameters.path("actionId").asText(null));
            if (StringUtils.hasText(explicit)) {
                return explicit;
            }
            JsonNode workflowAction = parameters.path("workflowAction");
            if (workflowAction.isObject()) {
                explicit = normalize(workflowAction.path("actionId").asText(null));
                if (StringUtils.hasText(explicit)) {
                    return explicit;
                }
            }
        }
        return "default";
    }

    private DomainRuleMaterialization maybeApplyMaterialization(
            DomainRuleMaterialization materialization,
            DomainRulePublicationRequest request,
            Instant now) {
        if ("draft".equals(materialization.getStatus()) || "pending_review".equals(materialization.getStatus())) {
            materialization.setStatus("applied");
            materialization.setAppliedByType(normalizeOrDefault(request.publishedByType(), "human"));
            materialization.setAppliedBy(normalize(request.publishedBy()));
            if (materialization.getAppliedAt() == null) {
                materialization.setAppliedAt(now);
            }
            return materializationRepository.save(materialization);
        }
        return materialization;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
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

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainRuleTimelineResponse definitionTimeline(
            UUID definitionId,
            String tenantId,
            String environment) {
        if (definitionId == null) {
            throw new ConfigurationIngestionException("definitionId is required");
        }
        DomainRuleDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ConfigurationIngestionException("Rule definition not found: " + definitionId));
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");

        ArrayList<DomainRuleTimelineEventResponse> events = new ArrayList<>();
        addDefinitionTimelineEvents(events, definition);
        materializationRepository
                .findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                        normalize(tenantId),
                        normalize(environment),
                        definitionId)
                .forEach(materialization -> addMaterializationTimelineEvents(events, materialization));
        events.sort(Comparator
                .comparing(DomainRuleTimelineEventResponse::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DomainRuleTimelineEventResponse::eventType, Comparator.nullsLast(Comparator.naturalOrder())));

        return new DomainRuleTimelineResponse(
                definition.getId(),
                definition.getTenantId(),
                definition.getEnvironment(),
                definition.getRuleKey(),
                definition.getVersion(),
                definition.getRuleType(),
                definition.getResourceKey(),
                definition.getServiceKey(),
                List.copyOf(events));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
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
        requireAllowedMaterializationTransition(materialization.getStatus(), status);
        if ("applied".equals(status) && !hasActiveDefinition(materialization)) {
            throw new ConfigurationIngestionException("Rule materialization can only be applied when its definition is active");
        }

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

    private void addDefinitionTimelineEvents(
            List<DomainRuleTimelineEventResponse> events,
            DomainRuleDefinition definition) {
        addTimelineEvent(
                events,
                "definition.created",
                definition.getCreatedAt(),
                definition.getCreatedByType(),
                definition.getCreatedBy(),
                "Decision definition created",
                definition.getStatus(),
                null);
        addTimelineEvent(
                events,
                "definition.approved",
                definition.getApprovedAt(),
                "human",
                definition.getApprovedBy(),
                "Decision definition approved",
                definition.getStatus(),
                null);
        addTimelineEvent(
                events,
                "definition.activated",
                definition.getActivatedAt(),
                "human",
                definition.getApprovedBy(),
                "Decision definition activated",
                definition.getStatus(),
                null);
    }

    private void addMaterializationTimelineEvents(
            List<DomainRuleTimelineEventResponse> events,
            DomainRuleMaterialization materialization) {
        addTimelineEvent(
                events,
                "materialization.created",
                materialization.getCreatedAt(),
                "system",
                "domain-rule-materialization",
                "Decision materialization created",
                materialization.getStatus(),
                materialization);
        addTimelineEvent(
                events,
                "materialization.applied",
                materialization.getAppliedAt(),
                materialization.getAppliedByType(),
                materialization.getAppliedBy(),
                "Decision materialization applied",
                materialization.getStatus(),
                materialization);
    }

    private void addTimelineEvent(
            List<DomainRuleTimelineEventResponse> events,
            String eventType,
            Instant occurredAt,
            String actorType,
            String actor,
            String summary,
            String status,
            DomainRuleMaterialization materialization) {
        if (occurredAt == null) {
            return;
        }
        events.add(new DomainRuleTimelineEventResponse(
                eventType,
                occurredAt,
                normalize(actorType),
                normalize(actor),
                summary,
                normalize(status),
                materialization != null ? materialization.getTargetLayer() : null,
                materialization != null ? materialization.getTargetArtifactType() : null,
                materialization != null ? materialization.getTargetArtifactKey() : null,
                materialization != null ? materialization.getId() : null,
                materialization != null ? materialization.getMaterializationKey() : null,
                materialization != null ? materialization.getSourceHash() : null,
                "safe"));
    }

    private boolean hasActiveDefinition(DomainRuleMaterialization materialization) {
        DomainRuleDefinition definition = materialization.getRuleDefinition();
        return definition != null && "active".equals(definition.getStatus());
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
                buildMaterializationDecisionDiagnostics(materialization, definition),
                materialization.getAppliedByType(),
                materialization.getAppliedBy(),
                materialization.getCreatedAt(),
                materialization.getUpdatedAt(),
                materialization.getAppliedAt());
    }

    private ObjectNode buildMaterializationDecisionDiagnostics(
            DomainRuleMaterialization materialization,
            DomainRuleDefinition definition) {
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("decisionKind", "semantic_domain_rule");
        diagnostics.put("authoringMode", "governed");
        diagnostics.put("decisionStage", "materialization");
        diagnostics.put("decisionSource", "materialization_record");
        diagnostics.put("canonicalOwner", "praxis-config-starter");
        diagnostics.put("materializationModel", "derived_projection");
        diagnostics.put("runtimeSurfacesAreDerived", true);
        if (definition != null) {
            diagnostics.put("ruleKey", definition.getRuleKey());
            diagnostics.put("ruleVersion", definition.getVersion());
            diagnostics.put("ruleType", definition.getRuleType());
            diagnostics.put("resourceKey", definition.getResourceKey());
            if (StringUtils.hasText(definition.getContextKey())) {
                diagnostics.put("contextKey", definition.getContextKey());
            }
            if (StringUtils.hasText(definition.getServiceKey())) {
                diagnostics.put("serviceKey", definition.getServiceKey());
            }
        }
        diagnostics.put("materializationKey", materialization.getMaterializationKey());
        diagnostics.put("targetLayer", materialization.getTargetLayer());
        diagnostics.put("targetArtifactType", materialization.getTargetArtifactType());
        diagnostics.put("targetArtifactKey", materialization.getTargetArtifactKey());
        if (StringUtils.hasText(materialization.getTargetPointer())) {
            diagnostics.put("targetPointer", materialization.getTargetPointer());
        }
        if (StringUtils.hasText(materialization.getMaterializedRuleId())) {
            diagnostics.put("materializedRuleId", materialization.getMaterializedRuleId());
        }
        diagnostics.put("status", materialization.getStatus());
        diagnostics.put("sourceHashPresent", StringUtils.hasText(materialization.getSourceHash()));
        if (StringUtils.hasText(materialization.getSourceHash())) {
            diagnostics.put("sourceHash", materialization.getSourceHash());
        }
        return diagnostics;
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

    private void requireAllowedDefinitionTransition(String currentStatus, String requestedStatus) {
        if (isSameStatus(currentStatus, requestedStatus) || isAllowedDefinitionTransition(currentStatus, requestedStatus)) {
            return;
        }
        throw new ConfigurationIngestionException(
                "Rule definition status transition is not allowed: "
                        + nullToEmpty(currentStatus)
                        + " -> "
                        + requestedStatus);
    }

    private boolean isAllowedDefinitionTransition(String currentStatus, String requestedStatus) {
        return switch (nullToEmpty(currentStatus)) {
            case "draft" -> List.of("proposed", "approved", "rejected", "retired").contains(requestedStatus);
            case "proposed" -> List.of("draft", "approved", "rejected", "retired").contains(requestedStatus);
            case "approved" -> List.of("active", "rejected", "deprecated", "retired").contains(requestedStatus);
            case "active" -> List.of("deprecated", "retired").contains(requestedStatus);
            case "deprecated" -> List.of("active", "retired").contains(requestedStatus);
            default -> false;
        };
    }

    private void requireAllowedMaterializationTransition(String currentStatus, String requestedStatus) {
        if (isSameStatus(currentStatus, requestedStatus) || isAllowedMaterializationTransition(currentStatus, requestedStatus)) {
            return;
        }
        throw new ConfigurationIngestionException(
                "Rule materialization status transition is not allowed: "
                        + nullToEmpty(currentStatus)
                        + " -> "
                        + requestedStatus);
    }

    private boolean isAllowedMaterializationTransition(String currentStatus, String requestedStatus) {
        return switch (nullToEmpty(currentStatus)) {
            case "draft" -> List.of("pending_review", "applied", "failed", "reverted").contains(requestedStatus);
            case "pending_review" -> List.of("draft", "applied", "failed", "reverted").contains(requestedStatus);
            case "applied" -> List.of("superseded", "reverted").contains(requestedStatus);
            case "failed", "superseded", "reverted" -> List.of("draft", "pending_review").contains(requestedStatus);
            default -> false;
        };
    }

    private boolean isSameStatus(String currentStatus, String requestedStatus) {
        return nullToEmpty(currentStatus).equals(requestedStatus);
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
