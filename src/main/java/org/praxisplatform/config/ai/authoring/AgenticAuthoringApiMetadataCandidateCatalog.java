package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.ContextRetrievalService;

public class AgenticAuthoringApiMetadataCandidateCatalog {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "as", "o", "os", "um", "uma", "de", "da", "das", "do", "dos", "para", "por",
            "com", "em", "no", "na", "nos", "nas", "crie", "criar", "gere", "gerar", "monte",
            "montar", "quero", "usar", "use", "visualizar", "ver", "mostre", "mostrar", "tabela",
            "lista", "listagem", "dashboard", "dashboards", "painel", "formulario", "form", "grafico", "chart",
            "graficos", "indicador", "indicadores", "kpi", "kpis", "metrica", "metricas",
            "qual", "quais", "outra", "outras", "opcao", "opcoes", "voce", "antes", "indica", "indicar",
            "indique", "alternativa", "alternativas", "compare", "comparar", "recomende", "recomendar");

    private final ApiMetadataRepository repository;
    private final ContextRetrievalService retrievalService;

    public AgenticAuthoringApiMetadataCandidateCatalog(ApiMetadataRepository repository) {
        this(repository, null);
    }

    public AgenticAuthoringApiMetadataCandidateCatalog(
            ApiMetadataRepository repository,
            ContextRetrievalService retrievalService) {
        this.repository = repository;
        this.retrievalService = retrievalService;
    }

    public List<AgenticAuthoringCandidate> discover(String normalizedPrompt, String artifactKind) {
        return discover(normalizedPrompt, artifactKind, null, null, null);
    }

    public List<AgenticAuthoringCandidate> discover(
            String normalizedPrompt,
            String artifactKind,
            String tenantId,
            String environment,
            String releaseId) {
        String expectedMethod = expectedMethod(artifactKind);
        RetrievalContext context = new RetrievalContext(
                normalizedPrompt,
                artifactKind,
                expectedMethod,
                tenantId,
                environment,
                releaseId);
        if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
            return repository == null ? List.of() : new BroadArtifactCandidateRetriever().retrieve(context);
        }
        List<AgenticAuthoringCandidate> semanticCandidates = new SemanticCandidateRetriever().retrieve(context);
        if (repository == null) {
            return semanticCandidates;
        }
        if (!semanticCandidates.isEmpty()) {
            return semanticCandidates;
        }
        List<String> originalTokens = meaningfulTokens(normalizedPrompt);
        if (originalTokens.isEmpty()) {
            return mergeCandidates(semanticCandidates, new BroadArtifactCandidateRetriever().retrieve(context));
        }
        List<String> tokens = meaningfulTokens(normalizedPrompt);
        List<AgenticAuthoringCandidate> lexicalCandidates =
                new LexicalFallbackCandidateRetriever().retrieve(context.withTokens(tokens));
        List<AgenticAuthoringCandidate> mergedCandidates =
                mergeCandidates(lexicalCandidates, semanticCandidates);
        return mergedCandidates;
    }

    private List<AgenticAuthoringCandidate> mergeCandidates(
            List<AgenticAuthoringCandidate> primary,
            List<AgenticAuthoringCandidate> secondary) {
        Map<String, AgenticAuthoringCandidate> candidatesByResource = new LinkedHashMap<>();
        for (AgenticAuthoringCandidate candidate : concat(primary, secondary)) {
            if (candidate == null || candidate.resourcePath() == null || candidate.resourcePath().isBlank()) {
                continue;
            }
            candidatesByResource.merge(
                    candidate.resourcePath(),
                    candidate,
                    (existing, replacement) -> replacement.score() > existing.score() ? replacement : existing);
        }
        return candidatesByResource.values().stream()
                .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                .limit(8)
                .toList();
    }

    private List<AgenticAuthoringCandidate> concat(
            List<AgenticAuthoringCandidate> primary,
            List<AgenticAuthoringCandidate> secondary) {
        List<AgenticAuthoringCandidate> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            merged.addAll(secondary);
        }
        return merged;
    }

    private List<AgenticAuthoringCandidate> discoverBroadCandidates(String artifactKind, String expectedMethod) {
        if (!isBroadDiscoveryArtifact(artifactKind)) {
            return List.of();
        }
        return repository.findAll().stream()
                .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                .filter(metadata -> isRenderableBusinessEndpoint(metadata.getPath()))
                .filter(metadata -> expectedMethod == null || expectedMethod.equalsIgnoreCase(metadata.getMethod()))
                .map(metadata -> toBroadScoredCandidate(metadata, expectedMethod, artifactKind, null, null, null))
                .filter(scored -> scored.score() >= 0.36d)
                .sorted(CandidateRankingPolicy.byScoreDescending())
                .limit(8)
                .map(ScoredCandidate::candidate)
                .toList();
    }

    private boolean isBroadDiscoveryArtifact(String artifactKind) {
        return "dashboard".equals(artifactKind)
                || "table".equals(artifactKind)
                || "form".equals(artifactKind)
                || "unknown".equals(artifactKind);
    }

    private List<AgenticAuthoringCandidate> discoverWithRetrievalService(
            String normalizedPrompt,
            String artifactKind,
            String expectedMethod,
            String tenantId,
            String environment,
            String releaseId) {
        if (retrievalService == null) {
            return List.of();
        }
        try {
            String method = expectedMethod == null ? null : expectedMethod.toUpperCase(Locale.ROOT);
            return retrievalService.searchApiMetadata(
                            normalizedPrompt,
                            method,
                            null,
                            8,
                            null,
                            tenantId,
                            environment,
                            releaseId)
                    .stream()
                    .filter(result -> result.getPath() != null && result.getMethod() != null)
                    .filter(result -> isRenderableBusinessEndpoint(result.getPath()))
                    .map(result -> toCandidate(
                            result,
                            artifactKind,
                            normalizedPrompt,
                            tenantId,
                            environment,
                            releaseId))
                    .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private interface CandidateRetriever {

        List<AgenticAuthoringCandidate> retrieve(RetrievalContext context);
    }

    private final class SemanticCandidateRetriever implements CandidateRetriever {

        @Override
        public List<AgenticAuthoringCandidate> retrieve(RetrievalContext context) {
            return discoverWithRetrievalService(
                    context.normalizedPrompt(),
                    context.artifactKind(),
                    context.expectedMethod(),
                    context.tenantId(),
                    context.environment(),
                    context.releaseId());
        }
    }

    private final class LexicalFallbackCandidateRetriever implements CandidateRetriever {

        @Override
        public List<AgenticAuthoringCandidate> retrieve(RetrievalContext context) {
            return repository.findAll().stream()
                    .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                    .filter(metadata -> isRenderableBusinessEndpoint(metadata.getPath()))
                    .filter(metadata -> context.expectedMethod() == null
                            || context.expectedMethod().equalsIgnoreCase(metadata.getMethod()))
                    .map(metadata -> toScoredCandidate(
                            metadata,
                            context.expectedMethod(),
                            context.artifactKind(),
                            context.tokens(),
                            context.tenantId(),
                            context.environment(),
                            context.releaseId()))
                    .filter(scored -> scored.score() >= 0.45d)
                    .sorted(CandidateRankingPolicy.byScoreDescending())
                    .limit(8)
                    .map(ScoredCandidate::candidate)
                    .toList();
        }
    }

    private final class BroadArtifactCandidateRetriever implements CandidateRetriever {

        @Override
        public List<AgenticAuthoringCandidate> retrieve(RetrievalContext context) {
            return discoverBroadCandidates(context.artifactKind(), context.expectedMethod());
        }
    }

    private static final class CandidateRankingPolicy {

        private CandidateRankingPolicy() {
        }

        static Comparator<ScoredCandidate> byScoreDescending() {
            return Comparator.comparingDouble(ScoredCandidate::score).reversed();
        }
    }

    private AgenticAuthoringCandidate toCandidate(
            ApiSearchResult result,
            String artifactKind,
            String normalizedPrompt,
            String tenantId,
            String environment,
            String releaseId) {
        String operation = result.getMethod().toLowerCase(Locale.ROOT);
        String submitUrl = canonicalSubmitUrl(result.getPath(), operation, artifactKind);
        String submitMethod = canonicalSubmitMethod(submitUrl, operation);
        String resourcePath = baseResourcePath(result.getPath());
        double score = Math.max(0.45d, Math.min(0.98d, result.getSimilarityScore()));
        return new AgenticAuthoringCandidate(
                resourcePath,
                submitMethod,
                schemaUrl(submitUrl, submitMethod),
                submitUrl,
                submitMethod,
                score,
                "api_metadata semantic retrieval",
                List.of("api-metadata", "semantic-retrieval", "schema-available", "actions-probe-pending"),
                evidenceBundle(
                        "semantic_retrieval",
                        resourcePath,
                        submitUrl,
                        submitMethod,
                        valueOrEmpty(result.getSummary()),
                        score,
                        meaningfulTokens(normalize(valueOrEmpty(normalizedPrompt))),
                        tenantId,
                        environment,
                        releaseId,
                        false));
    }

    private ScoredCandidate toScoredCandidate(
            ApiMetadata metadata,
            String expectedMethod,
            String artifactKind,
            List<String> tokens,
            String tenantId,
            String environment,
            String releaseId) {
        String endpointText = searchableText(metadata);
        String path = normalize(metadata.getPath());
        double score = expectedMethod == null || expectedMethod.equalsIgnoreCase(metadata.getMethod()) ? 0.34d : 0.20d;
        int matches = 0;
        for (String token : tokens) {
            if (matchesToken(endpointText, token)) {
                matches++;
                score += metadata.getPath() != null && matchesToken(path, token) ? 0.14d : 0.09d;
            }
        }
        if (matches == 0) {
            score = 0d;
        }
        score += artifactScoreAdjustment(artifactKind, endpointText, path);
        score = Math.min(0.98d, score);
        score += dashboardOperationScoreAdjustment(artifactKind, path, tokens);
        score = Math.max(0d, Math.min(0.99d, score));
        String operation = metadata.getMethod().toLowerCase(Locale.ROOT);
        String submitUrl = canonicalSubmitUrl(metadata.getPath(), operation, artifactKind);
        String submitMethod = canonicalSubmitMethod(submitUrl, operation);
        String resourcePath = baseResourcePath(metadata.getPath());
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                resourcePath,
                submitMethod,
                schemaUrl(submitUrl, submitMethod),
                submitUrl,
                submitMethod,
                score,
                "api_metadata weak lexical fallback evidence",
                List.of("api-metadata", "lexical-fallback", "weak-evidence", "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending"),
                evidenceBundle(
                        "lexical_fallback",
                        resourcePath,
                        submitUrl,
                        submitMethod,
                        compactReasonText(searchableText(metadata)),
                        Math.min(score, 0.49d),
                        tokens,
                        tenantId,
                        environment,
                        releaseId,
                        true)),
                score);
    }

    private ScoredCandidate toBroadScoredCandidate(
            ApiMetadata metadata,
            String expectedMethod,
            String artifactKind,
            String tenantId,
            String environment,
            String releaseId) {
        String endpointText = searchableText(metadata);
        String path = normalize(metadata.getPath());
        double score = expectedMethod == null || expectedMethod.equalsIgnoreCase(metadata.getMethod()) ? 0.36d : 0.20d;
        score += artifactScoreAdjustment(artifactKind, endpointText, path);
        if (path.contains("/api/praxis/config/") || path.contains("/actuator") || path.contains("/auth/")) {
            score -= 0.40d;
        }
        if (endpointText.contains("metadata") || endpointText.contains("schema") || endpointText.contains("swagger")) {
            score -= 0.12d;
        }
        score = Math.max(0d, Math.min(0.90d, score));
        String operation = metadata.getMethod().toLowerCase(Locale.ROOT);
        String submitUrl = canonicalSubmitUrl(metadata.getPath(), operation, artifactKind);
        String submitMethod = canonicalSubmitMethod(submitUrl, operation);
        String resourcePath = baseResourcePath(metadata.getPath());
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                resourcePath,
                submitMethod,
                schemaUrl(submitUrl, submitMethod),
                submitUrl,
                submitMethod,
                score,
                broadDiscoveryReason(metadata),
                List.of("api-metadata", "broad-artifact-discovery", "schema-probe-pending", "actions-probe-pending"),
                evidenceBundle(
                        "broad_artifact_discovery",
                        resourcePath,
                        submitUrl,
                        submitMethod,
                        broadDiscoveryReason(metadata),
                        Math.min(score, 0.72d),
                        List.of(),
                        tenantId,
                        environment,
                        releaseId,
                        false)),
                score);
    }

    private String broadDiscoveryReason(ApiMetadata metadata) {
        String businessContext = compactReasonText(String.join(" ",
                valueOrEmpty(metadata.getTags()),
                valueOrEmpty(metadata.getSummary()),
                valueOrEmpty(metadata.getDescription()),
                valueOrEmpty(metadata.getOperationId())));
        if (businessContext.isBlank()) {
            return "api_metadata broad artifact discovery";
        }
        return "api_metadata broad artifact discovery: " + businessContext;
    }

    private AgenticAuthoringEvidenceBundle evidenceBundle(
            String retrievalSource,
            String resourcePath,
            String submitUrl,
            String submitMethod,
            String summary,
            double confidence,
            List<String> matchedTerms,
            String tenantId,
            String environment,
            String releaseId,
            boolean weakLexical) {
        List<String> terms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        String safeResource = valueOrEmpty(resourcePath);
        String safeSubmit = valueOrEmpty(submitUrl);
        String safeMethod = valueOrEmpty(submitMethod).toLowerCase(Locale.ROOT);
        String safeSummary = compactReasonText(summary);
        List<AgenticAuthoringEvidenceBundle.Evidence> evidence = new ArrayList<>();
        evidence.add(new AgenticAuthoringEvidenceBundle.Evidence(
                "api_metadata",
                weakLexical ? "weak_lexical_match" : "retrieved_candidate",
                safeResource,
                safeSummary,
                weakLexical ? Math.min(confidence, 0.49d) : confidence,
                terms,
                tenantId,
                environment,
                releaseId));
        if (!safeSubmit.isBlank()) {
            evidence.add(new AgenticAuthoringEvidenceBundle.Evidence(
                    "/schemas/filtered",
                    weakLexical ? "schema_probe_pending" : "schema_grounding",
                    schemaUrl(safeSubmit, safeMethod),
                    "Canonical filtered schema for the selected operation.",
                    weakLexical ? 0.35d : 0.78d,
                    terms,
                    tenantId,
                    environment,
                    releaseId));
            evidence.add(new AgenticAuthoringEvidenceBundle.Evidence(
                    "actions",
                    weakLexical ? "actions_probe_pending" : "operation_grounding",
                    safeMethod.toUpperCase(Locale.ROOT) + " " + safeSubmit,
                    "Candidate operation and materialization endpoint.",
                    weakLexical ? 0.34d : 0.74d,
                    terms,
                    tenantId,
                    environment,
                    releaseId));
        }
        if (!safeResource.isBlank()) {
            evidence.add(new AgenticAuthoringEvidenceBundle.Evidence(
                    "capabilities",
                    weakLexical ? "capabilities_probe_pending" : "resource_capability_hint",
                    safeResource + "/capabilities",
                    "Resource capability snapshot candidate.",
                    weakLexical ? 0.32d : 0.68d,
                    terms,
                    tenantId,
                    environment,
                    releaseId));
            evidence.add(new AgenticAuthoringEvidenceBundle.Evidence(
                    "domain_catalog",
                    "domain_catalog_hint",
                    domainCatalogRef(safeResource),
                    "Domain catalog key inferred from the API resource path.",
                    weakLexical ? 0.30d : 0.62d,
                    domainTerms(safeResource),
                    tenantId,
                    environment,
                    releaseId));
        }
        return AgenticAuthoringEvidenceBundle.of(retrievalSource, evidence);
    }

    private String domainCatalogRef(String resourcePath) {
        String normalized = normalizePath(resourcePath);
        if (normalized.startsWith("/api/")) {
            normalized = normalized.substring(5);
        } else if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.replace('/', '.');
    }

    private List<String> domainTerms(String resourcePath) {
        return meaningfulTokens(normalize(domainCatalogRef(resourcePath).replace('.', ' ')));
    }

    private String compactReasonText(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240).trim();
    }

    private boolean isRenderableBusinessEndpoint(String path) {
        String normalizedPath = normalize(path);
        return normalizedPath.startsWith("/api/")
                && !normalizedPath.startsWith("/api/praxis/config/")
                && !normalizedPath.endsWith("/schemas")
                && !normalizedPath.contains("/schemas/")
                && !normalizedPath.endsWith("/capabilities")
                && !normalizedPath.contains("/capabilities/")
                && !normalizedPath.endsWith("/actions")
                && !normalizedPath.contains("/actions/")
                && !normalizedPath.endsWith("/surfaces")
                && !normalizedPath.contains("/surfaces/")
                && !normalizedPath.contains("{")
                && !normalizedPath.endsWith("/all")
                && !normalizedPath.endsWith("/by-ids")
                && !normalizedPath.endsWith("/options")
                && !normalizedPath.contains("/options/")
                && !normalizedPath.endsWith("/batch")
                && !normalizedPath.contains("/batch/")
                && !normalizedPath.endsWith("/export")
                && !normalizedPath.contains("/export/")
                && !normalizedPath.endsWith("/locate")
                && !normalizedPath.contains("/locate/");
    }

    private double dashboardOperationScoreAdjustment(String artifactKind, String path, List<String> tokens) {
        if (!"dashboard".equals(artifactKind) || path == null || path.isBlank()) {
            return 0d;
        }
        if (path.endsWith("/stats/group-by") && hasGroupingToken(tokens)) {
            return 0.10d;
        }
        if (path.endsWith("/stats/timeseries") && !hasTemporalToken(tokens)) {
            return -0.08d;
        }
        if (path.endsWith("/stats/distribution") && !hasDistributionToken(tokens)) {
            return -0.05d;
        }
        return 0d;
    }

    private boolean hasGroupingToken(List<String> tokens) {
        return tokens != null && tokens.stream().anyMatch(token -> containsAny(token,
                "grupo", "agrupamento", "categoria", "categorias", "recorte"));
    }

    private boolean hasTemporalToken(List<String> tokens) {
        return tokens != null && tokens.stream().anyMatch(token -> containsAny(token,
                "tempo", "temporal", "serie", "series", "evolucao", "historico",
                "mes", "mensal", "competencia", "periodo", "data"));
    }

    private boolean hasDistributionToken(List<String> tokens) {
        return tokens != null && tokens.stream().anyMatch(token -> containsAny(token,
                "distribuicao", "faixa", "faixas", "histograma", "dispersao"));
    }

    private String searchableText(ApiMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        return normalize(String.join(" ",
                valueOrEmpty(metadata.getPath()),
                valueOrEmpty(metadata.getTags()),
                valueOrEmpty(metadata.getSummary()),
                valueOrEmpty(metadata.getDescription()),
                valueOrEmpty(metadata.getOperationId()),
                valueOrEmpty(metadata.getRequestSchema()),
                valueOrEmpty(metadata.getResponseSchema()),
                valueOrEmpty(metadata.getParameters())));
    }

    private double artifactScoreAdjustment(String artifactKind, String endpointText, String path) {
        double adjustment = 0d;
        boolean analyticalMetadata = containsAny(endpointText,
                "analytics", "analit", "metric", "metrica", "indicador", "indicadores", "kpi", "dashboard");
        if ("dashboard".equals(artifactKind)) {
            if (analyticalMetadata) {
                adjustment += 0.18d;
            }
            if (path.endsWith("/stats/group-by") || path.endsWith("/stats/timeseries")
                    || path.endsWith("/stats/distribution")) {
                adjustment += 0.16d;
            }
            if (path.endsWith("/all") || path.endsWith("/by-ids") || path.contains("/{")) {
                adjustment -= 0.18d;
            }
            if (!analyticalMetadata && !path.contains("/stats/")) {
                adjustment -= 0.12d;
            }
        }
        if ("table".equals(artifactKind)) {
            if (analyticalMetadata && !endpointText.contains("operacional")) {
                adjustment -= 0.10d;
            }
            if (path.endsWith("/filter") || path.endsWith("/filter/cursor")) {
                adjustment += 0.18d;
            }
            if (path.endsWith("/all") || path.endsWith("/by-ids") || path.contains("/{")) {
                adjustment -= 0.12d;
            }
        }
        if ("page".equals(artifactKind)) {
            if (path.endsWith("/filter") || path.endsWith("/filter/cursor")) {
                adjustment += 0.18d;
            }
        }
        if (endpointText.contains("legado")) {
            adjustment -= 0.20d;
        }
        return adjustment;
    }

    private boolean matchesToken(String text, String token) {
        if (text.contains(token)) {
            return true;
        }
        if (token.endsWith("s") && token.length() > 4) {
            return text.contains(token.substring(0, token.length() - 1));
        }
        return text.contains(token + "s");
    }

    private List<String> meaningfulTokens(String normalizedPrompt) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String rawToken : normalizedPrompt.replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (rawToken.length() < 4 || STOP_WORDS.contains(rawToken)) {
                continue;
            }
            tokens.add(rawToken);
        }
        return new ArrayList<>(tokens);
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (token != null && !token.isBlank() && value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String expectedMethod(String artifactKind) {
        if ("form".equals(artifactKind)) {
            return "post";
        }
        if ("table".equals(artifactKind)) {
            return null;
        }
        if ("dashboard".equals(artifactKind) || "page".equals(artifactKind)) {
            return null;
        }
        if ("unknown".equals(artifactKind)) {
            return "get";
        }
        return null;
    }

    private String schemaUrl(String resourcePath, String operation) {
        String schemaType = "get".equalsIgnoreCase(operation) || isReadProjectionOperation(resourcePath, operation)
                ? "response"
                : "request";
        return "/schemas/filtered?path=" + resourcePath + "&operation=" + operation + "&schemaType=" + schemaType;
    }

    private String canonicalSubmitUrl(String path, String operation, String artifactKind) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = normalizePath(path);
        if (("table".equals(artifactKind) || "page".equals(artifactKind) || "unknown".equals(artifactKind))
                && normalized.endsWith("/filter")) {
            return normalized + "/cursor";
        }
        if ("dashboard".equals(artifactKind) && !isKnownCollectionOperation(normalized) && !normalized.contains("/{")) {
            return normalized + "/stats/group-by";
        }
        if (("table".equals(artifactKind) || "page".equals(artifactKind) || "unknown".equals(artifactKind))
                && !isKnownCollectionOperation(normalized)
                && !normalized.contains("/{")) {
            return normalized + "/filter/cursor";
        }
        return normalized;
    }

    private String baseResourcePath(String path) {
        String normalized = normalizePath(path);
        for (String suffix : List.of(
                "/stats/group-by",
                "/stats/timeseries",
                "/stats/distribution",
                "/filter/cursor",
                "/filter",
                "/all")) {
            if (normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }

    private boolean isKnownCollectionOperation(String path) {
        return path.endsWith("/filter")
                || path.endsWith("/filter/cursor")
                || path.endsWith("/stats/group-by")
                || path.endsWith("/stats/timeseries")
                || path.endsWith("/stats/distribution");
    }

    private String canonicalSubmitMethod(String submitUrl, String operation) {
        if (isReadProjectionOperation(submitUrl, "post")) {
            return "post";
        }
        return operation;
    }

    private boolean isReadProjectionOperation(String submitUrl, String operation) {
        String normalized = submitUrl == null ? "" : submitUrl.toLowerCase(Locale.ROOT);
        return "post".equalsIgnoreCase(operation)
                && (normalized.endsWith("/stats/group-by")
                || normalized.endsWith("/stats/timeseries")
                || normalized.endsWith("/stats/distribution")
                || normalized.endsWith("/filter")
                || normalized.endsWith("/filter/cursor"));
    }

    private String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ScoredCandidate(AgenticAuthoringCandidate candidate, double score) {
    }

    private record RetrievalContext(
            String normalizedPrompt,
            String artifactKind,
            String expectedMethod,
            String tenantId,
            String environment,
            String releaseId,
            List<String> tokens
    ) {

        private RetrievalContext(
                String normalizedPrompt,
                String artifactKind,
                String expectedMethod,
                String tenantId,
                String environment,
                String releaseId) {
            this(normalizedPrompt, artifactKind, expectedMethod, tenantId, environment, releaseId, List.of());
        }

        private RetrievalContext withTokens(List<String> tokens) {
            return new RetrievalContext(
                    normalizedPrompt,
                    artifactKind,
                    expectedMethod,
                    tenantId,
                    environment,
                    releaseId,
                    tokens == null ? List.of() : List.copyOf(tokens));
        }
    }
}
