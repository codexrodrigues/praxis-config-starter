package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.service.DomainCatalogIngestionService;

public class AgenticAuthoringDomainCatalogCandidateEnhancer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgenticAuthoringDomainCatalogCandidateEnhancer.class);

    static final String DOMAIN_CATALOG_GROUNDING = "domain-catalog-grounding";
    private static final int CONTEXT_LIMIT = 8;
    private static final int MAX_CANDIDATES_TO_GROUND = 16;
    private static final int MAX_CONTEXT_QUERIES = 3;
    private static final int MAX_STRUCTURED_CONTEXT_QUERIES = 6;

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
        Map<String, DomainCatalogContextResponse> contextByResourceKey = new HashMap<>();
        int groundingAttempts = 0;
        for (AgenticAuthoringCandidate candidate : candidates) {
            if (shouldAttemptGrounding(candidate) && groundingAttempts < MAX_CANDIDATES_TO_GROUND) {
                enhanced.add(enhanceCandidate(
                        service,
                        query,
                        candidate,
                        tenantId,
                        environment,
                        contextByResourceKey));
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
            String environment,
            Map<String, DomainCatalogContextResponse> contextByResourceKey) {
        if (candidate == null || hasEvidence(candidate, DOMAIN_CATALOG_GROUNDING)) {
            return candidate;
        }
        String resourceKey = resourceKey(candidate.resourcePath());
        if (resourceKey.isBlank()) {
            return candidate;
        }
        DomainCatalogContextResponse context;
        if (contextByResourceKey.containsKey(resourceKey)) {
            context = contextByResourceKey.get(resourceKey);
        } else {
            context = context(service, resourceKey, query, tenantId, environment);
            contextByResourceKey.put(resourceKey, context);
        }
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
            } catch (IllegalArgumentException missingRelease) {
                // No published catalog release for this resource scope: an expected governance gap,
                // so the candidate simply stays ungrounded.
                log.debug("[DomainCatalogGrounding] No catalog release for scope; serviceKey={}, resourceKey={}, "
                                + "tenantId={}, environment={}",
                        candidateServiceKey, resourceKey, tenantId, environment);
                return null;
            } catch (RuntimeException ex) {
                // Grounding failures degrade to an ungrounded candidate; keep the cause observable so an
                // infrastructure failure is not mistaken for a missing canonical binding.
                log.warn("[DomainCatalogGrounding] Context lookup failed; serviceKey={}, resourceKey={}, "
                                + "tenantId={}, environment={}, cause={}",
                        candidateServiceKey, resourceKey, tenantId, environment, ex.toString());
                return null;
            }
        }
        return null;
    }

    private List<String> contextQueries(String resourceKey, String query) {
        Set<String> queries = new LinkedHashSet<>();
        boolean structuredFocus = isStructuredRetrievalFocusQuery(query);
        if (!structuredFocus) {
            if (!valueOrEmpty(query).isBlank()) {
                queries.add(query);
            }
            String meaningful = meaningfulQuery(query);
            if (!meaningful.isBlank()) {
                queries.add(meaningful);
            }
        }
        queries.addAll(meaningfulQueryTerms(query));
        int maxQueries = structuredFocus ? MAX_STRUCTURED_CONTEXT_QUERIES : MAX_CONTEXT_QUERIES;
        return queries.stream().limit(maxQueries).toList();
    }

    private boolean isStructuredRetrievalFocusQuery(String query) {
        return normalizePrompt(query).startsWith("primary business entity");
    }

    private boolean shouldAttemptGrounding(AgenticAuthoringCandidate candidate) {
        return candidate != null
                && !hasEvidence(candidate, DOMAIN_CATALOG_GROUNDING)
                && !resourceKey(candidate.resourcePath()).isBlank();
    }

    private List<String> meaningfulQueryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String rawToken : extractDomainTermsText(query).split("\\s+")) {
            String token = trimTokenPunctuation(rawToken);
            if (isMeaningfulToken(token)) {
                terms.add(token);
            }
        }
        return List.copyOf(terms);
    }

    private String extractDomainTermsText(String query) {
        String normalized = normalizePrompt(query);
        if (!isStructuredRetrievalFocusQuery(query)) {
            return normalized;
        }
        return normalized
                .replaceAll("\\bprimary business entity:?\\s*", " ")
                .replaceAll("\\bsupporting concepts:?\\s*", " ")
                .replaceAll("\\bdesired surface:?\\s*.*?(?=\\bsemantic query\\b|$)", " ")
                .replaceAll("\\bsemantic query:?\\s*", " ")
                .replaceAll("\\b(none|unspecified)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
        for (String rawToken : extractDomainTermsText(query).split("\\s+")) {
            String token = trimTokenPunctuation(rawToken);
            if (isMeaningfulToken(token)) {
                tokens.add(token);
            }
        }
        return String.join(" ", tokens);
    }

    private String trimTokenPunctuation(String token) {
        return valueOrEmpty(token).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
    }

    private boolean isMeaningfulToken(String token) {
        String normalized = trimTokenPunctuation(token);
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
        String resolvedResourceKey = canonicalResourceKey(items, resourceKey);
        List<AgenticAuthoringEvidenceBundle.Evidence> evidence = new ArrayList<>();
        evidence.add(new AgenticAuthoringEvidenceBundle.Evidence(
                "domain_catalog",
                "domain_catalog_grounding",
                resolvedResourceKey,
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
        boolean analyticalDataProduct = items.stream()
                .map(DomainCatalogItemResponse::payload)
                .filter(Objects::nonNull)
                .anyMatch(payload -> "analytical_data_product".equalsIgnoreCase(
                        text(path(payload, "metadata"), "semanticRole"))
                        || payload.path("metadata").path("dataProduct").asBoolean(false));
        boolean ruleGoverned = items.stream()
                .map(DomainCatalogItemResponse::payload)
                .filter(Objects::nonNull)
                .anyMatch(payload -> "domain_rule_set".equalsIgnoreCase(
                        text(path(payload, "metadata"), "policyKind"))
                        || payload.path("metadata").hasNonNull("governedRuleSets")
                        || payload.path("metadata").hasNonNull("ontologicalFactRoots"));
        double score = 0.82d + Math.min(items.size(), CONTEXT_LIMIT) * 0.01d;
        double coverage = catalogItemQueryCoverage(items, query);
        score += coverage * 0.08d;
        if (governance) {
            score += 0.04d;
        }
        if (node) {
            score += 0.03d;
        }
        if ((analyticalDataProduct || ruleGoverned) && coverage > 0d) {
            score += 0.02d;
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
            extractCatalogItemSemanticTexts(item).forEach(value -> addNormalizedTerms(itemTerms, value));
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
        JsonNode metadata = path(payload, "metadata");
        JsonNode target = path(payload, "target");
        String fieldName = firstNonBlank(
                text(target, "fieldName"),
                text(metadata, "fieldName"));
        String fieldSummary = fieldName.isBlank() ? "" : " field=" + fieldName;
        String semanticRole = text(metadata, "semanticRole");
        String roleSummary = semanticRole.isBlank() ? "" : " role=" + semanticRole;
        String analyzes = joinArrayNode(path(metadata, "analyzesConcepts"));
        String analyzesSummary = analyzes.isBlank() ? "" : " analyzes=" + analyzes;
        String dataProducts = joinArrayNode(path(metadata, "analyzedByDataProducts"));
        String dataProductsSummary = dataProducts.isBlank() ? "" : " dataProducts=" + dataProducts;
        String children = joinArrayNode(path(metadata, "childConcepts"));
        String childrenSummary = children.isBlank() ? "" : " children=" + children;
        String parents = joinArrayNode(path(metadata, "parentConcepts"));
        String parentsSummary = parents.isBlank() ? "" : " parents=" + parents;
        String referenced = firstNonBlank(
                text(metadata, "referencedResourceKey"),
                text(path(metadata, "resourceReference"), "resourceKey"),
                text(path(metadata, "optionSource"), "referencedResourceKey"),
                text(target, "referencedResourceKey"));
        String referencedSummary = referenced.isBlank() ? "" : " references=" + referenced;
        String ruleSet = firstNonBlank(
                text(metadata, "ruleSetKey"),
                joinArrayNode(path(metadata, "governedRuleSets")),
                text(target, "ruleSetKey"));
        String ruleSetSummary = ruleSet.isBlank() ? "" : " ruleSets=" + ruleSet;
        String reasons = firstNonBlank(
                joinArrayNode(path(metadata, "reasonCodes")),
                joinArrayNode(path(metadata, "governedReasonCodes")));
        String reasonsSummary = reasons.isBlank() ? "" : " reasons=" + reasons;
        String factRoots = firstNonBlank(
                joinArrayNode(path(metadata, "availableRoots")),
                joinArrayNode(path(metadata, "ontologicalFactRoots")),
                joinArrayNode(path(target, "availableRoots")));
        String factRootsSummary = factRoots.isBlank() ? "" : " factRoots=" + factRoots;
        return compact(valueOrEmpty(item.itemType()) + " " + valueOrEmpty(item.itemKey()) + " " + label
                + fieldSummary + roleSummary + analyzesSummary + dataProductsSummary
                + childrenSummary + parentsSummary + referencedSummary
                + ruleSetSummary + reasonsSummary + factRootsSummary);
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
                .flatMap(item -> extractCatalogItemSemanticTexts(item).stream())
                .flatMap(itemText -> List.of(normalizePrompt(itemText.replace('.', ' ')).split("\\s+")).stream())
                .filter(token -> token.length() > 2)
                .forEach(terms::add);
        return List.copyOf(terms);
    }

    private List<String> extractCatalogItemSemanticTexts(DomainCatalogItemResponse item) {
        if (item == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        values.add(valueOrEmpty(item.itemKey()));
        values.add(valueOrEmpty(item.contextKey()));
        values.add(valueOrEmpty(item.nodeType()));
        values.add(valueOrEmpty(item.bindingType()));
        values.add(valueOrEmpty(item.edgeType()));
        JsonNode payload = item.payload();
        if (payload != null && !payload.isMissingNode() && !payload.isNull()) {
            values.add(text(payload, "label"));
            values.add(text(payload, "name"));
            values.add(text(payload, "title"));
            values.add(text(payload, "summary"));
            values.add(text(payload, "description"));
            values.add(text(payload, "aliases"));
            values.add(text(payload, "synonyms"));
            values.add(text(payload, "sourceNodeKey"));
            values.add(text(payload, "targetNodeKey"));
            values.add(joinArrayNode(path(payload, "tags")));
            JsonNode glossary = path(payload, "businessGlossary");
            values.add(text(glossary, "preferredTerm"));
            values.add(text(glossary, "description"));
            values.add(joinArrayNode(path(glossary, "synonyms")));
            JsonNode target = path(payload, "target");
            values.add(text(target, "fieldName"));
            values.add(text(target, "resourceKey"));
            values.add(text(target, "resourcePath"));
            values.add(text(target, "referencedResourceKey"));
            JsonNode metadata = path(payload, "metadata");
            values.add(text(metadata, "fieldName"));
            values.add(text(metadata, "resourceKey"));
            values.add(text(metadata, "resourcePath"));
            values.add(text(metadata, "referencedResourceKey"));
            values.add(text(metadata, "semanticRole"));
            values.add(text(metadata, "statsKind"));
            values.add(joinArrayNode(path(metadata, "analyzesConcepts")));
            values.add(joinArrayNode(path(metadata, "analyzedByDataProducts")));
            values.add(joinArrayNode(path(metadata, "childConcepts")));
            values.add(joinArrayNode(path(metadata, "parentConcepts")));
            values.add(joinArrayNode(path(metadata, "referencedConcepts")));
            JsonNode optionSource = path(metadata, "optionSource");
            values.add(text(optionSource, "key"));
            values.add(text(optionSource, "entityKey"));
            values.add(text(optionSource, "resourcePath"));
            values.add(text(optionSource, "referencedResourceKey"));
            values.add(text(metadata, "policyKind"));
            values.add(text(metadata, "ruleSetKey"));
            values.add(text(metadata, "operationKey"));
            values.add(joinArrayNode(path(metadata, "governedRuleSets")));
            values.add(joinArrayNode(path(metadata, "governedOperations")));
            values.add(joinArrayNode(path(metadata, "governedReasonCodes")));
            values.add(joinArrayNode(path(metadata, "ontologicalFactRoots")));
            values.add(joinArrayNode(path(metadata, "availableRoots")));
            values.add(joinArrayNode(path(metadata, "referencedFactPaths")));
            values.add(joinArrayNode(path(metadata, "reasonCodes")));
            values.add(joinArrayNode(path(metadata, "targetFields")));
            JsonNode rulesNode = path(metadata, "rules");
            if (rulesNode != null && rulesNode.isArray()) {
                for (JsonNode rule : rulesNode) {
                    if (rule != null && rule.isObject()) {
                        values.add(text(rule, "reasonCode"));
                        values.add(text(rule, "targetField"));
                        values.add(text(rule, "message"));
                        values.add(text(rule, "origin"));
                        values.add(text(rule, "lineage"));
                        values.add(text(rule, "slotKey"));
                    }
                }
            }
            values.add(text(target, "ruleSetKey"));
            values.add(text(target, "operationKey"));
            values.add(joinArrayNode(path(target, "availableRoots")));
            values.add(joinArrayNode(path(target, "referencedFactPaths")));
        }
        return values.stream().filter(v -> !v.isBlank()).toList();
    }

    private String joinArrayNode(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (element != null && element.isValueNode()) {
                String text = valueOrEmpty(element.asText(""));
                if (!text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return String.join(",", values);
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

    private String canonicalResourceKey(List<DomainCatalogItemResponse> items, String fallbackResourceKey) {
        if (items != null) {
            for (DomainCatalogItemResponse item : items) {
                if (item == null || item.payload() == null) {
                    continue;
                }
                JsonNode payload = item.payload();
                String candidate = firstNonBlank(
                        text(path(payload, "metadata"), "resourceKey"),
                        text(payload, "resourceKey"),
                        text(path(payload, "target"), "resourceKey"));
                if (!candidate.isBlank()) {
                    return candidate;
                }
            }
        }
        return fallbackResourceKey;
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
        return normalized.length() <= 320 ? normalized : normalized.substring(0, 320).trim();
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
