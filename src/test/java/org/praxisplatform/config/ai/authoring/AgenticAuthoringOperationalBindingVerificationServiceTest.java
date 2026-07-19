package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;

@Tag("unit")
class AgenticAuthoringOperationalBindingVerificationServiceTest {

    private final AgenticAuthoringDomainBindingService bindingService =
            mock(AgenticAuthoringDomainBindingService.class);
    private final SchemaRetrievalService schemaService = mock(SchemaRetrievalService.class);
    private final ResourceCapabilitiesRetrievalService capabilitiesService =
            mock(ResourceCapabilitiesRetrievalService.class);
    private final AgenticAuthoringOperationalBindingVerificationService service =
            new AgenticAuthoringOperationalBindingVerificationService(
                    bindingService, schemaService, capabilitiesService);

    @Test
    void verifiesExactSchemaAndPrincipalCapabilityForGovernedBinding() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management",
                        "resource",
                        "resource:human-resources.funcionarios",
                        "human-resources.funcionarios",
                        "/api/funcionarios",
                        "GET",
                        "/schemas/filtered?path=/api/funcionarios&operation=get&schemaType=response",
                        1.0,
                        "hr-v1",
                        List.of("domain-knowledge:evidence-status:active"));
        when(bindingService.resolve("tenant", "dev", "human-resources.funcionarios", 12))
                .thenReturn(List.of(binding));
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
            assertThat(operation.capabilityOperationId()).isEqualTo("list");
        });
    }

    @Test
    void blocksWhenPrincipalCapabilityDeniesTheBoundOperation() throws Exception {
        AgenticAuthoringDomainBindingService.BindingProjection binding =
                new AgenticAuthoringDomainBindingService.BindingProjection(
                        "hr:employee-management", "resource", "employee-resource",
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
}
