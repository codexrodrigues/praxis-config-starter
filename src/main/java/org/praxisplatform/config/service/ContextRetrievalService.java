package org.praxisplatform.config.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.dto.ComponentSearchResult;
import org.praxisplatform.config.projection.ApiMetadataProjection;
import org.praxisplatform.config.projection.ComponentDefinitionProjection;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.repository.ComponentDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContextRetrievalService {

    private final EmbeddingService embeddingService;
    private final ApiMetadataRepository apiMetadataRepository;
    private final ComponentDefinitionRepository componentDefinitionRepository;

    private static final int DEFAULT_SEARCH_LIMIT = 5; // Limite padrão para buscas

    @Transactional(readOnly = true)
    /**
     * Busca metadados de API usando similaridade vetorial.
     * Retorna os schemas completos (request/response) e parâmetros para permitir
     * que o LLM gere configurações de UI precisas.
     */
    public List<ApiSearchResult> searchApiMetadata(String query, String method, String tags, int limit) {
        String vectorLiteral = toVectorLiteral(embeddingService.embed(query));
        String methodFilter = method == null ? "" : method;
        String tagsFilter = tags == null ? "" : tags;
        List<ApiMetadataProjection> projections = apiMetadataRepository.findByVectorSimilarity(
                vectorLiteral, methodFilter, tagsFilter, limit > 0 ? limit : DEFAULT_SEARCH_LIMIT);
        
        return projections.stream().map(this::mapToApiSearchResult).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ComponentSearchResult> searchComponentDefinitions(String query, int limit) {
        String vectorLiteral = toVectorLiteral(embeddingService.embed(query));
        List<ComponentDefinitionProjection> projections = componentDefinitionRepository.findByVectorSimilarity(
                vectorLiteral, limit > 0 ? limit : DEFAULT_SEARCH_LIMIT);

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
        return ApiSearchResult.builder()
                .id(projection.getId())
                .method(projection.getMethod())
                .path(projection.getPath())
                .summary(projection.getSummary())
                .tags(projection.getTags())
                .similarityScore(projection.getSimilarityScore())
                .requestSchemaSnippet(projection.getRequestSchemaSnippet())
                .responseSchemaSnippet(projection.getResponseSchemaSnippet())
                .requestSchema(projection.getRequestSchema())
                .responseSchema(projection.getResponseSchema())
                .parameters(projection.getParameters())
                .build();
    }

    private ComponentSearchResult mapToComponentSearchResult(ComponentDefinitionProjection projection) {
        return ComponentSearchResult.builder()
                .id(projection.getId())
                .description(projection.getDescription())
                .jsonSchema(projection.getJsonSchemaSnippet()) // Usa o snippet já configurado
                .similarityScore(projection.getSimilarityScore())
                .build();
    }
}
