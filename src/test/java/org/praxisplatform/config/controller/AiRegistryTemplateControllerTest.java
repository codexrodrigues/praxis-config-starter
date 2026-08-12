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
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateRevision;
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
    var etag = "123e4567-e89b-12d3-a456-426614174000";
    AiRegistry config =
        AiRegistry.builder()
            .registryKey("praxis-table")
            .version(7L)
            .etag(java.util.UUID.fromString(etag))
            .payload("{\"unused\":true}")
            .build();
    when(service.getTemplate("praxis-table")).thenReturn(Optional.of(config));
    when(service.toRecord(config))
        .thenReturn(
            AiRegistryTemplateRecord.builder()
                .componentId("praxis-table")
                .aiDescription("Tabela operacional")
                .configJson(objectMapper.readTree("{\"columns\":[{\"field\":\"name\"}]}"))
                .templateMeta(objectMapper.readTree("{\"source\":\"recipe\"}"))
                .revision(
                    AiRegistryTemplateRevision.builder()
                        .version(7L)
                        .etag(etag)
                        .configSha256("a".repeat(64))
                        .build())
                .build());

    mockMvc
        .perform(get("/api/praxis/config/ai-registry/templates/praxis-table"))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", '"' + etag + '"'))
        .andExpect(jsonPath("$.componentId").value("praxis-table"))
        .andExpect(jsonPath("$.aiDescription").value("Tabela operacional"))
        .andExpect(jsonPath("$.configJson.columns[0].field").value("name"))
        .andExpect(jsonPath("$.templateMeta.source").value("recipe"))
        .andExpect(jsonPath("$.revision.version").value(7))
        .andExpect(jsonPath("$.revision.etag").value(etag))
        .andExpect(jsonPath("$.revision.configSha256").value("a".repeat(64)));

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
    when(service.toRecord(malformed))
        .thenReturn(AiRegistryTemplateRecord.builder().componentId("broken-template").build());

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
    when(service.toRecord(saved))
        .thenReturn(
            AiRegistryTemplateRecord.builder()
                .componentId("praxis-table")
                .aiDescription("Tabela compacta")
                .configJson(payload.get("configJson"))
                .templateMeta(payload.get("templateMeta"))
                .revision(
                    AiRegistryTemplateRevision.builder()
                        .version(2L)
                        .etag("123e4567-e89b-12d3-a456-426614174002")
                        .configSha256("b".repeat(64))
                        .build())
                .build());

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
        .andExpect(jsonPath("$.revision.version").value(2))
        .andExpect(jsonPath("$.revision.configSha256").value("b".repeat(64)))
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
    when(service.toRecord(saved))
        .thenReturn(
            AiRegistryTemplateRecord.builder()
                .componentId("praxis-table")
                .aiDescription("Tabela")
                .configJson(objectMapper.readTree("{\"columns\":[]}"))
                .build());

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
