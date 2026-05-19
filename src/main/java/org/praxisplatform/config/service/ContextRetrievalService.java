package org.praxisplatform.config.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.dto.ComponentSearchResult;
import org.praxisplatform.config.projection.ApiMetadataProjection;
import org.praxisplatform.config.projection.ComponentDefinitionProjection;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagFilters;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.EmbeddingService.EmbeddingCallConfig;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faz recuperacao semantica dos contextos que alimentam os fluxos AI da plataforma.
 *
 * <p>O servico consulta tanto a fonte estruturada quanto o vector store derivado para localizar
 * APIs e definicoes de componentes por similaridade, respeitando filtros de tenant, ambiente e
 * release quando a busca depende do corpus publicado para RAG.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContextRetrievalService {

    private final EmbeddingService embeddingService;
    private final ApiMetadataRepository apiMetadataRepository;
    private final AiRegistryRepository aiRegistryRepository;
    private final RagVectorStoreService ragVectorStoreService;

    private static final int DEFAULT_SEARCH_LIMIT = 5; // Limite padrão para buscas
    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String DEFAULT_RAG_RELEASE_FALLBACK = "v1";

    @Value("${praxis.ai.rag.release.default:v1}")
    private String ragDefaultRelease = DEFAULT_RAG_RELEASE_FALLBACK;

    @Value("${praxis.ai.rag.release.fallback.legacy-version:true}")
    private boolean ragReleaseFallbackToLegacyVersion = true;

    @Value("${praxis.ai.rag.release.fallback.default-enabled:false}")
    private boolean ragReleaseFallbackToDefaultEnabled;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    /**
     * Busca metadados de API usando similaridade vetorial.
     * Retorna os schemas completos (request/response) e parâmetros para permitir
     * que o LLM gere configurações de UI precisas.
     */
    public List<ApiSearchResult> searchApiMetadata(String query, String method, String tags, int limit) {
        return searchApiMetadata(query, method, tags, limit, null, null, null, null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<ApiSearchResult> searchApiMetadata(
            String query,
            String method,
            String tags,
            int limit,
            EmbeddingCallConfig embeddingConfig) {
        return searchApiMetadata(query, method, tags, limit, embeddingConfig, null, null, null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<ApiSearchResult> searchApiMetadata(
            String query,
            String method,
            String tags,
            int limit,
            EmbeddingCallConfig embeddingConfig,
            String tenantId,
            String environment) {
        return searchApiMetadata(
                query,
                method,
                tags,
                limit,
                embeddingConfig,
                tenantId,
                environment,
                null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<ApiSearchResult> searchApiMetadata(
            String query,
            String method,
            String tags,
            int limit,
            EmbeddingCallConfig embeddingConfig,
            String tenantId,
            String environment,
            String releaseId) {
        String resolvedReleaseId = resolveReleaseId(releaseId);
        List<ApiSearchResult> ragResults =
                searchApiMetadataWithVectorStore(
                        query,
                        method,
                        tags,
                        limit,
                        tenantId,
                        environment,
                        resolvedReleaseId);
        if (ragResults.isEmpty() && shouldFallbackToDefaultRelease(resolvedReleaseId)) {
            String defaultReleaseId = resolveDefaultReleaseId();
            ragResults = searchApiMetadataWithVectorStore(
                    query,
                    method,
                    tags,
                    limit,
                    tenantId,
                    environment,
                    defaultReleaseId);
            if (!ragResults.isEmpty()) {
                log.warn(
                        "[ContextRetrievalService] API retrieval fallback applied from release '{}' to default '{}'.",
                        safeQuery(resolvedReleaseId),
                        safeQuery(defaultReleaseId));
            }
        }
        if (!ragResults.isEmpty()) {
            return ragResults;
        }
        if (ragVectorStoreService.isAvailable()) {
            log.warn(
                    "[ContextRetrievalService] No RAG results for release-scoped API retrieval (releaseId='{}', tenantId='{}', env='{}'); skipping legacy fallback.",
                    safeQuery(resolvedReleaseId),
                    safeQuery(tenantId),
                    safeQuery(environment));
            return List.of();
        }
        if (hasTenantScope(tenantId, environment)) {
            log.warn(
                    "[ContextRetrievalService] Skipping legacy fallback for tenant-scoped search (tenantId={}, env={}).",
                    safeQuery(tenantId),
                    safeQuery(environment));
            return List.of();
        }
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

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<ComponentSearchResult> searchComponentDefinitions(String query, int limit) {
        return searchComponentDefinitions(query, limit, null, null, null, null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<ComponentSearchResult> searchComponentDefinitions(
            String query,
            int limit,
            EmbeddingCallConfig embeddingConfig) {
        return searchComponentDefinitions(query, limit, embeddingConfig, null, null, null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<ComponentSearchResult> searchComponentDefinitions(
            String query,
            int limit,
            EmbeddingCallConfig embeddingConfig,
            String tenantId,
            String environment) {
        return searchComponentDefinitions(
                query,
                limit,
                embeddingConfig,
                tenantId,
                environment,
                null);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<ComponentSearchResult> searchComponentDefinitions(
            String query,
            int limit,
            EmbeddingCallConfig embeddingConfig,
            String tenantId,
            String environment,
            String releaseId) {
        String resolvedReleaseId = resolveReleaseId(releaseId);
        List<ComponentSearchResult> ragResults =
                searchComponentDefinitionsWithVectorStore(
                        query,
                        limit,
                        tenantId,
                        environment,
                        resolvedReleaseId);
        if (ragResults.isEmpty() && shouldFallbackToDefaultRelease(resolvedReleaseId)) {
            String defaultReleaseId = resolveDefaultReleaseId();
            ragResults = searchComponentDefinitionsWithVectorStore(
                    query,
                    limit,
                    tenantId,
                    environment,
                    defaultReleaseId);
            if (!ragResults.isEmpty()) {
                log.warn(
                        "[ContextRetrievalService] Component retrieval fallback applied from release '{}' to default '{}'.",
                        safeQuery(resolvedReleaseId),
                        safeQuery(defaultReleaseId));
            }
        }
        if (!ragResults.isEmpty()) {
            return ragResults;
        }
        if (ragVectorStoreService.isAvailable()) {
            log.warn(
                    "[ContextRetrievalService] No RAG results for release-scoped component retrieval (releaseId='{}', tenantId='{}', env='{}'); skipping legacy fallback.",
                    safeQuery(resolvedReleaseId),
                    safeQuery(tenantId),
                    safeQuery(environment));
            return List.of();
        }
        if (hasTenantScope(tenantId, environment)) {
            log.warn(
                    "[ContextRetrievalService] Skipping legacy fallback for tenant-scoped search (tenantId={}, env={}).",
                    safeQuery(tenantId),
                    safeQuery(environment));
            return List.of();
        }
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

    private boolean hasTenantScope(String tenantId, String environment) {
        return (tenantId != null && !tenantId.isBlank()) || (environment != null && !environment.isBlank());
    }

    private String resolveReleaseId(String releaseId) {
        return RagDocumentIdentity.resolveReleaseId(releaseId, resolveDefaultReleaseId(), null);
    }

    private String resolveDefaultReleaseId() {
        return RagDocumentIdentity.resolveReleaseId(ragDefaultRelease, DEFAULT_RAG_RELEASE_FALLBACK, null);
    }

    private boolean shouldFallbackToDefaultRelease(String resolvedReleaseId) {
        if (!ragReleaseFallbackToDefaultEnabled) {
            return false;
        }
        String defaultReleaseId = resolveDefaultReleaseId();
        return defaultReleaseId != null && !defaultReleaseId.equals(resolvedReleaseId);
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

    private List<ApiSearchResult> searchApiMetadataWithVectorStore(
            String query,
            String method,
            String tags,
            int limit,
            String tenantId,
            String environment,
            String releaseId) {
        if (!ragVectorStoreService.isAvailable()) {
            return List.of();
        }
        if (tags != null && !tags.isBlank()) {
            return List.of();
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = builder.eq(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.API_METADATA);
        FilterExpressionBuilder.Op scopeFilter = RagFilters.buildScopedFilter(
                builder,
                tenantId,
                environment,
                releaseId,
                ragReleaseFallbackToLegacyVersion);
        filter = builder.and(filter, scopeFilter);
        String normalizedMethod = normalizeMethod(method);
        if (normalizedMethod != null) {
            filter = builder.and(filter, builder.eq(RagMetadataKeys.METHOD, normalizedMethod));
        }
        List<Document> documents = ragVectorStoreService.search(
                query,
                limit > 0 ? limit : DEFAULT_SEARCH_LIMIT,
                filter.build());
        if (documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .map(this::mapToApiSearchResult)
                .collect(Collectors.toList());
    }

    private List<ComponentSearchResult> searchComponentDefinitionsWithVectorStore(
            String query,
            int limit,
            String tenantId,
            String environment,
            String releaseId) {
        if (!ragVectorStoreService.isAvailable()) {
            return List.of();
        }
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = builder.eq(
                RagMetadataKeys.RESOURCE_TYPE,
                RagResourceTypes.COMPONENT_DEFINITION);
        FilterExpressionBuilder.Op scopeFilter = RagFilters.buildScopedFilter(
                builder,
                tenantId,
                environment,
                releaseId,
                ragReleaseFallbackToLegacyVersion);
        filter = builder.and(filter, scopeFilter);
        List<Document> documents = ragVectorStoreService.search(
                query,
                limit > 0 ? limit : DEFAULT_SEARCH_LIMIT,
                filter.build());
        if (documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .map(this::mapToComponentSearchResult)
                .collect(Collectors.toList());
    }

    private ApiSearchResult mapToApiSearchResult(Document document) {
        Map<String, Object> metadata = document != null ? document.getMetadata() : Map.of();
        Long id = toLong(metadata.get(RagMetadataKeys.DB_ID));
        String requestSchema = toString(metadata.get(RagMetadataKeys.REQUEST_SCHEMA));
        String responseSchema = toString(metadata.get(RagMetadataKeys.RESPONSE_SCHEMA));
        return ApiSearchResult.builder()
                .id(id)
                .method(toString(metadata.get(RagMetadataKeys.METHOD)))
                .path(toString(metadata.get(RagMetadataKeys.PATH)))
                .summary(toString(metadata.get(RagMetadataKeys.SUMMARY)))
                .tags(toString(metadata.get(RagMetadataKeys.TAGS)))
                .similarityScore(document != null && document.getScore() != null ? document.getScore() : 0.0d)
                .requestSchemaSnippet(toSnippet(requestSchema))
                .responseSchemaSnippet(toSnippet(responseSchema))
                .requestSchema(requestSchema)
                .responseSchema(responseSchema)
                .parameters(toString(metadata.get(RagMetadataKeys.PARAMETERS)))
                .build();
    }

    private ComponentSearchResult mapToComponentSearchResult(Document document) {
        Map<String, Object> metadata = document != null ? document.getMetadata() : Map.of();
        return ComponentSearchResult.builder()
                .id(toString(metadata.get(RagMetadataKeys.RESOURCE_ID)))
                .description(toString(metadata.get(RagMetadataKeys.DESCRIPTION)))
                .jsonSchema(toString(metadata.get(RagMetadataKeys.JSON_SCHEMA)))
                .similarityScore(document != null && document.getScore() != null ? document.getScore() : 0.0d)
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

    private String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            return null;
        }
        return method.trim().toUpperCase();
    }

    private String toSnippet(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 500) {
            return value;
        }
        return value.substring(0, 497) + "...";
    }

    private String toString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        return String.valueOf(value);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
