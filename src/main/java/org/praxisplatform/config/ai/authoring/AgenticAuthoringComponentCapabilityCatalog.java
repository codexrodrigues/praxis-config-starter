package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class AgenticAuthoringComponentCapabilityCatalog {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String version;
    private final String componentId;
    private final List<ComponentCapability> capabilities;

    private AgenticAuthoringComponentCapabilityCatalog(
            String version,
            String componentId,
            List<ComponentCapability> capabilities) {
        this.version = version;
        this.componentId = componentId;
        this.capabilities = List.copyOf(capabilities);
    }

    static AgenticAuthoringComponentCapabilityCatalog load(String resourcePath) {
        try (InputStream inputStream = AgenticAuthoringComponentCapabilityCatalog.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Component capability catalog resource not found: " + resourcePath);
            }
            JsonNode root = OBJECT_MAPPER.readTree(inputStream);
            return new AgenticAuthoringComponentCapabilityCatalog(
                    requiredText(root, "version"),
                    requiredText(root, "componentId"),
                    parseCapabilities(root.path("capabilities")));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read component capability catalog: " + resourcePath, exception);
        }
    }

    boolean matchesAnyModificationPrompt(String normalizedPrompt) {
        return capabilities.stream().anyMatch(capability -> capability.matches(normalizedPrompt));
    }

    Optional<String> resolveChangeKind(String normalizedPrompt) {
        ComponentCapability bestCapability = null;
        int bestScore = 0;
        for (ComponentCapability capability : capabilities) {
            int score = capability.matchScore(normalizedPrompt);
            if (score > bestScore) {
                bestCapability = capability;
                bestScore = score;
            }
        }
        return bestCapability == null ? Optional.empty() : Optional.of(bestCapability.changeKind());
    }

    boolean supports(String changeKind, String normalizedPrompt) {
        return capabilities.stream()
                .anyMatch(capability -> capability.changeKind().equals(changeKind)
                        && capability.matches(normalizedPrompt));
    }

    Optional<String> resolveField(String changeKind, String normalizedPrompt) {
        return capabilities.stream()
                .filter(capability -> capability.changeKind().equals(changeKind))
                .flatMap(capability -> capability.fieldAliases().stream())
                .filter(alias -> alias.matches(normalizedPrompt))
                .map(ComponentFieldAlias::field)
                .findFirst();
    }

    List<ComponentCapability> capabilities() {
        return capabilities;
    }

    String version() {
        return version;
    }

    String componentId() {
        return componentId;
    }

    private static List<ComponentCapability> parseCapabilities(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty()) {
            throw new IllegalStateException("Component capability catalog must declare at least one capability.");
        }
        List<ComponentCapability> parsed = new ArrayList<>();
        for (JsonNode node : nodes) {
            parsed.add(new ComponentCapability(
                    requiredText(node, "id"),
                    requiredText(node, "changeKind"),
                    parseStringList(node.path("triggerTerms")),
                    parseFieldAliases(node.path("fieldAliases")),
                    parseExamples(node.path("examples"))));
        }
        return parsed;
    }

    private static List<ComponentCapabilityExample> parseExamples(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<ComponentCapabilityExample> parsed = new ArrayList<>();
        for (JsonNode node : nodes) {
            parsed.add(new ComponentCapabilityExample(
                    requiredText(node, "prompt"),
                    requiredText(node, "intent"),
                    parseStringList(node.path("configHints"))));
        }
        return parsed;
    }

    private static List<ComponentFieldAlias> parseFieldAliases(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<ComponentFieldAlias> parsed = new ArrayList<>();
        for (JsonNode node : nodes) {
            parsed.add(new ComponentFieldAlias(
                    requiredText(node, "field"),
                    parseStringList(node.path("aliases"))));
        }
        return parsed;
    }

    private static List<String> parseStringList(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.isTextual() || node.asText().isBlank()) {
                throw new IllegalStateException("Catalog lists must contain only non-blank strings.");
            }
            parsed.add(node.asText());
        }
        return parsed;
    }

    private static String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Component capability catalog field is required: " + fieldName);
        }
        return value;
    }

    record ComponentCapability(
            String id,
            String changeKind,
            List<String> triggerTerms,
            List<ComponentFieldAlias> fieldAliases,
            List<ComponentCapabilityExample> examples) {

        boolean matches(String normalizedPrompt) {
            return containsAny(normalizedPrompt, triggerTerms);
        }

        int matchScore(String normalizedPrompt) {
            int score = matchCount(normalizedPrompt, triggerTerms);
            if (score == 0) {
                return 0;
            }
            for (ComponentFieldAlias alias : fieldAliases) {
                score += alias.matchScore(normalizedPrompt);
            }
            return score;
        }
    }

    record ComponentCapabilityExample(
            String prompt,
            String intent,
            List<String> configHints) {
    }

    record ComponentFieldAlias(String field, List<String> aliases) {

        boolean matches(String normalizedPrompt) {
            return containsAny(normalizedPrompt, aliases);
        }

        int matchScore(String normalizedPrompt) {
            int count = matchCount(normalizedPrompt, aliases);
            return count == 0 ? 0 : 100 + count;
        }
    }

    private static boolean containsAny(String value, List<String> terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static int matchCount(String value, List<String> terms) {
        int count = 0;
        for (String term : terms) {
            if (value.contains(term)) {
                count++;
            }
        }
        return count;
    }
}
