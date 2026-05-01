package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.DomainKnowledgeConcept;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.service.AiSensitiveDataRedactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@ConditionalOnBean({DomainKnowledgeConceptRepository.class, DomainKnowledgeEvidenceRepository.class})
public class AgenticAuthoringProjectKnowledgeService {

    private static final String MASKED_SUMMARY = "Knowledge payload masked by ai_visibility policy.";

    private final DomainKnowledgeConceptRepository conceptRepository;
    private final DomainKnowledgeEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;
    private final AiSensitiveDataRedactor redactor;

    public List<AgenticAuthoringProjectKnowledgeProjection> retrieve(
            AgenticAuthoringProjectKnowledgeQuery query) {
        if (query == null
                || !StringUtils.hasText(query.tenantId())
                || !StringUtils.hasText(query.environment())) {
            return List.of();
        }
        List<DomainKnowledgeConcept> candidates = conceptRepository.findGovernedProjectKnowledgeCandidates(
                query.tenantId(),
                query.environment(),
                query.contextKey(),
                query.resourceKey(),
                query.nodeType(),
                PageRequest.of(0, query.limit()));
        List<AgenticAuthoringProjectKnowledgeProjection> projections = new ArrayList<>();
        for (DomainKnowledgeConcept candidate : candidates) {
            if (!isGovernedCandidate(candidate) || !matchesScope(candidate, query)) {
                continue;
            }
            JsonNode payload = payload(candidate);
            String kind = text(payload, "kind");
            if (!matchesKind(kind, query.kinds())) {
                continue;
            }
            if (!hasActiveEvidence(candidate)) {
                continue;
            }
            projections.add(toProjection(candidate, payload, kind));
            if (projections.size() >= query.limit()) {
                break;
            }
        }
        return List.copyOf(projections);
    }

    private AgenticAuthoringProjectKnowledgeProjection toProjection(
            DomainKnowledgeConcept concept,
            JsonNode payload,
            String kind) {
        String visibility = clean(concept.getAiVisibility());
        String summary = "mask".equals(visibility)
                ? MASKED_SUMMARY
                : safeText(firstText(
                        text(payload, "safeSummary"),
                        text(payload, "summary"),
                        text(payload, "explanation"),
                        concept.getLabel(),
                        concept.getDescription()));
        String sourceSummary = safeText(firstText(
                text(payload, "sourceSummary"),
                text(payload, "source"),
                concept.getSourceRelease() == null ? null : concept.getSourceRelease().getReleaseKey(),
                "domain_knowledge_concept"));
        return new AgenticAuthoringProjectKnowledgeProjection(
                concept.getId() == null ? null : concept.getId().toString(),
                concept.getConceptKey(),
                kind,
                new AgenticAuthoringProjectKnowledgeProjection.Scope(
                        concept.getTenantId(),
                        concept.getEnvironment(),
                        concept.getContextKey(),
                        concept.getResourceKey()),
                new AgenticAuthoringProjectKnowledgeProjection.Status(
                        concept.getLifecycle(),
                        concept.getCurationStatus()),
                visibility,
                sourceSummary,
                influence(concept, payload),
                summary,
                evidence(concept, kind));
    }

    private boolean isGovernedCandidate(DomainKnowledgeConcept concept) {
        if (concept == null) {
            return false;
        }
        return "active".equals(clean(concept.getLifecycle()))
                && "approved".equals(clean(concept.getCurationStatus()))
                && ("allow".equals(clean(concept.getAiVisibility()))
                    || "mask".equals(clean(concept.getAiVisibility()))
                    || "summarize_only".equals(clean(concept.getAiVisibility())));
    }

    private boolean matchesScope(DomainKnowledgeConcept concept, AgenticAuthoringProjectKnowledgeQuery query) {
        return matchesOptionalScope(concept.getContextKey(), query.contextKey())
                && matchesOptionalScope(concept.getResourceKey(), query.resourceKey());
    }

    private boolean matchesOptionalScope(String candidateValue, String queryValue) {
        if (!StringUtils.hasText(queryValue) || !StringUtils.hasText(candidateValue)) {
            return true;
        }
        return queryValue.equals(candidateValue);
    }

    private boolean matchesKind(String kind, List<String> expectedKinds) {
        if (expectedKinds == null || expectedKinds.isEmpty()) {
            return StringUtils.hasText(kind);
        }
        return StringUtils.hasText(kind) && expectedKinds.contains(kind);
    }

    private boolean hasActiveEvidence(DomainKnowledgeConcept concept) {
        if (concept == null || concept.getId() == null) {
            return false;
        }
        return !evidenceRepository.findByTenantIdAndEnvironmentAndSubjectTypeAndSubjectIdAndStatus(
                concept.getTenantId(),
                concept.getEnvironment(),
                "concept",
                concept.getId(),
                "active").isEmpty();
    }

    private String influence(DomainKnowledgeConcept concept, JsonNode payload) {
        String explicitInfluence = text(payload, "influence");
        if (StringUtils.hasText(explicitInfluence)) {
            return safeText(explicitInfluence);
        }
        if ("mask".equals(clean(concept.getAiVisibility()))) {
            return "masked_context";
        }
        return "context_hint";
    }

    private List<String> evidence(DomainKnowledgeConcept concept, String kind) {
        List<String> evidence = new ArrayList<>();
        if (StringUtils.hasText(concept.getConceptKey())) {
            evidence.add("domain-knowledge:concept:" + concept.getConceptKey());
        }
        if (StringUtils.hasText(kind)) {
            evidence.add("project-knowledge-kind:" + kind);
        }
        if (StringUtils.hasText(concept.getAiVisibility())) {
            evidence.add("ai-visibility:" + clean(concept.getAiVisibility()));
        }
        evidence.add("domain-knowledge:evidence-status:active");
        if (concept.getSourceRelease() != null && StringUtils.hasText(concept.getSourceRelease().getReleaseKey())) {
            evidence.add("source-release:" + concept.getSourceRelease().getReleaseKey());
        }
        return List.copyOf(evidence);
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

    private String safeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return redactor.redactText(value.trim());
    }

    private String clean(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
