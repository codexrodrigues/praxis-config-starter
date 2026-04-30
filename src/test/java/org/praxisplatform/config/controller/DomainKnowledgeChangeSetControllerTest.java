package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetCreateRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetOperationSummary;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetResponse;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetStatusRequest;
import org.praxisplatform.config.dto.DomainKnowledgeChangeSetValidationResponse;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetService;

@Tag("unit")
class DomainKnowledgeChangeSetControllerTest {

    @Test
    void createsChangeSetWithTenantAndEnvironmentHeaders() {
        DomainKnowledgeChangeSetService service = mock(DomainKnowledgeChangeSetService.class);
        DomainKnowledgeChangeSetController controller = new DomainKnowledgeChangeSetController(service);
        DomainKnowledgeChangeSetCreateRequest request = request();
        DomainKnowledgeChangeSetResponse response = response(UUID.randomUUID(), "project-knowledge:employees:cpf:v1");
        when(service.create(request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.create(request, "tenant-a", "dev");

        assertThat(entity.getStatusCode().value()).isEqualTo(202);
        assertThat(entity.getBody()).isSameAs(response);
        verify(service).create(request, "tenant-a", "dev");
    }

    @Test
    void listsChangeSetsByStatusAndScope() {
        DomainKnowledgeChangeSetService service = mock(DomainKnowledgeChangeSetService.class);
        DomainKnowledgeChangeSetController controller = new DomainKnowledgeChangeSetController(service);
        DomainKnowledgeChangeSetResponse response = response(UUID.randomUUID(), "project-knowledge:employees:cpf:v1");
        when(service.list("tenant-a", "dev", "proposed")).thenReturn(List.of(response));

        var entity = controller.list("tenant-a", "dev", "proposed");

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getBody()).containsExactly(response);
        verify(service).list("tenant-a", "dev", "proposed");
    }

    @Test
    void getsChangeSetByIdAndScope() {
        DomainKnowledgeChangeSetService service = mock(DomainKnowledgeChangeSetService.class);
        DomainKnowledgeChangeSetController controller = new DomainKnowledgeChangeSetController(service);
        UUID id = UUID.randomUUID();
        DomainKnowledgeChangeSetResponse response = response(id, "project-knowledge:employees:cpf:v1");
        when(service.get(id, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.get(id, "tenant-a", "dev");

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getBody()).isSameAs(response);
        verify(service).get(id, "tenant-a", "dev");
    }

    @Test
    void validatesChangeSetByIdAndScope() {
        DomainKnowledgeChangeSetService service = mock(DomainKnowledgeChangeSetService.class);
        DomainKnowledgeChangeSetController controller = new DomainKnowledgeChangeSetController(service);
        UUID id = UUID.randomUUID();
        DomainKnowledgeChangeSetValidationResponse response =
                new DomainKnowledgeChangeSetValidationResponse(true, 0, 0, List.of());
        when(service.validate(id, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.validate(id, "tenant-a", "dev");

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getBody()).isSameAs(response);
        verify(service).validate(id, "tenant-a", "dev");
    }

    @Test
    void transitionsChangeSetStatusByIdAndScope() {
        DomainKnowledgeChangeSetService service = mock(DomainKnowledgeChangeSetService.class);
        DomainKnowledgeChangeSetController controller = new DomainKnowledgeChangeSetController(service);
        UUID id = UUID.randomUUID();
        DomainKnowledgeChangeSetStatusRequest request =
                new DomainKnowledgeChangeSetStatusRequest("approved", "reviewer:alice", "Safe to approve.");
        DomainKnowledgeChangeSetResponse response = response(id, "project-knowledge:employees:cpf:v1");
        when(service.transitionStatus(id, request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.transitionStatus(id, request, "tenant-a", "dev");

        assertThat(entity.getStatusCode().value()).isEqualTo(200);
        assertThat(entity.getBody()).isSameAs(response);
        verify(service).transitionStatus(id, request, "tenant-a", "dev");
    }

    private DomainKnowledgeChangeSetCreateRequest request() {
        return new DomainKnowledgeChangeSetCreateRequest(
                "project-knowledge:employees:cpf:v1",
                "proposed",
                "llm",
                "openai:gpt-5.4",
                "Improve CPF guidance",
                "CPF guidance needs explicit LGPD evidence.",
                List.of(new DomainKnowledgeChangeSetOperationRequest(
                        "op-add-evidence",
                        "add_evidence",
                        null,
                        "Add safe evidence.",
                        List.of("domain-catalog:hr:v1"),
                        0.82,
                        null)));
    }

    private DomainKnowledgeChangeSetResponse response(UUID id, String key) {
        return new DomainKnowledgeChangeSetResponse(
                id,
                "tenant-a",
                "dev",
                key,
                "proposed",
                "llm",
                "openai:gpt-5.4",
                null,
                "Improve CPF guidance",
                "CPF guidance needs explicit LGPD evidence.",
                1,
                "valid",
                List.of(new DomainKnowledgeChangeSetOperationSummary(
                        "op-add-evidence",
                        "add_evidence",
                        List.of("human-resources.funcionarios.field.cpf"))),
                null,
                Instant.now(),
                null,
                null);
    }
}
