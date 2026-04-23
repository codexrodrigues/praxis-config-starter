package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.Locale;
import org.praxisplatform.config.contract.AiContractSpec;

final class AgenticAuthoringDomainCatalogHints {

    static final String DEFAULT_SERVICE_KEY = "praxis-service";

    private AgenticAuthoringDomainCatalogHints() {
    }

    static void enrich(
            ObjectNode contextHints,
            AgenticAuthoringCandidate candidate,
            String artifactKind,
            String userPrompt,
            String serviceKey) {
        if (contextHints == null || candidate == null || candidate.resourcePath() == null || candidate.resourcePath().isBlank()) {
            return;
        }
        String resolvedServiceKey = valueOrDefault(serviceKey, DEFAULT_SERVICE_KEY);
        if (resolvedServiceKey.isBlank()) {
            return;
        }
        ObjectNode domainCatalog = contextHints.putObject("domainCatalog");
        domainCatalog.put("schemaVersion", AiContractSpec.DOMAIN_CATALOG_CONTEXT_HINT_SCHEMA_VERSION);
        domainCatalog.put("serviceKey", resolvedServiceKey);
        domainCatalog.put("type", "node");
        domainCatalog.put("intent", "authoring");
        domainCatalog.put("policyProfile", policyProfile(userPrompt));
        domainCatalog.put("limit", 12);
        String contextKey = contextKey(candidate.resourcePath());
        if (!contextKey.isBlank()) {
            domainCatalog.put("contextKey", contextKey);
        }
        String resourceKey = resourceKey(candidate.resourcePath());
        if (!resourceKey.isBlank()) {
            domainCatalog.put("resourceKey", resourceKey);
        }
        String query = query(userPrompt, candidate.resourcePath());
        if (!query.isBlank()) {
            domainCatalog.put("query", query);
        }
        var itemTypes = domainCatalog.putArray("itemTypes");
        itemTypes.add("node");
        if (requiresGovernanceContext(userPrompt)) {
            itemTypes.add("governance");
        }
        ObjectNode relationships = domainCatalog.putObject("relationships");
        relationships.put("enabled", true);
        relationships.put("federated", true);
        relationships.put("policyProfile", domainCatalog.path("policyProfile").asText());
        relationships.put("limit", 8);
        if (!query.isBlank()) {
            relationships.put("query", query);
        }
        String nodeType = nodeType(artifactKind);
        if (!nodeType.isBlank()) {
            domainCatalog.put("nodeType", nodeType);
        }
        String recommendedAuthoringFlow = recommendedAuthoringFlow(artifactKind, userPrompt);
        if (!recommendedAuthoringFlow.isBlank()) {
            domainCatalog.put("recommendedAuthoringFlow", recommendedAuthoringFlow);
        }
    }

    private static String contextKey(String resourcePath) {
        String[] parts = resourcePath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("api".equals(parts[i]) && !parts[i + 1].isBlank()) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private static String resourceKey(String resourcePath) {
        String[] parts = resourcePath.split("/");
        for (int i = 0; i < parts.length - 2; i++) {
            if ("api".equals(parts[i]) && !parts[i + 1].isBlank() && !parts[i + 2].isBlank()) {
                return parts[i + 1] + "." + parts[i + 2];
            }
        }
        return "";
    }

    private static String query(String userPrompt, String resourcePath) {
        String prompt = normalizeText(userPrompt);
        String resource = normalizeText(lastSegment(resourcePath));
        if (prompt.isBlank()) {
            return resource;
        }
        if (resource.isBlank() || prompt.contains(resource)) {
            return prompt;
        }
        return prompt + " " + resource;
    }

    private static String nodeType(String artifactKind) {
        return switch (valueOrDefault(artifactKind, "unknown")) {
            case "form", "table" -> "field";
            default -> "";
        };
    }

    private static String recommendedAuthoringFlow(String artifactKind, String userPrompt) {
        if (!"form".equals(valueOrDefault(artifactKind, "unknown"))) {
            return "";
        }
        String prompt = normalizeText(userPrompt);
        if (prompt.contains("lgpd")
                || prompt.contains("gdpr")
                || prompt.contains("compliance")
                || prompt.contains("governanca")
                || prompt.contains("privacidade")
                || prompt.contains("orientacao visual")) {
            return "shared_rule_authoring";
        }
        return "";
    }

    private static boolean requiresGovernanceContext(String userPrompt) {
        String prompt = normalizeText(userPrompt);
        return prompt.contains("lgpd")
                || prompt.contains("gdpr")
                || prompt.contains("compliance")
                || prompt.contains("governanca")
                || prompt.contains("privacidade");
    }

    private static String policyProfile(String userPrompt) {
        return requiresGovernanceContext(userPrompt) ? "compliance_review" : "authoring";
    }

    private static String lastSegment(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "";
        }
        String normalized = resourcePath.replaceAll("/+$", "");
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("vw-", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
