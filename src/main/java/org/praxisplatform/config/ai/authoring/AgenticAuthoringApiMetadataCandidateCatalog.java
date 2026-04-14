package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;

public class AgenticAuthoringApiMetadataCandidateCatalog {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "as", "o", "os", "um", "uma", "de", "da", "das", "do", "dos", "para", "por",
            "com", "em", "no", "na", "nos", "nas", "crie", "criar", "gere", "gerar", "monte",
            "montar", "quero", "usar", "use", "visualizar", "ver", "mostre", "mostrar", "tabela",
            "lista", "listagem", "dashboard", "painel", "formulario", "form", "grafico", "chart");

    private final ApiMetadataRepository repository;

    public AgenticAuthoringApiMetadataCandidateCatalog(ApiMetadataRepository repository) {
        this.repository = repository;
    }

    public List<AgenticAuthoringCandidate> discover(String normalizedPrompt, String artifactKind) {
        if (repository == null || normalizedPrompt == null || normalizedPrompt.isBlank()) {
            return List.of();
        }
        String expectedMethod = expectedMethod(artifactKind);
        List<String> tokens = meaningfulTokens(expandDomainSynonyms(normalizedPrompt));
        if (tokens.isEmpty()) {
            return List.of();
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
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                metadata.getPath(),
                operation,
                schemaUrl(metadata.getPath(), operation),
                metadata.getPath(),
                operation,
                score,
                "api_metadata lexical match",
                List.of("api-metadata", "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending")),
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
                && !normalizedPath.endsWith("/all")
                && !normalizedPath.endsWith("/by-ids")
                && !normalizedPath.endsWith("/filter")
                && !normalizedPath.endsWith("/filter/cursor")
                && !normalizedPath.endsWith("/options")
                && !normalizedPath.contains("/options/")
                && !normalizedPath.endsWith("/batch")
                && !normalizedPath.contains("/batch/")
                && !normalizedPath.endsWith("/locate")
                && !normalizedPath.contains("/locate/");
    }

    private double artifactScoreAdjustment(String artifactKind, String endpointText, String path) {
        double adjustment = 0d;
        if (endpointText.contains("analytics") || endpointText.contains("analit") || path.contains("/vw-")) {
            adjustment += 0.05d;
        }
        if ("dashboard".equals(artifactKind)) {
            if (endpointText.contains("analytics") || endpointText.contains("analit")
                    || endpointText.contains("dashboard") || path.contains("/vw-")) {
                adjustment += 0.26d;
            }
            if (path.endsWith("/all") || path.endsWith("/by-ids") || path.contains("/{")) {
                adjustment -= 0.18d;
            }
        }
        if ("table".equals(artifactKind)) {
            if (endpointText.contains("analytics") || endpointText.contains("analit") || path.contains("/vw-")) {
                adjustment -= 0.14d;
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
        if ("dashboard".equals(artifactKind) || "table".equals(artifactKind)) {
            return "get";
        }
        if ("unknown".equals(artifactKind)) {
            return "get";
        }
        return null;
    }

    private String schemaUrl(String resourcePath, String operation) {
        String schemaType = "get".equalsIgnoreCase(operation) ? "response" : "request";
        return "/schemas/filtered?path=" + resourcePath + "&operation=" + operation + "&schemaType=" + schemaType;
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
