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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;

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

    private DomainKnowledgeChangeSetService service(DomainKnowledgeChangeSetRepository repository) {
        return new DomainKnowledgeChangeSetService(repository, validator, objectMapper);
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
