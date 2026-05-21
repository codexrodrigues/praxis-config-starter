package org.praxisplatform.config.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RagVectorStoreService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider;
    private final String tableName;

    public RagVectorStoreService(
            ObjectProvider<VectorStore> vectorStoreProvider,
            @Qualifier("configNamedParameterJdbcTemplate") ObjectProvider<NamedParameterJdbcTemplate> jdbcTemplateProvider,
            @Value("${praxis.ai.rag.vector-store.table:vector_store}") String tableName) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.jdbcTemplateProvider = jdbcTemplateProvider;
        this.tableName = resolveTableName(tableName);
    }

    public void deleteDocumentsByScope(
            String tenantId,
            String environment,
            String releaseId,
            String sourceId,
            String sourceKind) {
        if (vectorStoreProvider.getIfAvailable() == null) {
            return;
        }
        NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            log.warn("Skipping vector store stale document purge because configNamedParameterJdbcTemplate is unavailable.");
            return;
        }

        String sql = """
            DELETE FROM %s
            WHERE COALESCE(metadata ->> 'tenantId', 'global') = :tenantId
              AND COALESCE(metadata ->> 'environment', 'global') = :environment
              AND COALESCE(metadata ->> 'releaseId', 'v1') = :releaseId
              AND COALESCE(metadata ->> 'componentId', metadata ->> 'sourceId', metadata ->> 'resourceId', id) = :sourceId
              AND COALESCE(metadata ->> 'docType', metadata ->> 'sourceKind', metadata ->> 'resourceType') = :sourceKind
            """.formatted(tableName);

        Map<String, Object> params = Map.of(
            "tenantId", RagDocumentIdentity.normalizeToken(tenantId, "global"),
            "environment", RagDocumentIdentity.normalizeToken(environment, "global"),
            "releaseId", RagDocumentIdentity.normalizeToken(releaseId, "v1"),
            "sourceId", RagDocumentIdentity.normalizeToken(sourceId, "unknown-source"),
            "sourceKind", RagDocumentIdentity.normalizeToken(sourceKind, "unknown-kind")
        );

        try {
            int deletedRows = jdbcTemplate.update(sql, params);
            log.debug("Purged {} stale vector store documents for tenant={}, env={}, release={}, sourceId={}, sourceKind={}",
                    deletedRows, tenantId, environment, releaseId, sourceId, sourceKind);
        } catch (Exception ex) {
            log.warn("Failed to purge documents from vector store. This might be normal if the schema is not initialized yet.", ex);
        }
    }

    public boolean isAvailable() {
        return vectorStoreProvider.getIfAvailable() != null;
    }

    public void upsertDocuments(List<Document> documents) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || documents == null || documents.isEmpty()) {
            return;
        }
        Map<String, Document> deduplicatedDocuments = new LinkedHashMap<>();
        int validCount = 0;
        for (Document document : documents) {
            if (document == null || document.getId() == null || document.getId().isBlank()) {
                continue;
            }
            validCount++;
            deduplicatedDocuments.putIfAbsent(buildDedupeKey(document), document);
        }
        if (deduplicatedDocuments.isEmpty()) {
            return;
        }
        List<Document> validDocuments = new ArrayList<>(deduplicatedDocuments.values());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Document document : validDocuments) {
            ids.add(document.getId());
        }
        if (validDocuments.size() < validCount) {
            log.debug(
                    "Deduplicated RAG upsert batch from {} to {} documents using metadata scope/hash key.",
                    validCount,
                    validDocuments.size());
        }
        vectorStore.delete(new ArrayList<>(ids));
        vectorStore.add(validDocuments);
    }

    public void deleteDocuments(List<String> ids) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || ids == null || ids.isEmpty()) {
            return;
        }
        List<String> validIds = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (validIds.isEmpty()) {
            return;
        }
        vectorStore.delete(validIds);
    }

    public List<Document> search(String query, int limit, Filter.Expression filterExpression) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null || query == null || query.isBlank()) {
            return List.of();
        }
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, limit))
                .similarityThresholdAll();
        if (filterExpression != null) {
            builder.filterExpression(filterExpression);
        }
        return vectorStore.similaritySearch(builder.build());
    }

    public RagCorpusReleaseStatus corpusReleaseStatus(
            String tenantId,
            String environment,
            String releaseId,
            long expectedChunkCount) {
        NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
        if (jdbcTemplate == null) {
            return RagCorpusReleaseStatus.unavailable(
                    normalizedScope(tenantId, "global"),
                    normalizedScope(environment, "global"),
                    normalizedScope(releaseId, "v1"),
                    expectedChunkCount,
                    "configNamedParameterJdbcTemplate-unavailable");
        }
        String resolvedTenant = normalizedScope(tenantId, "global");
        String resolvedEnvironment = normalizedScope(environment, "global");
        String resolvedRelease = normalizedScope(releaseId, "v1");
        String sql = """
            SELECT
              COALESCE(metadata ->> 'sourceId', metadata ->> 'componentId', metadata ->> 'resourceId', id) AS source_id,
              COALESCE(metadata ->> 'sourceKind', metadata ->> 'docType', metadata ->> 'resourceType', 'unknown-kind') AS source_kind,
              COALESCE(metadata ->> 'chunkKind', 'summary') AS chunk_kind,
              COALESCE(metadata ->> 'aiVisibility', 'allow') AS ai_visibility,
              COALESCE(metadata ->> 'corpusVersion', '') AS corpus_version,
              MAX(metadata ->> 'publishedAt') AS latest_published_at,
              COUNT(*) AS document_count
            FROM %s
            WHERE COALESCE(metadata ->> 'tenantId', 'global') = :tenantId
              AND COALESCE(metadata ->> 'environment', 'global') = :environment
              AND COALESCE(metadata ->> 'releaseId', 'v1') = :releaseId
              AND COALESCE(metadata ->> 'resourceType', metadata ->> 'docType', metadata ->> 'sourceKind') = :resourceType
            GROUP BY source_id, source_kind, chunk_kind, ai_visibility, corpus_version
            ORDER BY source_id, chunk_kind
            """.formatted(tableName);
        Map<String, Object> params = Map.of(
                "tenantId", resolvedTenant,
                "environment", resolvedEnvironment,
                "releaseId", resolvedRelease,
                "resourceType", RagResourceTypes.COMPONENT_DEFINITION);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
            return RagCorpusReleaseStatus.fromRows(
                    resolvedTenant,
                    resolvedEnvironment,
                    resolvedRelease,
                    expectedChunkCount,
                    rows);
        } catch (Exception ex) {
            log.warn("Failed to read RAG corpus release status for release '{}'.", resolvedRelease, ex);
            return RagCorpusReleaseStatus.unavailable(
                    resolvedTenant,
                    resolvedEnvironment,
                    resolvedRelease,
                    expectedChunkCount,
                    "vector-store-status-query-failed");
        }
    }

    private String buildDedupeKey(Document document) {
        Map<String, Object> metadata = document.getMetadata() != null ? document.getMetadata() : Map.of();
        String tenantId = normalizeMetadataToken(
                metadata.get(RagMetadataKeys.TENANT_ID),
                "global");
        String environment = normalizeMetadataToken(
                metadata.get(RagMetadataKeys.ENVIRONMENT),
                "global");
        String releaseId = normalizeMetadataToken(
                firstNonNull(metadata.get(RagMetadataKeys.RELEASE_ID), metadata.get(RagMetadataKeys.VERSION)),
                "v1");
        String componentId = normalizeMetadataToken(
                firstNonNull(metadata.get(RagMetadataKeys.COMPONENT_ID), metadata.get(RagMetadataKeys.RESOURCE_ID)),
                document.getId());
        String docType = normalizeMetadataToken(
                firstNonNull(metadata.get(RagMetadataKeys.DOC_TYPE), metadata.get(RagMetadataKeys.RESOURCE_TYPE)),
                "unknown-doc");
        String contentHash = normalizeMetadataToken(
                metadata.get(RagMetadataKeys.CONTENT_HASH),
                RagDocumentIdentity.sha256(document.getText() != null ? document.getText() : document.getId()));
        int chunkIndex = toChunkIndex(metadata.get(RagMetadataKeys.CHUNK_INDEX));
        return String.join(
                "|",
                tenantId,
                environment,
                releaseId,
                componentId,
                docType,
                contentHash,
                Integer.toString(chunkIndex));
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String normalizeMetadataToken(Object value, String fallback) {
        return RagDocumentIdentity.normalizeToken(value != null ? String.valueOf(value) : null, fallback);
    }

    private String normalizedScope(String value, String fallback) {
        return RagDocumentIdentity.normalizeToken(value, fallback);
    }

    private int toChunkIndex(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String resolveTableName(String configuredTableName) {
        String candidate = configuredTableName != null ? configuredTableName.trim() : "";
        if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return candidate;
        }
        log.warn(
                "Ignoring invalid RAG vector store table name '{}'; using vector_store.",
                configuredTableName);
        return "vector_store";
    }

    public record RagCorpusReleaseStatus(
            boolean available,
            boolean reconciled,
            String tenantId,
            String environment,
            String releaseId,
            long expectedChunkCount,
            long documentCount,
            int sourceCount,
            Map<String, Long> chunkKindCounts,
            Map<String, Long> visibilityCounts,
            List<SourceStatus> sources,
            String latestPublishedAt,
            List<String> warnings
    ) {
        private static RagCorpusReleaseStatus unavailable(
                String tenantId,
                String environment,
                String releaseId,
                long expectedChunkCount,
                String warning) {
            long normalizedExpected = Math.max(0, expectedChunkCount);
            return new RagCorpusReleaseStatus(
                    false,
                    normalizedExpected == 0,
                    tenantId,
                    environment,
                    releaseId,
                    normalizedExpected,
                    0,
                    0,
                    Map.of(),
                    Map.of(),
                    List.of(),
                    "",
                    List.of(warning));
        }

        private static RagCorpusReleaseStatus fromRows(
                String tenantId,
                String environment,
                String releaseId,
                long expectedChunkCount,
                List<Map<String, Object>> rows) {
            long normalizedExpected = Math.max(0, expectedChunkCount);
            Map<String, Long> chunkKindCounts = new LinkedHashMap<>();
            Map<String, Long> visibilityCounts = new LinkedHashMap<>();
            Map<String, SourceAccumulator> sources = new LinkedHashMap<>();
            long documentCount = 0;
            String latestPublishedAt = "";
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    long count = toLong(row.get("document_count"));
                    documentCount += count;
                    String sourceId = text(row.get("source_id"), "unknown-source");
                    String sourceKind = text(row.get("source_kind"), "unknown-kind");
                    String chunkKind = text(row.get("chunk_kind"), "summary");
                    String visibility = text(row.get("ai_visibility"), "allow");
                    String corpusVersion = text(row.get("corpus_version"), "");
                    String publishedAt = text(row.get("latest_published_at"), "");
                    chunkKindCounts.merge(chunkKind, count, Long::sum);
                    visibilityCounts.merge(visibility, count, Long::sum);
                    SourceAccumulator source = sources.computeIfAbsent(
                            sourceId,
                            ignored -> new SourceAccumulator(sourceId, sourceKind));
                    source.documentCount += count;
                    source.chunkKinds.add(chunkKind);
                    if (!corpusVersion.isBlank()) {
                        source.corpusVersions.add(corpusVersion);
                    }
                    if (!publishedAt.isBlank()
                            && (source.latestPublishedAt.isBlank()
                            || publishedAt.compareTo(source.latestPublishedAt) > 0)) {
                        source.latestPublishedAt = publishedAt;
                    }
                    if (!publishedAt.isBlank()
                            && (latestPublishedAt.isBlank() || publishedAt.compareTo(latestPublishedAt) > 0)) {
                        latestPublishedAt = publishedAt;
                    }
                }
            }
            List<String> warnings = new ArrayList<>();
            if (normalizedExpected > 0 && documentCount != normalizedExpected) {
                warnings.add("corpus-chunk-count-mismatch");
            }
            if (documentCount == 0) {
                warnings.add("corpus-release-empty");
            }
            return new RagCorpusReleaseStatus(
                    true,
                    normalizedExpected == 0 || documentCount == normalizedExpected,
                    tenantId,
                    environment,
                    releaseId,
                    normalizedExpected,
                    documentCount,
                    sources.size(),
                    Map.copyOf(chunkKindCounts),
                    Map.copyOf(visibilityCounts),
                    sources.values().stream().map(SourceAccumulator::toStatus).toList(),
                    latestPublishedAt,
                    List.copyOf(warnings));
        }

        private static long toLong(Object value) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value == null) {
                return 0;
            }
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ex) {
                return 0;
            }
        }

        private static String text(Object value, String fallback) {
            if (value == null) {
                return fallback;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? fallback : text;
        }
    }

    public record SourceStatus(
            String sourceId,
            String sourceKind,
            long documentCount,
            List<String> chunkKinds,
            List<String> corpusVersions,
            String latestPublishedAt
    ) {
    }

    private static final class SourceAccumulator {
        private final String sourceId;
        private final String sourceKind;
        private final LinkedHashSet<String> chunkKinds = new LinkedHashSet<>();
        private final LinkedHashSet<String> corpusVersions = new LinkedHashSet<>();
        private long documentCount;
        private String latestPublishedAt = "";

        private SourceAccumulator(String sourceId, String sourceKind) {
            this.sourceId = sourceId;
            this.sourceKind = sourceKind;
        }

        private SourceStatus toStatus() {
            return new SourceStatus(
                    sourceId,
                    sourceKind,
                    documentCount,
                    List.copyOf(chunkKinds),
                    List.copyOf(corpusVersions),
                    latestPublishedAt);
        }
    }
}
