package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class AgenticAuthoringResourceDiscoveryService {

    private static final String TOOL_NAME = "searchApiResources";

    private final AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog,
            ObjectMapper objectMapper) {
        this.candidateCatalog = candidateCatalog;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public AgenticAuthoringResourceCandidatesResult search(
            AgenticAuthoringResourceCandidatesRequest request) {
        String retrievalQuery = retrievalQuery(request);
        String artifactKind = artifactKind(request);
        List<String> warnings = new ArrayList<>();
        if (retrievalQuery.isBlank()) {
            warnings.add("resource-discovery-query-required");
            return new AgenticAuthoringResourceCandidatesResult(
                    false,
                    TOOL_NAME,
                    retrievalQuery,
                    artifactKind,
                    "Ainda preciso de uma descricao do dado de negocio para buscar APIs no catalogo.",
                    List.of(),
                    List.of(),
                    List.copyOf(warnings));
        }
        List<AgenticAuthoringCandidate> candidates = candidateCatalog == null
                ? List.of()
                : candidateCatalog.discover(retrievalQuery, artifactKind);
        int limit = limit(request);
        if (candidates.size() > limit) {
            candidates = candidates.subList(0, limit);
            warnings.add("resource-candidates-limited");
        }
        if (candidates.isEmpty()) {
            warnings.add("resource-candidates-empty");
        }
        List<AgenticAuthoringQuickReply> quickReplies = candidateResourceQuickReplies(candidates, artifactKind);
        return new AgenticAuthoringResourceCandidatesResult(
                true,
                TOOL_NAME,
                retrievalQuery,
                artifactKind,
                assistantMessage(candidates, artifactKind),
                List.copyOf(candidates),
                quickReplies,
                List.copyOf(warnings));
    }

    private String assistantMessage(List<AgenticAuthoringCandidate> candidates, String artifactKind) {
        if (candidates == null || candidates.isEmpty()) {
            return switch (artifactKind) {
                case "dashboard", "page" -> "Nao encontrei uma API compativel para alimentar este painel. Descreva melhor o dado de negocio, por exemplo area, entidade, indicador ou recorte esperado.";
                case "table" -> "Nao encontrei uma API de colecao compativel para esta tabela. Informe qual entidade ou processo operacional voce quer listar.";
                case "form" -> "Nao encontrei uma API de criacao compativel para este formulario. Informe qual recurso de negocio deve ser cadastrado.";
                default -> "Nao encontrei APIs candidatas para esse pedido. Descreva o dado de negocio que deve alimentar a tela.";
            };
        }
        String artifactLabel = switch (artifactKind) {
            case "dashboard", "page" -> "painel";
            case "table" -> "tabela";
            case "form" -> "formulario";
            default -> "tela";
        };
        return "Encontrei APIs que podem alimentar este " + artifactLabel
                + ". Escolha a fonte de dados mais alinhada ao recorte de negocio antes de gerar a pre-visualizacao.";
    }

    private List<AgenticAuthoringQuickReply> candidateResourceQuickReplies(
            List<AgenticAuthoringCandidate> candidates,
            String artifactKind) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Long> resourcePathCounts = candidates.stream()
                .collect(Collectors.groupingBy(
                        candidate -> valueOrDefault(candidate.resourcePath(), ""),
                        Collectors.counting()));
        return candidates.stream()
                .map(candidate -> {
                    ObjectNode contextHints = objectMapper.createObjectNode();
                    contextHints.put("resourcePath", candidate.resourcePath());
                    contextHints.put("submitUrl", candidate.submitUrl());
                    contextHints.put("operation", candidate.operation());
                    contextHints.put("schemaUrl", candidate.schemaUrl());
                    contextHints.put("submitMethod", candidate.submitMethod());
                    contextHints.put("artifactKind", artifactKind);
                    boolean duplicatedResourcePath = resourcePathCounts.getOrDefault(
                            valueOrDefault(candidate.resourcePath(), ""),
                            0L) > 1L;
                    return new AgenticAuthoringQuickReply(
                            quickReplyId(candidate, duplicatedResourcePath),
                            "suggestion",
                            candidateLabel(candidate),
                            "Usar " + candidate.resourcePath() + " como fonte de dados.",
                            candidateDescription(candidate),
                            candidateIcon(candidate),
                            candidateTone(candidate),
                            contextHints);
                })
                .toList();
    }

    private String quickReplyId(AgenticAuthoringCandidate candidate, boolean includeOperation) {
        String base = "resource-" + normalize(candidate.resourcePath())
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (!includeOperation) {
            return base;
        }
        String operation = valueOrDefault(candidate.operation(), "operation");
        String submitUrl = valueOrDefault(candidate.submitUrl(), candidate.resourcePath());
        String suffix = normalize(operation + "-" + submitUrl)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return suffix.isBlank() ? base : base + "-" + suffix;
    }

    private String candidateLabel(AgenticAuthoringCandidate candidate) {
        String path = candidate.resourcePath();
        if (path == null || path.isBlank()) {
            return "Recurso";
        }
        String lastSegment = path.replaceAll("/+$", "");
        int slash = lastSegment.lastIndexOf('/');
        lastSegment = slash >= 0 ? lastSegment.substring(slash + 1) : lastSegment;
        return lastSegment
                .replace("vw-", "")
                .replace("-", " ");
    }

    private String candidateDescription(AgenticAuthoringCandidate candidate) {
        String method = valueOrDefault(candidate.submitMethod(), candidate.operation()).toUpperCase(Locale.ROOT);
        String submitUrl = valueOrDefault(candidate.submitUrl(), candidate.resourcePath());
        return method + " " + submitUrl;
    }

    private String candidateIcon(AgenticAuthoringCandidate candidate) {
        String path = valueOrDefault(candidate.resourcePath(), "").toLowerCase(Locale.ROOT);
        if (path.contains("analytics") || path.contains("/vw-")) {
            return "query_stats";
        }
        return "dataset";
    }

    private String candidateTone(AgenticAuthoringCandidate candidate) {
        String path = valueOrDefault(candidate.resourcePath(), "").toLowerCase(Locale.ROOT);
        if (path.contains("analytics") || path.contains("/vw-")) {
            return "analytics";
        }
        return "resource";
    }

    private String retrievalQuery(AgenticAuthoringResourceCandidatesRequest request) {
        if (request == null) {
            return "";
        }
        if (request.retrievalQuery() != null && !request.retrievalQuery().isBlank()) {
            return request.retrievalQuery().trim();
        }
        return request.userPrompt() == null ? "" : request.userPrompt().trim();
    }

    private String artifactKind(AgenticAuthoringResourceCandidatesRequest request) {
        if (request == null || request.artifactKind() == null || request.artifactKind().isBlank()) {
            return "unknown";
        }
        return request.artifactKind().trim();
    }

    private int limit(AgenticAuthoringResourceCandidatesRequest request) {
        if (request == null || request.limit() == null) {
            return 8;
        }
        return Math.max(1, Math.min(20, request.limit()));
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
