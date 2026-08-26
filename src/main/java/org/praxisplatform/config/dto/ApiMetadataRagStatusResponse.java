package org.praxisplatform.config.dto;

import java.util.List;
import java.util.Map;
import org.praxisplatform.config.rag.RagVectorStoreService;

public record ApiMetadataRagStatusResponse(
        String schemaVersion,
        String tenantId,
        String environment,
        String serviceKey,
        String releaseId,
        String resourceType,
        boolean ragPublicationEnabled,
        boolean vectorStoreAvailable,
        boolean statusAvailable,
        boolean reconciled,
        String indexingStatus,
        long indexingRevision,
        int indexingAttempt,
        long expectedDocumentCount,
        long legacyIndexedDocumentCount,
        long publishedDocumentCount,
        long actualDocumentCount,
        int sourceCount,
        Map<String, Long> chunkKindCounts,
        Map<String, Long> visibilityCounts,
        List<SourceStatus> sources,
        String latestPublishedAt,
        String failureCode,
        String failureMessage,
        String requestedAt,
        String startedAt,
        String completedAt,
        String updatedAt,
        List<String> warnings
) {

    public static ApiMetadataRagStatusResponse from(
            String tenantId,
            String environment,
            String serviceKey,
            String releaseId,
            String resourceType,
            boolean ragPublicationEnabled,
            boolean vectorStoreAvailable,
            RagVectorStoreService.RagCorpusReleaseStatus status,
            IndexingStatus indexing) {
        boolean indexingReady = "READY".equals(indexing.status())
                && indexing.legacyIndexedDocumentCount() == status.expectedChunkCount()
                && indexing.publishedDocumentCount() == status.expectedChunkCount();
        return new ApiMetadataRagStatusResponse(
                "praxis.api-metadata-rag-status/v0.2",
                tenantId,
                environment,
                serviceKey,
                releaseId,
                resourceType,
                ragPublicationEnabled,
                vectorStoreAvailable,
                status.available(),
                indexingReady && status.reconciled(),
                indexing.status(),
                indexing.revision(),
                indexing.attempt(),
                status.expectedChunkCount(),
                indexing.legacyIndexedDocumentCount(),
                indexing.publishedDocumentCount(),
                status.documentCount(),
                status.sourceCount(),
                status.chunkKindCounts(),
                status.visibilityCounts(),
                status.sources().stream().map(SourceStatus::from).toList(),
                status.latestPublishedAt(),
                indexing.failureCode(),
                indexing.failureMessage(),
                indexing.requestedAt(),
                indexing.startedAt(),
                indexing.completedAt(),
                indexing.updatedAt(),
                status.warnings());
    }

    public record IndexingStatus(
            String status,
            long revision,
            int attempt,
            long legacyIndexedDocumentCount,
            long publishedDocumentCount,
            String failureCode,
            String failureMessage,
            String requestedAt,
            String startedAt,
            String completedAt,
            String updatedAt
    ) { }

    public record SourceStatus(
            String sourceId,
            String sourceKind,
            long documentCount,
            List<String> chunkKinds,
            List<String> corpusVersions,
            String latestPublishedAt
    ) {

        private static SourceStatus from(RagVectorStoreService.SourceStatus source) {
            return new SourceStatus(
                    source.sourceId(),
                    source.sourceKind(),
                    source.documentCount(),
                    source.chunkKinds(),
                    source.corpusVersions(),
                    source.latestPublishedAt());
        }
    }
}
