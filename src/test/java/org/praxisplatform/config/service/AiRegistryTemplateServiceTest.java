package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateSearchResult;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.projection.AiRegistryTemplateSearchProjection;
import org.praxisplatform.config.repository.AiRegistryRepository;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiRegistryTemplateServiceTest {

  private static final String REGISTRY_TYPE = "template";
  private static final String COMPONENT_TYPE = "template";
  private static final String SCOPE_KEY = "GLOBAL";

  @Mock private AiRegistryRepository repository;
  @Mock private EmbeddingService embeddingService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private AiRegistryTemplateService service;

  @BeforeEach
  void setUp() {
    service = new AiRegistryTemplateService(repository, objectMapper, embeddingService);
  }

  @Test
  void upsertTemplateRejectsNonObjectConfigJsonBeforeEmbeddingOrPersistence() throws Exception {
    JsonNode nonObjectConfig = objectMapper.readTree("[{\"field\":\"name\"}]");

    assertThatThrownBy(
            () -> service.upsertTemplate("praxis-table", nonObjectConfig, "Tabela", null))
        .isInstanceOf(ConfigurationIngestionException.class)
        .hasMessage("configJson must be a JSON object");

    verify(embeddingService, never()).embed(anyString());
    verify(repository, never()).save(any());
  }

  @Test
  void upsertTemplateCreatesSystemGlobalIdentityWithDefaultDescriptionAndPayload() throws Exception {
    JsonNode configJson = objectMapper.readTree("{\"columns\":[{\"field\":\"name\"}]}");
    when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
    when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
            REGISTRY_TYPE, "praxis-table", COMPONENT_TYPE, Scope.SYSTEM, SCOPE_KEY))
        .thenReturn(Optional.empty());
    when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

    AiRegistry saved = service.upsertTemplate("praxis-table", configJson, " ", null);

    assertThat(saved.getRegistryType()).isEqualTo(REGISTRY_TYPE);
    assertThat(saved.getRegistryKey()).isEqualTo("praxis-table");
    assertThat(saved.getComponentType()).isEqualTo(COMPONENT_TYPE);
    assertThat(saved.getScope()).isEqualTo(Scope.SYSTEM);
    assertThat(saved.getScopeKey()).isEqualTo(SCOPE_KEY);
    assertThat(saved.getEmbedding()).containsExactly(0.1f, 0.2f);

    JsonNode payload = objectMapper.readTree(saved.getPayload());
    assertThat(payload.path("componentId").asText()).isEqualTo("praxis-table");
    assertThat(payload.path("aiDescription").asText()).isEqualTo("Component praxis-table");
    assertThat(payload.path("configJson").path("columns").get(0).path("field").asText())
        .isEqualTo("name");
  }

  @Test
  void upsertTemplatePreservesStableIdentityAndUpdatesPayloadAndEmbedding() throws Exception {
    UUID id = UUID.randomUUID();
    JsonNode originalConfig = objectMapper.readTree("{\"columns\":[]}");
    AiRegistry existing =
        AiRegistry.builder()
            .id(id)
            .registryType(REGISTRY_TYPE)
            .registryKey("praxis-table")
            .componentType(COMPONENT_TYPE)
            .scope(Scope.SYSTEM)
            .scopeKey(SCOPE_KEY)
            .payload("{\"componentId\":\"praxis-table\",\"configJson\":{\"columns\":[]}}")
            .embedding(List.of(0.1f))
            .source("previous-source")
            .status("active")
            .build();

    when(embeddingService.embed(anyString())).thenReturn(List.of(0.7f, 0.8f));
    when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
            REGISTRY_TYPE, "praxis-table", COMPONENT_TYPE, Scope.SYSTEM, SCOPE_KEY))
        .thenReturn(Optional.of(existing));
    when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

    AiRegistry saved =
        service.upsertTemplate(
            "praxis-table", originalConfig, "Nova descricao", objectMapper.readTree("{\"rank\":1}"));

    assertThat(saved.getId()).isEqualTo(id);
    assertThat(saved.getRegistryType()).isEqualTo(REGISTRY_TYPE);
    assertThat(saved.getRegistryKey()).isEqualTo("praxis-table");
    assertThat(saved.getComponentType()).isEqualTo(COMPONENT_TYPE);
    assertThat(saved.getScope()).isEqualTo(Scope.SYSTEM);
    assertThat(saved.getScopeKey()).isEqualTo(SCOPE_KEY);
    assertThat(saved.getEmbedding()).containsExactly(0.7f, 0.8f);
    assertThat(saved.getSource()).isNull();
    assertThat(saved.getStatus()).isEqualTo("active");

    JsonNode payload = objectMapper.readTree(saved.getPayload());
    assertThat(payload.path("aiDescription").asText()).isEqualTo("Nova descricao");
    assertThat(payload.path("templateMeta").path("rank").asInt()).isEqualTo(1);
  }

  @Test
  void toRecordMapsExactPayloadAndReturnsNullTemplateFieldsForMalformedPayload() throws Exception {
    AiRegistry valid =
        AiRegistry.builder()
            .registryKey("praxis-dynamic-form")
            .payload(
                """
                {
                  "componentId": "praxis-dynamic-form",
                  "aiDescription": "Formulario base",
                  "configJson": { "fields": [{ "name": "email" }] },
                  "templateMeta": { "source": "recipe" }
                }
                """)
            .build();

    AiRegistryTemplateRecord record = service.toRecord(valid);

    assertThat(record.getComponentId()).isEqualTo("praxis-dynamic-form");
    assertThat(record.getAiDescription()).isEqualTo("Formulario base");
    assertThat(record.getConfigJson().path("fields").get(0).path("name").asText())
        .isEqualTo("email");
    assertThat(record.getTemplateMeta().path("source").asText()).isEqualTo("recipe");

    AiRegistry malformed =
        AiRegistry.builder().registryKey("broken-template").payload("{not-json").build();

    AiRegistryTemplateRecord malformedRecord = service.toRecord(malformed);

    assertThat(malformedRecord.getComponentId()).isEqualTo("broken-template");
    assertThat(malformedRecord.getAiDescription()).isNull();
    assertThat(malformedRecord.getConfigJson()).isNull();
    assertThat(malformedRecord.getTemplateMeta()).isNull();
  }

  @Test
  void searchTemplatesAppliesComponentScopeAndDefaultLimit() {
    when(embeddingService.embed(eq("cards with totals"), any())).thenReturn(List.of(0.3f, 0.4f));
    when(repository.findTemplatesByVectorSimilarity(
            REGISTRY_TYPE, "[0.3,0.4]", "praxis-table", 5))
        .thenReturn(
            List.of(
                projection(
                    "praxis-table",
                    "Tabela com totais",
                    "{\"columns\":[{\"field\":\"total\"}]}",
                    0.91d)));

    List<AiRegistryTemplateSearchResult> results =
        service.searchTemplates("cards with totals", "praxis-table", 0);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getComponentId()).isEqualTo("praxis-table");
    assertThat(results.get(0).getSimilarityScore()).isEqualTo(0.91d);
    assertThat(results.get(0).getConfigJsonSnippet()).contains("total");
  }

  @Test
  void searchTemplatesByPrefixReturnsEmptyForBlankPrefixAndNormalizesPrefixAndLimit() {
    assertThat(service.searchTemplatesByPrefix("variant", " ", 10)).isEmpty();
    verify(embeddingService, never()).embed(anyString(), any());

    when(embeddingService.embed(eq("variant"), any())).thenReturn(List.of(0.5f));
    when(repository.findTemplatesByVectorSimilarityAndPrefix(REGISTRY_TYPE, "[0.5]", "praxis-table:%", 5))
        .thenReturn(
            List.of(
                projection(
                    "praxis-table:finance",
                    "Tabela financeira",
                    "{\"columns\":[{\"field\":\"amount\"}]}",
                    null)));

    List<AiRegistryTemplateSearchResult> results =
        service.searchTemplatesByPrefix("variant", "praxis-table:", -1);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getComponentId()).isEqualTo("praxis-table:finance");
    assertThat(results.get(0).getSimilarityScore()).isZero();
  }

  @Test
  void templatesDoNotPromoteExecutableManifestsOrDomainContractsFromPayload() throws Exception {
    JsonNode configJson =
        objectMapper.readTree(
            """
            {
              "authoringManifest": { "operations": [{ "operationId": "domain.override" }] },
              "domainContract": { "resource": "payroll" },
              "columns": []
            }
            """);
    JsonNode templateMeta =
        objectMapper.readTree(
            """
            {
              "authoringManifest": { "operations": [{ "operationId": "unsafe" }] },
              "domainDecision": { "activation": "force" }
            }
            """);
    when(embeddingService.embed(anyString())).thenReturn(List.of(0.2f));
    when(repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
            REGISTRY_TYPE, "praxis-table", COMPONENT_TYPE, Scope.SYSTEM, SCOPE_KEY))
        .thenReturn(Optional.empty());
    when(repository.save(any(AiRegistry.class))).thenAnswer(invocation -> invocation.getArgument(0));

    AiRegistry saved =
        service.upsertTemplate("praxis-table", configJson, "Template com evidencia", templateMeta);

    ArgumentCaptor<AiRegistry> captor = ArgumentCaptor.forClass(AiRegistry.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue()).isSameAs(saved);
    assertThat(saved.getRegistryType()).isEqualTo(REGISTRY_TYPE);
    assertThat(saved.getComponentType()).isEqualTo(COMPONENT_TYPE);

    JsonNode payload = objectMapper.readTree(saved.getPayload());
    assertThat(payload.has("authoringManifest")).isFalse();
    assertThat(payload.has("domainContract")).isFalse();
    assertThat(payload.path("configJson").has("authoringManifest")).isTrue();
    assertThat(payload.path("templateMeta").has("authoringManifest")).isTrue();
    assertThat(payload.path("templateMeta").has("domainDecision")).isTrue();
  }

  private AiRegistryTemplateSearchProjection projection(
      String componentId, String aiDescription, String configJson, Double similarityScore) {
    return new AiRegistryTemplateSearchProjection() {
      @Override
      public String getComponentId() {
        return componentId;
      }

      @Override
      public String getAiDescription() {
        return aiDescription;
      }

      @Override
      public String getConfigJson() {
        return configJson;
      }

      @Override
      public Double getSimilarityScore() {
        return similarityScore;
      }

      @Override
      public String getConfigJsonSnippet() {
        if (configJson == null || configJson.length() <= 500) {
          return configJson;
        }
        return configJson.substring(0, 497) + "...";
      }
    };
  }
}
