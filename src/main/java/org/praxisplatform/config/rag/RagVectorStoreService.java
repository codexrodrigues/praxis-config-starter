package org.praxisplatform.config.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagVectorStoreService {

    private final ObjectProvider<VectorStore> vectorStoreProvider;

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
}
