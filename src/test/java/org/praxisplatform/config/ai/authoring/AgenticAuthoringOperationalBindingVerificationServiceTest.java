package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ResourceActionCatalogFetchResult;
import org.praxisplatform.config.service.ResourceActionCatalogRetrievalService;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;

@Tag("unit")
class AgenticAuthoringOperationalBindingVerificationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringDomainBindingService bindingService =
            mock(AgenticAuthoringDomainBindingService.class);
    private final SchemaRetrievalService schemaService = mock(SchemaRetrievalService.class);
    private final ResourceCapabilitiesRetrievalService capabilitiesService =
            mock(ResourceCapabilitiesRetrievalService.class);
    private final ResourceActionCatalogRetrievalService actionCatalogService =
            mock(ResourceActionCatalogRetrievalService.class);
    private final AgenticAuthoringOperationalBindingVerificationService service =
            new AgenticAuthoringOperationalBindingVerificationService(
                    bindingService, schemaService, capabilitiesService, actionCatalogService);

    @BeforeEach
    void publishesAnEmptyCanonicalActionCatalogByDefault() {
        when(actionCatalogService.fetchCatalogResult(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String resourceKey = invocation.getArgument(0);
                    var catalog = objectMapper.createObjectNode();
                    if (resourceKey == null) {
                        return ResourceActionCatalogFetchResult.success(catalog, "action-catalog-url");
                    }
                    catalog.put("resourceKey", resourceKey);
                    catalog.put("resourcePath", "/api/" + resourceKey.replace('.', '/'));
                    catalog.putArray("actions");
                    return ResourceActionCatalogFetchResult.success(catalog, "action-catalog-url");
                });
    }

    @Test
    void verifiesExactSchemaAndPrincipalCapabilityForGovernedBinding() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management",
                        "api_resource",
                        "resource:human-resources.funcionarios",
                        "human-resources.funcionarios",
                        "/api/funcionarios",
                        "GET",
                        "/schemas/filtered?path=/api/funcionarios&operation=get&schemaType=response",
                        1.0,
                        "hr-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        AgenticAuthoringDomainBindingService.BindingProjection resourceBinding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management",
                        "api_resource",
                        "resource:human-resources.funcionarios",
                        "human-resources.funcionarios",
                        "/api/human-resources/funcionarios",
                        "GET",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=get&schemaType=response",
                        1.0,
                        "hr-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "human-resources.funcionarios", 12))
                .thenReturn(List.of(binding, resourceBinding));
        var actionCatalog = objectMapper.createObjectNode();
        actionCatalog.put("resourceKey", "human-resources.funcionarios");
        actionCatalog.put("resourcePath", "/api/funcionarios");
        actionCatalog.putArray("actions");
        when(actionCatalogService.fetchCatalogResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceActionCatalogFetchResult.success(actionCatalog, "action-catalog-url"));
        when(schemaService.fetchSchemaResult(
                any(), eq("http://localhost"), eq("tenant"), eq("user"), eq("dev")))
                .thenReturn(SchemaFetchResult.success(
                        new ObjectMapper().readTree("{\"type\":\"array\"}"),
                        "http://localhost/schemas/filtered?path=%2Fapi%2Ffuncionarios&operation=get&schemaType=response"));
        when(capabilitiesService.fetchCapabilitiesResult(
                "/api/funcionarios", "http://localhost", "tenant", "user", "dev"))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        new ObjectMapper().readTree("""
                                {
                                  "data": {
                                    "operations": {
                                      "list": {
                                        "supported": true,
                                        "availability": {"allowed": true}
                                      }
                                    }
                                  }
                                }
                                """),
                        "http://localhost/api/funcionarios/capabilities"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "human-resources.funcionarios",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isTrue();
        assertThat(result.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.apiMethod()).isEqualTo("get");
            assertThat(operation.schemaType()).isEqualTo("response");
            assertThat(operation.operationId()).isEqualTo("list");
            assertThat(operation.verificationMode()).isEqualTo("principal_capability");
        });
    }

    @Test
    void blocksWhenPrincipalCapabilityDeniesTheBoundOperation() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management", "api_resource", "employee-resource",
                        "human-resources.funcionarios", "/api/funcionarios", "POST", null,
                        1.0, "hr-v1", List.of("active-evidence"));
        when(bindingService.resolve("tenant", "dev", "human-resources.funcionarios", 12))
                .thenReturn(List.of(binding));
        when(schemaService.fetchSchemaResult(any(), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(
                        new ObjectMapper().readTree("{\"type\":\"object\"}"), "schema-url"));
        when(capabilitiesService.fetchCapabilitiesResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        new ObjectMapper().readTree("""
                                {"operations":{"create":{"supported":true,"availability":{"allowed":false}}}}
                                """),
                        "capabilities-url"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "human-resources.funcionarios",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCodes()).contains("operational-binding-operation-unavailable");
    }

    @Test
    void verifiesExplicitPostFilterOperationWithoutConfusingItWithCreate() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management",
                        "api_operation",
                        "cursor",
                        "human-resources.funcionarios",
                        "/api/human-resources/funcionarios/filter/cursor",
                        "POST",
                        "/schemas/filtered?path=/api/human-resources/funcionarios/filter/cursor&operation=post&schemaType=response",
                        1.0,
                        "hr-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        AgenticAuthoringDomainBindingService.BindingProjection resourceBinding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management",
                        "api_resource",
                        "resource:human-resources.funcionarios",
                        "human-resources.funcionarios",
                        "/api/human-resources/funcionarios",
                        "GET",
                        "/schemas/filtered?path=/api/human-resources/funcionarios&operation=get&schemaType=response",
                        1.0,
                        "hr-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "human-resources.funcionarios", 12))
                .thenReturn(List.of(binding, resourceBinding));
        when(schemaService.fetchSchemaResult(any(), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(
                        new ObjectMapper().readTree("{\"type\":\"array\"}"), "schema-url"));
        when(capabilitiesService.fetchCapabilitiesResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        new ObjectMapper().readTree("""
                                {
                                  "operations": {
                                    "create": {"supported":true,"availability":{"allowed":false}},
                                    "cursor": {"supported":true,"availability":{"allowed":true}}
                                  }
                                }
                                """),
                        "capabilities-url"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "human-resources.funcionarios",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isTrue();
        assertThat(result.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.apiMethod()).isEqualTo("post");
            assertThat(operation.operationId()).isEqualTo("cursor");
            assertThat(operation.schemaType()).isEqualTo("response");
            assertThat(operation.resourcePath()).isEqualTo("/api/human-resources/funcionarios");
            assertThat(operation.apiPath()).endsWith("/filter/cursor");
        });
        var schemaContext = org.mockito.ArgumentCaptor.forClass(AiSchemaContext.class);
        org.mockito.Mockito.verify(schemaService, org.mockito.Mockito.times(2))
                .fetchSchemaResult(schemaContext.capture(), any(), any(), any(), any());
        assertThat(schemaContext.getAllValues())
                .filteredOn(context -> context.getPath().endsWith("/filter/cursor"))
                .singleElement()
                .extracting(AiSchemaContext::getSchemaType)
                .isEqualTo("response");
    }

    @Test
    void verifiesNativeDomainCatalogUiSurfaceAgainstCanonicalResourceCapabilities() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "human-resources.ferias-afastamentos.surface.absence-calendar-board",
                        "ui_surface",
                        "binding:human-resources.ferias-afastamentos.surface.absence-calendar-board:ui-surface",
                        "human-resources.ferias-afastamentos",
                        "/api/human-resources/ferias-afastamentos/filter",
                        "POST",
                        null,
                        1.0,
                        "hr-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "human-resources.ferias-afastamentos", 12))
                .thenReturn(List.of(binding));
        when(schemaService.fetchSchemaResult(any(), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(
                        new ObjectMapper().readTree("{\"type\":\"object\"}"), "schema-url"));
        when(capabilitiesService.fetchCapabilitiesResult(
                "/api/human-resources/ferias-afastamentos",
                "http://localhost",
                "tenant",
                "user",
                "dev"))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        new ObjectMapper().readTree("""
                                {
                                  "operations": {
                                    "create": {"supported":true,"availability":{"allowed":true}},
                                    "filter": {"supported":true,"availability":{"allowed":true}}
                                  }
                                }
                                """),
                        "capabilities-url"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "human-resources.ferias-afastamentos",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isTrue();
        assertThat(result.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.operationId()).isEqualTo("filter");
            assertThat(operation.resourcePath())
                    .isEqualTo("/api/human-resources/ferias-afastamentos");
            assertThat(operation.apiPath())
                    .isEqualTo("/api/human-resources/ferias-afastamentos/filter");
        });
    }

    @Test
    void verifiesNativeWorkflowActionByItsCanonicalActionId() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "operations.missoes.action.start",
                        "workflow_action",
                        "binding:operations.missoes.action.start:workflow-action",
                        "start",
                        "operations.missoes",
                        "/api/operations/missoes/{id}/actions/start",
                        "POST",
                        null,
                        1.0,
                        "operations-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "operations.missoes", 12))
                .thenReturn(List.of(binding));
        when(schemaService.fetchSchemaResult(any(), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(
                        new ObjectMapper().readTree("{\"type\":\"object\"}"), "schema-url"));
        when(actionCatalogService.fetchCatalogResult(
                "operations.missoes", "http://localhost", "tenant", "user", "dev"))
                .thenReturn(ResourceActionCatalogFetchResult.success(
                        new ObjectMapper().readTree("""
                                {
                                  "resourceKey": "operations.missoes",
                                  "resourcePath": "/api/operations/missoes",
                                  "actions": [{
                                    "id": "start",
                                    "resourceKey": "operations.missoes",
                                    "scope": "ITEM",
                                    "operationId": "startMission",
                                    "path": "/api/operations/missoes/{id}/actions/start",
                                    "method": "POST",
                                    "availability": {
                                      "allowed": false,
                                      "reason": "resource-context-required"
                                    }
                                  }]
                                }
                                """),
                        "http://localhost/schemas/actions?resource=operations.missoes"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "operations.missoes",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isTrue();
        assertThat(result.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.kind()).isEqualTo("workflow_action");
            assertThat(operation.actionId()).isEqualTo("start");
            assertThat(operation.operationId()).isEqualTo("startMission");
            assertThat(operation.scope()).isEqualTo("ITEM");
            assertThat(operation.verificationMode()).isEqualTo("runtime_action_discovery");
            assertThat(operation.availability().allowed()).isFalse();
            assertThat(operation.availability().reason()).isEqualTo("resource-context-required");
            assertThat(operation.availability().resolution())
                    .isEqualTo("item_capabilities_at_selection");
            assertThat(operation.apiPath()).endsWith("/{id}/actions/start");
        });
        verifyNoInteractions(capabilitiesService);
    }

    @Test
    void rejectsItemActionWhoseCatalogScopeAndPathDiverge() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "operations.missoes.action.start",
                        "workflow_action",
                        "binding:operations.missoes.action.start:workflow-action",
                        "start",
                        "operations.missoes",
                        "/api/operations/missoes/{id}/actions/start",
                        "POST",
                        null,
                        1.0,
                        "operations-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "operations.missoes", 12))
                .thenReturn(List.of(binding));
        when(actionCatalogService.fetchCatalogResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceActionCatalogFetchResult.success(
                        new ObjectMapper().readTree("""
                                {
                                  "resourceKey": "operations.missoes",
                                  "resourcePath": "/api/operations/missoes",
                                  "actions": [{
                                    "id": "start",
                                    "resourceKey": "operations.missoes",
                                    "scope": "ITEM",
                                    "operationId": "startMission",
                                    "path": "/api/operations/missoes/actions/start",
                                    "method": "POST",
                                    "availability": {
                                      "allowed": false,
                                      "reason": "resource-context-required"
                                    }
                                  }]
                                }
                                """),
                        "action-catalog-url"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "operations.missoes",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCodes())
                .contains("operational-binding-action-catalog-scope-mismatch");
        verifyNoInteractions(schemaService, capabilitiesService);
    }

    @Test
    void doesNotTreatARealItemDenialAsMissingContext() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "operations.missoes.action.start",
                        "workflow_action",
                        "binding:operations.missoes.action.start:workflow-action",
                        "start",
                        "operations.missoes",
                        "/api/operations/missoes/{id}/actions/start",
                        "POST",
                        null,
                        1.0,
                        "operations-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "operations.missoes", 12))
                .thenReturn(List.of(binding));
        when(actionCatalogService.fetchCatalogResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceActionCatalogFetchResult.success(
                        new ObjectMapper().readTree("""
                                {
                                  "resourceKey": "operations.missoes",
                                  "resourcePath": "/api/operations/missoes",
                                  "actions": [{
                                    "id": "start",
                                    "resourceKey": "operations.missoes",
                                    "scope": "ITEM",
                                    "operationId": "startMission",
                                    "path": "/api/operations/missoes/{id}/actions/start",
                                    "method": "POST",
                                    "availability": {
                                      "allowed": false,
                                      "reason": "missing-authority"
                                    }
                                  }]
                                }
                                """),
                        "action-catalog-url"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "operations.missoes",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCodes())
                .contains("operational-binding-action-catalog-item-context-invalid");
        verifyNoInteractions(schemaService, capabilitiesService);
    }

    @Test
    void blocksAValidResourceOperationWhenTheActionCatalogIsForbidden() throws Exception {
        stubValidResourceOperation();
        when(actionCatalogService.fetchCatalogResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceActionCatalogFetchResult.failure(
                        ResourceActionCatalogFetchResult.Status.FORBIDDEN,
                        403,
                        "action-catalog-url",
                        "RESOURCE_ACTION_CATALOG_ACCESS_DENIED",
                        "forbidden"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "operations.missoes",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isFalse();
        assertThat(result.operations()).isEmpty();
        assertThat(result.failureCodes()).contains("operational-binding-action-catalog-forbidden");
    }

    @Test
    void acceptsCanonicalActionCatalogNotFoundForAResourceWithoutWorkflowActions() throws Exception {
        stubValidResourceOperation();
        when(actionCatalogService.fetchCatalogResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceActionCatalogFetchResult.failure(
                        ResourceActionCatalogFetchResult.Status.NOT_FOUND,
                        404,
                        "action-catalog-url",
                        "RESOURCE_ACTION_CATALOG_NOT_FOUND",
                        "resource has no workflow actions"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "operations.missoes",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.kind()).isEqualTo("resource_operation");
            assertThat(operation.operationId()).isEqualTo("list");
        });
    }

    @Test
    void blocksAValidResourceOperationWhenTheActionCatalogContractIsInvalid() throws Exception {
        stubValidResourceOperation();
        when(actionCatalogService.fetchCatalogResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceActionCatalogFetchResult.failure(
                        ResourceActionCatalogFetchResult.Status.INVALID_RESPONSE,
                        200,
                        "action-catalog-url",
                        "RESOURCE_ACTION_CATALOG_INVALID_RESPONSE",
                        "invalid response"));

        AgenticAuthoringOperationalBindingVerificationService.VerificationResult result = service.verify(
                "operations.missoes",
                "http://localhost",
                new AiPrincipalContext("tenant", "user", "dev", true));

        assertThat(result.verified()).isFalse();
        assertThat(result.operations()).isEmpty();
        assertThat(result.failureCodes()).contains("operational-binding-action-catalog-invalid_response");
    }

    private void stubValidResourceOperation() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "operations.missoes",
                        "api_resource",
                        "resource:operations.missoes",
                        "operations.missoes",
                        "/api/operations/missoes",
                        "GET",
                        null,
                        1.0,
                        "operations-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "operations.missoes", 12))
                .thenReturn(List.of(binding));
        when(schemaService.fetchSchemaResult(any(), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(
                        objectMapper.readTree("{\"type\":\"array\"}"), "schema-url"));
        when(capabilitiesService.fetchCapabilitiesResult(any(), any(), any(), any(), any()))
                .thenReturn(ResourceCapabilitiesFetchResult.success(
                        objectMapper.readTree("""
                                {"operations":{"list":{"supported":true,"availability":{"allowed":true}}}}
                                """),
                        "capabilities-url"));
    }
}
