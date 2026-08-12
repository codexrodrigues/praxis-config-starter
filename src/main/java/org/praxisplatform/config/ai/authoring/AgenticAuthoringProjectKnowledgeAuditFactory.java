package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces the safe, terminal audit projection for governed Project Knowledge.
 *
 * <p>The audit is independent from preview materialization: consulted knowledge remains
 * observable even when the turn correctly stops at clarification. Citation status still depends
 * exclusively on source references emitted by a materialized plan or patch.
 */
final class AgenticAuthoringProjectKnowledgeAuditFactory {

    private AgenticAuthoringProjectKnowledgeAuditFactory() {
    }

    static JsonNode create(
            ObjectMapper objectMapper,
            JsonNode contextHints,
            JsonNode minimalFormPlan,
            JsonNode compiledFormPatch) {
        JsonNode projectKnowledge = contextHints == null
                ? MissingNode.getInstance()
                : contextHints.path("projectKnowledge");
        JsonNode entries = projectKnowledge.path("entries");
        if (!projectKnowledge.isObject() || !entries.isArray() || entries.isEmpty()) {
            return null;
        }
        Set<String> sourceRefs = sourceRefs(minimalFormPlan, compiledFormPatch);
        ObjectNode audit = objectMapper.createObjectNode();
        audit.put("schemaVersion", "praxis-agentic-authoring-project-knowledge-audit.v1");
        audit.put("source", safeText(projectKnowledge.path("source").asText("domain_knowledge_concept")));
        ArrayNode safeEntries = audit.putArray("entries");
        int citedCount = 0;
        for (JsonNode entry : entries) {
            if (!entry.isObject()) {
                continue;
            }
            String knowledgeId = safeText(entry.path("knowledgeId").asText(""));
            String conceptKey = safeText(entry.path("conceptKey").asText(""));
            List<String> matchedRefs = matchingProjectKnowledgeRefs(sourceRefs, knowledgeId, conceptKey);
            if (!matchedRefs.isEmpty()) {
                citedCount++;
            }
            ObjectNode safeEntry = safeEntries.addObject();
            safeEntry.put("knowledgeId", knowledgeId);
            safeEntry.put("conceptKey", conceptKey);
            safeEntry.put("kind", safeText(entry.path("kind").asText("")));
            safeEntry.put("visibility", safeText(entry.path("visibility").asText("")));
            safeEntry.put("influence", safeText(entry.path("influence").asText("")));
            safeEntry.put("sourceSummary", safeText(entry.path("sourceSummary").asText("")));
            safeEntry.put("cited", !matchedRefs.isEmpty());
            safeEntry.set("sourceRefs", objectMapper.valueToTree(matchedRefs));
        }
        audit.put("influenceCount", safeEntries.size());
        audit.put("citedCount", citedCount);
        audit.put("uncitedCount", Math.max(0, safeEntries.size() - citedCount));
        audit.put("citationPolicy", "sourceRefs must cite projectKnowledge entries when they materially influence the plan.");
        return audit;
    }

    private static Set<String> sourceRefs(JsonNode minimalFormPlan, JsonNode compiledFormPatch) {
        Set<String> refs = new LinkedHashSet<>();
        collectSourceRefs(minimalFormPlan, refs);
        collectSourceRefs(compiledFormPatch, refs);
        return refs;
    }

    private static void collectSourceRefs(JsonNode node, Set<String> refs) {
        JsonNode sourceRefs = node == null ? MissingNode.getInstance() : node.path("sourceRefs");
        if (!sourceRefs.isArray()) {
            return;
        }
        for (JsonNode sourceRef : sourceRefs) {
            if (sourceRef.isTextual() && !sourceRef.asText("").isBlank()) {
                refs.add(sourceRef.asText());
            }
        }
    }

    private static List<String> matchingProjectKnowledgeRefs(
            Set<String> sourceRefs,
            String knowledgeId,
            String conceptKey) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String sourceRef : sourceRefs) {
            if (!sourceRef.startsWith("projectKnowledge:")) {
                continue;
            }
            String ref = sourceRef.substring("projectKnowledge:".length());
            if ((!knowledgeId.isBlank() && knowledgeId.equals(ref))
                    || (!conceptKey.isBlank() && conceptKey.equals(ref))) {
                matches.add(sourceRef);
            }
        }
        return List.copyOf(matches);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
