package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
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
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnBean({
        DomainKnowledgeChangeSetRepository.class,
        DomainKnowledgeConceptRepository.class,
        DomainKnowledgeEvidenceRepository.class
})
public class DomainKnowledgeChangeSetService {

    private static final String VALIDATION_STATUS_VALID = "valid";
    private static final String VALIDATION_STATUS_INVALID = "invalid";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_APPLIED = "applied";
    private static final List<String> REVIEW_STATUSES = List.of(
            "draft", "proposed", "approved", "rejected", "superseded");

    private final DomainKnowledgeChangeSetRepository repository;
    private final DomainKnowledgeConceptRepository conceptRepository;
    private final DomainKnowledgeEvidenceRepository evidenceRepository;
    private final DomainKnowledgeChangeSetValidator validator;
    private final ObjectMapper objectMapper;
    private final ProjectKnowledgeDerivedIndexService projectKnowledgeDerivedIndexService;

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
        String patchHash = sha256(patch);
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
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Domain knowledge change set not found: " + id));
        if (!sameScope(normalizedTenant, changeSet.getTenantId())
                || !sameScope(normalizedEnvironment, changeSet.getEnvironment())) {
            throw new ConfigurationIngestionException("Domain knowledge change set not found in request scope: " + id);
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
        changeSet.setValidationResult(writeValidationResult(validation, sha256(writePatch(patch))));
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
        changeSet.setValidationResult(writeValidationResult(validation, patchHash));
        return toResponse(repository.save(changeSet));
    }

    private void applyOperation(
            DomainKnowledgeChangeSet changeSet,
            DomainKnowledgeChangeSetOperationRequest operation) {
        String operationType = normalize(operation.operationType());
        if ("add_evidence".equals(operationType)) {
            applyEvidenceOperation(changeSet, operation);
            return;
        }
        if ("revert_evidence".equals(operationType)) {
            applyRevertEvidenceOperation(changeSet, operation);
            return;
        }
        throw new ConfigurationIngestionException(
                "Only add_evidence and revert_evidence operations can be applied in this cut: " + operationType);
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
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Domain knowledge change set not found: " + id));
        if (!sameScope(normalizedTenant, changeSet.getTenantId())
                || !sameScope(normalizedEnvironment, changeSet.getEnvironment())) {
            throw new ConfigurationIngestionException("Domain knowledge change set not found in request scope: " + id);
        }
        return changeSet;
    }

    private DomainKnowledgeChangeSetResponse reuseExistingOrReject(
            DomainKnowledgeChangeSet existing,
            DomainKnowledgeChangeSetCreateRequest request,
            String patchHash,
            String authorType,
            String authorId) {
        JsonNode validationResult = read(existing.getValidationResult());
        boolean samePatch = patchHash.equals(validationResult.path("patchHash").asText(null));
        boolean sameAuthorType = normalize(existing.getAuthorType()).equals(authorType);
        boolean sameAuthorId = normalize(existing.getAuthorId()).equals(authorId);
        if (samePatch && sameAuthorType && sameAuthorId) {
            return toResponse(existing);
        }
        throw new ConfigurationIngestionException(
                "Domain knowledge change set key already exists with different semantics: "
                        + request.changeSetKey());
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
            String patchHash) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("validationStatus", validation.valid() ? VALIDATION_STATUS_VALID : VALIDATION_STATUS_INVALID);
        root.put("patchHash", patchHash);
        root.put("errorCount", validation.errorCount());
        root.put("warningCount", validation.warningCount());
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest unavailable", ex);
        }
    }
}
