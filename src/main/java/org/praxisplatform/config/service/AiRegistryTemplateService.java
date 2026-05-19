package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateSearchResult;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.projection.AiRegistryTemplateSearchProjection;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.service.EmbeddingService.EmbeddingCallConfig;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servico canonico de persistencia, upsert e busca semantica dos templates de registry
 * armazenados em {@code ai_registry}.
 *
 * <p>Os templates sao salvos como registros de escopo {@code SYSTEM/GLOBAL}, com embedding e
 * payload serializado, para sustentar lookup por similaridade, catalogos de templates publicados
 * e reutilizacao de configuracoes base pela plataforma.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiRegistryTemplateService {

  private static final int DEFAULT_SEARCH_LIMIT = 5;
  private static final String SYSTEM_SCOPE_KEY = "GLOBAL";
  private static final String REGISTRY_TYPE = "template";
  private static final String COMPONENT_TYPE = "template";

  private final AiRegistryRepository repository;
  private final ObjectMapper objectMapper;
  private final EmbeddingService embeddingService;

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public Optional<AiRegistry> getTemplate(String componentId) {
    return repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
        REGISTRY_TYPE, componentId, COMPONENT_TYPE, Scope.SYSTEM, SYSTEM_SCOPE_KEY);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public AiRegistry upsertTemplate(
      String componentId, JsonNode configJson, String aiDescription, JsonNode templateMeta) {
    validateConfigJson(configJson);

    String resolvedDescription = resolveDescription(componentId, aiDescription);
    List<Float> embedding = buildEmbedding(componentId, resolvedDescription, configJson, templateMeta);
    String payload = buildPayload(componentId, resolvedDescription, configJson, templateMeta);

    AiRegistry cfg =
        AiRegistry.builder()
            .registryType(REGISTRY_TYPE)
            .registryKey(componentId)
            .componentType(COMPONENT_TYPE)
            .scope(Scope.SYSTEM)
            .scopeKey(SYSTEM_SCOPE_KEY)
            .payload(payload)
            .embedding(embedding)
            .build();

    return saveConfig(cfg);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<AiRegistryTemplateSearchResult> searchTemplates(
      String query, String componentId, int limit) {
    return searchTemplates(query, componentId, limit, null);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<AiRegistryTemplateSearchResult> searchTemplates(
      String query, String componentId, int limit, EmbeddingCallConfig embeddingConfig) {
    String vectorLiteral = toVectorLiteral(embeddingService.embed(query, embeddingConfig));
    int effectiveLimit = limit > 0 ? limit : DEFAULT_SEARCH_LIMIT;

    List<AiRegistryTemplateSearchProjection> projections =
        repository.findTemplatesByVectorSimilarity(
            REGISTRY_TYPE, vectorLiteral, componentId, effectiveLimit);

    List<AiRegistryTemplateSearchResult> results =
        projections.stream().map(this::mapToSearchResult).collect(Collectors.toList());
    if (log.isDebugEnabled()) {
      log.debug(
          "[AiRegistryTemplateService] searchTemplates query='{}' componentId='{}' results={}",
          safeQuery(query),
          componentId,
          summarizeResults(results));
    }
    return results;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<AiRegistryTemplateSearchResult> searchTemplatesByPrefix(
      String query, String registryKeyPrefix, int limit) {
    return searchTemplatesByPrefix(query, registryKeyPrefix, limit, null);
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
  public List<AiRegistryTemplateSearchResult> searchTemplatesByPrefix(
      String query, String registryKeyPrefix, int limit, EmbeddingCallConfig embeddingConfig) {
    if (registryKeyPrefix == null || registryKeyPrefix.isBlank()) {
      return List.of();
    }
    String vectorLiteral = toVectorLiteral(embeddingService.embed(query, embeddingConfig));
    int effectiveLimit = limit > 0 ? limit : DEFAULT_SEARCH_LIMIT;
    String prefix = registryKeyPrefix.endsWith("%") ? registryKeyPrefix : registryKeyPrefix + "%";

    List<AiRegistryTemplateSearchProjection> projections =
        repository.findTemplatesByVectorSimilarityAndPrefix(
            REGISTRY_TYPE, vectorLiteral, prefix, effectiveLimit);

    List<AiRegistryTemplateSearchResult> results =
        projections.stream().map(this::mapToSearchResult).collect(Collectors.toList());
    if (log.isDebugEnabled()) {
      log.debug(
          "[AiRegistryTemplateService] searchTemplatesByPrefix query='{}' prefix='{}' results={}",
          safeQuery(query),
          registryKeyPrefix,
          summarizeResults(results));
    }
    return results;
  }

  @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
  public void deleteTemplate(AiRegistry config) {
    repository.delete(config);
  }

  public JsonNode parsePayload(AiRegistry config) {
    if (config == null || config.getPayload() == null) return null;
    try {
      return objectMapper.readTree(config.getPayload());
    } catch (Exception e) {
      log.warn("Failed to parse ai_registry payload for {}", config.getRegistryKey(), e);
      return null;
    }
  }

  public AiRegistryTemplateRecord toRecord(AiRegistry config) {
    JsonNode payload = parsePayload(config);
    JsonNode configJson = payload != null ? payload.get("configJson") : null;
    JsonNode templateMeta = payload != null ? payload.get("templateMeta") : null;
    return AiRegistryTemplateRecord.builder()
        .componentId(config.getRegistryKey())
        .aiDescription(safeText(payload, "aiDescription"))
        .configJson(configJson)
        .templateMeta(templateMeta)
        .build();
  }

  private AiRegistry saveConfig(AiRegistry config) {
    Optional<AiRegistry> existing =
        repository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
            config.getRegistryType(),
            config.getRegistryKey(),
            config.getComponentType(),
            config.getScope(),
            config.getScopeKey());

    if (existing.isPresent()) {
      AiRegistry dbConfig = existing.get();
      dbConfig.setPayload(config.getPayload());
      dbConfig.setEmbedding(config.getEmbedding());
      dbConfig.setSource(config.getSource());
      dbConfig.setSourceRef(config.getSourceRef());
      dbConfig.setStatus(config.getStatus() != null ? config.getStatus() : dbConfig.getStatus());
      return repository.save(dbConfig);
    }
    return repository.save(config);
  }

  private AiRegistryTemplateSearchResult mapToSearchResult(
      AiRegistryTemplateSearchProjection projection) {
    return AiRegistryTemplateSearchResult.builder()
        .componentId(projection.getComponentId())
        .aiDescription(projection.getAiDescription())
        .similarityScore(
            projection.getSimilarityScore() != null ? projection.getSimilarityScore() : 0.0d)
        .configJsonSnippet(projection.getConfigJsonSnippet())
        .build();
  }

  private void validateConfigJson(JsonNode configJson) {
    if (configJson == null || !configJson.isObject()) {
      throw new ConfigurationIngestionException("configJson must be a JSON object");
    }
  }

  private String summarizeResults(List<AiRegistryTemplateSearchResult> results) {
    if (results == null || results.isEmpty()) {
      return "[]";
    }
    return results.stream()
        .map(r -> r.getComponentId() + ":" + String.format("%.4f", r.getSimilarityScore()))
        .collect(Collectors.joining(", ", "[", "]"));
  }

  private String safeQuery(String query) {
    if (query == null) {
      return "";
    }
    if (query.length() <= 120) {
      return query;
    }
    return query.substring(0, 120) + "...";
  }

  private String resolveDescription(String componentId, String aiDescription) {
    if (aiDescription == null || aiDescription.isBlank()) {
      return "Component " + componentId;
    }
    return aiDescription;
  }

  private List<Float> buildEmbedding(
      String componentId, String aiDescription, JsonNode configJson, JsonNode templateMeta) {
    String summary = buildSummary(componentId, aiDescription, configJson, templateMeta);
    return embeddingService.embed(summary);
  }

  private String buildSummary(
      String componentId, String aiDescription, JsonNode configJson, JsonNode templateMeta) {
    StringJoiner joiner = new StringJoiner(" | ");
    joiner.add("Component: " + componentId);
    joiner.add("Scope: SYSTEM:GLOBAL");
    if (aiDescription != null && !aiDescription.isBlank()) {
      joiner.add("Description: " + aiDescription);
    }
    joiner.add("ConfigKeys: " + summarizeConfigKeys(configJson));
    joiner.add("ConfigSnippet: " + summarizeConfigSnippet(configJson));
    joiner.add("TemplateMeta: " + summarizeTemplateMeta(templateMeta));
    return joiner.toString();
  }

  private String summarizeConfigKeys(JsonNode configJson) {
    if (configJson == null || !configJson.isObject()) return "none";
    List<String> keys = new ArrayList<>();
    Iterator<String> fieldNames = configJson.fieldNames();
    while (fieldNames.hasNext()) {
      keys.add(fieldNames.next());
    }
    if (keys.isEmpty()) return "none";
    return String.join(",", keys);
  }

  private String summarizeConfigSnippet(JsonNode configJson) {
    if (configJson == null) return "none";
    String raw = writeJson(configJson);
    if (raw.length() > 1000) {
      return raw.substring(0, 1000);
    }
    return raw;
  }

  private String summarizeTemplateMeta(JsonNode templateMeta) {
    if (templateMeta == null || templateMeta.isNull()) return "none";
    String raw = writeJson(templateMeta);
    if (raw.length() > 500) {
      return raw.substring(0, 500);
    }
    return raw;
  }

  private String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new ConfigurationIngestionException("Failed to serialize configJson", e);
    }
  }

  private String safeText(JsonNode payload, String field) {
    if (payload == null) return null;
    JsonNode value = payload.get(field);
    return value != null && !value.isNull() ? value.asText() : null;
  }

  private String buildPayload(
      String componentId, String aiDescription, JsonNode configJson, JsonNode templateMeta) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("componentId", componentId);
    if (aiDescription != null && !aiDescription.isBlank()) {
      root.put("aiDescription", aiDescription);
    }
    root.set("configJson", configJson);
    if (templateMeta != null && !templateMeta.isNull()) {
      root.set("templateMeta", templateMeta);
    }
    return writeJson(root);
  }

  private String toVectorLiteral(List<Float> floatList) {
    if (floatList == null || floatList.isEmpty()) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < floatList.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      Float val = floatList.get(i);
      sb.append(val != null ? val : 0.0f);
    }
    sb.append(']');
    return sb.toString();
  }
}
