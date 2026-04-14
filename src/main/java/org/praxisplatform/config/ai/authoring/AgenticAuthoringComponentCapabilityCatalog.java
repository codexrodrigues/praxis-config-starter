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
        return capabilities.stream()
                .filter(capability -> capability.matches(normalizedPrompt))
                .map(ComponentCapability::changeKind)
                .findFirst();
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
                    parseFieldAliases(node.path("fieldAliases"))));
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
            List<ComponentFieldAlias> fieldAliases) {

        boolean matches(String normalizedPrompt) {
            return containsAny(normalizedPrompt, triggerTerms);
        }
    }

    record ComponentFieldAlias(String field, List<String> aliases) {

        boolean matches(String normalizedPrompt) {
            return containsAny(normalizedPrompt, aliases);
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
}
