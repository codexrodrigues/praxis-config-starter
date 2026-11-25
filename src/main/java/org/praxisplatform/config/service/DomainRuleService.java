package org.praxisplatform.config.service;

import static org.praxisplatform.config.domain.DomainRuleDefinition.AUTHORIZATION_POLICY_RULE_TYPE;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.authz.contracts.policy.AuthorizationPolicyDefinition;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleEvent;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleFactCatalogResponse;
import org.praxisplatform.config.dto.DomainRuleFactDescriptor;
import org.praxisplatform.config.dto.DomainRuleIntakeRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRulePublicationRequest;
import org.praxisplatform.config.dto.DomainRulePublicationResponse;
import org.praxisplatform.config.dto.DomainRuleReactiveDeterminationSpec;
import org.praxisplatform.config.dto.DomainRuleSimulationRequest;
import org.praxisplatform.config.dto.DomainRuleSimulationResponse;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleTimelineEventResponse;
import org.praxisplatform.config.dto.DomainRuleTimelineResponse;
import org.praxisplatform.config.dto.DomainRuleWorkspaceBlocker;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionApprovalRepository;
import org.praxisplatform.config.repository.DomainRuleEventRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.praxisplatform.rules.facts.RuleFactDescriptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnBean({DomainRuleDefinitionRepository.class, DomainRuleMaterializationRepository.class, DomainRuleEventRepository.class})
public class DomainRuleService {

    private static final List<String> DEFINITION_STATUSES = List.of(
            "draft", "proposed", "approved", "active", "deprecated", "retired", "rejected");
    private static final List<String> INITIAL_DEFINITION_STATUSES = List.of("draft", "proposed");
    private static final List<String> MATERIALIZATION_STATUSES = List.of(
            "draft", "pending_review", "applied", "failed", "superseded", "reverted");
    private static final List<String> INITIAL_MATERIALIZATION_STATUSES = List.of("draft", "pending_review");
    private static final List<String> COVERAGE_STATUSES = List.of("approved", "active");
    private static final String OPA_TARGET_LAYER = "policy_engine";
    private static final String OPA_ARTIFACT_TYPE = "opa-policy";
    private static final String OPA_PUBLICATION_REQUIRED = "requires_opa_publication";
    private static final String OPA_PUBLICATION_MESSAGE =
            "Authorization policy activation and materialization require governed OPA publication";
    private static final String BACKEND_DETERMINATION_TARGET_LAYER = "backend_determination";
    private static final String REACTIVE_DETERMINATION_ARTIFACT_TYPE = "resource-reactive-determination";
    private static final String REACTIVE_DETERMINATION_TARGET_POINTER = "/determination";
    private static final String REACTIVE_DETERMINATION_RULE_ID = "backend-reactive-determination";
    private static final String REACTIVE_DETERMINATION_SCHEMA_VERSION =
            "praxis.backend-reactive-determination.v1";
    private static final String COLOR_PALETTE_RULE_TYPE = "design_token_palette";
    private static final String COLOR_PALETTE_TARGET_LAYER = "design_token_catalog";
    private static final String COLOR_PALETTE_ARTIFACT_TYPE = "governed-color-palette";
    private static final int MAX_REACTIVE_DETERMINATION_BINDINGS = 64;
    private static final Pattern STABLE_OPERATION_ID =
            Pattern.compile("^[A-Za-z][A-Za-z0-9._:-]{0,254}$");
    private static final Pattern STABLE_DETERMINATION_KEY =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$");
    private static final Pattern STABLE_FACT_PATH =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{0,254}$");
    private static final String FACT_CATALOG_SCHEMA_VERSION = "praxis.domain-rule-fact-catalog.v1";
    private static final Set<String> FACT_VALUE_TYPES = Set.of(
            "boolean", "string", "number", "date", "string-array", "date-array", "number-array", "array<number>");
    private static final Set<String> FACT_SENSITIVITIES = Set.of(
            "NON_SENSITIVE", "PERSONAL", "SENSITIVE", "SECRET");
    private static final Set<String> FACT_REDACTION_POLICIES = Set.of(
            "NONE", "MASK", "HASH", "OMIT");
    private static final Pattern FACT_GROUP_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Set<String> FACT_VALUE_PRESENTATIONS = Set.of(
            "currency", "percentage", "date", "datetime", "time", "boolean",
            "number", "integer", "duration", "identifier", "text");
    private static final Set<String> FACT_CLASSIFICATIONS = Set.of(
            "public", "internal", "confidential", "restricted");
    private static final Set<String> FACT_AI_VISIBILITIES = Set.of(
            "allow", "mask", "summarize_only", "deny");
    private static final int MAX_FACT_HINTS_LENGTH = 500;
    private static final int MAX_FACT_GROUP_LABEL_LENGTH = 120;
    private static final int MAX_FACT_UNIT_LENGTH = 32;
    private static final int MAX_FACT_MASK_LENGTH = 64;
    private static final int MAX_FACT_ALLOWED_VALUES = 64;
    private static final int MAX_FACT_EXAMPLE_LENGTH = 256;
    private static final int MAX_FACT_PATTERN_LENGTH = 256;
    private static final int MAX_FACT_ICON_LENGTH = 64;
    private static final int MAX_FACT_TONE_LENGTH = 32;
    private static final int MAX_FACT_OPTION_DESCRIPTION_LENGTH = 256;
    private static final int MAX_FACT_SOURCE_KEY_LENGTH = 128;
    private static final int MAX_FACT_RESOURCE_KEY_LENGTH = 255;
    private static final int MAX_FACT_DATA_CATEGORY_LENGTH = 64;
    private static final int MAX_FACT_COMPLIANCE_TAGS = 32;
    private static final int MAX_FACT_ATTRIBUTE_ID_LENGTH = 255;
    private static final int MAX_FACT_TRANSFORMATION_LENGTH = 64;

    private final DomainRuleDefinitionRepository definitionRepository;
    private final DomainRuleMaterializationRepository materializationRepository;
    private final DomainRuleEventRepository eventRepository;
    private final DomainCatalogReleaseRepository releaseRepository;
    private final DomainKnowledgeChangeSetRepository changeSetRepository;
    private final DomainRuleDefinitionApprovalRepository definitionApprovalRepository;
    private final DomainRuleDefinitionFingerprint definitionFingerprint;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DomainRuleDefinitionEvidenceGateService> evidenceGateProvider;
    private final GovernedColorPaletteContractValidator governedColorPaletteValidator;
    private final DomainRuleEntityRefresh lifecycleRefresh;

    /**
     * Optional read-only overlay providers injected via setter so {@code @RequiredArgsConstructor}
     * retains its 10-argument signature for unit and integration tests.
     */
    private volatile List<DomainRuleFactMetadataOverlayProvider> factMetadataOverlayProviders = List.of();
    private volatile ObjectProvider<DomainRuleFactMetadataOverlayProvider> factMetadataOverlayProviderSource;

    @Autowired(required = false)
    void setFactMetadataOverlayProviders(ObjectProvider<DomainRuleFactMetadataOverlayProvider> providers) {
        this.factMetadataOverlayProviderSource = providers;
        this.factMetadataOverlayProviders = List.of();
    }

    void setFactMetadataOverlayProviders(List<DomainRuleFactMetadataOverlayProvider> providers) {
        this.factMetadataOverlayProviderSource = null;
        this.factMetadataOverlayProviders = providers == null ? List.of() : List.copyOf(providers);
    }

    private List<DomainRuleFactMetadataOverlayProvider> resolvedFactMetadataOverlayProviders() {
        ObjectProvider<DomainRuleFactMetadataOverlayProvider> source = this.factMetadataOverlayProviderSource;
        if (source != null) {
            synchronized (this) {
                if (this.factMetadataOverlayProviderSource != null) {
                    List<DomainRuleFactMetadataOverlayProvider> resolved =
                            this.factMetadataOverlayProviderSource.orderedStream().toList();
                    this.factMetadataOverlayProviders = List.copyOf(resolved);
                    this.factMetadataOverlayProviderSource = null;
                }
            }
        }
        return this.factMetadataOverlayProviders;
    }

    void prepareLifecycleScope(DomainRuleGovernancePrincipal principal) {
        requirePrincipal(principal);
        lifecycleRefresh.requireCleanEntry();
        definitionRepository.lockLifecycleScope(principal);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainRuleIntakeResponse intake(
            DomainRuleIntakeRequest request,
            DomainRuleGovernancePrincipal principal) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule intake request is required");
        }
        requireText(request.prompt(), "prompt");
        requireText(request.ruleType(), "ruleType");
        requireText(request.resourceKey(), "resourceKey");

        String ruleType = request.ruleType().trim();
        JsonNode definition = request.definition();
        String ruleKey = normalize(request.ruleKey());
        if (AUTHORIZATION_POLICY_RULE_TYPE.equals(ruleType)) {
            if (ruleKey == null && definition != null) {
                ruleKey = definition.path("policyId").asText(null);
            }
        } else {
            if (ruleKey == null) {
                ruleKey = deriveRuleKey(request.resourceKey(), ruleType);
            }
            ObjectNode enriched = definition != null && definition.isObject()
                    ? definition.deepCopy()
                    : objectMapper.createObjectNode();
            if (!StringUtils.hasText(enriched.path("summary").asText(null))) {
                enriched.put("summary", request.prompt().trim());
            }
            enriched.put("sourcePrompt", request.prompt().trim());
            if (StringUtils.hasText(request.assistantMessage())) {
                enriched.put("assistantMessage", request.assistantMessage().trim());
            }
            definition = enriched;
        }

        DomainRuleDefinitionResponse persisted = createDefinition(
                new DomainRuleDefinitionRequest(
                        ruleKey,
                        null,
                        ruleType,
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
                        null),
                principal);

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
        ArrayNode predictedMaterializations = buildPredictedMaterializations(
                persisted.resourceKey(),
                persisted.ruleType(),
                persisted.definition(),
                persisted.parameters());
        ArrayNode existingCoverage = buildExistingCoverage(
                persisted.tenantId(),
                persisted.environment(),
                persisted.resourceKey(),
                persisted.ruleType(),
                persisted.id(),
                persisted.ruleKey(),
                persisted.version(),
                predictedMaterializations);
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

        recordIntakeReceivedEvent(
                persisted,
                existingCoverage,
                predictedMaterializations,
                requiredApprovals,
                warnings);

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
            DomainRuleGovernancePrincipal principal) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule definition request is required");
        }
        requireText(request.ruleKey(), "ruleKey");
        requireText(request.ruleType(), "ruleType");

        DomainRuleDefinition definition = new DomainRuleDefinition();
        requirePrincipal(principal);
        definition.setTenantId(principal.tenantId());
        definition.setEnvironment(principal.environment());
        definition.setRuleKey(request.ruleKey().trim());
        definition.setVersion(request.version());
        definition.setRuleType(request.ruleType().trim());
        String requestedStatus = requireAllowedStatus(
                normalizeOrDefault(request.status(), "draft"),
                "status",
                DEFINITION_STATUSES);
        JsonNode requestedGovernance = request.governance();
        JsonNode requestedDefinition = validateAndNormalizeDocumentationUrls(request.definition());
        JsonNode requestedParameters = request.parameters();
        validateAuthorizationPolicyDefinition(definition.getRuleType(), requestedDefinition);
        validateFactCatalog(requestedDefinition);
        requireDefinitionCreationGovernance(
                request.ruleType().trim(),
                requestedStatus,
                "authenticated",
                requestedDefinition,
                requestedParameters,
                requestedGovernance);
        definition.setStatus(requestedStatus);
        definition.setContextKey(normalize(request.contextKey()));
        definition.setResourceKey(normalize(request.resourceKey()));
        definition.setServiceKey(normalize(request.serviceKey()));
        definition.setSemanticOwner(normalize(request.semanticOwner()));
        definition.setSteward(normalize(request.steward()));
        DomainCatalogRelease sourceRelease = resolveRelease(request.sourceReleaseId());
        DomainKnowledgeChangeSet sourceChangeSet = resolveChangeSet(request.sourceChangeSetId());
        if (AUTHORIZATION_POLICY_RULE_TYPE.equals(definition.getRuleType())) {
            if (sourceRelease != null) {
                requireScope(sourceRelease.getTenantId(), principal.tenantId(), "sourceRelease tenantId");
                requireScope(sourceRelease.getEnvironment(), principal.environment(), "sourceRelease environment");
            }
            if (sourceChangeSet != null) {
                requireScope(sourceChangeSet.getTenantId(), principal.tenantId(), "sourceChangeSet tenantId");
                requireScope(sourceChangeSet.getEnvironment(), principal.environment(), "sourceChangeSet environment");
            }
        }
        definition.setSourceRelease(sourceRelease);
        definition.setSourceChangeSet(sourceChangeSet);
        definition.setDefinition(writeOrDefault(requestedDefinition, "{}"));
        definition.setParameters(writeOrDefault(requestedParameters, "{}"));
        definition.setCondition(writeNullable(DomainRuleParameters.condition(
                objectMapper, requestedDefinition, requestedParameters, request.condition())));
        definition.setGovernance(writeOrDefault(requestedGovernance, "{}"));
        definition.setValidationResult(writeNullable(request.validationResult()));
        definition.setCreatedByType("authenticated");
        definition.setCreatedBy(principal.actorRef());
        DomainRuleDefinition saved = definitionRepository.save(definition);
        recordDefinitionEvent(
                saved,
                "definition.created",
                saved.getCreatedAt(),
                saved.getCreatedByType(),
                saved.getCreatedBy(),
                "Decision definition created",
                saved.getStatus());
        recordApprovalRequestedEventIfRequired(saved, saved.getCreatedAt());
        return toResponse(saved);
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

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainRuleDefinitionResponse definition(
            UUID definitionId, DomainRuleGovernancePrincipal principal) {
        if (definitionId == null) {
            throw new ConfigurationIngestionException("definitionId is required");
        }
        requirePrincipal(principal);
        DomainRuleDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Rule definition not found: " + definitionId));
        requireScope(definition.getTenantId(), principal.tenantId(), "tenantId");
        requireScope(definition.getEnvironment(), principal.environment(), "environment");
        return toResponse(definition);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainRuleFactCatalogResponse definitionFacts(
            UUID definitionId, DomainRuleGovernancePrincipal principal) {
        DomainRuleDefinitionResponse definition = definition(definitionId, principal);
        JsonNode catalog = definition.definition() == null
                ? objectMapper.missingNode()
                : definition.definition().path("factCatalog");
        validateFactCatalog(definition.definition());
        List<DomainRuleFactDescriptor> baseFacts = new ArrayList<>();
        JsonNode items = catalog.path("facts");
        if (items.isArray()) {
            items.forEach(item -> baseFacts.add(toFactDescriptor(item)));
        }
        List<DomainRuleFactDescriptor> resolvedFacts = List.copyOf(baseFacts);
        List<DomainRuleFactMetadataOverlayProvider> providers = resolvedFactMetadataOverlayProviders();
        if (providers != null && !providers.isEmpty() && !resolvedFacts.isEmpty()) {
            for (DomainRuleFactMetadataOverlayProvider provider : providers) {
                if (provider == null) {
                    continue;
                }
                List<DomainRuleFactDescriptor> overlaid = provider.enrich(definition, resolvedFacts);
                resolvedFacts = mergeOverlayFacts(resolvedFacts, overlaid);
            }
        }
        return new DomainRuleFactCatalogResponse(
                definition.id(),
                definition.ruleKey(),
                definition.version(),
                catalog.path("schemaVersion").asText(FACT_CATALOG_SCHEMA_VERSION),
                resolvedFacts);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public org.praxisplatform.config.dto.DomainRuleParameterCatalogResponse definitionParameters(
            UUID definitionId, DomainRuleGovernancePrincipal principal) {
        return DomainRuleParameters.catalog(objectMapper, definition(definitionId, principal));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainRuleDefinitionResponse transitionDefinitionStatus(
            UUID definitionId,
            DomainRuleDefinitionStatusTransitionRequest request,
            DomainRuleGovernancePrincipal principal) {
        if (definitionId == null) {
            throw new ConfigurationIngestionException("definitionId is required");
        }
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule status transition request is required");
        }
        requirePrincipal(principal);
        prepareLifecycleScope(principal);
        String status = requireAllowedStatus(request.status(), "status", DEFINITION_STATUSES);
        DomainRuleDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ConfigurationIngestionException("Rule definition not found: " + definitionId));
        requireScope(definition.getTenantId(), principal.tenantId(), "tenantId");
        requireScope(definition.getEnvironment(), principal.environment(), "environment");
        lifecycleRefresh.definition(definition);
        requireScope(definition.getTenantId(), principal.tenantId(), "tenantId");
        requireScope(definition.getEnvironment(), principal.environment(), "environment");
        if (AUTHORIZATION_POLICY_RULE_TYPE.equals(definition.getRuleType()) && "active".equals(status)) {
            throw new ConfigurationIngestionException(OPA_PUBLICATION_MESSAGE);
        }
        requireAllowedDefinitionTransition(definition.getStatus(), status);
        if (isApprovalSatisfiedDefinitionStatus(status)) {
            requireAuthenticatedAuthorEvidence(definition);
            requireDefinitionApproverAuthorization(definition, principal.actorRef(), status);
        }

        String previousStatus = definition.getStatus();
        boolean hadApprovedAt = definition.getApprovedAt() != null;
        boolean hadActivatedAt = definition.getActivatedAt() != null;
        definition.setStatus(status);
        if (request.validationResult() != null && !request.validationResult().isNull()) {
            definition.setValidationResult(write(request.validationResult()));
        }
        if ("approved".equals(status) || "active".equals(status)) {
            definition.setApprovedBy(principal.actorRef());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
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
        DomainRuleDefinition saved = definitionRepository.save(definition);
        if (isTerminalOrInactiveDefinitionStatus(status)) {
            supersedeAppliedMaterializationsForDefinition(saved, principal, now);
        }
        if (isApprovalSatisfiedDefinitionStatus(status)) {
            persistDefinitionApproval(saved, principal.actorRef(), now);
        }
        if (!hadApprovedAt && saved.getApprovedAt() != null) {
            recordDefinitionEvent(
                    saved,
                    "definition.approved",
                    saved.getApprovedAt(),
                    "authenticated",
                    principal.actorRef(),
                    "Decision definition approved",
                    saved.getStatus());
            recordApprovalCompletedEvent(
                    saved,
                    saved.getApprovedAt(),
                    "authenticated",
                    principal.actorRef());
        }
        if (!hadActivatedAt && saved.getActivatedAt() != null && !"active".equals(previousStatus)) {
            recordDefinitionEvent(
                    saved,
                    "definition.activated",
                    saved.getActivatedAt(),
                    "authenticated",
                    principal.actorRef(),
                    "Decision definition activated",
                    saved.getStatus());
        }
        return toResponse(saved);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public void validateDefinitionApprovalAuthority(
            UUID definitionId, DomainRuleGovernancePrincipal principal) {
        if (definitionId == null) {
            throw new ConfigurationIngestionException("definitionId is required");
        }
        requirePrincipal(principal);
        DomainRuleDefinition definition = definitionRepository.findById(definitionId)
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Rule definition not found: " + definitionId));
        requireScope(definition.getTenantId(), principal.tenantId(), "tenantId");
        requireScope(definition.getEnvironment(), principal.environment(), "environment");
        requireAuthenticatedAuthorEvidence(definition);
        requireDefinitionApproverAuthorization(definition, principal.actorRef(), "approval");
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
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
        validateAuthorizationPolicyDefinition(ruleType, definition);
        Instant simulationRequestedAt = Instant.now();
        if (persistedDefinition != null) {
            recordSimulationRequestedEvent(persistedDefinition, simulationRequestedAt);
        }

        ArrayNode predictedMaterializations = buildPredictedMaterializations(
                resourceKey,
                ruleType,
                definition,
                parameters);
        ArrayNode existingCoverage = buildExistingCoverage(
                resolvedTenant,
                resolvedEnvironment,
                resourceKey,
                ruleType,
                ruleDefinitionId,
                ruleKey,
                persistedDefinition != null ? persistedDefinition.getVersion() : null,
                predictedMaterializations);
        ArrayNode requiredApprovals = buildRequiredApprovals(governance);
        if (persistedDefinition != null && isApprovalSatisfiedDefinitionStatus(persistedDefinition.getStatus())) {
            requiredApprovals = objectMapper.createArrayNode();
        }
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
        Instant simulatedAt = Instant.now();
        if (persistedDefinition != null) {
            recordSimulationCompletedEvent(
                    persistedDefinition,
                    simulatedAt,
                    result,
                    explainability.path("publicationReadiness").asText(null),
                    existingCoverage,
                    predictedMaterializations,
                    requiredApprovals,
                    warnings);
        }
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
                simulatedAt);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainRulePublicationResponse publish(
            DomainRulePublicationRequest request,
            DomainRuleGovernancePrincipal principal) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule publication request is required");
        }
        if (request.ruleDefinitionId() == null) {
            throw new ConfigurationIngestionException("ruleDefinitionId is required");
        }
        requirePrincipal(principal);
        prepareLifecycleScope(principal);
        String tenantId = principal.tenantId();
        String environment = principal.environment();

        DomainRuleDefinition definition = definitionRepository.findById(request.ruleDefinitionId())
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Rule definition not found: " + request.ruleDefinitionId()));
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");

        lifecycleRefresh.definition(definition);
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");

        validateParameterDefinition(definition);
        requireSnapshotMaterializationForParameters(definition);
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
        if (isTerminalOrInactiveDefinitionStatus(definition.getStatus())) {
            readiness = "blocked_by_definition_status";
        } else if (!isApprovalSatisfiedDefinitionStatus(definition.getStatus())) {
            readiness = "approval_required";
        }

        List<DomainRuleWorkspaceBlocker> evidenceBlockers = publicationEvidenceBlockers(definition, principal);
        if (!evidenceBlockers.isEmpty()) {
            readiness = "blocked_by_test_evidence";
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
                    withBlockedPublicationDiagnostics(
                            simulation.explainability(), readiness, definition, evidenceBlockers),
                    Instant.now());
        }
        Instant publicationRequestedAt = Instant.now();
        UUID publicationId = UUID.randomUUID();
        recordPublicationEvent(
                definition,
                "publication.requested",
                publicationRequestedAt,
                "authenticated",
                principal.actorRef(),
                "Decision publication requested",
                "requested",
                readiness,
                0,
                publicationId);
        if (isPublishableDefinitionStatus(definition.getStatus())) {
            definition.setStatus("active");
            if (definition.getActivatedAt() == null) {
                definition.setActivatedAt(publicationRequestedAt);
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
                principal,
                publicationRequestedAt,
                materializationOutcomes);
        Instant publicationCompletedAt = Instant.now();
        if (!publicationCompletedAt.isAfter(publicationRequestedAt)) {
            publicationCompletedAt = publicationRequestedAt.plusNanos(1);
        }
        recordPublicationEvent(
                definition,
                "publication.completed",
                publicationCompletedAt,
                "authenticated",
                principal.actorRef(),
                "Decision publication completed",
                "published",
                readiness,
                publishedMaterializations.size(),
                publicationId);

        return new DomainRulePublicationResponse(
                publicationId,
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
                publicationCompletedAt);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainRuleMaterializationResponse createMaterialization(
            DomainRuleMaterializationRequest request,
            DomainRuleGovernancePrincipal principal) {
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
        for (String coordinate : List.of(request.targetLayer(), request.targetArtifactType(), request.targetArtifactKey())) {
            if (!coordinate.equals(coordinate.trim())) {
                throw new ConfigurationIngestionException("Materialization target coordinates must not contain surrounding whitespace");
            }
        }
        requirePrincipal(principal);
        String tenantId = principal.tenantId();
        String environment = principal.environment();

        DomainRuleDefinition definition = definitionRepository.findById(request.ruleDefinitionId())
                .orElseThrow(() -> new ConfigurationIngestionException("Rule definition not found: " + request.ruleDefinitionId()));
        requireScope(definition.getTenantId(), tenantId, "tenantId");
        requireScope(definition.getEnvironment(), environment, "environment");
        requireGenericMaterializationTarget(definition, request.targetArtifactType());
        validateParameterDefinition(definition);
        requireSnapshotMaterializationForParameters(definition);
        requireReactiveDeterminationTargetContract(
                definition,
                request.targetLayer(),
                request.targetArtifactType(),
                request.targetArtifactKey());
        if (isGovernedColorPaletteTarget(
                request.targetLayer(),
                request.targetArtifactType())
                && !COLOR_PALETTE_RULE_TYPE.equals(definition.getRuleType())) {
            throw new ConfigurationIngestionException(
                    "Governed color palette materialization requires ruleType=design_token_palette");
        }
        String status = requireAllowedStatus(
                normalizeOrDefault(request.status(), "draft"),
                "status",
                INITIAL_MATERIALIZATION_STATUSES);
        String materializationKey = request.materializationKey().trim();
        JsonNode materializedPayload = deriveMaterializedPayload(definition, request);
        requireExplicitOperationalEffect(definition, request.targetLayer(), request.targetArtifactType(),
                request.targetArtifactKey(), materializedPayload);
        boolean reactiveDetermination = isReactiveDeterminationTarget(
                request.targetLayer(),
                request.targetArtifactType());
        if (reactiveDetermination) {
            String canonicalMaterializationKey = derivedMaterializationKey(
                    definition,
                    request.targetLayer().trim(),
                    request.targetArtifactKey().trim());
            if (!canonicalMaterializationKey.equals(materializationKey)) {
                throw new ConfigurationIngestionException(
                        "Reactive determination materializationKey must be " + canonicalMaterializationKey);
            }
        }
        String targetPointer = reactiveDetermination
                ? requireCanonicalProjectionCoordinate(
                        request.targetPointer(),
                        REACTIVE_DETERMINATION_TARGET_POINTER,
                        "targetPointer")
                : normalize(request.targetPointer());
        String materializedRuleId = reactiveDetermination
                ? requireCanonicalProjectionCoordinate(
                        request.materializedRuleId(),
                        REACTIVE_DETERMINATION_RULE_ID,
                        "materializedRuleId")
                : normalize(request.materializedRuleId());
        String sourceHash = normalize(request.sourceHash());
        if (reactiveDetermination || OperationalPolicyTarget.Family.supports(request.targetLayer(), request.targetArtifactType())) {
            String derivedSourceHash = derivedSourceHash(
                    definition,
                    request.targetLayer().trim(),
                    request.targetArtifactType().trim(),
                    request.targetArtifactKey().trim(),
                    targetPointer,
                    materializedRuleId,
                    materializedPayload);
            if (StringUtils.hasText(sourceHash) && !derivedSourceHash.equals(sourceHash)) {
                throw new ConfigurationIngestionException(
                        "Materialization sourceHash must match the canonical derived projection");
            }
            sourceHash = derivedSourceHash;
        }
        DomainRuleMaterialization existing = materializationRepository
                .findByTenantIdAndEnvironmentAndMaterializationKey(
                        normalize(tenantId),
                        normalize(environment),
                        materializationKey)
                .orElse(null);
        if (existing != null) {
            requireReusableMaterialization(
                    existing,
                    definition,
                    request.targetLayer(),
                    request.targetArtifactType(),
                    request.targetArtifactKey(),
                    sourceHash);
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
        materialization.setTargetPointer(targetPointer);
        materialization.setTargetReleaseKey(normalize(request.targetReleaseKey()));
        materialization.setMaterializedRuleId(materializedRuleId);
        materialization.setStatus(status);
        materialization.setMaterializedPayload(write(materializedPayload));
        materialization.setSourceHash(sourceHash);
        if (request.validationResult() != null && !request.validationResult().isNull()) {
            materialization.setValidationResult(write(request.validationResult()));
        }
        DomainRuleMaterialization saved = materializationRepository.save(materialization);
        recordMaterializationEvent(
                saved,
                "materialization.created",
                saved.getCreatedAt(),
                "authenticated",
                principal.actorRef(),
                "Decision materialization created");
        return toResponse(saved);
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
            String currentRuleKey,
            Integer currentVersion,
            ArrayNode predictedMaterializations) {
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
            // A reviewed higher version of the same palette replaces its previous head.
            // Competing decisions and attempts to publish older versions still require coverage review.
            if (COLOR_PALETTE_RULE_TYPE.equals(ruleType)
                    && currentDefinitionId != null
                    && Objects.equals(currentRuleKey, candidate.getRuleKey())
                    && currentVersion != null && candidate.getVersion() != null
                    && currentVersion > candidate.getVersion()
                    && predictedMaterializations.size() == 1
                    && isGovernedColorPaletteTarget(
                            predictedMaterializations.get(0).path("targetLayer").asText(),
                            predictedMaterializations.get(0).path("targetArtifactType").asText())
                    && Objects.equals(
                            predictedMaterializations.get(0).path("targetArtifactKey").asText(),
                            deriveColorPaletteKey(candidate.getResourceKey(), read(candidate.getParameters())))) {
                continue;
            }
            if (COLOR_PALETTE_RULE_TYPE.equals(ruleType)
                    && predictedMaterializations.size() == 1
                    && isGovernedColorPaletteTarget(
                            predictedMaterializations.get(0).path("targetLayer").asText(),
                            predictedMaterializations.get(0).path("targetArtifactType").asText())) {
                String candidatePaletteKey = explicitColorPaletteKey(read(candidate.getParameters()));
                if (StringUtils.hasText(candidatePaletteKey)
                        && !Objects.equals(
                                predictedMaterializations.get(0).path("targetArtifactKey").asText(),
                                candidatePaletteKey)) {
                    continue;
                }
            }
            if (usesOnlyReactiveDeterminationTargets(predictedMaterializations)
                    && hasDisjointReactiveDeterminationTargets(
                            predictedMaterializations,
                            candidate)) {
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

    private boolean hasDisjointReactiveDeterminationTargets(
            ArrayNode requestedTargets,
            DomainRuleDefinition candidate) {
        try {
            ArrayNode candidateTargets = buildPredictedMaterializations(
                    candidate.getResourceKey(),
                    candidate.getRuleType(),
                    read(candidate.getDefinition()),
                    read(candidate.getParameters()));
            return usesOnlyReactiveDeterminationTargets(candidateTargets)
                    && !hasExactReactiveDeterminationTargetOverlap(requestedTargets, candidateTargets);
        } catch (ConfigurationIngestionException ex) {
            return false;
        }
    }

    private boolean usesOnlyReactiveDeterminationTargets(ArrayNode targets) {
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        for (JsonNode target : targets) {
            if (!isReactiveDeterminationTarget(
                    normalize(target.path("targetLayer").asText(null)),
                    normalize(target.path("targetArtifactType").asText(null)))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasExactReactiveDeterminationTargetOverlap(
            ArrayNode requestedTargets,
            ArrayNode candidateTargets) {
        for (JsonNode requested : requestedTargets) {
            String requestedKey = normalize(requested.path("targetArtifactKey").asText(null));
            for (JsonNode candidate : candidateTargets) {
                if (normalize(requested.path("targetLayer").asText(null))
                        .equals(normalize(candidate.path("targetLayer").asText(null)))
                        && normalize(requested.path("targetArtifactType").asText(null))
                        .equals(normalize(candidate.path("targetArtifactType").asText(null)))
                        && requestedKey.equals(normalize(candidate.path("targetArtifactKey").asText(null)))) {
                    return true;
                }
            }
        }
        return false;
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
        appendExplicitMaterializationTargets(targets, definition);
        if (AUTHORIZATION_POLICY_RULE_TYPE.equals(ruleType)) {
            return targets;
        }
        String recommendedOperation = definition != null ? normalize(definition.path("recommendedOperation").asText(null)) : null;
        JsonNode params = parameters == null || parameters.isNull() ? null : parameters;

        validateReactiveDeterminationTargets(ruleType, params, targets);

        if (!containsMaterializationTarget(targets, "option_source", "resource-option-source")
                && shouldDeriveOptionSourceTarget(ruleType, params)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "option_source");
            target.put("targetArtifactType", "resource-option-source");
            target.put("targetArtifactKey", deriveOptionSourceKey(resourceKey, params));
            target.put("operation", "selection_policy.review");
        }

        if (!containsMaterializationTarget(targets, "backend_validation", "resource-validation")
                && isBackendValidationRuleType(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "backend_validation");
            target.put("targetArtifactType", "resource-validation");
            target.put("targetArtifactKey", resourceKey);
            target.put("operation", "validation_rule.review");
        }

        if (!containsMaterializationTarget(targets, "workflow_action", "resource-workflow-action")
                && isWorkflowActionRuleType(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "workflow_action");
            target.put("targetArtifactType", "resource-workflow-action");
            target.put("targetArtifactKey", deriveWorkflowActionKey(resourceKey, params));
            target.put("operation", "workflow_action_policy.review");
        }

        if (!containsMaterializationTarget(targets, "approval_policy", "resource-action-approval")
                && isApprovalPolicyRuleType(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "approval_policy");
            target.put("targetArtifactType", "resource-action-approval");
            target.put("targetArtifactKey", deriveActionApprovalKey(resourceKey, params));
            target.put("operation", "approval_policy.review");
        }

        if (!containsMaterializationTarget(targets, "form_config", "praxis-dynamic-form")
                && ("visual_guidance".equals(ruleType)
                || "form_rule".equals(ruleType)
                || "rule.visualBlockGuidance.add".equals(recommendedOperation))) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", "form_config");
            target.put("targetArtifactType", "praxis-dynamic-form");
            target.put("targetArtifactKey", resourceKey);
            target.put("operation", recommendedOperation != null ? recommendedOperation : "rule.review");
        }

        if (!containsMaterializationTarget(
                targets,
                COLOR_PALETTE_TARGET_LAYER,
                COLOR_PALETTE_ARTIFACT_TYPE)
                && COLOR_PALETTE_RULE_TYPE.equals(ruleType)) {
            ObjectNode target = targets.addObject();
            target.put("targetLayer", COLOR_PALETTE_TARGET_LAYER);
            target.put("targetArtifactType", COLOR_PALETTE_ARTIFACT_TYPE);
            String paletteKey = deriveColorPaletteKey(resourceKey, params);
            target.put("targetArtifactKey", paletteKey);
            target.put("operation", "palette.publish");
            JsonNode authoredPalette = params != null && params.path("palette").isObject()
                    ? params.path("palette")
                    : params;
            GovernedColorPaletteContractValidator.Result validation =
                    governedColorPaletteValidator.validate(paletteKey, authoredPalette);
            target.set("validation", objectMapper.valueToTree(validation.validation()));
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

    private void appendExplicitMaterializationTargets(ArrayNode targets, JsonNode definition) {
        if (definition == null || !definition.isObject()) {
            return;
        }
        JsonNode explicitTargets = definition.path("materializationTargets");
        if (!explicitTargets.isArray()) {
            return;
        }
        explicitTargets.forEach(candidate -> {
            if (!candidate.isObject()) {
                return;
            }
            String targetLayer = normalize(candidate.path("targetLayer").asText(null));
            String targetArtifactType = normalize(candidate.path("targetArtifactType").asText(null));
            String targetArtifactKey = normalize(candidate.path("targetArtifactKey").asText(null));
            if (isReactiveDeterminationCoordinate(targetLayer, targetArtifactType)) {
                requireOnlyFields(
                        candidate,
                        Set.of("targetLayer", "targetArtifactType", "targetArtifactKey", "operation"),
                        "definition.materializationTargets[] backend determination target");
                requireReactiveDeterminationTargetPair(targetLayer, targetArtifactType);
                requireStableIdentifier(
                        targetArtifactKey,
                        STABLE_DETERMINATION_KEY,
                        "backend determination targetArtifactKey");
            }
            if (!StringUtils.hasText(targetLayer)
                    || !StringUtils.hasText(targetArtifactType)
                    || !StringUtils.hasText(targetArtifactKey)) {
                return;
            }
            if (containsMaterializationTarget(targets, targetLayer, targetArtifactType, targetArtifactKey)) {
                return;
            }
            ObjectNode target = targets.addObject();
            target.put("targetLayer", targetLayer);
            target.put("targetArtifactType", targetArtifactType);
            target.put("targetArtifactKey", targetArtifactKey);
            String operation = normalize(candidate.path("operation").asText(null));
            target.put("operation", StringUtils.hasText(operation)
                    ? operation
                    : defaultMaterializationOperation(targetLayer));
        });
    }

    private boolean containsMaterializationTarget(
            ArrayNode targets,
            String targetLayer,
            String targetArtifactType) {
        return containsMaterializationTarget(targets, targetLayer, targetArtifactType, null);
    }

    private boolean containsMaterializationTarget(
            ArrayNode targets,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey) {
        for (JsonNode target : targets) {
            if (targetLayer.equals(normalize(target.path("targetLayer").asText(null)))
                    && targetArtifactType.equals(normalize(target.path("targetArtifactType").asText(null)))
                    && (!StringUtils.hasText(targetArtifactKey)
                    || targetArtifactKey.equals(normalize(target.path("targetArtifactKey").asText(null))))) {
                return true;
            }
        }
        return false;
    }

    private String defaultMaterializationOperation(String targetLayer) {
        return switch (targetLayer) {
            case "option_source" -> "selection_policy.review";
            case "backend_validation" -> "validation_rule.review";
            case "workflow_action" -> "workflow_action_policy.review";
            case "approval_policy" -> "approval_policy.review";
            case BACKEND_DETERMINATION_TARGET_LAYER -> "reactive_determination.review";
            case COLOR_PALETTE_TARGET_LAYER -> "palette.publish";
            case "form_config" -> "rule.review";
            default -> "governance.review";
        };
    }

    private boolean shouldDeriveOptionSourceTarget(
            String ruleType,
            JsonNode parameters) {
        boolean explicitlyTargetsOptionSource = false;
        if (parameters != null && parameters.isObject()) {
            if (StringUtils.hasText(parameters.path("optionSourceKey").asText(null))
                    || StringUtils.hasText(parameters.path("lookupSource").asText(null))) {
                explicitlyTargetsOptionSource = true;
            }
        }
        return explicitlyTargetsOptionSource || "selection_eligibility".equals(ruleType);
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

    private String deriveColorPaletteKey(String resourceKey, JsonNode parameters) {
        String paletteKey = explicitColorPaletteKey(parameters);
        return StringUtils.hasText(paletteKey) ? paletteKey : resourceKey;
    }

    private String explicitColorPaletteKey(JsonNode parameters) {
        String paletteKey = parameters != null && parameters.isObject()
                ? normalize(parameters.path("paletteKey").asText(null))
                : null;
        if (!StringUtils.hasText(paletteKey)
                && parameters != null
                && parameters.path("palette").isObject()) {
            paletteKey = normalize(parameters.path("palette").path("paletteKey").asText(null));
        }
        return paletteKey;
    }

    private boolean isGovernedColorPaletteTarget(
            String targetLayer,
            String targetArtifactType) {
        return COLOR_PALETTE_TARGET_LAYER.equals(normalize(targetLayer))
                && COLOR_PALETTE_ARTIFACT_TYPE.equals(normalize(targetArtifactType));
    }

    private JsonNode deriveMaterializedPayload(
            DomainRuleDefinition definition,
            DomainRuleMaterializationRequest request) {
        if (isReactiveDeterminationTarget(request.targetLayer(), request.targetArtifactType())) {
            if (request.materializedPayload() != null && !request.materializedPayload().isNull()) {
                throw new ConfigurationIngestionException(
                        "Reactive determination payload is compiled from parameters.reactiveDetermination and cannot be supplied directly");
            }
            return buildReactiveDeterminationMaterializedPayload(definition, request.targetArtifactKey());
        }
        if (request.materializedPayload() != null && !request.materializedPayload().isNull()) {
            if (isGovernedColorPaletteTarget(
                    request.targetLayer(),
                    request.targetArtifactType())) {
                return validateGovernedColorPalettePayload(
                        request.targetArtifactKey(),
                        request.materializedPayload());
            }
            return request.materializedPayload();
        }
        if (isGovernedColorPaletteTarget(
                request.targetLayer(),
                request.targetArtifactType())
                && definition != null
                && COLOR_PALETTE_RULE_TYPE.equals(definition.getRuleType())) {
            return buildGovernedColorPaletteMaterializedPayload(
                    definition,
                    request.targetArtifactKey());
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
        if ("form_config".equals(request.targetLayer())
                && "praxis-dynamic-form".equals(request.targetArtifactType())
                && definition != null
                && isVisualGuidanceRuleType(definition.getRuleType())) {
            return buildVisualGuidanceMaterializedPayload(definition);
        }
        return objectMapper.createObjectNode();
    }

    private ObjectNode buildGovernedColorPaletteMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = read(definition.getParameters());
        JsonNode authoredPalette = parameters != null && parameters.path("palette").isObject()
                ? parameters.path("palette")
                : parameters;
        if (authoredPalette == null || !authoredPalette.isObject()) {
            throw new ConfigurationIngestionException(
                    "Design token palette materialization requires parameters.palette");
        }
        ObjectNode payload = authoredPalette.deepCopy();
        if (!StringUtils.hasText(payload.path("paletteKey").asText(null))) {
            payload.put("paletteKey", targetArtifactKey);
        }
        return validateGovernedColorPalettePayload(targetArtifactKey, payload);
    }

    private ObjectNode validateGovernedColorPalettePayload(
            String targetArtifactKey,
            JsonNode payload) {
        GovernedColorPaletteContractValidator.Result result =
                governedColorPaletteValidator.validate(targetArtifactKey, payload);
        if (!result.validation().valid()) {
            throw new ConfigurationIngestionException(
                    "Governed color palette is invalid: "
                            + String.join("; ", result.validation().errors()));
        }
        return payload.deepCopy();
    }

    private ObjectNode buildVisualGuidanceMaterializedPayload(DomainRuleDefinition definition) {
        JsonNode authoredDefinition = read(definition.getDefinition());
        JsonNode parameters = read(definition.getParameters());
        JsonNode condition = read(definition.getCondition());
        if (parameters == null || !parameters.isObject()) {
            throw new ConfigurationIngestionException(
                    "Visual guidance materialization requires canonical parameters");
        }

        String visualBlockId = normalize(parameters.path("visualBlockId").asText(null));
        requireText(visualBlockId, "parameters.visualBlockId");
        String message = normalize(parameters.path("message").asText(null));
        if (!StringUtils.hasText(message) && authoredDefinition != null && authoredDefinition.isObject()) {
            message = normalize(authoredDefinition.path("summary").asText(null));
        }
        requireText(message, "parameters.message or definition.summary");

        String operation = authoredDefinition != null && authoredDefinition.isObject()
                ? normalize(authoredDefinition.path("derivedMaterializationOperation").asText(null))
                : null;
        if (!StringUtils.hasText(operation) && authoredDefinition != null && authoredDefinition.isObject()) {
            operation = normalize(authoredDefinition.path("recommendedOperation").asText(null));
        }
        operation = normalizeOrDefault(operation, "rule.visualBlockGuidance.add");
        if (!"rule.visualBlockGuidance.add".equals(operation)) {
            throw new ConfigurationIngestionException(
                    "Visual guidance form_config materialization requires operation=rule.visualBlockGuidance.add");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("id", deriveVisualGuidanceRuleId(definition, parameters));
        payload.put("operation", operation);
        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("origin", "domain_rule_definition");
        metadata.put("ruleType", definition.getRuleType());
        metadata.put("reviewStatus", "pending");

        ObjectNode effect = payload.putObject("effect");
        if (condition != null && !condition.isNull()) {
            effect.set("condition", condition);
        }
        effect.put("targetBlockId", visualBlockId);
        ObjectNode properties = effect.putObject("properties");
        properties.put("severity", normalizeOrDefault(
                parameters.path("severity").asText(null),
                "warning"));
        properties.put("message", message);
        return payload;
    }

    private String deriveVisualGuidanceRuleId(
            DomainRuleDefinition definition,
            JsonNode parameters) {
        String explicit = parameters != null && parameters.isObject()
                ? normalize(parameters.path("materializedRuleId").asText(null))
                : null;
        return normalizeOrDefault(explicit, definition.getRuleKey());
    }

    private ObjectNode buildReactiveDeterminationMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        DomainRuleReactiveDeterminationSpec spec = readReactiveDeterminationSpec(
                read(definition.getParameters()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("schemaVersion", REACTIVE_DETERMINATION_SCHEMA_VERSION);
        payload.put("kind", "backend_reactive_determination");
        payload.put("determinationKey", targetArtifactKey);

        ObjectNode metadata = payload.putObject("metadata");
        metadata.put("origin", "domain_rule_definition");
        metadata.put("ruleType", definition.getRuleType());
        metadata.put("reviewStatus", "pending");

        ObjectNode decisionRef = payload.putObject("decisionRef");
        decisionRef.put("ruleKey", definition.getRuleKey());
        decisionRef.put("ruleVersion", definition.getVersion());

        ObjectNode scope = payload.putObject("scope");
        scope.put("resourceKey", definition.getResourceKey());
        if (StringUtils.hasText(definition.getServiceKey())) {
            scope.put("serviceKey", definition.getServiceKey());
        }
        if (StringUtils.hasText(definition.getContextKey())) {
            scope.put("contextKey", definition.getContextKey());
        }

        ObjectNode operationRef = payload.putObject("operationRef");
        operationRef.put("operationId", spec.operationId().trim());

        ObjectNode execution = payload.putObject("executionContract");
        execution.put("idempotent", true);
        execution.put("persistence", spec.persistence().value());
        execution.put("finalCommandRevalidation", true);

        ArrayNode inputs = payload.putArray("inputs");
        spec.inputs().forEach(binding -> {
            ObjectNode item = inputs.addObject();
            item.put("resourcePointer", binding.resourcePointer().trim());
            item.put("requestPointer", binding.requestPointer().trim());
        });
        ArrayNode outputs = payload.putArray("outputs");
        spec.outputs().forEach(binding -> {
            ObjectNode item = outputs.addObject();
            item.put("responsePointer", binding.responsePointer().trim());
            item.put("resourcePointer", binding.resourcePointer().trim());
        });

        JsonNode condition = read(definition.getCondition());
        if (condition != null && !condition.isNull()) {
            payload.set("activationCondition", condition);
        }
        return payload;
    }

    private void validateReactiveDeterminationTargets(
            String ruleType,
            JsonNode parameters,
            ArrayNode targets) {
        for (JsonNode target : targets) {
            String targetLayer = normalize(target.path("targetLayer").asText(null));
            String targetArtifactType = normalize(target.path("targetArtifactType").asText(null));
            String targetArtifactKey = normalize(target.path("targetArtifactKey").asText(null));
            if (!isReactiveDeterminationCoordinate(targetLayer, targetArtifactType)) {
                continue;
            }
            requireReactiveDeterminationTargetPair(targetLayer, targetArtifactType);
            requireStableIdentifier(
                    targetArtifactKey,
                    STABLE_DETERMINATION_KEY,
                    "backend determination targetArtifactKey");
            if (!"calculation".equals(ruleType)) {
                throw new ConfigurationIngestionException(
                        "Backend reactive determination materialization requires ruleType=calculation");
            }
            readReactiveDeterminationSpec(parameters);
        }
    }

    private void requireReactiveDeterminationTargetContract(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey) {
        if (!isReactiveDeterminationCoordinate(targetLayer, targetArtifactType)) {
            return;
        }
        requireReactiveDeterminationTargetPair(targetLayer, targetArtifactType);
        requireStableIdentifier(
                normalize(targetArtifactKey),
                STABLE_DETERMINATION_KEY,
                "backend determination targetArtifactKey");
        if (definition == null || !"calculation".equals(definition.getRuleType())) {
            throw new ConfigurationIngestionException(
                    "Backend reactive determination materialization requires ruleType=calculation");
        }
        requireText(definition.getResourceKey(), "resourceKey");
        readReactiveDeterminationSpec(read(definition.getParameters()));
    }

    private void requireReactiveDeterminationTargetPair(
            String targetLayer,
            String targetArtifactType) {
        if (!isReactiveDeterminationTarget(targetLayer, targetArtifactType)) {
            throw new ConfigurationIngestionException(
                    "backend_determination must use targetArtifactType=resource-reactive-determination");
        }
    }

    private boolean isReactiveDeterminationCoordinate(
            String targetLayer,
            String targetArtifactType) {
        return BACKEND_DETERMINATION_TARGET_LAYER.equals(normalize(targetLayer))
                || REACTIVE_DETERMINATION_ARTIFACT_TYPE.equals(normalize(targetArtifactType));
    }

    private boolean isReactiveDeterminationTarget(
            String targetLayer,
            String targetArtifactType) {
        return BACKEND_DETERMINATION_TARGET_LAYER.equals(normalize(targetLayer))
                && REACTIVE_DETERMINATION_ARTIFACT_TYPE.equals(normalize(targetArtifactType));
    }

    private DomainRuleReactiveDeterminationSpec readReactiveDeterminationSpec(JsonNode parameters) {
        if (parameters == null || !parameters.isObject()) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination is required for backend reactive determination materialization");
        }
        JsonNode candidate = parameters.get("reactiveDetermination");
        if (candidate == null || !candidate.isObject()) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination is required for backend reactive determination materialization");
        }
        requireOnlyFields(
                candidate,
                Set.of(
                        "operationId",
                        "idempotent",
                        "persistence",
                        "finalCommandRevalidation",
                        "inputs",
                        "outputs"),
                "parameters.reactiveDetermination");
        try {
            DomainRuleReactiveDeterminationSpec spec = objectMapper.treeToValue(
                    candidate,
                    DomainRuleReactiveDeterminationSpec.class);
            validateReactiveDeterminationSpec(spec, candidate);
            return spec;
        } catch (ConfigurationIngestionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination does not match the canonical contract",
                    ex);
        }
    }

    private void validateReactiveDeterminationSpec(
            DomainRuleReactiveDeterminationSpec spec,
            JsonNode source) {
        if (spec == null) {
            throw new ConfigurationIngestionException("Reactive determination contract is required");
        }
        requireText(spec.operationId(), "parameters.reactiveDetermination.operationId");
        requireStableIdentifier(
                spec.operationId().trim(),
                STABLE_OPERATION_ID,
                "parameters.reactiveDetermination.operationId");
        if (!Boolean.TRUE.equals(spec.idempotent())) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination.idempotent must be true");
        }
        if (spec.persistence() != DomainRuleReactiveDeterminationSpec.Persistence.NONE) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination.persistence must be none");
        }
        if (!Boolean.TRUE.equals(spec.finalCommandRevalidation())) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination.finalCommandRevalidation must be true");
        }
        if (spec.inputs() == null || spec.inputs().isEmpty()) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination.inputs must not be empty");
        }
        if (spec.outputs() == null || spec.outputs().isEmpty()) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination.outputs must not be empty");
        }
        if ((long) spec.inputs().size() + spec.outputs().size()
                > MAX_REACTIVE_DETERMINATION_BINDINGS) {
            throw new ConfigurationIngestionException(
                    "parameters.reactiveDetermination supports at most 64 input and output bindings in total");
        }

        Set<String> sourcePointers = new HashSet<>();
        Set<String> requestPointers = new HashSet<>();
        for (int index = 0; index < spec.inputs().size(); index++) {
            JsonNode bindingNode = source.path("inputs").path(index);
            requireOnlyFields(
                    bindingNode,
                    Set.of("resourcePointer", "requestPointer"),
                    "parameters.reactiveDetermination.inputs[" + index + "]");
            DomainRuleReactiveDeterminationSpec.InputBinding binding = spec.inputs().get(index);
            String resourcePointer = requireJsonPointer(
                    binding.resourcePointer(),
                    "parameters.reactiveDetermination.inputs[" + index + "].resourcePointer");
            String requestPointer = requireJsonPointer(
                    binding.requestPointer(),
                    "parameters.reactiveDetermination.inputs[" + index + "].requestPointer");
            requireNonOverlappingPointer(
                    sourcePointers,
                    resourcePointer,
                    "Reactive determination input resourcePointer values");
            requireNonOverlappingPointer(
                    requestPointers,
                    requestPointer,
                    "Reactive determination requestPointer values");
        }

        Set<String> targetPointers = new HashSet<>();
        Set<String> responsePointers = new HashSet<>();
        for (int index = 0; index < spec.outputs().size(); index++) {
            JsonNode bindingNode = source.path("outputs").path(index);
            requireOnlyFields(
                    bindingNode,
                    Set.of("responsePointer", "resourcePointer"),
                    "parameters.reactiveDetermination.outputs[" + index + "]");
            DomainRuleReactiveDeterminationSpec.OutputBinding binding = spec.outputs().get(index);
            String responsePointer = requireJsonPointer(
                    binding.responsePointer(),
                    "parameters.reactiveDetermination.outputs[" + index + "].responsePointer");
            String targetPointer = requireJsonPointer(
                    binding.resourcePointer(),
                    "parameters.reactiveDetermination.outputs[" + index + "].resourcePointer");
            requireNonOverlappingPointer(
                    responsePointers,
                    responsePointer,
                    "Reactive determination responsePointer values");
            requireNonOverlappingPointer(
                    targetPointers,
                    targetPointer,
                    "Reactive determination output resourcePointer values");
        }
        for (String sourcePointer : sourcePointers) {
            for (String targetPointer : targetPointers) {
                if (pointersOverlap(sourcePointer, targetPointer)) {
                    throw new ConfigurationIngestionException(
                            "Reactive determination input and output resourcePointer values must not overlap");
                }
            }
        }
    }

    private void requireOnlyFields(JsonNode node, Set<String> allowed, String field) {
        if (node == null || !node.isObject()) {
            throw new ConfigurationIngestionException(field + " must be an object");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new ConfigurationIngestionException(field + " contains unsupported field: " + name);
            }
        });
    }

    private String requireJsonPointer(String value, String field) {
        requireText(value, field);
        String pointer = value.trim();
        if (!pointer.startsWith("/") || pointer.length() == 1) {
            throw new ConfigurationIngestionException(field + " must be a non-root RFC 6901 JSON Pointer");
        }
        String[] segments = pointer.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new ConfigurationIngestionException(field + " must not contain empty JSON Pointer segments");
            }
            if (segment.indexOf('*') >= 0) {
                throw new ConfigurationIngestionException(field + " must not contain JSON Pointer wildcards");
            }
            for (int index = 0; index < segment.length(); index++) {
                if (segment.charAt(index) == '~'
                        && (index + 1 >= segment.length()
                        || (segment.charAt(index + 1) != '0' && segment.charAt(index + 1) != '1'))) {
                    throw new ConfigurationIngestionException(
                            field + " contains an invalid RFC 6901 escape sequence");
                }
            }
        }
        try {
            JsonPointer.compile(pointer);
        } catch (IllegalArgumentException ex) {
            throw new ConfigurationIngestionException(field + " must be a valid RFC 6901 JSON Pointer", ex);
        }
        return pointer;
    }

    private void requireNonOverlappingPointer(
            Set<String> pointers,
            String candidate,
            String field) {
        for (String existing : pointers) {
            if (pointersOverlap(existing, candidate)) {
                throw new ConfigurationIngestionException(field + " must be unique and non-overlapping");
            }
        }
        pointers.add(candidate);
    }

    private boolean pointersOverlap(String left, String right) {
        return left.equals(right)
                || left.startsWith(right + "/")
                || right.startsWith(left + "/");
    }

    private void requireStableIdentifier(
            String value,
            Pattern pattern,
            String field) {
        if (!StringUtils.hasText(value) || !pattern.matcher(value).matches()) {
            throw new ConfigurationIngestionException(
                    field + " must be a stable identifier with at most 255 characters");
        }
    }

    private String requireCanonicalProjectionCoordinate(
            String requested,
            String canonical,
            String field) {
        String normalized = normalize(requested);
        if (normalized != null && !canonical.equals(normalized)) {
            throw new ConfigurationIngestionException(
                    "Reactive determination " + field + " must be " + canonical);
        }
        return canonical;
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

    JsonNode operationalProjection(DomainRuleDefinition definition, OperationalPolicyTarget target) {
        return switch (target.family()) {
            case APPROVAL -> buildApprovalPolicyMaterializedPayload(definition, target.targetArtifactKey());
            case WORKFLOW -> buildWorkflowActionMaterializedPayload(definition, target.targetArtifactKey());
            case VALIDATION -> buildBackendValidationMaterializedPayload(definition, target.targetArtifactKey());
        };
    }

    private ObjectNode buildBackendValidationMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = DomainRuleMaterializationFingerprint.parse(definition.getParameters());
        JsonNode condition = DomainRuleMaterializationFingerprint.parse(definition.getCondition());

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
        OperationalPolicyContract.projectEffect(definition, new OperationalPolicyTarget("backend_validation", "resource-validation", payload.path("resourceKey").asText()), payload);
        return payload;
    }

    private ObjectNode buildWorkflowActionMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = DomainRuleMaterializationFingerprint.parse(definition.getParameters());
        JsonNode condition = DomainRuleMaterializationFingerprint.parse(definition.getCondition());
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
        OperationalPolicyContract.projectEffect(definition, new OperationalPolicyTarget("workflow_action", "resource-workflow-action", resourceKey + ":" + actionId), payload);
        return payload;
    }

    private ObjectNode buildApprovalPolicyMaterializedPayload(
            DomainRuleDefinition definition,
            String targetArtifactKey) {
        JsonNode parameters = DomainRuleMaterializationFingerprint.parse(definition.getParameters());
        JsonNode condition = DomainRuleMaterializationFingerprint.parse(definition.getCondition());
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
        OperationalPolicyContract.projectEffect(definition, new OperationalPolicyTarget("approval_policy", "resource-action-approval", resourceKey + ":" + actionId), payload);
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

    private void requireDefinitionCreationGovernance(
            String ruleType,
            String requestedStatus,
            String createdByType,
            JsonNode definition,
            JsonNode parameters,
            JsonNode governance) {
        if (!isGovernedDecision(ruleType, definition, parameters)) {
            return;
        }
        if (!INITIAL_DEFINITION_STATUSES.contains(requestedStatus)) {
            throw new ConfigurationIngestionException(
                    "Governed domain rule definitions must be created as draft or proposed");
        }
        if ("llm".equals(createdByType) || "ai".equals(createdByType)) {
            if (!"draft".equals(requestedStatus) && !"proposed".equals(requestedStatus)) {
                throw new ConfigurationIngestionException(
                        "LLM-authored governed domain rule definitions cannot bypass review");
            }
        }
        requireRequiredApprovals(governance);
    }

    private void requireDefinitionApproverAuthorization(
            DomainRuleDefinition definition,
            String actor,
            String action) {
        String normalizedActor = normalize(actor);
        if (!StringUtils.hasText(normalizedActor)) {
            throw new ConfigurationIngestionException(
                    "Governed domain rule " + action + " requires an authenticated actor");
        }
        if (normalizedActor.equals(normalize(definition.getCreatedBy()))) {
            throw new ConfigurationIngestionException(
                    "Rule definition approver must be different from its author");
        }
        if (!isGovernedDecision(
                definition.getRuleType(),
                read(definition.getDefinition()),
                read(definition.getParameters()))) {
            return;
        }
        JsonNode governance = read(definition.getGovernance());
        List<String> authorizedApprovers = authorizedApprovers(
                governance, requireRequiredApprovals(governance));
        boolean genericHumanReviewGate =
                !hasExplicitAuthorizedApprovers(governance)
                        && authorizedApprovers.equals(List.of("human-review"));
        if (!genericHumanReviewGate && !authorizedApprovers.contains(normalizedActor)) {
            throw new ConfigurationIngestionException(
                    "Governed domain rule " + action + " actor is not authorized by governance");
        }
    }

    private void requireAuthenticatedAuthorEvidence(DomainRuleDefinition definition) {
        if (!"authenticated".equals(definition.getCreatedByType())
                || !StringUtils.hasText(definition.getCreatedBy())) {
            throw new ConfigurationIngestionException(
                    "Rule definition approval requires server-authenticated author evidence");
        }
    }

    private void persistDefinitionApproval(
            DomainRuleDefinition definition,
            String actorRef,
            Instant approvedAt) {
        String definitionHash = definitionFingerprint.sha256(definition);
        definitionApprovalRepository.insertIfAbsent(
                UUID.randomUUID(),
                definition.getTenantId(),
                definition.getEnvironment(),
                definition.getId(),
                definitionHash,
                actorRef,
                approvedAt);
    }

    private void requirePrincipal(DomainRuleGovernancePrincipal principal) {
        if (principal == null) {
            throw new ConfigurationIngestionException("Authenticated governance principal is required");
        }
        requireText(principal.tenantId(), "tenantId");
        requireText(principal.environment(), "environment");
        requireText(principal.actorRef(), "authenticated actor");
    }

    private ArrayNode requireRequiredApprovals(JsonNode governance) {
        ArrayNode requiredApprovals = buildRequiredApprovals(governance);
        if (requiredApprovals.isEmpty()) {
            throw new ConfigurationIngestionException(
                    "Governed domain rule definitions require governance.requiredApprovals");
        }
        return requiredApprovals;
    }

    private List<String> authorizedApprovers(JsonNode governance, ArrayNode requiredApprovals) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        collectTextArray(requiredApprovals).stream()
                .map(this::normalize)
                .filter(StringUtils::hasText)
                .forEach(values::add);
        if (governance != null && governance.isObject()) {
            JsonNode explicit = governance.get("authorizedApprovers");
            if (explicit != null && explicit.isArray()) {
                collectTextArray(explicit).stream()
                        .map(this::normalize)
                        .filter(StringUtils::hasText)
                        .forEach(values::add);
            }
        }
        return List.copyOf(values);
    }

    private boolean hasExplicitAuthorizedApprovers(JsonNode governance) {
        if (governance == null || !governance.isObject()) {
            return false;
        }
        JsonNode explicit = governance.get("authorizedApprovers");
        if (explicit == null || !explicit.isArray()) {
            return false;
        }
        return collectTextArray(explicit).stream()
                .map(this::normalize)
                .anyMatch(StringUtils::hasText);
    }

    private boolean isGovernedDecision(String ruleType, JsonNode definition, JsonNode parameters) {
        if (isBackendValidationRuleType(ruleType)
                || isWorkflowActionRuleType(ruleType)
                || isApprovalPolicyRuleType(ruleType)
                || AUTHORIZATION_POLICY_RULE_TYPE.equals(ruleType)
                || COLOR_PALETTE_RULE_TYPE.equals(ruleType)
                || "visual_guidance".equals(ruleType)
                || "form_rule".equals(ruleType)) {
            return true;
        }
        if (definition != null && definition.isObject()) {
            JsonNode targets = definition.path("materializationTargets");
            if (targets.isArray() && !targets.isEmpty()) {
                return true;
            }
        }
        return parameters != null
                && parameters.isObject()
                && (StringUtils.hasText(parameters.path("optionSourceKey").asText(null))
                || StringUtils.hasText(parameters.path("lookupSource").asText(null)));
    }

    private void validateAuthorizationPolicyDefinition(String ruleType, JsonNode definition) {
        JsonNode body = definition == null ? objectMapper.missingNode() : definition;
        if (!AUTHORIZATION_POLICY_RULE_TYPE.equals(ruleType)) {
            if (AuthorizationPolicyDefinition.SCHEMA_VERSION_V1.equals(body.path("schemaVersion").asText())) {
                throw new ConfigurationIngestionException("Authorization policy payload requires ruleType=authorization_policy");
            }
            for (JsonNode target : body.path("materializationTargets")) {
                if (OPA_ARTIFACT_TYPE.equals(target.path("targetArtifactType").asText().trim())) {
                    throw new ConfigurationIngestionException("OPA policy targets require ruleType=authorization_policy");
                }
            }
            return;
        }
        if (!body.isObject()) {
            throw new ConfigurationIngestionException("Authorization policy definition must be a JSON object");
        }
        AuthorizationPolicyDefinition policy;
        try {
            policy = objectMapper.readerFor(AuthorizationPolicyDefinition.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .readValue(body);
        } catch (IOException ex) {
            throw new ConfigurationIngestionException(
                    "Invalid authorization policy definition for " + AuthorizationPolicyDefinition.SCHEMA_VERSION_V1);
        }
        if (policy.materializationTargets().stream().anyMatch(target ->
                !OPA_TARGET_LAYER.equals(target.targetLayer()) || !OPA_ARTIFACT_TYPE.equals(target.targetArtifactType()))) {
            throw new ConfigurationIngestionException("Authorization policy targets must use policy_engine/opa-policy");
        }
    }

    private void requireGenericMaterializationTarget(DomainRuleDefinition definition, String targetArtifactType) {
        if (AUTHORIZATION_POLICY_RULE_TYPE.equals(definition.getRuleType())
                || OPA_ARTIFACT_TYPE.equals(normalize(targetArtifactType))) {
            throw new ConfigurationIngestionException(OPA_PUBLICATION_MESSAGE);
        }
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
        boolean authorizationPolicy = AUTHORIZATION_POLICY_RULE_TYPE.equals(ruleType);
        explainability.put(
                "summary",
                buildExplainabilitySummary(ruleType, resourceKey, existingCoverage, predictedMaterializations, requiredApprovals));
        explainability.put(
                "recommendedAction",
                authorizationPolicy && persistedDefinition && requiredApprovals.isEmpty()
                        ? "review_opa_publication"
                        : buildRecommendedAction(existingCoverage, requiredApprovals, persistedDefinition));
        explainability.put(
                "publicationReadiness",
                authorizationPolicy && persistedDefinition && requiredApprovals.isEmpty()
                        ? OPA_PUBLICATION_REQUIRED
                        : buildPublicationReadiness(existingCoverage, requiredApprovals, persistedDefinition));
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
        if (persistedDefinition && authorizationPolicy) {
            ObjectNode step = nextSteps.addObject();
            step.put("kind", "review_opa_publication");
            step.put("title", OPA_PUBLICATION_MESSAGE);
        } else if (persistedDefinition) {
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
        diagnostics.put("decisionKind",
                AUTHORIZATION_POLICY_RULE_TYPE.equals(ruleType) ? "AUTHORIZATION" : "semantic_domain_rule");
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
        return "approved".equals(status) || "active".equals(status);
    }

    private boolean isApprovalSatisfiedDefinitionStatus(String status) {
        return "approved".equals(status) || "active".equals(status);
    }

    private boolean isTerminalOrInactiveDefinitionStatus(String status) {
        return "rejected".equals(status) || "retired".equals(status) || "deprecated".equals(status);
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
            DomainRuleDefinition definition,
            List<DomainRuleWorkspaceBlocker> evidenceBlockers) {
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
        if (evidenceBlockers != null && !evidenceBlockers.isEmpty()) {
            diagnostics.set("testEvidenceBlockers", objectMapper.valueToTree(evidenceBlockers));
        }
        diagnostics.set("materializationOutcomes", objectMapper.createArrayNode());
        return copy;
    }

    private String blockedPublicationReason(String publicationReadiness) {
        return switch (publicationReadiness) {
            case "blocked_by_definition_status" -> "definition_status_not_publishable";
            case "blocked_by_existing_coverage" -> "existing_coverage";
            case "approval_required" -> "approval_required";
            case "blocked_by_test_evidence" -> "test_evidence_policy_not_satisfied";
            case "ready_for_definition_review" -> "definition_not_persisted";
            default -> publicationReadiness;
        };
    }

    private List<DomainRuleWorkspaceBlocker> publicationEvidenceBlockers(
            DomainRuleDefinition definition,
            DomainRuleGovernancePrincipal principal) {
        DomainRuleDefinitionEvidenceGateService gate = evidenceGateProvider.getIfAvailable();
        if (gate != null) {
            return gate.blockers("PUBLISH", definition, principal);
        }
        JsonNode governance = read(definition.getGovernance());
        if (governance != null && governance.path("testEvidencePolicy").isObject()) {
            return List.of(new DomainRuleWorkspaceBlocker(
                    "TEST_EVIDENCE_GATE_UNAVAILABLE", "PUBLISH",
                    "The governed publication evidence gate is unavailable"));
        }
        return List.of();
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
            DomainRuleGovernancePrincipal principal,
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
        boolean explicitMaterializationOrder = false;
        if (request.materializationIds() != null && !request.materializationIds().isEmpty()) {
            explicitMaterializationOrder = true;
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
                materializationRepository.flush();
            } else {
                candidates = orderedMaterializations(candidates);
                candidates.forEach(materialization ->
                        addMaterializationOutcome(
                                materializationOutcomes,
                                materialization,
                                isDerivedMaterialization(materialization) ? "reused" : "selected_existing",
                                null));
            }
        }
        if (!explicitMaterializationOrder) {
            candidates = orderedMaterializations(candidates);
        }

        candidates.forEach(lifecycleRefresh::materialization);
        return candidates.stream()
                .peek(materialization -> {
                    requireScope(materialization.getTenantId(), tenantId, "tenantId");
                    requireScope(materialization.getEnvironment(), environment, "environment");
                    if (!definition.getId().equals(materialization.getRuleDefinition().getId())) {
                        throw new ConfigurationIngestionException(
                                "Rule materialization does not belong to definition " + definition.getId());
                    }
                    requireGenericMaterializationTarget(definition, materialization.getTargetArtifactType());
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
                .peek(this::requireOperationalIntegrity)
                .map(materialization -> maybeApplyMaterialization(materialization, principal, now))
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

    private List<DomainRuleMaterialization> orderedMaterializations(List<DomainRuleMaterialization> materializations) {
        if (materializations == null || materializations.size() < 2) {
            return materializations != null ? materializations : List.of();
        }
        return materializations.stream()
                .sorted(materializationComparator())
                .toList();
    }

    private Comparator<DomainRuleMaterialization> materializationComparator() {
        return Comparator
                .comparingInt((DomainRuleMaterialization item) -> materializationLayerPriority(item.getTargetLayer()))
                .thenComparing(DomainRuleMaterialization::getTargetLayer, Comparator.nullsLast(String::compareTo))
                .thenComparing(DomainRuleMaterialization::getTargetArtifactType, Comparator.nullsLast(String::compareTo))
                .thenComparing(DomainRuleMaterialization::getTargetArtifactKey, Comparator.nullsLast(String::compareTo))
                .thenComparing(DomainRuleMaterialization::getMaterializationKey, Comparator.nullsLast(String::compareTo));
    }

    private int materializationLayerPriority(String targetLayer) {
        return switch (targetLayer != null ? targetLayer : "") {
            case "option_source" -> 10;
            case "backend_validation" -> 20;
            case BACKEND_DETERMINATION_TARGET_LAYER -> 25;
            case "workflow_action" -> 30;
            case "approval_policy" -> 40;
            default -> 100;
        };
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
        if ("form_config".equals(targetLayer)
                && "praxis-dynamic-form".equals(targetArtifactType)
                && isVisualGuidanceRuleType(definition.getRuleType())
                && hasExplicitMaterializationTarget(
                        definition,
                        targetLayer,
                        targetArtifactType,
                        targetArtifactKey)) {
            return createVisualGuidanceMaterialization(
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey,
                    tenantId,
                    environment,
                    materializationOutcomes);
        }
        if (isGovernedColorPaletteTarget(targetLayer, targetArtifactType)
                && COLOR_PALETTE_RULE_TYPE.equals(definition.getRuleType())) {
            return createGovernedColorPaletteMaterialization(
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey,
                    tenantId,
                    environment,
                    materializationOutcomes);
        }
        if (isReactiveDeterminationCoordinate(targetLayer, targetArtifactType)) {
            requireReactiveDeterminationTargetContract(
                    definition,
                    targetLayer,
                    targetArtifactType,
                    targetArtifactKey);
            return createReactiveDeterminationMaterialization(
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

    private boolean hasExplicitMaterializationTarget(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey) {
        JsonNode authoredDefinition = read(definition.getDefinition());
        JsonNode targets = authoredDefinition != null && authoredDefinition.isObject()
                ? authoredDefinition.path("materializationTargets")
                : null;
        if (targets == null || !targets.isArray()) {
            return false;
        }
        for (JsonNode candidate : targets) {
            if (targetLayer.equals(normalize(candidate.path("targetLayer").asText(null)))
                    && targetArtifactType.equals(normalize(candidate.path("targetArtifactType").asText(null)))
                    && targetArtifactKey.equals(normalize(candidate.path("targetArtifactKey").asText(null)))) {
                return true;
            }
        }
        return false;
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

    private DomainRuleMaterialization createVisualGuidanceMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        ObjectNode payload = buildVisualGuidanceMaterializedPayload(definition);
        return createOrReuseDerivedMaterialization(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                "/formRules/-",
                deriveVisualGuidanceRuleId(definition, read(definition.getParameters())),
                payload,
                tenantId,
                environment,
                materializationOutcomes);
    }

    private DomainRuleMaterialization createReactiveDeterminationMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        ObjectNode payload = buildReactiveDeterminationMaterializedPayload(
                definition,
                targetArtifactKey);
        return createOrReuseDerivedMaterialization(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                REACTIVE_DETERMINATION_TARGET_POINTER,
                REACTIVE_DETERMINATION_RULE_ID,
                payload,
                tenantId,
                environment,
                materializationOutcomes);
    }

    private DomainRuleMaterialization createGovernedColorPaletteMaterialization(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactType,
            String targetArtifactKey,
            String tenantId,
            String environment,
            ArrayNode materializationOutcomes) {
        ObjectNode payload = buildGovernedColorPaletteMaterializedPayload(
                definition,
                targetArtifactKey);
        return createOrReuseDerivedMaterialization(
                definition,
                targetLayer,
                targetArtifactType,
                targetArtifactKey,
                "/",
                COLOR_PALETTE_ARTIFACT_TYPE,
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
        requireExplicitOperationalEffect(definition, targetLayer, targetArtifactType, targetArtifactKey, payload);
        String materializationKey = derivedMaterializationKey(definition, targetLayer, targetArtifactKey);
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
            lifecycleRefresh.materialization(existing.get());
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
        recordMaterializationEvent(
                saved,
                "materialization.created",
                saved.getCreatedAt(),
                "system",
                "domain-rule-materialization",
                "Decision materialization created");
        addMaterializationOutcome(materializationOutcomes, saved, "created", null);
        return saved;
    }

    private String derivedMaterializationKey(
            DomainRuleDefinition definition,
            String targetLayer,
            String targetArtifactKey) {
        if (COLOR_PALETTE_RULE_TYPE.equals(definition.getRuleType())
                && COLOR_PALETTE_TARGET_LAYER.equals(targetLayer)) {
            return definition.getRuleKey() + ":v" + definition.getVersion()
                    + ":" + targetLayer + ":" + targetArtifactKey;
        }
        return definition.getRuleKey() + ":" + targetLayer + ":" + targetArtifactKey;
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

    private void requireOperationalIntegrity(DomainRuleMaterialization materialization) {
        if (!OperationalPolicyTarget.Family.supports(materialization.getTargetLayer(), materialization.getTargetArtifactType())) return;
        var definition = materialization.getRuleDefinition();
        JsonNode payload = DomainRuleMaterializationFingerprint.parse(materialization.getMaterializedPayload());
        requireExplicitOperationalEffect(definition, materialization.getTargetLayer(), materialization.getTargetArtifactType(),
                materialization.getTargetArtifactKey(), payload);
        String expectedHash = DomainRuleMaterializationFingerprint.sha256(definition, materialization.getTargetLayer(),
                materialization.getTargetArtifactType(), materialization.getTargetArtifactKey(), materialization.getTargetPointer(),
                materialization.getMaterializedRuleId(), payload);
        if (!expectedHash.equals(materialization.getSourceHash())) {
            throw new ConfigurationIngestionException("Operational policy sourceHash must match its canonical projection");
        }
    }

    private void requireExplicitOperationalEffect(DomainRuleDefinition definition, String layer, String type,
            String key, JsonNode payload) {
        if (!OperationalPolicyTarget.Family.supports(layer, type)) return;
        var target = new OperationalPolicyTarget(layer, type, key);
        OperationalPolicyContract.validate(definition, target, payload);
        JsonNode expected = operationalProjection(definition, target);
        if (!org.praxisplatform.rules.digest.PraxisCanonicalJson.canonicalize(expected)
                .equals(org.praxisplatform.rules.digest.PraxisCanonicalJson.canonicalize(payload))) {
            throw new ConfigurationIngestionException("Operational policy payload must match its canonical definition projection");
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
        if (OperationalPolicyTarget.Family.supports(targetLayer, targetArtifactType)) {
            return DomainRuleMaterializationFingerprint.sha256(definition, targetLayer, targetArtifactType,
                    targetArtifactKey, targetPointer, materializedRuleId, materializedPayload);
        }
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

    private boolean isVisualGuidanceRuleType(String ruleType) {
        return "visual_guidance".equals(ruleType);
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
            DomainRuleGovernancePrincipal principal,
            Instant now) {
        if ("draft".equals(materialization.getStatus())
                || "pending_review".equals(materialization.getStatus())
                || "applied".equals(materialization.getStatus())) {
            if (!hasActiveDefinition(materialization)) {
                throw new ConfigurationIngestionException(
                        "Rule materialization can only be applied when its definition is active");
            }
            supersedeAppliedTargetHeads(materialization, principal, now);
        }
        if ("draft".equals(materialization.getStatus()) || "pending_review".equals(materialization.getStatus())) {
            materialization.setStatus("applied");
            materialization.setEverApplied(true);
            materialization.setAppliedByType("authenticated");
            materialization.setAppliedBy(principal.actorRef());
            materialization.setAppliedAt(now);
            DomainRuleMaterialization saved = materializationRepository.save(materialization);
            recordMaterializationEvent(
                    saved,
                    "materialization.applied",
                    saved.getAppliedAt(),
                    saved.getAppliedByType(),
                    saved.getAppliedBy(),
                    "Decision materialization applied");
            return saved;
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
                    .sorted(materializationComparator())
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
                    .filter(materialization -> isRuntimeEligibleMaterialization(materialization, status))
                    .sorted(materializationComparator())
                    .map(this::toResponse)
                    .toList();
        }
        if (StringUtils.hasText(status)) {
            return materializationRepository
                    .findByTenantIdAndEnvironmentAndStatus(resolvedTenant, resolvedEnvironment, status.trim())
                    .stream()
                    .filter(materialization -> isRuntimeEligibleMaterialization(materialization, status))
                    .sorted(materializationComparator())
                    .map(this::toResponse)
                    .toList();
        }
        return materializationRepository.findAll().stream()
                .filter(materialization -> matchesScope(materialization.getTenantId(), resolvedTenant))
                .filter(materialization -> matchesScope(materialization.getEnvironment(), resolvedEnvironment))
                .sorted(materializationComparator())
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

        ArrayList<DomainRuleTimelineEventResponse> events = eventRepository
                .findByTenantIdAndEnvironmentAndRuleDefinition_IdOrderByOccurredAtAscEventTypeAsc(
                        normalize(tenantId),
                        normalize(environment),
                        definitionId)
                .stream()
                .map(this::toTimelineEvent)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (events.isEmpty()) {
            addDefinitionTimelineEvents(events, definition);
            materializationRepository
                    .findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                            normalize(tenantId),
                            normalize(environment),
                            definitionId)
                    .stream()
                    .sorted(materializationComparator())
                    .forEach(materialization -> addMaterializationTimelineEvents(events, materialization));
        }
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

    private static final Set<String> SAFE_TIMELINE_ARG_KEYS = Set.of(
            "requiredApprovalCount",
            "materializationCount",
            "publicationReadiness",
            "publicationId",
            "targetLayer",
            "targetArtifactType",
            "status",
            "version",
            "existingCoverageCount",
            "predictedMaterializationCount",
            "warningCount");

    private DomainRuleTimelineEventResponse toTimelineEvent(DomainRuleEvent event) {
        DomainRuleMaterialization materialization = event.getMaterialization();
        String eventType = event.getEventType();
        return new DomainRuleTimelineEventResponse(
                eventType,
                event.getOccurredAt(),
                normalize(event.getActorType()),
                normalize(event.getActor()),
                event.getSummary(),
                normalize(event.getStatus()),
                normalize(event.getTargetLayer()),
                normalize(event.getTargetArtifactType()),
                normalize(event.getTargetArtifactKey()),
                materialization != null ? materialization.getId() : null,
                normalize(event.getMaterializationKey()),
                normalize(event.getSourceHash()),
                normalizeOrDefault(event.getVisibility(), "safe"),
                deriveTimelineSummaryCode(eventType),
                extractSafeSummaryArgs(event.getSafeMetadata(), materialization));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainRuleMaterializationResponse transitionMaterializationStatus(
            UUID materializationId,
            DomainRuleStatusTransitionRequest request,
            DomainRuleGovernancePrincipal principal) {
        if (materializationId == null) {
            throw new ConfigurationIngestionException("materializationId is required");
        }
        if (request == null) {
            throw new ConfigurationIngestionException("Domain rule status transition request is required");
        }
        requirePrincipal(principal);
        prepareLifecycleScope(principal);
        String tenantId = principal.tenantId();
        String environment = principal.environment();
        String status = requireAllowedStatus(request.status(), "status", MATERIALIZATION_STATUSES);
        DomainRuleMaterialization materialization = materializationRepository.findById(materializationId)
                .orElseThrow(() -> new ConfigurationIngestionException("Rule materialization not found: " + materializationId));
        requireScope(materialization.getTenantId(), tenantId, "tenantId");
        requireScope(materialization.getEnvironment(), environment, "environment");
        lifecycleRefresh.materialization(materialization);
        lifecycleRefresh.definition(materialization.getRuleDefinition());
        requireScope(materialization.getTenantId(), tenantId, "tenantId");
        requireScope(materialization.getEnvironment(), environment, "environment");
        requireScope(materialization.getRuleDefinition().getTenantId(), tenantId, "tenantId");
        requireScope(materialization.getRuleDefinition().getEnvironment(), environment, "environment");
        requireAllowedMaterializationTransition(materialization.getStatus(), status);
        if ("applied".equals(status)) {
            requireGenericMaterializationTarget(materialization.getRuleDefinition(), materialization.getTargetArtifactType());
            requireSnapshotMaterializationForParameters(materialization.getRuleDefinition());
            requireOperationalIntegrity(materialization);
        }
        if ("applied".equals(status) && !hasActiveDefinition(materialization)) {
            throw new ConfigurationIngestionException("Rule materialization can only be applied when its definition is active");
        }

        String previousStatus = materialization.getStatus();
        Instant now = Instant.now();
        if ("applied".equals(status)) {
            supersedeAppliedTargetHeads(materialization, principal, now);
        }
        materialization.setStatus(status);
        if ("applied".equals(status)) {
            materialization.setEverApplied(true);
        }
        materialization.setValidationResult(writeNullable(request.validationResult()));
        if ("applied".equals(status) && !"applied".equals(previousStatus)) {
            materialization.setAppliedByType("authenticated");
            materialization.setAppliedBy(principal.actorRef());
            materialization.setAppliedAt(now);
        }
        DomainRuleMaterialization saved = materializationRepository.save(materialization);
        if ("applied".equals(status) && !"applied".equals(previousStatus)) {
            recordMaterializationEvent(
                    saved,
                    "materialization.applied",
                    saved.getAppliedAt(),
                    saved.getAppliedByType(),
                    saved.getAppliedBy(),
                    "Decision materialization applied");
        }
        return toResponse(saved);
    }

    private void recordDefinitionEvent(
            DomainRuleDefinition definition,
            String eventType,
            Instant occurredAt,
            String actorType,
            String actor,
            String summary,
            String status) {
        if (definition == null || definition.getId() == null || occurredAt == null) {
            return;
        }
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(definition.getTenantId())
                .environment(definition.getEnvironment())
                .ruleDefinition(definition)
                .eventType(eventType)
                .occurredAt(occurredAt)
                .actorType(normalize(actorType))
                .actor(normalize(actor))
                .summary(summary)
                .status(normalize(status))
                .build());
    }

    private void recordMaterializationEvent(
            DomainRuleMaterialization materialization,
            String eventType,
            Instant occurredAt,
            String actorType,
            String actor,
            String summary) {
        if (materialization == null || materialization.getRuleDefinition() == null || occurredAt == null) {
            return;
        }
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(materialization.getTenantId())
                .environment(materialization.getEnvironment())
                .ruleDefinition(materialization.getRuleDefinition())
                .eventType(eventType)
                .occurredAt(occurredAt)
                .actorType(normalize(actorType))
                .actor(normalize(actor))
                .summary(summary)
                .status(normalize(materialization.getStatus()))
                .targetLayer(normalize(materialization.getTargetLayer()))
                .targetArtifactType(normalize(materialization.getTargetArtifactType()))
                .targetArtifactKey(normalize(materialization.getTargetArtifactKey()))
                .materialization(materialization.getId() != null ? materialization : null)
                .materializationKey(normalize(materialization.getMaterializationKey()))
                .sourceHash(normalize(materialization.getSourceHash()))
                .build());
    }

    private void recordPublicationEvent(
            DomainRuleDefinition definition,
            String eventType,
            Instant occurredAt,
            String actorType,
            String actor,
            String summary,
            String status,
            String publicationReadiness,
            int materializationCount,
            UUID publicationId) {
        if (definition == null || definition.getId() == null || occurredAt == null) {
            return;
        }
        ObjectNode safeMetadata = objectMapper.createObjectNode();
        if (publicationId != null) {
            safeMetadata.put("publicationId", publicationId.toString());
        }
        if (StringUtils.hasText(publicationReadiness)) {
            safeMetadata.put("publicationReadiness", publicationReadiness);
        }
        safeMetadata.put("materializationCount", Math.max(0, materializationCount));
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(definition.getTenantId())
                .environment(definition.getEnvironment())
                .ruleDefinition(definition)
                .eventType(eventType)
                .occurredAt(occurredAt)
                .actorType(normalizeOrDefault(actorType, "human"))
                .actor(normalize(actor))
                .summary(summary)
                .status(normalize(status))
                .safeMetadata(safeMetadata.toString())
                .build());
    }

    private void recordApprovalRequestedEventIfRequired(DomainRuleDefinition definition, Instant occurredAt) {
        if (definition == null || definition.getId() == null || occurredAt == null) {
            return;
        }
        ArrayNode requiredApprovals = buildRequiredApprovals(read(definition.getGovernance()));
        if (requiredApprovals.isEmpty()) {
            return;
        }
        ObjectNode safeMetadata = objectMapper.createObjectNode();
        safeMetadata.put("requiredApprovalCount", requiredApprovals.size());
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(definition.getTenantId())
                .environment(definition.getEnvironment())
                .ruleDefinition(definition)
                .eventType("approval.requested")
                .occurredAt(occurredAt)
                .actorType(normalizeOrDefault(definition.getCreatedByType(), "system"))
                .actor(normalize(definition.getCreatedBy()))
                .summary("Decision approval requested")
                .status("requested")
                .safeMetadata(safeMetadata.toString())
                .build());
    }

    private void recordApprovalCompletedEvent(
            DomainRuleDefinition definition,
            Instant occurredAt,
            String actorType,
            String actor) {
        if (definition == null || definition.getId() == null || occurredAt == null) {
            return;
        }
        ArrayNode requiredApprovals = buildRequiredApprovals(read(definition.getGovernance()));
        ObjectNode safeMetadata = objectMapper.createObjectNode();
        safeMetadata.put("requiredApprovalCount", requiredApprovals.size());
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(definition.getTenantId())
                .environment(definition.getEnvironment())
                .ruleDefinition(definition)
                .eventType("approval.completed")
                .occurredAt(occurredAt)
                .actorType(normalizeOrDefault(actorType, "human"))
                .actor(normalize(actor))
                .summary("Decision approval completed")
                .status("completed")
                .safeMetadata(safeMetadata.toString())
                .build());
    }

    private void recordIntakeReceivedEvent(
            DomainRuleDefinitionResponse definition,
            ArrayNode existingCoverage,
            ArrayNode predictedMaterializations,
            ArrayNode requiredApprovals,
            ArrayNode warnings) {
        if (definition == null || definition.id() == null || definition.createdAt() == null) {
            return;
        }
        DomainRuleDefinition eventDefinition = definitionRepository.getReferenceById(definition.id());
        ObjectNode safeMetadata = objectMapper.createObjectNode();
        safeMetadata.put("decisionStage", "intake");
        safeMetadata.put("definitionStatus", normalize(definition.status()));
        safeMetadata.put("existingCoverageCount", existingCoverage != null ? existingCoverage.size() : 0);
        safeMetadata.put("predictedMaterializationCount",
                predictedMaterializations != null ? predictedMaterializations.size() : 0);
        safeMetadata.put("requiredApprovalCount", requiredApprovals != null ? requiredApprovals.size() : 0);
        safeMetadata.put("warningCount", warnings != null ? warnings.size() : 0);
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(definition.tenantId())
                .environment(definition.environment())
                .ruleDefinition(eventDefinition)
                .eventType("intake.received")
                .occurredAt(definition.createdAt())
                .actorType(normalizeOrDefault(definition.createdByType(), "llm"))
                .actor(normalize(definition.createdBy()))
                .summary("Decision intake received")
                .status(normalize(definition.status()))
                .safeMetadata(safeMetadata.toString())
                .build());
    }

    private void recordSimulationRequestedEvent(DomainRuleDefinition definition, Instant occurredAt) {
        if (definition == null || definition.getId() == null || occurredAt == null) {
            return;
        }
        ObjectNode safeMetadata = objectMapper.createObjectNode();
        safeMetadata.put("decisionSource", "persisted_definition");
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(definition.getTenantId())
                .environment(definition.getEnvironment())
                .ruleDefinition(definition)
                .eventType("simulation.requested")
                .occurredAt(occurredAt)
                .actorType("system")
                .summary("Decision simulation requested")
                .status("requested")
                .safeMetadata(safeMetadata.toString())
                .build());
    }

    private void recordSimulationCompletedEvent(
            DomainRuleDefinition definition,
            Instant occurredAt,
            String result,
            String publicationReadiness,
            ArrayNode existingCoverage,
            ArrayNode predictedMaterializations,
            ArrayNode requiredApprovals,
            ArrayNode warnings) {
        if (definition == null || definition.getId() == null || occurredAt == null) {
            return;
        }
        ObjectNode safeMetadata = objectMapper.createObjectNode();
        safeMetadata.put("result", normalize(result));
        if (StringUtils.hasText(publicationReadiness)) {
            safeMetadata.put("publicationReadiness", publicationReadiness);
        }
        safeMetadata.put("existingCoverageCount", existingCoverage != null ? existingCoverage.size() : 0);
        safeMetadata.put("predictedMaterializationCount",
                predictedMaterializations != null ? predictedMaterializations.size() : 0);
        safeMetadata.put("requiredApprovalCount", requiredApprovals != null ? requiredApprovals.size() : 0);
        safeMetadata.put("warningCount", warnings != null ? warnings.size() : 0);
        eventRepository.save(DomainRuleEvent.builder()
                .tenantId(definition.getTenantId())
                .environment(definition.getEnvironment())
                .ruleDefinition(definition)
                .eventType("simulation.completed")
                .occurredAt(occurredAt)
                .actorType("system")
                .summary("Decision simulation completed")
                .status("completed")
                .safeMetadata(safeMetadata.toString())
                .build());
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
                "safe",
                deriveTimelineSummaryCode(eventType),
                extractSafeSummaryArgs(null, materialization)));
    }

    private String deriveTimelineSummaryCode(String eventType) {
        if (!StringUtils.hasText(eventType)) {
            return null;
        }
        String normalized = eventType.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? null : "domain_rule.event." + normalized;
    }

    private Map<String, Object> extractSafeSummaryArgs(
            String safeMetadataJson,
            DomainRuleMaterialization materialization) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (StringUtils.hasText(safeMetadataJson)) {
            try {
                JsonNode metadataNode = objectMapper.readTree(safeMetadataJson);
                if (metadataNode != null && metadataNode.isObject()) {
                    for (String key : SAFE_TIMELINE_ARG_KEYS) {
                        JsonNode field = metadataNode.get(key);
                        if (field == null || field.isNull() || field.isContainerNode()) {
                            continue;
                        }
                        if (field.isIntegralNumber()) {
                            args.put(key, field.longValue());
                        } else if (field.isFloatingPointNumber()) {
                            args.put(key, field.doubleValue());
                        } else if (field.isBoolean()) {
                            args.put(key, field.booleanValue());
                        } else if (field.isTextual()) {
                            String value = field.asText().trim();
                            if (!value.isEmpty()) {
                                args.put(key, value.length() > 128 ? value.substring(0, 128) : value);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Safe summary args are additive; malformed historical metadata must not break timelines.
            }
        }
        if (materialization != null) {
            if (StringUtils.hasText(materialization.getTargetLayer())) {
                args.putIfAbsent("targetLayer", materialization.getTargetLayer().trim());
            }
            if (StringUtils.hasText(materialization.getTargetArtifactType())) {
                args.putIfAbsent("targetArtifactType", materialization.getTargetArtifactType().trim());
            }
        }
        return args.isEmpty() ? Map.of() : Map.copyOf(args);
    }

    private boolean hasActiveDefinition(DomainRuleMaterialization materialization) {
        DomainRuleDefinition definition = materialization.getRuleDefinition();
        return definition != null && "active".equals(definition.getStatus());
    }

    private boolean isRuntimeEligibleMaterialization(
            DomainRuleMaterialization materialization,
            String requestedStatus) {
        return !"applied".equals(normalize(requestedStatus)) || hasActiveDefinition(materialization);
    }

    private void supersedeAppliedMaterializationsForDefinition(
            DomainRuleDefinition definition,
            DomainRuleGovernancePrincipal principal,
            Instant now) {
        materializationRepository.findForUpdateByDefinition(
                        normalize(definition.getTenantId()),
                        normalize(definition.getEnvironment()),
                        definition.getId())
                .stream()
                .peek(lifecycleRefresh::materialization)
                .filter(materialization -> "applied".equals(materialization.getStatus()))
                .forEach(materialization -> supersedeMaterialization(materialization, principal, now));
    }

    private void supersedeAppliedTargetHeads(
            DomainRuleMaterialization selected,
            DomainRuleGovernancePrincipal principal,
            Instant now) {
        List<DomainRuleMaterialization> superseded = materializationRepository.findAppliedForUpdateByExactTarget(
                        normalize(selected.getTenantId()),
                        normalize(selected.getEnvironment()),
                        selected.getTargetLayer(),
                        selected.getTargetArtifactType(),
                        selected.getTargetArtifactKey())
                .stream()
                .filter(existing -> !existing.getId().equals(selected.getId()))
                .toList();
        superseded.forEach(lifecycleRefresh::materialization);
        superseded.forEach(existing -> supersedeMaterialization(existing, principal, now));
        if (!superseded.isEmpty()) {
            // The partial unique index is immediate in PostgreSQL. Flush the previous head first so
            // the selected projection can become applied without relying on Hibernate update order.
            materializationRepository.flush();
        }
    }

    private void supersedeMaterialization(
            DomainRuleMaterialization materialization,
            DomainRuleGovernancePrincipal principal,
            Instant now) {
        materialization.setStatus("superseded");
        DomainRuleMaterialization saved = materializationRepository.save(materialization);
        recordMaterializationEvent(
                saved,
                "materialization.superseded",
                now,
                "authenticated",
                principal.actorRef(),
                "Decision materialization superseded by the governed target head");
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

    private void validateFactCatalog(JsonNode definition) {
        if (definition == null || definition.isNull() || !definition.has("factCatalog")) {
            return;
        }
        JsonNode catalog = definition.path("factCatalog");
        if (!catalog.isObject()) {
            throw new ConfigurationIngestionException("definition.factCatalog must be an object");
        }
        if (!FACT_CATALOG_SCHEMA_VERSION.equals(catalog.path("schemaVersion").asText())) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog.schemaVersion must be " + FACT_CATALOG_SCHEMA_VERSION);
        }
        JsonNode facts = catalog.path("facts");
        if (!facts.isArray()) {
            throw new ConfigurationIngestionException("definition.factCatalog.facts must be an array");
        }
        if (facts.size() > 256) {
            throw new ConfigurationIngestionException("definition.factCatalog.facts cannot exceed 256 entries");
        }
        Set<String> paths = new HashSet<>();
        facts.forEach(item -> {
            if (!item.isObject()) {
                throw new ConfigurationIngestionException("definition.factCatalog facts must be objects");
            }
            String path = requiredFactText(item, "path");
            if (!STABLE_FACT_PATH.matcher(path).matches()) {
                throw new ConfigurationIngestionException("definition.factCatalog fact path is invalid: " + path);
            }
            if (!paths.add(path)) {
                throw new ConfigurationIngestionException("definition.factCatalog fact path is duplicated: " + path);
            }
            String valueType = requireFactEnum(item, "valueType", FACT_VALUE_TYPES);
            if (!item.has("nullable") || !item.path("nullable").isBoolean()) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact nullable must be boolean: " + path);
            }
            requireLocalizedFactText(item, "labels", path);
            requireLocalizedFactText(item, "descriptions", path);
            requiredFactText(item, "providerRef");
            JsonNode evidenceRefs = item.path("evidenceRefs");
            if (!evidenceRefs.isArray() || evidenceRefs.isEmpty()
                    || java.util.stream.StreamSupport.stream(evidenceRefs.spliterator(), false)
                    .anyMatch(ref -> !ref.isTextual() || !StringUtils.hasText(ref.asText()))) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact evidenceRefs must contain non-empty references: " + path);
            }
            String sensitivity = requireFactEnum(item, "sensitivity", FACT_SENSITIVITIES);
            String redaction = requireFactEnum(item, "redaction", FACT_REDACTION_POLICIES);
            validateOptionalLocalizedMap(item, "hints", path, MAX_FACT_HINTS_LENGTH);
            validateOptionalPatternText(item, "groupKey", path, FACT_GROUP_KEY_PATTERN);
            validateOptionalLocalizedMap(item, "groupLabels", path, MAX_FACT_GROUP_LABEL_LENGTH);
            validateOptionalEnumText(item, "valuePresentation", path, FACT_VALUE_PRESENTATIONS);
            validateOptionalBoundedText(item, "unit", path, MAX_FACT_UNIT_LENGTH);
            validateOptionalBoundedText(item, "mask", path, MAX_FACT_MASK_LENGTH);
            validateOptionalExample(item, path, sensitivity, redaction);
            validateOptionalAllowedValues(item, path, valueType, sensitivity, redaction);
            validateOptionalOptionSourceRef(item, path);
            validateOptionalConstraints(item, path);
            validateOptionalGovernance(item, path, sensitivity, redaction);
            validateOptionalLineage(item, path);
        });
    }

    private DomainRuleFactDescriptor toFactDescriptor(JsonNode item) {
        return new DomainRuleFactDescriptor(
                item.path("path").asText(),
                item.path("valueType").asText(),
                item.path("nullable").asBoolean(),
                localizedFactText(item.path("labels")),
                localizedFactText(item.path("descriptions")),
                item.path("providerRef").asText(),
                java.util.stream.StreamSupport.stream(item.path("evidenceRefs").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList(),
                item.path("sensitivity").asText(),
                item.path("redaction").asText(),
                optionalLocalizedFactText(item.get("hints")),
                optionalText(item.get("groupKey")),
                optionalLocalizedFactText(item.get("groupLabels")),
                optionalText(item.get("valuePresentation")),
                optionalText(item.get("unit")),
                optionalText(item.get("mask")),
                parseAllowedValues(item.get("allowedValues")),
                parseOptionSourceRef(item.get("optionSourceRef")),
                optionalText(item.get("example")),
                parseConstraints(item.get("constraints")),
                parseGovernanceMetadata(item.get("governance")),
                parseLineageMetadata(item.get("lineage")));
    }

    private Map<String, String> optionalLocalizedFactText(JsonNode source) {
        if (source == null || source.isNull() || !source.isObject() || source.isEmpty()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        source.fields().forEachRemaining(entry -> {
            if (StringUtils.hasText(entry.getKey())
                    && entry.getValue().isTextual()
                    && StringUtils.hasText(entry.getValue().asText())) {
                values.put(entry.getKey().trim(), entry.getValue().asText().trim());
            }
        });
        return values.isEmpty() ? null : Map.copyOf(values);
    }

    private String optionalText(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String trimmed = node.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<DomainRuleFactDescriptor.AllowedValue> parseAllowedValues(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<DomainRuleFactDescriptor.AllowedValue> result = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry == null || !entry.isObject()) {
                continue;
            }
            JsonNode valueNode = entry.get("value");
            Object scalar = scalarJsonValue(valueNode);
            Map<String, String> labels = optionalLocalizedFactText(entry.get("labels"));
            if (scalar == null || labels == null) {
                continue;
            }
            result.add(new DomainRuleFactDescriptor.AllowedValue(
                    scalar,
                    labels,
                    optionalText(entry.get("icon")),
                    optionalText(entry.get("tone")),
                    optionalText(entry.get("description"))));
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

    private Object scalarJsonValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isBoolean()) {
            return valueNode.booleanValue();
        }
        if (valueNode.isNumber()) {
            return valueNode.numberValue();
        }
        if (valueNode.isTextual()) {
            String trimmed = valueNode.asText().trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private DomainRuleFactDescriptor.OptionSourceRef parseOptionSourceRef(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        String sourceKey = optionalText(node.get("sourceKey"));
        if (sourceKey == null) {
            return null;
        }
        return new DomainRuleFactDescriptor.OptionSourceRef(
                sourceKey, optionalText(node.get("resourceKey")));
    }

    private DomainRuleFactDescriptor.Constraints parseConstraints(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject() || node.isEmpty()) {
            return null;
        }
        BigDecimal min = optionalDecimal(node.get("min"));
        BigDecimal max = optionalDecimal(node.get("max"));
        BigDecimal step = optionalDecimal(node.get("step"));
        Integer minLength = optionalInt(node.get("minLength"));
        Integer maxLength = optionalInt(node.get("maxLength"));
        String pattern = optionalText(node.get("pattern"));
        if (min == null && max == null && step == null
                && minLength == null && maxLength == null && pattern == null) {
            return null;
        }
        return new DomainRuleFactDescriptor.Constraints(min, max, step, minLength, maxLength, pattern);
    }

    private BigDecimal optionalDecimal(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.decimalValue();
    }

    private Integer optionalInt(JsonNode node) {
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.intValue();
    }

    private DomainRuleFactDescriptor.GovernanceMetadata parseGovernanceMetadata(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject() || node.isEmpty()) {
            return null;
        }
        String classification = optionalText(node.get("classification"));
        String dataCategory = optionalText(node.get("dataCategory"));
        Boolean exportable = node.has("exportable") && node.get("exportable").isBoolean()
                ? node.get("exportable").booleanValue()
                : null;
        String aiVisibility = optionalText(node.get("aiVisibility"));
        List<String> complianceTags = null;
        JsonNode tagsNode = node.get("complianceTags");
        if (tagsNode != null && tagsNode.isArray() && !tagsNode.isEmpty()) {
            List<String> tags = new ArrayList<>();
            tagsNode.forEach(tag -> {
                if (tag.isTextual() && StringUtils.hasText(tag.asText())) {
                    tags.add(tag.asText().trim());
                }
            });
            if (!tags.isEmpty()) {
                complianceTags = List.copyOf(tags);
            }
        }
        if (classification == null && dataCategory == null && exportable == null
                && aiVisibility == null && complianceTags == null) {
            return null;
        }
        return new DomainRuleFactDescriptor.GovernanceMetadata(
                classification, dataCategory, exportable, aiVisibility, complianceTags);
    }

    private DomainRuleFactDescriptor.LineageMetadata parseLineageMetadata(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject() || node.isEmpty()) {
            return null;
        }
        String attributeId = optionalText(node.get("attributeId"));
        String sourceResourceKey = optionalText(node.get("sourceResourceKey"));
        String transformation = optionalText(node.get("transformation"));
        if (attributeId == null && sourceResourceKey == null && transformation == null) {
            return null;
        }
        return new DomainRuleFactDescriptor.LineageMetadata(attributeId, sourceResourceKey, transformation);
    }

    private void validateOptionalLocalizedMap(JsonNode item, String field, String path, int maxLength) {
        if (!item.has(field) || item.get(field).isNull()) {
            return;
        }
        JsonNode values = item.get(field);
        if (!values.isObject() || values.isEmpty()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " must be a non-empty localized object: " + path);
        }
        values.fields().forEachRemaining(entry -> {
            if (!StringUtils.hasText(entry.getKey())
                    || !entry.getValue().isTextual()
                    || !StringUtils.hasText(entry.getValue().asText())) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact " + field + " must contain non-blank localized text: " + path);
            }
            if (entry.getValue().asText().trim().length() > maxLength) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact " + field + " exceeds max length " + maxLength + ": " + path);
            }
        });
    }

    private void validateOptionalPatternText(JsonNode item, String field, String path, Pattern pattern) {
        if (!item.has(field) || item.get(field).isNull()) {
            return;
        }
        JsonNode node = item.get(field);
        if (!node.isTextual() || !pattern.matcher(node.asText().trim()).matches()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " is invalid: " + path);
        }
    }

    private void validateOptionalEnumText(JsonNode item, String field, String path, Set<String> allowed) {
        if (!item.has(field) || item.get(field).isNull()) {
            return;
        }
        JsonNode node = item.get(field);
        if (!node.isTextual() || !allowed.contains(node.asText().trim())) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " must be one of " + allowed + ": " + path);
        }
    }

    private void validateOptionalBoundedText(JsonNode item, String field, String path, int maxLength) {
        if (!item.has(field) || item.get(field).isNull()) {
            return;
        }
        JsonNode node = item.get(field);
        if (!node.isTextual() || !StringUtils.hasText(node.asText())) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " must be a non-blank string: " + path);
        }
        if (node.asText().trim().length() > maxLength) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " exceeds max length " + maxLength + ": " + path);
        }
    }

    private void validateOptionalExample(
            JsonNode item, String path, String sensitivity, String redaction) {
        if (!item.has("example") || item.get("example").isNull()) {
            return;
        }
        if (isRestrictedFact(sensitivity, redaction)) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact example is forbidden for SECRET/OMIT facts: " + path);
        }
        validateOptionalBoundedText(item, "example", path, MAX_FACT_EXAMPLE_LENGTH);
    }

    private void validateOptionalAllowedValues(
            JsonNode item, String path, String valueType, String sensitivity, String redaction) {
        if (!item.has("allowedValues") || item.get("allowedValues").isNull()) {
            return;
        }
        if (isRestrictedFact(sensitivity, redaction)) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact allowedValues is forbidden for SECRET/OMIT facts: " + path);
        }
        JsonNode allowed = item.get("allowedValues");
        if (!allowed.isArray() || allowed.isEmpty()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact allowedValues must be a non-empty array: " + path);
        }
        if (allowed.size() > MAX_FACT_ALLOWED_VALUES) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact allowedValues exceeds max size "
                            + MAX_FACT_ALLOWED_VALUES + ": " + path);
        }
        Set<String> seenValues = new HashSet<>();
        for (JsonNode option : allowed) {
            if (!option.isObject()) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact allowedValues entries must be objects: " + path);
            }
            JsonNode valueNode = option.get("value");
            if (!isAllowedScalarForValueType(valueNode, valueType)) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact allowedValues value mismatches valueType "
                                + valueType + ": " + path);
            }
            String canonicalKey = valueNode.isNumber()
                    ? valueNode.decimalValue().stripTrailingZeros().toPlainString()
                    : valueNode.asText().trim();
            if (!seenValues.add(canonicalKey)) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact allowedValues contains duplicate value: " + path);
            }
            requireLocalizedFactText(option, "labels", path);
            validateOptionalBoundedText(option, "icon", path, MAX_FACT_ICON_LENGTH);
            validateOptionalBoundedText(option, "tone", path, MAX_FACT_TONE_LENGTH);
            validateOptionalBoundedText(
                    option, "description", path, MAX_FACT_OPTION_DESCRIPTION_LENGTH);
        }
    }

    private boolean isAllowedScalarForValueType(JsonNode valueNode, String valueType) {
        if (valueNode == null || valueNode.isNull()) {
            return false;
        }
        return switch (valueType) {
            case "boolean" -> valueNode.isBoolean();
            case "number", "number-array", "array<number>" -> valueNode.isNumber();
            case "string", "date", "string-array", "date-array" ->
                    valueNode.isTextual() && StringUtils.hasText(valueNode.asText());
            default -> false;
        };
    }

    private void validateOptionalOptionSourceRef(JsonNode item, String path) {
        if (!item.has("optionSourceRef") || item.get("optionSourceRef").isNull()) {
            return;
        }
        JsonNode ref = item.get("optionSourceRef");
        if (!ref.isObject()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact optionSourceRef must be an object: " + path);
        }
        String sourceKey = requiredFactText(ref, "sourceKey");
        if (sourceKey.length() > MAX_FACT_SOURCE_KEY_LENGTH) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact optionSourceRef.sourceKey exceeds max length: " + path);
        }
        validateOptionalBoundedText(ref, "resourceKey", path, MAX_FACT_RESOURCE_KEY_LENGTH);
    }

    private void validateOptionalConstraints(JsonNode item, String path) {
        if (!item.has("constraints") || item.get("constraints").isNull()) {
            return;
        }
        JsonNode constraints = item.get("constraints");
        if (!constraints.isObject()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact constraints must be an object: " + path);
        }
        BigDecimal min = requireOptionalJsonNumber(constraints, "min", path);
        BigDecimal max = requireOptionalJsonNumber(constraints, "max", path);
        BigDecimal step = requireOptionalJsonNumber(constraints, "step", path);
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact constraints min cannot exceed max: " + path);
        }
        if (step != null && step.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact constraints step must be positive: " + path);
        }
        Integer minLength = requireOptionalNonNegativeInt(constraints, "minLength", path);
        Integer maxLength = requireOptionalNonNegativeInt(constraints, "maxLength", path);
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact constraints minLength cannot exceed maxLength: " + path);
        }
        if (constraints.has("pattern") && !constraints.get("pattern").isNull()) {
            JsonNode patternNode = constraints.get("pattern");
            if (!patternNode.isTextual() || !StringUtils.hasText(patternNode.asText())) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact constraints.pattern must be non-blank text: " + path);
            }
            String regex = patternNode.asText();
            if (regex.length() > MAX_FACT_PATTERN_LENGTH) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact constraints.pattern exceeds max length: " + path);
            }
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException ex) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact constraints.pattern is invalid regex: " + path, ex);
            }
        }
    }

    private BigDecimal requireOptionalJsonNumber(JsonNode parent, String field, String path) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode node = parent.get(field);
        if (!node.isNumber()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact constraints." + field + " must be numeric: " + path);
        }
        return node.decimalValue();
    }

    private Integer requireOptionalNonNegativeInt(JsonNode parent, String field, String path) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        JsonNode node = parent.get(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact constraints." + field + " must be a non-negative integer: " + path);
        }
        return node.intValue();
    }

    private void validateOptionalGovernance(
            JsonNode item, String path, String sensitivity, String redaction) {
        if (!item.has("governance") || item.get("governance").isNull()) {
            return;
        }
        JsonNode gov = item.get("governance");
        if (!gov.isObject()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact governance must be an object: " + path);
        }
        validateOptionalEnumText(gov, "classification", path, FACT_CLASSIFICATIONS);
        validateOptionalBoundedText(gov, "dataCategory", path, MAX_FACT_DATA_CATEGORY_LENGTH);
        if (gov.has("exportable") && !gov.get("exportable").isNull() && !gov.get("exportable").isBoolean()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact governance.exportable must be boolean: " + path);
        }
        validateOptionalEnumText(gov, "aiVisibility", path, FACT_AI_VISIBILITIES);
        String aiVisibility = optionalText(gov.get("aiVisibility"));
        if (isRestrictedFact(sensitivity, redaction)
                && aiVisibility != null
                && !"deny".equalsIgnoreCase(aiVisibility)) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact governance.aiVisibility must be deny for SECRET/OMIT facts: " + path);
        }
        if (gov.has("complianceTags") && !gov.get("complianceTags").isNull()) {
            JsonNode tags = gov.get("complianceTags");
            if (!tags.isArray() || tags.size() > MAX_FACT_COMPLIANCE_TAGS) {
                throw new ConfigurationIngestionException(
                        "definition.factCatalog fact governance.complianceTags must be an array with at most "
                                + MAX_FACT_COMPLIANCE_TAGS + " entries: " + path);
            }
            for (JsonNode tag : tags) {
                if (!tag.isTextual() || !StringUtils.hasText(tag.asText())) {
                    throw new ConfigurationIngestionException(
                            "definition.factCatalog fact governance.complianceTags entries must be non-blank text: "
                                    + path);
                }
            }
        }
    }

    private void validateOptionalLineage(JsonNode item, String path) {
        if (!item.has("lineage") || item.get("lineage").isNull()) {
            return;
        }
        JsonNode lineage = item.get("lineage");
        if (!lineage.isObject()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact lineage must be an object: " + path);
        }
        if (lineage.has("sourcePaths") && !lineage.get("sourcePaths").isNull()) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact lineage.sourcePaths is forbidden in published fact catalogs: "
                            + path);
        }
        validateOptionalBoundedText(lineage, "attributeId", path, MAX_FACT_ATTRIBUTE_ID_LENGTH);
        validateOptionalBoundedText(lineage, "sourceResourceKey", path, MAX_FACT_RESOURCE_KEY_LENGTH);
        validateOptionalBoundedText(lineage, "transformation", path, MAX_FACT_TRANSFORMATION_LENGTH);
    }

    private List<DomainRuleFactDescriptor> mergeOverlayFacts(
            List<DomainRuleFactDescriptor> baseFacts,
            List<DomainRuleFactDescriptor> candidateFacts) {
        if (candidateFacts == null || candidateFacts.size() != baseFacts.size()) {
            return baseFacts;
        }
        List<DomainRuleFactDescriptor> merged = new ArrayList<>(baseFacts.size());
        for (int i = 0; i < baseFacts.size(); i++) {
            DomainRuleFactDescriptor base = baseFacts.get(i);
            DomainRuleFactDescriptor candidate = candidateFacts.get(i);
            merged.add(applyOverlayEntry(base, candidate));
        }
        return List.copyOf(merged);
    }

    private DomainRuleFactDescriptor applyOverlayEntry(
            DomainRuleFactDescriptor base, DomainRuleFactDescriptor candidate) {
        if (candidate == null
                || !Objects.equals(base.path(), candidate.path())
                || !Objects.equals(base.valueType(), candidate.valueType())
                || base.nullable() != candidate.nullable()) {
            return base;
        }
        String sensitivity = stricterSensitivity(base.sensitivity(), candidate.sensitivity());
        String redaction = stricterRedaction(base.redaction(), candidate.redaction());
        boolean restricted = isRestrictedFact(sensitivity, redaction);

        Map<String, String> labels = mergeLocalizedEditorialMap(
                base.labels(), candidate.labels(), 255, value -> isGenericFactLabel(value, base.path()));
        Map<String, String> descriptions = mergeLocalizedEditorialMap(
                base.descriptions(), candidate.descriptions(), 2000, this::isBoilerplateFactDescription);
        Map<String, String> hints = mergeLocalizedEditorialMap(
                base.hints(), candidate.hints(), MAX_FACT_HINTS_LENGTH, value -> false);

        String candidateGroupKey = validPatternText(candidate.groupKey(), FACT_GROUP_KEY_PATTERN);
        boolean mismatchedGroupKey = base.groupKey() != null
                && candidateGroupKey != null
                && !base.groupKey().equals(candidateGroupKey);
        Map<String, String> groupLabels = mismatchedGroupKey
                ? base.groupLabels()
                : mergeLocalizedEditorialMap(
                        base.groupLabels(), candidate.groupLabels(), MAX_FACT_GROUP_LABEL_LENGTH, value -> false);

        String groupKey = base.groupKey() != null
                ? base.groupKey()
                : candidateGroupKey;
        List<String> evidenceRefs = mergeOverlayEvidenceRefs(base.evidenceRefs(), candidate.evidenceRefs());
        String valuePresentation = base.valuePresentation() != null
                ? base.valuePresentation()
                : validEnumText(candidate.valuePresentation(), FACT_VALUE_PRESENTATIONS);
        String unit = base.unit() != null
                ? base.unit()
                : validBoundedText(candidate.unit(), MAX_FACT_UNIT_LENGTH);
        String mask = base.mask() != null
                ? base.mask()
                : validBoundedText(candidate.mask(), MAX_FACT_MASK_LENGTH);

        List<DomainRuleFactDescriptor.AllowedValue> allowedValues = null;
        if (!restricted) {
            allowedValues = base.allowedValues() != null && !base.allowedValues().isEmpty()
                    ? base.allowedValues()
                    : sanitizeOverlayAllowedValues(candidate.allowedValues(), base.valueType());
        }

        DomainRuleFactDescriptor.OptionSourceRef optionSourceRef = base.optionSourceRef() != null
                ? base.optionSourceRef()
                : sanitizeOverlayOptionSourceRef(candidate.optionSourceRef());

        String example = restricted
                ? null
                : (base.example() != null
                        ? base.example()
                        : validBoundedText(candidate.example(), MAX_FACT_EXAMPLE_LENGTH));

        DomainRuleFactDescriptor.Constraints constraints =
                mergeConstraints(base.constraints(), candidate.constraints());
        DomainRuleFactDescriptor.GovernanceMetadata governance =
                mergeGovernance(base.governance(), candidate.governance(), restricted);
        DomainRuleFactDescriptor.LineageMetadata lineage = base.lineage() != null
                ? base.lineage()
                : sanitizeOverlayLineage(candidate.lineage());

        return new DomainRuleFactDescriptor(
                base.path(),
                base.valueType(),
                base.nullable(),
                labels,
                descriptions,
                base.providerRef(),
                evidenceRefs,
                sensitivity,
                redaction,
                hints,
                groupKey,
                groupLabels,
                valuePresentation,
                unit,
                mask,
                allowedValues,
                optionSourceRef,
                example,
                constraints,
                governance,
                lineage);
    }

    private List<String> mergeOverlayEvidenceRefs(
            List<String> baseRefs, List<String> candidateRefs) {
        if (candidateRefs == null || candidateRefs.isEmpty()) {
            return baseRefs;
        }
        Set<String> union = new LinkedHashSet<>();
        if (baseRefs != null) {
            for (String ref : baseRefs) {
                String clean = validBoundedText(ref, 255);
                if (clean != null) {
                    union.add(clean);
                }
            }
        }
        for (String ref : candidateRefs) {
            String clean = validBoundedText(ref, 255);
            if (clean != null) {
                union.add(clean);
            }
        }
        return union.isEmpty() ? baseRefs : List.copyOf(union);
    }

    private Map<String, String> mergeLocalizedEditorialMap(
            Map<String, String> base,
            Map<String, String> candidate,
            int maxLength,
            java.util.function.Predicate<String> replaceableFallbackPredicate) {
        if ((base == null || base.isEmpty()) && (candidate == null || candidate.isEmpty())) {
            return null;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        Set<String> unreplacedBaseLocales = new LinkedHashSet<>();
        if (base != null) {
            base.forEach((locale, value) -> {
                if (StringUtils.hasText(locale) && StringUtils.hasText(value)) {
                    String loc = locale.trim();
                    merged.put(loc, value.trim());
                    unreplacedBaseLocales.add(loc);
                }
            });
        }
        boolean candidateContributedRich = false;
        if (candidate != null) {
            for (Map.Entry<String, String> entry : candidate.entrySet()) {
                String locale = entry.getKey();
                String rawValue = entry.getValue();
                if (!StringUtils.hasText(locale) || !StringUtils.hasText(rawValue)) {
                    continue;
                }
                String loc = locale.trim();
                String trimmed = rawValue.trim();
                if (trimmed.length() > maxLength) {
                    continue;
                }
                boolean candidateIsRich = !replaceableFallbackPredicate.test(trimmed);
                if (candidateIsRich) {
                    candidateContributedRich = true;
                }
                String existing = merged.get(loc);
                if (existing == null || (candidateIsRich && replaceableFallbackPredicate.test(existing))) {
                    merged.put(loc, trimmed);
                    unreplacedBaseLocales.remove(loc);
                }
            }
        }
        if (candidateContributedRich && !unreplacedBaseLocales.isEmpty()) {
            for (String staleLocale : unreplacedBaseLocales) {
                String val = merged.get(staleLocale);
                if (val != null && replaceableFallbackPredicate.test(val)) {
                    merged.remove(staleLocale);
                }
            }
        }
        return merged.isEmpty() ? null : Map.copyOf(merged);
    }

    private boolean isGenericFactLabel(String label, String path) {
        if (!StringUtils.hasText(label)) {
            return true;
        }
        String trimmed = label.trim();
        if (trimmed.equals(path) || (StringUtils.hasText(path) && trimmed.equals(path.replace('.', ' ')))) {
            return true;
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) {
            return trimmed.equals("fato: " + path);
        }
        String root = path.substring(0, lastDot);
        String leaf = path.substring(lastDot + 1);
        return trimmed.equals(leaf) || trimmed.equals(root + ": " + leaf);
    }

    private boolean isBoilerplateFactDescription(String description) {
        if (!StringUtils.hasText(description)) {
            return true;
        }
        String trimmed = description.trim();
        return trimmed.startsWith("Fato governado do RuleSet")
                || trimmed.startsWith("Fato de avaliação do RuleSet")
                || trimmed.startsWith("Governed fact for RuleSet")
                || trimmed.startsWith("Governed evaluation fact for RuleSet")
                || trimmed.startsWith("Evaluation fact for RuleSet")
                || trimmed.startsWith("Projeção primitiva de ");
    }

    private String validPatternText(String value, Pattern pattern) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return pattern.matcher(trimmed).matches() ? trimmed : null;
    }

    private String validEnumText(String value, Set<String> allowed) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return allowed.contains(trimmed) ? trimmed : null;
    }

    private String validBoundedText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : null;
    }

    private List<DomainRuleFactDescriptor.AllowedValue> sanitizeOverlayAllowedValues(
            List<DomainRuleFactDescriptor.AllowedValue> values, String valueType) {
        if (values == null || values.isEmpty() || values.size() > MAX_FACT_ALLOWED_VALUES) {
            return null;
        }
        List<DomainRuleFactDescriptor.AllowedValue> sanitized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DomainRuleFactDescriptor.AllowedValue option : values) {
            if (option == null || !isAllowedScalarObjectForValueType(option.value(), valueType)) {
                return null;
            }
            if (option.value() instanceof Double d && !Double.isFinite(d)) {
                return null;
            }
            if (option.value() instanceof Float f && !Float.isFinite(f)) {
                return null;
            }
            if (option.labels() == null || option.labels().isEmpty()
                    || option.labels().entrySet().stream().anyMatch(
                            e -> !StringUtils.hasText(e.getKey()) || !StringUtils.hasText(e.getValue()))) {
                return null;
            }
            String key;
            if (option.value() instanceof BigDecimal bd) {
                key = bd.stripTrailingZeros().toPlainString();
            } else if (option.value() instanceof Number num) {
                try {
                    key = new BigDecimal(num.toString()).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException ex) {
                    return null;
                }
            } else {
                key = String.valueOf(option.value()).trim();
            }
            if (!seen.add(key)) {
                return null;
            }
            sanitized.add(new DomainRuleFactDescriptor.AllowedValue(
                    option.value(),
                    option.labels(),
                    validBoundedText(option.icon(), MAX_FACT_ICON_LENGTH),
                    validBoundedText(option.tone(), MAX_FACT_TONE_LENGTH),
                    validBoundedText(option.description(), MAX_FACT_OPTION_DESCRIPTION_LENGTH)));
        }
        return sanitized.isEmpty() ? null : List.copyOf(sanitized);
    }

    private boolean isAllowedScalarObjectForValueType(Object value, String valueType) {
        if (value == null) {
            return false;
        }
        return switch (valueType) {
            case "boolean" -> value instanceof Boolean;
            case "number", "number-array", "array<number>" -> value instanceof Number;
            case "string", "date", "string-array", "date-array" ->
                    value instanceof String str && StringUtils.hasText(str);
            default -> false;
        };
    }

    private DomainRuleFactDescriptor.OptionSourceRef sanitizeOverlayOptionSourceRef(
            DomainRuleFactDescriptor.OptionSourceRef ref) {
        if (ref == null) {
            return null;
        }
        String sourceKey = validBoundedText(ref.sourceKey(), MAX_FACT_SOURCE_KEY_LENGTH);
        if (sourceKey == null) {
            return null;
        }
        return new DomainRuleFactDescriptor.OptionSourceRef(
                sourceKey, validBoundedText(ref.resourceKey(), MAX_FACT_RESOURCE_KEY_LENGTH));
    }

    private DomainRuleFactDescriptor.Constraints mergeConstraints(
            DomainRuleFactDescriptor.Constraints base,
            DomainRuleFactDescriptor.Constraints candidate) {
        if (base != null) {
            return base;
        }
        if (candidate == null) {
            return null;
        }
        BigDecimal min = candidate.min();
        BigDecimal max = candidate.max();
        BigDecimal step = candidate.step();
        Integer minLength = candidate.minLength();
        Integer maxLength = candidate.maxLength();
        String pattern = candidate.pattern();
        if (min != null && max != null && min.compareTo(max) > 0) {
            return null;
        }
        if (step != null && step.compareTo(BigDecimal.ZERO) <= 0) {
            step = null;
        }
        if ((minLength != null && minLength < 0)
                || (maxLength != null && maxLength < 0)
                || (minLength != null && maxLength != null && minLength > maxLength)) {
            minLength = null;
            maxLength = null;
        }
        if (pattern != null) {
            if (!StringUtils.hasText(pattern) || pattern.length() > MAX_FACT_PATTERN_LENGTH) {
                pattern = null;
            } else {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException ex) {
                    pattern = null;
                }
            }
        }
        if (min == null && max == null && step == null
                && minLength == null && maxLength == null && pattern == null) {
            return null;
        }
        return new DomainRuleFactDescriptor.Constraints(
                min, max, step, minLength, maxLength, pattern);
    }

    private DomainRuleFactDescriptor.GovernanceMetadata mergeGovernance(
            DomainRuleFactDescriptor.GovernanceMetadata base,
            DomainRuleFactDescriptor.GovernanceMetadata candidate,
            boolean restricted) {
        if (base == null && candidate == null) {
            if (!restricted) {
                return null;
            }
            return new DomainRuleFactDescriptor.GovernanceMetadata(null, null, Boolean.FALSE, "deny", null);
        }
        String classification = stricterClassification(
                base != null ? validEnumText(base.classification(), FACT_CLASSIFICATIONS) : null,
                candidate != null ? validEnumText(candidate.classification(), FACT_CLASSIFICATIONS) : null);
        String dataCategory = base != null && base.dataCategory() != null
                ? base.dataCategory()
                : (candidate != null ? validBoundedText(candidate.dataCategory(), MAX_FACT_DATA_CATEGORY_LENGTH) : null);
        Boolean baseExport = base != null ? base.exportable() : null;
        Boolean candExport = candidate != null ? candidate.exportable() : null;
        Boolean exportable;
        if (restricted) {
            exportable = Boolean.FALSE;
        } else if (Boolean.FALSE.equals(baseExport) || Boolean.FALSE.equals(candExport)) {
            exportable = Boolean.FALSE;
        } else {
            exportable = baseExport != null ? baseExport : candExport;
        }

        String aiVisibility;
        if (restricted) {
            aiVisibility = "deny";
        } else {
            aiVisibility = stricterAiVisibility(
                    base != null ? base.aiVisibility() : null,
                    candidate != null ? validEnumText(candidate.aiVisibility(), FACT_AI_VISIBILITIES) : null);
        }
        List<String> complianceTags = mergeGovernanceTags(
                base != null ? base.complianceTags() : null,
                candidate != null ? candidate.complianceTags() : null);
        return new DomainRuleFactDescriptor.GovernanceMetadata(
                classification, dataCategory, exportable, aiVisibility, complianceTags);
    }

    private List<String> mergeGovernanceTags(List<String> baseTags, List<String> candidateTags) {
        if ((baseTags == null || baseTags.isEmpty()) && (candidateTags == null || candidateTags.isEmpty())) {
            return null;
        }
        Set<String> union = new LinkedHashSet<>();
        if (baseTags != null) {
            for (String tag : baseTags) {
                if (StringUtils.hasText(tag) && union.size() < MAX_FACT_COMPLIANCE_TAGS) {
                    union.add(tag.trim());
                }
            }
        }
        if (candidateTags != null) {
            for (String tag : candidateTags) {
                if (StringUtils.hasText(tag) && union.size() < MAX_FACT_COMPLIANCE_TAGS) {
                    union.add(tag.trim());
                }
            }
        }
        return union.isEmpty() ? null : List.copyOf(union);
    }

    private String stricterClassification(String base, String candidate) {
        if (base == null) {
            return candidate;
        }
        if (candidate == null) {
            return base;
        }
        return classificationRank(candidate) > classificationRank(base) ? candidate : base;
    }

    private int classificationRank(String value) {
        if (value == null) {
            return -1;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "public" -> 0;
            case "internal" -> 1;
            case "confidential" -> 2;
            case "restricted" -> 3;
            default -> -1;
        };
    }

    private DomainRuleFactDescriptor.LineageMetadata sanitizeOverlayLineage(
            DomainRuleFactDescriptor.LineageMetadata lineage) {
        if (lineage == null) {
            return null;
        }
        String attributeId = validBoundedText(lineage.attributeId(), MAX_FACT_ATTRIBUTE_ID_LENGTH);
        String sourceResourceKey = validBoundedText(lineage.sourceResourceKey(), MAX_FACT_RESOURCE_KEY_LENGTH);
        String transformation = validBoundedText(lineage.transformation(), MAX_FACT_TRANSFORMATION_LENGTH);
        if (attributeId == null && sourceResourceKey == null && transformation == null) {
            return null;
        }
        return new DomainRuleFactDescriptor.LineageMetadata(
                attributeId, sourceResourceKey, transformation);
    }

    private boolean isRestrictedFact(String sensitivity, String redaction) {
        return "SECRET".equalsIgnoreCase(sensitivity) || "OMIT".equalsIgnoreCase(redaction);
    }

    private String stricterSensitivity(String base, String candidate) {
        String normBase = normalizeSensitivityToken(base);
        String normCandidate = normalizeSensitivityToken(candidate);
        if (normBase == null) {
            return normCandidate != null ? normCandidate : base;
        }
        if (normCandidate == null) {
            return normBase;
        }
        return sensitivityRank(normCandidate) > sensitivityRank(normBase) ? normCandidate : normBase;
    }

    private String normalizeSensitivityToken(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return FACT_SENSITIVITIES.contains(upper)
                ? upper
                : RuleFactDescriptor.normalizeSensitivity(upper);
    }

    private int sensitivityRank(String value) {
        if (value == null) {
            return -1;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "NON_SENSITIVE" -> 0;
            case "PERSONAL" -> 1;
            case "SENSITIVE" -> 2;
            case "SECRET" -> 3;
            default -> -1;
        };
    }

    private String stricterRedaction(String base, String candidate) {
        String normBase = normalizeRedactionToken(base);
        String normCandidate = normalizeRedactionToken(candidate);
        if (normBase == null) {
            return normCandidate != null ? normCandidate : base;
        }
        if (normCandidate == null) {
            return normBase;
        }
        return redactionRank(normCandidate) > redactionRank(normBase) ? normCandidate : normBase;
    }

    private String normalizeRedactionToken(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return FACT_REDACTION_POLICIES.contains(upper) ? upper : null;
    }

    private int redactionRank(String value) {
        if (value == null) {
            return -1;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "NONE" -> 0;
            case "MASK" -> 1;
            case "HASH" -> 2;
            case "OMIT" -> 3;
            default -> -1;
        };
    }

    private String stricterAiVisibility(String base, String candidate) {
        if (base == null) {
            return candidate;
        }
        if (candidate == null) {
            return base;
        }
        return aiVisibilityRank(candidate) > aiVisibilityRank(base) ? candidate : base;
    }

    private int aiVisibilityRank(String value) {
        if (value == null) {
            return -1;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "allow" -> 0;
            case "summarize_only" -> 1;
            case "mask" -> 2;
            case "deny" -> 3;
            default -> -1;
        };
    }

    private Map<String, String> localizedFactText(JsonNode source) {
        Map<String, String> values = new LinkedHashMap<>();
        source.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(values);
    }

    private String requiredFactText(JsonNode item, String field) {
        String value = item.path(field).asText(null);
        if (!StringUtils.hasText(value)) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " is required");
        }
        return value.trim();
    }

    private void requireLocalizedFactText(JsonNode item, String field, String path) {
        JsonNode values = item.path(field);
        if (!values.isObject() || values.isEmpty()
                || java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(values.fields(), 0), false)
                .anyMatch(entry -> !StringUtils.hasText(entry.getKey())
                        || !entry.getValue().isTextual()
                        || !StringUtils.hasText(entry.getValue().asText()))) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " must contain localized text: " + path);
        }
    }

    private String requireFactEnum(JsonNode item, String field, Set<String> allowed) {
        String value = requiredFactText(item, field);
        if (!allowed.contains(value)) {
            throw new ConfigurationIngestionException(
                    "definition.factCatalog fact " + field + " must be one of " + allowed);
        }
        return value;
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
    private void validateParameterDefinition(DomainRuleDefinition definition) {
        DomainRuleParameters.condition(objectMapper,
                DomainRuleParameters.read(objectMapper, definition.getDefinition()),
                DomainRuleParameters.read(objectMapper, definition.getParameters()),
                DomainRuleParameters.read(objectMapper, definition.getCondition()));
    }

    private void requireSnapshotMaterializationForParameters(DomainRuleDefinition definition) {
        if (DomainRuleParameters.template(objectMapper,
                DomainRuleParameters.read(objectMapper, definition.getDefinition())) != null) {
            throw new ConfigurationIngestionException(
                    "Parameterized Engine definitions require governed RuleSet snapshot publication; generic materializations cannot preserve their execution contract");
        }
    }

    private JsonNode validateAndNormalizeDocumentationUrls(JsonNode definition) {
        if (definition == null || definition.isNull() || !definition.isObject()) {
            return definition;
        }
        boolean hasDocFields = definition.has("documentation")
                || definition.has("slotDocumentation")
                || definition.has("legalBasis");
        if (!hasDocFields) {
            return definition;
        }
        ObjectNode copy = ((ObjectNode) definition).deepCopy();
        JsonNode docNode = copy.get("documentation");
        if (docNode != null && !docNode.isNull() && docNode.isObject()) {
            validateLegalBasisOnDocObject((ObjectNode) docNode, "definition.documentation.legalBasis");
        }
        JsonNode slotDocNode = copy.get("slotDocumentation");
        if (slotDocNode != null && !slotDocNode.isNull() && slotDocNode.isObject()) {
            ObjectNode slotDocObj = (ObjectNode) slotDocNode;
            // Support direct documentation object shape (MetadataDomainRuleSetCatalogBridgeSupport)
            validateLegalBasisOnDocObject(slotDocObj, "definition.slotDocumentation.legalBasis");
            // Support map-of-slots shape (slotKey -> documentation object)
            slotDocObj.fields().forEachRemaining(entry -> {
                JsonNode slotEntryValue = entry.getValue();
                if (slotEntryValue != null && slotEntryValue.isObject()) {
                    validateLegalBasisOnDocObject(
                            (ObjectNode) slotEntryValue,
                            "definition.slotDocumentation." + entry.getKey() + ".legalBasis");
                }
            });
        }
        validateLegalBasisOnDocObject(copy, "definition.legalBasis");
        return copy;
    }

    private void validateLegalBasisOnDocObject(ObjectNode docObject, String fieldPrefix) {
        JsonNode legalBasisNode = docObject.get("legalBasis");
        if (legalBasisNode == null || legalBasisNode.isNull()) {
            return;
        }
        if (!legalBasisNode.isArray()) {
            throw new IllegalArgumentException(fieldPrefix + " must be a JSON array");
        }
        for (JsonNode item : legalBasisNode) {
            if (item != null && item.isObject() && item.has("url") && !item.get("url").isNull()) {
                JsonNode urlNode = item.get("url");
                if (!urlNode.isTextual()) {
                    throw new IllegalArgumentException(fieldPrefix + ".url must be a string");
                }
                String validated = RuleLegalBasisUrlValidator.validateOptionalHttpUrl(
                        urlNode.asText(), fieldPrefix + ".url");
                ObjectNode itemObj = (ObjectNode) item;
                if (validated == null) {
                    itemObj.remove("url");
                } else {
                    itemObj.put("url", validated);
                }
            }
        }
    }

}
