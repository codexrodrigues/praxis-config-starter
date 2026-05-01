package org.praxisplatform.config.rag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.springframework.util.StringUtils;

/**
 * Builds derived RAG provenance for Project Knowledge candidates.
 *
 * <p>The returned metadata is intentionally not authoritative. Retrieval must reload the
 * canonical Domain Knowledge rows and re-check active evidence before AI authoring influence.
 */
public final class RagProjectKnowledgeMetadata {

    private RagProjectKnowledgeMetadata() {
    }

    public static Map<String, Object> from(
            DomainKnowledgeConcept concept,
            DomainKnowledgeEvidence evidence,
            String releaseId,
            String contentHash,
            int chunkIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE);
        metadata.put(RagMetadataKeys.DOC_TYPE, RagResourceTypes.PROJECT_KNOWLEDGE);
        metadata.put(RagMetadataKeys.TENANT_ID, firstText(
                concept == null ? null : concept.getTenantId(),
                evidence == null ? null : evidence.getTenantId()));
        metadata.put(RagMetadataKeys.ENVIRONMENT, firstText(
                concept == null ? null : concept.getEnvironment(),
                evidence == null ? null : evidence.getEnvironment()));
        metadata.put(RagMetadataKeys.RELEASE_ID, releaseId);
        metadata.put(RagMetadataKeys.CONTENT_HASH, contentHash);
        metadata.put(RagMetadataKeys.CHUNK_INDEX, Math.max(0, chunkIndex));
        if (concept != null) {
            metadata.put(RagMetadataKeys.RESOURCE_ID, concept.getConceptKey());
            metadata.put(RagMetadataKeys.COMPONENT_ID, concept.getConceptKey());
            metadata.put(RagMetadataKeys.DOMAIN_KNOWLEDGE_CONCEPT_ID, stringify(concept.getId()));
            metadata.put(RagMetadataKeys.DOMAIN_KNOWLEDGE_CONCEPT_KEY, concept.getConceptKey());
            metadata.put(RagMetadataKeys.AI_VISIBILITY, concept.getAiVisibility());
            metadata.put(RagMetadataKeys.CONTEXT_KEY, concept.getContextKey());
            metadata.put(RagMetadataKeys.RESOURCE_KEY, concept.getResourceKey());
        }
        if (evidence != null) {
            metadata.put(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_ID, stringify(evidence.getId()));
            metadata.put(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_KEY, evidence.getEvidenceKey());
            metadata.put(RagMetadataKeys.DOMAIN_KNOWLEDGE_EVIDENCE_STATUS, evidence.getStatus());
        }
        metadata.entrySet().removeIf(entry -> isEmpty(entry.getValue()));
        return Map.copyOf(metadata);
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return !StringUtils.hasText(text);
        }
        return false;
    }

    private static String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        return StringUtils.hasText(second) ? second.trim() : null;
    }

    private static String stringify(Object value) {
        return Objects.toString(value, null);
    }
}
