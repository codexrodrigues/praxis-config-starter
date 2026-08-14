package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleDefinitionCapabilitiesResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleDefinitionStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeResponse;
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.dto.DomainRuleMaterializationResponse;
import org.praxisplatform.config.dto.DomainRulePublicationRequest;
import org.praxisplatform.config.dto.DomainRulePublicationResponse;
import org.praxisplatform.config.dto.DomainRuleSimulationRequest;
import org.praxisplatform.config.dto.DomainRuleSimulationResponse;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.dto.DomainRuleTimelineResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class DomainRuleControllerTest {

    private static final DomainRuleGovernancePrincipal PRINCIPAL =
            new DomainRuleGovernancePrincipal("tenant-a", "agent", "dev");

    @Test
    void intakeCreatesDraftDefinitionWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
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
                null);
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
        when(service.intake(request, PRINCIPAL)).thenReturn(response);

        var entity = controller.intake(request, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).intake(request, PRINCIPAL);
    }

    @Test
    void createsDefinitionWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
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
        when(service.createDefinition(request, PRINCIPAL)).thenReturn(response);

        var entity = controller.createDefinition(request, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).createDefinition(request, PRINCIPAL);
    }

    @Test
    void listsMaterializationsByTargetArtifact() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
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
                "pending_review",
                servletRequest());

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
    void returnsDefinitionTimelineWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
        UUID definitionId = UUID.randomUUID();
        DomainRuleTimelineResponse response = new DomainRuleTimelineResponse(
                definitionId,
                "tenant-a",
                "dev",
                "operations.missoes.rule.pause",
                1,
                "workflow_action_policy",
                "operations.missoes",
                "praxis-api-quickstart",
                List.of());
        when(service.definitionTimeline(definitionId, "tenant-a", "dev")).thenReturn(response);

        var entity = controller.definitionTimeline(definitionId, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).definitionTimeline(definitionId, "tenant-a", "dev");
    }

    @Test
    void readPlaneUsesServerResolvedScopeInsteadOfCallerHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        DomainRuleController controller = new DomainRuleController(service, resolver);
        HttpServletRequest servletRequest = servletRequest();
        UUID definitionId = UUID.randomUUID();
        DomainRuleTimelineResponse timeline = new DomainRuleTimelineResponse(
                definitionId,
                "tenant-a",
                "dev",
                "operations.missoes.rule.pause",
                1,
                "workflow_action_policy",
                "operations.missoes",
                "praxis-api-quickstart",
                List.of());

        when(resolver.resolve(
                servletRequest, "spoofed-tenant", "spoofed-env", "RULE_DEFINITION_READER"))
                .thenReturn(PRINCIPAL);
        when(service.definitions("tenant-a", "dev", null, null, null, null))
                .thenReturn(List.of());
        when(service.definitionTimeline(definitionId, "tenant-a", "dev"))
                .thenReturn(timeline);
        when(service.materializations("tenant-a", "dev", null, null, null, null, null))
                .thenReturn(List.of());

        controller.definitions(
                "spoofed-tenant", "spoofed-env", null, null, null, null, servletRequest);
        controller.definitionTimeline(
                definitionId, "spoofed-tenant", "spoofed-env", servletRequest);
        controller.materializations(
                "spoofed-tenant", "spoofed-env", null, null, null, null, null, servletRequest);

        verify(resolver, org.mockito.Mockito.times(3)).resolve(
                servletRequest, "spoofed-tenant", "spoofed-env", "RULE_DEFINITION_READER");
        verify(service).definitions("tenant-a", "dev", null, null, null, null);
        verify(service).definitionTimeline(definitionId, "tenant-a", "dev");
        verify(service).materializations("tenant-a", "dev", null, null, null, null, null);
    }

    @Test
    void definitionCapabilitiesAreScopedAndAuthorizedByTheServer() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        DomainRuleController controller = new DomainRuleController(service, resolver);
        HttpServletRequest servletRequest = servletRequest();
        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinitionResponse definition = definitionResponse(definitionId, "rule-a", 3);

        when(resolver.resolve(
                servletRequest, "spoofed-tenant", "spoofed-env", "RULE_DEFINITION_READER"))
                .thenReturn(PRINCIPAL);
        when(resolver.hasRole(servletRequest, "RULE_DEFINITION_AUTHOR")).thenReturn(true);
        when(service.definitions("tenant-a", "dev", null, null, null, null))
                .thenReturn(List.of(definition));

        DomainRuleDefinitionCapabilitiesResponse response = controller.definitionCapabilities(
                "spoofed-tenant", "spoofed-env", servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.tenantId()).isEqualTo("tenant-a");
        assertThat(response.environment()).isEqualTo("dev");
        assertThat(response.definitions()).singleElement().satisfies(capability -> {
            assertThat(capability.definitionId()).isEqualTo(definitionId);
            assertThat(capability.ruleKey()).isEqualTo("rule-a");
            assertThat(capability.version()).isEqualTo(3);
            assertThat(capability.availableActions()).containsExactly("CREATE_NEW_VERSION");
        });
        verify(service).definitions("tenant-a", "dev", null, null, null, null);
    }

    @Test
    void readerWithoutAuthorRoleReceivesNoCreateVersionAction() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        DomainRuleController controller = new DomainRuleController(service, resolver);
        HttpServletRequest servletRequest = servletRequest();
        DomainRuleDefinitionResponse definition = definitionResponse(UUID.randomUUID(), "rule-a", 3);
        when(resolver.resolve(servletRequest, null, null, "RULE_DEFINITION_READER")).thenReturn(PRINCIPAL);
        when(resolver.hasRole(servletRequest, "RULE_DEFINITION_AUTHOR")).thenReturn(false);
        when(service.definitions("tenant-a", "dev", null, null, null, null)).thenReturn(List.of(definition));

        var response = controller.definitionCapabilities(null, null, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.definitions()).singleElement()
                .satisfies(capability -> assertThat(capability.availableActions()).isEmpty());
    }

    @Test
    void createsMaterializationWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
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
                null);
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
        when(service.createMaterialization(request, PRINCIPAL)).thenReturn(response);

        var entity = controller.createMaterialization(request, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).createMaterialization(request, PRINCIPAL);
    }

    @Test
    void transitionsDefinitionStatusWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinitionStatusTransitionRequest request = new DomainRuleDefinitionStatusTransitionRequest(
                "active",
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
        when(service.transitionDefinitionStatus(definitionId, request, PRINCIPAL)).thenReturn(response);

        var entity = controller.transitionDefinitionStatus(
                definitionId, request, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).transitionDefinitionStatus(definitionId, request, PRINCIPAL);
    }

    @Test
    void simulatesRuleWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
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

        var entity = controller.simulate(request, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).simulate(request, "tenant-a", "dev");
    }

    @Test
    void publishesRuleWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
        UUID definitionId = UUID.randomUUID();
        DomainRulePublicationRequest request = new DomainRulePublicationRequest(
                definitionId,
                List.of(),
                true,
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
        when(service.publish(request, PRINCIPAL)).thenReturn(response);

        var entity = controller.publish(request, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).publish(request, PRINCIPAL);
    }

    @Test
    void transitionsMaterializationStatusWithTenantAndEnvironmentHeaders() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleController controller = controller(service);
        UUID definitionId = UUID.randomUUID();
        UUID materializationId = UUID.randomUUID();
        DomainRuleStatusTransitionRequest request = new DomainRuleStatusTransitionRequest(
                "applied",
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
        when(service.transitionMaterializationStatus(materializationId, request, PRINCIPAL))
                .thenReturn(response);

        var entity = controller.transitionMaterializationStatus(
                materializationId, request, "tenant-a", "dev", servletRequest());

        assertThat(entity.getBody()).isSameAs(response);
        verify(service).transitionMaterializationStatus(materializationId, request, PRINCIPAL);
    }

    @Test
    void mutableMaterializationBoundariesResolveStageSpecificServerRoles() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        DomainRuleController controller = new DomainRuleController(service, resolver);
        HttpServletRequest servletRequest = servletRequest();
        UUID definitionId = UUID.randomUUID();
        UUID materializationId = UUID.randomUUID();
        DomainRuleMaterializationRequest draft = new DomainRuleMaterializationRequest(
                definitionId,
                "rule-a:backend-validation",
                "backend_validation",
                "resource-validation",
                "resource-a",
                "/validationPolicy",
                null,
                "rule-a",
                "draft",
                null,
                "sha256:source",
                null);
        DomainRulePublicationRequest publication = new DomainRulePublicationRequest(
                definitionId, List.of(), true, null);
        DomainRuleStatusTransitionRequest applied = new DomainRuleStatusTransitionRequest("applied", null);
        DomainRuleStatusTransitionRequest failed = new DomainRuleStatusTransitionRequest("failed", null);

        when(resolver.resolve(servletRequest, "caller-tenant", "caller-env", "RULE_DEFINITION_AUTHOR"))
                .thenReturn(PRINCIPAL);
        when(resolver.resolve(servletRequest, "caller-tenant", "caller-env", "RULE_SNAPSHOT_PUBLISHER"))
                .thenReturn(PRINCIPAL);
        when(resolver.resolve(servletRequest, "caller-tenant", "caller-env", "RULE_SNAPSHOT_OPERATOR"))
                .thenReturn(PRINCIPAL);

        controller.createMaterialization(draft, "caller-tenant", "caller-env", servletRequest);
        controller.publish(publication, "caller-tenant", "caller-env", servletRequest);
        controller.transitionMaterializationStatus(
                materializationId, applied, "caller-tenant", "caller-env", servletRequest);
        controller.transitionMaterializationStatus(
                materializationId, failed, "caller-tenant", "caller-env", servletRequest);

        verify(resolver).resolve(
                servletRequest, "caller-tenant", "caller-env", "RULE_DEFINITION_AUTHOR");
        verify(resolver, org.mockito.Mockito.times(2)).resolve(
                servletRequest, "caller-tenant", "caller-env", "RULE_SNAPSHOT_PUBLISHER");
        verify(resolver).resolve(
                servletRequest, "caller-tenant", "caller-env", "RULE_SNAPSHOT_OPERATOR");
        verify(service).createMaterialization(draft, PRINCIPAL);
        verify(service).publish(publication, PRINCIPAL);
        verify(service).transitionMaterializationStatus(materializationId, applied, PRINCIPAL);
        verify(service).transitionMaterializationStatus(materializationId, failed, PRINCIPAL);
    }

    @Test
    void readBoundariesUseServerResolvedScopeInsteadOfCallerHints() {
        DomainRuleService service = mock(DomainRuleService.class);
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        DomainRuleController controller = new DomainRuleController(service, resolver);
        HttpServletRequest servletRequest = servletRequest();
        UUID definitionId = UUID.randomUUID();
        when(resolver.resolve(servletRequest, "caller-tenant", "caller-env", "RULE_DEFINITION_READER"))
                .thenReturn(PRINCIPAL);

        controller.definitions(
                "caller-tenant", "caller-env", null, null, null, null, servletRequest);
        controller.definition(definitionId, "caller-tenant", "caller-env", servletRequest);
        controller.definitionTimeline(
                definitionId, "caller-tenant", "caller-env", servletRequest);
        controller.materializations(
                "caller-tenant", "caller-env", null, null, null, null, null, servletRequest);

        verify(resolver, org.mockito.Mockito.times(4)).resolve(
                servletRequest, "caller-tenant", "caller-env", "RULE_DEFINITION_READER");
        verify(service).definitions("tenant-a", "dev", null, null, null, null);
        verify(service).definition(definitionId, PRINCIPAL);
        verify(service).definitionTimeline(definitionId, "tenant-a", "dev");
        verify(service).materializations("tenant-a", "dev", null, null, null, null, null);
    }

    @Test
    void corporateDefinitionReadAcceptsDefinitionReaderWithoutSnapshotReader() {
        DomainRuleService service = mock(DomainRuleService.class);
        AiPrincipalContextResolver contextResolver = mock(AiPrincipalContextResolver.class);
        HttpServletRequest servletRequest = servletRequest();
        when(contextResolver.resolve(servletRequest, "caller-tenant", null, "caller-env"))
                .thenReturn(new AiPrincipalContext("trusted-tenant", "iam-reader", "prod", true));
        when(servletRequest.isUserInRole("RULE_DEFINITION_READER")).thenReturn(true);
        when(servletRequest.isUserInRole("RULE_SNAPSHOT_READER")).thenReturn(false);
        when(service.definitions("trusted-tenant", "prod", null, null, null, null))
                .thenReturn(List.of());
        DomainRuleController controller = new DomainRuleController(
                service, new DomainRuleGovernancePrincipalResolver(contextResolver, true));

        var response = controller.definitions(
                "caller-tenant", "caller-env", null, null, null, null, servletRequest);

        assertThat(response.getBody()).isEmpty();
        verify(service).definitions("trusted-tenant", "prod", null, null, null, null);
    }

    @Test
    void corporateDefinitionReadRejectsSnapshotReaderWithoutDefinitionReader() {
        DomainRuleService service = mock(DomainRuleService.class);
        AiPrincipalContextResolver contextResolver = mock(AiPrincipalContextResolver.class);
        HttpServletRequest servletRequest = servletRequest();
        when(contextResolver.resolve(servletRequest, "caller-tenant", null, "caller-env"))
                .thenReturn(new AiPrincipalContext("trusted-tenant", "iam-snapshot-reader", "prod", true));
        when(servletRequest.isUserInRole("RULE_DEFINITION_READER")).thenReturn(false);
        when(servletRequest.isUserInRole("RULE_SNAPSHOT_READER")).thenReturn(true);
        DomainRuleController controller = new DomainRuleController(
                service, new DomainRuleGovernancePrincipalResolver(contextResolver, true));

        assertThatThrownBy(() -> controller.definitions(
                "caller-tenant", "caller-env", null, null, null, null, servletRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RULE_DEFINITION_READER");
    }

    private DomainRuleController controller(DomainRuleService service) {
        DomainRuleGovernancePrincipalResolver resolver = mock(DomainRuleGovernancePrincipalResolver.class);
        when(resolver.resolve(
                org.mockito.ArgumentMatchers.any(HttpServletRequest.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(PRINCIPAL);
        return new DomainRuleController(service, resolver);
    }

    private HttpServletRequest servletRequest() {
        return mock(HttpServletRequest.class);
    }

    private DomainRuleDefinitionResponse definitionResponse(UUID id, String ruleKey, int version) {
        return new DomainRuleDefinitionResponse(
                id, "tenant-a", "dev", ruleKey, version, "validation", "draft",
                null, null, null, null, null, null, null, null, null, null, null, null,
                "authenticated", "agent", null, null, null, null, null);
    }
}
