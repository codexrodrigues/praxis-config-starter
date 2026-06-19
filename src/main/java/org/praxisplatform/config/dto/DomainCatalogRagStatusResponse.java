package org.praxisplatform.config.dto;

import java.util.List;
import java.util.Map;
import org.praxisplatform.config.rag.RagVectorStoreService;

public record DomainCatalogRagStatusResponse(
        String schemaVersion,
        DomainCatalogReleaseResponse release,
        String resourceType,
        boolean ragPublicationEnabled,
        boolean vectorStoreAvailable,
        boolean statusAvailable,
        boolean reconciled,
        long expectedDocumentCount,
        long actualDocumentCount,
        int sourceCount,
        Map<String, Long> chunkKindCounts,
        Map<String, Long> visibilityCounts,
        List<SourceStatus> sources,
        String latestPublishedAt,
        List<String> warnings
) {

    public static DomainCatalogRagStatusResponse from(
            DomainCatalogReleaseResponse release,
            String resourceType,
            boolean ragPublicationEnabled,
            boolean vectorStoreAvailable,
            RagVectorStoreService.RagCorpusReleaseStatus status) {
        return new DomainCatalogRagStatusResponse(
                "praxis.domain-catalog-rag-status/v0.1",
                release,
                resourceType,
                ragPublicationEnabled,
                vectorStoreAvailable,
                status.available(),
                status.reconciled(),
                status.expectedChunkCount(),
                status.documentCount(),
                status.sourceCount(),
                status.chunkKindCounts(),
                status.visibilityCounts(),
                status.sources().stream().map(SourceStatus::from).toList(),
                status.latestPublishedAt(),
                status.warnings());
    }

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
