package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.service.AiInteractionLogger;
import org.praxisplatform.config.service.AiOrchestratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class AiOrchestratorControllerTest {

    private static final String HASH =
            "51b7901f1df633d89fc019a2e41f675cc5b87b135dfc8335aa96e53205034b26";

    @Mock
    private AiOrchestratorService orchestratorService;

    @Mock
    private AiInteractionLogger interactionLogger;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiOrchestratorController controller;

    @BeforeEach
    void setUp() {
        controller = new AiOrchestratorController(orchestratorService, interactionLogger);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/praxis/config/ai/patch");
        servletRequest.setScheme("http");
        servletRequest.setServerName("localhost");
        servletRequest.setServerPort(8088);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldApplyContractFromBodyAndReturnResponseHeaders() {
        AiOrchestratorRequest request = baseRequest();
        request.setContractVersion("v1.1");
        request.setSchemaHash(HASH);

        AiOrchestratorResponse serviceResponse = AiOrchestratorResponse.builder().type("patch").build();
        when(orchestratorService.generatePatch(eq(request), anyString(), eq("t1"), eq("u1"), eq("dev")))
                .thenReturn(serviceResponse);

        ResponseEntity<AiOrchestratorResponse> response = controller.generatePatch(
                request,
                null,
                "t1",
                "u1",
                "dev",
                "ignored-version",
                "ignored-hash");

        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_VERSION_HEADER))
                .isEqualTo("v1.1");
        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(HASH);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContractVersion()).isEqualTo("v1.1");
        assertThat(response.getBody().getSchemaHash()).isEqualTo(HASH);
        verify(interactionLogger).logFrontendResponse(request, serviceResponse);
    }

    @Test
    void shouldUseHeadersWhenBodyContractFieldsAreMissing() {
        AiOrchestratorRequest request = baseRequest();
        AiOrchestratorResponse serviceResponse = AiOrchestratorResponse.builder().type("patch").build();
        when(orchestratorService.generatePatch(eq(request), anyString(), eq("t1"), eq("u1"), eq("dev")))
                .thenReturn(serviceResponse);

        ResponseEntity<AiOrchestratorResponse> response = controller.generatePatch(
                request,
                null,
                "t1",
                "u1",
                "dev",
                "v1.1",
                HASH);

        assertThat(request.getContractVersion()).isEqualTo("v1.1");
        assertThat(request.getSchemaHash()).isEqualTo(HASH);
        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_VERSION_HEADER))
                .isEqualTo("v1.1");
        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(HASH);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContractVersion()).isEqualTo("v1.1");
        assertThat(response.getBody().getSchemaHash()).isEqualTo(HASH);
    }

    @Test
    void shouldFallbackToDefaultContractMetadata() {
        AiOrchestratorRequest request = baseRequest();
        AiOrchestratorResponse serviceResponse = AiOrchestratorResponse.builder().type("patch").build();
        when(orchestratorService.generatePatch(eq(request), anyString(), eq("t1"), eq("u1"), eq("dev")))
                .thenReturn(serviceResponse);

        ResponseEntity<AiOrchestratorResponse> response = controller.generatePatch(
                request,
                null,
                "t1",
                "u1",
                "dev",
                null,
                null);

        assertThat(request.getContractVersion()).isEqualTo(AiOrchestratorController.DEFAULT_CONTRACT_VERSION);
        assertThat(request.getSchemaHash()).isEqualTo(AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH);
        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_VERSION_HEADER))
                .isEqualTo(AiOrchestratorController.DEFAULT_CONTRACT_VERSION);
        assertThat(response.getHeaders().getFirst(AiOrchestratorController.CONTRACT_SCHEMA_HASH_HEADER))
                .isEqualTo(AiOrchestratorController.DEFAULT_CONTRACT_SCHEMA_HASH);
    }

    private AiOrchestratorRequest baseRequest() {
        return AiOrchestratorRequest.builder()
                .componentId("praxis-table")
                .componentType("table")
                .currentState(objectMapper.createObjectNode())
                .build();
    }
}
