package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        String expectedMethod = expectedMethod(artifactKind);
        if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
            return repository == null ? List.of() : discoverBroadCandidates(artifactKind, expectedMethod);
        }
        List<AgenticAuthoringCandidate> semanticCandidates =
                discoverWithRetrievalService(normalizedPrompt, artifactKind, expectedMethod);
        if (!semanticCandidates.isEmpty()) {
            return semanticCandidates;
        }
        if (repository == null) {
            return List.of();
        }
        List<String> tokens = meaningfulTokens(expandDomainSynonyms(normalizedPrompt));
        if (tokens.isEmpty()) {
            return discoverBroadCandidates(artifactKind, expectedMethod);
        }
        return repository.findAll().stream()
                .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                .filter(metadata -> isRenderableBusinessEndpoint(metadata.getPath()))
                .filter(metadata -> expectedMethod == null || expectedMethod.equalsIgnoreCase(metadata.getMethod()))
                .map(metadata -> toScoredCandidate(metadata, expectedMethod, artifactKind, tokens))
                .filter(scored -> scored.score() >= 0.45d)
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                .limit(8)
                .map(ScoredCandidate::candidate)
                .toList();
    }

    private List<AgenticAuthoringCandidate> discoverBroadCandidates(String artifactKind, String expectedMethod) {
        if (!isBroadDiscoveryArtifact(artifactKind)) {
            return List.of();
        }
        return repository.findAll().stream()
                .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                .filter(metadata -> isRenderableBusinessEndpoint(metadata.getPath()))
                .filter(metadata -> expectedMethod == null || expectedMethod.equalsIgnoreCase(metadata.getMethod()))
                .map(metadata -> toBroadScoredCandidate(metadata, expectedMethod, artifactKind))
                .filter(scored -> scored.score() >= 0.36d)
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
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
            String expectedMethod) {
        if (retrievalService == null) {
            return List.of();
        }
        try {
            String method = expectedMethod == null ? null : expectedMethod.toUpperCase(Locale.ROOT);
            return retrievalService.searchApiMetadata(normalizedPrompt, method, null, 8).stream()
                    .filter(result -> result.getPath() != null && result.getMethod() != null)
                    .filter(result -> isRenderableBusinessEndpoint(result.getPath()))
                    .map(result -> toCandidate(result, artifactKind))
                    .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private AgenticAuthoringCandidate toCandidate(ApiSearchResult result, String artifactKind) {
        String operation = result.getMethod().toLowerCase(Locale.ROOT);
        String submitUrl = canonicalSubmitUrl(result.getPath(), operation, artifactKind);
        String resourcePath = baseResourcePath(result.getPath());
        double score = Math.max(0.45d, Math.min(0.98d, result.getSimilarityScore()));
        return new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaUrl(submitUrl, operation),
                submitUrl,
                operation,
                score,
                "api_metadata semantic retrieval",
                List.of("api-metadata", "semantic-retrieval", "schema-available", "actions-probe-pending"));
    }

    private ScoredCandidate toScoredCandidate(
            ApiMetadata metadata,
            String expectedMethod,
            String artifactKind,
            List<String> tokens) {
        String endpointText = normalize(String.join(" ",
                valueOrEmpty(metadata.getPath()),
                valueOrEmpty(metadata.getTags()),
                valueOrEmpty(metadata.getSummary()),
                valueOrEmpty(metadata.getDescription()),
                valueOrEmpty(metadata.getOperationId())));
        double score = expectedMethod == null || expectedMethod.equalsIgnoreCase(metadata.getMethod()) ? 0.34d : 0.20d;
        int matches = 0;
        for (String token : tokens) {
            if (matchesToken(endpointText, token)) {
                matches++;
                score += metadata.getPath() != null && matchesToken(normalize(metadata.getPath()), token) ? 0.14d : 0.09d;
            }
        }
        if (matches == 0) {
            score = 0d;
        }
        score += artifactScoreAdjustment(artifactKind, endpointText, normalize(metadata.getPath()));
        score = Math.min(0.98d, score);
        String operation = metadata.getMethod().toLowerCase(Locale.ROOT);
        String submitUrl = canonicalSubmitUrl(metadata.getPath(), operation, artifactKind);
        String resourcePath = baseResourcePath(metadata.getPath());
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaUrl(submitUrl, operation),
                submitUrl,
                operation,
                score,
                "api_metadata lexical match",
                List.of("api-metadata", "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending")),
                score);
    }

    private ScoredCandidate toBroadScoredCandidate(
            ApiMetadata metadata,
            String expectedMethod,
            String artifactKind) {
        String endpointText = normalize(String.join(" ",
                valueOrEmpty(metadata.getPath()),
                valueOrEmpty(metadata.getTags()),
                valueOrEmpty(metadata.getSummary()),
                valueOrEmpty(metadata.getDescription()),
                valueOrEmpty(metadata.getOperationId())));
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
        String resourcePath = baseResourcePath(metadata.getPath());
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaUrl(submitUrl, operation),
                submitUrl,
                operation,
                score,
                "api_metadata broad artifact discovery",
                List.of("api-metadata", "broad-artifact-discovery", "schema-probe-pending", "actions-probe-pending")),
                score);
    }

    private boolean isRenderableBusinessEndpoint(String path) {
        String normalizedPath = normalize(path);
        return !normalizedPath.endsWith("/schemas")
                && !normalizedPath.contains("/schemas/")
                && !normalizedPath.endsWith("/capabilities")
                && !normalizedPath.contains("/capabilities/")
                && !normalizedPath.endsWith("/actions")
                && !normalizedPath.contains("/actions/")
                && !normalizedPath.endsWith("/surfaces")
                && !normalizedPath.contains("/surfaces/")
                && !normalizedPath.contains("{")
                && !normalizedPath.endsWith("/by-ids")
                && !normalizedPath.endsWith("/options")
                && !normalizedPath.contains("/options/")
                && !normalizedPath.endsWith("/batch")
                && !normalizedPath.contains("/batch/")
                && !normalizedPath.endsWith("/locate")
                && !normalizedPath.contains("/locate/");
    }

    private double artifactScoreAdjustment(String artifactKind, String endpointText, String path) {
        double adjustment = 0d;
        boolean analyticsLike = endpointText.contains("analytics") || endpointText.contains("analit") || path.contains("/vw-");
        if (analyticsLike) {
            adjustment += 0.05d;
        }
        if ("dashboard".equals(artifactKind)) {
            if (analyticsLike || endpointText.contains("dashboard")) {
                adjustment += 0.26d;
            }
            if (path.endsWith("/stats/group-by") || path.endsWith("/stats/timeseries")
                    || path.endsWith("/stats/distribution")) {
                adjustment += 0.24d;
            }
            if (path.endsWith("/all") || path.endsWith("/by-ids") || path.contains("/{")) {
                adjustment -= 0.18d;
            }
            if (!analyticsLike && !path.contains("/stats/")) {
                adjustment -= 0.12d;
            }
        }
        if ("table".equals(artifactKind)) {
            if (analyticsLike) {
                adjustment -= 0.14d;
            }
            if (path.endsWith("/all") || path.endsWith("/filter")) {
                adjustment += 0.18d;
            }
            if (path.endsWith("/all") || path.endsWith("/by-ids") || path.contains("/{")) {
                adjustment -= 0.12d;
            }
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

    private String expandDomainSynonyms(String normalizedPrompt) {
        if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
            return "";
        }
        StringBuilder expanded = new StringBuilder(normalizedPrompt);
        if (containsAny(normalizedPrompt, "chamado", "chamados", "helpdesk", "notebook", "notebooks",
                "tela quebrada", "quebrada", "quebrado", "manutencao", "incidente", "incidentes")) {
            expanded.append(" alerta alertas socorro incidente incidentes equipamento equipamentos")
                    .append(" operacional triagem ocorrencia ocorrencias");
        }
        return expanded.toString();
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
        if ("dashboard".equals(artifactKind)) {
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
        if ("get".equalsIgnoreCase(operation) && !isKnownCollectionOperation(normalized) && !normalized.contains("/{")) {
            return normalized + "/all";
        }
        if ("table".equals(artifactKind) && !isKnownCollectionOperation(normalized) && !normalized.contains("/{")) {
            return normalized + "/all";
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
        return path.endsWith("/all")
                || path.endsWith("/filter")
                || path.endsWith("/filter/cursor")
                || path.endsWith("/stats/group-by")
                || path.endsWith("/stats/timeseries")
                || path.endsWith("/stats/distribution");
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
}
