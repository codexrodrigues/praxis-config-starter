package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
    private static final Set<String> PROPOSED_OPERATION_TYPES = Set.of(
            "create_concept",
            "approve_concept",
            "update_concept_summary",
            "set_concept_visibility",
            "add_alias",
            "add_binding",
            "add_relationship",
            "add_evidence",
            "revert_evidence"
    );
    private static final Set<String> EXECUTABLE_OPERATION_TYPES = Set.of(
            "create_concept",
            "approve_concept",
            "add_alias",
            "add_binding",
            "add_relationship",
            "add_evidence",
            "revert_evidence"
    );
    private static final Set<String> AUTHORED_NODE_TYPES = Set.of(
            "context",
            "concept",
            "business_capability",
            "process",
            "business_event",
            "policy",
            "metric",
            "actor"
    );
    private static final Set<String> AI_VISIBILITIES = Set.of(
            "allow", "mask", "summarize_only", "deny");
    private static final Set<String> ALIAS_TYPES = Set.of(
            "preferred_term", "synonym", "abbreviation", "legacy_name",
            "business_slang", "technical_name", "misspelling");
    private static final Set<String> BINDING_TYPES = Set.of(
            "api_resource", "api_operation", "dto_class", "dto_schema", "dto_field",
            "entity_class", "entity_field", "service_method", "repository_projection",
            "workflow_action", "ui_surface", "ui_schema_field", "option_source",
            "form_config", "table_config", "rule_definition", "external_reference",
            "component_capability", "event_schema");
    private static final Set<String> RELATIONSHIP_TYPES = Set.of(
            "contains", "part_of", "related_to", "has_field", "has_state", "has_action",
            "has_surface", "has_event", "has_metric", "has_relationship", "allowed_in_state",
            "selectable_when", "blocked_when", "blocked_in_state", "uses_concept", "references",
            "depends_on", "computed_from", "triggers", "produces", "consumes", "applies_to",
            "measured_by", "implemented_by", "maps_to", "same_as", "equivalent_to", "broader",
            "narrower", "broader_than", "narrower_than", "impacts", "owned_by", "stewarded_by",
            "governed_by", "materializes");
    private static final Set<String> CLAIM_SOURCE_CLASSES = Set.of(
            "authored", "extracted", "inferred");
    private static final Set<String> DESTRUCTIVE_OPERATION_TYPES = Set.of(
            "delete_concept",
            "delete_alias",
            "delete_binding",
            "delete_relationship",
            "delete_evidence",
            "replace_concept",
            "replace_payload"
    );
    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "annotation",
            "openapi",
            "json_schema",
            "java_symbol",
            "catalog_release",
            "manual_review",
            "llm_proposal",
            "import"
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
        Set<String> claimIds = new HashSet<>();
        for (int i = 0; i < operations.size(); i++) {
            validateOperation(
                    tenantId,
                    environment,
                    request,
                    operations.get(i),
                    i,
                    operationIds,
                    claimIds,
                    issues);
        }

        return report(issues, operations);
    }

    private void validateOperation(
            String tenantId,
            String environment,
            DomainKnowledgeChangeSetCreateRequest request,
            DomainKnowledgeChangeSetOperationRequest operation,
            int index,
            Set<String> operationIds,
            Set<String> claimIds,
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
        } else if (!PROPOSED_OPERATION_TYPES.contains(operationType)) {
            error(issues, "unsupported_operation_type", pointer + "/operationType",
                    "operationType is not supported");
        } else if (!EXECUTABLE_OPERATION_TYPES.contains(operationType)) {
            error(issues, "non_executable_operation_type", pointer + "/operationType",
                    "operationType is proposed but has no canonical applier in this cut");
        }

        requireText(operation.reason(), pointer + "/reason", "missing_reason",
                "operation reason is required", issues);
        if ("llm".equals(normalize(request.authorType())) && isEmpty(operation.evidenceRefs())) {
            error(issues, "missing_evidence", pointer + "/evidenceRefs",
                    "LLM-authored operations require evidenceRefs");
        }
        validateConfidence(operation.confidence(), pointer + "/confidence", issues);
        validateTargetScope(tenantId, environment, operation.target(), pointer + "/target", issues);
        validatePayload(
                operationType,
                request.authorType(),
                operation.target(),
                operation.payload(),
                pointer + "/payload",
                claimIds,
                issues);
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
            String operationType,
            String authorType,
            JsonNode target,
            JsonNode payload,
            String pointer,
            Set<String> claimIds,
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
        if ("create_concept".equals(operationType)) {
            requireTargetConceptKey(target, pointer, operationType, issues);
            requireText(payload.path("contextKey").asText(null), pointer + "/contextKey",
                    "context_key_required", "create_concept operations require payload.contextKey", issues);
            requireText(payload.path("nodeType").asText(null), pointer + "/nodeType",
                    "node_type_required", "create_concept operations require payload.nodeType", issues);
            requireEnum(payload.path("nodeType").asText(null), AUTHORED_NODE_TYPES, pointer + "/nodeType",
                    "unsupported_authored_node_type", issues);
            requireText(payload.path("label").asText(null), pointer + "/label",
                    "concept_label_required", "create_concept operations require payload.label", issues);
            requireText(payload.path("description").asText(null), pointer + "/description",
                    "concept_description_required", "create_concept operations require payload.description", issues);
            requireText(payload.path("semanticOwner").asText(null), pointer + "/semanticOwner",
                    "semantic_owner_required", "create_concept operations require payload.semanticOwner", issues);
            requireEnum(payload.path("aiVisibility").asText(null), AI_VISIBILITIES,
                    pointer + "/aiVisibility", "unsupported_ai_visibility", issues);
            validateClaimProvenance(
                    authorType, payload.path("provenance"), pointer + "/provenance", claimIds, issues);
        } else if ("approve_concept".equals(operationType)) {
            requireTargetConceptKey(target, pointer, operationType, issues);
            validateClaimProvenance(
                    authorType, payload.path("provenance"), pointer + "/provenance", claimIds, issues);
        } else if ("add_alias".equals(operationType)) {
            requireTargetConceptKey(target, pointer, operationType, issues);
            requireText(payload.path("alias").asText(null), pointer + "/alias",
                    "alias_required", "add_alias operations require payload.alias", issues);
            requireText(payload.path("aliasType").asText(null), pointer + "/aliasType",
                    "alias_type_required", "add_alias operations require payload.aliasType", issues);
            requireEnum(payload.path("aliasType").asText(null), ALIAS_TYPES,
                    pointer + "/aliasType", "unsupported_alias_type", issues);
            validateClaimProvenance(
                    authorType, payload.path("provenance"), pointer + "/provenance", claimIds, issues);
        } else if ("add_binding".equals(operationType)) {
            requireTargetConceptKey(target, pointer, operationType, issues);
            requireText(payload.path("bindingType").asText(null), pointer + "/bindingType",
                    "binding_type_required", "add_binding operations require payload.bindingType", issues);
            requireEnum(payload.path("bindingType").asText(null), BINDING_TYPES,
                    pointer + "/bindingType", "unsupported_binding_type", issues);
            requireText(payload.path("bindingKey").asText(null), pointer + "/bindingKey",
                    "binding_key_required", "add_binding operations require payload.bindingKey", issues);
            validateClaimProvenance(
                    authorType, payload.path("provenance"), pointer + "/provenance", claimIds, issues);
        } else if ("add_relationship".equals(operationType)) {
            requireText(target == null ? null : target.path("sourceConceptKey").asText(null),
                    pointer.replace("/payload", "/target") + "/sourceConceptKey",
                    "source_concept_key_required",
                    "add_relationship operations require target.sourceConceptKey",
                    issues);
            requireText(target == null ? null : target.path("targetConceptKey").asText(null),
                    pointer.replace("/payload", "/target") + "/targetConceptKey",
                    "target_concept_key_required",
                    "add_relationship operations require target.targetConceptKey",
                    issues);
            requireText(payload.path("relationshipType").asText(null), pointer + "/relationshipType",
                    "relationship_type_required",
                    "add_relationship operations require payload.relationshipType",
                    issues);
            requireEnum(payload.path("relationshipType").asText(null), RELATIONSHIP_TYPES,
                    pointer + "/relationshipType", "unsupported_relationship_type", issues);
            validateClaimProvenance(
                    authorType, payload.path("provenance"), pointer + "/provenance", claimIds, issues);
        } else if ("add_evidence".equals(operationType)) {
            requireText(target == null ? null : target.path("conceptKey").asText(null),
                    pointer.replace("/payload", "/target") + "/conceptKey",
                    "target_concept_key_required",
                    "add_evidence operations require target.conceptKey",
                    issues);
            requireText(payload.path("evidenceKey").asText(null), pointer + "/evidenceKey",
                    "evidence_key_required", "add_evidence operations require payload.evidenceKey", issues);
            String evidenceType = normalize(payload.path("evidenceType").asText(null));
            if (StringUtils.hasText(evidenceType) && !EVIDENCE_TYPES.contains(evidenceType)) {
                error(issues, "unsupported_evidence_type", pointer + "/evidenceType",
                        "evidenceType is not supported");
            }
        } else if ("revert_evidence".equals(operationType)) {
            requireText(target == null ? null : target.path("conceptKey").asText(null),
                    pointer.replace("/payload", "/target") + "/conceptKey",
                    "target_concept_key_required",
                    "revert_evidence operations require target.conceptKey",
                    issues);
            requireText(payload.path("evidenceKey").asText(null), pointer + "/evidenceKey",
                    "evidence_key_required", "revert_evidence operations require payload.evidenceKey", issues);
            requireText(payload.path("revertReason").asText(null), pointer + "/revertReason",
                    "revert_reason_required", "revert_evidence operations require payload.revertReason", issues);
        }
    }

    private void requireTargetConceptKey(
            JsonNode target,
            String payloadPointer,
            String operationType,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        requireText(target == null ? null : target.path("conceptKey").asText(null),
                payloadPointer.replace("/payload", "/target") + "/conceptKey",
                "target_concept_key_required",
                operationType + " operations require target.conceptKey",
                issues);
    }

    private void validateClaimProvenance(
            String authorType,
            JsonNode provenance,
            String pointer,
            Set<String> claimIds,
            List<DomainKnowledgeChangeSetValidationIssue> issues) {
        if (provenance == null || !provenance.isObject()) {
            error(issues, "claim_provenance_required", pointer,
                    "semantic claim operations require payload.provenance");
            return;
        }
        String claimId = provenance.path("claimId").asText(null);
        requireText(claimId, pointer + "/claimId",
                "claim_id_required", "claim provenance requires claimId", issues);
        if (StringUtils.hasText(claimId) && !claimIds.add(claimId.trim())) {
            error(issues, "claim_id_duplicate", pointer + "/claimId",
                    "claimId must be unique within a change set");
        }
        String sourceClass = normalize(provenance.path("sourceClass").asText(null));
        requireText(sourceClass, pointer + "/sourceClass",
                "claim_source_class_required", "claim provenance requires sourceClass", issues);
        requireEnum(sourceClass, CLAIM_SOURCE_CLASSES, pointer + "/sourceClass",
                "unsupported_claim_source_class", issues);
        requireText(provenance.path("derivationActivity").asText(null), pointer + "/derivationActivity",
                "derivation_activity_required", "claim provenance requires derivationActivity", issues);
        requireText(provenance.path("agent").path("id").asText(null), pointer + "/agent/id",
                "claim_agent_required", "claim provenance requires agent.id", issues);
        if (!provenance.path("sourceRefs").isArray() || provenance.path("sourceRefs").isEmpty()) {
            error(issues, "claim_source_refs_required", pointer + "/sourceRefs",
                    "claim provenance requires at least one sourceRef");
        }
        if ("llm".equals(normalize(authorType)) && !"inferred".equals(sourceClass)) {
            error(issues, "llm_claim_must_be_inferred", pointer + "/sourceClass",
                    "LLM-authored semantic claims must use sourceClass=inferred");
        }
        if ("inferred".equals(sourceClass)) {
            requireText(provenance.path("model").asText(null), pointer + "/model",
                    "inferred_claim_model_required", "inferred claim provenance requires model", issues);
            requireText(provenance.path("templateHash").asText(null), pointer + "/templateHash",
                    "inferred_claim_template_hash_required",
                    "inferred claim provenance requires templateHash",
                    issues);
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
        return report(issues, List.of());
    }

    private DomainKnowledgeChangeSetValidationResponse report(
            List<DomainKnowledgeChangeSetValidationIssue> issues,
            List<DomainKnowledgeChangeSetOperationRequest> operations) {
        int errorCount = (int) issues.stream()
                .filter(issue -> "error".equals(issue.severity()))
                .count();
        int warningCount = (int) issues.stream()
                .filter(issue -> "warning".equals(issue.severity()))
                .count();
        List<String> proposedOperationTypes = operationTypes(operations);
        return new DomainKnowledgeChangeSetValidationResponse(
                errorCount == 0,
                errorCount,
                warningCount,
                List.copyOf(issues),
                proposedOperationTypes,
                EXECUTABLE_OPERATION_TYPES.stream().sorted().toList(),
                proposedOperationTypes.stream()
                        .filter(EXECUTABLE_OPERATION_TYPES::contains)
                        .toList(),
                proposedOperationTypes.stream()
                        .filter(PROPOSED_OPERATION_TYPES::contains)
                        .filter(type -> !EXECUTABLE_OPERATION_TYPES.contains(type))
                        .toList());
    }

    private List<String> operationTypes(List<DomainKnowledgeChangeSetOperationRequest> operations) {
        if (operations == null) {
            return List.of();
        }
        return operations.stream()
                .map(operation -> operation == null ? null : normalize(operation.operationType()))
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
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

    public static Set<String> proposedOperationTypes() {
        return PROPOSED_OPERATION_TYPES;
    }

    public static Set<String> executableOperationTypes() {
        return EXECUTABLE_OPERATION_TYPES;
    }

    static Set<String> relationshipTypes() {
        return RELATIONSHIP_TYPES;
    }
}
