package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates candidate ranking, prompt-candidate alignment scoring, projection role fitting,
 * local UI/page composition classification, and eligibility gate enrichment for
 * {@link AgenticAuthoringIntentResolverService}.
 */
final class AgenticAuthoringIntentCandidateScoringSupport {

    static final String SEMANTIC_ROLE_OPERATIONAL_RESOURCE = "semantic-role:operational-resource";
    static final String SEMANTIC_ROLE_ANALYTICS_PROJECTION = "semantic-role:analytics-projection";
    static final String SEMANTIC_ROLE_PROFILE_PROJECTION = "semantic-role:profile-projection";

    enum SemanticResourceNeed {
        GENERIC_OPERATIONAL,
        ANALYTICS,
        PROFILE
    }

    record CandidatePromptAlignment(AgenticAuthoringCandidate candidate, int score) {
    }

    private final AgenticAuthoringIntentQuickReplySupport quickReplySupport;

    AgenticAuthoringIntentCandidateScoringSupport(AgenticAuthoringIntentQuickReplySupport quickReplySupport) {
        this.quickReplySupport = quickReplySupport;
    }

    AgenticAuthoringCandidate preLlmGovernedResourceChoiceCandidate(
            String prompt,
            String operationKind,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate contextHintCandidate) {
        if (contextHintCandidate != null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (!"dashboard".equals(artifactKind) || !"create".equals(operationKind)) {
            return null;
        }
        if (!isConcreteDashboardMaterializationPrompt(prompt)) {
            return null;
        }
        boolean promptNamesCandidate = candidates.stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> promptMatchesCandidatePathIdentity(prompt, candidate));
        boolean promptMatchesGovernedCandidate = candidates.stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate));
        if (isBroadArtifactDiscoveryOnly(candidates)
                && !isAnalyticalComparisonPrompt(prompt)
                && !promptMatchesGovernedCandidate) {
            return null;
        }
        List<CandidatePromptAlignment> alignments = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !promptNamesCandidate || promptMatchesCandidatePathIdentity(prompt, candidate))
                .map(candidate -> new CandidatePromptAlignment(
                        candidate,
                        preLlmDashboardCandidateAlignmentScore(prompt, artifactKind, candidate)))
                .filter(alignment -> alignment.score() > 0)
                .toList();
        if (alignments.isEmpty()) {
            return null;
        }
        SemanticResourceNeed resourceNeed = semanticResourceNeed(prompt, artifactKind);
        CandidatePromptAlignment best = alignments.stream()
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> semanticRoleFit(alignment.candidate(), resourceNeed))
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .orElse(null);
        CandidatePromptAlignment dedicatedProjection = alignments.stream()
                .filter(alignment -> isDedicatedProjectionCandidate(alignment.candidate(), projectionRole(resourceNeed)))
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .orElse(null);
        if (best != null
                && dedicatedProjection != null
                && !sameCandidate(best.candidate(), dedicatedProjection.candidate())
                && dedicatedProjection.score() >= best.score() - 4) {
            return dedicatedProjection.candidate();
        }
        return best == null ? null : best.candidate();
    }

    String projectionRole(SemanticResourceNeed resourceNeed) {
        if (resourceNeed == SemanticResourceNeed.ANALYTICS) {
            return SEMANTIC_ROLE_ANALYTICS_PROJECTION;
        }
        if (resourceNeed == SemanticResourceNeed.PROFILE) {
            return SEMANTIC_ROLE_PROFILE_PROJECTION;
        }
        return "";
    }

    int preLlmDashboardCandidateAlignmentScore(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        List<String> evidenceTerms = candidateGovernedTextTerms(candidate);
        if (evidenceTerms.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String token : promptSpecificTokens(prompt)) {
            if (termListMatchesToken(evidenceTerms, token)) {
                score += 3;
            }
        }
        score = Math.max(score, candidatePathDomainTokenScore(prompt, candidate));
        if (isVisualMaterializationArtifact(artifactKind) && termListMatchesToken(evidenceTerms, artifactKind)) {
            score += 2;
        }
        if (isVisualMaterializationArtifact(artifactKind)
                && promptMatchesCandidatePathIdentity(prompt, candidate)
                && hasAnalyticalProjectionAffordance(candidate, evidenceTerms)) {
            score += 3;
        }
        if ("chart".equals(artifactKind) && isStatsProjectionOperation(candidate)) {
            score += 2;
        }
        if (score <= 0) {
            return 0;
        }
        SemanticResourceNeed resourceNeed = semanticResourceNeed(prompt, artifactKind);
        if (resourceNeed == SemanticResourceNeed.ANALYTICS
                && hasEvidence(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)) {
            score += 4;
            if (isDedicatedProjectionCandidate(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)) {
                score += 6;
            } else if (isMixedOperationalProjectionCandidate(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)) {
                score -= 3;
            }
        } else if (resourceNeed == SemanticResourceNeed.ANALYTICS
                && hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)) {
            score -= 1;
        } else if (resourceNeed == SemanticResourceNeed.PROFILE
                && hasEvidence(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
            score += 4;
            if (isDedicatedProjectionCandidate(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
                score += 6;
            } else if (isMixedOperationalProjectionCandidate(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
                score -= 3;
            }
        }
        return score;
    }

    boolean hasAnalyticalProjectionAffordance(
            AgenticAuthoringCandidate candidate,
            List<String> evidenceTerms) {
        if (candidate == null) {
            return false;
        }
        String candidateText = normalize(String.join(" ",
                valueOrDefault(candidate.resourcePath(), ""),
                valueOrDefault(candidate.submitUrl(), ""),
                valueOrDefault(candidate.reason(), "")));
        if (containsAny(candidateText,
                "vw analytics",
                "analytics",
                "analitica",
                "analitico",
                "projection",
                "projecao",
                "projeção")) {
            return true;
        }
        return termListMatchesToken(evidenceTerms, "analytics")
                || termListMatchesToken(evidenceTerms, "analitica")
                || termListMatchesToken(evidenceTerms, "analitico")
                || termListMatchesToken(evidenceTerms, "projection")
                || termListMatchesToken(evidenceTerms, "projecao");
    }

    boolean promptMatchesCandidatePathIdentity(String prompt, AgenticAuthoringCandidate candidate) {
        String normalizedPrompt = normalize(prompt);
        String normalizedPath = normalizePath(candidate == null ? "" : candidate.resourcePath());
        if (normalizedPrompt.isBlank() || normalizedPath.isBlank()) {
            return false;
        }
        for (String token : candidatePathIdentityTokens(normalizedPath)) {
            if (!isGenericAnalyticalCandidateToken(token) && tokenMatchesCandidateText(token, normalizedPrompt)) {
                return true;
            }
        }
        return false;
    }

    boolean hasVerifiedStatsCapabilitiesOrProjection(AgenticAuthoringCandidate candidate) {
        return hasEvidence(candidate, "stats-capabilities-verified") || isStatsProjectionOperation(candidate);
    }

    boolean isStatsProjectionOperation(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return normalizePath(candidate.submitUrl()).contains("/stats/");
    }

    List<String> candidateGovernedTextTerms(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidenceBundle() == null || candidate.evidenceBundle().evidence() == null) {
            return List.of();
        }
        return candidate.evidenceBundle().evidence().stream()
                .flatMap(evidence -> Stream.of(evidence.ref(), evidence.summary()))
                .map(this::normalize)
                .flatMap(value -> Stream.of(value.replaceAll("[^a-z0-9]+", " ").split("\\s+")))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .distinct()
                .toList();
    }

    int candidatePathDomainTokenScore(String prompt, AgenticAuthoringCandidate candidate) {
        String normalizedPrompt = normalize(prompt);
        String normalizedPath = normalizePath(candidate == null ? "" : candidate.resourcePath());
        if (normalizedPrompt.isBlank() || normalizedPath.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String token : candidatePathIdentityTokens(normalizedPath)) {
            if (isGenericAnalyticalCandidateToken(token)) {
                continue;
            }
            if (tokenMatchesCandidateText(token, normalizedPrompt)) {
                score += 4;
            } else {
                score -= 2;
            }
        }
        return Math.max(0, score);
    }

    boolean isConcreteDashboardMaterializationPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (!containsAny(normalized,
                "dashboard",
                "painel",
                "visao geral",
                "visão geral",
                "360",
                "indicador",
                "indicadores",
                "grafico",
                "graficos",
                "gráfico",
                "gráficos")) {
            return false;
        }
        if (containsAny(normalized,
                "apenas",
                "somente",
                "so ",
                "só ",
                "nao crie tabela",
                "não crie tabela",
                "nao sei",
                "não sei",
                "quais informacoes",
                "quais informações",
                "devo usar",
                "melhor",
                "recomenda",
                "recomende")) {
            return false;
        }
        boolean hasChart = containsAny(normalized, "grafico", "graficos", "gráfico", "gráficos", "chart", "charts");
        boolean hasFilter = containsAny(normalized, "filtro", "filtros");
        boolean hasDetails = containsAny(normalized, "tabela", "detalhe", "detalhes", "detalhada", "detalhado");
        boolean hasOverviewDashboard = containsAny(normalized,
                "painel geral",
                "dashboard geral",
                "visao geral",
                "visão geral",
                "overview");
        boolean hasPageSurface = containsAny(normalized, "tela", "pagina", "página", "page");
        boolean hasAuthoringIntent = containsAny(normalized,
                "quero",
                "preciso",
                "gostaria",
                "crie",
                "criar",
                "monte",
                "montar",
                "gere",
                "gerar");
        return containsAny(normalized, "360", "visao 360", "visão 360")
                || hasOverviewDashboard
                || (hasPageSurface && hasAuthoringIntent)
                || (hasChart && hasFilter && hasDetails);
    }

    List<AgenticAuthoringCandidate> rankCandidatesForDecision(
            List<AgenticAuthoringCandidate> candidates,
            String artifactKind,
            String prompt) {
        if (candidates == null || candidates.isEmpty() || "api_catalog".equals(artifactKind)) {
            return candidates == null ? List.of() : candidates;
        }
        SemanticResourceNeed resourceNeed = semanticResourceNeed(prompt, artifactKind);
        return candidates.stream()
                .sorted(Comparator
                        .comparingInt(this::evidenceStrength)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(
                                (AgenticAuthoringCandidate candidate) -> hasEvidence(candidate, "tool-search-api-resources") ? 1 : 0)
                                .reversed())
                        .thenComparing(Comparator.comparingInt(
                                (AgenticAuthoringCandidate candidate) -> hasEvidence(candidate, "domain-binding") ? 1 : 0)
                                .reversed())
                        .thenComparing(Comparator.comparingDouble(
                                (AgenticAuthoringCandidate candidate) -> semanticRoleFit(candidate, resourceNeed)).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (AgenticAuthoringCandidate candidate) -> promptCandidateIdentityFit(prompt, candidate)).reversed())
                        .thenComparing(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                        .thenComparing(Comparator.comparingInt(
                                (AgenticAuthoringCandidate candidate) -> operationFitForArtifact(artifactKind, candidate)).reversed()))
                .toList();
    }

    int promptCandidateIdentityFit(String prompt, AgenticAuthoringCandidate candidate) {
        return promptMatchesCandidatePathIdentity(prompt, candidate) ? 1 : 0;
    }

    int operationFitForArtifact(String artifactKind, AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return 0;
        }
        String submitUrl = normalizePath(valueOrDefault(candidate.submitUrl(), ""));
        String operation = valueOrDefault(candidate.operation(), "").toLowerCase(Locale.ROOT);
        if ("chart".equals(artifactKind) || "dashboard".equals(artifactKind)) {
            if (submitUrl.endsWith("/stats/group-by")) {
                return 4;
            }
            if (submitUrl.contains("/stats/")) {
                return 3;
            }
            if (submitUrl.endsWith("/filter/cursor")) {
                return 2;
            }
            if (submitUrl.endsWith("/filter") || "get".equals(operation)) {
                return 1;
            }
            return 0;
        }
        if ("table".equals(artifactKind) || "list".equals(artifactKind) || "page".equals(artifactKind)) {
            if (submitUrl.endsWith("/filter/cursor")) {
                return 3;
            }
            if (submitUrl.endsWith("/filter") || ("get".equals(operation) && !submitUrl.contains("{"))) {
                return 2;
            }
            if (submitUrl.contains("/stats/")) {
                return 1;
            }
            return 0;
        }
        return 0;
    }

    double semanticRoleFit(AgenticAuthoringCandidate candidate, SemanticResourceNeed resourceNeed) {
        if (candidate == null || candidate.evidence() == null || resourceNeed == null) {
            return 0d;
        }
        boolean derivedProjection = isDerivedProjectionCandidate(candidate);
        return switch (resourceNeed) {
            case ANALYTICS -> hasEvidence(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                    ? isDedicatedProjectionCandidate(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION) ? 0.26d : 0.14d
                    : hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE) ? 0.04d : 0d;
            case PROFILE -> hasEvidence(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                    ? isDedicatedProjectionCandidate(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION) ? 0.26d : 0.14d
                    : hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE) ? 0.08d : 0d;
            case GENERIC_OPERATIONAL -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                    ? (derivedProjection ? 0.04d : 0.18d)
                    : hasEvidence(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION) ? 0.06d : 0d;
        };
    }

    boolean isDerivedProjectionCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return isDerivedProjectionPath(candidate.resourcePath()) || isDerivedProjectionPath(candidate.submitUrl());
    }

    boolean isDerivedProjectionPath(String value) {
        String normalized = normalizePath(value).toLowerCase(Locale.ROOT);
        return normalized.contains("/vw-")
                || normalized.contains("/view-")
                || normalized.contains("/views/")
                || normalized.contains("/projection-")
                || normalized.contains("/projections/");
    }

    SemanticResourceNeed semanticResourceNeed(String prompt, String artifactKind) {
        String normalized = normalize(valueOrDefault(prompt, ""));
        if ("chart".equals(artifactKind)) {
            return SemanticResourceNeed.ANALYTICS;
        }
        if (containsAny(normalized,
                "grafico", "graficos", "chart", "charts", "indicador", "indicadores",
                "kpi", "kpis", "metrica", "metricas", "metric", "metrics",
                "analitico", "analitica", "analytics", "analytical",
                "tendencia", "trend", "trends", "distribuicao", "distribution",
                "serie temporal", "time series", "comparar", "comparacao", "comparison",
                "evolucao", "evolução", "progressao", "progressão",
                "trajetoria", "trajetória", "ao longo do tempo",
                "aggregate", "aggregated", "average", "ranking")) {
            return SemanticResourceNeed.ANALYTICS;
        }
        if (containsAny(normalized,
                "perfil individual", "individual profile", "profile page", "profile screen",
                "tela de perfil", "pagina de perfil", "página de perfil",
                "ficha", "resumo individual", "individual", "360",
                "visao resumida", "visao consolidada",
                "summary sheet", "summary view", "profile summary", "individual summary",
                "personal summary", "person summary", "record summary", "summary card",
                "detail card", "detail page", "details page")) {
            return SemanticResourceNeed.PROFILE;
        }
        return SemanticResourceNeed.GENERIC_OPERATIONAL;
    }

    int evidenceStrength(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidence() == null) {
            return 0;
        }
        if (hasEvidence(candidate, "semantic-retrieval")
                || hasEvidence(candidate, "explicit-source-match")
                || hasEvidence(candidate, "domain-binding")
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

    AgenticAuthoringCandidate selectCandidate(
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringTarget target,
            String operationKind,
            String artifactKind,
            String prompt) {
        if (target != null && target.resourcePath() != null && !target.resourcePath().isBlank()
                && !shouldDetachCurrentTarget(prompt, artifactKind, target)) {
            return candidates.stream()
                    .filter(candidate -> target.resourcePath().equals(candidate.resourcePath()))
                    .map(candidate -> targetBoundCandidate(candidate, target))
                    .findFirst()
                    .orElse(null);
        }
        if (isDataSourceReplacementPrompt(normalize(prompt)) && !candidates.isEmpty()) {
            AgenticAuthoringCandidate promptAlignedCandidate = promptAlignedBusinessCandidate(prompt, candidates);
            return promptAlignedCandidate == null ? candidates.get(0) : promptAlignedCandidate;
        }
        if (shouldDeferResourceSelectionForGovernedChoice(prompt, operationKind, artifactKind, candidates)) {
            return null;
        }
        if ("page".equals(artifactKind)
                && "explore".equals(operationKind)
                && containsAny(normalize(prompt), "nao sei quais informacoes existem", "quais informacoes existem")) {
            AgenticAuthoringCandidate pageCandidate = candidates.stream()
                    .filter(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))
                    .findFirst()
                    .orElse(null);
            if (pageCandidate != null) {
                return pageCandidate;
            }
        }
        if (isBroadArtifactDiscoveryOnly(candidates)
                && "form".equals(artifactKind)
                && "create".equals(operationKind)
                && strongestPromptCandidateAlignmentScore(prompt, candidates) < 6) {
            return null;
        }
        AgenticAuthoringCandidate visualProjectionCandidate = visualMaterializationCandidate(
                prompt,
                operationKind,
                artifactKind,
                candidates);
        if (visualProjectionCandidate != null) {
            return visualProjectionCandidate;
        }
        AgenticAuthoringCandidate promptAlignedCandidate = promptAlignedBusinessCandidate(prompt, candidates);
        if (promptAlignedCandidate != null && shouldPreferPromptAlignedCandidate(promptAlignedCandidate, candidates)) {
            return promptAlignedCandidate;
        }
        if ("form".equals(artifactKind)
                && "create".equals(operationKind)
                && candidates.size() > 1
                && !hasStrongPromptAlignment(prompt, candidates)) {
            return null;
        }
        if ("dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && !isBroadAnalyticalCanvasPrompt(prompt)
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))) {
            return null;
        }
        if (isBroadArtifactDiscoveryOnly(candidates)) {
            return null;
        }
        if ("api_catalog".equals(artifactKind)
                && "explore".equals(operationKind)
                && isBroadApiCatalogResourceDiscoveryPrompt(prompt)) {
            return null;
        }
        if ("api_catalog".equals(artifactKind)
                && "explore".equals(operationKind)
                && isSpecificApiCatalogAnswerPrompt(prompt)
                && !candidates.isEmpty()) {
            return candidates.get(0);
        }
        if (candidates.stream().allMatch(candidate ->
                isWeakLexicalCandidate(candidate) && !hasTrustedSelectionEvidence(candidate))) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if ("dashboard".equals(artifactKind)
                && isAnalyticalComparisonPrompt(prompt)
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))) {
            return null;
        }
        if (candidates.size() > 1 && candidates.get(0).score() - candidates.get(1).score() >= 0.08d) {
            return candidates.get(0);
        }
        return null;
    }

    AgenticAuthoringCandidate visualMaterializationCandidate(
            String prompt,
            String operationKind,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if ((!isVisualMaterializationArtifact(artifactKind)
                && !isConcreteDashboardMaterializationPrompt(prompt))
                || candidates == null
                || candidates.size() <= 1
                || isBroadArtifactDiscoveryOnly(candidates)) {
            return null;
        }
        boolean promptNamesCandidate = candidates.stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> promptMatchesCandidatePathIdentity(prompt, candidate));
        SemanticResourceNeed visualNeed = semanticResourceNeed(prompt, artifactKind);
        String visualProjectionRole = switch (visualNeed) {
            case PROFILE -> SEMANTIC_ROLE_PROFILE_PROJECTION;
            case ANALYTICS -> SEMANTIC_ROLE_ANALYTICS_PROJECTION;
            default -> "";
        };
        List<CandidatePromptAlignment> alignments = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !promptNamesCandidate
                        || promptMatchesCandidatePathIdentity(prompt, candidate)
                        || isDedicatedProjectionCandidate(candidate, visualProjectionRole))
                .map(candidate -> new CandidatePromptAlignment(
                        candidate,
                        preLlmDashboardCandidateAlignmentScore(prompt, artifactKind, candidate)))
                .filter(candidateAlignment -> candidateAlignment.score() > 0)
                .toList();
        CandidatePromptAlignment bestAlignment = alignments.stream()
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(candidateAlignment -> candidateAlignment.candidate().score()))
                .orElse(null);
        if (bestAlignment == null) {
            return null;
        }
        return bestAlignment.candidate();
    }

    AgenticAuthoringCandidate analyticalRoleCandidateForComparisonPrompt(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (!"dashboard".equals(artifactKind)
                || !isAnalyticalComparisonPrompt(prompt)
                || candidates == null
                || candidates.isEmpty()
                || (selectedCandidate != null && promptMatchesCandidatePathIdentity(prompt, selectedCandidate))) {
            return null;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION))
                .filter(candidate -> !isWeakLexicalCandidate(candidate) || hasTrustedSelectionEvidence(candidate))
                .max(Comparator
                        .comparingInt(this::evidenceStrength)
                        .thenComparingDouble(AgenticAuthoringCandidate::score))
                .orElse(null);
    }

    boolean isVisualMaterializationArtifact(String artifactKind) {
        return "dashboard".equals(artifactKind) || "chart".equals(artifactKind);
    }

    boolean isDataSourceRefinement(AgenticAuthoringSemanticRefinement semanticRefinement) {
        return semanticRefinement != null
                && semanticRefinement.active()
                && "data_source".equals(semanticRefinement.refinementKind());
    }

    boolean shouldPreferPromptAlignedCandidate(
            AgenticAuthoringCandidate promptAlignedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (promptAlignedCandidate == null || candidates == null || candidates.isEmpty()) {
            return false;
        }
        double bestScore = candidates.stream()
                .filter(Objects::nonNull)
                .mapToDouble(AgenticAuthoringCandidate::score)
                .max()
                .orElse(0d);
        return bestScore - promptAlignedCandidate.score() <= 0.20d;
    }

    boolean hasStrongPromptAlignment(String prompt, List<AgenticAuthoringCandidate> candidates) {
        String normalizedPrompt = normalize(prompt);
        if (normalizedPrompt.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> promptMentionsSpecificCandidateToken(normalizedPrompt, candidate));
    }

    int promptCandidateAlignmentScore(String prompt, AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return 0;
        }
        List<String> promptTokens = promptSpecificTokens(prompt);
        if (promptTokens.isEmpty()) {
            return 0;
        }
        List<String> evidenceTerms = candidateEvidenceTerms(candidate);
        int score = 0;
        for (String token : promptTokens) {
            if (termListMatchesToken(evidenceTerms, token)) {
                score += 3;
            } else if (promptMentionsSpecificCandidateToken(token, candidate)) {
                score += 1;
            }
        }
        score += candidatePathDomainTokenScore(prompt, candidate);
        return score;
    }

    AgenticAuthoringCandidate promptAlignedGovernedRuleCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<CandidatePromptAlignment> alignments = candidates.stream()
                .filter(Objects::nonNull)
                .map(candidate -> new CandidatePromptAlignment(candidate, directPromptCandidateAlignmentScore(prompt, candidate)))
                .filter(alignment -> alignment.score() > 0)
                .toList();
        List<CandidatePromptAlignment> trustedAlignments = alignments.stream()
                .filter(alignment -> !isWeakLexicalCandidate(alignment.candidate())
                        || hasTrustedSelectionEvidence(alignment.candidate()))
                .toList();
        List<CandidatePromptAlignment> eligibleAlignments =
                trustedAlignments.isEmpty() ? alignments : trustedAlignments;
        return eligibleAlignments.stream()
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .map(CandidatePromptAlignment::candidate)
                .orElse(null);
    }

    boolean canUsePromptAlignedCandidate(
            AgenticAuthoringCandidate candidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (!isWeakLexicalCandidate(candidate) || hasTrustedSelectionEvidence(candidate)) {
            return true;
        }
        return (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .filter(Objects::nonNull)
                .filter(other -> !sameCandidate(candidate, other))
                .noneMatch(this::hasTrustedSelectionEvidence);
    }

    int directPromptCandidateAlignmentScore(String prompt, AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return 0;
        }
        String candidateText = directCandidateSemanticText(candidate, true);
        if (candidateText.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String token : promptSpecificTokens(prompt)) {
            score += Math.min(3, tokenOccurrenceCount(token, candidateText));
        }
        return score;
    }

    int tokenOccurrenceCount(String token, String candidateText) {
        if (token == null || token.isBlank() || candidateText == null || candidateText.isBlank()) {
            return 0;
        }
        String normalizedToken = normalize(token);
        if (normalizedToken.isBlank()) {
            return 0;
        }
        String paddedText = " " + candidateText.replaceAll("[^a-z0-9]+", " ") + " ";
        int count = countWholeToken(paddedText, normalizedToken);
        if (normalizedToken.endsWith("s") && normalizedToken.length() > 4) {
            count += countWholeToken(paddedText, normalizedToken.substring(0, normalizedToken.length() - 1));
        }
        if (normalizedToken.length() > 4) {
            count += countWholeToken(paddedText, normalizedToken + "s");
        }
        return count;
    }

    int countWholeToken(String paddedText, String token) {
        if (paddedText == null || token == null || token.isBlank()) {
            return 0;
        }
        String needle = " " + token + " ";
        int count = 0;
        int index = paddedText.indexOf(needle);
        while (index >= 0) {
            count++;
            index = paddedText.indexOf(needle, index + needle.length() - 1);
        }
        return count;
    }

    boolean tokenMatchesCandidateText(String token, String candidateText) {
        if (token == null || token.isBlank() || candidateText == null || candidateText.isBlank()) {
            return false;
        }
        String normalizedToken = normalize(token);
        String paddedText = " " + candidateText.replaceAll("[^a-z0-9]+", " ") + " ";
        if (paddedText.contains(" " + normalizedToken + " ")) {
            return true;
        }
        if (normalizedToken.endsWith("s") && normalizedToken.length() > 4
                && paddedText.contains(" " + normalizedToken.substring(0, normalizedToken.length() - 1) + " ")) {
            return true;
        }
        if (normalizedToken.length() > 4 && paddedText.contains(" " + normalizedToken + "s ")) {
            return true;
        }
        if (normalizedToken.endsWith("ais") && normalizedToken.length() > 5) {
            String singular = normalizedToken.substring(0, normalizedToken.length() - 3) + "al";
            if (paddedText.contains(" " + singular + " ")) {
                return true;
            }
        }
        if (normalizedToken.endsWith("al") && normalizedToken.length() > 4) {
            String plural = normalizedToken.substring(0, normalizedToken.length() - 2) + "ais";
            if (paddedText.contains(" " + plural + " ")) {
                return true;
            }
        }
        String stem = morphologicalStem(normalizedToken);
        if (stem.length() >= 6) {
            for (String candidateWord : paddedText.trim().split("\\s+")) {
                if (candidateWord.length() >= 6 && morphologicalStem(candidateWord).equals(stem)) {
                    return true;
                }
            }
        }
        if (Set.of("historico", "historicos").contains(normalizedToken)
                && (paddedText.contains(" evolucao ")
                        || paddedText.contains(" progressao ")
                        || paddedText.contains(" trajetoria ")
                        || paddedText.contains(" serie temporal "))) {
            return true;
        }
        return false;
    }

    private String morphologicalStem(String word) {
        String normalized = normalize(word);
        for (String suffix : List.of("amentos", "amento", "acoes", "acao", "iais", "ial", "ais", "al", "oes", "ao", "os", "as", "o", "a", "s")) {
            if (normalized.endsWith(suffix) && normalized.length() - suffix.length() >= 5) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }

    String directCandidateSemanticText(AgenticAuthoringCandidate candidate) {
        return directCandidateSemanticText(candidate, false);
    }

    String directCandidateSemanticText(AgenticAuthoringCandidate candidate, boolean includeMatchedTerms) {
        Stream<String> evidenceText = candidate.evidenceBundle() == null
                || candidate.evidenceBundle().evidence() == null
                ? Stream.empty()
                : candidate.evidenceBundle().evidence().stream()
                .flatMap(evidence -> includeMatchedTerms
                        ? Stream.concat(
                        Stream.of(evidence.ref(), evidence.summary()),
                        weakLexicalEvidence(evidence)
                                || evidence.matchedTerms() == null
                                ? Stream.empty()
                                : evidence.matchedTerms().stream())
                        : Stream.of(evidence.ref(), evidence.summary()));
        return normalize(Stream.concat(
                        Stream.of(candidate.resourcePath(), candidate.submitUrl(), candidate.reason()),
                        evidenceText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" ")));
    }

    boolean weakLexicalEvidence(AgenticAuthoringEvidenceBundle.Evidence evidence) {
        return evidence != null && "weak_lexical_match".equals(valueOrDefault(evidence.kind(), ""));
    }

    int strongestPromptCandidateAlignmentScore(String prompt, List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .mapToInt(candidate -> promptCandidateAlignmentScore(prompt, candidate))
                .max()
                .orElse(0);
    }

    List<String> promptSpecificTokens(String prompt) {
        String normalizedPrompt = normalize(prompt);
        if (normalizedPrompt.isBlank()) {
            return List.of();
        }
        return Stream.of(normalizedPrompt.replaceAll("[^a-z0-9]+", " ").split("\\s+"))
                .map(String::trim)
                .filter(token -> !isGenericAnalyticalCandidateToken(token))
                .filter(token -> !isPromptAlignmentStopword(token))
                .distinct()
                .toList();
    }

    boolean isPromptAlignmentStopword(String token) {
        return Set.of(
                "uma", "um", "de", "do", "da", "dos", "das", "para", "por", "com",
                "quero", "preciso", "gostaria", "tipo", "bonito", "ver", "ve",
                "crie", "criar", "monte", "montar", "gere", "gerar",
                "tela", "pagina", "page", "tabela", "table", "lista", "listagem", "dashboard", "painel",
                "grafico", "graficos", "campos", "colunas", "somente", "apenas",
                "regra", "regras", "poder", "posso", "pode", "podem", "ser")
                .contains(valueOrDefault(token, ""));
    }

    List<String> candidateEvidenceTerms(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidenceBundle() == null || candidate.evidenceBundle().evidence() == null) {
            return List.of();
        }
        return candidate.evidenceBundle().evidence().stream()
                .flatMap(evidence -> {
                    Stream<String> matchedTerms =
                            evidence.matchedTerms() == null ? Stream.empty() : evidence.matchedTerms().stream();
                    Stream<String> structuralTerms = "weak_lexical_match".equals(valueOrDefault(evidence.kind(), ""))
                            ? Stream.of(evidence.ref())
                            : Stream.of(evidence.ref(), evidence.summary());
                    return Stream.concat(matchedTerms, structuralTerms);
                })
                .map(this::normalize)
                .flatMap(value -> Stream.of(value.replaceAll("[^a-z0-9]+", " ").split("\\s+")))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .filter(token -> !isGenericAnalyticalCandidateToken(token))
                .distinct()
                .toList();
    }

    boolean termListMatchesToken(List<String> terms, String token) {
        if (terms == null || terms.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        String normalizedToken = normalize(token);
        return terms.stream().anyMatch(term -> tokenMatchesTerm(normalizedToken, term));
    }

    boolean tokenMatchesTerm(String token, String term) {
        if (token == null || token.isBlank() || term == null || term.isBlank()) {
            return false;
        }
        if (term.equals(token)) {
            return true;
        }
        if (token.endsWith("s") && token.length() > 4 && term.equals(token.substring(0, token.length() - 1))) {
            return true;
        }
        return term.endsWith("s") && term.length() > 4 && token.equals(term.substring(0, term.length() - 1));
    }

    String candidateText(AgenticAuthoringCandidate candidate) {
        return normalize(String.join(" ",
                valueOrDefault(candidate.resourcePath(), ""),
                valueOrDefault(candidate.submitUrl(), ""),
                valueOrDefault(candidate.reason(), "")));
    }

    AgenticAuthoringCandidate targetBoundCandidate(
            AgenticAuthoringCandidate candidate,
            AgenticAuthoringTarget target) {
        if (candidate == null || target == null || valueOrDefault(target.submitMethod(), "").isBlank()) {
            return candidate;
        }
        String submitMethod = valueOrDefault(target.submitMethod(), candidate.submitMethod());
        String submitUrl = valueOrDefault(target.submitUrl(), candidate.submitUrl());
        String schemaUrl = valueOrDefault(target.schemaUrl(), candidate.schemaUrl());
        return new AgenticAuthoringCandidate(
                candidate.resourcePath(),
                submitMethod,
                schemaUrl,
                submitUrl,
                submitMethod,
                candidate.score(),
                candidate.reason(),
                candidate.evidence(),
                candidate.evidenceBundle());
    }

    boolean shouldDeferResourceSelectionForGovernedChoice(
            String prompt,
            String operationKind,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.size() <= 1) {
            return false;
        }
        String normalized = normalize(prompt);
        if (isBareDomainPrompt(normalized)) {
            return true;
        }
        if ("dashboard".equals(artifactKind)
                && containsAny(normalized, "dashboard", "painel", "grafico", "graficos")
                && candidates.size() > 1
                && candidates.stream().noneMatch(candidate -> promptMentionsSpecificCandidateToken(prompt, candidate))) {
            return true;
        }
        return false;
    }

    boolean isBroadApiCatalogResourceDiscoveryPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (containsAny(normalized,
                "schema",
                "campos",
                "filtros",
                "actions",
                "acoes",
                "ações",
                "permite criar",
                "permite editar",
                "permite excluir",
                "qual endpoint devo usar",
                "qual api devo usar",
                "recomendacao",
                "recomendação")) {
            return false;
        }
        return containsAny(normalized,
                "quais apis",
                "quais dados",
                "quais recursos",
                "dados disponiveis",
                "dados disponíveis",
                "recursos disponiveis",
                "recursos disponíveis",
                "fontes candidatas",
                "areas de dados",
                "áreas de dados",
                "para criar graficos",
                "para criar gráficos",
                "para indicadores");
    }

    boolean isSpecificApiCatalogAnswerPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "schema",
                "campos",
                "filtros",
                "actions",
                "acoes",
                "ações",
                "permite criar",
                "permite editar",
                "permite excluir",
                "qual endpoint devo usar",
                "qual api devo usar",
                "recomendacao",
                "recomendação");
    }

    boolean isApiCatalogResourceListPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "quais dados",
                "que dados",
                "dados existem",
                "dados que existem",
                "dados existentes",
                "dados disponiveis",
                "dados disponíveis",
                "o que posso consultar",
                "posso consultar",
                "que telas recomenda",
                "quais telas recomenda",
                "telas recomenda criar",
                "quais apis",
                "quais api",
                "apis de",
                "api de",
                "quais recursos",
                "recursos disponiveis",
                "recursos disponíveis",
                "fontes candidatas");
    }

    boolean isApiCatalogCandidateChoicePrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "quais apis",
                "quais api",
                "apis de",
                "api de",
                "quais recursos",
                "recursos disponiveis",
                "recursos disponíveis",
                "fontes candidatas");
    }

    boolean isBroadAnalyticalCanvasPrompt(String prompt) {
        String normalized = normalize(prompt);
        boolean asksForCanvas = containsAny(normalized,
                "tela", "pagina", "painel", "dashboard", "visao");
        boolean asksForInspection = containsAny(normalized,
                "enxergar", "visualizar", "mostrar", "ver", "entender", "analisar");
        boolean asksForDetail = containsAny(normalized,
                "registros por tras", "registros por trás", "dados por tras", "dados por trás",
                "detalhes por tras", "detalhes por trás", "detalhar", "detalhes", "drill");
        return asksForCanvas && asksForInspection && asksForDetail;
    }

    boolean promptMentionsSpecificCandidateToken(String prompt, AgenticAuthoringCandidate candidate) {
        String normalizedPrompt = normalize(prompt);
        if (normalizedPrompt.isBlank() || candidate == null) {
            return false;
        }
        String candidateText = directCandidateSemanticText(candidate);
        for (String token : candidateText.replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (isGenericAnalyticalCandidateToken(token)) {
                continue;
            }
            if (normalizedPrompt.contains(token)) {
                return true;
            }
            if (token.endsWith("s") && token.length() > 4 && normalizedPrompt.contains(token.substring(0, token.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    boolean isGenericAnalyticalCandidateToken(String token) {
        return token == null
                || token.length() < 4
                || Set.of(
                "api", "post", "get", "path", "filter", "cursor", "stats", "group", "timeseries",
                "distribution", "resource", "metadata", "lexical", "match",
                "broad", "artifact", "discovery", "analytics", "analitica", "analitico",
                "dashboard", "ranking", "rank", "valor", "valores", "maior", "maiores",
                "indicador", "indicadores", "empresa", "business")
                .contains(token);
    }

    List<String> candidatePathIdentityTokens(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return List.of();
        }
        List<String> segments = Stream.of(normalizedPath.split("/"))
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .toList();
        if (segments.isEmpty()) {
            return List.of();
        }
        int firstIdentitySegment = "api".equals(segments.get(0)) && segments.size() > 2 ? 2 : 0;
        return segments.stream()
                .skip(firstIdentitySegment)
                .flatMap(segment -> Stream.of(segment.replaceAll("[^a-z0-9]+", " ").split("\\s+")))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    boolean isBroadArtifactDiscoveryOnly(List<AgenticAuthoringCandidate> candidates) {
        return candidates != null
                && !candidates.isEmpty()
                && candidates.stream()
                .allMatch(candidate -> candidate.evidence() != null
                        && candidate.evidence().contains("broad-artifact-discovery"));
    }

    boolean shouldDetachCurrentTarget(String prompt, String artifactKind, AgenticAuthoringTarget target) {
        String normalized = normalize(prompt);
        if (target != null
                && target.resourcePath() != null
                && !target.resourcePath().isBlank()
                && isDataSourceReplacementPrompt(normalized)
                && !isExplicitSourcePreservePrompt(normalized)) {
            return true;
        }
        if (target == null || !"praxis-dynamic-form".equals(target.componentId())) {
            return false;
        }
        if ("form".equals(artifactKind)) {
            return false;
        }
        return containsAny(normalized,
                "nao quero cadastrar", "nao quero cadastro", "nao era isso", "em vez disso",
                "dashboard", "painel", "indicador", "indicadores",
                "master detail", "master-detail", "mestre detalhe", "mestre-detalhe",
                "lista e detalhe", "lista com detalhe", "abrir detalhes", "abrir detalhe",
                "acompanhar", "consultar", "buscar", "visualizar", "ver detalhes", "ver detalhe");
    }

    boolean isDataSourceReplacementPrompt(String normalizedPrompt) {
        if (normalizedPrompt == null || normalizedPrompt.isBlank()) {
            return false;
        }
        boolean sourceLanguage = containsAny(normalizedPrompt,
                "fonte", "dados", "tabela de", "recurso", "source", "data source");
        boolean explicitSourceReference = !explicitResourcePath(normalizedPrompt).isBlank();
        boolean sourceReplacementIntentLanguage = containsAny(normalizedPrompt,
                "troca", "troque", "mude", "muda", "usar", "usa", "use",
                "deve ser", "precisa ser", "como fonte", "nova fonte", "outra fonte");
        return containsAny(normalizedPrompt,
                "troca a fonte", "troque a fonte", "mude a fonte",
                "outra fonte", "nova fonte", "fonte deve ser", "fonte precisa ser")
                || (sourceLanguage && sourceReplacementIntentLanguage && explicitSourceReference);
    }

    boolean isExplicitSourcePreservePrompt(String normalizedPrompt) {
        return containsAny(normalizedPrompt,
                "mantem os dados", "mantenha os dados", "mantem a fonte", "mantenha a fonte",
                "mantem o recurso", "mantenha o recurso", "mesma fonte", "mesmos dados", "preserve a fonte");
    }

    boolean isAnalyticalComparisonPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "ranking", "rank", "top", "maior", "maiores", "menor", "menores",
                "comparar", "compare", "comparativo", "grupo", "grupos", "categoria", "categorias");
    }

    boolean isOptionalDataSourceHint(String prompt) {
        return containsAny(normalize(prompt),
                "se precisar usa",
                "se precisar, usa",
                "se precisar use",
                "se precisar, use",
                "se precisar usar",
                "se precisar, usar",
                "caso precise usa",
                "caso precise, usa",
                "caso precise use",
                "caso precise, use",
                "caso precise usar",
                "caso precise, usar");
    }

    AgenticAuthoringCandidate candidate(String resourcePath, double score, String reason, String evidence) {
        return candidate(resourcePath, "post", score, reason, evidence);
    }

    AgenticAuthoringCandidate candidate(String resourcePath, String operation, double score, String reason, String evidence) {
        return candidate(resourcePath, resourcePath, operation, score, reason, evidence);
    }

    AgenticAuthoringCandidate candidate(
            String resourcePath,
            String submitUrl,
            String operation,
            double score,
            String reason,
            String evidence) {
        String normalizedOperation = operation.toLowerCase(Locale.ROOT);
        String schemaType = "get".equalsIgnoreCase(operation) || isReadProjectionOperation(submitUrl, operation)
                ? "response"
                : "request";
        String schemaPath = "/schemas/filtered?path=" + submitUrl + "&operation=" + normalizedOperation
                + "&schemaType=" + schemaType;
        return new AgenticAuthoringCandidate(
                resourcePath,
                operation,
                schemaPath,
                submitUrl,
                operation,
                score,
                reason,
                List.of(evidence, "schema-probe-pending", "actions-probe-pending", "capabilities-probe-pending"));
    }

    String authoringProfile(String operationKind, String artifactKind, boolean explicitLocalPageComposition) {
        if ("api_catalog".equals(artifactKind)) {
            return "api-catalog-qa";
        }
        if ("component".equals(artifactKind)
                && ("explore".equals(operationKind) || "explain".equals(operationKind))) {
            return "component-catalog-qa";
        }
        if (explicitLocalPageComposition
                && "page".equals(artifactKind)
                && ("create".equals(operationKind) || "modify".equals(operationKind) || "remove".equals(operationKind))) {
            return "ui-composition-plan@0.1.0";
        }
        if ("form".equals(artifactKind) && ("create".equals(operationKind)
                || "modify".equals(operationKind)
                || "remove".equals(operationKind))) {
            return "create-minimal-form";
        }
        return "generic-page-change";
    }

    AgenticAuthoringGateResult withPromptSpecificGateMessages(
            AgenticAuthoringGateResult gate,
            String rawPrompt,
            String prompt,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringConversationTurn turn) {
        List<String> messages = new ArrayList<>(gate.messages());
        if ("create".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && isOptionalDataSourceHint(prompt)) {
            messages.remove("resource-candidate-ambiguous");
        }
        String status = messages.isEmpty() ? "eligible" : "clarification_required";
        return new AgenticAuthoringGateResult(gate.gateId(), status, List.copyOf(messages));
    }

    AgenticAuthoringGateResult withGateMessage(AgenticAuthoringGateResult gate, String message) {
        if (gate == null || message == null || message.isBlank()) {
            return gate;
        }
        List<String> messages = new ArrayList<>(gate.messages() == null ? List.of() : gate.messages());
        if (!messages.contains(message)) {
            messages.add(message);
        }
        return new AgenticAuthoringGateResult(gate.gateId(), "clarification_required", List.copyOf(messages));
    }

    AgenticAuthoringGateResult withSharedRuleAuthoringGate(
            AgenticAuthoringGateResult gate,
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            boolean llmRequiresGovernedAuthoring,
            String operationKind,
            boolean deterministicFallbackApplied) {
        if (gate == null) {
            return null;
        }
        if (!requiresSharedRuleAuthoring(request, prompt, selectedCandidate, llmRequiresGovernedAuthoring, operationKind, deterministicFallbackApplied)) {
            return gate;
        }
        List<String> messages = new ArrayList<>(gate.messages());
        if (!messages.contains("shared-rule-authoring-required")) {
            messages.add("shared-rule-authoring-required");
        }
        return new AgenticAuthoringGateResult(
                gate.gateId(),
                "route_required",
                List.copyOf(messages));
    }

    AgenticAuthoringGateResult withExplicitLocalUiCompositionGate(
            AgenticAuthoringGateResult gate,
            boolean explicitLocalUiComposition,
            boolean explicitLocalTargetedComposition) {
        if (!explicitLocalUiComposition || gate == null || gate.messages() == null || gate.messages().isEmpty()) {
            return gate;
        }
        List<String> messages = gate.messages().stream()
                .filter(message -> !"resource-candidate-required".equals(message))
                .filter(message -> !"resource-candidate-ambiguous".equals(message))
                .filter(message -> !explicitLocalTargetedComposition || !"target-widget-required".equals(message))
                .toList();
        String status = messages.isEmpty() ? "eligible" : gate.status();
        return new AgenticAuthoringGateResult(gate.gateId(), status, messages);
    }

    AgenticAuthoringGateResult withPrimaryLlmProviderFailureGate(
            AgenticAuthoringGateResult gate,
            boolean primaryLlmIntentProviderFailure) {
        if (!primaryLlmIntentProviderFailure || gate == null) {
            return gate;
        }
        List<String> messages = new ArrayList<>(gate.messages() == null ? List.of() : gate.messages());
        if (!messages.contains("llm-intent-resolution-provider-failed")) {
            messages.add("llm-intent-resolution-provider-failed");
        }
        if (!messages.contains("semantic-intent-confirmation-required")) {
            messages.add("semantic-intent-confirmation-required");
        }
        return new AgenticAuthoringGateResult(gate.gateId(), "clarification_required", List.copyOf(messages));
    }

    boolean hasExplicitLocalComponentTarget(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind) {
        if (request == null) {
            return false;
        }
        if (request.selectedWidgetKey() != null && !request.selectedWidgetKey().isBlank()) {
            return true;
        }
        String targetComponentId = valueOrDefault(request.targetComponentId(), "").trim();
        if (targetComponentId.isBlank() || "praxis-dynamic-page-builder".equals(targetComponentId)) {
            return false;
        }
        return containsAny(normalize(targetComponentId),
                artifactKind,
                "table",
                "tabela",
                "crud",
                "form",
                "formulario",
                "list",
                "lista",
                "component",
                "componente");
    }

    boolean matchesManifestComponentId(String targetComponentId, String catalogComponentId) {
        if (targetComponentId == null || targetComponentId.isBlank()
                || catalogComponentId == null || catalogComponentId.isBlank()) {
            return false;
        }
        return targetComponentId.equals(catalogComponentId)
                || ("praxis-dynamic-page-builder".equals(targetComponentId)
                        && "praxis-page-builder".equals(catalogComponentId));
    }

    boolean isManifestOwnedComponentOperation(
            AgenticAuthoringIntentResolutionRequest request,
            String changeKind,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (request == null
                || request.targetComponentId() == null
                || request.targetComponentId().isBlank()
                || changeKind == null
                || changeKind.isBlank()
                || componentCapabilities == null
                || componentCapabilities.catalogs() == null) {
            return false;
        }
        String targetComponentId = request.targetComponentId().trim();
        return componentCapabilities.catalogs().stream()
                .filter(Objects::nonNull)
                .filter(catalog -> matchesManifestComponentId(targetComponentId, catalog.componentId()))
                .filter(catalog -> catalog.capabilities() != null)
                .flatMap(catalog -> catalog.capabilities().stream())
                .filter(Objects::nonNull)
                .anyMatch(capability -> changeKind.equals(capability.id())
                        || changeKind.equals(capability.changeKind()));
    }

    String explicitLocalTargetedArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String currentArtifactKind) {
        String normalizedComponent = normalize(valueOrDefault(request.targetComponentId(), ""));
        String normalizedPrompt = normalize(prompt);
        if (containsAny(normalizedComponent, "table", "tabela")
                || containsAny(normalizedPrompt, "tabela", "table", "crud", "coluna", "colunas")) {
            return "table";
        }
        if (containsAny(normalizedComponent, "form", "formulario")
                || containsAny(normalizedPrompt, "formulario", "form", "campo", "campos")) {
            return "form";
        }
        if (containsAny(normalizedComponent, "list", "lista")
                || containsAny(normalizedPrompt, "lista", "list", "cards")) {
            return "list";
        }
        if ("form".equals(currentArtifactKind)
                || "table".equals(currentArtifactKind)
                || "dashboard".equals(currentArtifactKind)
                || "page".equals(currentArtifactKind)) {
            return currentArtifactKind;
        }
        return "component";
    }

    boolean requiresSharedRuleAuthoring(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            boolean llmRequiresGovernedAuthoring,
            String operationKind,
            boolean deterministicFallbackApplied) {
        if ("explore".equals(operationKind) || "explain".equals(operationKind) || "unknown".equals(operationKind)) {
            return false;
        }
        String requestedFlow = quickReplySupport.requestedAuthoringFlow(request);
        if ("shared_rule_authoring".equals(requestedFlow) && !isExplicitLocalUiCompositionPrompt(prompt)) {
            return selectedCandidate != null;
        }
        boolean requiresAuthoring = deterministicFallbackApplied
                ? (llmRequiresGovernedAuthoring || isExplicitGovernedBusinessRulePrompt(prompt))
                : llmRequiresGovernedAuthoring;
        return selectedCandidate != null && requiresAuthoring;
    }

    boolean requiresGovernedAuthoring(AgenticAuthoringLlmIntentResolution llmIntent) {
        return llmIntent != null && llmIntent.requiresGovernedAuthoring();
    }

    boolean isExplicitGovernedBusinessRulePrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank() || isExplicitLocalUiCompositionPrompt(prompt)) {
            return false;
        }
        boolean isQuestion = normalized.endsWith("?")
                || normalized.matches("^(por\\s+favor\\s*,?\\s*|gostaria\\s+de\\s+(saber|entender|verificar)\\s+(se\\s+)?|(quero|preciso)\\s+saber\\s+(se\\s+)?|me\\s+diga\\s+(se\\s+)?|(voce\\s+)?sabe\\s+se\\s+|saber\\s+se\\s+)?(como|por\\s+que|porque|existe|existem|ha|há|tem|quais|qual|o\\s+que)\\b.*");
        if (isQuestion) {
            return false;
        }
        boolean isVisualRequest = containsAny(normalized,
                "regra visual", "regras visuais",
                "formatacao visual", "destaque visual",
                "estilo visual", "estilos visuais",
                "cor de fundo", "cor da linha", "cores das linhas",
                "pintar a linha", "pintar as linhas", "pintar de",
                "destacar a linha", "destacar as linhas",
                "estilo", "estilos", "css");
        if (isVisualRequest) {
            return false;
        }
        boolean ruleAuthoring = containsAny(normalized,
                "regra", "regras",
                "politica", "policy",
                "validacao", "validar", "valide",
                "aprovacao", "aprovar",
                "governada", "governado", "governanca", "governance",
                "compliance", "lgpd", "gdpr",
                "elegibilidade", "eligibility");
        boolean businessConstraint = containsAny(normalized,
                "nao pode", "nao deve", "impedir", "impeca",
                "bloqueado", "bloqueada", "blocked",
                "inativo", "inactive",
                "obrigatorio", "obrigatoria", "obrigatorios", "obrigatorias",
                "exigir", "exija", "require",
                "antes de salvar", "antes de seguir",
                "selecionado", "selecionada", "selecionavel", "selecionavel",
                "mascarar", "mascare", "dados sensiveis", "privacidade");
        return ruleAuthoring && businessConstraint;
    }

    boolean isExplicitLocalUiCompositionPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean localDemoIntent = containsAny(normalized,
                "conteudo local",
                "conteudo editorial",
                "editorial local",
                "local/editorial",
                "dados locais",
                "local data",
                "demo",
                "demonstracao",
                "demonstracao",
                "exemplo",
                "exemplos",
                "sem dependencia de api",
                "sem api real",
                "sem schema externo",
                "sem conectar dados",
                "sem conectar api",
                "sem conectar api real",
                "nao conecte api",
                "nao conecte api real",
                "nao conectar api",
                "nao conectar api real",
                "nao use api real",
                "nao usar api real",
                "nao descubra fonte",
                "nao descubra fonte de dados",
                "nao descobrir fonte",
                "nao descobrir fonte de dados",
                "sem regra de negocio",
                "sem criar regra",
                "sem criar regra de negocio",
                "without business rule",
                "without creating business rule");
        boolean uiCompositionIntent = containsAny(normalized,
                "pagina",
                "page",
                "aba",
                "abas",
                "tab",
                "tabs",
                "formulario",
                "form",
                "lista",
                "list",
                "cards",
                "dashboard",
                "widget",
                "componente",
                "component",
                "tabela",
                "table",
                "crud",
                "coluna",
                "colunas",
                "column",
                "columns",
                "acao",
                "acoes",
                "action",
                "actions");
        return localDemoIntent && uiCompositionIntent;
    }

    boolean isExplicitLocalPageCompositionPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean tabbedWorkspaceIntent = containsAny(normalized, "pagina", "page", "aba", "abas", "tab", "tabs")
                && containsAny(normalized, "crud", "registros", "formulario", "form", "relacionamento", "relacionamentos",
                "comentario", "comentarios", "lista", "list", "cards");
        boolean refinementIntent = containsAny(normalized,
                "refine", "refinar", "adicione", "adicionar", "inclua", "incluir",
                "ajuste", "ajustar", "preencha", "preencher", "complete", "completar",
                "corrija", "corrigir", "preserve", "preservar", "manter", "mantendo", "continue");
        return tabbedWorkspaceIntent && (refinementIntent || containsAny(normalized, "pagina", "page"));
    }

    boolean isMaterializablePageCompositionOperation(String operationKind) {
        return "create".equals(operationKind) || "modify".equals(operationKind) || "remove".equals(operationKind);
    }

    String materializablePageCompositionOperation(String fallbackOperationKind, String prompt) {
        String normalized = normalize(prompt);
        if (containsAny(normalized, "refine", "refinar", "adicione", "adicionar", "inclua", "incluir",
                "ajuste", "ajustar", "preencha", "preencher", "complete", "completar",
                "corrija", "corrigir", "preserve", "preservar", "manter", "mantendo", "continue")) {
            return "modify";
        }
        if (isMaterializablePageCompositionOperation(fallbackOperationKind)) {
            return fallbackOperationKind;
        }
        return "create";
    }

    String materializablePageCompositionChangeKind(
            String changeKind,
            String operationKind,
            String prompt) {
        if ("answer_api_catalog_question".equals(changeKind)) {
            return "create".equals(operationKind) ? "create_artifact" : "unknown";
        }
        if ("unknown".equals(changeKind) && "create".equals(operationKind)
                && isExplicitLocalPageCompositionPrompt(prompt)) {
            return "create_artifact";
        }
        return changeKind;
    }

    boolean shouldSuppressLlmAssistantMessageForExplicitLocalComposition(
            boolean explicitLocalUiComposition,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        return explicitLocalUiComposition
                && llmIntent != null
                && "api_catalog".equals(valueOrUnknown(llmIntent.artifactKind()));
    }

    boolean isConfirmedDataSourceSelection(String prompt) {
        return containsAny(prompt, "fonte confirmada", "source confirmed", "data source", "usar vw-", "use vw-",
                "usar /api/", "use /api/", "recurso confirmado", "api confirmada");
    }

    boolean isBareConfirmationPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean materializationRequest = containsAny(normalized,
                "preview",
                "previa",
                "pre visualizacao",
                "materializar",
                "materialize");
        boolean generationVerb = containsAny(normalized, "gere", "gerar", "generate");
        boolean newInstruction = containsAny(normalized,
                "crie",
                "criar",
                "adicione",
                "adicionar",
                "altere",
                "alterar",
                "remova",
                "remover",
                "monte",
                "montar",
                "create",
                "add",
                "change",
                "remove",
                "build")
                || (generationVerb && !materializationRequest);
        if (newInstruction) {
            return false;
        }
        return containsAny(normalized,
                "sim",
                "confirmo",
                "confirmado",
                "confirmed",
                "ok",
                "siga",
                "seguir",
                "pode seguir",
                "pode fazer",
                "fazer agora",
                "materializar",
                "materialize",
                "faca isso",
                "faça isso");
    }

    String normalizeTargetlessCreationChangeKind(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringTarget target) {
        if (target == null
                && "modify".equals(operationKind)
                && "dashboard".equals(artifactKind)
                && Set.of("add_component", "add_dashboard_component", "add_widget").contains(changeKind)) {
            return "add_dashboard_widget";
        }
        if (target != null || !"create".equals(operationKind) || !isCreatableArtifactKind(artifactKind)) {
            return changeKind;
        }
        if ("form".equals(artifactKind)) {
            return "create_artifact";
        }
        if (Set.of("author_component", "materialize", "materialize_component").contains(changeKind)) {
            return "create_artifact";
        }
        if (containsAny(changeKind, "add_field", "add_column", "add_filter", "modify_field", "update_field")) {
            return "create_artifact";
        }
        return changeKind;
    }

    boolean isCreatableArtifactKind(String artifactKind) {
        return "dashboard".equals(artifactKind)
                || "table".equals(artifactKind)
                || "form".equals(artifactKind)
                || "page".equals(artifactKind);
    }

    boolean shouldExposeAsChartArtifact(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        if (!"create".equals(operationKind) || !"dashboard".equals(artifactKind)) {
            return false;
        }
        if (llmSingleChartDecisionRejectedForOpenOperationalPrompt(prompt, llmIntent)) {
            return false;
        }
        if ("create_chart".equals(changeKind)) {
            return true;
        }
        String normalized = normalize(prompt);
        if (containsAny(normalized, "tipo um painel", "tipo painel")
                && !containsAny(normalized, "dashboard")
                && !containsAny(normalized, "tabela", "formulario", "formulário", "lista", "listagem")) {
            return true;
        }
        AgenticAuthoringVisualizationDecision visualizationDecision =
                llmIntent == null ? null : llmIntent.visualizationDecision();
        if (visualizationDecision != null
                && shouldTrustVisualizationDecisionForArtifact(normalized, visualizationDecision)
                && "single_chart".equals(valueOrDefault(visualizationDecision.layoutKind(), ""))
                && "praxis-chart".equals(valueOrDefault(visualizationDecision.primaryComponent(), ""))) {
            return true;
        }
        return containsAny(normalized, "apenas", "somente", "so ", "só ")
                && containsAny(normalized, "grafico", "graficos", "chart", "charts");
    }

    boolean llmSingleChartDecisionRejectedForOpenOperationalPrompt(
            String prompt,
            AgenticAuthoringLlmIntentResolution llmIntent) {
        AgenticAuthoringVisualizationDecision decision =
                llmIntent == null ? null : llmIntent.visualizationDecision();
        return decision != null
                && isSingleChartDecision(decision)
                && !shouldTrustVisualizationDecisionForArtifact(prompt, decision);
    }

    boolean shouldTrustVisualizationDecisionForArtifact(
            String prompt,
            AgenticAuthoringVisualizationDecision decision) {
        if (decision == null || !isSingleChartDecision(decision)) {
            return true;
        }
        return hasExplicitAnalyticalVisualizationIntent(prompt);
    }

    boolean isSingleChartDecision(AgenticAuthoringVisualizationDecision decision) {
        return decision != null
                && "single_chart".equals(valueOrDefault(decision.layoutKind(), ""))
                && "praxis-chart".equals(valueOrDefault(decision.primaryComponent(), ""));
    }

    boolean hasExplicitAnalyticalVisualizationIntent(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "grafico", "graficos", "gráfico", "gráficos", "chart", "charts",
                "indicador", "indicadores", "kpi", "kpis", "metrica", "metricas",
                "métrica", "métricas", "comparar", "comparacao", "comparação",
                "ranking", "rank", "tendencia", "tendência", "distribuicao", "distribuição",
                "serie temporal", "série temporal", "maiores", "menores", "media", "média",
                "evolucao", "evolução", "progressao", "progressão", "trajetoria", "trajetória",
                "ao longo do tempo", "variacao", "variação",
                "total", "totais", "soma", "agregado", "agregada", "agrupado", "agrupada");
    }

    boolean hasPageWidgets(JsonNode currentPage) {
        return currentPage != null
                && currentPage.path("widgets").isArray()
                && !currentPage.path("widgets").isEmpty();
    }

    boolean shouldIsolateConversationHistoryForBlankCreate(
            AgenticAuthoringIntentResolutionRequest request,
            String operationKind,
            String artifactKind,
            String changeKind) {
        if (request == null
                || request.conversationMessages() == null
                || request.conversationMessages().isEmpty()
                || request.pendingClarification() != null
                || hasBlockingActiveSemanticDecision(request)
                || hasPageWidgets(request.currentPage())
                || !valueOrDefault(request.selectedWidgetKey(), "").isBlank()
                || !"create".equals(valueOrDefault(operationKind, ""))
                || !List.of("page", "dashboard", "table", "form", "chart").contains(valueOrDefault(artifactKind, ""))
                || !isMaterializableAuthoringIntent(operationKind, artifactKind, changeKind)) {
            return false;
        }
        return true;
    }

    boolean hasBlockingActiveSemanticDecision(AgenticAuthoringIntentResolutionRequest request) {
        AgenticAuthoringSemanticDecision decision = request == null ? null : request.activeSemanticDecision();
        if (decision == null) {
            return false;
        }
        String constraintSource = decision.constraints() == null
                ? ""
                : decision.constraints().path("source").asText("").trim();
        if ("server-issued-quick-reply".equalsIgnoreCase(constraintSource)) {
            return true;
        }
        String resourcePath = decision.selectedResource() == null
                ? ""
                : valueOrDefault(decision.selectedResource().resourcePath(), "").trim();
        if (resourcePath.isBlank()) {
            return false;
        }
        String operation = valueOrDefault(decision.operationKind(), "").trim().toLowerCase(Locale.ROOT);
        String artifact = valueOrDefault(decision.artifactKind(), "").trim().toLowerCase(Locale.ROOT);
        String change = valueOrDefault(decision.changeKind(), "").trim().toLowerCase(Locale.ROOT);
        if ("explore".equals(operation)
                || "explain".equals(operation)
                || "api_catalog".equals(artifact)
                || "unknown".equals(artifact)
                || change.startsWith("answer_")
                || "api_catalog_followup".equals(change)) {
            return false;
        }
        String normalizedPrompt = normalize(valueOrDefault(request.userPrompt(), ""));
        return !(isDataSourceReplacementPrompt(normalizedPrompt) && !isExplicitSourcePreservePrompt(normalizedPrompt));
    }

    AgenticAuthoringIntentResolutionRequest withoutConversationMessages(
            AgenticAuthoringIntentResolutionRequest request) {
        if (request == null) {
            return null;
        }
        return new AgenticAuthoringIntentResolutionRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                List.of(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                request.contextHints(),
                request.activeSemanticDecision());
    }

    AgenticAuthoringCandidate promptAlignedBusinessCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .map(candidate -> new CandidatePromptAlignment(
                        candidate,
                        promptCandidateAlignmentScore(prompt, candidate)))
                .filter(alignment -> alignment.score() > 0)
                .filter(alignment -> canUsePromptAlignedCandidate(alignment.candidate(), candidates))
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .map(CandidatePromptAlignment::candidate)
                .orElse(null);
    }

    AgenticAuthoringCandidate promptAlignedDirectSemanticCandidate(
            String prompt,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .map(candidate -> new CandidatePromptAlignment(
                        candidate,
                        directPromptCandidateAlignmentScore(prompt, candidate)))
                .filter(alignment -> alignment.score() >= 4)
                .filter(alignment -> canUsePromptAlignedCandidate(alignment.candidate(), candidates))
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .map(CandidatePromptAlignment::candidate)
                .orElse(null);
    }

    boolean isDedicatedProjectionCandidate(AgenticAuthoringCandidate candidate, String projectionRole) {
        if (candidate == null || projectionRole == null || projectionRole.isBlank()) {
            return false;
        }
        String candidatePath = normalizePath(candidate.resourcePath()).toLowerCase(Locale.ROOT);
        String submitPath = normalizePath(candidate.submitUrl()).toLowerCase(Locale.ROOT);
        boolean roleSpecificProjectionIdentity = SEMANTIC_ROLE_ANALYTICS_PROJECTION.equals(projectionRole)
                && (containsAny(candidatePath, "/analytics", "-analytics", "/analitica", "-analitica")
                || containsAny(submitPath, "/analytics", "-analytics", "/analitica", "-analitica"))
                || SEMANTIC_ROLE_PROFILE_PROJECTION.equals(projectionRole)
                && (containsAny(candidatePath, "/profile", "-profile", "/perfil", "-perfil")
                || containsAny(submitPath, "/profile", "-profile", "/perfil", "-perfil"));
        return hasEvidence(candidate, projectionRole)
                && (isDerivedProjectionCandidate(candidate) || roleSpecificProjectionIdentity);
    }

    boolean isMixedOperationalProjectionCandidate(AgenticAuthoringCandidate candidate, String projectionRole) {
        return candidate != null
                && projectionRole != null
                && !projectionRole.isBlank()
                && hasEvidence(candidate, projectionRole)
                && !isDedicatedProjectionCandidate(candidate, projectionRole)
                && (hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                || isStatsProjectionOperation(candidate));
    }

    boolean isWeakLexicalCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (hasStrongGroundingEvidence(candidate)
                || hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                || hasEvidence(candidate, "semantic-retrieval")
                || hasEvidence(candidate, "schema-available")) {
            return false;
        }
        if (hasEvidence(candidate, "lexical-fallback") || hasEvidence(candidate, "weak-evidence")) {
            return true;
        }
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        return bundle != null
                && "lexical_fallback".equals(valueOrDefault(bundle.retrievalSource(), ""));
    }

    boolean hasTrustedSelectionEvidence(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        return hasStrongGroundingEvidence(candidate)
                || hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)
                || hasEvidence(candidate, "semantic-retrieval")
                || hasEvidence(candidate, "tool-search-api-resources")
                || hasEvidence(candidate, "explicit-source-match")
                || hasEvidence(candidate, "explicit-resource-path")
                || hasEvidence(candidate, "quick-reply-context")
                || hasEvidence(candidate, "current-page");
    }

    boolean hasStrongGroundingEvidence(AgenticAuthoringCandidate candidate) {
        if (candidate == null || candidate.evidenceBundle() == null) {
            return hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING);
        }
        if (hasEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING)) {
            return true;
        }
        AgenticAuthoringEvidenceBundle bundle = candidate.evidenceBundle();
        if ("lexical_fallback".equals(valueOrDefault(bundle.retrievalSource(), ""))) {
            return false;
        }
        double strongestEvidence = bundle.evidence().stream()
                .mapToDouble(AgenticAuthoringEvidenceBundle.Evidence::confidence)
                .max()
                .orElse(0d);
        boolean structuralEvidence = bundle.evidence().stream()
                .map(AgenticAuthoringEvidenceBundle.Evidence::kind)
                .map(kind -> valueOrDefault(kind, ""))
                .anyMatch(kind -> kind.equals("schema_grounding")
                        || kind.equals("domain_catalog_grounding")
                        || kind.equals("operation_grounding")
                        || kind.equals("resource_capability_hint")
                        || kind.equals("retrieved_candidate"));
        return strongestEvidence >= 0.62d && structuralEvidence;
    }

    String explicitResourcePath(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern
                .compile("(?i)(/api/[a-z0-9][a-z0-9_./-]*)")
                .matcher(prompt);
        return matcher.find() ? normalizeExplicitApiPath(matcher.group(1)) : "";
    }

    String explicitSubmitUrl(String prompt, String resourcePath) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern
                .compile("(?i)\\b(?:via|submitUrl|submit url)\\s+(/api/[a-z0-9][a-z0-9_./-]*)")
                .matcher(prompt);
        if (!matcher.find()) {
            return "";
        }
        String submitUrl = normalizeExplicitApiPath(matcher.group(1));
        String normalizedResourcePath = normalizePath(resourcePath);
        return !normalizedResourcePath.isBlank() && submitUrl.startsWith(normalizedResourcePath + "/")
                ? submitUrl
                : "";
    }

    String normalizeExplicitApiPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return normalizePath(value.replaceAll("[.,;:!?]+$", ""));
    }

    boolean isBareDomainPrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank() || !explicitResourcePath(normalized).isBlank()) {
            return false;
        }
        String[] tokens = normalized.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        return tokens.length <= 2;
    }

    boolean isMaterializableAuthoringIntent(
            String operationKind,
            String artifactKind,
            String changeKind) {
        if (!("create".equals(operationKind) || "modify".equals(operationKind))) {
            return false;
        }
        if (!List.of("page", "table", "form", "dashboard", "chart", "component").contains(artifactKind)) {
            return false;
        }
        String normalizedChangeKind = valueOrDefault(changeKind, "");
        return !normalizedChangeKind.startsWith("answer_")
                && !"presentation_affordance_choice".equals(normalizedChangeKind);
    }

    boolean sameCandidate(AgenticAuthoringCandidate left, AgenticAuthoringCandidate right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizePath(left.resourcePath()).equals(normalizePath(right.resourcePath()))
                || normalizePath(left.submitUrl()).equals(normalizePath(right.submitUrl()));
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null && candidate.evidence() != null && candidate.evidence().contains(evidence);
    }

    private boolean isReadProjectionOperation(String submitUrl, String operation) {
        return AgenticAuthoringTextNormalizationSupport.isReadProjectionOperation(submitUrl, operation);
    }

    private boolean containsAny(String value, String... tokens) {
        return AgenticAuthoringTextNormalizationSupport.containsAny(value, tokens);
    }

    private String normalize(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalize(value);
    }

    private String normalizePath(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalizePath(value);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return AgenticAuthoringTextNormalizationSupport.valueOrDefault(value, defaultValue);
    }

    private String valueOrUnknown(String value) {
        return AgenticAuthoringTextNormalizationSupport.valueOrUnknown(value);
    }
}
