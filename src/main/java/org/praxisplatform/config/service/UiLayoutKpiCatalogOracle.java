package org.praxisplatform.config.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Oráculo fail-closed de catálogo para validação estrutural/semântica de KPIs publicados em
 * páginas e widgets declarativos ({@code ADR-0007} Fase 4).
 */
public interface UiLayoutKpiCatalogOracle {

    Pattern STRICT_CANONICAL_PATH_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9_-]*(?:/[a-z0-9][a-z0-9_-]*)*$");

    boolean isResourcePathAllowed(String canonicalResourcePath);

    Optional<VerifiedRecipeSpec> findVerifiedRecipe(String canonicalResourcePath, String recipeId);

    Optional<ResourceStatsContract> findResourceStatsContract(String canonicalResourcePath);

    default Optional<Set<String>> findAnalyticsProjectionIds(String canonicalResourcePath) {
        return Optional.empty();
    }

    record VerifiedRecipeSpec(String recipeId, Set<String> adjustableParams) {
        public VerifiedRecipeSpec {
            if (recipeId == null || recipeId.isBlank()) {
                throw new IllegalArgumentException("recipeId must not be blank.");
            }
            recipeId = recipeId.trim();
            adjustableParams =
                    adjustableParams == null ? Set.of() : Set.copyOf(adjustableParams);
        }
    }

    record StatsFieldCapabilities(
            String field,
            Set<String> metrics,
            boolean groupByEligible,
            boolean timeSeriesEligible,
            boolean distributionTermsEligible,
            boolean distributionHistogramEligible,
            boolean metricFieldEligible) {
        public StatsFieldCapabilities {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank.");
            }
            field = field.trim();
            metrics = metrics == null ? Set.of() : Set.copyOf(metrics);
        }
    }

    record ResourceStatsContract(
            Set<String> statsFields,
            Set<String> filterDtoFields,
            Map<String, StatsFieldCapabilities> fieldCapabilities) {
        public ResourceStatsContract {
            statsFields = statsFields == null ? Set.of() : Set.copyOf(statsFields);
            filterDtoFields = filterDtoFields == null ? Set.of() : Set.copyOf(filterDtoFields);
            fieldCapabilities = fieldCapabilities == null ? Map.of() : Map.copyOf(fieldCapabilities);
        }

        public ResourceStatsContract(Set<String> statsFields, Set<String> filterDtoFields) {
            this(statsFields, filterDtoFields, Map.of());
        }
    }

    static UiLayoutKpiCatalogOracle denyAll() {
        return DenyAllUiLayoutKpiCatalogOracle.INSTANCE;
    }

    static Builder builder() {
        return new Builder();
    }

    /**
     * Normaliza e valida estritamente {@code resourcePath} relativo para prevenir SSRF, schemas
     * externos, traversal ({@code ..}), encoding suspeito, query/fragment e caracteres de controle.
     */
    static Optional<String> normalizeCanonicalResourcePath(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            return Optional.empty();
        }
        if (containsUnsafeCharactersOrEncoding(trimmed)) {
            return Optional.empty();
        }
        String decodedOnce = bestEffortDecode(trimmed);
        if (containsUnsafeCharactersOrEncoding(decodedOnce)) {
            return Optional.empty();
        }
        String candidate = trimmed;
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.length() < 2 || candidate.length() > 255) {
            return Optional.empty();
        }
        if (!STRICT_CANONICAL_PATH_PATTERN.matcher(candidate).matches()) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static boolean containsUnsafeCharactersOrEncoding(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.contains("://")
                || lower.startsWith("http:")
                || lower.startsWith("https:")
                || lower.startsWith("file:")
                || lower.startsWith("javascript:")
                || lower.startsWith("data:")
                || value.startsWith("//")
                || value.contains("//")
                || value.contains("\\")
                || value.contains("?")
                || value.contains("#")
                || value.contains("..")
                || lower.contains("%2e")
                || lower.contains("%2f")
                || lower.contains("%5c")) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                return true;
            }
        }
        return false;
    }

    private static String bestEffortDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    enum DenyAllUiLayoutKpiCatalogOracle implements UiLayoutKpiCatalogOracle {
        INSTANCE;

        @Override
        public boolean isResourcePathAllowed(String canonicalResourcePath) {
            return false;
        }

        @Override
        public Optional<VerifiedRecipeSpec> findVerifiedRecipe(
                String canonicalResourcePath, String recipeId) {
            return Optional.empty();
        }

        @Override
        public Optional<ResourceStatsContract> findResourceStatsContract(
                String canonicalResourcePath) {
            return Optional.empty();
        }

        @Override
        public Optional<Set<String>> findAnalyticsProjectionIds(String canonicalResourcePath) {
            return Optional.of(Set.of());
        }
    }

    final class Builder {
        private final Set<String> allowedPaths = new LinkedHashSet<>();
        private final Map<String, Map<String, VerifiedRecipeSpec>> recipesByPath =
                new LinkedHashMap<>();
        private final Map<String, ResourceStatsContract> statsByPath = new LinkedHashMap<>();
        private final Map<String, Set<String>> projectionsByPath = new LinkedHashMap<>();

        private Builder() {}

        public Builder allowResource(String resourcePath) {
            String canonical = requireCanonical(resourcePath);
            allowedPaths.add(canonical);
            return this;
        }

        public Builder registerVerifiedRecipe(
                String resourcePath, String recipeId, Set<String> adjustableParams) {
            String canonical = requireCanonical(resourcePath);
            allowedPaths.add(canonical);
            VerifiedRecipeSpec spec = new VerifiedRecipeSpec(recipeId, adjustableParams);
            recipesByPath
                    .computeIfAbsent(canonical, ignored -> new LinkedHashMap<>())
                    .put(spec.recipeId(), spec);
            return this;
        }

        public Builder registerStatsContract(
                String resourcePath, Set<String> statsFields, Set<String> filterDtoFields) {
            String canonical = requireCanonical(resourcePath);
            allowedPaths.add(canonical);
            statsByPath.put(canonical, new ResourceStatsContract(statsFields, filterDtoFields));
            return this;
        }

        public Builder registerStatsContract(
                String resourcePath,
                Map<String, StatsFieldCapabilities> fieldCapabilities,
                Set<String> filterDtoFields) {
            String canonical = requireCanonical(resourcePath);
            allowedPaths.add(canonical);
            Set<String> fields =
                    fieldCapabilities == null ? Set.of() : fieldCapabilities.keySet();
            statsByPath.put(
                    canonical,
                    new ResourceStatsContract(fields, filterDtoFields, fieldCapabilities));
            return this;
        }

        public Builder registerAnalyticsProjections(
                String resourcePath, Set<String> projectionIds) {
            String canonical = requireCanonical(resourcePath);
            allowedPaths.add(canonical);
            projectionsByPath.put(canonical, projectionIds == null ? Set.of() : Set.copyOf(projectionIds));
            return this;
        }

        public UiLayoutKpiCatalogOracle build() {
            return new StaticUiLayoutKpiCatalogOracle(
                    allowedPaths, recipesByPath, statsByPath, projectionsByPath);
        }

        private static String requireCanonical(String resourcePath) {
            return normalizeCanonicalResourcePath(resourcePath)
                    .orElseThrow(
                            () ->
                                    new IllegalArgumentException(
                                            "Invalid canonical KPI resourcePath: " + resourcePath));
        }
    }

    final class StaticUiLayoutKpiCatalogOracle implements UiLayoutKpiCatalogOracle {
        private final Set<String> allowedPaths;
        private final Map<String, Map<String, VerifiedRecipeSpec>> recipesByPath;
        private final Map<String, ResourceStatsContract> statsByPath;
        private final Map<String, Set<String>> projectionsByPath;

        private StaticUiLayoutKpiCatalogOracle(
                Set<String> allowedPaths,
                Map<String, Map<String, VerifiedRecipeSpec>> recipesByPath,
                Map<String, ResourceStatsContract> statsByPath,
                Map<String, Set<String>> projectionsByPath) {
            this.allowedPaths = Set.copyOf(allowedPaths);
            Map<String, Map<String, VerifiedRecipeSpec>> copiedRecipes = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, VerifiedRecipeSpec>> entry : recipesByPath.entrySet()) {
                copiedRecipes.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            this.recipesByPath = Map.copyOf(copiedRecipes);
            this.statsByPath = Map.copyOf(statsByPath);
            this.projectionsByPath = Map.copyOf(projectionsByPath);
        }

        @Override
        public boolean isResourcePathAllowed(String canonicalResourcePath) {
            return normalizeCanonicalResourcePath(canonicalResourcePath)
                    .map(allowedPaths::contains)
                    .orElse(false);
        }

        @Override
        public Optional<VerifiedRecipeSpec> findVerifiedRecipe(
                String canonicalResourcePath, String recipeId) {
            if (recipeId == null || recipeId.isBlank()) {
                return Optional.empty();
            }
            return normalizeCanonicalResourcePath(canonicalResourcePath)
                    .map(recipesByPath::get)
                    .map(byId -> byId.get(recipeId.trim()));
        }

        @Override
        public Optional<ResourceStatsContract> findResourceStatsContract(
                String canonicalResourcePath) {
            return normalizeCanonicalResourcePath(canonicalResourcePath)
                    .map(statsByPath::get);
        }

        @Override
        public Optional<Set<String>> findAnalyticsProjectionIds(String canonicalResourcePath) {
            return normalizeCanonicalResourcePath(canonicalResourcePath)
                    .map(projectionsByPath::get);
        }
    }
}
