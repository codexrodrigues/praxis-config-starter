package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.domain.ApiMetadataIndexingStatus;
import org.praxisplatform.config.dto.ApiCatalogRequest;
import org.praxisplatform.config.dto.ApiMetadataRagReconcileResponse;
import org.praxisplatform.config.dto.ApiMetadataRagStatusResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingesta o catalogo operacional de APIs na fonte canonica {@code api_metadata} e sincroniza a
 * superficie derivada consumida pelo RAG.
 *
 * <p>Para cada endpoint recebido, o servico normaliza e persiste o snapshot estruturado em uma
 * transacao curta. Embeddings e documentos RAG sao materializacoes derivadas executadas depois do
 * commit pelo coordenador limitado de indexacao.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiMetadataIngestionService {

    private static final String DEFAULT_TENANT_ID = "GLOBAL";
    private static final String DEFAULT_ENVIRONMENT = "default";
    private static final String DEFAULT_SERVICE_KEY = "default";
    // Bounds repeated provider work while keeping each checkpoint efficient for batch embeddings.
    private static final int EMBEDDING_CHECKPOINT_SIZE = 20;

    private final ApiMetadataRepository repository;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final RagVectorStoreService ragVectorStoreService;
    private final ApiMetadataIndexingStateService indexingStateService;
    private final ApiMetadataIndexingCoordinator indexingCoordinator;
    private static final Logger ingestLog = LoggerFactory.getLogger("api-metadata-ingest");

    @Value("${praxis.api-metadata.rag-publication.enabled:true}")
    private boolean apiMetadataRagPublicationEnabled = true;

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public void ingestCatalog(ApiCatalogRequest request, String tenantId, String environment) {
        if (request.getEndpoints() == null || request.getEndpoints().isEmpty()) {
            log.warn("No endpoints found in catalog request");
            return;
        }

        String resolvedTenant = normalizeOrDefault(tenantId, DEFAULT_TENANT_ID);
        String resolvedEnv = normalizeOrDefault(environment, DEFAULT_ENVIRONMENT);
        String releaseId = RagDocumentIdentity.resolveReleaseId(
                request.getReleaseId(),
                request.getVersion(),
                request.getGeneratedAt());
        String requestVersion = normalize(request.getVersion());
        String resolvedReleaseId = normalizeOrDefault(releaseId, "v1");
        String generatedAt = normalize(request.getGeneratedAt());
        String serviceKey = DEFAULT_SERVICE_KEY;
        List<ApiCatalogRequest.ApiEndpointEntry> endpoints = request.getEndpoints();
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                resolvedTenant, resolvedEnv, serviceKey, resolvedReleaseId);
        boolean indexingRequired = false;

        for (ApiCatalogRequest.ApiEndpointEntry ep : endpoints) {
            try {
                String path = ep.getPath();
                String method = ep.getMethod();
                
                String tags = toCommaSeparated(ep.getTags());
                String summary = ep.getSummary();
                String description = ep.getDescription();
                String operationId = ep.getOperationId();

                String requestSchema = safeWrite(ep.getRequestSchema());
                String responseSchema = safeWrite(ep.getResponseSchema());
                String parameters = safeWrite(ep.getParameters());
                String rawJson = safeWrite(ep);

                ingestLog.info(
                        "Ingest start: method={} path={} tags={} summaryLen={} descLen={} reqSchemaLen={} resSchemaLen={} paramsLen={}",
                        method,
                        path,
                        safeLen(tags),
                        safeLen(summary),
                        safeLen(description),
                        safeLen(requestSchema),
                        safeLen(responseSchema),
                        safeLen(parameters));
                UpsertOutcome outcome = upsert(
                        resolvedTenant,
                        resolvedEnv,
                        serviceKey,
                        resolvedReleaseId,
                        requestVersion,
                        generatedAt,
                        path,
                        method,
                        tags,
                        summary,
                        description,
                        operationId,
                        requestSchema,
                        responseSchema,
                        parameters,
                        rawJson);
                ApiMetadata meta = outcome.metadata();
                indexingRequired |= outcome.indexingRequired();

                log.info("Ingested api metadata: {} {}", meta.getMethod(), meta.getPath());
                ingestLog.info("Ingest saved: id={} method={} path={} indexingRequired={}",
                        meta.getId(),
                        meta.getMethod(),
                        meta.getPath(),
                        outcome.indexingRequired());

            } catch (Exception e) {
                String msg = "Error ingesting endpoint: " + ep.getMethod() + " " + ep.getPath();
                log.error(msg, e);
                ingestLog.error(msg, e);
                throw new ConfigurationIngestionException(msg, e);
            }
        }
        if (!indexingRequired && !requiresIndexingRecovery(scope)) {
            log.info(
                    "API metadata ingest is materially unchanged; derived indexing remains current for tenant={}, env={}, serviceKey={}, release={}",
                    resolvedTenant,
                    resolvedEnv,
                    serviceKey,
                    resolvedReleaseId);
            return;
        }
        long indexingRevision = indexingStateService.request(scope);
        long expectedCount = repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                resolvedTenant, resolvedEnv, serviceKey, resolvedReleaseId);
        indexingStateService.updateExpectedCount(scope, indexingRevision, expectedCount);
        indexingCoordinator.scheduleAfterCommit(scope);
    }

    private boolean requiresIndexingRecovery(ApiMetadataIndexingScope scope) {
        Optional<ApiMetadataIndexingStateService.StateSnapshot> current = indexingStateService.snapshot(scope);
        if (current.isEmpty() || current.get().status() == ApiMetadataIndexingStatus.FAILED) {
            return true;
        }
        ApiMetadataIndexingStateService.StateSnapshot snapshot = current.get();
        if (snapshot.status() != ApiMetadataIndexingStatus.READY
                || !apiMetadataRagPublicationEnabled
                || !ragVectorStoreService.isAvailable()) {
            return false;
        }
        RagVectorStoreService.RagCorpusReleaseStatus corpus = ragVectorStoreService.corpusReleaseStatus(
                scope.tenantId(),
                scope.environment(),
                scope.serviceKey(),
                scope.releaseId(),
                RagResourceTypes.API_METADATA,
                snapshot.expectedDocumentCount());
        return !corpus.reconciled();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public ApiMetadataRagStatusResponse ragStatus(
            String tenantId,
            String environment,
            String serviceKey,
            String releaseId) {
        String resolvedTenant = normalizeOrDefault(tenantId, DEFAULT_TENANT_ID);
        String resolvedEnv = normalizeOrDefault(environment, DEFAULT_ENVIRONMENT);
        String resolvedServiceKey = normalizeOrDefault(serviceKey, DEFAULT_SERVICE_KEY);
        String resolvedReleaseId = normalizeOrDefault(releaseId, "v1");
        long expectedDocumentCount = repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                resolvedTenant,
                resolvedEnv,
                resolvedServiceKey,
                resolvedReleaseId);
        RagVectorStoreService.RagCorpusReleaseStatus status = ragVectorStoreService.corpusReleaseStatus(
                resolvedTenant,
                resolvedEnv,
                resolvedServiceKey,
                resolvedReleaseId,
                RagResourceTypes.API_METADATA,
                expectedDocumentCount);
        long legacyIndexedDocumentCount = repository
                .countByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndEmbeddingIsNotNull(
                        resolvedTenant,
                        resolvedEnv,
                        resolvedServiceKey,
                        resolvedReleaseId);
        ApiMetadataRagStatusResponse.IndexingStatus indexingStatus = indexingStateService.snapshot(
                        new ApiMetadataIndexingScope(
                                resolvedTenant,
                                resolvedEnv,
                                resolvedServiceKey,
                                resolvedReleaseId))
                .map(snapshot -> new ApiMetadataRagStatusResponse.IndexingStatus(
                        snapshot.status().name(),
                        snapshot.revision(),
                        snapshot.attempt(),
                        snapshot.legacyIndexedDocumentCount(),
                        snapshot.publishedDocumentCount(),
                        snapshot.failureCode(),
                        snapshot.failureMessage(),
                        instantText(snapshot.requestedAt()),
                        instantText(snapshot.startedAt()),
                        instantText(snapshot.completedAt()),
                        instantText(snapshot.updatedAt())))
                .orElseGet(() -> legacyStatus(
                        expectedDocumentCount,
                        legacyIndexedDocumentCount,
                        status));
        return ApiMetadataRagStatusResponse.from(
                resolvedTenant,
                resolvedEnv,
                resolvedServiceKey,
                resolvedReleaseId,
                RagResourceTypes.API_METADATA,
                apiMetadataRagPublicationEnabled,
                ragVectorStoreService.isAvailable(),
                status,
                indexingStatus);
    }

    private ApiMetadataRagStatusResponse.IndexingStatus legacyStatus(
            long expectedDocumentCount,
            long legacyIndexedDocumentCount,
            RagVectorStoreService.RagCorpusReleaseStatus ragStatus) {
        boolean ready = legacyIndexedDocumentCount == expectedDocumentCount && ragStatus.reconciled();
        return new ApiMetadataRagStatusResponse.IndexingStatus(
                ready ? "READY" : "PENDING",
                0L,
                0,
                legacyIndexedDocumentCount,
                ragStatus.documentCount(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private String instantText(Instant value) {
        return value == null ? null : value.toString();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public ApiMetadataRagReconcileResponse reconcileRag(
            String tenantId,
            String environment,
            String serviceKey,
            String releaseId) {
        String resolvedTenant = normalizeOrDefault(tenantId, DEFAULT_TENANT_ID);
        String resolvedEnv = normalizeOrDefault(environment, DEFAULT_ENVIRONMENT);
        String resolvedServiceKey = normalizeOrDefault(serviceKey, DEFAULT_SERVICE_KEY);
        String resolvedReleaseId = normalizeOrDefault(releaseId, "v1");
        ApiMetadataIndexingScope scope = new ApiMetadataIndexingScope(
                resolvedTenant, resolvedEnv, resolvedServiceKey, resolvedReleaseId);
        long revision = indexingStateService.request(scope);
        long expectedCount = repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                resolvedTenant, resolvedEnv, resolvedServiceKey, resolvedReleaseId);
        indexingStateService.updateExpectedCount(scope, revision, expectedCount);
        indexingCoordinator.scheduleAfterCommit(scope);
        ApiMetadataRagStatusResponse status = ragStatus(
                resolvedTenant,
                resolvedEnv,
                resolvedServiceKey,
                resolvedReleaseId);
        return new ApiMetadataRagReconcileResponse(
                "praxis.api-metadata-rag-reconcile/v0.2",
                resolvedTenant,
                resolvedEnv,
                resolvedServiceKey,
                resolvedReleaseId,
                apiMetadataRagPublicationEnabled,
                ragVectorStoreService.isAvailable(),
                expectedCount,
                0,
                status);
    }

    public void processIndexingClaim(ApiMetadataIndexingStateService.WorkClaim claim) {
        ApiMetadataIndexingScope scope = claim.scope();
        try {
            if (!apiMetadataRagPublicationEnabled) {
                indexingStateService.fail(
                        scope,
                        claim.revision(),
                        "RAG_PUBLICATION_DISABLED",
                        "API metadata RAG publication is disabled for this runtime.");
                return;
            }
            if (!ragVectorStoreService.isAvailable()) {
                indexingStateService.fail(
                        scope,
                        claim.revision(),
                        "VECTOR_STORE_UNAVAILABLE",
                        "API metadata vector store is unavailable; request reconcile after recovery.");
                return;
            }
            List<ApiMetadata> metadataRows = repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                    scope.tenantId(), scope.environment(), scope.serviceKey(), scope.releaseId());
            List<ApiMetadata> rowsWithoutLegacyEmbedding = metadataRows.stream()
                    .filter(row -> row.getEmbedding() == null || row.getEmbedding().isEmpty())
                    .toList();
            if (!embedAndCheckpointStoredMetadata(
                    scope,
                    claim.revision(),
                    rowsWithoutLegacyEmbedding)) {
                return;
            }
            indexingStateService.publishAndCompleteIfCurrent(
                    scope,
                    claim.revision(),
                    () -> publishCanonicalRagDocuments(
                            scope.tenantId(),
                            scope.environment(),
                            scope.serviceKey(),
                            scope.releaseId()).publishedDocumentCount());
        } catch (RuntimeException ex) {
            AiProviderCallException providerFailure = findCause(ex, AiProviderCallException.class);
            indexingStateService.fail(
                    scope,
                    claim.revision(),
                    indexingFailureCode(ex),
                    indexingFailureMessage(ex));
            log.warn(
                    "API metadata indexing failed for tenant={}, env={}, serviceKey={}, release={}, revision={}: "
                            + "failureType={}, provider={}, kind={}, statusCode={}, providerRequestId={}",
                    scope.tenantId(),
                    scope.environment(),
                    scope.serviceKey(),
                    scope.releaseId(),
                    claim.revision(),
                    ex.getClass().getSimpleName(),
                    providerFailure != null ? providerFailure.getProvider() : null,
                    providerFailure != null ? providerFailure.getKind() : null,
                    providerFailure != null ? providerFailure.getStatusCode() : null,
                    providerFailure != null ? providerFailure.getProviderRequestId() : null);
        }
    }

    private boolean embedAndCheckpointStoredMetadata(
            ApiMetadataIndexingScope scope,
            long revision,
            List<ApiMetadata> rows) {
        if (rows == null || rows.isEmpty()) {
            return indexingStateService.commitLegacyEmbeddings(scope, revision, Map.of());
        }
        for (int offset = 0; offset < rows.size(); offset += EMBEDDING_CHECKPOINT_SIZE) {
            int end = Math.min(offset + EMBEDDING_CHECKPOINT_SIZE, rows.size());
            Map<Long, List<Float>> embeddingsById = embedStoredMetadata(rows.subList(offset, end));
            if (!indexingStateService.commitLegacyEmbeddings(scope, revision, embeddingsById)) {
                return false;
            }
        }
        return true;
    }

    private Map<Long, List<Float>> embedStoredMetadata(List<ApiMetadata> rows) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        List<String> summaries = rows.stream().map(this::storedEmbeddingSummary).toList();
        List<List<Float>> embeddings;
        try {
            if (summaries.size() == 1) {
                embeddings = List.of(embeddingService.embed(summaries.get(0)));
            } else {
                embeddings = embeddingService.embedAll(summaries);
            }
        } catch (RuntimeException ex) {
            throw new ConfigurationIngestionException("API metadata embedding provider failed.", ex);
        }
        if (embeddings == null || embeddings.size() != rows.size()) {
            throw new ConfigurationIngestionException(
                    "Embedding provider returned an incompatible API metadata batch.");
        }
        Map<Long, List<Float>> result = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            List<Float> embedding = embeddings.get(index);
            if (embedding == null || embedding.isEmpty()) {
                throw new ConfigurationIngestionException(
                        "Embedding provider returned an empty API metadata vector.");
            }
            result.put(rows.get(index).getId(), embedding);
        }
        return result;
    }

    private String storedEmbeddingSummary(ApiMetadata metadata) {
        ApiCatalogRequest.ApiEndpointEntry endpoint = readRawEndpoint(metadata.getRawJson());
        return endpoint == null
                ? buildStoredSummary(metadata)
                : buildSummary(
                        metadata.getPath(),
                        metadata.getMethod(),
                        metadata.getTags(),
                        metadata.getSummary(),
                        metadata.getDescription(),
                        metadata.getOperationId(),
                        endpoint);
    }

    private String indexingFailureCode(RuntimeException ex) {
        return ex instanceof ConfigurationIngestionException
                ? "EMBEDDING_FAILED"
                : "RAG_PUBLICATION_FAILED";
    }

    private String indexingFailureMessage(RuntimeException ex) {
        String message = "API metadata derived indexing failed; canonical metadata remains persisted.";
        AiProviderCallException providerFailure = findCause(ex, AiProviderCallException.class);
        if (providerFailure == null) {
            return message;
        }
        return message
                + " provider=" + providerFailure.getProvider()
                + " kind=" + providerFailure.getKind().name().toLowerCase(java.util.Locale.ROOT) + ".";
    }

    private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private String buildSummary(String path, String method, String tags, String summary, String description,
                                String operationId, ApiCatalogRequest.ApiEndpointEntry ep) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add(method + " " + path);
        if (summary != null) joiner.add("Summary: " + summary);
        if (description != null) joiner.add("Desc: " + description);
        if (operationId != null) joiner.add("OpId: " + operationId);
        if (tags != null && !tags.isBlank()) joiner.add("Tags: " + tags);
        
        joiner.add("Params: " + summarizeParams(ep.getParameters()));
        
        // For complex schemas, we still rely on JsonNode navigation inside the DTO
        joiner.add("Req: " + summarizeSchema(ep.getRequestSchema()));
        joiner.add("Res: " + summarizeSchema(ep.getResponseSchema()));
        
        if (ep.getRequestSchema() != null) {
             joiner.add("ReqFields: " + summarizeFields(ep.getRequestSchema().path("fields")));
             joiner.add("ReqRelations: " + summarizeRelations(ep.getRequestSchema().path("relations")));
        }
        if (ep.getResponseSchema() != null) {
             joiner.add("ResFields: " + summarizeFields(ep.getResponseSchema().path("fields")));
             joiner.add("ResRelations: " + summarizeRelations(ep.getResponseSchema().path("relations")));
        }
        
        return joiner.toString();
    }
    
    private String toCommaSeparated(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return String.join(",", tags);
    }

    private int safeLen(String value) {
        return value == null ? 0 : value.length();
    }

    private String safeWrite(Object node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private String summarizeFields(JsonNode fieldsNode) {
        if (fieldsNode == null || !fieldsNode.isArray()) return "none";
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode f : fieldsNode) {
            String name = f.path("name").asText("");
            String type = f.path("type").asText("");
            boolean required = f.path("required").asBoolean(false);
            joiner.add(name + ":" + type + (required ? " (req)" : ""));
        }
        String res = joiner.toString();
        return res.isEmpty() ? "none" : res;
    }

    private String summarizeRelations(JsonNode relNode) {
        if (relNode == null || !relNode.isArray()) return "none";
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode r : relNode) {
            String field = r.path("field").asText("");
            String target = r.path("targetSchema").asText("");
            String card = r.path("cardinality").asText("");
            joiner.add(field + "->" + target + (card.isEmpty() ? "" : " (" + card + ")"));
        }
        String res = joiner.toString();
        return res.isEmpty() ? "none" : res;
    }

    private String summarizeParams(JsonNode params) {
        if (params == null || !params.isArray()) return "none";
        StringJoiner joiner = new StringJoiner("; ");
        for (JsonNode p : params) {
            String name = p.path("name").asText("");
            String in = p.path("in").asText("");
            String type = p.path("type").asText("");
            boolean required = p.path("required").asBoolean(false);
            joiner.add(name + "@" + in + ":" + type + (required ? " (req)" : ""));
        }
        String res = joiner.toString();
        return res.isEmpty() ? "none" : res;
    }

    private String summarizeSchema(JsonNode schema) {
        if (schema == null || schema.isMissingNode()) return "none";
        String name = schema.path("name").asText(null);
        if (name != null) return name;
        if (schema.has("inlineSchema")) {
            // crude summary of inline schema keys
            JsonNode inline = schema.path("inlineSchema");
            if (inline.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = inline.fields();
                StringJoiner joiner = new StringJoiner(",");
                while (fields.hasNext()) {
                    joiner.add(fields.next().getKey());
                }
                String res = joiner.toString();
                return res.isEmpty() ? "inline" : "inline:" + res;
            }
        }
        return "inline";
    }

    private UpsertOutcome upsert(
            String tenantId,
            String environment,
            String serviceKey,
            String releaseId,
            String releaseVersion,
            String generatedAt,
            String path,
            String method,
            String tags,
            String summary,
            String description,
            String operationId,
            String requestSchema,
            String responseSchema,
            String parameters,
            String rawJson) {
        Optional<ApiMetadata> existing =
                repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                                tenantId,
                                environment,
                                serviceKey,
                                releaseId,
                                path,
                                method)
                        .or(() -> findExistingByStableOperationIdentity(
                                tenantId,
                                environment,
                                serviceKey,
                                releaseId,
                                operationId,
                                method));
        ApiMetadata meta = existing.orElse(new ApiMetadata());
        boolean indexingRequired = existing.isEmpty()
                || meta.getEmbedding() == null
                || meta.getEmbedding().isEmpty()
                || !materiallyEquivalent(
                        meta,
                        releaseVersion,
                        path,
                        method,
                        tags,
                        summary,
                        description,
                        operationId,
                        requestSchema,
                        responseSchema,
                        parameters,
                        rawJson);
        meta.setTenantId(tenantId);
        meta.setEnvironment(environment);
        meta.setServiceKey(serviceKey);
        meta.setReleaseId(releaseId);
        meta.setReleaseVersion(releaseVersion);
        meta.setGeneratedAt(generatedAt);
        meta.setPath(path);
        meta.setMethod(method);
        meta.setTags(tags);
        meta.setSummary(summary);
        meta.setDescription(description);
        meta.setOperationId(operationId);
        meta.setRequestSchema(requestSchema);
        meta.setResponseSchema(responseSchema);
        meta.setParameters(parameters);
        meta.setRawJson(rawJson);
        if (indexingRequired) {
            meta.setEmbedding(null);
        }
        return new UpsertOutcome(repository.save(meta), indexingRequired);
    }

    private boolean materiallyEquivalent(
            ApiMetadata existing,
            String releaseVersion,
            String path,
            String method,
            String tags,
            String summary,
            String description,
            String operationId,
            String requestSchema,
            String responseSchema,
            String parameters,
            String rawJson) {
        return Objects.equals(existing.getReleaseVersion(), releaseVersion)
                && Objects.equals(existing.getPath(), path)
                && Objects.equals(existing.getMethod(), method)
                && Objects.equals(existing.getTags(), tags)
                && Objects.equals(existing.getSummary(), summary)
                && Objects.equals(existing.getDescription(), description)
                && Objects.equals(existing.getOperationId(), operationId)
                && Objects.equals(existing.getRequestSchema(), requestSchema)
                && Objects.equals(existing.getResponseSchema(), responseSchema)
                && Objects.equals(existing.getParameters(), parameters)
                && Objects.equals(existing.getRawJson(), rawJson);
    }

    private Optional<ApiMetadata> findExistingByStableOperationIdentity(
            String tenantId,
            String environment,
            String serviceKey,
            String releaseId,
            String operationId,
            String method) {
        String normalizedOperationId = normalize(operationId);
        if (normalizedOperationId == null) {
            return Optional.empty();
        }
        return repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndOperationIdAndMethod(
                        tenantId,
                        environment,
                        serviceKey,
                        releaseId,
                        normalizedOperationId,
                        method)
                .stream()
                .max(Comparator.comparing(ApiMetadata::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private PublicationOutcome publishCanonicalRagDocuments(
            String tenantId,
            String environment,
            String serviceKey,
            String releaseId) {
        if (!apiMetadataRagPublicationEnabled || !ragVectorStoreService.isAvailable()) {
            long expected = repository.countByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                    tenantId,
                    environment,
                    serviceKey,
                    releaseId);
            return new PublicationOutcome(expected, 0);
        }
        List<ApiMetadata> metadataRows = repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                tenantId,
                environment,
                serviceKey,
                releaseId);
        if (metadataRows == null) {
            metadataRows = List.of();
        }
        List<Document> documents = metadataRows.stream()
                .map(this::toRagDocument)
                .toList();
        ragVectorStoreService.deleteDocumentsByReleaseScope(
                tenantId,
                environment,
                serviceKey,
                releaseId,
                RagResourceTypes.API_METADATA);
        ragVectorStoreService.upsertDocuments(documents);
        return new PublicationOutcome(metadataRows.size(), documents.size());
    }

    private Document toRagDocument(ApiMetadata meta) {
        ApiCatalogRequest.ApiEndpointEntry endpoint = readRawEndpoint(meta.getRawJson());
        String content = endpoint != null
                ? buildSummary(
                        meta.getPath(),
                        meta.getMethod(),
                        meta.getTags(),
                        meta.getSummary(),
                        meta.getDescription(),
                        meta.getOperationId(),
                        endpoint)
                : buildStoredSummary(meta);
        return toRagDocument(
                meta,
                content,
                meta.getTags(),
                meta.getRequestSchema(),
                meta.getResponseSchema(),
                meta.getParameters(),
                meta.getRawJson(),
                meta.getTenantId(),
                meta.getEnvironment(),
                meta.getReleaseId(),
                meta.getReleaseVersion());
    }

    private ApiCatalogRequest.ApiEndpointEntry readRawEndpoint(String rawJson) {
        String normalizedRawJson = normalize(rawJson);
        if (normalizedRawJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(normalizedRawJson, ApiCatalogRequest.ApiEndpointEntry.class);
        } catch (Exception ex) {
            log.debug("Unable to reconstruct API endpoint DTO from raw_json; using stored metadata summary.", ex);
            return null;
        }
    }

    private String buildStoredSummary(ApiMetadata meta) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add(meta.getMethod() + " " + meta.getPath());
        if (meta.getSummary() != null) joiner.add("Summary: " + meta.getSummary());
        if (meta.getDescription() != null) joiner.add("Desc: " + meta.getDescription());
        if (meta.getOperationId() != null) joiner.add("OpId: " + meta.getOperationId());
        if (meta.getTags() != null && !meta.getTags().isBlank()) joiner.add("Tags: " + meta.getTags());
        if (meta.getParameters() != null) joiner.add("Params: " + meta.getParameters());
        if (meta.getRequestSchema() != null) joiner.add("Req: " + meta.getRequestSchema());
        if (meta.getResponseSchema() != null) joiner.add("Res: " + meta.getResponseSchema());
        return joiner.toString();
    }

    private Document toRagDocument(
            ApiMetadata meta,
            String content,
            String tags,
            String requestSchema,
            String responseSchema,
            String parameters,
            String rawJson,
            String tenantId,
            String environment,
            String releaseId,
            String requestVersion) {
        String componentId = buildApiComponentId(meta.getMethod(), meta.getPath());
        String contentHash = RagDocumentIdentity.sha256(buildApiHashPayload(meta, rawJson));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.API_METADATA);
        metadata.put(RagMetadataKeys.RESOURCE_ID, meta.getMethod() + " " + meta.getPath());
        metadata.put(RagMetadataKeys.COMPONENT_ID, componentId);
        metadata.put(RagMetadataKeys.DOC_TYPE, RagResourceTypes.API_METADATA);
        metadata.put(RagMetadataKeys.RELEASE_ID, releaseId);
        metadata.put(RagMetadataKeys.SERVICE_KEY, meta.getServiceKey());
        metadata.put(RagMetadataKeys.CONTENT_HASH, contentHash);
        metadata.put(RagMetadataKeys.CHUNK_INDEX, 0);
        metadata.put(RagMetadataKeys.DB_ID, meta.getId());
        metadata.put(RagMetadataKeys.PATH, meta.getPath());
        metadata.put(RagMetadataKeys.METHOD, meta.getMethod());
        metadata.put(RagMetadataKeys.TAGS, tags);
        metadata.put(RagMetadataKeys.SUMMARY, meta.getSummary());
        metadata.put(RagMetadataKeys.DESCRIPTION, meta.getDescription());
        metadata.put(RagMetadataKeys.OPERATION_ID, meta.getOperationId());
        metadata.put(RagMetadataKeys.REQUEST_SCHEMA, requestSchema);
        metadata.put(RagMetadataKeys.RESPONSE_SCHEMA, responseSchema);
        metadata.put(RagMetadataKeys.PARAMETERS, parameters);
        metadata.put(RagMetadataKeys.SOURCE_KIND, RagResourceTypes.API_METADATA);
        metadata.put(RagMetadataKeys.SOURCE_ID, componentId);
        metadata.put(RagMetadataKeys.CHUNK_KIND, "summary");
        metadata.put(RagMetadataKeys.AI_VISIBILITY, "allow");
        metadata.put(RagMetadataKeys.PUBLISHED_AT, Instant.now().toString());
        if (tenantId != null) {
            metadata.put(RagMetadataKeys.TENANT_ID, tenantId);
        }
        if (environment != null) {
            metadata.put(RagMetadataKeys.ENVIRONMENT, environment);
        }
        metadata.put(RagMetadataKeys.VERSION, requestVersion != null ? requestVersion : "1");
        metadata.entrySet().removeIf(entry -> Objects.isNull(entry.getValue()));
        return Document.builder()
                .id(RagDocumentIdentity.buildDocumentId(
                        tenantId,
                        environment,
                        componentId,
                        releaseId,
                        RagResourceTypes.API_METADATA,
                        contentHash,
                        0))
                .text(content)
                .metadata(metadata)
                .build();
    }

    private String buildApiComponentId(String method, String path) {
        String normalizedMethod = normalize(method);
        String normalizedPath = normalize(path);
        String methodPart = normalizedMethod != null ? normalizedMethod.toUpperCase() : "UNKNOWN";
        String pathPart = normalizedPath != null ? normalizedPath : "unknown";
        return methodPart + ":" + pathPart;
    }

    private String buildApiHashPayload(ApiMetadata metadata, String rawJson) {
        String normalizedMethod = normalize(metadata.getMethod());
        String normalizedPath = normalize(metadata.getPath());
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(normalize(metadata.getTenantId()) != null ? normalize(metadata.getTenantId()) : "");
        joiner.add(normalize(metadata.getEnvironment()) != null ? normalize(metadata.getEnvironment()) : "");
        joiner.add(normalize(metadata.getServiceKey()) != null ? normalize(metadata.getServiceKey()) : "");
        joiner.add(normalize(metadata.getReleaseId()) != null ? normalize(metadata.getReleaseId()) : "");
        joiner.add(normalizedMethod != null ? normalizedMethod : "");
        joiner.add(normalizedPath != null ? normalizedPath : "");
        joiner.add(rawJson != null ? rawJson : "");
        return joiner.toString();
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        String normalized = normalize(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PublicationOutcome(long expectedDocumentCount, long publishedDocumentCount) {
    }

    private record UpsertOutcome(ApiMetadata metadata, boolean indexingRequired) {
    }
}
