package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationIssue;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DomainKnowledgeChangeSetValidator {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PROPOSED = "proposed";

    private static final Set<String> AUTHOR_TYPES = Set.of("human", "llm", "system");
    private static final Set<String> INITIAL_STATUSES = Set.of(STATUS_DRAFT, STATUS_PROPOSED);
    private static final Set<String> OPERATION_TYPES = Set.of(
            "create_concept",
            "update_concept_summary",
            "set_concept_visibility",
            "add_alias",
            "add_binding",
            "add_relationship",
            "add_evidence"
    );
    private static final Set<String> DESTRUCTIVE_OPERATION_TYPES = Set.of(
            "delete_concept",
            "delete_alias",
            "delete_binding",
            "delete_relationship",
            "delete_evidence",
            "replace_concept",
            "replace_payload"
    );
    private static final Set<String> RAW_MEMORY_FIELD_NAMES = Set.of(
            "prompt",
            "rawprompt",
            "chatprompt",
            "messages",
            "conversation",
            "transcript",
            "chattranscript",
            "rawconversation",
            "rawchat"
    );

    public DomainKnowledgeChangeSetValidationResponse validateCreateRequest(
            String tenantId,
            String environment,
            DomainKnowledgeChangeSetCreateRequest request) {
        List<DomainKnowledgeChangeSetValidationIssue> issues = new ArrayList<>();
        if (request == null) {
            error(issues, "request_required", "/", "change set create request is required");
            return report(issues);
        }

        requireText(request.changeSetKey(), "/changeSetKey", "change_set_key_required",
                "changeSetKey is required", issues);
        requireText(request.authorType(), "/authorType", "author_type_required",
                "authorType is required", issues);
        requireEnum(request.authorType(), AUTHOR_TYPES, "/authorType", "unsupported_author_type", issues);
        requireText(request.reason(), "/reason", "missing_reason", "reason is required", issues);

        String status = normalize(request.status());
        if (!StringUtils.hasText(status)) {
            status = STATUS_PROPOSED;
        }
        if (!INITIAL_STATUSES.contains(status)) {
            error(issues, "invalid_initial_status", "/status",
                    "initial status must be draft or proposed");
        }
        if ("llm".equals(normalize(request.authorType())) && !STATUS_PROPOSED.equals(status)) {
            error(issues, "invalid_initial_status", "/status",
                    "LLM-authored change sets must start as proposed");
        }

        List<DomainKnowledgeChangeSetOperationRequest> operations =
                request.patch() == null ? List.of() : request.patch();
        if (operations.isEmpty()) {
            error(issues, "patch_required", "/patch", "at least one patch operation is required");
        }

        Set<String> operationIds = new HashSet<>();
        for (int i = 0; i < operations.size(); i++) {
            validateOperation(tenantId, environment, request, operations.get(i), i, operationIds, issues);
        }

        return report(issues);
    }

    private void validateOperation(
            String tenantId,
            String environment,
            DomainKnowledgeChangeSetCreateRequest request,
            DomainKnowledgeChangeSetOperationRequest operation,
            int index,
            Set<String> operationIds,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        String pointer = "/patch/" + index;
        if (operation == null) {
            error(issues, "operation_required", pointer, "patch operation is required");
            return;
        }

        String operationId = trim(operation.operationId());
        if (!StringUtils.hasText(operationId)) {
            error(issues, "operation_id_required", pointer + "/operationId", "operationId is required");
        } else if (!operationIds.add(operationId)) {
            error(issues, "operation_id_duplicate", pointer + "/operationId",
                    "operationId must be unique");
        }

        String operationType = normalize(operation.operationType());
        if (!StringUtils.hasText(operationType)) {
            error(issues, "operation_type_required", pointer + "/operationType", "operationType is required");
        } else if (DESTRUCTIVE_OPERATION_TYPES.contains(operationType)) {
            error(issues, "destructive_operation_not_supported", pointer + "/operationType",
                    "destructive operations are not supported in this cut");
        } else if (!OPERATION_TYPES.contains(operationType)) {
            error(issues, "unsupported_operation_type", pointer + "/operationType",
                    "operationType is not supported");
        }

        requireText(operation.reason(), pointer + "/reason", "missing_reason",
                "operation reason is required", issues);
        if ("llm".equals(normalize(request.authorType())) && isEmpty(operation.evidenceRefs())) {
            error(issues, "missing_evidence", pointer + "/evidenceRefs",
                    "LLM-authored operations require evidenceRefs");
        }
        validateConfidence(operation.confidence(), pointer + "/confidence", issues);
        validateTargetScope(tenantId, environment, operation.target(), pointer + "/target", issues);
        validatePayload(operation.payload(), pointer + "/payload", issues);
    }

    private void validateConfidence(
            Double confidence,
            String pointer,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        if (confidence == null) {
            error(issues, "confidence_required", pointer, "confidence is required");
            return;
        }
        if (confidence < 0.0 || confidence > 1.0) {
            error(issues, "confidence_out_of_range", pointer, "confidence must be between 0 and 1");
        }
    }

    private void validateTargetScope(
            String tenantId,
            String environment,
            JsonNode target,
            String pointer,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        if (target == null || target.isNull() || !target.isObject()) {
            error(issues, "target_required", pointer, "target object is required");
            return;
        }
        requireScopeMatch(tenantId, target.path("tenantId").asText(null),
                pointer + "/tenantId", "scope_mismatch", issues);
        requireScopeMatch(environment, target.path("environment").asText(null),
                pointer + "/environment", "scope_mismatch", issues);
    }

    private void validatePayload(
            JsonNode payload,
            String pointer,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        if (payload == null || payload.isNull() || !payload.isObject()) {
            error(issues, "payload_required", pointer, "payload object is required");
            return;
        }
        if (containsRawMemoryField(payload)) {
            error(issues, "raw_prompt_memory_not_allowed", pointer,
                    "raw prompt, chat or transcript content cannot be stored as canonical knowledge");
        }
        String aiVisibility = normalize(payload.path("aiVisibility").asText(null));
        String evidenceSafety = normalize(payload.path("evidenceSafety").asText(null));
        if ("allow".equals(aiVisibility) && Set.of("unsafe", "private", "restricted", "deny").contains(evidenceSafety)) {
            error(issues, "unsafe_evidence_ai_visibility", pointer + "/aiVisibility",
                    "unsafe evidence cannot be proposed as ai_visibility=allow");
        }
    }

    private boolean containsRawMemoryField(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = normalizeKey(fieldNames.next());
                if (RAW_MEMORY_FIELD_NAMES.contains(fieldName)) {
                    return true;
                }
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                if (containsRawMemoryField(values.next())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                if (containsRawMemoryField(values.next())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void requireScopeMatch(
            String expected,
            String actual,
            String pointer,
            String code,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        if (!StringUtils.hasText(actual)) {
            return;
        }
        if (!normalize(expected).equals(normalize(actual))) {
            error(issues, code, pointer, "target scope must match request scope");
        }
    }

    private void requireEnum(
            String value,
            Set<String> allowed,
            String pointer,
            String code,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!allowed.contains(normalize(value))) {
            error(issues, code, pointer, "unsupported value: " + value);
        }
    }

    private void requireText(
            String value,
            String pointer,
            String code,
            String message,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        if (!StringUtils.hasText(value)) {
            error(issues, code, pointer, message);
        }
    }

    private DomainKnowledgeChangeSetValidationResponse report(
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        int errorCount = (int) issues.stream()
                .filter(issue -> "error".equals(issue.severity()))
                .count();
        int warningCount = (int) issues.stream()
                .filter(issue -> "warning".equals(issue.severity()))
                .count();
        return new DomainKnowledgeChangeSetValidationResponse(
                errorCount == 0,
                errorCount,
                warningCount,
                List.copyOf(issues));
    }

    private void error(
            List<DomainKnowledgeChangeSetValidationIssue> issues,
            String code,
            String pointer,
            String message) {
        issues.add(new DomainKnowledgeChangeSetValidationIssue("error", code, pointer, message));
    }

    private boolean isEmpty(List<String> values) {
        return values == null || values.stream().noneMatch(StringUtils::hasText);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return normalize(value).replace("_", "").replace("-", "");
    }
}
