package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainKnowledgeAlias;
import org.praxisplatform.config.domain.DomainKnowledgeBinding;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.domain.DomainKnowledgeRelationship;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationSummary;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetResponse;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetStatusRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetTimelineEventResponse;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetTimelineResponse;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationIssue;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainKnowledgeAliasRepository;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.repository.DomainKnowledgeRelationshipRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnBean({
        DomainKnowledgeChangeSetRepository.class,
        DomainKnowledgeConceptRepository.class,
        DomainKnowledgeAliasRepository.class,
        DomainKnowledgeBindingRepository.class,
        DomainKnowledgeRelationshipRepository.class,
        DomainKnowledgeEvidenceRepository.class
})
@ConditionalOnProperty(prefix = "praxis.domain-knowledge.change-sets", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DomainKnowledgeChangeSetService {

    private static final String VALIDATION_STATUS_VALID = "valid";
    private static final String VALIDATION_STATUS_INVALID = "invalid";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_APPLIED = "applied";
    private static final List<String> REVIEW_STATUSES = List.of(
            "draft", "proposed", "approved", "rejected", "superseded");

    private final DomainKnowledgeChangeSetRepository repository;
    private final DomainKnowledgeConceptRepository conceptRepository;
    private final DomainKnowledgeAliasRepository aliasRepository;
    private final DomainKnowledgeBindingRepository bindingRepository;
    private final DomainKnowledgeRelationshipRepository relationshipRepository;
    private final DomainKnowledgeEvidenceRepository evidenceRepository;
    private final DomainKnowledgeChangeSetValidator validator;
    private final ObjectMapper objectMapper;
    private final CanonicalJsonHashService canonicalJsonHashService;
    private final ProjectKnowledgeDerivedIndexService projectKnowledgeDerivedIndexService;

    @Autowired
    public DomainKnowledgeChangeSetService(
            DomainKnowledgeChangeSetRepository repository,
            DomainKnowledgeConceptRepository conceptRepository,
            DomainKnowledgeAliasRepository aliasRepository,
            DomainKnowledgeBindingRepository bindingRepository,
            DomainKnowledgeRelationshipRepository relationshipRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository,
            DomainKnowledgeChangeSetValidator validator,
            ObjectMapper objectMapper,
            CanonicalJsonHashService canonicalJsonHashService,
            ObjectProvider<ProjectKnowledgeDerivedIndexService> projectKnowledgeDerivedIndexService) {
        this(
                repository,
                conceptRepository,
                aliasRepository,
                bindingRepository,
                relationshipRepository,
                evidenceRepository,
                validator,
                objectMapper,
                canonicalJsonHashService,
                projectKnowledgeDerivedIndexService.getIfAvailable(NoopProjectKnowledgeDerivedIndexService::new));
    }

    public DomainKnowledgeChangeSetService(
            DomainKnowledgeChangeSetRepository repository,
            DomainKnowledgeConceptRepository conceptRepository,
            DomainKnowledgeAliasRepository aliasRepository,
            DomainKnowledgeBindingRepository bindingRepository,
            DomainKnowledgeRelationshipRepository relationshipRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository,
            DomainKnowledgeChangeSetValidator validator,
            ObjectMapper objectMapper,
            ObjectProvider<ProjectKnowledgeDerivedIndexService> projectKnowledgeDerivedIndexService) {
        this(
                repository,
                conceptRepository,
                aliasRepository,
                bindingRepository,
                relationshipRepository,
                evidenceRepository,
                validator,
                objectMapper,
                new CanonicalJsonHashService(objectMapper),
                projectKnowledgeDerivedIndexService.getIfAvailable(NoopProjectKnowledgeDerivedIndexService::new));
    }

    DomainKnowledgeChangeSetService(
            DomainKnowledgeChangeSetRepository repository,
            DomainKnowledgeConceptRepository conceptRepository,
            DomainKnowledgeAliasRepository aliasRepository,
            DomainKnowledgeBindingRepository bindingRepository,
            DomainKnowledgeRelationshipRepository relationshipRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository,
            DomainKnowledgeChangeSetValidator validator,
            ObjectMapper objectMapper,
            ProjectKnowledgeDerivedIndexService projectKnowledgeDerivedIndexService) {
        this(
                repository,
                conceptRepository,
                aliasRepository,
                bindingRepository,
                relationshipRepository,
                evidenceRepository,
                validator,
                objectMapper,
                new CanonicalJsonHashService(objectMapper),
                projectKnowledgeDerivedIndexService);
    }

    private DomainKnowledgeChangeSetService(
            DomainKnowledgeChangeSetRepository repository,
            DomainKnowledgeConceptRepository conceptRepository,
            DomainKnowledgeAliasRepository aliasRepository,
            DomainKnowledgeBindingRepository bindingRepository,
            DomainKnowledgeRelationshipRepository relationshipRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository,
            DomainKnowledgeChangeSetValidator validator,
            ObjectMapper objectMapper,
            CanonicalJsonHashService canonicalJsonHashService,
            ProjectKnowledgeDerivedIndexService projectKnowledgeDerivedIndexService) {
        this.repository = repository;
        this.conceptRepository = conceptRepository;
        this.aliasRepository = aliasRepository;
        this.bindingRepository = bindingRepository;
        this.relationshipRepository = relationshipRepository;
        this.evidenceRepository = evidenceRepository;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.canonicalJsonHashService = canonicalJsonHashService;
        this.projectKnowledgeDerivedIndexService = projectKnowledgeDerivedIndexService == null
                ? new NoopProjectKnowledgeDerivedIndexService()
                : projectKnowledgeDerivedIndexService;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainKnowledgeChangeSetResponse create(
            DomainKnowledgeChangeSetCreateRequest request,
            String tenantId,
            String environment) {
        DomainKnowledgeChangeSetValidationResponse validation =
                validator.validateCreateRequest(tenantId, environment, request);
        if (!validation.valid()) {
            throw new ConfigurationIngestionException("Domain knowledge change set is invalid: "
                    + validation.issues().stream()
                    .map(DomainKnowledgeChangeSetValidationIssue::code)
                    .distinct()
                    .toList());
        }

        String normalizedTenant = normalize(tenantId);
        String normalizedEnvironment = normalize(environment);
        String changeSetKey = requireText(request.changeSetKey(), "changeSetKey");
        String patch = writePatch(request.patch());
        String patchHash = canonicalPatchHash(patch);
        String authorType = normalizeOrDefault(request.authorType(), "llm");
        String authorId = normalize(request.authorId());
        String status = normalizeOrDefault(request.status(), DomainKnowledgeChangeSetValidator.STATUS_PROPOSED);

        return repository.findByTenantIdAndEnvironmentAndChangeSetKey(
                        normalizedTenant,
                        normalizedEnvironment,
                        changeSetKey)
                .map(existing -> reuseExistingOrReject(existing, request, patchHash, authorType, authorId))
                .orElseGet(() -> persistNew(
                        request,
                        normalizedTenant,
                        normalizedEnvironment,
                        changeSetKey,
                        patch,
                        patchHash,
                        status,
                        authorType,
                        authorId,
                        validation));
    }

    @Transactional(readOnly = true, transactionManager = ConfigTransactionManagerNames.CONFIG)
    public List<DomainKnowledgeChangeSetResponse> list(
            String tenantId,
            String environment,
            String status) {
        String normalizedTenant = normalize(tenantId);
        String normalizedEnvironment = normalize(environment);
        List<DomainKnowledgeChangeSet> changeSets = StringUtils.hasText(status)
                ? repository.findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
                        normalizedTenant,
                        normalizedEnvironment,
                        normalize(status))
                : repository.findByTenantIdAndEnvironmentOrderByCreatedAtDesc(
                        normalizedTenant,
                        normalizedEnvironment);
        return changeSets.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true, transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainKnowledgeChangeSetResponse get(
            UUID id,
            String tenantId,
            String environment) {
        if (id == null) {
            throw new ConfigurationIngestionException("Domain knowledge change set id is required");
        }
        String normalizedTenant = normalize(tenantId);
        String normalizedEnvironment = normalize(environment);
        DomainKnowledgeChangeSet changeSet = repository.findById(id)
                .orElseThrow(() -> changeSetNotFound(id));
        if (!sameScope(normalizedTenant, changeSet.getTenantId())
                || !sameScope(normalizedEnvironment, changeSet.getEnvironment())) {
            throw changeSetNotFound(id);
        }
        return toResponse(changeSet);
    }

    @Transactional(readOnly = true, transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainKnowledgeChangeSetTimelineResponse timeline(
            UUID id,
            String tenantId,
            String environment) {
        DomainKnowledgeChangeSet changeSet = findInScope(id, tenantId, environment);
        JsonNode validation = read(changeSet.getValidationResult());
        List<JsonNode> patchOperations = readPatch(changeSet.getPatch());
        List<DomainKnowledgeChangeSetOperationSummary> summaries =
                patchOperations.stream()
                        .map(this::toOperationSummary)
                        .toList();
        List<String> operationTypes = summaries.stream()
                .map(DomainKnowledgeChangeSetOperationSummary::operationType)
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        List<String> targetConceptKeys = summaries.stream()
                .flatMap(summary -> summary.targetConceptKeys().stream())
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        int operationCount = summaries.size();
        String validationStatus = validation.path("validationStatus").asText("unknown");

        ArrayList<DomainKnowledgeChangeSetTimelineEventResponse> events = new ArrayList<>();
        addTimelineEvent(
                events,
                "change_set.created",
                changeSet.getCreatedAt(),
                changeSet.getAuthorType(),
                changeSet.getAuthorId(),
                "Domain Knowledge change set created",
                changeSet.getStatus(),
                validationStatus,
                operationCount,
                operationTypes,
                targetConceptKeys);
        addTimelineEvent(
                events,
                "validation.completed",
                changeSet.getCreatedAt(),
                "system",
                "domain-knowledge-change-set-validator",
                validationSummary(validation),
                changeSet.getStatus(),
                validationStatus,
                operationCount,
                operationTypes,
                targetConceptKeys);
        if (changeSet.getReviewedAt() != null) {
            addTimelineEvent(
                    events,
                    reviewEventType(changeSet.getStatus()),
                    changeSet.getReviewedAt(),
                    "human",
                    changeSet.getReviewerId(),
                    "Domain Knowledge change set reviewed",
                    changeSet.getStatus(),
                    validationStatus,
                    operationCount,
                    operationTypes,
                    targetConceptKeys);
        }
        addTimelineEvent(
                events,
                "change_set.applied",
                changeSet.getAppliedAt(),
                "system",
                "domain-knowledge-patch-applier",
                "Domain Knowledge change set applied",
                changeSet.getStatus(),
                validationStatus,
                operationCount,
                operationTypes,
                targetConceptKeys);
        addEvidenceLifecycleTimelineEvents(
                events,
                changeSet,
                patchOperations,
                validationStatus,
                operationCount,
                operationTypes,
                targetConceptKeys);
        events.sort(Comparator
                .comparingInt((DomainKnowledgeChangeSetTimelineEventResponse event) -> timelineEventOrder(event.eventType()))
                .thenComparing(DomainKnowledgeChangeSetTimelineEventResponse::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DomainKnowledgeChangeSetTimelineEventResponse::eventType, Comparator.nullsLast(Comparator.naturalOrder())));

        return new DomainKnowledgeChangeSetTimelineResponse(
                changeSet.getId(),
                changeSet.getTenantId(),
                changeSet.getEnvironment(),
                changeSet.getChangeSetKey(),
                changeSet.getStatus(),
                changeSet.getAuthorType(),
                changeSet.getAuthorId(),
                changeSet.getReviewerId(),
                List.copyOf(events));
    }

    private String reviewEventType(String status) {
        return "rejected".equals(normalize(status)) ? "review.rejected" : "review.approved";
    }

    private int timelineEventOrder(String eventType) {
        return switch (normalize(eventType)) {
            case "change_set.created" -> 10;
            case "validation.completed" -> 20;
            case "review.approved", "review.rejected" -> 30;
            case "change_set.applied" -> 40;
            case "evidence.reverted" -> 50;
            case "evidence.superseded" -> 60;
            default -> 100;
        };
    }

    private void addEvidenceLifecycleTimelineEvents(
            List<DomainKnowledgeChangeSetTimelineEventResponse> events,
            DomainKnowledgeChangeSet changeSet,
            List<JsonNode> patchOperations,
            String validationStatus,
            int operationCount,
            List<String> operationTypes,
            List<String> targetConceptKeys) {
        if (changeSet.getAppliedAt() == null) {
            return;
        }
        for (JsonNode operation : patchOperations) {
            if (!"revert_evidence".equals(normalize(operation.path("operationType").asText(null)))) {
                continue;
            }
            if (StringUtils.hasText(operation.path("payload").path("replacementEvidenceKey").asText(null))) {
                addTimelineEvent(
                        events,
                        "evidence.superseded",
                        changeSet.getAppliedAt(),
                        "system",
                        "domain-knowledge-patch-applier",
                        "Domain Knowledge evidence superseded by governed replacement evidence",
                        changeSet.getStatus(),
                        validationStatus,
                        operationCount,
                        operationTypes,
                        targetConceptKeys);
            } else {
                addTimelineEvent(
                        events,
                        "evidence.reverted",
                        changeSet.getAppliedAt(),
                        "system",
                        "domain-knowledge-patch-applier",
                        "Domain Knowledge evidence reverted",
                        changeSet.getStatus(),
                        validationStatus,
                        operationCount,
                        operationTypes,
                        targetConceptKeys);
            }
        }
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainKnowledgeChangeSetValidationResponse validate(
            UUID id,
            String tenantId,
            String environment) {
        DomainKnowledgeChangeSet changeSet = findInScope(id, tenantId, environment);
        List<DomainKnowledgeChangeSetOperationRequest> patch = readPatchRequests(changeSet.getPatch());
        DomainKnowledgeChangeSetCreateRequest validationRequest = new DomainKnowledgeChangeSetCreateRequest(
                changeSet.getChangeSetKey(),
                changeSet.getStatus(),
                changeSet.getAuthorType(),
                changeSet.getAuthorId(),
                changeSet.getIntent(),
                changeSet.getReason(),
                patch);
        DomainKnowledgeChangeSetValidationResponse validation =
                validator.validateCreateRequest(changeSet.getTenantId(), changeSet.getEnvironment(), validationRequest);
        changeSet.setValidationResult(writeValidationResult(
                validation,
                canonicalPatchHash(changeSet.getPatch()),
                patch));
        repository.save(changeSet);
        return validation;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainKnowledgeChangeSetResponse transitionStatus(
            UUID id,
            DomainKnowledgeChangeSetStatusRequest request,
            String tenantId,
            String environment) {
        if (request == null) {
            throw new ConfigurationIngestionException("Domain knowledge change set status request is required");
        }
        String status = requireAllowedReviewStatus(request.status());
        DomainKnowledgeChangeSet changeSet = findInScope(id, tenantId, environment);
        requireAllowedStatusTransition(changeSet.getStatus(), status);
        if ("approved".equals(status)) {
            requireValidChangeSet(changeSet);
            requireExecutablePatch(changeSet);
            requireText(request.reviewerId(), "reviewerId");
        }
        if ("rejected".equals(status)) {
            requireText(request.reviewerId(), "reviewerId");
            requireText(request.reason(), "reason");
        }

        changeSet.setStatus(status);
        if (StringUtils.hasText(request.reviewerId())) {
            changeSet.setReviewerId(request.reviewerId().trim());
        }
        if (("approved".equals(status) || "rejected".equals(status)) && changeSet.getReviewedAt() == null) {
            changeSet.setReviewedAt(Instant.now());
        }
        return toResponse(repository.save(changeSet));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainKnowledgeChangeSetResponse apply(
            UUID id,
            String tenantId,
            String environment) {
        DomainKnowledgeChangeSet changeSet = findInScope(id, tenantId, environment);
        if (STATUS_APPLIED.equals(changeSet.getStatus())) {
            return toResponse(changeSet);
        }
        if (!STATUS_APPROVED.equals(changeSet.getStatus())) {
            throw new ConfigurationIngestionException(
                    "Domain knowledge change set must be approved before apply");
        }
        requireValidChangeSet(changeSet);
        List<DomainKnowledgeChangeSetOperationRequest> operations = readPatchRequests(changeSet.getPatch());
        requireExecutablePatch(operations);
        if (operations.isEmpty()) {
            throw new ConfigurationIngestionException("Domain knowledge change set patch is empty");
        }
        for (DomainKnowledgeChangeSetOperationRequest operation : operations) {
            applyOperation(changeSet, operation);
        }
        changeSet.setStatus(STATUS_APPLIED);
        if (changeSet.getAppliedAt() == null) {
            changeSet.setAppliedAt(Instant.now());
        }
        return toResponse(repository.save(changeSet));
    }

    private DomainKnowledgeChangeSetResponse persistNew(
            DomainKnowledgeChangeSetCreateRequest request,
            String tenantId,
            String environment,
            String changeSetKey,
            String patch,
            String patchHash,
            String status,
            String authorType,
            String authorId,
            DomainKnowledgeChangeSetValidationResponse validation) {
        DomainKnowledgeChangeSet changeSet = new DomainKnowledgeChangeSet();
        changeSet.setTenantId(tenantId);
        changeSet.setEnvironment(environment);
        changeSet.setChangeSetKey(changeSetKey);
        changeSet.setStatus(status);
        changeSet.setAuthorType(authorType);
        changeSet.setAuthorId(authorId);
        changeSet.setIntent(normalize(request.intent()));
        changeSet.setReason(request.reason().trim());
        changeSet.setPatch(patch);
        changeSet.setValidationResult(writeValidationResult(validation, patchHash, request.patch()));
        return toResponse(repository.save(changeSet));
    }

    private void applyOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        String operationType = normalize(operation.operationType());
        if ("create_concept".equals(operationType)) {
            applyCreateConceptOperation(changeSet, operation);
            return;
        }
        if ("approve_concept".equals(operationType)) {
            applyApproveConceptOperation(changeSet, operation);
            return;
        }
        if ("approve_binding".equals(operationType)) {
            applyApproveBindingOperation(changeSet, operation);
            return;
        }
        if ("add_alias".equals(operationType)) {
            applyAliasOperation(changeSet, operation);
            return;
        }
        if ("add_binding".equals(operationType)) {
            applyBindingOperation(changeSet, operation);
            return;
        }
        if ("add_relationship".equals(operationType)) {
            applyRelationshipOperation(changeSet, operation);
            return;
        }
        if ("add_evidence".equals(operationType)) {
            applyEvidenceOperation(changeSet, operation);
            return;
        }
        if ("revert_evidence".equals(operationType)) {
            applyRevertEvidenceOperation(changeSet, operation);
            return;
        }
        throw new ConfigurationIngestionException(
                "Domain knowledge operation has no canonical applier: " + operationType);
    }

    private void applyCreateConceptOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        String conceptKey = requireText(text(operation.target(), "conceptKey"), "target.conceptKey");
        if (conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(
                changeSet.getTenantId(), changeSet.getEnvironment(), conceptKey).isPresent()) {
            throw new ConfigurationIngestionException(
                    "Domain knowledge concept already exists in request scope: " + conceptKey);
        }
        JsonNode payload = operation.payload();
        DomainKnowledgeConcept concept = new DomainKnowledgeConcept();
        concept.setTenantId(changeSet.getTenantId());
        concept.setEnvironment(changeSet.getEnvironment());
        concept.setConceptKey(conceptKey);
        concept.setContextKey(requireText(text(payload, "contextKey"), "payload.contextKey"));
        concept.setResourceKey(text(payload, "resourceKey"));
        concept.setNodeType(requireText(text(payload, "nodeType"), "payload.nodeType"));
        concept.setLabel(requireText(text(payload, "label"), "payload.label"));
        concept.setDescription(requireText(text(payload, "description"), "payload.description"));
        concept.setLocale(text(payload, "locale"));
        concept.setSemanticOwner(requireText(text(payload, "semanticOwner"), "payload.semanticOwner"));
        concept.setSteward(text(payload, "steward"));
        concept.setLifecycle("active");
        concept.setCurationStatus("approved");
        concept.setAiVisibility(normalizeOrDefault(text(payload, "aiVisibility"), "allow"));
        concept.setDataCategory(text(payload, "dataCategory"));
        concept.setClassification(text(payload, "classification"));
        JsonNode complianceTags = payload == null ? null : payload.path("complianceTags");
        concept.setComplianceTags(complianceTags != null && complianceTags.isArray()
                ? write(complianceTags)
                : "[]");
        concept.setPayload(write(payload));
        DomainKnowledgeConcept savedConcept = conceptRepository.save(concept);
        persistClaimEvidence(
                changeSet, "concept", savedConcept.getId(), savedConcept, payload, operation.confidence());
    }

    private void applyApproveConceptOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        DomainKnowledgeConcept concept = requireConcept(changeSet, text(operation.target(), "conceptKey"));
        String lifecycle = normalize(concept.getLifecycle());
        String curationStatus = normalize(concept.getCurationStatus());
        if (Set.of("retired", "deprecated").contains(lifecycle)
                || "rejected".equals(curationStatus)) {
            throw new ConfigurationIngestionException(
                    "Domain knowledge concept cannot be approved from lifecycle=" + lifecycle
                            + " and curationStatus=" + curationStatus);
        }
        concept.setLifecycle("active");
        concept.setCurationStatus("approved");
        DomainKnowledgeConcept savedConcept = conceptRepository.save(concept);
        persistClaimEvidence(
                changeSet,
                "concept",
                savedConcept.getId(),
                savedConcept,
                operation.payload(),
                operation.confidence());
    }

    private void applyAliasOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        DomainKnowledgeConcept concept = requireConcept(changeSet, text(operation.target(), "conceptKey"));
        JsonNode payload = operation.payload();
        String aliasValue = requireText(text(payload, "alias"), "payload.alias");
        String normalizedAlias = normalizedAlias(aliasValue);
        DomainKnowledgeAlias existing = aliasRepository.findByConcept_Id(concept.getId()).stream()
                .filter(alias -> normalizedAlias.equals(alias.getNormalizedAlias()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (normalize(existing.getAliasType()).equals(normalize(text(payload, "aliasType")))) {
                persistClaimEvidence(
                        changeSet, "alias", existing.getId(), null, payload, operation.confidence());
                return;
            }
            throw new ConfigurationIngestionException(
                    "Domain knowledge alias already exists with different semantics: " + aliasValue);
        }
        DomainKnowledgeAlias alias = new DomainKnowledgeAlias();
        alias.setTenantId(changeSet.getTenantId());
        alias.setEnvironment(changeSet.getEnvironment());
        alias.setConcept(concept);
        alias.setAlias(aliasValue);
        alias.setNormalizedAlias(normalizedAlias);
        alias.setLocale(text(payload, "locale"));
        alias.setRegion(text(payload, "region"));
        alias.setBusinessUnit(text(payload, "businessUnit"));
        alias.setAliasType(requireText(text(payload, "aliasType"), "payload.aliasType"));
        alias.setWeight(operation.confidence());
        alias.setSource(aliasSource(payload));
        alias.setCurationStatus("approved");
        DomainKnowledgeAlias savedAlias = aliasRepository.save(alias);
        persistClaimEvidence(
                changeSet, "alias", savedAlias.getId(), null, payload, operation.confidence());
    }

    private void applyApproveBindingOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        DomainKnowledgeConcept concept = requireConcept(changeSet, text(operation.target(), "conceptKey"));
        if (!"active".equals(normalize(concept.getLifecycle()))
                || !"approved".equals(normalize(concept.getCurationStatus()))) {
            throw new ConfigurationIngestionException(
                    "Domain knowledge binding cannot be approved before its concept is active and approved");
        }
        JsonNode payload = operation.payload();
        String bindingType = requireText(text(payload, "bindingType"), "payload.bindingType");
        String bindingKey = requireText(text(payload, "bindingKey"), "payload.bindingKey");
        DomainKnowledgeBinding binding = bindingRepository
                .findByTenantIdAndEnvironmentAndBindingTypeAndBindingKey(
                        changeSet.getTenantId(), changeSet.getEnvironment(), bindingType, bindingKey)
                .stream()
                .filter(candidate -> candidate.getConcept() != null
                        && java.util.Objects.equals(candidate.getConcept().getId(), concept.getId()))
                .findFirst()
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Domain knowledge binding not found in request scope for concept "
                                + concept.getConceptKey() + ": " + bindingType + "/" + bindingKey));
        if ("rejected".equals(normalize(binding.getCurationStatus()))) {
            throw new ConfigurationIngestionException(
                    "Rejected domain knowledge binding cannot be approved: " + bindingType + "/" + bindingKey);
        }
        binding.setCurationStatus("approved");
        DomainKnowledgeBinding savedBinding = bindingRepository.save(binding);
        persistClaimEvidence(
                changeSet, "binding", savedBinding.getId(), null, payload, operation.confidence());
    }

    private void applyBindingOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        DomainKnowledgeConcept concept = requireConcept(changeSet, text(operation.target(), "conceptKey"));
        JsonNode payload = operation.payload();
        String bindingType = requireText(text(payload, "bindingType"), "payload.bindingType");
        String bindingKey = requireText(text(payload, "bindingKey"), "payload.bindingKey");
        DomainKnowledgeBinding existingBinding = bindingRepository
                .findByTenantIdAndEnvironmentAndBindingTypeAndBindingKey(
                        changeSet.getTenantId(), changeSet.getEnvironment(), bindingType, bindingKey)
                .stream()
                .filter(binding -> binding.getConcept() != null
                        && java.util.Objects.equals(binding.getConcept().getId(), concept.getId()))
                .findFirst()
                .orElse(null);
        if (existingBinding != null) {
            persistClaimEvidence(
                    changeSet, "binding", existingBinding.getId(), null, payload, operation.confidence());
            return;
        }
        DomainKnowledgeBinding binding = new DomainKnowledgeBinding();
        binding.setTenantId(changeSet.getTenantId());
        binding.setEnvironment(changeSet.getEnvironment());
        binding.setConcept(concept);
        binding.setBindingType(bindingType);
        binding.setBindingKey(bindingKey);
        binding.setResourceKey(text(payload, "resourceKey"));
        binding.setApiPath(text(payload, "apiPath"));
        binding.setApiMethod(text(payload, "apiMethod"));
        binding.setSchemaPointer(text(payload, "schemaPointer"));
        binding.setConfidence(operation.confidence());
        binding.setCurationStatus("approved");
        binding.setPayload(write(payload));
        DomainKnowledgeBinding savedBinding = bindingRepository.save(binding);
        persistClaimEvidence(
                changeSet, "binding", savedBinding.getId(), null, payload, operation.confidence());
    }

    private void applyRelationshipOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        DomainKnowledgeConcept source = requireConcept(
                changeSet, text(operation.target(), "sourceConceptKey"));
        DomainKnowledgeConcept target = requireConcept(
                changeSet, text(operation.target(), "targetConceptKey"));
        JsonNode payload = operation.payload();
        String relationshipType = requireText(
                text(payload, "relationshipType"), "payload.relationshipType");
        DomainKnowledgeRelationship existingRelationship = relationshipRepository
                .findByTenantIdAndEnvironmentAndSourceConcept_Id(
                        changeSet.getTenantId(), changeSet.getEnvironment(), source.getId())
                .stream()
                .filter(relationship -> relationship.getTargetConcept() != null
                        && java.util.Objects.equals(relationship.getTargetConcept().getId(), target.getId())
                        && normalize(relationship.getRelationshipType()).equals(normalize(relationshipType)))
                .findFirst()
                .orElse(null);
        if (existingRelationship != null) {
            persistClaimEvidence(
                    changeSet,
                    "relationship",
                    existingRelationship.getId(),
                    null,
                    payload,
                    operation.confidence());
            return;
        }
        DomainKnowledgeRelationship relationship = new DomainKnowledgeRelationship();
        relationship.setTenantId(changeSet.getTenantId());
        relationship.setEnvironment(changeSet.getEnvironment());
        relationship.setSourceConcept(source);
        relationship.setTargetConcept(target);
        relationship.setRelationshipType(relationshipType);
        relationship.setCrossContext(!java.util.Objects.equals(source.getContextKey(), target.getContextKey()));
        relationship.setSourceContextKey(source.getContextKey());
        relationship.setTargetContextKey(target.getContextKey());
        relationship.setContractKey(text(payload, "contractKey"));
        relationship.setConfidence(operation.confidence());
        relationship.setCurationStatus("approved");
        relationship.setPayload(write(payload));
        DomainKnowledgeRelationship savedRelationship = relationshipRepository.save(relationship);
        persistClaimEvidence(
                changeSet, "relationship", savedRelationship.getId(), null, payload, operation.confidence());
    }

    private void persistClaimEvidence(
            DomainKnowledgeChangeSet changeSet,
            String subjectType,
            UUID subjectId,
            DomainKnowledgeConcept concept,
            JsonNode claimPayload,
            Double confidence) {
        JsonNode provenance = claimPayload == null ? null : claimPayload.path("provenance");
        String claimId = requireText(text(provenance, "claimId"), "payload.provenance.claimId");
        List<DomainKnowledgeEvidence> existingEvidence =
                evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKey(
                        changeSet.getTenantId(), changeSet.getEnvironment(), claimId);
        if (!existingEvidence.isEmpty()) {
            DomainKnowledgeEvidence existing = existingEvidence.get(0);
            if (subjectType.equals(existing.getSubjectType())
                    && java.util.Objects.equals(subjectId, existing.getSubjectId())) {
                return;
            }
            throw new ConfigurationIngestionException(
                    "Claim id already exists for a different semantic subject: " + claimId);
        }
        DomainKnowledgeEvidence evidence = new DomainKnowledgeEvidence();
        evidence.setTenantId(changeSet.getTenantId());
        evidence.setEnvironment(changeSet.getEnvironment());
        evidence.setEvidenceKey(claimId);
        evidence.setSubjectType(subjectType);
        evidence.setSubjectId(subjectId);
        evidence.setEvidenceType(claimEvidenceType(text(provenance, "sourceClass")));
        evidence.setSourceUri(firstTextValue(provenance == null ? null : provenance.path("sourceRefs")));
        evidence.setSourcePointer(text(provenance, "derivationActivity"));
        evidence.setConfidence(confidence);
        evidence.setPayload(write(provenance));
        DomainKnowledgeEvidence savedEvidence = evidenceRepository.save(evidence);
        if (concept != null) {
            projectKnowledgeDerivedIndexService.evidenceActivated(concept, savedEvidence);
        }
    }

    private String claimEvidenceType(String sourceClass) {
        return switch (normalizeOrDefault(sourceClass, "inferred")) {
            case "authored" -> "manual_review";
            case "extracted" -> "import";
            default -> "llm_proposal";
        };
    }

    private String firstTextValue(JsonNode values) {
        if (values == null || !values.isArray()) {
            return null;
        }
        for (JsonNode value : values) {
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private DomainKnowledgeConcept requireConcept(
            DomainKnowledgeChangeSet changeSet,
            String conceptKey) {
        String requiredConceptKey = requireText(conceptKey, "target concept key");
        return conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(
                        changeSet.getTenantId(), changeSet.getEnvironment(), requiredConceptKey)
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Domain knowledge concept not found in request scope: " + requiredConceptKey));
    }

    private String aliasSource(JsonNode payload) {
        String sourceClass = text(payload == null ? null : payload.path("provenance"), "sourceClass");
        return switch (normalizeOrDefault(sourceClass, "inferred")) {
            case "authored" -> "manual";
            case "extracted" -> "generated";
            default -> "llm_proposed";
        };
    }

    private String normalizedAlias(String value) {
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void applyEvidenceOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        JsonNode target = operation.target();
        String conceptKey = target == null ? null : target.path("conceptKey").asText(null);
        String evidenceKey = operation.payload() == null
                ? null
                : operation.payload().path("evidenceKey").asText(null);
        String evidenceType = normalizeOrDefault(
                operation.payload() == null ? null : operation.payload().path("evidenceType").asText(null),
                "llm_proposal");
        DomainKnowledgeConcept concept = conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(
                        changeSet.getTenantId(),
                        changeSet.getEnvironment(),
                        requireText(conceptKey, "target.conceptKey"))
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Target concept not found for evidence operation: " + conceptKey));
        DomainKnowledgeEvidence evidence = reusableEvidenceOrNew(
                changeSet,
                concept,
                requireText(evidenceKey, "payload.evidenceKey"));
        evidence.setTenantId(changeSet.getTenantId());
        evidence.setEnvironment(changeSet.getEnvironment());
        evidence.setEvidenceKey(evidenceKey.trim());
        evidence.setSubjectType("concept");
        evidence.setSubjectId(concept.getId());
        evidence.setEvidenceType(evidenceType);
        evidence.setSourceUri(text(operation.payload(), "sourceUri"));
        evidence.setSourcePointer(text(operation.payload(), "sourcePointer"));
        evidence.setConfidence(operation.confidence());
        evidence.setPayload(write(operation.payload()));
        DomainKnowledgeEvidence savedEvidence = evidenceRepository.save(evidence);
        projectKnowledgeDerivedIndexService.evidenceActivated(concept, savedEvidence);
    }

    private void applyRevertEvidenceOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        JsonNode target = operation.target();
        JsonNode payload = operation.payload();
        String conceptKey = target == null ? null : target.path("conceptKey").asText(null);
        String evidenceKey = payload == null ? null : payload.path("evidenceKey").asText(null);
        String replacementEvidenceKey = payload == null ? null : payload.path("replacementEvidenceKey").asText(null);
        DomainKnowledgeConcept concept = conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(
                        changeSet.getTenantId(),
                        changeSet.getEnvironment(),
                        requireText(conceptKey, "target.conceptKey"))
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Target concept not found for evidence revert operation: " + conceptKey));
        DomainKnowledgeEvidence evidence = activeEvidenceForRevert(
                changeSet,
                concept,
                requireText(evidenceKey, "payload.evidenceKey"));
        boolean hasReplacementEvidence = StringUtils.hasText(replacementEvidenceKey);
        if (hasReplacementEvidence) {
            DomainKnowledgeEvidence replacement = activeReplacementEvidence(
                    changeSet,
                    concept,
                    replacementEvidenceKey.trim());
            evidence.setSupersededByEvidenceId(replacement.getId());
        }
        evidence.setStatus(hasReplacementEvidence ? "superseded" : "reverted");
        evidence.setRevertedByChangeSetId(changeSet.getId());
        evidence.setRevertedAt(Instant.now());
        evidence.setRevertReason(requireText(text(payload, "revertReason"), "payload.revertReason"));
        DomainKnowledgeEvidence savedEvidence = evidenceRepository.save(evidence);
        projectKnowledgeDerivedIndexService.evidenceDeactivated(concept, savedEvidence);
    }

    private DomainKnowledgeEvidence reusableEvidenceOrNew(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeConcept concept,
            String evidenceKey) {
        List<DomainKnowledgeEvidence> existingEvidence =
                evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKey(
                        changeSet.getTenantId(),
                        changeSet.getEnvironment(),
                        evidenceKey.trim());
        if (existingEvidence.isEmpty()) {
            return new DomainKnowledgeEvidence();
        }
        DomainKnowledgeEvidence evidence = existingEvidence.get(0);
        if (!"concept".equals(evidence.getSubjectType())
                || !java.util.Objects.equals(concept.getId(), evidence.getSubjectId())) {
            throw new ConfigurationIngestionException(
                    "Evidence key already exists for a different subject: " + evidenceKey);
        }
        return evidence;
    }

    private DomainKnowledgeEvidence activeEvidenceForRevert(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeConcept concept,
            String evidenceKey) {
        List<DomainKnowledgeEvidence> activeEvidence =
                evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKeyAndStatus(
                        changeSet.getTenantId(),
                        changeSet.getEnvironment(),
                        evidenceKey.trim(),
                        "active");
        if (!activeEvidence.isEmpty()) {
            DomainKnowledgeEvidence evidence = activeEvidence.get(0);
            requireEvidenceSubject(evidence, concept, evidenceKey);
            return evidence;
        }
        List<DomainKnowledgeEvidence> existingEvidence =
                evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKey(
                        changeSet.getTenantId(),
                        changeSet.getEnvironment(),
                        evidenceKey.trim());
        if (existingEvidence.isEmpty()) {
            throw new ConfigurationIngestionException(
                    "Active evidence not found for revert operation: " + evidenceKey);
        }
        DomainKnowledgeEvidence evidence = existingEvidence.get(0);
        requireEvidenceSubject(evidence, concept, evidenceKey);
        throw new ConfigurationIngestionException(
                "Evidence is not active and cannot be reverted: " + evidenceKey);
    }

    private DomainKnowledgeEvidence activeReplacementEvidence(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeConcept concept,
            String evidenceKey) {
        List<DomainKnowledgeEvidence> activeEvidence =
                evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKeyAndStatus(
                        changeSet.getTenantId(),
                        changeSet.getEnvironment(),
                        evidenceKey,
                        "active");
        if (activeEvidence.isEmpty()) {
            throw new ConfigurationIngestionException(
                    "Active replacement evidence not found for revert operation: " + evidenceKey);
        }
        DomainKnowledgeEvidence evidence = activeEvidence.get(0);
        requireEvidenceSubject(evidence, concept, evidenceKey);
        return evidence;
    }

    private void requireEvidenceSubject(
            DomainKnowledgeEvidence evidence,
            DomainKnowledgeConcept concept,
            String evidenceKey) {
        if (!"concept".equals(evidence.getSubjectType())
                || !java.util.Objects.equals(concept.getId(), evidence.getSubjectId())) {
            throw new ConfigurationIngestionException(
                    "Evidence does not belong to target concept: " + evidenceKey);
        }
    }

    private DomainKnowledgeChangeSet findInScope(
            UUID id,
            String tenantId,
            String environment) {
        if (id == null) {
            throw new ConfigurationIngestionException("Domain knowledge change set id is required");
        }
        String normalizedTenant = normalize(tenantId);
        String normalizedEnvironment = normalize(environment);
        DomainKnowledgeChangeSet changeSet = repository.findById(id)
                .orElseThrow(() -> changeSetNotFound(id));
        if (!sameScope(normalizedTenant, changeSet.getTenantId())
                || !sameScope(normalizedEnvironment, changeSet.getEnvironment())) {
            throw changeSetNotFound(id);
        }
        return changeSet;
    }

    private ResponseStatusException changeSetNotFound(UUID id) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Domain knowledge change set not found in request scope: " + id);
    }

    private DomainKnowledgeChangeSetResponse reuseExistingOrReject(
            DomainKnowledgeChangeSet existing,
            DomainKnowledgeChangeSetCreateRequest request,
            String patchHash,
            String authorType,
            String authorId) {
        // The stored JSONB patch is the authoritative semantic artifact. PostgreSQL may reorder
        // object properties and validation diagnostics are only a derived projection, so both
        // sides must be compared through the shared canonical JSON hash.
        boolean samePatch = patchHash.equals(canonicalPatchHash(existing.getPatch()));
        boolean sameAuthorType = normalize(existing.getAuthorType()).equals(authorType);
        boolean sameAuthorId = normalize(existing.getAuthorId()).equals(authorId);
        if (samePatch && sameAuthorType && sameAuthorId) {
            return toResponse(existing);
        }
        List<String> semanticMismatches = new ArrayList<>();
        if (!samePatch) {
            semanticMismatches.add("patch-hash");
        }
        if (!sameAuthorType) {
            semanticMismatches.add("author-type");
        }
        if (!sameAuthorId) {
            semanticMismatches.add("author-id");
        }
        throw new ConfigurationIngestionException(
                "Domain knowledge change set key already exists with different semantics: "
                        + request.changeSetKey()
                        + "; mismatches="
                        + semanticMismatches);
    }

    private DomainKnowledgeChangeSetResponse toResponse(DomainKnowledgeChangeSet changeSet) {
        JsonNode validation = read(changeSet.getValidationResult());
        List<DomainKnowledgeChangeSetOperationSummary> summaries =
                readPatch(changeSet.getPatch()).stream()
                        .map(this::toOperationSummary)
                        .toList();
        return new DomainKnowledgeChangeSetResponse(
                changeSet.getId(),
                changeSet.getTenantId(),
                changeSet.getEnvironment(),
                changeSet.getChangeSetKey(),
                changeSet.getStatus(),
                changeSet.getAuthorType(),
                changeSet.getAuthorId(),
                changeSet.getReviewerId(),
                changeSet.getIntent(),
                changeSet.getReason(),
                summaries.size(),
                validation.path("validationStatus").asText("unknown"),
                summaries,
                validation,
                changeSet.getCreatedAt(),
                changeSet.getReviewedAt(),
                changeSet.getAppliedAt());
    }

    private DomainKnowledgeChangeSetOperationSummary toOperationSummary(JsonNode operation) {
        return new DomainKnowledgeChangeSetOperationSummary(
                operation.path("operationId").asText(null),
                operation.path("operationType").asText(null),
                targetConceptKeys(operation.path("target")));
    }

    private List<String> targetConceptKeys(JsonNode target) {
        if (target == null || target.isMissingNode() || target.isNull()) {
            return List.of();
        }
        if (target.hasNonNull("conceptKey")) {
            return List.of(target.path("conceptKey").asText());
        }
        if (target.hasNonNull("sourceConceptKey") || target.hasNonNull("targetConceptKey")) {
            return java.util.stream.Stream.of(
                            target.path("sourceConceptKey").asText(null),
                            target.path("targetConceptKey").asText(null))
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
        }
        if (target.has("conceptKeys") && target.path("conceptKeys").isArray()) {
            return readableValues(target.path("conceptKeys"));
        }
        return List.of();
    }

    private List<String> readableValues(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .filter(StringUtils::hasText)
                .toList();
    }

    private void addTimelineEvent(
            List<DomainKnowledgeChangeSetTimelineEventResponse> events,
            String eventType,
            Instant occurredAt,
            String actorType,
            String actor,
            String summary,
            String status,
            String validationStatus,
            int operationCount,
            List<String> operationTypes,
            List<String> targetConceptKeys) {
        if (occurredAt == null) {
            return;
        }
        events.add(new DomainKnowledgeChangeSetTimelineEventResponse(
                eventType,
                occurredAt,
                normalize(actorType),
                normalize(actor),
                summary,
                normalize(status),
                normalize(validationStatus),
                operationCount,
                operationTypes,
                targetConceptKeys,
                "safe"));
    }

    private String validationSummary(JsonNode validation) {
        String status = validation.path("validationStatus").asText("unknown");
        int errorCount = validation.path("errorCount").asInt(0);
        int warningCount = validation.path("warningCount").asInt(0);
        return "Domain Knowledge change set validation "
                + status
                + " with "
                + errorCount
                + " errors and "
                + warningCount
                + " warnings";
    }

    private String writeValidationResult(
            DomainKnowledgeChangeSetValidationResponse validation,
            String patchHash,
            List<DomainKnowledgeChangeSetOperationRequest> patch) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("validationStatus", validation.valid() ? VALIDATION_STATUS_VALID : VALIDATION_STATUS_INVALID);
        root.put("patchHash", patchHash);
        root.put("errorCount", validation.errorCount());
        root.put("warningCount", validation.warningCount());
        writeOperationCapabilityDiagnostics(root, patch);
        ArrayNode issues = root.putArray("issues");
        validation.issues().forEach(issue -> {
            ObjectNode item = issues.addObject();
            item.put("severity", issue.severity());
            item.put("code", issue.code());
            item.put("pointer", issue.pointer());
            item.put("message", issue.message());
        });
        return write(root);
    }

    private void writeOperationCapabilityDiagnostics(
            ObjectNode root,
            List<DomainKnowledgeChangeSetOperationRequest> patch) {
        List<String> proposedPatchTypes = operationTypes(patch);
        writeArray(root.putArray("proposedOperationTypes"), proposedPatchTypes);
        writeArray(root.putArray("executableOperationTypes"),
                DomainKnowledgeChangeSetValidator.executableOperationTypes().stream().sorted().toList());
        writeArray(root.putArray("executablePatchOperationTypes"),
                proposedPatchTypes.stream()
                        .filter(DomainKnowledgeChangeSetValidator.executableOperationTypes()::contains)
                        .toList());
        writeArray(root.putArray("nonExecutableOperationTypes"),
                proposedPatchTypes.stream()
                        .filter(DomainKnowledgeChangeSetValidator.proposedOperationTypes()::contains)
                        .filter(type -> !DomainKnowledgeChangeSetValidator.executableOperationTypes().contains(type))
                        .toList());
    }

    private List<String> operationTypes(List<DomainKnowledgeChangeSetOperationRequest> patch) {
        if (patch == null) {
            return List.of();
        }
        return patch.stream()
                .map(operation -> operation == null ? null : normalize(operation.operationType()))
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
    }

    private void writeArray(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }

    private String writePatch(List<DomainKnowledgeChangeSetOperationRequest> patch) {
        return write(objectMapper.valueToTree(patch == null ? List.of() : patch));
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new ConfigurationIngestionException("Unable to serialize domain knowledge change set JSON", ex);
        }
    }

    private JsonNode read(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new ConfigurationIngestionException("Unable to read domain knowledge change set JSON", ex);
        }
    }

    private List<JsonNode> readPatch(String json) {
        JsonNode node = read(json);
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .toList();
    }

    private List<DomainKnowledgeChangeSetOperationRequest> readPatchRequests(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<List<DomainKnowledgeChangeSetOperationRequest>>() {
                    });
        } catch (JsonProcessingException ex) {
            throw new ConfigurationIngestionException("Unable to read domain knowledge change set patch", ex);
        }
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !node.hasNonNull(fieldName)) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ConfigurationIngestionException(fieldName + " is required");
        }
        return value.trim();
    }

    private String requireAllowedReviewStatus(String value) {
        String status = normalize(requireText(value, "status"));
        if (!REVIEW_STATUSES.contains(status)) {
            throw new ConfigurationIngestionException("status must be one of " + REVIEW_STATUSES
                    + "; use the apply endpoint for applied change sets");
        }
        return status;
    }

    private void requireAllowedStatusTransition(String currentStatus, String requestedStatus) {
        if (isSameStatus(currentStatus, requestedStatus) || isAllowedStatusTransition(currentStatus, requestedStatus)) {
            return;
        }
        throw new ConfigurationIngestionException(
                "Domain knowledge change set status transition is not allowed: "
                        + nullToEmpty(currentStatus)
                        + " -> "
                        + requestedStatus);
    }

    private boolean isAllowedStatusTransition(String currentStatus, String requestedStatus) {
        return switch (nullToEmpty(currentStatus)) {
            case "draft" -> List.of("proposed", "approved", "rejected", "superseded").contains(requestedStatus);
            case "proposed" -> List.of("draft", "approved", "rejected", "superseded").contains(requestedStatus);
            case "approved" -> List.of("rejected", "superseded").contains(requestedStatus);
            default -> false;
        };
    }

    private void requireValidChangeSet(DomainKnowledgeChangeSet changeSet) {
        JsonNode validation = read(changeSet.getValidationResult());
        if (!VALIDATION_STATUS_VALID.equals(validation.path("validationStatus").asText(null))
                || validation.path("errorCount").asInt(0) > 0) {
            throw new ConfigurationIngestionException("Domain knowledge change set must be valid before approval");
        }
    }

    private void requireExecutablePatch(DomainKnowledgeChangeSet changeSet) {
        requireExecutablePatch(readPatchRequests(changeSet.getPatch()));
    }

    private void requireExecutablePatch(List<DomainKnowledgeChangeSetOperationRequest> operations) {
        List<String> nonExecutableTypes = operationTypes(operations).stream()
                .filter(type -> !DomainKnowledgeChangeSetValidator.executableOperationTypes().contains(type))
                .toList();
        if (!nonExecutableTypes.isEmpty()) {
            throw new ConfigurationIngestionException(
                    "Domain knowledge change set contains operation types without canonical appliers: "
                            + nonExecutableTypes);
        }
    }

    private boolean isSameStatus(String currentStatus, String requestedStatus) {
        return nullToEmpty(currentStatus).equals(requestedStatus);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalize(value);
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean sameScope(String expected, String actual) {
        return java.util.Objects.equals(normalize(expected), normalize(actual));
    }

    private String canonicalPatchHash(String patch) {
        return canonicalJsonHashService.sha256(read(patch));
    }
}
