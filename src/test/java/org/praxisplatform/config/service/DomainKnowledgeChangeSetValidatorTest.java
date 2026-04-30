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

    private JsonNode payload() {
        return objectMapper.createObjectNode()
                .put("evidenceKey", "llm-proposal:funcionarios:cpf-guidance:v1")
                .put("evidenceType", "llm_proposal")
                .put("sourceUri", "praxis-agentic-authoring://turn/example")
                .put("sourcePointer", "/projectKnowledge/0")
                .put("summary", "CPF is personal data and guidance should explain purpose and minimization.");
    }
}
