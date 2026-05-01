package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetStatusRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;

@Tag("unit")
class DomainKnowledgeChangeSetServiceTest {

    private static final String TENANT = "tenant-a";
    private static final String ENVIRONMENT = "dev";
    private static final String CHANGE_SET_KEY = "project-knowledge:employees:cpf-guidance:v1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainKnowledgeChangeSetValidator validator = new DomainKnowledgeChangeSetValidator();

    @Test
    void createsProposedChangeSetWithSafeResponseAndValidationHash() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        when(repository.findByTenantIdAndEnvironmentAndChangeSetKey(TENANT, ENVIRONMENT, CHANGE_SET_KEY))
                .thenReturn(Optional.empty());
        when(repository.save(any(DomainKnowledgeChangeSet.class))).thenAnswer(invocation -> {
            DomainKnowledgeChangeSet changeSet = invocation.getArgument(0);
            changeSet.onInsert();
            return changeSet;
        });

        var response = service.create(validRequest(), TENANT, ENVIRONMENT);

        assertThat(response.id()).isNotNull();
        assertThat(response.tenantId()).isEqualTo(TENANT);
        assertThat(response.environment()).isEqualTo(ENVIRONMENT);
        assertThat(response.changeSetKey()).isEqualTo(CHANGE_SET_KEY);
        assertThat(response.status()).isEqualTo("proposed");
        assertThat(response.authorType()).isEqualTo("llm");
        assertThat(response.operationCount()).isEqualTo(1);
        assertThat(response.validationStatus()).isEqualTo("valid");
        assertThat(response.validationResult().path("patchHash").asText()).isNotBlank();
        assertThat(response.safeOperationSummary()).hasSize(1);
        assertThat(response.safeOperationSummary().get(0).operationType()).isEqualTo("add_evidence");
        assertThat(response.safeOperationSummary().get(0).targetConceptKeys())
                .containsExactly("human-resources.funcionarios.field.cpf");

        ArgumentCaptor<DomainKnowledgeChangeSet> captor = ArgumentCaptor.forClass(DomainKnowledgeChangeSet.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPatch()).contains("op-add-cpf-guidance-evidence");
        assertThat(captor.getValue().getValidationResult()).contains("\"validationStatus\":\"valid\"");
        assertThat(captor.getValue().getValidationResult()).contains("\"patchHash\"");
    }

    @Test
    void createsProposedRevertEvidenceChangeSetWithSafeSummary() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSetCreateRequest request = revertEvidenceRequest();
        when(repository.findByTenantIdAndEnvironmentAndChangeSetKey(
                TENANT,
                ENVIRONMENT,
                request.changeSetKey()))
                .thenReturn(Optional.empty());
        when(repository.save(any(DomainKnowledgeChangeSet.class))).thenAnswer(invocation -> {
            DomainKnowledgeChangeSet changeSet = invocation.getArgument(0);
            changeSet.onInsert();
            return changeSet;
        });

        var response = service.create(request, TENANT, ENVIRONMENT);

        assertThat(response.status()).isEqualTo("proposed");
        assertThat(response.validationStatus()).isEqualTo("valid");
        assertThat(response.safeOperationSummary()).hasSize(1);
        assertThat(response.safeOperationSummary().get(0).operationType()).isEqualTo("revert_evidence");
        assertThat(response.safeOperationSummary().get(0).targetConceptKeys())
                .containsExactly("human-resources.funcionarios.field.cpf");
    }

    @Test
    void returnsExistingChangeSetForIdempotentRetry() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSetCreateRequest request = validRequest();
        DomainKnowledgeChangeSet existing = persisted(request);
        when(repository.findByTenantIdAndEnvironmentAndChangeSetKey(TENANT, ENVIRONMENT, CHANGE_SET_KEY))
                .thenReturn(Optional.of(existing));

        var response = service.create(request, TENANT, ENVIRONMENT);

        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.changeSetKey()).isEqualTo(CHANGE_SET_KEY);
        assertThat(response.operationCount()).isEqualTo(1);
    }

    @Test
    void rejectsSameKeyWithDifferentPatch() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        when(repository.findByTenantIdAndEnvironmentAndChangeSetKey(TENANT, ENVIRONMENT, CHANGE_SET_KEY))
                .thenReturn(Optional.of(existing));

        DomainKnowledgeChangeSetCreateRequest changed = new DomainKnowledgeChangeSetCreateRequest(
                CHANGE_SET_KEY,
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Improve CPF field guidance",
                "Changed operation should conflict with the existing stable key.",
                List.of(operation(
                        "op-add-cpf-guidance-evidence-v2",
                        "add_evidence",
                        target(),
                        payload("llm-proposal:funcionarios:cpf-guidance:v2"))));

        assertThatThrownBy(() -> service.create(changed, TENANT, ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("already exists with different semantics");
    }

    @Test
    void rejectsInvalidCreateRequestBeforePersisting() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSetCreateRequest invalid = new DomainKnowledgeChangeSetCreateRequest(
                CHANGE_SET_KEY,
                "applied",
                "llm",
                "openai:gpt-5.4",
                "Invalid",
                "Invalid request",
                List.of(new DomainKnowledgeChangeSetOperationRequest(
                        "op-invalid",
                        "delete_concept",
                        target(),
                        "Destructive operation should fail.",
                        List.of("domain-catalog:hr:v1"),
                        0.5,
                        payload("llm-proposal:invalid:v1"))));

        assertThatThrownBy(() -> service.create(invalid, TENANT, ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("destructive_operation_not_supported")
                .hasMessageContaining("invalid_initial_status");
    }

    @Test
    void listsByStatusWithinScope() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        when(repository.findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(TENANT, ENVIRONMENT, "proposed"))
                .thenReturn(List.of(existing));

        var results = service.list(TENANT, ENVIRONMENT, "proposed");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).changeSetKey()).isEqualTo(CHANGE_SET_KEY);
        verify(repository).findByTenantIdAndEnvironmentAndStatusOrderByCreatedAtDesc(TENANT, ENVIRONMENT, "proposed");
    }

    @Test
    void getsOnlyWithinRequestScope() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThat(service.get(existing.getId(), TENANT, ENVIRONMENT).changeSetKey()).isEqualTo(CHANGE_SET_KEY);

        assertThatThrownBy(() -> service.get(existing.getId(), "tenant-b", ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("not found in request scope");
    }

    @Test
    void validatesExistingChangeSetAndPersistsInvalidResult() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        existing.setPatch(write(objectMapper.valueToTree(List.of(new DomainKnowledgeChangeSetOperationRequest(
                "op-invalid-delete",
                "delete_concept",
                target(),
                "Destructive operation should stay invalid.",
                List.of("domain-catalog:human-resources:v2026-04-30"),
                0.8,
                payload("llm-proposal:invalid:v1"))))));
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any(DomainKnowledgeChangeSet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var validation = service.validate(existing.getId(), TENANT, ENVIRONMENT);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues())
                .extracting(org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationIssue::code)
                .contains("destructive_operation_not_supported");
        ArgumentCaptor<DomainKnowledgeChangeSet> captor = ArgumentCaptor.forClass(DomainKnowledgeChangeSet.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getValidationResult()).contains("\"validationStatus\":\"invalid\"");
        assertThat(captor.getValue().getValidationResult()).contains("destructive_operation_not_supported");
    }

    @Test
    void validateRejectsChangeSetOutsideRequestScope() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.validate(existing.getId(), "tenant-b", ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("not found in request scope");
    }

    @Test
    void approvesValidChangeSetAndRecordsReviewer() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any(DomainKnowledgeChangeSet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.transitionStatus(
                existing.getId(),
                new DomainKnowledgeChangeSetStatusRequest("approved", "reviewer:alice", "Safe evidence reviewed."),
                TENANT,
                ENVIRONMENT);

        assertThat(response.status()).isEqualTo("approved");
        assertThat(response.reviewerId()).isEqualTo("reviewer:alice");
        assertThat(response.reviewedAt()).isNotNull();
        ArgumentCaptor<DomainKnowledgeChangeSet> captor = ArgumentCaptor.forClass(DomainKnowledgeChangeSet.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("approved");
        assertThat(captor.getValue().getReviewerId()).isEqualTo("reviewer:alice");
        assertThat(captor.getValue().getReviewedAt()).isNotNull();
    }

    @Test
    void rejectsInvalidChangeSetApprovalUntilValidationIsClean() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        existing.setValidationResult("""
                {
                  "validationStatus": "invalid",
                  "patchHash": "invalid",
                  "errorCount": 1,
                  "warningCount": 0,
                  "issues": []
                }
                """);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transitionStatus(
                existing.getId(),
                new DomainKnowledgeChangeSetStatusRequest("approved", "reviewer:alice", "Invalid proposal."),
                TENANT,
                ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("must be valid before approval");
    }

    @Test
    void rejectsStatusTransitionToAppliedBecauseApplyNeedsOwnEndpoint() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transitionStatus(
                existing.getId(),
                new DomainKnowledgeChangeSetStatusRequest("applied", "reviewer:alice", "Do not bypass apply."),
                TENANT,
                ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("use the apply endpoint");
    }

    @Test
    void rejectsTerminalRejectedChangeSetReapproval() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        existing.setStatus("rejected");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.transitionStatus(
                existing.getId(),
                new DomainKnowledgeChangeSetStatusRequest("approved", "reviewer:alice", "Reopen rejected proposal."),
                TENANT,
                ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("status transition is not allowed: rejected -> approved");
    }

    @Test
    void appliesApprovedAddEvidenceChangeSetToExistingConcept() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
        DomainKnowledgeEvidenceRepository evidenceRepository = mock(DomainKnowledgeEvidenceRepository.class);
        DomainKnowledgeChangeSetService service = service(repository, conceptRepository, evidenceRepository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        existing.setStatus("approved");
        DomainKnowledgeConcept concept = concept();
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any(DomainKnowledgeChangeSet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(
                TENANT,
                ENVIRONMENT,
                "human-resources.funcionarios.field.cpf"))
                .thenReturn(Optional.of(concept));
        when(evidenceRepository.findByTenantIdAndEnvironmentAndEvidenceKey(
                TENANT,
                ENVIRONMENT,
                "llm-proposal:funcionarios:cpf-guidance:v1"))
                .thenReturn(List.of());
        when(evidenceRepository.save(any(DomainKnowledgeEvidence.class))).thenAnswer(invocation -> {
            DomainKnowledgeEvidence evidence = invocation.getArgument(0);
            evidence.onInsert();
            return evidence;
        });

        var response = service.apply(existing.getId(), TENANT, ENVIRONMENT);

        assertThat(response.status()).isEqualTo("applied");
        assertThat(response.appliedAt()).isNotNull();
        ArgumentCaptor<DomainKnowledgeEvidence> evidenceCaptor =
                ArgumentCaptor.forClass(DomainKnowledgeEvidence.class);
        verify(evidenceRepository).save(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().getEvidenceKey())
                .isEqualTo("llm-proposal:funcionarios:cpf-guidance:v1");
        assertThat(evidenceCaptor.getValue().getSubjectType()).isEqualTo("concept");
        assertThat(evidenceCaptor.getValue().getSubjectId()).isEqualTo(concept.getId());
        assertThat(evidenceCaptor.getValue().getEvidenceType()).isEqualTo("llm_proposal");
        assertThat(evidenceCaptor.getValue().getPayload()).contains("CPF is personal data");
    }

    @Test
    void rejectsApplyBeforeApproval() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.apply(existing.getId(), TENANT, ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("must be approved before apply");
    }

    @Test
    void rejectsApplyWhenTargetConceptDoesNotExist() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeConceptRepository conceptRepository = mock(DomainKnowledgeConceptRepository.class);
        DomainKnowledgeEvidenceRepository evidenceRepository = mock(DomainKnowledgeEvidenceRepository.class);
        DomainKnowledgeChangeSetService service = service(repository, conceptRepository, evidenceRepository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        existing.setStatus("approved");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(conceptRepository.findByTenantIdAndEnvironmentAndConceptKey(
                TENANT,
                ENVIRONMENT,
                "human-resources.funcionarios.field.cpf"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(existing.getId(), TENANT, ENVIRONMENT))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Target concept not found");
    }

    @Test
    void timelineReturnsSafeLifecycleWithoutRawPatchPayload() {
        DomainKnowledgeChangeSetRepository repository = mock(DomainKnowledgeChangeSetRepository.class);
        DomainKnowledgeChangeSetService service = service(repository);
        DomainKnowledgeChangeSet existing = persisted(validRequest());
        existing.setStatus("applied");
        existing.setReviewerId("reviewer:alice");
        existing.setReviewedAt(Instant.parse("2026-05-01T10:15:30Z"));
        existing.setAppliedAt(Instant.parse("2026-05-01T10:16:30Z"));
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        var timeline = service.timeline(existing.getId(), TENANT, ENVIRONMENT);

        assertThat(timeline.changeSetId()).isEqualTo(existing.getId());
        assertThat(timeline.changeSetKey()).isEqualTo(CHANGE_SET_KEY);
        assertThat(timeline.events()).extracting("eventType")
                .containsExactly(
                        "change_set.created",
                        "validation.completed",
                        "review.approved",
                        "change_set.applied");
        assertThat(timeline.events()).allSatisfy(event -> {
            assertThat(event.visibility()).isEqualTo("safe");
            assertThat(event.operationTypes()).containsExactly("add_evidence");
            assertThat(event.targetConceptKeys()).containsExactly("human-resources.funcionarios.field.cpf");
        });
        assertThat(timeline.events()).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("validation.completed");
            assertThat(event.validationStatus()).isEqualTo("valid");
            assertThat(event.summary()).contains("0 errors").contains("0 warnings");
        });
        String safeSurface = timeline.events().toString();
        assertThat(safeSurface).doesNotContain("CPF is personal data");
        assertThat(safeSurface).doesNotContain("sourcePointer");
        assertThat(safeSurface).doesNotContain("patchHash");
    }

    private DomainKnowledgeChangeSetService service(DomainKnowledgeChangeSetRepository repository) {
        return service(
                repository,
                mock(DomainKnowledgeConceptRepository.class),
                mock(DomainKnowledgeEvidenceRepository.class));
    }

    private DomainKnowledgeChangeSetService service(
            DomainKnowledgeChangeSetRepository repository,
            DomainKnowledgeConceptRepository conceptRepository,
            DomainKnowledgeEvidenceRepository evidenceRepository) {
        return new DomainKnowledgeChangeSetService(
                repository,
                conceptRepository,
                evidenceRepository,
                validator,
                objectMapper);
    }

    private DomainKnowledgeChangeSet persisted(DomainKnowledgeChangeSetCreateRequest request) {
        String patch = write(objectMapper.valueToTree(request.patch()));
        String validationResult = """
                {
                  "validationStatus": "valid",
                  "patchHash": "%s",
                  "errorCount": 0,
                  "warningCount": 0,
                  "issues": []
                }
                """.formatted(sha256(patch));
        DomainKnowledgeChangeSet changeSet = new DomainKnowledgeChangeSet();
        changeSet.setId(UUID.randomUUID());
        changeSet.setTenantId(TENANT);
        changeSet.setEnvironment(ENVIRONMENT);
        changeSet.setChangeSetKey(request.changeSetKey());
        changeSet.setStatus("proposed");
        changeSet.setAuthorType("llm");
        changeSet.setAuthorId("openai:gpt-5.4");
        changeSet.setIntent(request.intent());
        changeSet.setReason(request.reason());
        changeSet.setPatch(patch);
        changeSet.setValidationResult(validationResult);
        changeSet.onInsert();
        return changeSet;
    }

    private DomainKnowledgeConcept concept() {
        DomainKnowledgeConcept concept = DomainKnowledgeConcept.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT)
                .environment(ENVIRONMENT)
                .conceptKey("human-resources.funcionarios.field.cpf")
                .nodeType("field")
                .lifecycle("active")
                .curationStatus("approved")
                .aiVisibility("allow")
                .build();
        concept.onInsert();
        return concept;
    }

    private DomainKnowledgeChangeSetCreateRequest validRequest() {
        return new DomainKnowledgeChangeSetCreateRequest(
                CHANGE_SET_KEY,
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Improve CPF field guidance for employee registration",
                "The authoring turn detected that CPF field handling needs explicit LGPD guidance.",
                List.of(operation(
                        "op-add-cpf-guidance-evidence",
                        "add_evidence",
                        target(),
                        payload("llm-proposal:funcionarios:cpf-guidance:v1"))));
    }

    private DomainKnowledgeChangeSetCreateRequest revertEvidenceRequest() {
        return new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:revert-cpf-guidance:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Revert superseded CPF field guidance",
                "The prior evidence should be reverted because a reviewed accessibility guideline superseded it.",
                List.of(operation(
                        "op-revert-cpf-guidance",
                        "revert_evidence",
                        target(),
                        revertPayload("llm-proposal:funcionarios:cpf-guidance:v1"))));
    }

    private DomainKnowledgeChangeSetOperationRequest operation(
            String operationId,
            String operationType,
            JsonNode target,
            JsonNode payload) {
        return new DomainKnowledgeChangeSetOperationRequest(
                operationId,
                operationType,
                target,
                "Connect the guidance to reviewed Project Knowledge evidence.",
                List.of("domain-catalog:human-resources:v2026-04-30"),
                0.82,
                payload);
    }

    private JsonNode target() {
        return objectMapper.createObjectNode()
                .put("tenantId", TENANT)
                .put("environment", ENVIRONMENT)
                .put("subjectType", "concept")
                .put("conceptKey", "human-resources.funcionarios.field.cpf");
    }

    private JsonNode payload(String evidenceKey) {
        return objectMapper.createObjectNode()
                .put("evidenceKey", evidenceKey)
                .put("evidenceType", "llm_proposal")
                .put("sourceUri", "praxis-agentic-authoring://turn/example")
                .put("sourcePointer", "/projectKnowledge/0")
                .put("summary", "CPF is personal data and guidance should explain purpose and minimization.");
    }

    private JsonNode revertPayload(String evidenceKey) {
        return objectMapper.createObjectNode()
                .put("evidenceKey", evidenceKey)
                .put("revertReason", "The evidence was superseded by a reviewed accessibility guideline.")
                .put("replacementEvidenceKey", "llm-proposal:funcionarios:cpf-guidance:v2")
                .put("visibilityAfterRevert", "deny");
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
