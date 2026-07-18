package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ResourceCapabilitiesFetchResult;
import org.praxisplatform.config.service.ResourceCapabilitiesRetrievalService;

public class AgenticAuthoringResourceDiscoveryService {

    private static final String TOOL_NAME = "searchApiResources";
    private static final int MIN_GROUNDING_WORK_LIMIT = 8;
    private static final int GROUNDING_OVERSAMPLE = 4;

    private final AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog;
    private final ObjectMapper objectMapper;
    private final String domainCatalogServiceKey;
    private final AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer;
    private final AgenticAuthoringConsultativeApiCatalogProjectionService consultativeApiCatalogProjectionService;
    private final ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService;

    public AgenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog,
            ObjectMapper objectMapper) {
        this(candidateCatalog, objectMapper, AgenticAuthoringDomainCatalogHints.DEFAULT_SERVICE_KEY);
    }

    public AgenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog,
            ObjectMapper objectMapper,
            String domainCatalogServiceKey) {
        this(candidateCatalog, objectMapper, domainCatalogServiceKey, null);
    }

    public AgenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog,
            ObjectMapper objectMapper,
            String domainCatalogServiceKey,
            AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer) {
        this(
                candidateCatalog,
                objectMapper,
                domainCatalogServiceKey,
                domainCatalogCandidateEnhancer,
                null,
                null);
    }

    public AgenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog,
            ObjectMapper objectMapper,
            String domainCatalogServiceKey,
            AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer,
            AgenticAuthoringConsultativeApiCatalogProjectionService consultativeApiCatalogProjectionService) {
        this(
                candidateCatalog,
                objectMapper,
                domainCatalogServiceKey,
                domainCatalogCandidateEnhancer,
                consultativeApiCatalogProjectionService,
                null);
    }

    public AgenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog,
            ObjectMapper objectMapper,
            String domainCatalogServiceKey,
            AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer,
            AgenticAuthoringConsultativeApiCatalogProjectionService consultativeApiCatalogProjectionService,
            ResourceCapabilitiesRetrievalService resourceCapabilitiesRetrievalService) {
        this.candidateCatalog = candidateCatalog;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.domainCatalogServiceKey = domainCatalogServiceKey;
        this.domainCatalogCandidateEnhancer = domainCatalogCandidateEnhancer;
        this.consultativeApiCatalogProjectionService = consultativeApiCatalogProjectionService;
        this.resourceCapabilitiesRetrievalService = resourceCapabilitiesRetrievalService;
    }

    public AgenticAuthoringResourceCandidatesResult search(
            AgenticAuthoringResourceCandidatesRequest request) {
        return search(request, null);
    }

    public AgenticAuthoringResourceCandidatesResult search(
            AgenticAuthoringResourceCandidatesRequest request,
            AiPrincipalContext principalContext) {
        long startedAtNanos = System.nanoTime();
        String retrievalQuery = retrievalQuery(request);
        String artifactKind = artifactKind(request);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
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
        int limit = limit(request);
        long catalogStartedAtNanos = System.nanoTime();
        List<AgenticAuthoringCandidate> candidates = candidateCatalog == null
                ? List.of()
                : candidateCatalog.discover(
                        retrievalQuery,
                        artifactKind,
                        principalContext == null ? null : principalContext.tenantId(),
                        principalContext == null ? null : principalContext.environment(),
                        null);
        diagnostics.put("catalogDiscoveryElapsedMs", elapsedMs(catalogStartedAtNanos));
        diagnostics.put("catalogCandidateCount", candidates.size());
        diagnostics.put("preGroundingCandidateCount", candidates.size());
        long groundingStartedAtNanos = System.nanoTime();
        candidates = groundCandidates(
                retrievalQuery,
                candidates,
                principalContext == null ? null : principalContext.tenantId(),
                principalContext == null ? null : principalContext.environment(),
                groundingWorkLimit(candidates, limit),
                diagnostics);
        diagnostics.put("groundingElapsedMs", elapsedMs(groundingStartedAtNanos));
        diagnostics.put("groundedCandidateCount", candidates.size());
        diagnostics.put("domainCatalogGroundedCandidateCount", domainCatalogGroundedCandidateCount(candidates));
        long capabilityGroundingStartedAtNanos = System.nanoTime();
        AnalyticsCapabilityGrounding analyticsCapabilityGrounding = groundAnalyticsCapabilities(
                artifactKind,
                candidates,
                principalContext,
                diagnostics);
        candidates = analyticsCapabilityGrounding.candidates();
        diagnostics.put("analyticsCapabilityGroundingElapsedMs", elapsedMs(capabilityGroundingStartedAtNanos));
        if (analyticsCapabilityGrounding.excludedCount() > 0) {
            warnings.add("analytics-candidates-without-verified-stats-excluded");
        }
        if (analyticsCapabilityGrounding.unavailable()) {
            warnings.add("analytics-capability-grounding-unavailable");
        }
        long consultativeProjectionStartedAtNanos = System.nanoTime();
        AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection = consultativeProjection(
                retrievalQuery,
                artifactKind,
                candidates,
                principalContext == null ? null : principalContext.tenantId(),
                principalContext == null ? null : principalContext.environment());
        diagnostics.put("consultativeProjectionElapsedMs", elapsedMs(consultativeProjectionStartedAtNanos));
        diagnostics.put("consultativeProjectionUsed", consultativeProjection != null && consultativeProjection.hasResources());
        if (consultativeProjection != null && consultativeProjection.hasResources()) {
            warnings.add("domain-api-consultative-projection-used");
            warnings.addAll(consultativeProjection.warnings());
        }
        if (candidates.size() > limit) {
            candidates = candidates.subList(0, limit);
            warnings.add("resource-candidates-limited");
        }
        if (candidates.isEmpty()) {
            warnings.add("resource-candidates-empty");
        }
        long quickRepliesStartedAtNanos = System.nanoTime();
        List<AgenticAuthoringQuickReply> quickReplies = candidateResourceQuickReplies(candidates, artifactKind, retrievalQuery);
        diagnostics.put("quickReplyElapsedMs", elapsedMs(quickRepliesStartedAtNanos));
        diagnostics.put("limitedCandidateCount", candidates.size());
        diagnostics.put("quickReplyCount", quickReplies.size());
        diagnostics.put("totalElapsedMs", elapsedMs(startedAtNanos));
        return new AgenticAuthoringResourceCandidatesResult(
                true,
                TOOL_NAME,
                retrievalQuery,
                artifactKind,
                assistantMessage(candidates, artifactKind, consultativeProjection),
                AgenticAuthoringAssistantContentFactory.fromConsultativeProjection(consultativeProjection),
                List.copyOf(candidates),
                quickReplies,
                List.copyOf(warnings),
                request.resourceSearchFocus(),
                consultativeProjection,
                diagnostics);
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private int domainCatalogGroundedCandidateCount(List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        return (int) candidates.stream()
                .filter(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING))
                .count();
    }

    private AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection(
            String retrievalQuery,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        if (consultativeApiCatalogProjectionService == null
                || !"api_catalog".equals(valueOrDefault(artifactKind, ""))) {
            return null;
        }
        return consultativeApiCatalogProjectionService.project(
                retrievalQuery,
                candidates == null ? List.of() : candidates,
                tenantId,
                environment);
    }

    private List<AgenticAuthoringCandidate> groundCandidates(
            String retrievalQuery,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment,
            int groundingWorkLimit,
            Map<String, Object> diagnostics) {
        if (domainCatalogCandidateEnhancer == null || candidates == null || candidates.isEmpty()) {
            putGroundingWorkDiagnostics(diagnostics, candidates, candidates == null ? 0 : candidates.size());
            return candidates == null ? List.of() : candidates;
        }
        int effectiveLimit = Math.max(1, Math.min(candidates.size(), groundingWorkLimit));
        putGroundingWorkDiagnostics(diagnostics, candidates, effectiveLimit);
        List<AgenticAuthoringCandidate> groundingInput = candidates.subList(0, effectiveLimit);
        List<AgenticAuthoringCandidate> grounded = pruneWeakLexicalCandidatesWhenGrounded(
                domainCatalogCandidateEnhancer.enhance(retrievalQuery, groundingInput, tenantId, environment));
        if (effectiveLimit >= candidates.size()) {
            return grounded;
        }
        List<AgenticAuthoringCandidate> merged = new ArrayList<>(grounded.size() + candidates.size() - effectiveLimit);
        merged.addAll(grounded);
        merged.addAll(candidates.subList(effectiveLimit, candidates.size()));
        return merged;
    }

    private int groundingWorkLimit(List<AgenticAuthoringCandidate> candidates, int resultLimit) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int requestedLimit = Math.max(1, resultLimit);
        int workLimit = Math.max(MIN_GROUNDING_WORK_LIMIT, requestedLimit + GROUNDING_OVERSAMPLE);
        return Math.min(candidates.size(), workLimit);
    }

    private void putGroundingWorkDiagnostics(
            Map<String, Object> diagnostics,
            List<AgenticAuthoringCandidate> candidates,
            int groundingInputCandidateCount) {
        if (diagnostics == null) {
            return;
        }
        int candidateCount = candidates == null ? 0 : candidates.size();
        diagnostics.put("groundingInputCandidateCount", Math.max(0, groundingInputCandidateCount));
        diagnostics.put("groundingSkippedCandidateCount", Math.max(0, candidateCount - groundingInputCandidateCount));
    }

    private List<AgenticAuthoringCandidate> pruneWeakLexicalCandidatesWhenGrounded(
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        boolean hasGroundedCandidate = candidates.stream()
                .anyMatch(candidate -> hasEvidence(
                        candidate,
                        AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING));
        if (!hasGroundedCandidate) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> !isWeakLexicalCandidate(candidate))
                .toList();
    }

    private boolean isWeakLexicalCandidate(AgenticAuthoringCandidate candidate) {
        return !hasEvidence(candidate, "stats-capabilities-verified")
                && (hasEvidence(candidate, "lexical-fallback") || hasEvidence(candidate, "weak-evidence"));
    }

    private AnalyticsCapabilityGrounding groundAnalyticsCapabilities(
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AiPrincipalContext principalContext,
            Map<String, Object> diagnostics) {
        int inputCount = candidates == null ? 0 : candidates.size();
        diagnostics.put("analyticsCapabilityGroundingRequired", requiresVerifiedStats(artifactKind));
        diagnostics.put("analyticsCapabilityInputCandidateCount", inputCount);
        if (!requiresVerifiedStats(artifactKind) || inputCount == 0) {
            diagnostics.put("analyticsCapabilityGroundingAvailable", resourceCapabilitiesRetrievalService != null);
            diagnostics.put("analyticsCapabilityVerifiedCandidateCount", inputCount);
            diagnostics.put("analyticsCapabilityExcludedCandidateCount", 0);
            diagnostics.put("analyticsCapabilityFetchCount", 0);
            return new AnalyticsCapabilityGrounding(candidates == null ? List.of() : candidates, 0, false);
        }
        if (resourceCapabilitiesRetrievalService == null) {
            diagnostics.put("analyticsCapabilityGroundingAvailable", false);
            diagnostics.put("analyticsCapabilityVerifiedCandidateCount", 0);
            diagnostics.put("analyticsCapabilityExcludedCandidateCount", 0);
            diagnostics.put("analyticsCapabilityFetchCount", 0);
            return new AnalyticsCapabilityGrounding(candidates, 0, false);
        }

        diagnostics.put("analyticsCapabilityGroundingAvailable", true);
        Map<String, ResourceCapabilitiesFetchResult> fetches = new HashMap<>();
        List<AgenticAuthoringCandidate> verified = new ArrayList<>();
        int excludedCount = 0;
        for (AgenticAuthoringCandidate candidate : candidates) {
            if (candidate == null || candidate.resourcePath() == null || candidate.resourcePath().isBlank()) {
                excludedCount++;
                continue;
            }
            ResourceCapabilitiesFetchResult result = fetches.computeIfAbsent(
                    candidate.resourcePath(),
                    resourcePath -> resourceCapabilitiesRetrievalService.fetchCapabilitiesResult(
                            resourcePath,
                            null,
                            principalContext == null ? null : principalContext.tenantId(),
                            principalContext == null ? null : principalContext.userId(),
                            principalContext == null ? null : principalContext.environment()));
            if (!hasVerifiedStatsFields(result)) {
                excludedCount++;
                continue;
            }
            verified.add(withVerifiedStatsEvidence(candidate, principalContext));
        }
        diagnostics.put("analyticsCapabilityVerifiedCandidateCount", verified.size());
        diagnostics.put("analyticsCapabilityExcludedCandidateCount", excludedCount);
        diagnostics.put("analyticsCapabilityFetchCount", fetches.size());
        boolean unavailable = !fetches.isEmpty()
                && fetches.values().stream().noneMatch(ResourceCapabilitiesFetchResult::isSuccess);
        return new AnalyticsCapabilityGrounding(List.copyOf(verified), excludedCount, unavailable);
    }

    private boolean requiresVerifiedStats(String artifactKind) {
        return "dashboard".equals(artifactKind) || "chart".equals(artifactKind);
    }

    private boolean hasVerifiedStatsFields(ResourceCapabilitiesFetchResult result) {
        if (result == null || !result.isSuccess()) {
            return false;
        }
        JsonNode root = result.getCapabilities();
        if (root != null && root.path("data").path("stats").isObject()) {
            root = root.path("data");
        }
        JsonNode fields = root == null ? null : root.path("stats").path("fields");
        if (fields == null || !fields.isArray()) {
            return false;
        }
        for (JsonNode field : fields) {
            if (field.path("field").asText("").isBlank()) {
                continue;
            }
            if (field.path("groupByEligible").asBoolean(false)
                    || field.path("timeSeriesEligible").asBoolean(false)
                    || field.path("distributionTermsEligible").asBoolean(false)
                    || field.path("distributionHistogramEligible").asBoolean(false)
                    || field.path("metricFieldEligible").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private AgenticAuthoringCandidate withVerifiedStatsEvidence(
            AgenticAuthoringCandidate candidate,
            AiPrincipalContext principalContext) {
        LinkedHashSet<String> evidence = new LinkedHashSet<>(candidate.evidence() == null
                ? List.of()
                : candidate.evidence());
        evidence.remove("capabilities-probe-pending");
        evidence.add("stats-capabilities-verified");

        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        List<AgenticAuthoringEvidenceBundle.Evidence> items = new ArrayList<>();
        if (bundle != null && bundle.evidence() != null) {
            bundle.evidence().stream()
                    .filter(item -> !("capabilities".equals(item.source())
                            && "capabilities_probe_pending".equals(item.kind())))
                    .forEach(items::add);
        }
        items.add(new AgenticAuthoringEvidenceBundle.Evidence(
                "capabilities",
                "stats_capabilities_verified",
                candidate.resourcePath() + "/capabilities",
                "The canonical resource capability snapshot publishes eligible statistical fields.",
                1d,
                List.of("stats", "fields"),
                principalContext == null ? "" : principalContext.tenantId(),
                principalContext == null ? "" : principalContext.environment(),
                ""));
        AgenticAuthoringEvidenceBundle groundedBundle = AgenticAuthoringEvidenceBundle.of(
                bundle == null ? "capabilities" : bundle.retrievalSource(),
                items);
        return new AgenticAuthoringCandidate(
                candidate.resourcePath(),
                candidate.operation(),
                candidate.schemaUrl(),
                candidate.submitUrl(),
                candidate.submitMethod(),
                candidate.score(),
                candidate.reason(),
                List.copyOf(evidence),
                groundedBundle);
    }

    private record AnalyticsCapabilityGrounding(
            List<AgenticAuthoringCandidate> candidates,
            int excludedCount,
            boolean unavailable) {
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
    }

    private String assistantMessage(
            List<AgenticAuthoringCandidate> candidates,
            String artifactKind,
            AgenticAuthoringConsultativeApiCatalogProjection consultativeProjection) {
        if (consultativeProjection != null && consultativeProjection.hasResources()) {
            return consultativeProjection.assistantMessage();
        }
        if (candidates == null || candidates.isEmpty()) {
            return switch (artifactKind) {
                case "dashboard", "page" -> "Nao encontrei uma API compativel para alimentar este painel. Descreva melhor o dado de negocio, por exemplo area, entidade, indicador ou recorte esperado.";
                case "table" -> "Nao encontrei uma API de colecao compativel para esta tabela. Informe qual entidade ou processo operacional voce quer listar.";
                case "form" -> "Nao encontrei uma API de criacao compativel para este formulario. Informe qual recurso de negocio deve ser cadastrado.";
                default -> "Nao encontrei APIs candidatas para esse pedido. Descreva o dado de negocio que deve alimentar a tela.";
            };
        }
        if ("api_catalog".equals(valueOrDefault(artifactKind, ""))) {
            return "Encontrei APIs relacionadas ao recorte de negocio. Escolha a fonte mais alinhada ou peca para eu detalhar campos, acoes e usos possiveis antes de criar a tela.";
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
            String artifactKind,
            String retrievalQuery) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, List<AgenticAuthoringCandidate>> candidatesByResourcePath = candidates.stream()
                .filter(candidate -> isStrongEnoughForQuickReply(candidate, artifactKind))
                .collect(Collectors.groupingBy(
                        candidate -> valueOrDefault(candidate.resourcePath(), ""),
                        LinkedHashMap::new,
                        Collectors.toList()));
        return candidatesByResourcePath.values().stream()
                .map(resourceCandidates -> {
                    AgenticAuthoringCandidate candidate = resourceCandidates.get(0);
                    ObjectNode contextHints = objectMapper.createObjectNode();
                    contextHints.put("resourcePath", candidate.resourcePath());
                    contextHints.put("submitUrl", candidate.submitUrl());
                    contextHints.put("operation", candidate.operation());
                    contextHints.put("schemaUrl", candidate.schemaUrl());
                    contextHints.put("submitMethod", candidate.submitMethod());
                    contextHints.put("artifactKind", artifactKind);
                    contextHints.set("technicalDetails", technicalDetails(candidate, resourceCandidates));
                    contextHints.set("presentation", candidatePresentation(candidate, artifactKind));
                    AgenticAuthoringDomainCatalogHints.enrich(
                            contextHints,
                            candidate,
                            artifactKind,
                            retrievalQuery,
                            domainCatalogServiceKey);
                    return new AgenticAuthoringQuickReply(
                            quickReplyId(candidate),
                            "suggestion",
                            candidateLabel(candidate),
                            candidatePrompt(candidate, artifactKind),
                            candidateFriendlyDescription(candidate, artifactKind),
                            candidateIcon(candidate),
                            candidateTone(candidate),
                            contextHints);
                })
                .toList();
    }

    private boolean isStrongEnoughForQuickReply(AgenticAuthoringCandidate candidate, String artifactKind) {
        if (candidate == null) {
            return false;
        }
        if (isWeakLexicalCandidate(candidate)) {
            return false;
        }
        if (!"api_catalog".equals(valueOrDefault(artifactKind, ""))) {
            return true;
        }
        return candidate.score() >= 0.50d;
    }

    private String quickReplyId(AgenticAuthoringCandidate candidate) {
        String base = "resource-" + normalize(candidate.resourcePath())
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return base.isBlank() ? "resource-candidate" : base;
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

    private String candidatePrompt(AgenticAuthoringCandidate candidate, String artifactKind) {
        String label = candidateLabel(candidate);
        if ("dashboard".equals(artifactKind) || "page".equals(artifactKind)) {
            return "Usar " + label + " como fonte de dados do painel.";
        }
        if ("table".equals(artifactKind)) {
            return "Usar " + label + " como fonte de dados da tabela.";
        }
        if ("form".equals(artifactKind)) {
            return "Usar " + label + " como operacao de formulario.";
        }
        return "Usar " + label + " como fonte de dados.";
    }

    private String candidateDescription(AgenticAuthoringCandidate candidate, String artifactKind) {
        return switch (artifactKind) {
            case "dashboard", "page" -> "Fonte candidata para alimentar o painel.";
            case "table" -> "Fonte candidata para alimentar a tabela.";
            case "form" -> "Operação candidata para o formulário.";
            default -> "Fonte candidata encontrada no catálogo.";
        };
    }

    private String candidateFriendlyDescription(AgenticAuthoringCandidate candidate, String artifactKind) {
        if ("dashboard".equals(artifactKind) || "page".equals(artifactKind)) {
            return "Indicada para montar um painel. Retorna dados que podem virar listas, indicadores e gráficos quando o schema confirmar os recortes.";
        }
        if ("table".equals(artifactKind)) {
            return "Indicada quando você quer uma lista navegável. Retorna registros para tabela, filtros e detalhes.";
        }
        if ("form".equals(artifactKind)) {
            return "Indicada quando você precisa cadastrar ou atualizar dados. Retorna uma operação governada para o formulário.";
        }
        return "Opção encontrada no catálogo semântico. Use para explorar quais dados ela oferece antes de criar a tela.";
    }

    private ObjectNode candidatePresentation(AgenticAuthoringCandidate candidate, String artifactKind) {
        ObjectNode presentation = objectMapper.createObjectNode();
        if ("dashboard".equals(artifactKind) || "page".equals(artifactKind)) {
            presentation.put("bestFor", "Boa para painéis com acompanhamento de registros, gráficos e drill-down quando o schema confirmar os recortes.");
            presentation.put("returns", "Retorna dados de negócio que podem alimentar cards, listas e gráficos materializados por schema.");
            presentation.put("nextStep", "Clique para usar esta fonte como recorte inicial da pré-visualização.");
            return presentation;
        }
        if ("table".equals(artifactKind)) {
            presentation.put("bestFor", "Boa para consultar, filtrar e comparar registros em uma lista.");
            presentation.put("returns", "Retorna coleções navegáveis com campos para colunas, busca e detalhes.");
            presentation.put("nextStep", "Clique para criar a tabela usando esta fonte.");
            return presentation;
        }
        if ("form".equals(artifactKind)) {
            presentation.put("bestFor", "Boa para capturar ou atualizar informações com governança.");
            presentation.put("returns", "Retorna a operação que o formulário deve executar ao salvar.");
            presentation.put("nextStep", "Clique para usar esta operação no formulário.");
            return presentation;
        }
        presentation.put("bestFor", "Boa para explorar uma fonte semântica disponível no catálogo.");
        presentation.put("returns", "Retorna dados ou operações que podem ser materializados em componentes.");
        presentation.put("nextStep", "Clique para investigar esta opção no próximo passo.");
        return presentation;
    }

    private ObjectNode technicalDetails(
            AgenticAuthoringCandidate candidate,
            List<AgenticAuthoringCandidate> resourceCandidates) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("resourcePath", valueOrDefault(candidate.resourcePath(), ""));
        details.put("submitUrl", valueOrDefault(candidate.submitUrl(), candidate.resourcePath()));
        details.put("submitMethod", valueOrDefault(candidate.submitMethod(), candidate.operation()).toUpperCase(Locale.ROOT));
        details.put("operation", valueOrDefault(candidate.operation(), ""));
        details.put("schemaUrl", valueOrDefault(candidate.schemaUrl(), ""));
        ArrayNode relatedOperations = details.putArray("relatedOperations");
        List<AgenticAuthoringCandidate> related = resourceCandidates == null
                ? List.of(candidate)
                : resourceCandidates;
        related.stream()
                .filter(relatedCandidate -> relatedCandidate != null)
                .forEach(relatedCandidate -> {
                    ObjectNode operation = relatedOperations.addObject();
                    operation.put("submitUrl", valueOrDefault(relatedCandidate.submitUrl(), relatedCandidate.resourcePath()));
                    operation.put("submitMethod", valueOrDefault(relatedCandidate.submitMethod(), relatedCandidate.operation()).toUpperCase(Locale.ROOT));
                    operation.put("operation", valueOrDefault(relatedCandidate.operation(), ""));
                    operation.put("schemaUrl", valueOrDefault(relatedCandidate.schemaUrl(), ""));
                    operation.put("score", relatedCandidate.score());
                });
        return details;
    }

    private String candidateIcon(AgenticAuthoringCandidate candidate) {
        return "dataset";
    }

    private String candidateTone(AgenticAuthoringCandidate candidate) {
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
