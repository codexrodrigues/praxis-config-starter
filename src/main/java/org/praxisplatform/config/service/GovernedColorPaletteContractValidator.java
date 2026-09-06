package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.ColorTokenEntry;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.ContrastEvidence;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.Validation;
import org.praxisplatform.config.dto.GovernedColorPaletteResponse.Variant;
import org.springframework.stereotype.Component;

/** Validates and normalizes the materialized contract of a governed color palette. */
@Component
public class GovernedColorPaletteContractValidator {

    private static final Pattern CSS_TOKEN = Pattern.compile("var\\(--[A-Za-z0-9_-]+\\)");
    private static final Pattern FUNCTION_COLOR = Pattern.compile("^(rgb|rgba|hsl|hsla)\\(([^()]+)\\)$");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:e[+-]?[0-9]+)?");
    private static final Set<String> PURPOSES = Set.of(
            "text", "fill", "chart", "state", "surface", "border", "focus");
    private static final double RATIO_TOLERANCE = 0.02d;

    private final ObjectMapper objectMapper;

    public GovernedColorPaletteContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result validate(String targetArtifactKey, JsonNode payload) {
        JsonNode source = payload != null && payload.isObject()
                ? payload
                : objectMapper.createObjectNode();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String paletteKey = normalize(targetArtifactKey);
        if (paletteKey == null) {
            errors.add("targetArtifactKey is required");
            paletteKey = text(source, "paletteKey");
        }
        String payloadKey = text(source, "paletteKey");
        if (payloadKey == null) {
            errors.add("paletteKey is required");
        } else if (paletteKey != null && !paletteKey.equals(payloadKey)) {
            errors.add("paletteKey must match targetArtifactKey");
        }
        String displayName = text(source, "displayName");
        if (displayName == null) {
            errors.add("displayName is required");
            displayName = paletteKey;
        }
        String familyKey = text(source, "familyKey");
        if (familyKey == null) {
            errors.add("familyKey is required");
        }
        Variant variant = readVariant(source.path("variant"), errors);

        List<ColorTokenEntry> entries = readEntries(source.path("entries"), errors, warnings);
        validateFallbackGraph(entries, errors);
        List<ContrastEvidence> evidence = readContrastEvidence(
                source.path("contrastEvidence"), entries, errors, warnings);
        if (evidence.isEmpty()) {
            if (entries.stream().anyMatch(entry -> entry.purposes().contains("text"))) {
                errors.add("contrastEvidence is required when the palette contains text tokens");
            } else {
                warnings.add("contrastEvidence is empty");
            }
        }
        JsonNode provenance = source.path("provenance").isObject()
                ? source.path("provenance")
                : objectMapper.createObjectNode();
        JsonNode scope = source.path("scope").isObject()
                ? source.path("scope")
                : objectMapper.createObjectNode();
        return new Result(
                paletteKey,
                displayName,
                familyKey,
                variant,
                scope,
                entries,
                evidence,
                provenance,
                new Validation(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings)));
    }

    public Set<String> supportedPurposes() {
        return PURPOSES;
    }

    private List<ColorTokenEntry> readEntries(
            JsonNode node, List<String> errors, List<String> warnings) {
        if (!node.isArray() || node.isEmpty()) {
            errors.add("entries must contain at least one token");
            return List.of();
        }
        List<ColorTokenEntry> entries = new ArrayList<>();
        Set<String> tokenIds = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.path(index);
            String prefix = "entries[" + index + "]";
            String tokenId = text(item, "tokenId");
            String entryDisplayName = text(item, "displayName");
            List<String> aliases = strings(item.path("aliases"));
            String semanticRole = text(item, "semanticRole");
            String cssReference = text(item, "cssReference");
            String fallback = text(item, "fallback");
            String fallbackTokenId = text(item, "fallbackTokenId");
            List<String> purposes = strings(item.path("purposes"));
            if (tokenId == null) {
                errors.add(prefix + ".tokenId is required");
            } else if (!tokenIds.add(tokenId)) {
                errors.add(prefix + ".tokenId is duplicated");
            }
            if (entryDisplayName == null) {
                errors.add(prefix + ".displayName is required");
            }
            if (!item.has("aliases") || !item.path("aliases").isArray()) {
                errors.add(prefix + ".aliases must be an array");
            } else {
                item.path("aliases").forEach(alias -> {
                    if (!alias.isTextual() || alias.asText().isBlank()) {
                        errors.add(prefix + ".aliases must contain non-blank strings");
                    }
                });
            }
            Set<String> normalizedAliases = new HashSet<>();
            for (String alias : aliases) {
                String normalizedAlias = alias.toLowerCase(Locale.ROOT);
                if (tokenId != null && normalizedAlias.equals(tokenId.toLowerCase(Locale.ROOT))) {
                    errors.add(prefix + ".aliases must not contain tokenId");
                } else if (!normalizedAliases.add(normalizedAlias)) {
                    errors.add(prefix + ".aliases contains duplicate value " + alias);
                }
            }
            if (semanticRole == null) {
                errors.add(prefix + ".semanticRole is required");
            }
            if (cssReference == null || !CSS_TOKEN.matcher(cssReference).matches()) {
                errors.add(prefix + ".cssReference must be a var(--token) reference");
            }
            if (fallback == null && fallbackTokenId == null) {
                errors.add(prefix + " requires fallback or fallbackTokenId");
            }
            if (fallback != null && fallbackTokenId != null) {
                errors.add(prefix + " must specify only one of fallback or fallbackTokenId");
            }
            if (fallback != null && parseColor(fallback) == null) {
                errors.add(prefix + ".fallback is not a safe CSS color");
            }
            if (purposes.isEmpty()) {
                warnings.add(prefix + ".purposes is empty");
            }
            purposes.stream()
                    .filter(purpose -> !PURPOSES.contains(purpose))
                    .forEach(purpose -> errors.add(prefix + ".purposes contains unsupported value " + purpose));
            entries.add(new ColorTokenEntry(
                    tokenId, entryDisplayName, aliases, semanticRole, cssReference, fallback, fallbackTokenId, purposes));
        }
        return List.copyOf(entries);
    }

    private List<ContrastEvidence> readContrastEvidence(
            JsonNode node,
            List<ColorTokenEntry> entries,
            List<String> errors,
            List<String> warnings) {
        if (!node.isArray()) {
            return List.of();
        }
        Map<String, ColorTokenEntry> entriesById = entries.stream()
                .filter(entry -> entry.tokenId() != null)
                .collect(Collectors.toMap(ColorTokenEntry::tokenId, entry -> entry, (left, right) -> left));
        List<ContrastEvidence> result = new ArrayList<>();
        Set<String> pairs = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.path(index);
            String prefix = "contrastEvidence[" + index + "]";
            String foreground = text(item, "foregroundTokenId");
            String background = text(item, "backgroundTokenId");
            String level = text(item, "requiredLevel");
            ColorTokenEntry foregroundEntry = entriesById.get(foreground);
            ColorTokenEntry backgroundEntry = entriesById.get(background);
            if (foregroundEntry == null) {
                errors.add(prefix + ".foregroundTokenId must reference an entry");
            }
            if (backgroundEntry == null) {
                errors.add(prefix + ".backgroundTokenId must reference an entry");
            }
            if (foreground != null && background != null && !pairs.add(foreground + "\u0000" + background)) {
                errors.add(prefix + " duplicates a foreground/background pair");
            }
            if (level == null || !(level.equals("AA") || level.equals("AAA"))) {
                errors.add(prefix + ".requiredLevel must be AA or AAA");
            }

            double ratio = 0d;
            if (foregroundEntry != null && backgroundEntry != null) {
                Rgba foregroundColor = resolveFallbackColor(foregroundEntry, entriesById, new HashSet<>());
                Rgba backgroundColor = resolveFallbackColor(backgroundEntry, entriesById, new HashSet<>());
                if (foregroundColor == null) {
                    errors.add(prefix + ".foregroundTokenId must resolve to a supported fallback color");
                }
                if (backgroundColor == null || backgroundColor.alpha() < 1d) {
                    errors.add(prefix + ".backgroundTokenId must resolve to an opaque supported fallback color");
                }
                if (foregroundColor != null && backgroundColor != null && backgroundColor.alpha() >= 1d) {
                    ratio = contrastRatio(composite(foregroundColor, backgroundColor), backgroundColor);
                    if (item.has("ratio") && item.path("ratio").isNumber()) {
                        double suppliedRatio = item.path("ratio").asDouble();
                        if (Math.abs(suppliedRatio - ratio) > RATIO_TOLERANCE) {
                            warnings.add(prefix + ".ratio was recalculated from resolved fallback colors");
                        }
                    }
                }
            }
            double threshold = "AAA".equals(level) ? 7d : 4.5d;
            boolean passes = ratio >= threshold;
            if (ratio > 0d && !passes) {
                errors.add(prefix + " does not meet " + (level == null ? "AA" : level));
            }
            result.add(new ContrastEvidence(foreground, background, roundRatio(ratio), level, passes));
        }
        return List.copyOf(result);
    }

    private Variant readVariant(JsonNode node, List<String> errors) {
        String key = text(node, "key");
        String displayName = text(node, "displayName");
        if (!node.isObject()) {
            errors.add("variant is required");
        }
        if (key == null) {
            errors.add("variant.key is required");
        }
        if (displayName == null) {
            errors.add("variant.displayName is required");
        }
        if (node.has("dimensions") && !node.path("dimensions").isObject()) {
            errors.add("variant.dimensions must be an object");
        }
        JsonNode dimensions = node.path("dimensions").isObject()
                ? node.path("dimensions")
                : objectMapper.createObjectNode();
        return new Variant(key, displayName, dimensions);
    }

    private Rgba resolveFallbackColor(
            ColorTokenEntry entry,
            Map<String, ColorTokenEntry> entriesById,
            Set<String> visited) {
        if (entry == null || entry.tokenId() == null || !visited.add(entry.tokenId())) {
            return null;
        }
        if (entry.fallback() != null) {
            return parseColor(entry.fallback());
        }
        return resolveFallbackColor(entriesById.get(entry.fallbackTokenId()), entriesById, visited);
    }

    private Rgba parseColor(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("transparent".equals(normalized)) {
            return new Rgba(0d, 0d, 0d, 0d);
        }
        if (normalized.startsWith("#")) {
            if (!normalized.matches("#[0-9a-f]+")) return null;
            return parseHex(normalized.substring(1));
        }
        Matcher function = FUNCTION_COLOR.matcher(normalized);
        if (!function.matches()) {
            return null;
        }
        try {
            String body = function.group(2).trim();
            boolean commaSyntax = body.contains(",");
            String[] parts;
            String alphaPart = null;
            if (commaSyntax) {
                if (body.contains("/")) return null;
                parts = body.split(",", -1);
                if (parts.length != 3 && parts.length != 4) return null;
                if (parts.length == 4) alphaPart = parts[3];
            } else {
                String[] halves = body.split("/", -1);
                if (halves.length > 2) return null;
                parts = halves[0].trim().split("\\s+");
                if (parts.length != 3) return null;
                if (halves.length == 2) alphaPart = halves[1];
            }
            double alpha = alphaPart == null ? 1d : parseUnitInterval(alphaPart);
            if (function.group(1).startsWith("rgb")) {
                if (commaSyntax && (parts[0].trim().endsWith("%") != parts[1].trim().endsWith("%")
                        || parts[0].trim().endsWith("%") != parts[2].trim().endsWith("%"))) return null;
                return new Rgba(parseRgbChannel(parts[0]), parseRgbChannel(parts[1]), parseRgbChannel(parts[2]), alpha);
            }
            if (!parts[1].trim().endsWith("%") || !parts[2].trim().endsWith("%")) return null;
            double hue = parseHue(parts[0]);
            double saturation = parseUnitInterval(parts[1]);
            double lightness = parseUnitInterval(parts[2]);
            double chroma = (1d - Math.abs(2d * lightness - 1d)) * saturation;
            double x = chroma * (1d - Math.abs((hue / 60d) % 2d - 1d));
            double offset = lightness - chroma / 2d;
            double red = hue < 60d || hue >= 300d ? chroma : hue < 120d || hue >= 240d ? x : 0d;
            double green = hue >= 60d && hue < 180d ? chroma : hue < 60d || hue < 240d && hue >= 180d ? x : 0d;
            double blue = hue >= 180d && hue < 300d ? chroma : hue >= 120d && hue < 180d || hue >= 300d ? x : 0d;
            return new Rgba(red + offset, green + offset, blue + offset, alpha);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Rgba parseHex(String value) {
        try {
            return switch (value.length()) {
                case 3 -> new Rgba(
                        duplicateHex(value.charAt(0)),
                        duplicateHex(value.charAt(1)),
                        duplicateHex(value.charAt(2)),
                        1d);
                case 4 -> new Rgba(
                        duplicateHex(value.charAt(0)),
                        duplicateHex(value.charAt(1)),
                        duplicateHex(value.charAt(2)),
                        duplicateHex(value.charAt(3)));
                case 6 -> new Rgba(
                        Integer.parseInt(value.substring(0, 2), 16) / 255d,
                        Integer.parseInt(value.substring(2, 4), 16) / 255d,
                        Integer.parseInt(value.substring(4, 6), 16) / 255d,
                        1d);
                case 8 -> new Rgba(
                        Integer.parseInt(value.substring(0, 2), 16) / 255d,
                        Integer.parseInt(value.substring(2, 4), 16) / 255d,
                        Integer.parseInt(value.substring(4, 6), 16) / 255d,
                        Integer.parseInt(value.substring(6, 8), 16) / 255d);
                default -> null;
            };
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private double duplicateHex(char value) {
        return Integer.parseInt("" + value + value, 16) / 255d;
    }

    private double parseRgbChannel(String value) {
        String normalized = value.trim();
        if (normalized.endsWith("%")) {
            return parseUnitInterval(normalized);
        }
        return clamp(parseFiniteNumber(normalized) / 255d);
    }

    private double parseUnitInterval(String value) {
        String normalized = value.trim();
        return normalized.endsWith("%")
                ? clamp(parseFiniteNumber(normalized.substring(0, normalized.length() - 1)) / 100d)
                : clamp(parseFiniteNumber(normalized));
    }

    private double parseHue(String value) {
        String normalized = value.trim();
        double multiplier = 1d;
        for (String unit : List.of("grad", "turn", "deg", "rad")) {
            if (normalized.endsWith(unit)) {
                normalized = normalized.substring(0, normalized.length() - unit.length());
                multiplier = switch (unit) {
                    case "grad" -> 0.9d;
                    case "turn" -> 360d;
                    case "rad" -> 180d / Math.PI;
                    default -> 1d;
                };
                break;
            }
        }
        double degrees = parseFiniteNumber(normalized) * multiplier;
        if (!Double.isFinite(degrees)) throw new NumberFormatException("Non-finite hue");
        return ((degrees % 360d) + 360d) % 360d;
    }

    private double parseFiniteNumber(String value) {
        if (!NUMBER.matcher(value).matches()) throw new NumberFormatException("Invalid CSS number");
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw new NumberFormatException("Non-finite CSS number");
        return number;
    }

    private Rgba composite(Rgba foreground, Rgba background) {
        if (foreground.alpha() >= 1d) {
            return foreground;
        }
        double alpha = foreground.alpha();
        return new Rgba(
                foreground.red() * alpha + background.red() * (1d - alpha),
                foreground.green() * alpha + background.green() * (1d - alpha),
                foreground.blue() * alpha + background.blue() * (1d - alpha),
                1d);
    }

    private double contrastRatio(Rgba foreground, Rgba background) {
        double first = relativeLuminance(foreground);
        double second = relativeLuminance(background);
        return (Math.max(first, second) + 0.05d) / (Math.min(first, second) + 0.05d);
    }

    private double relativeLuminance(Rgba color) {
        return 0.2126d * linearize(color.red())
                + 0.7152d * linearize(color.green())
                + 0.0722d * linearize(color.blue());
    }

    private double linearize(double channel) {
        return channel <= 0.04045d
                ? channel / 12.92d
                : Math.pow((channel + 0.055d) / 1.055d, 2.4d);
    }

    private double roundRatio(double ratio) {
        return Math.round(ratio * 100d) / 100d;
    }

    private double clamp(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private void validateFallbackGraph(List<ColorTokenEntry> entries, List<String> errors) {
        Map<String, String> edges = new HashMap<>();
        Set<String> tokenIds = entries.stream()
                .map(ColorTokenEntry::tokenId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (ColorTokenEntry entry : entries) {
            if (entry.tokenId() != null && entry.fallbackTokenId() != null) {
                if (!tokenIds.contains(entry.fallbackTokenId())) {
                    errors.add("fallbackTokenId references unknown token " + entry.fallbackTokenId());
                } else {
                    edges.put(entry.tokenId(), entry.fallbackTokenId());
                }
            }
        }
        for (String start : edges.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = start;
            while (current != null && edges.containsKey(current)) {
                if (!visited.add(current)) {
                    errors.add("fallback token cycle detected at " + current);
                    break;
                }
                current = edges.get(current);
            }
        }
    }

    private String text(JsonNode node, String field) {
        return normalize(node.path(field).asText(null));
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText().trim());
            }
        });
        return List.copyOf(values);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Rgba(double red, double green, double blue, double alpha) {}

    public record Result(
            String paletteKey,
            String displayName,
            String familyKey,
            Variant variant,
            JsonNode scope,
            List<ColorTokenEntry> entries,
            List<ContrastEvidence> contrastEvidence,
            JsonNode provenance,
            Validation validation) {}
}
