package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.config.dto.ApiSearchResult;
import org.praxisplatform.config.domain.ApiMetadata;
import org.praxisplatform.config.repository.ApiMetadataRepository;
import org.praxisplatform.config.service.ContextRetrievalService;

public class AgenticAuthoringApiMetadataCandidateCatalog {

    private static final int CANDIDATE_LIMIT = 16;
    private static final double MIN_STRONG_SEMANTIC_SCORE = 0.52d;
    private static final int MIN_GENERIC_OPERATIONAL_SEMANTIC_CANDIDATES = 6;
    private static final String SEMANTIC_ROLE_OPERATIONAL_RESOURCE = "semantic-role:operational-resource";
    private static final String SEMANTIC_ROLE_ANALYTICS_PROJECTION = "semantic-role:analytics-projection";
    private static final String SEMANTIC_ROLE_PROFILE_PROJECTION = "semantic-role:profile-projection";

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "as", "o", "os", "um", "uma", "de", "da", "das", "do", "dos", "para", "por",
            "com", "em", "no", "na", "nos", "nas", "crie", "criar", "gere", "gerar", "monte",
            "montar", "quero", "usar", "use", "visualizar", "ver", "mostre", "mostrar", "tabela",
            "lista", "listagem", "dashboard", "dashboards", "painel", "formulario", "form", "grafico", "chart",
            "graficos", "indicador", "indicadores", "kpi", "kpis", "metrica", "metricas",
            "qual", "quais", "outra", "outras", "opcao", "opcoes", "voce", "antes", "indica", "indicar",
            "indique", "alternativa", "alternativas", "compare", "comparar", "recomende", "recomendar",
            "regra", "regras", "poder", "posso", "pode", "podem", "ser");

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
        List<AgenticAuthoringCandidate> llmFocusedCandidates = discoverLlmAuthoredResourceFocusCandidates(context);
        if (!llmFocusedCandidates.isEmpty()) {
            return mergeCandidates(llmFocusedCandidates, List.of(), artifactKind, normalizedPrompt);
        }
        List<AgenticAuthoringCandidate> semanticCandidates = new SemanticCandidateRetriever().retrieve(context);
        if (repository == null) {
            return semanticCandidates;
        }
        boolean explicitSourceReference = hasExplicitSourceReference(normalizedPrompt);
        boolean supplementSemanticRetrieval = shouldSupplementSemanticRetrieval(
                normalizedPrompt,
                artifactKind,
                semanticCandidates);
        if (!semanticCandidates.isEmpty()
                && !explicitSourceReference
                && !supplementSemanticRetrieval) {
            return mergeCandidates(semanticCandidates, List.of(), artifactKind, normalizedPrompt);
        }
        List<String> originalTokens = meaningfulTokens(normalizedPrompt);
        if (originalTokens.isEmpty()) {
            return mergeCandidates(
                    semanticCandidates,
                    new BroadArtifactCandidateRetriever().retrieve(context),
                    artifactKind,
                    normalizedPrompt);
        }
        List<String> tokens = meaningfulTokens(normalizedPrompt);
        List<AgenticAuthoringCandidate> lexicalCandidates =
                new LexicalFallbackCandidateRetriever().retrieve(context.withTokens(tokens));
        List<AgenticAuthoringCandidate> explicitSourceCandidates = lexicalCandidates.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match"))
                .toList();
        if (!"api_catalog".equals(artifactKind) && !explicitSourceCandidates.isEmpty()) {
            return mergeCandidates(explicitSourceCandidates, semanticCandidates, artifactKind, normalizedPrompt);
        }
        List<AgenticAuthoringCandidate> supplementaryCandidates = new ArrayList<>(lexicalCandidates);
        if (shouldSupplementWithBroadOperationalDiscovery(
                normalizedPrompt,
                artifactKind,
                semanticCandidates,
                supplementSemanticRetrieval)) {
            supplementaryCandidates.addAll(new BroadArtifactCandidateRetriever().retrieve(context));
        }
        List<AgenticAuthoringCandidate> mergedCandidates =
                mergeCandidates(supplementaryCandidates, semanticCandidates, artifactKind, normalizedPrompt);
        return mergedCandidates;
    }

    private boolean hasScope(String tenantId, String environment) {
        return (tenantId != null && !tenantId.isBlank())
                || (environment != null && !environment.isBlank());
    }

    private boolean shouldSupplementSemanticRetrieval(
            String normalizedPrompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> semanticCandidates) {
        if (semanticCandidates == null || semanticCandidates.isEmpty()) {
            return true;
        }
        if (!strongSemanticRetrieval(semanticCandidates)) {
            return true;
        }
        if ("api_catalog".equals(artifactKind)) {
            return true;
        }
        if (isGenericOperationalBroadSurface(normalizedPrompt, artifactKind)
                && semanticCandidates.size() < MIN_GENERIC_OPERATIONAL_SEMANTIC_CANDIDATES) {
            return !hasOperationalSemanticResourceCandidate(semanticCandidates)
                    || hasUnmatchedEnumeratedBusinessScope(normalizedPrompt, semanticCandidates);
        }
        return ("dashboard".equals(artifactKind) || "chart".equals(artifactKind))
                && containsAny(normalize(normalizedPrompt), "grafico", "graficos", "chart", "charts", "barras", "linha", "pizza");
    }

    private boolean shouldSupplementWithBroadOperationalDiscovery(
            String normalizedPrompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> semanticCandidates,
            boolean supplementSemanticRetrieval) {
        return supplementSemanticRetrieval
                && semanticCandidates != null
                && !semanticCandidates.isEmpty()
                && isGenericOperationalBroadSurface(normalizedPrompt, artifactKind);
    }

    private boolean hasOperationalSemanticResourceCandidate(List<AgenticAuthoringCandidate> semanticCandidates) {
        return semanticCandidates != null
                && semanticCandidates.stream()
                .anyMatch(candidate -> hasEvidence(candidate, "semantic-retrieval")
                        && hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE));
    }

    private boolean hasUnmatchedEnumeratedBusinessScope(
            String normalizedPrompt,
            List<AgenticAuthoringCandidate> semanticCandidates) {
        List<String> scopeTokens = enumeratedBusinessScopeTokens(normalizedPrompt);
        if (scopeTokens.isEmpty()) {
            return false;
        }
        return scopeTokens.stream()
                .noneMatch(token -> semanticCandidates.stream()
                        .anyMatch(candidate -> candidateMatchesBusinessToken(candidate, token)));
    }

    private List<String> enumeratedBusinessScopeTokens(String normalizedPrompt) {
        String normalized = normalize(valueOrEmpty(normalizedPrompt));
        int scopeStart = firstPositiveIndex(
                normalized.indexOf(" incluindo "),
                normalized.indexOf(" inclui "),
                normalized.indexOf(" dados de "),
                normalized.indexOf(" relacionado a "),
                normalized.indexOf(" relacionados a "));
        if (scopeStart < 0) {
            return List.of();
        }
        return meaningfulTokens(normalized.substring(scopeStart)).stream()
                .filter(token -> !isPresentationOrGenericScopeToken(token))
                .limit(8)
                .toList();
    }

    private int firstPositiveIndex(int... indexes) {
        int selected = -1;
        for (int index : indexes) {
            if (index >= 0 && (selected < 0 || index < selected)) {
                selected = index;
            }
        }
        return selected;
    }

    private boolean isPresentationOrGenericScopeToken(String token) {
        return containsWord(new String[] {
                "incluindo", "inclui", "dados", "informacao", "informacoes", "visao", "geral",
                "detalhe", "detalhes", "individual", "individuais", "area", "areas", "contexto",
                "operacional", "operacionais", "atual", "atuais"
        }, token);
    }

    private boolean candidateMatchesBusinessToken(AgenticAuthoringCandidate candidate, String token) {
        if (candidate == null || token == null || token.isBlank()) {
            return false;
        }
        String candidateIdentity = normalize(String.join(" ",
                valueOrEmpty(candidate.resourcePath()),
                valueOrEmpty(candidate.submitUrl()),
                valueOrEmpty(candidate.reason())));
        if (matchesToken(candidateIdentity, token)) {
            return true;
        }
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        if (bundle == null || bundle.evidence() == null) {
            return false;
        }
        return bundle.evidence().stream()
                .flatMap(evidence -> evidence.matchedTerms().stream())
                .anyMatch(term -> matchesToken(normalize(term), token));
    }

    private boolean isGenericOperationalBroadSurface(String normalizedPrompt, String artifactKind) {
        return semanticResourceNeed(normalizedPrompt, artifactKind) == SemanticResourceNeed.GENERIC_OPERATIONAL
                && ("page".equals(artifactKind) || "table".equals(artifactKind) || "unknown".equals(artifactKind));
    }

    private List<AgenticAuthoringCandidate> discoverLlmAuthoredResourceFocusCandidates(RetrievalContext context) {
        if (repository == null
                || "api_catalog".equals(context.artifactKind())
                || semanticResourceNeed(context.normalizedPrompt(), context.artifactKind())
                != SemanticResourceNeed.GENERIC_OPERATIONAL) {
            return List.of();
        }
        String resourceFocus = canonicalLlmResourceFocus(context.normalizedPrompt());
        if (resourceFocus.isBlank()) {
            return List.of();
        }
        List<ApiMetadata> focusedMetadata = findLlmFocusedMetadata(resourceFocus, context);
        return focusedMetadata.stream()
                .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                .filter(metadata -> isRenderableBusinessEndpoint(metadata.getPath()))
                .filter(metadata -> context.expectedMethod() == null
                        || context.expectedMethod().equalsIgnoreCase(metadata.getMethod()))
                .filter(metadata -> resourceFocus.equals(resourceKey(baseResourcePath(metadata.getPath()))))
                .map(metadata -> toLlmFocusedCandidate(metadata, context, resourceFocus))
                .sorted(CandidateRankingPolicy.byScoreDescending())
                .limit(CANDIDATE_LIMIT)
                .map(ScoredCandidate::candidate)
                .toList();
    }

    private List<ApiMetadata> findLlmFocusedMetadata(String resourceFocus, RetrievalContext context) {
        String basePath = resourceFocusToApiBasePath(resourceFocus);
        if (basePath.isBlank()) {
            return List.of();
        }
        List<ApiMetadata> exactMatches = exactLlmFocusedMetadataMatches(basePath, context);
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }
        return structuredMetadata(context).stream()
                .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                .filter(metadata -> resourceFocus.equals(resourceKey(baseResourcePath(metadata.getPath()))))
                .toList();
    }

    private List<ApiMetadata> exactLlmFocusedMetadataMatches(String basePath, RetrievalContext context) {
        List<String> methods = context.expectedMethod() == null || context.expectedMethod().isBlank()
                ? List.of("POST", "GET")
                : List.of(context.expectedMethod().toUpperCase(Locale.ROOT));
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.add(basePath + "/filter/cursor");
        paths.add(basePath + "/filter");
        paths.add(basePath);
        List<ApiMetadata> matches = new ArrayList<>();
        if (!hasScope(context.tenantId(), context.environment())) {
            List<ApiMetadata> metadata = structuredMetadata(context);
            for (String method : methods) {
                for (String path : paths) {
                    metadata.stream()
                            .filter(candidate -> path.equals(candidate.getPath()))
                            .filter(candidate -> method.equalsIgnoreCase(candidate.getMethod()))
                            .findFirst()
                            .ifPresent(matches::add);
                }
            }
            return matches;
        }
        for (String method : methods) {
            for (String path : paths) {
                Optional<ApiMetadata> metadata = findStructuredMetadataByPathAndMethod(context, path, method);
                metadata.ifPresent(matches::add);
            }
        }
        return matches;
    }

    private String resourceFocusToApiBasePath(String resourceFocus) {
        String normalized = canonicalResourceFocus(resourceFocus);
        if (normalized.isBlank()) {
            return "";
        }
        return "/api/" + normalized.replace('.', '/');
    }

    private String canonicalLlmResourceFocus(String normalizedPrompt) {
        String primaryBusinessEntity = semanticQuerySection(
                normalize(valueOrEmpty(normalizedPrompt)),
                "primary business entity:",
                "supporting concepts:");
        return canonicalResourceFocus(primaryBusinessEntity);
    }

    private String canonicalResourceFocus(String value) {
        String normalized = normalizePath(normalize(valueOrEmpty(value)))
                .replaceAll("[\\s()\\[\\]{}]+", "")
                .replace('/', '.')
                .replaceAll("^\\.+|\\.+$", "");
        if (normalized.startsWith("api.")) {
            normalized = normalized.substring(4);
        }
        normalized = normalized.replaceAll("\\.+", ".");
        if (!normalized.contains(".") || normalized.length() < 3) {
            return "";
        }
        return normalized;
    }


    private boolean hasExplicitSourceReference(String normalizedPrompt) {
        return !explicitPhraseTerms(normalizedPrompt, "fonte", "source", "recurso").isEmpty();
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
    }

    private boolean strongSemanticRetrieval(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && candidates.stream()
                .filter(candidate -> candidate != null && candidate.evidence().contains("semantic-retrieval"))
                .mapToDouble(AgenticAuthoringCandidate::score)
                .max()
                .orElse(0d) >= MIN_STRONG_SEMANTIC_SCORE;
    }

    private List<AgenticAuthoringCandidate> mergeCandidates(
            List<AgenticAuthoringCandidate> primary,
            List<AgenticAuthoringCandidate> secondary,
            String artifactKind,
            String normalizedPrompt) {
        Map<String, AgenticAuthoringCandidate> candidatesByResource = new LinkedHashMap<>();
        for (AgenticAuthoringCandidate candidate : concat(primary, secondary)) {
            if (candidate == null || candidate.resourcePath() == null || candidate.resourcePath().isBlank()) {
                continue;
            }
            candidatesByResource.merge(
                    candidate.resourcePath(),
                    candidate,
                    this::preferredCandidateForSameResource);
        }
        SemanticResourceNeed resourceNeed = semanticResourceNeed(normalizedPrompt, artifactKind);
        Comparator<AgenticAuthoringCandidate> ranking = "api_catalog".equals(artifactKind)
                ? Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed()
                : CandidateRankingPolicy.byEvidenceStrengthRoleFitThenScore(resourceNeed);
        return candidatesByResource.values().stream()
                .sorted(ranking)
                .limit(CANDIDATE_LIMIT)
                .toList();
    }

    private AgenticAuthoringCandidate preferredCandidateForSameResource(
            AgenticAuthoringCandidate existing,
            AgenticAuthoringCandidate replacement) {
        int existingEvidenceStrength = evidenceStrength(existing);
        int replacementEvidenceStrength = evidenceStrength(replacement);
        if (replacementEvidenceStrength != existingEvidenceStrength) {
            return replacementEvidenceStrength > existingEvidenceStrength ? replacement : existing;
        }
        boolean existingCreate = isCreateEndpointCandidate(existing);
        boolean replacementCreate = isCreateEndpointCandidate(replacement);
        if (replacementCreate != existingCreate) {
            return replacementCreate ? replacement : existing;
        }
        return replacement.score() > existing.score() ? replacement : existing;
    }

    private int evidenceStrength(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidence() == null) {
            return 0;
        }
        if (hasEvidence(candidate, "semantic-retrieval")
                || hasEvidence(candidate, "explicit-source-match")
                || hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)) {
            return 3;
        }
        if (hasEvidence(candidate, "broad-artifact-discovery")) {
            return 1;
        }
        if (hasEvidence(candidate, "lexical-fallback") || hasEvidence(candidate, "weak-evidence")) {
            return 0;
        }
        return 2;
    }

    private boolean isCreateEndpointCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String operation = valueOrEmpty(candidate.operation());
        String submitMethod = valueOrEmpty(candidate.submitMethod());
        String resourcePath = normalizePath(candidate.resourcePath());
        String submitUrl = normalizePath(candidate.submitUrl());
        return "post".equalsIgnoreCase(operation)
                && "post".equalsIgnoreCase(submitMethod)
                && !resourcePath.isBlank()
                && resourcePath.equals(submitUrl)
                && !isKnownCollectionOperation(submitUrl);
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

    private List<AgenticAuthoringCandidate> discoverBroadCandidates(
            String artifactKind,
            String expectedMethod,
            String normalizedPrompt,
            RetrievalContext context) {
        if (!isBroadDiscoveryArtifact(artifactKind)) {
            return List.of();
        }
        return structuredMetadata(context).stream()
                .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                .filter(metadata -> isRenderableBusinessEndpoint(metadata.getPath()))
                .filter(metadata -> expectedMethod == null || expectedMethod.equalsIgnoreCase(metadata.getMethod()))
                .map(metadata -> toBroadScoredCandidate(
                        metadata,
                        expectedMethod,
                        artifactKind,
                        normalizedPrompt,
                        context.tenantId(),
                        context.environment(),
                        context.releaseId()))
                .filter(scored -> scored.score() >= 0.36d)
                .sorted(CandidateRankingPolicy.byScoreDescending())
                .limit(CANDIDATE_LIMIT)
                .map(ScoredCandidate::candidate)
                .toList();
    }

    private boolean isBroadDiscoveryArtifact(String artifactKind) {
        return "page".equals(artifactKind)
                || "dashboard".equals(artifactKind)
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
                            CANDIDATE_LIMIT,
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
            return structuredMetadata(context).stream()
                    .filter(metadata -> metadata.getPath() != null && metadata.getMethod() != null)
                    .filter(metadata -> isRenderableBusinessEndpoint(metadata.getPath()))
                    .filter(metadata -> context.expectedMethod() == null
                            || context.expectedMethod().equalsIgnoreCase(metadata.getMethod()))
                    .map(metadata -> toScoredCandidate(
                            metadata,
                            context.expectedMethod(),
                            context.artifactKind(),
                            context.normalizedPrompt(),
                            context.tokens(),
                            context.tenantId(),
                            context.environment(),
                            context.releaseId()))
                    .filter(scored -> scored.score() >= 0.45d)
                    .sorted(CandidateRankingPolicy.byScoreDescending())
                    .limit(CANDIDATE_LIMIT)
                    .map(ScoredCandidate::candidate)
                    .toList();
        }
    }

    private final class BroadArtifactCandidateRetriever implements CandidateRetriever {

        @Override
        public List<AgenticAuthoringCandidate> retrieve(RetrievalContext context) {
            return discoverBroadCandidates(
                    context.artifactKind(),
                    context.expectedMethod(),
                    context.normalizedPrompt(),
                    context);
        }
    }

    private List<ApiMetadata> structuredMetadata(RetrievalContext context) {
        if (context != null && hasScope(context.tenantId(), context.environment())) {
            return repository.findAllByTenantIdAndEnvironmentAndServiceKeyAndReleaseId(
                    normalizeOrDefault(context.tenantId(), "GLOBAL"),
                    normalizeOrDefault(context.environment(), "default"),
                    "default",
                    normalizeOrDefault(context.releaseId(), "v1"));
        }
        return repository.findAll();
    }

    private Optional<ApiMetadata> findStructuredMetadataByPathAndMethod(
            RetrievalContext context,
            String path,
            String method) {
        if (context != null && hasScope(context.tenantId(), context.environment())) {
            return repository.findByTenantIdAndEnvironmentAndServiceKeyAndReleaseIdAndPathAndMethod(
                    normalizeOrDefault(context.tenantId(), "GLOBAL"),
                    normalizeOrDefault(context.environment(), "default"),
                    "default",
                    normalizeOrDefault(context.releaseId(), "v1"),
                    path,
                    method);
        }
        return repository.findAll().stream()
                .filter(metadata -> path.equals(metadata.getPath()))
                .filter(metadata -> method.equalsIgnoreCase(metadata.getMethod()))
                .findFirst();
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static final class CandidateRankingPolicy {

        private CandidateRankingPolicy() {
        }

        static Comparator<ScoredCandidate> byScoreDescending() {
            return Comparator.comparingDouble(ScoredCandidate::score).reversed();
        }

        static Comparator<AgenticAuthoringCandidate> byEvidenceStrengthRoleFitThenScore(SemanticResourceNeed resourceNeed) {
            return Comparator.<AgenticAuthoringCandidate>comparingInt(AgenticAuthoringApiMetadataCandidateCatalog::staticEvidenceStrength)
                    .reversed()
                    .thenComparing(Comparator.comparingInt(
                            (AgenticAuthoringCandidate candidate) -> hasStaticEvidence(candidate, "tool-search-api-resources") ? 1 : 0)
                            .reversed())
                    .thenComparing(Comparator.comparingDouble(
                            (AgenticAuthoringCandidate candidate) -> semanticRoleFit(candidate, resourceNeed)).reversed())
                    .thenComparing(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed());
        }
    }

    private static boolean hasStaticEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
    }

    private static double semanticRoleFit(AgenticAuthoringCandidate candidate, SemanticResourceNeed resourceNeed) {
        if (candidate == null || candidate.evidence() == null || resourceNeed == null) {
            return 0d;
        }
        boolean derivedProjection = isDerivedProjectionCandidate(candidate);
        return switch (resourceNeed) {
            case ANALYTICS -> candidate.evidence().contains(SEMANTIC_ROLE_ANALYTICS_PROJECTION) ? 0.20d
                    : candidate.evidence().contains(SEMANTIC_ROLE_OPERATIONAL_RESOURCE) ? 0.04d : 0d;
            case PROFILE -> candidate.evidence().contains(SEMANTIC_ROLE_PROFILE_PROJECTION) ? 0.20d
                    : candidate.evidence().contains(SEMANTIC_ROLE_OPERATIONAL_RESOURCE) ? 0.08d : 0d;
            case GENERIC_OPERATIONAL -> candidate.evidence().contains(SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                    ? (derivedProjection ? 0.04d : 0.18d)
                    : candidate.evidence().contains(SEMANTIC_ROLE_PROFILE_PROJECTION) ? 0.06d : 0d;
        };
    }

    private static boolean isDerivedProjectionCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return isDerivedProjectionPath(candidate.resourcePath()) || isDerivedProjectionPath(candidate.submitUrl());
    }

    private static int staticEvidenceStrength(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidence() == null) {
            return 0;
        }
        List<String> evidence = candidate.evidence();
        if (evidence.contains("semantic-retrieval")
                || evidence.contains("explicit-source-match")
                || evidence.contains(AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)) {
            return 3;
        }
        if (evidence.contains("broad-artifact-discovery")) {
            return 1;
        }
        if (evidence.contains("lexical-fallback") || evidence.contains("weak-evidence")) {
            return 0;
        }
        return 2;
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
        String evidenceText = String.join(" ",
                valueOrEmpty(result.getPath()),
                valueOrEmpty(result.getTags()),
                valueOrEmpty(result.getSummary()),
                valueOrEmpty(result.getRequestSchema()),
                valueOrEmpty(result.getResponseSchema()),
                valueOrEmpty(result.getParameters()));
        String normalizedEvidenceText = normalize(evidenceText);
        ResourceSemanticRole semanticRole = semanticRole(resourcePath, submitUrl, normalizedEvidenceText, "");
        score += semanticRoleScoreAdjustment(semanticRole, semanticResourceNeed(normalizedPrompt, artifactKind));
        score += derivedProjectionScoreAdjustment(resourcePath, submitUrl, normalizedPrompt, artifactKind);
        score = Math.max(0.45d, Math.min(0.99d, score));
        List<String> evidence = new ArrayList<>(List.of(
                "api-metadata", "semantic-retrieval", "schema-available", "actions-probe-pending"));
        evidence.add(semanticRole.evidence);
        return new AgenticAuthoringCandidate(
                resourcePath,
                submitMethod,
                schemaUrl(submitUrl, submitMethod),
                submitUrl,
                submitMethod,
                score,
                "api_metadata semantic retrieval",
                List.copyOf(evidence),
                evidenceBundle(
                        "semantic_retrieval",
                        resourcePath,
                        submitUrl,
                        submitMethod,
                        valueOrEmpty(result.getSummary()),
                        score,
                        meaningfulTokens(normalizedEvidenceText),
                        tenantId,
                        environment,
                        releaseId,
                        false));
    }

    private ScoredCandidate toScoredCandidate(
            ApiMetadata metadata,
            String expectedMethod,
            String artifactKind,
            String normalizedPrompt,
            List<String> tokens,
            String tenantId,
            String environment,
            String releaseId) {
        String endpointText = searchableText(metadata);
        String sourceIdentityText = sourceIdentityText(metadata);
        String path = normalize(metadata.getPath());
        List<String> explicitSourceTerms = explicitPhraseTerms(normalizedPrompt, "fonte", "source", "recurso");
        List<String> explicitFieldTerms = explicitPhraseTerms(normalizedPrompt, "campo", "field", "coluna", "eixo");
        boolean explicitSourceMatch = !explicitSourceTerms.isEmpty()
                && explicitSourceTerms.stream().allMatch(token -> matchesToken(sourceIdentityText, token));
        boolean explicitFieldMatch = !explicitFieldTerms.isEmpty()
                && explicitFieldTerms.stream().allMatch(token -> matchesToken(endpointText, token));
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
        if (explicitSourceMatch) {
            score = Math.max(score, 0.86d);
        }
        if (explicitFieldMatch) {
            score += explicitSourceMatch ? 0.04d : 0.07d;
        }
        score = Math.max(0d, Math.min(0.99d, score));
        String operation = metadata.getMethod().toLowerCase(Locale.ROOT);
        String submitUrl = canonicalSubmitUrl(metadata.getPath(), operation, artifactKind);
        String submitMethod = canonicalSubmitMethod(submitUrl, operation);
        String resourcePath = baseResourcePath(metadata.getPath());
        ResourceSemanticRole semanticRole = semanticRole(
                resourcePath,
                submitUrl,
                endpointText,
                valueOrEmpty(metadata.getRawJson()));
        boolean explicitMetadataMatch = explicitSourceMatch;
        if (explicitMetadataMatch) {
            score += semanticRoleScoreAdjustment(semanticRole, semanticResourceNeed(normalizedPrompt, artifactKind));
            score += derivedProjectionScoreAdjustment(resourcePath, submitUrl, normalizedPrompt, artifactKind);
            score = Math.max(0d, Math.min(0.99d, score));
        }
        List<String> evidence = explicitMetadataMatch
                ? new ArrayList<>(explicitMetadataEvidence(explicitFieldMatch))
                : new ArrayList<>(List.of("api-metadata", "lexical-fallback", "weak-evidence",
                        "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending"));
        evidence.add(semanticRole.evidence);
        List<String> evidenceTerms = explicitMetadataMatch
                ? mergeTerms(explicitSourceTerms, explicitFieldTerms, tokens)
                : tokens;
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                resourcePath,
                submitMethod,
                schemaUrl(submitUrl, submitMethod),
                submitUrl,
                submitMethod,
                score,
                explicitMetadataMatch
                        ? "api_metadata explicit source evidence"
                        : "api_metadata weak lexical fallback evidence",
                List.copyOf(evidence),
                evidenceBundle(
                        explicitMetadataMatch ? "explicit_source_match" : "lexical_fallback",
                        resourcePath,
                        submitUrl,
                        submitMethod,
                        compactReasonText(searchableText(metadata)),
                        explicitMetadataMatch ? Math.max(0.78d, Math.min(score, 0.92d)) : Math.min(score, 0.49d),
                        evidenceTerms,
                        tenantId,
                        environment,
                        releaseId,
                        !explicitMetadataMatch)),
                score);
    }

    private ScoredCandidate toBroadScoredCandidate(
            ApiMetadata metadata,
            String expectedMethod,
            String artifactKind,
            String normalizedPrompt,
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
        ResourceSemanticRole semanticRole = semanticRole(
                resourcePath,
                submitUrl,
                endpointText,
                valueOrEmpty(metadata.getRawJson()));
        score += semanticRoleScoreAdjustment(semanticRole, semanticResourceNeed(normalizedPrompt, artifactKind));
        score += derivedProjectionScoreAdjustment(resourcePath, submitUrl, normalizedPrompt, artifactKind);
        score = Math.max(0d, Math.min(0.90d, score));
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                resourcePath,
                submitMethod,
                schemaUrl(submitUrl, submitMethod),
                submitUrl,
                submitMethod,
                score,
                broadDiscoveryReason(metadata),
                List.of("api-metadata", "broad-artifact-discovery", "schema-probe-pending",
                        "actions-probe-pending", semanticRole.evidence),
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

    private ScoredCandidate toLlmFocusedCandidate(
            ApiMetadata metadata,
            RetrievalContext context,
            String resourceFocus) {
        String endpointText = searchableText(metadata);
        String operation = metadata.getMethod().toLowerCase(Locale.ROOT);
        String submitUrl = canonicalSubmitUrl(metadata.getPath(), operation, context.artifactKind());
        String submitMethod = canonicalSubmitMethod(submitUrl, operation);
        String resourcePath = baseResourcePath(metadata.getPath());
        ResourceSemanticRole semanticRole = semanticRole(
                resourcePath,
                submitUrl,
                endpointText,
                valueOrEmpty(metadata.getRawJson()));
        double score = 0.86d + semanticRoleScoreAdjustment(
                semanticRole,
                semanticResourceNeed(context.normalizedPrompt(), context.artifactKind()));
        score += derivedProjectionScoreAdjustment(
                resourcePath,
                submitUrl,
                context.normalizedPrompt(),
                context.artifactKind());
        score = Math.max(0.45d, Math.min(0.99d, score));
        List<String> evidence = new ArrayList<>(List.of(
                "api-metadata",
                "semantic-retrieval",
                "llm-resource-focus",
                "schema-available",
                "actions-probe-pending"));
        evidence.add(semanticRole.evidence);
        return new ScoredCandidate(new AgenticAuthoringCandidate(
                resourcePath,
                submitMethod,
                schemaUrl(submitUrl, submitMethod),
                submitUrl,
                submitMethod,
                score,
                "api_metadata llm-authored resource focus",
                List.copyOf(evidence),
                evidenceBundle(
                        "semantic_retrieval",
                        resourcePath,
                        submitUrl,
                        submitMethod,
                        "LLM-authored governed resource focus: " + resourceFocus,
                        score,
                        domainTerms(resourcePath),
                        context.tenantId(),
                        context.environment(),
                        context.releaseId(),
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

    private String resourceKey(String resourcePath) {
        return canonicalResourceFocus(domainCatalogRef(resourcePath));
    }

    private List<String> domainTerms(String resourcePath) {
        return meaningfulTokens(normalize(domainCatalogRef(resourcePath).replace('.', ' ')));
    }

    private String compactReasonText(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 900) {
            return normalized;
        }
        return normalized.substring(0, 900).trim();
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

    private String sourceIdentityText(ApiMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        return normalize(String.join(" ",
                valueOrEmpty(metadata.getPath()),
                valueOrEmpty(metadata.getTags()),
                valueOrEmpty(metadata.getSummary()),
                valueOrEmpty(metadata.getDescription()),
                valueOrEmpty(metadata.getOperationId())));
    }

    private ResourceSemanticRole semanticRole(
            String resourcePath,
            String submitUrl,
            String endpointText,
            String rawJson) {
        String normalizedResource = normalizePath(resourcePath).toLowerCase(Locale.ROOT);
        String normalizedSubmit = normalizePath(submitUrl).toLowerCase(Locale.ROOT);
        String normalizedEndpointText = normalize(valueOrEmpty(endpointText));
        String normalizedRawJson = normalize(valueOrEmpty(rawJson));
        if (hasAnalyticsSignal(normalizedResource, normalizedSubmit, normalizedEndpointText, normalizedRawJson)) {
            return ResourceSemanticRole.ANALYTICS_PROJECTION;
        }
        if (hasProfileSignal(normalizedResource, normalizedSubmit, normalizedEndpointText, normalizedRawJson)) {
            return ResourceSemanticRole.PROFILE_PROJECTION;
        }
        return ResourceSemanticRole.OPERATIONAL_RESOURCE;
    }

    private boolean hasAnalyticsSignal(
            String resourcePath,
            String submitUrl,
            String endpointText,
            String rawJson) {
        boolean structuralProjection = isDerivedProjectionPath(resourcePath) || isDerivedProjectionPath(submitUrl);
        return submitUrl.contains("/stats/")
                || containsAny(resourcePath, "/analytics-", "-analytics-", "analytics/")
                || structuralProjection
                && (rawJson.contains("\"x-ui\"") && rawJson.contains("\"analytics\"")
                || endpointText.contains("x-ui") && endpointText.contains("analytics")
                || containsAny(endpointText, "analytical", "analytics", "analitica", "analitico"));
    }

    private boolean hasProfileSignal(
            String resourcePath,
            String submitUrl,
            String endpointText,
            String rawJson) {
        boolean structuralProjection = isDerivedProjectionPath(resourcePath) || isDerivedProjectionPath(submitUrl);
        return containsAny(resourcePath, "/profile-", "-profile-", "/perfil-", "-perfil-")
                || containsAny(submitUrl, "/profile", "/perfil")
                || structuralProjection
                && (rawJson.contains("read_projection")
                || rawJson.contains("read-projection")
                || containsAny(endpointText,
                "read projection", "read-projection", "profile projection", "perfil 360",
                "profile 360", "visao consolidada", "summary profile"));
    }

    private SemanticResourceNeed semanticResourceNeed(String normalizedPrompt, String artifactKind) {
        String normalized = normalize(valueOrEmpty(normalizedPrompt));
        if (hasLlmAuthoredResourceFocus(normalized)) {
            String supportingConcepts = semanticQuerySection(normalized, "supporting concepts:", "desired surface:");
            String desiredSurface = semanticQuerySection(normalized, "desired surface:", "semantic query:");
            String semanticQuery = semanticQuerySection(normalized, "semantic query:", "");
            String focusedIntent = String.join(" ", supportingConcepts, desiredSurface, semanticQuery).trim();
            if ("chart".equals(artifactKind)) {
                return SemanticResourceNeed.ANALYTICS;
            }
            if (hasProfileIntentSignal(focusedIntent, normalized)) {
                return SemanticResourceNeed.PROFILE;
            }
            if (hasFocusedOperationalCollectionSignal(focusedIntent)
                    && !hasFocusedExplicitAnalyticalProjectionSignal(focusedIntent)) {
                return SemanticResourceNeed.GENERIC_OPERATIONAL;
            }
            if (hasFocusedExplicitAnalyticalProjectionSignal(focusedIntent)) {
                return SemanticResourceNeed.ANALYTICS;
            }
            return SemanticResourceNeed.GENERIC_OPERATIONAL;
        }
        String intentBearingText = intentBearingSemanticText(normalized);
        if ("chart".equals(artifactKind)) {
            return SemanticResourceNeed.ANALYTICS;
        }
        if (containsAny(intentBearingText,
                "grafico", "graficos", "chart", "charts", "indicador", "indicadores",
                "kpi", "kpis", "metrica", "metricas", "analitico", "analitica", "analytics",
                "tendencia", "distribuicao", "serie temporal")) {
            return SemanticResourceNeed.ANALYTICS;
        }
        if (hasProfileIntentSignal(intentBearingText, normalized)) {
            return SemanticResourceNeed.PROFILE;
        }
        return SemanticResourceNeed.GENERIC_OPERATIONAL;
    }

    private boolean hasLlmAuthoredResourceFocus(String normalizedPrompt) {
        return normalizedPrompt.contains("primary business entity:")
                && normalizedPrompt.contains("supporting concepts:")
                && normalizedPrompt.contains("semantic query:");
    }

    private boolean hasFocusedOperationalCollectionSignal(String focusedIntent) {
        return containsAny(valueOrEmpty(focusedIntent),
                "lista", "listagem", "listavel", "filtravel", "filtro", "filtros",
                "tabela", "registros", "cadastro", "colecao", "collection",
                "overview", "visao geral", "acompanhamento", "acompanhar", "mostrar",
                "exibir", "informacoes", "dados");
    }

    private boolean hasFocusedExplicitAnalyticalProjectionSignal(String focusedIntent) {
        String value = valueOrEmpty(focusedIntent);
        return containsAny(value,
                "analitico", "analitica", "analytics", "metricas agregadas", "agregado", "agregada",
                "distribuicao", "serie temporal", "tendencia", "histograma", "folha", "pagamento",
                "remuneracao", "salario", "payroll", "financeira");
    }

    private boolean hasProfileIntentSignal(String intentBearingText, String fullText) {
        if (containsAny(intentBearingText,
                "perfil individual", "individual profile", "profile page", "profile screen",
                "ficha", "resumo individual", "visao resumida", "visao consolidada")) {
            return true;
        }
        String normalized = valueOrEmpty(fullText);
        return containsAny(normalized,
                "perfil individual", "individual profile", "ficha", "resumo individual");
    }

    private String intentBearingSemanticText(String normalizedPrompt) {
        String normalized = valueOrEmpty(normalizedPrompt);
        if (!normalized.contains("primary business entity:")
                || !normalized.contains("supporting concepts:")
                || !normalized.contains("semantic query:")) {
            return normalized;
        }
        return String.join(" ",
                semanticQuerySection(normalized, "desired surface:", "semantic query:"),
                semanticQuerySection(normalized, "semantic query:", ""));
    }

    private String semanticQuerySection(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        start += startMarker.length();
        int end = endMarker == null || endMarker.isBlank() ? -1 : value.indexOf(endMarker, start);
        if (end < 0) {
            end = value.length();
        }
        return value.substring(start, end).trim();
    }

    private double semanticRoleScoreAdjustment(ResourceSemanticRole role, SemanticResourceNeed need) {
        if (role == null || need == null) {
            return 0d;
        }
        return switch (need) {
            case ANALYTICS -> role == ResourceSemanticRole.ANALYTICS_PROJECTION ? 0.07d
                    : role == ResourceSemanticRole.OPERATIONAL_RESOURCE ? -0.12d : -0.02d;
            case PROFILE -> role == ResourceSemanticRole.PROFILE_PROJECTION ? 0.07d
                    : role == ResourceSemanticRole.OPERATIONAL_RESOURCE ? 0.02d : -0.03d;
            case GENERIC_OPERATIONAL -> role == ResourceSemanticRole.OPERATIONAL_RESOURCE ? 0.06d
                    : role == ResourceSemanticRole.PROFILE_PROJECTION ? -0.02d : -0.05d;
        };
    }

    private double derivedProjectionScoreAdjustment(
            String resourcePath,
            String submitUrl,
            String normalizedPrompt,
            String artifactKind) {
        if (!isDerivedProjectionPath(resourcePath) && !isDerivedProjectionPath(submitUrl)) {
            return 0d;
        }
        SemanticResourceNeed need = semanticResourceNeed(normalizedPrompt, artifactKind);
        return need == SemanticResourceNeed.GENERIC_OPERATIONAL ? -0.08d : 0d;
    }

    private static boolean isDerivedProjectionPath(String value) {
        String normalized = normalizePath(value).toLowerCase(Locale.ROOT);
        return normalized.contains("/vw-")
                || normalized.contains("/view-")
                || normalized.contains("/views/")
                || normalized.contains("/projection-")
                || normalized.contains("/projections/");
    }

    private double artifactScoreAdjustment(String artifactKind, String endpointText, String path) {
        double adjustment = 0d;
        boolean analyticalMetadata = containsAny(endpointText,
                "analytics", "analit", "metric", "metrica", "indicador", "indicadores", "kpi", "dashboard");
        if ("dashboard".equals(artifactKind) || "chart".equals(artifactKind)) {
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
        if ("form".equals(artifactKind)) {
            if (path.endsWith("/filter") || path.endsWith("/filter/cursor")
                    || path.endsWith("/stats/group-by") || path.endsWith("/stats/timeseries")
                    || path.endsWith("/stats/distribution")) {
                adjustment -= 0.18d;
            } else if (!path.endsWith("/all") && !path.endsWith("/by-ids") && !path.contains("/{")) {
                adjustment += 0.18d;
            }
        }
        if (endpointText.contains("legado")) {
            adjustment -= 0.20d;
        }
        return adjustment;
    }

    private boolean matchesToken(String text, String token) {
        return matchesRawToken(text, token);
    }

    private boolean matchesRawToken(String text, String token) {
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

    private List<String> explicitPhraseTerms(String normalizedPrompt, String... anchors) {
        String[] words = normalize(normalizedPrompt).replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        if (words.length == 0) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (int i = 0; i < words.length; i++) {
            if (!containsWord(anchors, words[i])) {
                continue;
            }
            for (int j = i + 1; j < words.length; j++) {
                String word = words[j];
                if (isExplicitPhraseBoundary(word)) {
                    break;
                }
                if (word.length() >= 4 && !isExplicitPhraseFiller(word)) {
                    terms.add(word);
                }
            }
            if (!terms.isEmpty()) {
                break;
            }
        }
        return List.copyOf(terms);
    }

    private boolean containsWord(String[] words, String value) {
        if (words == null || value == null) {
            return false;
        }
        for (String word : words) {
            if (value.equals(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExplicitPhraseFiller(String word) {
        return containsWord(new String[] {
                "a", "as", "o", "os", "um", "uma", "de", "da", "das", "do", "dos", "para", "por",
                "com", "em", "no", "na", "nos", "nas", "usar", "use", "usando"
        }, word);
    }

    private boolean isExplicitPhraseBoundary(String word) {
        return containsWord(new String[] {
                "campo", "campos", "field", "fields", "coluna", "colunas", "eixo", "eixos",
                "nao", "sem", "tabela", "tabelas", "filtro", "filtros", "kpi", "kpis",
                "dashboard", "dashboards", "grafico", "graficos", "chart", "charts", "somente",
                "apenas", "only"
        }, word);
    }

    private List<String> explicitMetadataEvidence(boolean explicitFieldMatch) {
        List<String> evidence = new ArrayList<>();
        evidence.add("api-metadata");
        evidence.add("explicit-source-match");
        if (explicitFieldMatch) {
            evidence.add("explicit-field-match");
        }
        evidence.add("schema-available");
        evidence.add("actions-probe-pending");
        evidence.add("capabilities-probe-pending");
        return List.copyOf(evidence);
    }

    private List<String> mergeTerms(List<String> primary, List<String> secondary, List<String> tertiary) {
        Set<String> terms = new LinkedHashSet<>();
        if (primary != null) {
            terms.addAll(primary);
        }
        if (secondary != null) {
            terms.addAll(secondary);
        }
        if (tertiary != null) {
            terms.addAll(tertiary);
        }
        return List.copyOf(terms);
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

    private static String normalizePath(String path) {
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

    private enum ResourceSemanticRole {
        OPERATIONAL_RESOURCE(SEMANTIC_ROLE_OPERATIONAL_RESOURCE),
        ANALYTICS_PROJECTION(SEMANTIC_ROLE_ANALYTICS_PROJECTION),
        PROFILE_PROJECTION(SEMANTIC_ROLE_PROFILE_PROJECTION);

        private final String evidence;

        ResourceSemanticRole(String evidence) {
            this.evidence = evidence;
        }
    }

    private enum SemanticResourceNeed {
        GENERIC_OPERATIONAL,
        ANALYTICS,
        PROFILE
    }
}
