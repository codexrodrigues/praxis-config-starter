package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationIssue;

@Tag("unit")
class DomainKnowledgeChangeSetValidatorTest {

    private static final String TENANT = "tenant-a";
    private static final String ENVIRONMENT = "dev";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainKnowledgeChangeSetValidator validator = new DomainKnowledgeChangeSetValidator();

    @Test
    void acceptsSafeLlmProposedEvidenceOperation() {
        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, validRequest());

        assertThat(report.valid()).isTrue();
        assertThat(report.errorCount()).isZero();
        assertThat(report.warningCount()).isZero();
        assertThat(report.issues()).isEmpty();
        assertThat(report.proposedOperationTypes()).containsExactly("add_evidence");
        assertThat(report.executableOperationTypes()).containsExactly(
                "add_alias", "add_binding", "add_evidence", "add_relationship", "approve_binding", "approve_concept", "create_concept", "revert_evidence");
        assertThat(report.executablePatchOperationTypes()).containsExactly("add_evidence");
        assertThat(report.nonExecutableOperationTypes()).isEmpty();
    }

    @Test
    void rejectsUnsupportedAndDestructiveOperations() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:unsafe:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Unsafe operations",
                "Exercise validator rejection paths.",
                List.of(
                        operation("op-unsupported", "merge_concept", target(), payload()),
                        operation("op-delete", "delete_concept", target(), payload())));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains(
                        "unsupported_operation_type",
                        "destructive_operation_not_supported");
    }

    @Test
    void rejectsRemainingProposedOperationWithoutCanonicalApplier() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:update-summary:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Update concept summary",
                "Summary updates must wait for an executable applier.",
                List.of(operation("op-update-summary", "update_concept_summary", target(), payload())));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains("non_executable_operation_type");
        assertThat(report.proposedOperationTypes()).containsExactly("update_concept_summary");
        assertThat(report.executablePatchOperationTypes()).isEmpty();
        assertThat(report.nonExecutableOperationTypes()).containsExactly("update_concept_summary");
    }

    @Test
    void acceptsInferredConceptWithClaimLevelProvenance() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:hr:workforce-management:v1",
                "proposed",
                "llm",
                "openai:gpt-5.6-mini",
                "Propose workforce management capability",
                "The reviewed HR pilot needs a semantic capability above API resources.",
                List.of(operation(
                        "op-create-workforce-capability",
                        "create_concept",
                        conceptTarget("human-resources.capability.workforce-management"),
                        semanticConceptPayload())));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isTrue();
        assertThat(report.executablePatchOperationTypes()).containsExactly("create_concept");
        assertThat(report.nonExecutableOperationTypes()).isEmpty();
    }

    @Test
    void acceptsGovernedApprovalOfProjectedConcept() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:hr:funcionarios:approve:v1",
                "proposed",
                "system",
                "praxis-reference-pilot",
                "Approve projected employee concept",
                "A reviewed catalog concept must become eligible for governed operational bindings.",
                List.of(operation(
                        "op-approve-funcionarios",
                        "approve_concept",
                        conceptTarget("human-resources.funcionarios"),
                        semanticConceptPayload())));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isTrue();
        assertThat(report.executablePatchOperationTypes()).containsExactly("approve_concept");
        assertThat(report.nonExecutableOperationTypes()).isEmpty();
    }

    @Test
    void acceptsGovernedApprovalOfProjectedBinding() {
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) semanticConceptPayload();
        payload.put("bindingType", "stats_endpoint");
        payload.put(
                "bindingKey",
                "binding:human-resources.ferias-afastamentos.stats.group-by:openapi-stats");
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:hr:ferias-afastamentos:binding-approve:v1",
                "proposed",
                "system",
                "praxis-reference-pilot",
                "Approve projected vacation resource binding",
                "A reviewed catalog binding must become eligible for operational grounding.",
                List.of(operation(
                        "op-approve-ferias-afastamentos-binding",
                        "approve_binding",
                        conceptTarget("human-resources.ferias-afastamentos"),
                        payload)));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isTrue();
        assertThat(report.executablePatchOperationTypes()).containsExactly("approve_binding");
        assertThat(report.nonExecutableOperationTypes()).isEmpty();
    }

    @Test
    void rejectsLlmSemanticClaimWithoutInferenceProvenance() {
        JsonNode payload = semanticConceptPayload();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("provenance"))
                .put("sourceClass", "authored")
                .remove("model");
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:hr:invalid-provenance:v1",
                "proposed",
                "llm",
                "openai:gpt-5.6-mini",
                "Invalid authored claim",
                "An LLM cannot self-declare an authored claim.",
                List.of(operation(
                        "op-invalid-provenance",
                        "create_concept",
                        conceptTarget("human-resources.capability.invalid"),
                        payload)));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains("llm_claim_must_be_inferred");
    }

    @Test
    void rejectsDuplicateClaimIdentityBeforeApply() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:hr:duplicate-claim:v1",
                "proposed",
                "llm",
                "openai:gpt-5.6-mini",
                "Duplicate claim",
                "A claim identity cannot authorize two semantic subjects.",
                List.of(
                        operation(
                                "op-first-claim",
                                "create_concept",
                                conceptTarget("human-resources.capability.first"),
                                semanticConceptPayload()),
                        operation(
                                "op-second-claim",
                                "create_concept",
                                conceptTarget("human-resources.capability.second"),
                                semanticConceptPayload())));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains("claim_id_duplicate");
    }

    @Test
    void rejectsLlmChangeSetWithoutEvidenceOrProposedInitialStatus() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:status:v1",
                "applied",
                "llm",
                "openai:gpt-5.4",
                "Invalid status",
                "LLM changes must be proposed and evidenced.",
                List.of(new DomainKnowledgeChangeSetOperationRequest(
                        "op-no-evidence",
                        "add_evidence",
                        target(),
                        "Missing evidence should fail.",
                        List.of(),
                        0.7,
                        payload())));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains(
                        "invalid_initial_status",
                        "missing_evidence");
    }

    @Test
    void rejectsScopeMismatchAndRawPromptMemoryPayload() {
        JsonNode target = objectMapper.createObjectNode()
                .put("tenantId", "tenant-b")
                .put("environment", "prod")
                .put("conceptKey", "human-resources.funcionarios.field.cpf");
        JsonNode payload = objectMapper.createObjectNode()
                .put("evidenceKey", "llm-proposal:cpf:v1")
                .put("chatTranscript", "user asked to remember this forever");

        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:raw-memory:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Raw memory attempt",
                "Raw prompts must not become canonical knowledge.",
                List.of(operation("op-raw", "add_evidence", target, payload)));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains(
                        "scope_mismatch",
                        "raw_prompt_memory_not_allowed");
    }

    @Test
    void rejectsUnsafeEvidenceMarkedAiVisible() {
        JsonNode payload = objectMapper.createObjectNode()
                .put("evidenceKey", "llm-proposal:private:v1")
                .put("aiVisibility", "allow")
                .put("evidenceSafety", "private");

        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:unsafe-visibility:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Unsafe visibility",
                "Unsafe evidence cannot be promoted to allow.",
                List.of(operation("op-private", "add_evidence", target(), payload)));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains("unsafe_evidence_ai_visibility");
    }

    @Test
    void rejectsAddEvidenceWithUnsupportedEvidenceTypeOrMissingCanonicalKeys() {
        JsonNode targetWithoutConceptKey = objectMapper.createObjectNode()
                .put("tenantId", TENANT)
                .put("environment", ENVIRONMENT)
                .put("subjectType", "concept");
        JsonNode payload = objectMapper.createObjectNode()
                .put("evidenceType", "project_preference")
                .put("summary", "Project preference must stay as source kind, not evidence type.");

        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:invalid-evidence-type:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Invalid evidence payload",
                "Evidence persistence must fail during governed validation, not at database constraints.",
                List.of(operation("op-invalid-evidence", "add_evidence", targetWithoutConceptKey, payload)));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains(
                        "target_concept_key_required",
                        "evidence_key_required",
                        "unsupported_evidence_type");
    }

    @Test
    void acceptsGovernedRevertEvidenceOperation() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:revert-cpf-guidance:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Revert superseded CPF field guidance",
                "The prior evidence should be reverted because a reviewed accessibility guideline superseded it.",
                List.of(operation("op-revert-cpf-guidance", "revert_evidence", target(), revertPayload())));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isTrue();
        assertThat(report.errorCount()).isZero();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void rejectsRevertEvidenceWithoutCanonicalTargetEvidenceOrReason() {
        JsonNode targetWithoutConceptKey = objectMapper.createObjectNode()
                .put("tenantId", TENANT)
                .put("environment", ENVIRONMENT)
                .put("subjectType", "concept");
        JsonNode payloadWithoutRevertContract = objectMapper.createObjectNode()
                .put("summary", "This is not enough to revert governed evidence.");

        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:invalid-revert:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Invalid revert evidence",
                "Revert must identify governed evidence and explain the reason.",
                List.of(operation(
                        "op-invalid-revert",
                        "revert_evidence",
                        targetWithoutConceptKey,
                        payloadWithoutRevertContract)));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains(
                        "target_concept_key_required",
                        "evidence_key_required",
                        "revert_reason_required");
    }

    @Test
    void rejectsDuplicateOperationIdsAndInvalidConfidence() {
        var request = new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:duplicates:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Duplicate operation",
                "Operation ids must be stable and unique.",
                List.of(
                        operation("op-duplicate", "add_evidence", target(), payload(), 1.2),
                        operation("op-duplicate", "add_evidence", target(), payload(), 0.7)));

        var report = validator.validateCreateRequest(TENANT, ENVIRONMENT, request);

        assertThat(report.valid()).isFalse();
        assertThat(report.issues())
                .extracting(DomainKnowledgeChangeSetValidationIssue::code)
                .contains(
                        "operation_id_duplicate",
                        "confidence_out_of_range");
    }

    private DomainKnowledgeChangeSetCreateRequest validRequest() {
        return new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:human-resources.funcionarios:cpf-guidance:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Improve CPF field guidance for employee registration",
                "The authoring turn detected that CPF field handling needs explicit LGPD guidance.",
                List.of(operation("op-add-cpf-guidance-evidence", "add_evidence", target(), payload())));
    }

    private DomainKnowledgeChangeSetOperationRequest operation(
            String operationId,
            String operationType,
            JsonNode target,
            JsonNode payload) {
        return operation(operationId, operationType, target, payload, 0.82);
    }

    private DomainKnowledgeChangeSetOperationRequest operation(
            String operationId,
            String operationType,
            JsonNode target,
            JsonNode payload,
            Double confidence) {
        return new DomainKnowledgeChangeSetOperationRequest(
                operationId,
                operationType,
                target,
                "Connect the guidance to reviewed Project Knowledge evidence.",
                List.of("domain-catalog:human-resources:v2026-04-30"),
                confidence,
                payload);
    }

    private JsonNode target() {
        return objectMapper.createObjectNode()
                .put("tenantId", TENANT)
                .put("environment", ENVIRONMENT)
                .put("subjectType", "concept")
                .put("conceptKey", "human-resources.funcionarios.field.cpf");
    }

    private JsonNode conceptTarget(String conceptKey) {
        return objectMapper.createObjectNode()
                .put("tenantId", TENANT)
                .put("environment", ENVIRONMENT)
                .put("conceptKey", conceptKey);
    }

    private JsonNode semanticConceptPayload() {
        var provenance = objectMapper.createObjectNode()
                .put("claimId", "claim:hr:workforce-management:v1")
                .put("sourceClass", "inferred")
                .put("derivationActivity", "semantic-pilot-synthesis")
                .put("model", "gpt-5.6-mini")
                .put("templateHash", "sha256:semantic-pilot-v1");
        provenance.set("sourceRefs", objectMapper.createArrayNode()
                .add("domain-catalog:praxis-service:human-resources:025f0d304a66669b"));
        provenance.set("agent", objectMapper.createObjectNode()
                .put("type", "model")
                .put("id", "openai:gpt-5.6-mini"));
        return objectMapper.createObjectNode()
                .put("contextKey", "human-resources")
                .put("nodeType", "business_capability")
                .put("label", "Workforce Management")
                .put("description", "Manage the employee lifecycle and workforce structure.")
                .put("semanticOwner", "people-operations")
                .put("aiVisibility", "allow")
                .set("provenance", provenance);
    }

    private JsonNode payload() {
        return objectMapper.createObjectNode()
                .put("evidenceKey", "llm-proposal:funcionarios:cpf-guidance:v1")
                .put("evidenceType", "llm_proposal")
                .put("sourceUri", "praxis-agentic-authoring://turn/example")
                .put("sourcePointer", "/projectKnowledge/0")
                .put("summary", "CPF is personal data and guidance should explain purpose and minimization.");
    }

    private JsonNode revertPayload() {
        return objectMapper.createObjectNode()
                .put("evidenceKey", "llm-proposal:funcionarios:cpf-guidance:v1")
                .put("revertReason", "The evidence was superseded by a reviewed accessibility guideline.")
                .put("replacementEvidenceKey", "llm-proposal:funcionarios:cpf-guidance:v2")
                .put("visibilityAfterRevert", "deny");
    }
}
