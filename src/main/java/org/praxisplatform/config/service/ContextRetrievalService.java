package org.praxisplatform.config.service;

import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.dto.ComponentSearchResult;
import org.praxisplatform.config.projection.ApiMetadataProjection;
import org.praxisplatform.config.projection.ComponentDefinitionProjection;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.EmbeddingService.EmbeddingCallConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContextRetrievalService {

    private final EmbeddingService embeddingService;
    private final ApiMetadataRepository apiMetadataRepository;
    private final AiRegistryRepository aiRegistryRepository;

    private static final int DEFAULT_SEARCH_LIMIT = 5; // Limite padrão para buscas
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";

    @Transactional(readOnly = true)
    /**
     * Busca metadados de API usando similaridade vetorial.
     * Retorna os schemas completos (request/response) e parâmetros para permitir
     * que o LLM gere configurações de UI precisas.
     */
    public List<ApiSearchResult> searchApiMetadata(String query, String method, String tags, int limit) {
        return searchApiMetadata(query, method, tags, limit, null);
    }

    @Transactional(readOnly = true)
    public List<ApiSearchResult> searchApiMetadata(
            String query,
            String method,
            String tags,
            int limit,
            EmbeddingCallConfig embeddingConfig) {
        String vectorLiteral = toVectorLiteral(embeddingService.embed(query, embeddingConfig));
        String methodFilter = method == null ? "" : method;
        String tagsFilter = tags == null ? "" : tags;
        List<ApiMetadataProjection> projections = apiMetadataRepository.findByVectorSimilarity(
                vectorLiteral, methodFilter, tagsFilter, limit > 0 ? limit : DEFAULT_SEARCH_LIMIT);
        if (log.isDebugEnabled()) {
            log.debug(
                    "[ContextRetrievalService] apiMetadata query='{}' method='{}' tags='{}' results={}",
                    safeQuery(query),
                    methodFilter,
                    tagsFilter,
                    summarizeApiResults(projections));
        }
        return projections.stream().map(this::mapToApiSearchResult).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ComponentSearchResult> searchComponentDefinitions(String query, int limit) {
        return searchComponentDefinitions(query, limit, null);
    }

    @Transactional(readOnly = true)
    public List<ComponentSearchResult> searchComponentDefinitions(
            String query,
            int limit,
            EmbeddingCallConfig embeddingConfig) {
        String vectorLiteral = toVectorLiteral(embeddingService.embed(query, embeddingConfig));
        List<ComponentDefinitionProjection> projections =
                aiRegistryRepository.findComponentDefinitionsByVectorSimilarity(
                REGISTRY_TYPE_COMPONENT_DEF, vectorLiteral, limit > 0 ? limit : DEFAULT_SEARCH_LIMIT);

        if (log.isDebugEnabled()) {
            log.debug(
                    "[ContextRetrievalService] componentDefinitions query='{}' results={}",
                    safeQuery(query),
                    summarizeComponentResults(projections));
        }
        return projections.stream().map(this::mapToComponentSearchResult).collect(Collectors.toList());
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

    private ApiSearchResult mapToApiSearchResult(ApiMetadataProjection projection) {
        double score = safeSimilarityScore(projection != null ? projection.getSimilarityScore() : null);
        return ApiSearchResult.builder()
                .id(projection.getId())
                .method(projection.getMethod())
                .path(projection.getPath())
                .summary(projection.getSummary())
                .tags(projection.getTags())
                .similarityScore(score)
                .requestSchemaSnippet(projection.getRequestSchemaSnippet())
                .responseSchemaSnippet(projection.getResponseSchemaSnippet())
                .requestSchema(projection.getRequestSchema())
                .responseSchema(projection.getResponseSchema())
                .parameters(projection.getParameters())
                .build();
    }

    private ComponentSearchResult mapToComponentSearchResult(ComponentDefinitionProjection projection) {
        double score = safeSimilarityScore(projection != null ? projection.getSimilarityScore() : null);
        return ComponentSearchResult.builder()
                .id(projection.getId())
                .description(projection.getDescription())
                .jsonSchema(projection.getJsonSchemaSnippet()) // Usa o snippet já configurado
                .similarityScore(score)
                .build();
    }

    private String summarizeApiResults(List<ApiMetadataProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return "[]";
        }
        return projections.stream()
                .map(p -> String.format("%s %s:%.4f",
                        p.getMethod(),
                        p.getPath(),
                        p.getSimilarityScore() != null ? p.getSimilarityScore() : 0.0d))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String summarizeComponentResults(List<ComponentDefinitionProjection> projections) {
        if (projections == null || projections.isEmpty()) {
            return "[]";
        }
        return projections.stream()
                .map(p -> String.format("%s:%.4f",
                        p.getId(),
                        p.getSimilarityScore() != null ? p.getSimilarityScore() : 0.0d))
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

    private double safeSimilarityScore(Double score) {
        return score != null ? score : 0.0d;
    }
}
