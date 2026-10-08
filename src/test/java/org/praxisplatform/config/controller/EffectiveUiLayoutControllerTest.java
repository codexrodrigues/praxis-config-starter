package org.praxisplatform.config.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.AppliedUiLayoutLayer;
import org.praxisplatform.config.dto.EffectiveUiLayoutResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.UiLayoutResolutionException;
import org.praxisplatform.config.service.EffectiveUiLayoutResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class EffectiveUiLayoutControllerTest {
    private static final UiLayoutTarget TARGET = new UiLayoutTarget("praxis-table", "table-config:orders");
    @Mock private EffectiveUiLayoutResolver service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EffectiveUiLayoutController(service))
                .setControllerAdvice(new UiLayoutResolutionExceptionHandler())
                .build();
    }

    @Test
    void returnsEffectiveDocumentWithPrivateRevalidationContract() throws Exception {
        when(service.resolve(any(), eq("ctx-1"), eq(TARGET))).thenReturn(Optional.of(response()));

        mvc.perform(get("/api/praxis/config/ui-layouts/effective")
                        .principal(() -> "authenticated")
                        .header("X-Praxis-Context-Version", "ctx-1")
                        .param("componentType", TARGET.componentType())
                        .param("componentId", TARGET.componentId()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"etag-1\""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"))
                .andExpect(jsonPath("$.document.density").value("compact"))
                .andExpect(jsonPath("$.appliedLayers[0].revisionRef").value("opaque-revision"))
                .andExpect(jsonPath("$.groups").doesNotExist());
        verify(service).resolve(any(), eq("ctx-1"), eq(TARGET));
    }

    @Test
    void supportsWeakConditionalReadAndExactNotFound() throws Exception {
        when(service.resolve(any(), eq("ctx-1"), eq(TARGET)))
                .thenReturn(Optional.of(response()), Optional.empty());
        mvc.perform(request().principal(() -> "authenticated").header(HttpHeaders.IF_NONE_MATCH, "W/\"etag-1\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, "\"etag-1\""));
        mvc.perform(request().principal(() -> "authenticated"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }

    @Test
    void rejectsMissingPrincipalAndMalformedConditionalHeader() throws Exception {
        mvc.perform(request())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Authenticate again before resolving this layout."));

        when(service.resolve(any(), eq("ctx-1"), eq(TARGET))).thenReturn(Optional.of(response()));
        mvc.perform(request().principal(() -> "authenticated").header(HttpHeaders.IF_NONE_MATCH, "etag-1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_LAYOUT_REQUEST"));
    }

    @Test
    void mapsMissingContextHeaderToTheStableRequestError() throws Exception {
        mvc.perform(get("/api/praxis/config/ui-layouts/effective")
                        .principal(() -> "authenticated")
                        .param("componentType", TARGET.componentType())
                        .param("componentId", TARGET.componentId()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_LAYOUT_REQUEST"));
    }

    @Test
    void mapsResolutionFailuresWithoutPrivateDetails() throws Exception {
        when(service.resolve(any(), eq("ctx-1"), eq(TARGET)))
                .thenThrow(new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.LAYOUT_RESOLUTION_AMBIGUOUS,
                        "private selector and payload values",
                        "/density"));

        mvc.perform(get("/api/praxis/config/ui-layouts/effective")
                        .principal(() -> "authenticated")
                        .header("X-Praxis-Context-Version", "ctx-1")
                        .param("componentType", TARGET.componentType())
                        .param("componentId", TARGET.componentId()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("LAYOUT_RESOLUTION_AMBIGUOUS"))
                .andExpect(jsonPath("$.path").value("/density"))
                .andExpect(jsonPath("$.detail").value("Published layout inputs are ambiguous."));
    }

    @Test
    void mapsHostAuthorizationDenialWithoutDisclosingAudienceFacts() throws Exception {
        when(service.resolve(any(), eq("ctx-1"), eq(TARGET)))
                .thenThrow(new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.LAYOUT_ACCESS_DENIED,
                        "private capability and audience values",
                        null));

        mvc.perform(request().principal(() -> "authenticated"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("LAYOUT_ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail")
                        .value("The current enterprise context cannot consume this layout."))
                .andExpect(jsonPath("$.groups").doesNotExist());
    }

    @Test
    void distinguishesExpiredSessionFromUnauthorizedEnterpriseContext() throws Exception {
        when(service.resolve(any(), eq("ctx-1"), eq(TARGET)))
                .thenThrow(new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.SESSION_REQUIRED, "private session detail", null),
                        new UiLayoutResolutionException(
                                UiLayoutResolutionException.Code.CONTEXT_ACCESS_DENIED,
                                "private membership detail", null));

        mvc.perform(request().principal(() -> "authenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
        mvc.perform(request().principal(() -> "authenticated"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTEXT_ACCESS_DENIED"));
    }

    private EffectiveUiLayoutResponse response() throws Exception {
        return new EffectiveUiLayoutResponse(
                "praxis.effective-ui-layout/v1",
                TARGET,
                "ctx-1",
                new ObjectMapper().readTree("{\"density\":\"compact\"}"),
                "etag-1",
                List.of(new AppliedUiLayoutLayer("TENANT", "opaque-revision", "hash", (short) 0, null)),
                List.of(),
                Instant.parse("2026-09-10T12:00:00Z"));
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/praxis/config/ui-layouts/effective")
                .header("X-Praxis-Context-Version", "ctx-1")
                .param("componentType", TARGET.componentType())
                .param("componentId", TARGET.componentId());
    }
}
