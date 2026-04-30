package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationSummary;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetResponse;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationIssue;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnBean(DomainKnowledgeChangeSetRepository.class)
public class DomainKnowledgeChangeSetService {

    private static final String VALIDATION_STATUS_VALID = "valid";
    private static final String VALIDATION_STATUS_INVALID = "invalid";

    private final DomainKnowledgeChangeSetRepository repository;
    private final DomainKnowledgeChangeSetValidator validator;
    private final ObjectMapper objectMapper;

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

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ConfigurationIngestionException(fieldName + " is required");
        }
        return value.trim();
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
