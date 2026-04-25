package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleIntakeRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRulePublicationRequest;
import org.praxisplatform.config.dto.DomainRulePublicationResponse;
import org.praxisplatform.config.dto.DomainRuleSimulationRequest;
import org.praxisplatform.config.dto.DomainRuleSimulationResponse;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.service.DomainRuleService;

@Tag("unit")
class DomainRuleControllerTest {

    @Test
    void intakeCreatesDraftDefinitionWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        DomainRuleIntakeRequest request = new DomainRuleIntakeRequest(
                "Impedir seleção de fornecedores bloqueados em pedidos de compra.",
                "Esse pedido deve seguir pela trilha governada de regra compartilhada.",
                null,
                "selection_eligibility",
                "procurement",
                "procurement.suppliers",
                "praxis-api-quickstart",
                null,
                null,
                null,
                null,
                "llm",
                "agent");
        DomainRuleDefinitionResponse definition = new DomainRuleDefinitionResponse(
                UUID.randomUUID(),
                "tenant-a",
                "dev",
                "procurement.suppliers.rule.selection-eligibility",
                1,
                "selection_eligibility",
                "draft",
                "procurement",
                "procurement.suppliers",
                "praxis-api-quickstart",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "llm",
                "agent",
                null,
                null,
                null,
                null,
                null);
        DomainRuleIntakeResponse response = new DomainRuleIntakeResponse(
                UUID.randomUUID(),
                "tenant-a",
                "dev",
                "procurement.suppliers.rule.selection-eligibility",
                "selection_eligibility",
                "procurement",
                "procurement.suppliers",
                "praxis-api-quickstart",
                "draft",
                null,
                definition,
                java.time.Instant.now());
        when(service.intake(request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.intake(request, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).intake(request, "tenant-a", "dev");
    }

    @Test
    void createsDefinitionWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        DomainRuleDefinitionRequest request = new DomainRuleDefinitionRequest(
                "rule-a",
                null,
                "visual_guidance",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "llm",
                "agent",
                null);
        DomainRuleDefinitionResponse response = new DomainRuleDefinitionResponse(
                UUID.randomUUID(),
                "tenant-a",
                "dev",
                "rule-a",
                1,
                "visual_guidance",
                "draft",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "llm",
                "agent",
                null,
                null,
                null,
                null,
                null);
        when(service.createDefinition(request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.createDefinition(request, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).createDefinition(request, "tenant-a", "dev");
    }

    @Test
    void listsMaterializationsByTargetArtifact() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        UUID definitionId = UUID.randomUUID();
        DomainRuleMaterializationResponse response = new DomainRuleMaterializationResponse(
                UUID.randomUUID(),
                "tenant-a",
                "dev",
                definitionId,
                "rule-a",
                1,
                "form:rule-a",
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "/formRules/-",
                null,
                "lgpd-cpf-guidance",
                "pending_review",
                null,
                null,
                null,
                null,
                "llm",
                "agent",
                null,
                null,
                null);
        when(service.materializations(
                "tenant-a",
                "dev",
                null,
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "pending_review"))
                .thenReturn(List.of(response));

        var entity = controller.materializations(
                "tenant-a",
                "dev",
                null,
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "pending_review");

        assertThat(entity.getBody()).containsExactly(response);
        verify(service).materializations(
                "tenant-a",
                "dev",
                null,
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "pending_review");
    }

    @Test
    void createsMaterializationWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        UUID definitionId = UUID.randomUUID();
        DomainRuleMaterializationRequest request = new DomainRuleMaterializationRequest(
                definitionId,
                "form:rule-a",
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "/formRules/-",
                null,
                "lgpd-cpf-guidance",
                "pending_review",
                null,
                null,
                null,
                "llm",
                "agent");
        DomainRuleMaterializationResponse response = new DomainRuleMaterializationResponse(
                UUID.randomUUID(),
                "tenant-a",
                "dev",
                definitionId,
                "rule-a",
                1,
                "form:rule-a",
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "/formRules/-",
                null,
                "lgpd-cpf-guidance",
                "pending_review",
                null,
                null,
                null,
                null,
                "llm",
                "agent",
                null,
                null,
                null);
        when(service.createMaterialization(request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.createMaterialization(request, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).createMaterialization(request, "tenant-a", "dev");
    }

    @Test
    void transitionsDefinitionStatusWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        UUID definitionId = UUID.randomUUID();
        DomainRuleStatusTransitionRequest request = new DomainRuleStatusTransitionRequest(
                "active",
                "human",
                "privacy-office",
                null);
        DomainRuleDefinitionResponse response = new DomainRuleDefinitionResponse(
                definitionId,
                "tenant-a",
                "dev",
                "rule-a",
                1,
                "visual_guidance",
                "active",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "llm",
                "agent",
                "privacy-office",
                null,
                null,
                null,
                null);
        when(service.transitionDefinitionStatus(definitionId, request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.transitionDefinitionStatus(definitionId, request, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).transitionDefinitionStatus(definitionId, request, "tenant-a", "dev");
    }

    @Test
    void simulatesRuleWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        DomainRuleSimulationRequest request = new DomainRuleSimulationRequest(
                null,
                "procurement.suppliers.rule.selection-eligibility",
                "policy_reference",
                "procurement",
                "procurement.suppliers",
                "praxis-api-quickstart",
                null,
                null,
                null,
                null);
        DomainRuleSimulationResponse response = new DomainRuleSimulationResponse(
                UUID.randomUUID(),
                null,
                "tenant-a",
                "dev",
                "procurement.suppliers.rule.selection-eligibility",
                null,
                "policy_reference",
                "procurement",
                "procurement.suppliers",
                "praxis-api-quickstart",
                "pass",
                null,
                null,
                null,
                null,
                null,
                null,
                java.time.Instant.now());
        when(service.simulate(request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.simulate(request, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).simulate(request, "tenant-a", "dev");
    }

    @Test
    void publishesRuleWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        UUID definitionId = UUID.randomUUID();
        DomainRulePublicationRequest request = new DomainRulePublicationRequest(
                definitionId,
                List.of(),
                true,
                "human",
                "procurement-owner",
                null);
        DomainRulePublicationResponse response = new DomainRulePublicationResponse(
                UUID.randomUUID(),
                "tenant-a",
                "dev",
                "published",
                "ready_to_publish",
                definitionId,
                "procurement.suppliers.rule.selection-eligibility",
                1,
                "selection_eligibility",
                "procurement.suppliers",
                "praxis-api-quickstart",
                null,
                List.of(),
                null,
                java.time.Instant.now());
        when(service.publish(request, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.publish(request, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).publish(request, "tenant-a", "dev");
    }

    @Test
    void transitionsMaterializationStatusWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = new DomainRuleController(service);
        UUID definitionId = UUID.randomUUID();
        UUID materializationId = UUID.randomUUID();
        DomainRuleStatusTransitionRequest request = new DomainRuleStatusTransitionRequest(
                "applied",
                "human",
                "privacy-office",
                null);
        DomainRuleMaterializationResponse response = new DomainRuleMaterializationResponse(
                materializationId,
                "tenant-a",
                "dev",
                definitionId,
                "rule-a",
                1,
                "form:rule-a",
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "/formRules/-",
                null,
                "lgpd-cpf-guidance",
                "applied",
                null,
                null,
                null,
                null,
                "human",
                "privacy-office",
                null,
                null,
                null);
        when(service.transitionMaterializationStatus(materializationId, request, "tenant-a", "dev"))
                .thenReturn(response);

        var entity = controller.transitionMaterializationStatus(materializationId, request, "tenant-a", "dev");

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).transitionMaterializationStatus(materializationId, request, "tenant-a", "dev");
    }
}
