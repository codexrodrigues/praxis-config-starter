package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.AiRegistryTemplateSearchResult;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.service.AiRegistryTemplateService;
import org.praxisplatform.config.service.AiIntelligenceReleaseService;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiRegistryTemplateControllerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private AiRegistryTemplateService service;
  @Mock private AiIntelligenceReleaseService releaseService;
  @Mock private CanonicalJsonHashService hashService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiRegistryTemplateController(service, releaseService, hashService)).build();
  }

  @Test
  void getTemplateReturnsMappedPayloadAndNotFoundWhenMissing() throws Exception {
    AiRegistry config =
        AiRegistry.builder().registryKey("praxis-table").payload("{\"unused\":true}").build();
    JsonNode payload =
        objectMapper.readTree(
            """
            {
              "aiDescription": "Tabela operacional",
              "configJson": { "columns": [{ "field": "name" }] },
              "templateMeta": { "source": "recipe" }
            }
            """);
    when(service.getTemplate("praxis-table")).thenReturn(Optional.of(config));
    when(service.parsePayload(config)).thenReturn(payload);

    mockMvc
        .perform(get("/api/praxis/config/ai-registry/templates/praxis-table"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.componentId").value("praxis-table"))
        .andExpect(jsonPath("$.aiDescription").value("Tabela operacional"))
        .andExpect(jsonPath("$.configJson.columns[0].field").value("name"))
        .andExpect(jsonPath("$.templateMeta.source").value("recipe"));

    when(service.getTemplate("missing-component")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/praxis/config/ai-registry/templates/missing-component"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getTemplateReturnsNullTemplateFieldsForMalformedPersistedPayload() throws Exception {
    AiRegistry malformed =
        AiRegistry.builder().registryKey("broken-template").payload("{not-json").build();
    when(service.getTemplate("broken-template")).thenReturn(Optional.of(malformed));
    when(service.parsePayload(malformed)).thenReturn(null);

    mockMvc
        .perform(get("/api/praxis/config/ai-registry/templates/broken-template"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.componentId").value("broken-template"))
        .andExpect(jsonPath("$.aiDescription").doesNotExist())
        .andExpect(jsonPath("$.configJson").doesNotExist())
        .andExpect(jsonPath("$.templateMeta").doesNotExist());
  }

  @Test
  void upsertReturnsResolvedPayloadAndPropagatesObjectValidationFailure() throws Exception {
    JsonNode payload =
        objectMapper.readTree(
            """
            {
              "aiDescription": "Tabela compacta",
              "configJson": { "columns": [{ "field": "id" }] },
              "templateMeta": { "rank": 1 }
            }
            """);
    AiRegistry saved = AiRegistry.builder().registryKey("praxis-table").payload("{}").build();
    when(service.upsertTemplate(eq("praxis-table"), any(), eq("Tabela compacta"), any()))
        .thenReturn(saved);
    when(service.parsePayload(saved)).thenReturn(payload);

    mockMvc
        .perform(
            put("/api/praxis/config/ai-registry/templates/praxis-table")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "aiDescription": "Tabela compacta",
                      "configJson": { "columns": [{ "field": "id" }] },
                      "templateMeta": { "rank": 1 }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.componentId").value("praxis-table"))
        .andExpect(jsonPath("$.aiDescription").value("Tabela compacta"))
        .andExpect(jsonPath("$.configJson.columns[0].field").value("id"))
        .andExpect(jsonPath("$.templateMeta.rank").value(1))
        .andExpect(jsonPath("$.status").value("upserted"));

    doThrow(new ConfigurationIngestionException("configJson must be a JSON object"))
        .when(service)
        .upsertTemplate(eq("praxis-table"), any(), eq("Invalid"), any());

    mockMvc
        .perform(
            put("/api/praxis/config/ai-registry/templates/praxis-table")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "aiDescription": "Invalid",
                      "configJson": []
                    }
                    """))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void bulkUpsertReportsPartialSuccessAndDiagnostics() throws Exception {
    when(service.upsertTemplate(eq("praxis-table"), any(), eq("Valid table"), any()))
        .thenReturn(AiRegistry.builder().registryKey("praxis-table").payload("{}").build());
    doThrow(new ConfigurationIngestionException("configJson must be a JSON object"))
        .when(service)
        .upsertTemplate(eq("praxis-list"), any(), eq("Invalid list"), any());

    mockMvc
        .perform(
            post("/api/praxis/config/ai-registry/templates/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "items": [
                        {
                          "componentId": "praxis-table",
                          "aiDescription": "Valid table",
                          "configJson": { "columns": [] }
                        },
                        {
                          "componentId": "praxis-list",
                          "aiDescription": "Invalid list",
                          "configJson": []
                        }
                      ]
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(1))
        .andExpect(jsonPath("$.failed").value(1))
        .andExpect(jsonPath("$.errors[0].componentId").value("praxis-list"))
        .andExpect(jsonPath("$.errors[0].reason").value("configJson must be a JSON object"));
  }

  @Test
  void deleteReturnsNotFoundOrNoContent() throws Exception {
    when(service.getTemplate("missing-component")).thenReturn(Optional.empty());

    mockMvc
        .perform(delete("/api/praxis/config/ai-registry/templates/missing-component"))
        .andExpect(status().isNotFound());

    AiRegistry config = AiRegistry.builder().registryKey("praxis-table").build();
    when(service.getTemplate("praxis-table")).thenReturn(Optional.of(config));

    mockMvc
        .perform(delete("/api/praxis/config/ai-registry/templates/praxis-table"))
        .andExpect(status().isNoContent());

    verify(service).deleteTemplate(config);
  }

  @Test
  void searchDelegatesComponentScopeAndLimitDefaultsToService() throws Exception {
    when(service.searchTemplates("metric cards", "praxis-table", 5))
        .thenReturn(
            List.of(
                AiRegistryTemplateSearchResult.builder()
                    .componentId("praxis-table")
                    .aiDescription("Tabela com metricas")
                    .similarityScore(0.88d)
                    .configJsonSnippet("{\"columns\":[]}")
                    .build()));

    mockMvc
        .perform(
            get("/api/praxis/config/ai-registry/templates/search")
                .param("query", "metric cards")
                .param("componentId", "praxis-table"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].componentId").value("praxis-table"))
        .andExpect(jsonPath("$[0].similarityScore").value(0.88d));

    verify(service).searchTemplates("metric cards", "praxis-table", 5);
  }

  @Test
  void templateEndpointsStayUnderConfigBoundaryWithoutLocalCorsBypass() throws Exception {
    RequestMapping mapping = AiRegistryTemplateController.class.getAnnotation(RequestMapping.class);
    assertThat(mapping.value()).containsExactly("/api/praxis/config/ai-registry/templates");
    assertThat(AiRegistryTemplateController.class.getAnnotation(CrossOrigin.class)).isNull();

    when(service.getTemplate("praxis-table")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/api/praxis/config/ai-registry/templates/praxis-table")
                .header("Origin", "http://localhost:4003"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

    AiRegistry saved = AiRegistry.builder().registryKey("praxis-table").payload("{}").build();
    when(service.upsertTemplate(eq("praxis-table"), any(), eq("Tabela"), any())).thenReturn(saved);
    when(service.parsePayload(saved))
        .thenReturn(objectMapper.readTree("{\"aiDescription\":\"Tabela\",\"configJson\":{\"columns\":[]}}"));

    mockMvc
        .perform(
            put("/api/praxis/config/ai-registry/templates/praxis-table")
                .header("Origin", "http://localhost:4003")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "aiDescription": "Tabela",
                      "configJson": { "columns": [] }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
