package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.domain.DomainKnowledgeEvidence;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagProjectKnowledgeMetadata;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "praxis.project-knowledge.rag-publication",
        name = "enabled",
        havingValue = "true")
@Slf4j
public class RagProjectKnowledgeDerivedIndexService implements ProjectKnowledgeDerivedIndexService {

    private static final String SUBJECT_TYPE_CONCEPT = "concept";
    private static final String MASKED_SUMMARY = "Knowledge payload masked by ai_visibility policy.";

    private final RagVectorStoreService ragVectorStoreService;
    private final ObjectMapper objectMapper;
    private final AiSensitiveDataRedactor redactor;

    @Override
    public void evidenceActivated(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        if (!ragVectorStoreService.isAvailable() || !canPublish(concept, evidence)) {
            return;
        }
        Document document = toDocument(concept, evidence);
        ragVectorStoreService.upsertDocuments(List.of(document));
        log.debug(
                "Published derived Project Knowledge RAG document for concept={} evidence={}",
                concept.getConceptKey(),
                evidence.getEvidenceKey());
    }

    @Override
    public void evidenceDeactivated(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        if (!ragVectorStoreService.isAvailable() || concept == null || evidence == null) {
            return;
        }
        ragVectorStoreService.deleteDocuments(List.of(documentId(concept, evidence)));
        log.debug(
                "Deleted derived Project Knowledge RAG document for concept={} evidence={}",
                concept.getConceptKey(),
                evidence.getEvidenceKey());
    }

    private boolean canPublish(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        return concept != null
                && evidence != null
                && StringUtils.hasText(concept.getConceptKey())
                && StringUtils.hasText(evidence.getEvidenceKey())
                && "active".equals(clean(concept.getLifecycle()))
                && "approved".equals(clean(concept.getCurationStatus()))
                && isAiVisible(concept)
                && "active".equals(clean(evidence.getStatus()))
                && SUBJECT_TYPE_CONCEPT.equals(clean(evidence.getSubjectType()))
                && concept.getId() != null
                && concept.getId().equals(evidence.getSubjectId());
    }

    private boolean isAiVisible(DomainKnowledgeConcept concept) {
        String visibility = clean(concept.getAiVisibility());
        return "allow".equals(visibility)
                || "mask".equals(visibility)
                || "summarize_only".equals(visibility);
    }

    private Document toDocument(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        String content = content(concept, evidence);
        String contentHash = contentHash(concept, evidence);
        Map<String, Object> metadata = RagProjectKnowledgeMetadata.from(
                concept,
                evidence,
                releaseId(concept, evidence),
                contentHash,
                0);
        return Document.builder()
                .id(documentId(concept, evidence))
                .text(content)
                .metadata(metadata)
                .build();
    }

    private String content(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        List<String> lines = new ArrayList<>();
        add(lines, "Project Knowledge");
        add(lines, "conceptKey: " + concept.getConceptKey());
        add(lines, "contextKey: " + concept.getContextKey());
        add(lines, "resourceKey: " + concept.getResourceKey());
        add(lines, "nodeType: " + concept.getNodeType());
        add(lines, "aiVisibility: " + concept.getAiVisibility());
        add(lines, "evidenceType: " + evidence.getEvidenceType());

        JsonNode payload = payload(concept);
        add(lines, "kind: " + text(payload, "kind"));
        if ("mask".equals(clean(concept.getAiVisibility()))) {
            add(lines, "summary: " + MASKED_SUMMARY);
        } else {
            add(lines, "label: " + concept.getLabel());
            add(lines, "description: " + concept.getDescription());
            add(lines, "summary: " + firstText(
                    text(payload, "safeSummary"),
                    text(payload, "summary"),
                    text(payload, "explanation")));
            add(lines, "sourceSummary: " + text(payload, "sourceSummary"));
            add(lines, "influence: " + text(payload, "influence"));
        }
        return redactor.redactText(String.join("\n", lines));
    }

    private void add(List<String> lines, String line) {
        if (!StringUtils.hasText(line)) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.endsWith(": null") || trimmed.endsWith(":")) {
            return;
        }
        lines.add(trimmed);
    }

    private JsonNode payload(DomainKnowledgeConcept concept) {
        if (concept == null || !StringUtils.hasText(concept.getPayload())) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode payload = objectMapper.readTree(concept.getPayload());
            return payload == null || payload.isNull() ? objectMapper.createObjectNode() : payload;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName) || !node.get(fieldName).isValueNode()) {
            return null;
        }
        String value = node.get(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String releaseId(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        String evidenceRelease = evidence.getSourceRelease() == null
                ? null
                : evidence.getSourceRelease().getReleaseKey();
        String conceptRelease = concept.getSourceRelease() == null
                ? null
                : concept.getSourceRelease().getReleaseKey();
        return RagDocumentIdentity.resolveReleaseId(
                firstText(evidenceRelease, conceptRelease, "project-knowledge"),
                null,
                null);
    }

    private String documentId(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        return RagDocumentIdentity.buildDocumentId(
                firstText(concept.getTenantId(), evidence.getTenantId()),
                firstText(concept.getEnvironment(), evidence.getEnvironment()),
                concept.getConceptKey(),
                releaseId(concept, evidence),
                RagResourceTypes.PROJECT_KNOWLEDGE,
                contentHash(concept, evidence),
                0);
    }

    private String contentHash(DomainKnowledgeConcept concept, DomainKnowledgeEvidence evidence) {
        return RagDocumentIdentity.sha256(concept.getConceptKey() + "|" + evidence.getEvidenceKey());
    }

    private String clean(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
