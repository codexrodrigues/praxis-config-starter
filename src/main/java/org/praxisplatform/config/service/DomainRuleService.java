package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
                resourceKey,
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
                    simulation.explainability(),
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

        List<DomainRuleMaterializationResponse> publishedMaterializations = publishMaterializations(
                definition,
                request,
                simulation.predictedMaterializations(),
                tenantId,
                environment,
                now);

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
                simulation.explainability(),
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
        materialization.setMaterializedPayload(write(deriveMaterializedPayload(definition, request)));
        materialization.setSourceHash(normalize(request.sourceHash()));
        if (request.validationResult() != null && !request.validationResult().isNull()) {
            materialization.setValidationResult(write(request.validationResult()));
        }
        materialization.setAppliedByType(normalize(request.appliedByType()));
        materialization.setAppliedBy(normalize(request.appliedBy()));
        return toResponse(materializationRepository.save(materialization));
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

        if ("validation".equals(ruleType) || "compliance".equals(ruleType) || "privacy".equals(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "backend_validation");
            target.put("targetArtifactType", "resource-validation");
            target.put("targetArtifactKey", resourceKey);
            target.put("operation", "validation_rule.review");
        }

        if ("workflow".equals(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "workflow");
            target.put("targetArtifactType", "workflow-policy");
            target.put("targetArtifactKey", resourceKey);
            target.put("operation", "workflow_rule.review");
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
            String resourceKey,
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

    private List<DomainRuleMaterializationResponse> publishMaterializations(
            DomainRuleDefinition definition,
            DomainRulePublicationRequest request,
            JsonNode predictedMaterializations,
            String tenantId,
            String environment,
            Instant now) {
        boolean applyEligibleMaterializations = request.applyEligibleMaterializations() == null
                || request.applyEligibleMaterializations();
        if (!applyEligibleMaterializations && (request.materializationIds() == null || request.materializationIds().isEmpty())) {
            return List.of();
        }

        List<DomainRuleMaterialization> candidates;
        if (request.materializationIds() != null && !request.materializationIds().isEmpty()) {
            candidates = request.materializationIds().stream()
                    .map(id -> materializationRepository.findById(id)
                            .orElseThrow(() -> new ConfigurationIngestionException("Rule materialization not found: " + id)))
                    .toList();
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
                        environment);
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
                })
                .map(materialization -> maybeApplyMaterialization(materialization, request, now))
                .map(this::toResponse)
                .toList();
    }

    private List<DomainRuleMaterialization> createEligibleMaterializationsFromPredictions(
            DomainRuleDefinition definition,
            JsonNode predictedMaterializations,
            String tenantId,
            String environment) {
        if (predictedMaterializations == null || !predictedMaterializations.isArray()) {
            return List.of();
        }
        return predictedMaterializations.findParents("targetLayer").stream()
                .filter(JsonNode::isObject)
                .map(target -> createEligibleMaterialization(definition, target, tenantId, environment))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private DomainRuleMaterialization createEligibleMaterialization(
            DomainRuleDefinition definition,
            JsonNode target,
            String tenantId,
            String environment) {
        String targetLayer = normalize(target.path("targetLayer").asText(null));
        String targetArtifactType = normalize(target.path("targetArtifactType").asText(null));
        String targetArtifactKey = normalize(target.path("targetArtifactKey").asText(null));
        if (!StringUtils.hasText(targetArtifactKey)) {
            return null;
        }
        if ("option_source".equals(targetLayer) && "resource-option-source".equals(targetArtifactType)) {
            return createOptionSourceMaterialization(definition, targetLayer, targetArtifactType, targetArtifactKey, tenantId, environment);
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
                    environment);
        }
        return null;
    }

    private DomainRuleMaterialization createOptionSourceMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment) {
        DomainRuleMaterialization materialization = new DomainRuleMaterialization();
        materialization.setTenantId(normalize(tenantId));
        materialization.setEnvironment(normalize(environment));
        materialization.setRuleDefinition(definition);
        materialization.setMaterializationKey(
                definition.getRuleKey() + ":" + targetLayer + ":" + targetArtifactKey);
        materialization.setTargetLayer(targetLayer);
        materialization.setTargetArtifactType(targetArtifactType);
        materialization.setTargetArtifactKey(targetArtifactKey);
        materialization.setTargetPointer("/selectionPolicy");
        materialization.setMaterializedRuleId("selection-policy");
        materialization.setStatus("pending_review");
        materialization.setMaterializedPayload(write(buildOptionSourceMaterializedPayload(
                definition,
                targetArtifactKey)));
        materialization.setSourceHash(derivedSourceHash(definition, targetLayer, targetArtifactKey));
        return materializationRepository.save(materialization);
    }

    private DomainRuleMaterialization createBackendValidationMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment) {
        DomainRuleMaterialization materialization = new DomainRuleMaterialization();
        materialization.setTenantId(normalize(tenantId));
        materialization.setEnvironment(normalize(environment));
        materialization.setRuleDefinition(definition);
        materialization.setMaterializationKey(
                definition.getRuleKey() + ":" + targetLayer + ":" + targetArtifactKey);
        materialization.setTargetLayer(targetLayer);
        materialization.setTargetArtifactType(targetArtifactType);
        materialization.setTargetArtifactKey(targetArtifactKey);
        materialization.setTargetPointer("/validationPolicy");
        materialization.setMaterializedRuleId("backend-validation-policy");
        materialization.setStatus("pending_review");
        materialization.setMaterializedPayload(write(buildBackendValidationMaterializedPayload(
                definition,
                targetArtifactKey)));
        materialization.setSourceHash(derivedSourceHash(definition, targetLayer, targetArtifactKey));
        return materializationRepository.save(materialization);
    }

    private static String derivedSourceHash(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactKey) {
        String source = definition.getRuleKey() + ":" + targetLayer + ":" + targetArtifactKey;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "derived:sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }

    private boolean isBackendValidationRuleType(String ruleType) {
        return "validation".equals(ruleType)
                || "compliance".equals(ruleType)
                || "privacy".equals(ruleType);
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
