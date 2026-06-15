package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.service.DomainCatalogIngestionService;

public class AgenticAuthoringDomainCatalogCandidateEnhancer {

    static final String DOMAIN_CATALOG_GROUNDING = "domain-catalog-grounding";
    private static final int CONTEXT_LIMIT = 8;
    private static final int MAX_CANDIDATES_TO_GROUND = 6;
    private static final int MAX_CONTEXT_QUERIES = 3;

    private final Supplier<DomainCatalogIngestionService> domainCatalogIngestionService;
    private final String serviceKey;

    public AgenticAuthoringDomainCatalogCandidateEnhancer(
            DomainCatalogIngestionService domainCatalogIngestionService,
            String serviceKey) {
        this(() -> domainCatalogIngestionService, serviceKey);
    }

    public AgenticAuthoringDomainCatalogCandidateEnhancer(
            Supplier<DomainCatalogIngestionService> domainCatalogIngestionService,
            String serviceKey) {
        this.domainCatalogIngestionService = domainCatalogIngestionService;
        this.serviceKey = valueOrEmpty(serviceKey).isBlank()
                ? AgenticAuthoringDomainCatalogHints.DEFAULT_SERVICE_KEY
                : serviceKey.trim();
    }

    public List<AgenticAuthoringCandidate> enhance(
            String prompt,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        if (domainCatalogIngestionService == null || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        DomainCatalogIngestionService service = domainCatalogIngestionService.get();
        if (service == null) {
            return candidates;
        }
        String query = normalizePrompt(prompt);
        if (query.isBlank()) {
            return candidates;
        }
        List<AgenticAuthoringCandidate> enhanced = new ArrayList<>(candidates.size());
        int groundingAttempts = 0;
        for (AgenticAuthoringCandidate candidate : candidates) {
            if (shouldAttemptGrounding(candidate) && groundingAttempts < MAX_CANDIDATES_TO_GROUND) {
                enhanced.add(enhanceCandidate(service, query, candidate, tenantId, environment));
                groundingAttempts++;
            } else {
                enhanced.add(candidate);
            }
        }
        return rankGroundedCandidates(query, enhanced);
    }

    public boolean hasResourceKey(String resourceKey, String tenantId, String environment) {
        DomainCatalogIngestionService service = domainCatalogIngestionService == null
                ? null
                : domainCatalogIngestionService.get();
        String normalizedResourceKey = valueOrEmpty(resourceKey);
        if (service == null || normalizedResourceKey.isBlank()) {
            return false;
        }
        for (String query : resourceKeyVerificationQueries(normalizedResourceKey)) {
            try {
                DomainCatalogContextResponse response = service.contextLatest(
                        serviceKey,
                        normalizedResourceKey,
                        tenantId,
                        environment,
                        null,
                        null,
                        null,
                        query,
                        1);
                if (response != null && response.items() != null && !response.items().isEmpty()) {
                    return true;
                }
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return false;
    }

    private AgenticAuthoringCandidate enhanceCandidate(
            DomainCatalogIngestionService service,
            String query,
            AgenticAuthoringCandidate candidate,
            String tenantId,
            String environment) {
        if (candidate == null || hasEvidence(candidate, DOMAIN_CATALOG_GROUNDING)) {
            return candidate;
        }
        String resourceKey = resourceKey(candidate.resourcePath());
        if (resourceKey.isBlank()) {
            return candidate;
        }
        DomainCatalogContextResponse context = context(service, resourceKey, query, tenantId, environment);
        List<DomainCatalogItemResponse> items = context == null || context.items() == null
                ? List.of()
                : context.items();
        if (items.isEmpty()) {
            return candidate;
        }
        List<String> evidence = promotedEvidence(candidate.evidence());
        AgenticAuthoringEvidenceBundle evidenceBundle = promotedEvidenceBundle(
                candidate,
                resourceKey,
                query,
                items,
                tenantId,
                environment,
                context == null || context.release() == null ? "" : context.release().releaseKey());
        double score = Math.max(candidate.score(), score(items, query));
        return new AgenticAuthoringCandidate(
                candidate.resourcePath(),
                candidate.operation(),
                candidate.schemaUrl(),
                candidate.submitUrl(),
                candidate.submitMethod(),
                Math.min(0.96d, score),
                "domain_catalog grounded resource selection",
                evidence,
                evidenceBundle);
    }

    private DomainCatalogContextResponse context(
            DomainCatalogIngestionService service,
            String resourceKey,
            String query,
            String tenantId,
            String environment) {
        return context(
                service,
                serviceKey,
                resourceKey,
                query,
                tenantId,
                environment);
    }

    private DomainCatalogContextResponse context(
            DomainCatalogIngestionService service,
            String candidateServiceKey,
            String resourceKey,
            String query,
            String tenantId,
            String environment) {
        for (String candidateQuery : contextQueries(resourceKey, query)) {
            try {
                DomainCatalogContextResponse response = service.contextLatest(
                        candidateServiceKey,
                        resourceKey,
                        tenantId,
                        environment,
                        null,
                        null,
                        null,
                        candidateQuery,
                        CONTEXT_LIMIT);
                if (response != null && response.items() != null && !response.items().isEmpty()) {
                    return response;
                }
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private List<String> contextQueries(String resourceKey, String query) {
        Set<String> queries = new LinkedHashSet<>();
        if (!valueOrEmpty(query).isBlank()) {
            queries.add(query);
        }
        String meaningful = meaningfulQuery(query);
        if (!meaningful.isBlank()) {
            queries.add(meaningful);
        }
        queries.addAll(meaningfulQueryTerms(query));
        return queries.stream().limit(MAX_CONTEXT_QUERIES).toList();
    }

    private boolean shouldAttemptGrounding(AgenticAuthoringCandidate candidate) {
        return candidate != null
                && !hasEvidence(candidate, DOMAIN_CATALOG_GROUNDING)
                && !resourceKey(candidate.resourcePath()).isBlank();
    }

    private List<String> meaningfulQueryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalizePrompt(query).split("\\s+")) {
            if (isMeaningfulToken(token)) {
                terms.add(token);
            }
        }
        return List.copyOf(terms);
    }

    private List<String> resourceKeyVerificationQueries(String resourceKey) {
        Set<String> queries = new LinkedHashSet<>();
        String resourceTerms = normalizePrompt(resourceKey.replace('.', ' '));
        if (!resourceTerms.isBlank()) {
            queries.add(resourceTerms);
        }
        String[] tokens = resourceTerms.split("\\s+");
        if (tokens.length > 0 && !tokens[tokens.length - 1].isBlank()) {
            queries.add(tokens[tokens.length - 1]);
        }
        return List.copyOf(queries);
    }

    private String meaningfulQuery(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalizePrompt(query).split("\\s+")) {
            if (isMeaningfulToken(token)) {
                tokens.add(token);
            }
        }
        return String.join(" ", tokens);
    }

    private boolean isMeaningfulToken(String token) {
        String normalized = valueOrEmpty(token);
        if (normalized.length() <= 2) {
            return false;
        }
        return !Set.of(
                "crie", "criar", "gera", "gerar", "monte", "montar", "adicione", "adicionar",
                "uma", "um", "de", "do", "da", "dos", "das", "para", "com", "por", "em",
                "antes", "qualquer", "coisa", "explique", "quais", "qual", "dados", "existem",
                "sobre", "voce", "recomenda", "recomendar", "recomende",
                "tabela", "lista", "formulario", "form", "dashboard", "grafico", "chart",
                "create", "make", "build", "add", "table", "list", "view", "page")
                .contains(normalized);
    }

    private AgenticAuthoringEvidenceBundle promotedEvidenceBundle(
            AgenticAuthoringCandidate candidate,
            String resourceKey,
            String query,
            List<DomainCatalogItemResponse> items,
            String tenantId,
            String environment,
            String releaseKey) {
        List<AgenticAuthoringEvidenceBundle.Evidence> evidence = new ArrayList<>();
        evidence.add(new AgenticAuthoringEvidenceBundle.Evidence(
                "domain_catalog",
                "domain_catalog_grounding",
                resourceKey,
                summary(items),
                score(items, query),
                matchedTerms(resourceKey, items),
                tenantId,
                environment,
                releaseKey));
        AgenticAuthoringEvidenceBundle existing = candidate.evidenceBundle();
        if (existing != null && existing.evidence() != null) {
            for (AgenticAuthoringEvidenceBundle.Evidence item : existing.evidence()) {
                if (!"weak_lexical_match".equals(valueOrEmpty(item.kind()))) {
                    evidence.add(item);
                }
            }
        }
        return AgenticAuthoringEvidenceBundle.of("domain_catalog", evidence);
    }

    private List<String> promotedEvidence(List<String> evidence) {
        Set<String> promoted = new LinkedHashSet<>();
        promoted.add("api-metadata");
        promoted.add(DOMAIN_CATALOG_GROUNDING);
        promoted.add("semantic-retrieval");
        if (evidence != null) {
            evidence.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .filter(value -> !"lexical-fallback".equals(value))
                    .filter(value -> !"weak-evidence".equals(value))
                    .forEach(promoted::add);
        }
        return List.copyOf(promoted);
    }

    private List<AgenticAuthoringCandidate> rankGroundedCandidates(
            String query,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        boolean hasGroundedCandidate = candidates.stream()
                .anyMatch(candidate -> hasEvidence(candidate, DOMAIN_CATALOG_GROUNDING));
        if (!hasGroundedCandidate) {
            return candidates;
        }
        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble((AgenticAuthoringCandidate candidate) ->
                                governedAlignmentScore(query, candidate)).reversed()
                        .thenComparing(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed()))
                .toList();
    }

    private double governedAlignmentScore(String query, AgenticAuthoringCandidate candidate) {
        if (candidate == null || !hasEvidence(candidate, DOMAIN_CATALOG_GROUNDING)) {
            return 0d;
        }
        List<String> queryTerms = meaningfulQueryTerms(query);
        if (queryTerms.isEmpty()) {
            return candidate.score();
        }
        Set<String> evidenceTerms = new LinkedHashSet<>();
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        if (bundle != null && bundle.evidence() != null) {
            for (AgenticAuthoringEvidenceBundle.Evidence evidence : bundle.evidence()) {
                if (evidence == null) {
                    continue;
                }
                addNormalizedTerms(evidenceTerms, evidence.ref());
                addNormalizedTerms(evidenceTerms, evidence.summary());
                if (evidence.matchedTerms() != null) {
                    evidence.matchedTerms().forEach(term -> addNormalizedTerms(evidenceTerms, term));
                }
            }
        }
        double matches = queryTerms.stream()
                .filter(term -> termMatches(evidenceTerms, term))
                .count();
        double coverage = matches / Math.max(1d, queryTerms.size());
        return candidate.score() + coverage;
    }

    private double score(List<DomainCatalogItemResponse> items, String query) {
        boolean governance = items.stream()
                .map(DomainCatalogItemResponse::itemType)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch("governance"::equals);
        boolean node = items.stream()
                .map(DomainCatalogItemResponse::itemType)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch("node"::equals);
        double score = 0.82d + Math.min(items.size(), CONTEXT_LIMIT) * 0.01d;
        score += catalogItemQueryCoverage(items, query) * 0.08d;
        if (governance) {
            score += 0.04d;
        }
        if (node) {
            score += 0.03d;
        }
        return Math.min(0.94d, score);
    }

    private double catalogItemQueryCoverage(List<DomainCatalogItemResponse> items, String query) {
        List<String> queryTerms = meaningfulQueryTerms(query);
        if (items == null || items.isEmpty() || queryTerms.isEmpty()) {
            return 0d;
        }
        Set<String> itemTerms = new LinkedHashSet<>();
        for (DomainCatalogItemResponse item : items) {
            if (item == null) {
                continue;
            }
            addNormalizedTerms(itemTerms, item.itemKey());
            addNormalizedTerms(itemTerms, item.contextKey());
            addNormalizedTerms(itemTerms, item.nodeType());
            addNormalizedTerms(itemTerms, item.bindingType());
            addNormalizedTerms(itemTerms, item.edgeType());
            JsonNode payload = item.payload();
            addNormalizedTerms(itemTerms, text(payload, "label", "name", "title", "summary", "description", "aliases", "synonyms"));
            addNormalizedTerms(itemTerms, text(path(payload, "target"), "fieldName", "resourceKey", "resourcePath"));
            addNormalizedTerms(itemTerms, text(path(payload, "metadata"), "fieldName", "resourceKey", "resourcePath"));
        }
        long matches = queryTerms.stream()
                .filter(term -> termMatches(itemTerms, term))
                .count();
        return matches / Math.max(1d, queryTerms.size());
    }

    private void addNormalizedTerms(Set<String> terms, String value) {
        if (terms == null || value == null || value.isBlank()) {
            return;
        }
        for (String token : normalizePrompt(value.replace('.', ' ')).split("\\s+")) {
            if (token.length() > 2) {
                terms.add(token);
            }
        }
    }

    private boolean termMatches(Set<String> terms, String term) {
        String normalized = valueOrEmpty(term);
        if (normalized.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        if (terms.contains(normalized)) {
            return true;
        }
        if (normalized.endsWith("s") && normalized.length() > 4
                && terms.contains(normalized.substring(0, normalized.length() - 1))) {
            return true;
        }
        return normalized.length() > 4 && terms.contains(normalized + "s");
    }

    private String summary(List<DomainCatalogItemResponse> items) {
        return items.stream()
                .limit(3)
                .map(this::summary)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "; " + right)
                .orElse("Governed domain catalog context matched the requested resource.");
    }

    private String summary(DomainCatalogItemResponse item) {
        if (item == null) {
            return "";
        }
        JsonNode payload = item.payload();
        String label = text(payload, "label", "name", "title", "summary", "description");
        if (label.isBlank()) {
            label = valueOrEmpty(item.itemKey());
        }
        String fieldName = firstNonBlank(
                text(path(payload, "target"), "fieldName"),
                text(path(payload, "metadata"), "fieldName"));
        String fieldSummary = fieldName.isBlank() ? "" : " field=" + fieldName;
        return compact(valueOrEmpty(item.itemType()) + " " + valueOrEmpty(item.itemKey()) + " " + label + fieldSummary);
    }

    private List<String> matchedTerms(String resourceKey, List<DomainCatalogItemResponse> items) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalizePrompt(resourceKey.replace('.', ' ')).split("\\s+")) {
            if (!token.isBlank()) {
                terms.add(token);
            }
        }
        items.stream()
                .limit(5)
                .flatMap(item -> Stream.concat(
                        Stream.of(valueOrEmpty(item.itemKey())),
                        Stream.of(
                                text(path(item.payload(), "target"), "fieldName"),
                                text(path(item.payload(), "metadata"), "fieldName"),
                                text(item.payload(), "label"),
                                text(item.payload(), "name"),
                                text(item.payload(), "title"),
                                text(item.payload(), "summary"),
                                text(item.payload(), "description"),
                                text(item.payload(), "aliases"),
                                text(item.payload(), "synonyms"))))
                .flatMap(itemText -> List.of(normalizePrompt(itemText.replace('.', ' ')).split("\\s+")).stream())
                .filter(token -> token.length() > 2)
                .forEach(terms::add);
        return List.copyOf(terms);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String safe = valueOrEmpty(value);
            if (!safe.isBlank()) {
                return safe;
            }
        }
        return "";
    }

    private JsonNode path(JsonNode node, String field) {
        return node == null || field == null ? null : node.path(field);
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
    }

    private String resourceKey(String resourcePath) {
        String normalized = valueOrEmpty(resourcePath);
        if (normalized.startsWith("/api/")) {
            normalized = normalized.substring(5);
        } else if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.replace('/', '.');
    }

    private String text(JsonNode payload, String... fields) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return "";
        }
        for (String field : fields) {
            String value = valueOrEmpty(payload.path(field).asText(""));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String compact(String value) {
        String normalized = valueOrEmpty(value).replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220).trim();
    }

    private String normalizePrompt(String value) {
        String normalized = Normalizer.normalize(valueOrEmpty(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9./_-]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
