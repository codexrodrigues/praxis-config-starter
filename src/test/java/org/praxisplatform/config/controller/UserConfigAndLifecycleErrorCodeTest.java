package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.SensitiveTenantConfigGuard;
import org.praxisplatform.config.service.UiConfigWriteDeniedException;
import org.praxisplatform.config.service.UiLayoutLifecycleException;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class UserConfigAndLifecycleErrorCodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void userConfigControllerPreservesErrorCodeInProblemDetailFor422And403() throws Exception {
        UserConfigService userConfigService = mock(UserConfigService.class);
        AiApiKeyProtectionService apiKeyProtectionService = mock(AiApiKeyProtectionService.class);
        SensitiveTenantConfigGuard guard = new SensitiveTenantConfigGuard(true, List.of(), List.of());

        UserConfigController guardedController =
                new UserConfigController(
                        userConfigService, MAPPER, apiKeyProtectionService, guard::assertAllowed);
        MockMvc guardedMvc = MockMvcBuilders.standaloneSetup(guardedController).build();

        ObjectNode sensitiveRequest = MAPPER.createObjectNode();
        sensitiveRequest.putObject("payload").putObject("criteria").put("cpf", "12345678901");

        guardedMvc
                .perform(
                        put("/api/praxis/config/ui/praxis-table/employees")
                                .header("X-Tenant-ID", "tenant-a")
                                .header("X-Env", "local")
                                .param("scope", "tenant")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MAPPER.writeValueAsString(sensitiveRequest)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(
                        jsonPath("$.errorCode")
                                .value(SensitiveTenantConfigGuard.SENSITIVE_CRITERIA_ERROR_CODE))
                .andExpect(jsonPath("$.detail").isNotEmpty());

        UserConfigController forbiddenController =
                new UserConfigController(
                        userConfigService,
                        MAPPER,
                        apiKeyProtectionService,
                        req -> {
                            throw new UiConfigWriteDeniedException(
                                    HttpStatus.FORBIDDEN,
                                    "TENANT_SHARED_CONFIG_WRITE_FORBIDDEN",
                                    "Actor lacks TENANT_ADMIN authority.");
                        });
        MockMvc forbiddenMvc = MockMvcBuilders.standaloneSetup(forbiddenController).build();

        ObjectNode safeRequest = MAPPER.createObjectNode();
        safeRequest.putObject("payload").putObject("criteria").put("status", "ACTIVE");

        forbiddenMvc
                .perform(
                        put("/api/praxis/config/ui/praxis-table/employees")
                                .header("X-Tenant-ID", "tenant-a")
                                .param("scope", "tenant")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(MAPPER.writeValueAsString(safeRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_SHARED_CONFIG_WRITE_FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Actor lacks TENANT_ADMIN authority."));
    }

    @Test
    void uiLayoutResolutionExceptionHandlerPreservesDeclaredLifecycleErrorCode() {
        UiLayoutResolutionExceptionHandler handler = new UiLayoutResolutionExceptionHandler();

        UiLayoutLifecycleException withDeclaredErrorCode =
                new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.INVALID_STATE,
                        "CORPORATE_PAGE_WRITE_DENIED",
                        "Write transition blocked by host policy.");
        ResponseEntity<ProblemDetail> declaredResponse = handler.lifecycle(withDeclaredErrorCode);
        assertThat(declaredResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(declaredResponse.getBody()).isNotNull();
        assertThat(declaredResponse.getBody().getProperties())
                .containsEntry("errorCode", "CORPORATE_PAGE_WRITE_DENIED")
                .containsEntry("code", "INVALID_STATE");
        assertThat(declaredResponse.getBody().getDetail())
                .isEqualTo("The lifecycle operation is not valid for the current state.");

        UiLayoutLifecycleException withoutCustomErrorCode =
                new UiLayoutLifecycleException(
                        UiLayoutLifecycleException.Code.VALIDATION_FAILED,
                        "Schema validation failed.");
        ResponseEntity<ProblemDetail> fallbackResponse = handler.lifecycle(withoutCustomErrorCode);
        assertThat(fallbackResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(fallbackResponse.getBody()).isNotNull();
        assertThat(fallbackResponse.getBody().getProperties())
                .containsEntry("code", "VALIDATION_FAILED")
                .doesNotContainKey("errorCode");
    }
}
