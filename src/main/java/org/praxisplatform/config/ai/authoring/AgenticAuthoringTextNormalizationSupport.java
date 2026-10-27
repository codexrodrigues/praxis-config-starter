package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Shared pure text, path, and JSON string normalization helpers for the agentic authoring package.
 */
final class AgenticAuthoringTextNormalizationSupport {

    private AgenticAuthoringTextNormalizationSupport() {
    }

    static boolean containsAny(String value, String... tokens) {
        if (value == null || value.isBlank() || tokens == null) {
            return false;
        }
        String normalizedValue = normalize(value);
        String wordPaddedValue = null;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = normalize(token);
            if (requiresWholeWordMatch(normalizedToken)) {
                if (wordPaddedValue == null) {
                    wordPaddedValue = " " + normalizedValue.replaceAll("[^a-z0-9]+", " ") + " ";
                }
                if (wordPaddedValue.contains(" " + normalizedToken + " ")) {
                    return true;
                }
                continue;
            }
            if (normalizedValue.contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    static boolean requiresWholeWordMatch(String token) {
        return token != null
                && token.length() <= 4
                && token.chars().allMatch(Character::isLetterOrDigit);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    static String normalizePath(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    static String valueOrUnknown(String value) {
        return valueOrDefault(value, "unknown");
    }

    static String jsonText(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    static String humanJoin(List<String> values) {
        List<String> clean = values == null
                ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (clean.isEmpty()) {
            return "";
        }
        if (clean.size() == 1) {
            return clean.get(0);
        }
        if (clean.size() == 2) {
            return clean.get(0) + " e " + clean.get(1);
        }
        return String.join(", ", clean.subList(0, clean.size() - 1)) + " e " + clean.get(clean.size() - 1);
    }

    static boolean isReadProjectionOperation(String submitUrl, String operation) {
        String normalized = submitUrl == null ? "" : submitUrl.toLowerCase(Locale.ROOT);
        return "post".equalsIgnoreCase(operation)
                && (normalized.endsWith("/stats/group-by")
                || normalized.endsWith("/stats/timeseries")
                || normalized.endsWith("/stats/distribution")
                || normalized.endsWith("/filter")
                || normalized.endsWith("/filter/cursor"));
    }
}
