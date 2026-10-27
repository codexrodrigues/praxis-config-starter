package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentCandidateScoringSupport.SemanticResourceNeed;

/**
 * Encapsulates context-hint target/candidate hydration, explicit resource-path extraction,
 * operational resource-discovery lead evaluation, and LLM candidate selection/reconciliation for
 * {@link AgenticAuthoringIntentResolverService}.
 */
final class AgenticAuthoringIntentContextCandidateSupport {

    private static final double DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE =
            AgenticAuthoringProjectionRoleResolutionSupport.DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE;
    private static final String SEMANTIC_ROLE_OPERATIONAL_RESOURCE =
            AgenticAuthoringIntentCandidateScoringSupport.SEMANTIC_ROLE_OPERATIONAL_RESOURCE;
    private static final String SEMANTIC_ROLE_ANALYTICS_PROJECTION =
            AgenticAuthoringIntentCandidateScoringSupport.SEMANTIC_ROLE_ANALYTICS_PROJECTION;
    private static final String SEMANTIC_ROLE_PROFILE_PROJECTION =
            AgenticAuthoringIntentCandidateScoringSupport.SEMANTIC_ROLE_PROFILE_PROJECTION;

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog;
    private final AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer;
    private final AgenticAuthoringIntentCandidateScoringSupport candidateScoringSupport;
    private final AgenticAuthoringProjectionRoleResolutionSupport projectionRoleResolutionSupport;
    private final AgenticAuthoringCandidateDiscoveryTelemetrySupport candidateDiscoveryTelemetrySupport;

    AgenticAuthoringIntentContextCandidateSupport(
            ObjectMapper objectMapper,
            AgenticAuthoringApiMetadataCandidateCatalog apiMetadataCandidateCatalog,
            AgenticAuthoringDomainCatalogCandidateEnhancer domainCatalogCandidateEnhancer,
            AgenticAuthoringIntentCandidateScoringSupport candidateScoringSupport,
            AgenticAuthoringProjectionRoleResolutionSupport projectionRoleResolutionSupport,
            AgenticAuthoringCandidateDiscoveryTelemetrySupport candidateDiscoveryTelemetrySupport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.apiMetadataCandidateCatalog = apiMetadataCandidateCatalog;
        this.domainCatalogCandidateEnhancer = domainCatalogCandidateEnhancer;
        this.candidateScoringSupport = Objects.requireNonNull(
                candidateScoringSupport, "candidateScoringSupport must not be null");
        this.projectionRoleResolutionSupport = Objects.requireNonNull(
                projectionRoleResolutionSupport, "projectionRoleResolutionSupport must not be null");
        this.candidateDiscoveryTelemetrySupport = Objects.requireNonNull(
                candidateDiscoveryTelemetrySupport, "candidateDiscoveryTelemetrySupport must not be null");
    }

    boolean hasMaterializableResourceDiscoveryContext(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode resourceDiscovery = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("resourceDiscovery");
        if (resourceDiscovery == null || !resourceDiscovery.isObject()) {
            return false;
        }
        String artifactKind = valueOrUnknown(resourceDiscovery.path("artifactKind").asText(""));
        return List.of("page", "table", "dashboard", "form", "chart").contains(artifactKind)
                && resourceDiscovery.path("candidates").isArray()
                && !resourceDiscovery.path("candidates").isEmpty();
    }

    boolean hasBusinessDataAuthoringSignal(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt) {
        return isBusinessDataAuthoringPrompt(prompt)
                || hasLlmAuthoredMaterializableResourceFocus(request);
    }

    boolean isBusinessDataAuthoringPrompt(String prompt) {
        String normalized = normalize(prompt);
        boolean hasIntent = containsAny(normalized,
                "quero",
                "queria",
                "preciso",
                "gostaria",
                "crie",
                "criar",
                "monte",
                "montar",
                "gere",
                "gerar",
                "ver",
                "visualizar",
                "acompanhar",
                "mostrar",
                "mostre");
        boolean asksForDataSurface = containsAny(normalized,
                "tela",
                "pagina",
                "page",
                "visao",
                "visualizacao",
                "view",
                "painel",
                "dashboard",
                "tabela",
                "lista",
                "formulario",
                "mesa",
                "bancada",
                "workbench",
                "dados",
                "informacoes",
                "status",
                "detalhes",
                "registros",
                "como esta",
                "visualizar",
                "acompanhar",
                "mostrar",
                "mostre");
        return hasIntent && asksForDataSurface;
    }

    boolean hasLlmAuthoredMaterializableResourceFocus(AgenticAuthoringIntentResolutionRequest request) {
        if (!hasMaterializableResourceDiscoveryContext(request)) {
            return false;
        }
        AgenticAuthoringResourceSearchFocus focus =
                projectionRoleResolutionSupport.resourceDiscoverySearchFocus(request);
        if (focus == null || focus.isEmpty()) {
            return false;
        }
        String desiredSurface = normalize(focus.desiredSurface());
        String primaryBusinessEntity = normalize(focus.primaryBusinessEntity());
        return !desiredSurface.isBlank() && !primaryBusinessEntity.isBlank()
                || focus.supportingConcepts() != null && focus.supportingConcepts().size() >= 2;
    }

    String materializableResourceDiscoveryArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String fallback) {
        JsonNode resourceDiscovery = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("resourceDiscovery");
        String artifactKind = resourceDiscovery == null
                ? ""
                : valueOrUnknown(resourceDiscovery.path("artifactKind").asText(""));
        return List.of("page", "table", "dashboard", "form", "chart").contains(artifactKind)
                ? artifactKind
                : fallback;
    }

    boolean hasResourceDiscoveryCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate candidate) {
        if (request == null || candidate == null || request.contextHints() == null) {
            return false;
        }
        JsonNode candidates = request.contextHints().path("resourceDiscovery").path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            return false;
        }
        String resourcePath = normalizePath(candidate.resourcePath());
        String submitUrl = normalizePath(candidate.submitUrl());
        for (JsonNode node : candidates) {
            String candidateResourcePath = normalizePath(node.path("resourcePath").asText(""));
            String candidateSubmitUrl = normalizePath(node.path("submitUrl").asText(""));
            if (!resourcePath.isBlank() && resourcePath.equals(candidateResourcePath)
                    || !submitUrl.isBlank() && submitUrl.equals(candidateSubmitUrl)) {
                return true;
            }
        }
        return false;
    }

    AgenticAuthoringCandidate singleGovernedArtifactCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringPreIntentToolPlan semanticOrientation) {
        boolean resourcePage = "page".equals(artifactKind);
        if (!(List.of("form", "table", "dashboard").contains(artifactKind) || resourcePage)
                || request == null
                || candidates == null
                || candidates.isEmpty()
                || !hasLlmAuthoredMaterializableResourceFocus(request)) {
            return null;
        }
        List<AgenticAuthoringCandidate> eligible = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasResourceDiscoveryCandidate(request, candidate))
                .filter(candidate -> hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                        || hasVerifiedOperationalBindingEvidence(candidate))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .filter(candidate -> isCanonicalArtifactEndpointCandidate(artifactKind, candidate)
                        || resourcePage && isCanonicalCompactResourceCandidate(candidate))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .toList();
        Map<String, List<AgenticAuthoringCandidate>> candidatesByResource = eligible.stream()
                .collect(Collectors.groupingBy(
                        candidate -> normalizePath(candidate.resourcePath()),
                        LinkedHashMap::new,
                        Collectors.toList()));
        if (candidatesByResource.size() != 1) {
            return null;
        }
        List<AgenticAuthoringCandidate> resourceCandidates = candidatesByResource.values().iterator().next();
        if (resourcePage) {
            return resourceCandidates.stream()
                    .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                    .findFirst()
                    .orElse(null);
        }
        return resourceCandidates.stream()
                .sorted(Comparator
                        .comparing((AgenticAuthoringCandidate candidate) ->
                                normalizePath(candidate.submitUrl()).endsWith("/filter"))
                        .thenComparingDouble(AgenticAuthoringCandidate::score)
                        .reversed())
                .findFirst()
                .orElse(null);
    }

    boolean isCanonicalCompactResourceCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null || !hasVerifiedOperationalBindingEvidence(candidate)) {
            return false;
        }
        String resourcePath = normalizePath(candidate.resourcePath());
        String submitUrl = normalizePath(valueOrDefault(candidate.submitUrl(), resourcePath));
        return !resourcePath.isBlank()
                && !resourcePath.contains("{")
                && !valueOrDefault(candidate.schemaUrl(), "").isBlank()
                && (submitUrl.equals(resourcePath) || submitUrl.startsWith(resourcePath + "/"));
    }

    boolean hasVerifiedOperationalBindingEvidence(AgenticAuthoringCandidate candidate) {
        return hasEvidence(candidate, "domain-binding")
                && hasEvidence(candidate, "schema-grounding-verified")
                && hasEvidence(candidate, "resource-capabilities-verified");
    }

    boolean isCanonicalArtifactEndpointCandidate(
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        if ("form".equals(artifactKind)) {
            return isCreateEndpointCandidate(candidate);
        }
        if (!("table".equals(artifactKind) || "dashboard".equals(artifactKind)) || candidate == null) {
            return false;
        }
        String operation = valueOrDefault(candidate.operation(), candidate.submitMethod());
        String submitMethod = valueOrDefault(candidate.submitMethod(), operation);
        String submitUrl = normalizePath(candidate.submitUrl());
        boolean canonicalFilteredCollection = "post".equalsIgnoreCase(operation)
                && "post".equalsIgnoreCase(submitMethod)
                && (submitUrl.endsWith("/filter") || submitUrl.endsWith("/filter/cursor"));
        return canonicalFilteredCollection
                && (!"dashboard".equals(artifactKind)
                        || hasEvidence(candidate, "stats-capabilities-verified"));
    }

    AgenticAuthoringCandidate strongLlmAuthoredOperationalResourceDiscoveryLead(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (request == null
                || candidates == null
                || candidates.isEmpty()
                || !hasLlmAuthoredMaterializableResourceFocus(request)
                || projectionRoleResolutionSupport.governedResourceDiscoveryNeed(request, prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL) {
            return null;
        }
        List<AgenticAuthoringCandidate> operationalCandidates = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasResourceDiscoveryCandidate(request, candidate)
                        || hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidate -> hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidate -> hasEvidence(candidate, "semantic-retrieval"))
                .filter(candidate -> hasEvidence(candidate, "schema-available"))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                .toList();
        if (operationalCandidates.isEmpty()) {
            return null;
        }
        AgenticAuthoringCandidate lead = operationalCandidates.get(0);
        if (lead.score() < 0.58d) {
            return null;
        }
        if (operationalCandidates.size() == 1) {
            return lead.score() >= 0.60d ? lead : null;
        }
        AgenticAuthoringCandidate second = operationalCandidates.get(1);
        AgenticAuthoringResourceSearchFocus focus =
                projectionRoleResolutionSupport.resourceDiscoverySearchFocus(request);
        int leadFocusScore = projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, lead);
        int secondFocusScore = projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, second);
        boolean scoreLead = lead.score() >= second.score() + 0.025d;
        boolean focusLead = leadFocusScore >= secondFocusScore + 2;
        boolean sameNamespaceLead = sameGovernedResourceNamespace(lead, second)
                && lead.score() >= second.score() + 0.015d;
        return scoreLead || focusLead || sameNamespaceLead ? lead : null;
    }

    boolean hasSafeFocusedResourceDiscoveryLead(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate focusedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (hasSafeFocusedProjectionResourceDiscoveryLead(
                request,
                prompt,
                artifactKind,
                focusedCandidate,
                candidates)) {
            return true;
        }
        if (candidateScoringSupport.isDerivedProjectionCandidate(focusedCandidate)
                && !hasEvidence(focusedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)) {
            return false;
        }
        if (projectionRoleResolutionSupport.governedResourceDiscoveryNeed(request, prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL) {
            return false;
        }
        if (candidateScoringSupport.sameCandidate(
                semanticRetrievalOperationalLeadCandidate(request, prompt, artifactKind, candidates),
                focusedCandidate)) {
            return true;
        }
        List<AgenticAuthoringCandidate> operationalCandidates = distinctGovernedOperationalCandidates(candidates);
        if (operationalCandidates.size() == 1) {
            return candidateScoringSupport.sameCandidate(operationalCandidates.get(0), focusedCandidate);
        }
        if (operationalCandidates.isEmpty() || focusedCandidate == null) {
            return false;
        }
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        boolean derivedProjectionRanksAboveFocused = usableCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidateScoringSupport::isDerivedProjectionCandidate)
                .anyMatch(candidate -> candidate.score() >= focusedCandidate.score() + 0.02d);
        if (derivedProjectionRanksAboveFocused) {
            return false;
        }
        boolean focusedHasOperationalLead = operationalCandidates.stream()
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, focusedCandidate))
                .allMatch(candidate -> focusedCandidate.score() >= candidate.score() + 0.015d);
        boolean focusedHasSemanticFocusLead = hasSemanticFocusLead(
                request,
                focusedCandidate,
                operationalCandidates);
        boolean groundedByFocus =
                projectionRoleResolutionSupport.isGroundedByResourceDiscoveryFocus(request, focusedCandidate, candidates);
        return groundedByFocus && (focusedHasOperationalLead || focusedHasSemanticFocusLead);
    }

    AgenticAuthoringCandidate semanticRetrievalOperationalLeadCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (request == null
                || candidates == null
                || candidates.isEmpty()
                || !hasLlmAuthoredMaterializableResourceFocus(request)
                || projectionRoleResolutionSupport.governedResourceDiscoveryNeed(request, prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL) {
            return null;
        }
        List<AgenticAuthoringCandidate> operationalCandidates = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidate -> hasEvidence(candidate, "semantic-retrieval"))
                .filter(candidate -> hasEvidence(candidate, "schema-available"))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .sorted(Comparator.comparingDouble(AgenticAuthoringCandidate::score).reversed())
                .toList();
        if (operationalCandidates.isEmpty()) {
            return null;
        }
        AgenticAuthoringCandidate top = operationalCandidates.get(0);
        if (top.score() < 0.53d) {
            return null;
        }
        if (operationalCandidates.size() == 1) {
            return top.score() >= 0.55d ? top : null;
        }
        AgenticAuthoringCandidate second = operationalCandidates.get(1);
        if (top.score() >= second.score() + 0.055d) {
            return top;
        }
        return hasCoherentSemanticRetrievalClusterLead(
                request,
                top,
                second,
                operationalCandidates)
                ? top
                : null;
    }

    private boolean hasCoherentSemanticRetrievalClusterLead(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate top,
            AgenticAuthoringCandidate second,
            List<AgenticAuthoringCandidate> operationalCandidates) {
        if (top == null
                || second == null
                || operationalCandidates == null
                || operationalCandidates.isEmpty()
                || !sameGovernedResourceNamespace(top, second)) {
            return false;
        }
        AgenticAuthoringResourceSearchFocus focus =
                projectionRoleResolutionSupport.resourceDiscoverySearchFocus(request);
        boolean focusBacksTop = hasLlmAuthoredMaterializableResourceFocus(request)
                && focus != null
                && !focus.isEmpty()
                && projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, top) >= 5
                && projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, top)
                >= projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, second) + 3;
        if (top.score() < (focusBacksTop ? 0.53d : 0.58d)) {
            return false;
        }
        if (top.score() < second.score() + (focusBacksTop ? 0.005d : 0.025d)) {
            return false;
        }
        if (focus != null
                && !focus.isEmpty()
                && projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, top)
                < projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, second) - 2) {
            return false;
        }
        return operationalCandidates.stream()
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, top))
                .allMatch(candidate -> sameGovernedResourceNamespace(top, candidate)
                        || top.score() >= candidate.score() + 0.08d);
    }

    private boolean sameGovernedResourceNamespace(
            AgenticAuthoringCandidate left,
            AgenticAuthoringCandidate right) {
        String leftNamespace = governedResourceNamespace(left);
        String rightNamespace = governedResourceNamespace(right);
        return !leftNamespace.isBlank() && leftNamespace.equals(rightNamespace);
    }

    private String governedResourceNamespace(AgenticAuthoringCandidate candidate) {
        String path = normalizePath(candidate == null ? "" : candidate.resourcePath());
        if (path.isBlank()) {
            return "";
        }
        String[] parts = path.replaceAll("^/+", "").split("/");
        if (parts.length < 2) {
            return "";
        }
        return parts[0] + "/" + parts[1];
    }

    private boolean hasSafeFocusedProjectionResourceDiscoveryLead(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate focusedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (focusedCandidate == null
                || candidates == null
                || candidates.isEmpty()
                || "chart".equals(valueOrDefault(artifactKind, ""))) {
            return false;
        }
        SemanticResourceNeed effectiveNeed =
                projectionRoleResolutionSupport.governedResourceDiscoveryNeed(request, prompt, artifactKind);
        String expectedRole = switch (effectiveNeed) {
            case PROFILE -> SEMANTIC_ROLE_PROFILE_PROJECTION;
            case ANALYTICS -> SEMANTIC_ROLE_ANALYTICS_PROJECTION;
            default -> "";
        };
        if (expectedRole.isBlank() || !hasEvidence(focusedCandidate, expectedRole)) {
            return false;
        }
        AgenticAuthoringResourceSearchFocus focus =
                projectionRoleResolutionSupport.resourceDiscoverySearchFocus(request);
        int focusedAlignment = projectionRoleResolutionSupport.projectionResourceDiscoveryAlignmentScore(
                request,
                focus,
                focusedCandidate);
        boolean dedicatedProjectionSemanticLead = hasDedicatedProjectionSemanticScoreLead(
                focusedCandidate,
                expectedRole,
                candidates);
        if (focusedAlignment < 8 && !dedicatedProjectionSemanticLead) {
            return false;
        }
        List<AgenticAuthoringCandidate> sameRoleCompetitors = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, focusedCandidate))
                .filter(candidate -> hasEvidence(candidate, expectedRole))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .toList();
        if (sameRoleCompetitors.isEmpty()) {
            return true;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, focusedCandidate))
                .filter(candidate -> hasEvidence(candidate, expectedRole))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .allMatch(candidate -> focusedCandidate.score() >= candidate.score() + 0.04d
                        || focusedAlignment >= projectionRoleResolutionSupport.projectionResourceDiscoveryAlignmentScore(
                                request,
                                focus,
                                candidate) + 3);
    }

    private boolean hasDedicatedProjectionSemanticScoreLead(
            AgenticAuthoringCandidate focusedCandidate,
            String expectedRole,
            List<AgenticAuthoringCandidate> candidates) {
        if (focusedCandidate == null
                || expectedRole == null
                || expectedRole.isBlank()
                || candidates == null
                || candidates.isEmpty()
                || !projectionRoleResolutionSupport.isDedicatedProjectionCandidate(focusedCandidate, expectedRole)
                || !hasEvidence(focusedCandidate, "semantic-retrieval")
                || !candidateDiscoveryTelemetrySupport.hasTrustedSelectionEvidence(focusedCandidate)
                || candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(focusedCandidate)) {
            return false;
        }
        List<AgenticAuthoringCandidate> mixedProjectionCompetitors = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, focusedCandidate))
                .filter(candidate -> hasEvidence(candidate, expectedRole))
                .filter(candidate -> projectionRoleResolutionSupport.isMixedOperationalProjectionCandidate(
                        candidate, expectedRole))
                .filter(candidate -> hasEvidence(candidate, "semantic-retrieval"))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .toList();
        return !mixedProjectionCompetitors.isEmpty()
                && mixedProjectionCompetitors.stream()
                        .allMatch(candidate -> focusedCandidate.score() >= candidate.score() + 0.06d);
    }

    private boolean hasSemanticFocusLead(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate focusedCandidate,
            List<AgenticAuthoringCandidate> operationalCandidates) {
        AgenticAuthoringResourceSearchFocus focus =
                projectionRoleResolutionSupport.resourceDiscoverySearchFocus(request);
        if (focus == null
                || focus.isEmpty()
                || focusedCandidate == null
                || operationalCandidates == null
                || operationalCandidates.isEmpty()
                || !hasEvidence(focusedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                || candidateScoringSupport.isDerivedProjectionCandidate(focusedCandidate)) {
            return false;
        }
        int focusedAlignment = projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, focusedCandidate);
        if (focusedAlignment < 5) {
            return false;
        }
        return operationalCandidates.stream()
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, focusedCandidate))
                .allMatch(candidate -> focusedAlignment
                        >= projectionRoleResolutionSupport.resourceSearchFocusAlignmentScore(focus, candidate) + 3);
    }

    List<AgenticAuthoringCandidate> distinctGovernedOperationalCandidates(
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, AgenticAuthoringCandidate> values = new LinkedHashMap<>();
        for (AgenticAuthoringCandidate candidate : candidates) {
            if (candidate == null
                    || !candidateDiscoveryTelemetrySupport.hasTrustedSelectionEvidence(candidate)
                    || candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate)
                    || candidateScoringSupport.isDerivedProjectionCandidate(candidate)
                    || !hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)) {
                continue;
            }
            String key = normalizePath(candidate.resourcePath());
            if (!key.isBlank()) {
                values.putIfAbsent(key, candidate);
            }
        }
        return List.copyOf(values.values());
    }

    String preIntentGovernedEvidenceAssistantMessage(
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        String resource = candidate == null ? "" : valueOrDefault(candidate.resourcePath(), "");
        String surface = switch (artifactKind) {
            case "table" -> "uma tabela";
            case "form" -> "um formulario";
            default -> "uma pagina";
        };
        return resource.isBlank()
                ? "Vou criar " + surface + " usando a fonte governada encontrada."
                : "Vou criar " + surface + " usando a fonte governada " + resource + ".";
    }

    String effectiveSelectedWidgetKey(AgenticAuthoringIntentResolutionRequest request) {
        String selectedWidgetKey = request == null ? "" : valueOrDefault(request.selectedWidgetKey(), "");
        if (!selectedWidgetKey.isBlank()) {
            return selectedWidgetKey;
        }
        JsonNode contextHints = request == null ? null : request.contextHints();
        String contextualSelection = valueOrDefault(jsonText(contextHints, "selectedWidgetKey"), "");
        if (!contextualSelection.isBlank()) {
            return contextualSelection;
        }
        return valueOrDefault(jsonText(contextHints, "targetWidgetKey"), "");
    }

    boolean isContextualPreviewAction(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        String source = jsonText(contextHints, "source");
        String kind = jsonText(contextHints, "kind");
        String changeKind = jsonText(contextHints, "changeKind");
        return "component-capability-catalog".equals(source)
                || "contextual-preview-action".equals(kind)
                || (!valueOrDefault(changeKind, "").isBlank()
                && !valueOrDefault(jsonText(contextHints, "targetComponentId"), "").isBlank());
    }

    AgenticAuthoringTarget contextualPreviewTarget(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringTarget currentTarget,
            AgenticAuthoringCandidate contextHintCandidate) {
        if (!isContextualPreviewAction(request)) {
            return currentTarget;
        }
        String componentId = valueOrDefault(jsonText(request.contextHints(), "targetComponentId"), "");
        if (componentId.isBlank()) {
            componentId = valueOrDefault(jsonText(request.contextHints(), "selectedComponentId"), "");
        }
        if (componentId.isBlank()) {
            componentId = currentTarget == null ? "" : valueOrDefault(currentTarget.componentId(), "");
        }
        if (componentId.isBlank()) {
            return currentTarget;
        }
        String widgetKey = valueOrDefault(jsonText(request.contextHints(), "targetWidgetKey"), "");
        if (widgetKey.isBlank()) {
            widgetKey = valueOrDefault(jsonText(request.contextHints(), "selectedWidgetKey"), "");
        }
        if (widgetKey.isBlank() && currentTarget != null) {
            widgetKey = valueOrDefault(currentTarget.widgetKey(), "");
        }
        boolean hasHintedWidgetKey = !widgetKey.isBlank()
                && !widgetKey.equals(valueOrDefault(currentTarget == null ? "" : currentTarget.widgetKey(), ""));
        if (!hasHintedWidgetKey
                && currentTarget != null
                && !valueOrDefault(currentTarget.widgetKey(), "").isBlank()
                && componentId.equals(valueOrDefault(currentTarget.componentId(), componentId))) {
            return currentTarget;
        }
        String resourcePath = firstNonBlank(
                currentTarget == null ? "" : currentTarget.resourcePath(),
                contextHintCandidate == null ? "" : contextHintCandidate.resourcePath(),
                jsonText(request.contextHints(), "resourcePath"));
        String schemaUrl = firstNonBlank(
                currentTarget == null ? "" : currentTarget.schemaUrl(),
                contextHintCandidate == null ? "" : contextHintCandidate.schemaUrl(),
                jsonText(request.contextHints(), "schemaUrl"));
        String submitUrl = firstNonBlank(
                currentTarget == null ? "" : currentTarget.submitUrl(),
                contextHintCandidate == null ? "" : contextHintCandidate.submitUrl(),
                jsonText(request.contextHints(), "submitUrl"),
                resourcePath);
        String submitMethod = firstNonBlank(
                currentTarget == null ? "" : currentTarget.submitMethod(),
                contextHintCandidate == null ? "" : contextHintCandidate.submitMethod(),
                contextHintCandidate == null ? "" : contextHintCandidate.operation(),
                jsonText(request.contextHints(), "submitMethod"),
                jsonText(request.contextHints(), "operation"),
                "get");
        return new AgenticAuthoringTarget(
                widgetKey,
                componentId,
                resourcePath,
                schemaUrl,
                submitUrl,
                submitMethod);
    }

    String contextualPreviewOperationKind(AgenticAuthoringIntentResolutionRequest request) {
        String operationKind = valueOrDefault(jsonText(request == null ? null : request.contextHints(), "operationKind"), "");
        if (!operationKind.isBlank() && !"explore".equals(operationKind) && !"explain".equals(operationKind)) {
            return operationKind;
        }
        return "modify";
    }

    String contextualPreviewArtifactKind(
            AgenticAuthoringIntentResolutionRequest request,
            String fallbackArtifactKind) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        String hintedArtifact = valueOrDefault(jsonText(contextHints, "artifactKind"), "");
        if (!hintedArtifact.isBlank() && !"unknown".equals(hintedArtifact)) {
            return hintedArtifact;
        }
        String componentId = valueOrDefault(jsonText(contextHints, "targetComponentId"), "");
        if (componentId.contains("chart")) {
            return "chart";
        }
        if (componentId.contains("table")) {
            return "table";
        }
        if (componentId.contains("form")) {
            return "form";
        }
        return valueOrDefault(fallbackArtifactKind, "dashboard");
    }

    boolean isGovernedResourceConfirmation(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringConversationTurn turn) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        String prompt = normalize(String.join(" ",
                request == null ? "" : valueOrDefault(request.userPrompt(), ""),
                turn == null ? "" : valueOrDefault(turn.effectivePrompt(), "")));
        String hintedResourcePath = contextHints != null && contextHints.isObject()
                ? jsonText(contextHints, "resourcePath")
                : "";
        if (hintedResourcePath.isBlank() && explicitResourcePath(prompt).isBlank()) {
            return false;
        }
        return prompt.contains("confirmed:")
                || prompt.contains("confirmado:")
                || prompt.contains("confirmada:")
                || prompt.contains("confirmo")
                || containsAny(
                prompt,
                "gerar previa",
                "gerar pre visualizacao");
    }

    AgenticAuthoringCandidate contextHintCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            String tenantId,
            String environment) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        if (contextHints == null || !contextHints.isObject()) {
            return null;
        }
        String resourcePath = jsonText(contextHints, "resourcePath");
        JsonNode domainCatalogHint = contextHints.path("domainCatalog");
        String domainCatalogResourceKey = normalizeDomainCatalogResourceKey(jsonText(domainCatalogHint, "resourceKey"));
        boolean domainCatalogResourceHint = false;
        boolean derivedFromDomainCatalogHint = false;
        if (resourcePath.isBlank()) {
            resourcePath = resourcePathFromDomainCatalogResourceKey(domainCatalogResourceKey);
            domainCatalogResourceHint = !resourcePath.isBlank();
            derivedFromDomainCatalogHint = domainCatalogResourceHint;
        } else {
            domainCatalogResourceHint = !domainCatalogResourceKey.isBlank()
                    && resourcePath.equals(resourcePathFromDomainCatalogResourceKey(domainCatalogResourceKey));
        }
        if (resourcePath.isBlank()) {
            return null;
        }
        String operation = valueOrDefault(jsonText(contextHints, "operation"), jsonText(contextHints, "submitMethod"));
        if (operation.isBlank() && domainCatalogHint != null && domainCatalogHint.isObject()) {
            operation = valueOrDefault(jsonText(domainCatalogHint, "operation"), jsonText(domainCatalogHint, "submitMethod"));
        }
        if (operation.isBlank()) {
            operation = "form".equals(artifactKind)
                    || "table".equals(artifactKind)
                    || "dashboard".equals(artifactKind)
                    || "page".equals(artifactKind)
                    ? "post"
                    : "get";
        }
        String submitUrl = canonicalContextSubmitUrl(
                resourcePath,
                valueOrDefault(jsonText(contextHints, "submitUrl"), resourcePath),
                operation,
                artifactKind);
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        String normalizedResourcePath = normalizePath(resourcePath);
        String normalizedSubmitUrl = normalizePath(submitUrl);
        Optional<AgenticAuthoringCandidate> existing = usableCandidates.stream()
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizedSubmitUrl.equals(normalizePath(candidate.submitUrl())))
                .findFirst();
        if (existing.isPresent()) {
            return domainCatalogResourceHint && verifiedDomainCatalogResourceKey(domainCatalogResourceKey, tenantId, environment)
                    ? verifiedDomainCatalogContextCandidate(
                            domainCatalogResourceKey,
                            existing.get(),
                            artifactKind,
                            tenantId,
                            environment)
                    : withEvidence(existing.get(), "quick-reply-context");
        }
        if (derivedFromDomainCatalogHint) {
            AgenticAuthoringCandidate verifiedApiCandidate = verifiedApiMetadataCandidate(
                    resourcePath,
                    artifactKind,
                    tenantId,
                    environment);
            return verifiedDomainCatalogContextCandidate(
                    domainCatalogResourceKey,
                    verifiedApiCandidate,
                    artifactKind,
                    tenantId,
                    environment);
        }
        return candidateScoringSupport.candidate(
                normalizePath(resourcePath),
                submitUrl,
                operation,
                1.0d,
                "resource selected from assistant quick reply context",
                "quick-reply-context");
    }

    private AgenticAuthoringCandidate verifiedDomainCatalogContextCandidate(
            String resourceKey,
            AgenticAuthoringCandidate candidate,
            String artifactKind,
            String tenantId,
            String environment) {
        if (candidate == null
                || !verifiedDomainCatalogResourceKey(resourceKey, tenantId, environment)) {
            return null;
        }
        return withDomainCatalogContextEvidence(candidate, resourceKey);
    }

    private boolean verifiedDomainCatalogResourceKey(String resourceKey, String tenantId, String environment) {
        return domainCatalogCandidateEnhancer != null
                && domainCatalogCandidateEnhancer.hasResourceKey(resourceKey, tenantId, environment);
    }

    private AgenticAuthoringCandidate verifiedApiMetadataCandidate(
            String resourcePath,
            String artifactKind,
            String tenantId,
            String environment) {
        String normalizedResourcePath = normalizePath(resourcePath);
        if (normalizedResourcePath.isBlank() || apiMetadataCandidateCatalog == null) {
            return null;
        }
        String query = normalize(normalizedResourcePath
                .replaceFirst("^/api/", "")
                .replace('/', ' ')
                .replace('-', ' '));
        return apiMetadataCandidateCatalog.discover(query, artifactKind, tenantId, environment, null).stream()
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizePath(candidate.submitUrl()).startsWith(normalizedResourcePath + "/"))
                .findFirst()
                .orElse(null);
    }

    private AgenticAuthoringCandidate withDomainCatalogContextEvidence(
            AgenticAuthoringCandidate candidate,
            String resourceKey) {
        AgenticAuthoringCandidate grounded = withEvidence(
                withEvidence(candidate, AgenticAuthoringDomainCatalogCandidateEnhancer.DOMAIN_CATALOG_GROUNDING),
                "domain-catalog-context");
        if (grounded == null
                || (grounded.evidenceBundle() != null
                && AgenticAuthoringCandidateProvenancePolicy.DOMAIN_CATALOG
                .equals(grounded.evidenceBundle().retrievalSource()))) {
            return grounded;
        }
        return new AgenticAuthoringCandidate(
                grounded.resourcePath(),
                grounded.operation(),
                grounded.schemaUrl(),
                grounded.submitUrl(),
                grounded.submitMethod(),
                grounded.score(),
                grounded.reason(),
                grounded.evidence(),
                AgenticAuthoringEvidenceBundle.of(
                        AgenticAuthoringCandidateProvenancePolicy.DOMAIN_CATALOG,
                        List.of(new AgenticAuthoringEvidenceBundle.Evidence(
                                AgenticAuthoringCandidateProvenancePolicy.DOMAIN_CATALOG,
                                "domain_catalog_context_hint",
                                valueOrDefault(resourceKey, grounded.resourcePath()),
                                "Governed domain catalog context hint selected this resource.",
                                0.96d,
                                List.of(valueOrDefault(resourceKey, grounded.resourcePath())),
                                "",
                                "",
                                ""))));
    }

    AgenticAuthoringCandidate withEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        if (candidate == null || evidence == null || evidence.isBlank()) {
            return candidate;
        }
        List<String> currentEvidence = candidate.evidence() == null ? List.of() : candidate.evidence();
        if (currentEvidence.contains(evidence)) {
            return candidate;
        }
        List<String> mergedEvidence = new ArrayList<>(currentEvidence);
        mergedEvidence.add(evidence);
        return new AgenticAuthoringCandidate(
                candidate.resourcePath(),
                candidate.operation(),
                candidate.schemaUrl(),
                candidate.submitUrl(),
                candidate.submitMethod(),
                candidate.score(),
                candidate.reason(),
                List.copyOf(mergedEvidence),
                candidate.evidenceBundle());
    }

    List<AgenticAuthoringCandidate> withContextHintCandidates(
            AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> contextCandidates = contextHintCandidates(request);
        if (contextCandidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        List<AgenticAuthoringCandidate> merged = new ArrayList<>(candidates == null ? List.of() : candidates);
        merged.addAll(contextCandidates);
        return candidateDiscoveryTelemetrySupport.deduplicateCandidates(merged);
    }

    List<AgenticAuthoringCandidate> contextHintCandidates(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode contextHints = request == null ? null : request.contextHints();
        JsonNode candidateNodes = contextHints == null ? null : contextHints.path("resourceDiscovery").path("candidates");
        if (candidateNodes == null || !candidateNodes.isArray() || candidateNodes.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> candidates = new ArrayList<>();
        for (JsonNode candidateNode : candidateNodes) {
            if (candidateNode == null || !candidateNode.isObject()) {
                continue;
            }
            String resourcePath = jsonText(candidateNode, "resourcePath");
            if (resourcePath.isBlank()) {
                continue;
            }
            String operation = valueOrDefault(jsonText(candidateNode, "operation"), jsonText(candidateNode, "submitMethod"));
            if (operation.isBlank()) {
                operation = "post";
            }
            double score = candidateNode.path("score").isNumber() ? candidateNode.path("score").asDouble() : 1.0d;
            String reason = valueOrDefault(jsonText(candidateNode, "reason"), "resource discovered by backend tool");
            List<String> evidence = new ArrayList<>();
            JsonNode evidenceNode = candidateNode.path("evidence");
            if (evidenceNode.isArray()) {
                evidenceNode.forEach(item -> {
                    if (item != null && item.isTextual() && !item.asText().isBlank()) {
                        evidence.add(item.asText());
                    }
                });
            }
            evidence.add("tool-search-api-resources");
            AgenticAuthoringEvidenceBundle evidenceBundle = evidenceBundle(candidateNode.path("evidenceBundle"));
            // A verified projection already identifies its operation and schema. Artifact
            // defaults must not turn a create binding into a collection read (or fill a
            // read-only binding's deliberately absent mutation endpoint).
            AgenticAuthoringCandidate rawProjection = new AgenticAuthoringCandidate(
                    normalizePath(resourcePath),
                    jsonText(candidateNode, "operation"),
                    nullableCandidateText(candidateNode, "schemaUrl"),
                    nullableCandidateText(candidateNode, "submitUrl"),
                    nullableCandidateText(candidateNode, "submitMethod"),
                    score,
                    reason,
                    List.copyOf(evidence),
                    evidenceBundle);
            boolean verifiedProjection =
                    candidateDiscoveryTelemetrySupport.verifiedOperationForCandidate(request, rawProjection) != null;
            String submitMethod = verifiedProjection
                    ? nullableCandidateText(candidateNode, "submitMethod")
                    : valueOrDefault(jsonText(candidateNode, "submitMethod"), operation);
            String submitUrl = verifiedProjection
                    ? nullableCandidateText(candidateNode, "submitUrl")
                    : canonicalContextSubmitUrl(
                            resourcePath,
                            valueOrDefault(jsonText(candidateNode, "submitUrl"), resourcePath),
                            submitMethod,
                            artifactKindFromContextCandidate(candidateNode, request));
            String schemaUrl = verifiedProjection
                    ? nullableCandidateText(candidateNode, "schemaUrl")
                    : canonicalContextSchemaUrl(jsonText(candidateNode, "schemaUrl"), submitUrl, submitMethod);
            candidates.add(new AgenticAuthoringCandidate(
                    normalizePath(resourcePath),
                    operation,
                    schemaUrl,
                    submitUrl,
                    submitMethod,
                    score,
                    reason,
                    List.copyOf(evidence),
                    evidenceBundle));
        }
        return candidateDiscoveryTelemetrySupport.deduplicateCandidates(candidates);
    }

    private String nullableCandidateText(JsonNode candidate, String field) {
        return candidate.path(field).isTextual() ? candidate.path(field).asText() : null;
    }

    private AgenticAuthoringEvidenceBundle evidenceBundle(JsonNode evidenceBundleNode) {
        if (evidenceBundleNode == null || evidenceBundleNode.isMissingNode() || evidenceBundleNode.isNull()) {
            return null;
        }
        try {
            return objectMapper.convertValue(evidenceBundleNode, AgenticAuthoringEvidenceBundle.class);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String artifactKindFromContextCandidate(
            JsonNode candidateNode,
            AgenticAuthoringIntentResolutionRequest request) {
        String artifactKind = valueOrDefault(jsonText(candidateNode, "artifactKind"), "");
        if (!artifactKind.isBlank()) {
            return artifactKind;
        }
        JsonNode contextHints = request == null ? null : request.contextHints();
        artifactKind = jsonText(
                contextHints == null ? null : contextHints.path("resourceDiscovery"),
                "artifactKind");
        if (!artifactKind.isBlank()) {
            return artifactKind;
        }
        return valueOrUnknown(jsonText(contextHints, "artifactKind"));
    }

    private String normalizeDomainCatalogResourceKey(String resourceKey) {
        String normalized = valueOrDefault(resourceKey, "").trim();
        if (normalized.startsWith("/api/")) {
            normalized = normalized.substring("/api/".length()).replace('/', '.');
        }
        return normalized.replaceAll("^\\.+|\\.+$", "");
    }

    private String resourcePathFromDomainCatalogResourceKey(String resourceKey) {
        String normalizedResourceKey = normalizeDomainCatalogResourceKey(resourceKey);
        if (normalizedResourceKey.isBlank()) {
            return "";
        }
        return normalizePath("/api/" + normalizedResourceKey.replace('.', '/'));
    }

    private String canonicalContextSubmitUrl(
            String resourcePath,
            String submitUrl,
            String operation,
            String artifactKind) {
        String normalizedResourcePath = normalizePath(resourcePath);
        String normalizedSubmitUrl = normalizePath(valueOrDefault(submitUrl, normalizedResourcePath));
        String normalizedArtifactKind = valueOrUnknown(artifactKind);
        if (List.of("page", "table", "unknown").contains(normalizedArtifactKind)
                && "post".equalsIgnoreCase(valueOrDefault(operation, "post"))) {
            if (normalizedSubmitUrl.endsWith("/filter")) {
                return normalizedSubmitUrl + "/cursor";
            }
            if (normalizedSubmitUrl.equals(normalizedResourcePath)) {
                return normalizedSubmitUrl + "/filter/cursor";
            }
        }
        if ("dashboard".equals(normalizedArtifactKind)
                && normalizedSubmitUrl.equals(normalizedResourcePath)
                && !normalizedSubmitUrl.contains("/{")) {
            return normalizedSubmitUrl + "/stats/group-by";
        }
        return normalizedSubmitUrl;
    }

    String canonicalContextSchemaUrl(String schemaUrl, String submitUrl, String operation) {
        String normalizedSubmitUrl = normalizePath(submitUrl);
        if (!valueOrDefault(schemaUrl, "").isBlank()
                && schemaUrl.contains("path=" + normalizedSubmitUrl + "&operation=" + valueOrDefault(operation, "post"))) {
            return schemaUrl;
        }
        String normalizedOperation = valueOrDefault(operation, "post").toLowerCase(Locale.ROOT);
        String schemaType = "get".equalsIgnoreCase(normalizedOperation)
                || isReadProjectionOperation(normalizedSubmitUrl, normalizedOperation)
                ? "response"
                : "request";
        return "/schemas/filtered?path=" + normalizedSubmitUrl + "&operation=" + normalizedOperation
                + "&schemaType=" + schemaType;
    }

    List<AgenticAuthoringCandidate> withPriorityCandidate(
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate priorityCandidate) {
        if (priorityCandidate == null) {
            return candidates == null ? List.of() : candidates;
        }
        List<AgenticAuthoringCandidate> next = new ArrayList<>();
        next.add(priorityCandidate);
        if (candidates != null) {
            next.addAll(candidates);
        }
        return candidateDiscoveryTelemetrySupport.deduplicateCandidates(next);
    }

    AgenticAuthoringCandidate selectContextHintCandidate(
            AgenticAuthoringCandidate contextHintCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback) {
        if (contextHintCandidate == null) {
            return fallback;
        }
        String normalizedResourcePath = normalizePath(contextHintCandidate.resourcePath());
        String normalizedSubmitUrl = normalizePath(contextHintCandidate.submitUrl());
        return (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath()))
                        || normalizedSubmitUrl.equals(normalizePath(candidate.submitUrl())))
                .findFirst()
                .orElse(contextHintCandidate);
    }

    AgenticAuthoringCandidate selectLlmCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback,
            String artifactKind,
            String prompt) {
        String selectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (selectedResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return fallback;
        }
        AgenticAuthoringCandidate llmCandidate = candidates.stream()
                .filter(candidate -> selectedResourcePath.equals(candidate.resourcePath()))
                .findFirst()
                .orElse(fallback);
        AgenticAuthoringCandidate strongerGovernedCandidate =
                strongerGovernedOperationalCandidateForLlmSelection(prompt, artifactKind, llmCandidate, candidates);
        if (strongerGovernedCandidate != null) {
            return strongerGovernedCandidate;
        }
        AgenticAuthoringCandidate dedicatedProjectionCandidate =
                projectionRoleResolutionSupport.strongerDedicatedProjectionCandidateForGovernedNeed(
                        request,
                        prompt,
                        artifactKind,
                        llmCandidate,
                        candidates);
        if (dedicatedProjectionCandidate != null) {
            return dedicatedProjectionCandidate;
        }
        AgenticAuthoringCandidate governedOperationalCandidate =
                governedOperationalCandidateForGenericNeed(prompt, artifactKind, candidates);
        if (governedOperationalCandidate != null
                && llmCandidate != null
                && !candidateScoringSupport.sameCandidate(governedOperationalCandidate, llmCandidate)
                && projectionRoleResolutionSupport.hasRoleProjectionMismatchForGenericOperation(llmCandidate)
                && governedOperationalCandidate.score() >= llmCandidate.score() + 0.08d
                && !candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, llmCandidate)
                && !candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, llmCandidate)) {
            return governedOperationalCandidate;
        }
        AgenticAuthoringCandidate promptAlignedCandidate = firstNonNullCandidate(
                candidateScoringSupport.promptAlignedBusinessCandidate(prompt, candidates),
                candidateScoringSupport.promptAlignedDirectSemanticCandidate(prompt, candidates));
        boolean llmCandidateIsWeakOrBroad = candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(llmCandidate)
                || hasEvidence(llmCandidate, "broad-artifact-discovery")
                || !candidateDiscoveryTelemetrySupport.hasTrustedSelectionEvidence(llmCandidate);
        boolean promptAlignedCandidateHasMateriallyBetterEvidence =
                hasMateriallyBetterPromptAlignment(prompt, promptAlignedCandidate, llmCandidate);
        if (promptAlignedCandidate != null
                && llmCandidate != null
                && !candidateScoringSupport.sameCandidate(promptAlignedCandidate, llmCandidate)
                && (llmCandidateIsWeakOrBroad
                || promptAlignedCandidate.score() >= llmCandidate.score() + 0.08d
                || promptAlignedCandidateHasMateriallyBetterEvidence)
                && !candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, llmCandidate)) {
            return promptAlignedCandidate;
        }
        return llmCandidate;
    }

    private AgenticAuthoringCandidate firstNonNullCandidate(
            AgenticAuthoringCandidate first,
            AgenticAuthoringCandidate second) {
        return first != null ? first : second;
    }

    private AgenticAuthoringCandidate governedOperationalCandidateForGenericNeed(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidateScoringSupport.semanticResourceNeed(prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL
                || candidates == null
                || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .filter(candidate -> hasEvidence(candidate, "tool-search-api-resources"))
                .findFirst()
                .orElse(null);
    }

    private boolean hasMateriallyBetterPromptAlignment(
            String prompt,
            AgenticAuthoringCandidate promptAlignedCandidate,
            AgenticAuthoringCandidate llmCandidate) {
        if (promptAlignedCandidate == null
                || llmCandidate == null
                || candidateScoringSupport.sameCandidate(promptAlignedCandidate, llmCandidate)
                || !candidateDiscoveryTelemetrySupport.hasTrustedSelectionEvidence(promptAlignedCandidate)
                || candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(promptAlignedCandidate)
                || promptAlignedCandidate.score() < 0.75d) {
            return false;
        }
        int alignedScore = candidateScoringSupport.promptCandidateAlignmentScore(prompt, promptAlignedCandidate);
        int llmScore = candidateScoringSupport.promptCandidateAlignmentScore(prompt, llmCandidate);
        return alignedScore >= 4 && alignedScore >= llmScore + 4;
    }

    boolean llmResourceSelectionOverriddenByPromptAlignment(
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate selectedCandidate,
            String artifactKind,
            String prompt) {
        String selectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (selectedResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        AgenticAuthoringCandidate llmCandidate = candidates.stream()
                .filter(candidate -> selectedResourcePath.equals(candidate.resourcePath()))
                .findFirst()
                .orElse(null);
        if (llmCandidate == null
                || selectedCandidate == null
                || candidateScoringSupport.sameCandidate(llmCandidate, selectedCandidate)) {
            return false;
        }
        AgenticAuthoringCandidate strongerGovernedCandidate =
                strongerGovernedOperationalCandidateForLlmSelection(prompt, artifactKind, llmCandidate, candidates);
        if (strongerGovernedCandidate != null
                && candidateScoringSupport.sameCandidate(strongerGovernedCandidate, selectedCandidate)) {
            return false;
        }
        AgenticAuthoringCandidate finalGovernedCandidate =
                strongerGovernedOperationalCandidateForFinalSelection(prompt, artifactKind, selectedCandidate, candidates);
        if (finalGovernedCandidate != null
                && candidateScoringSupport.sameCandidate(finalGovernedCandidate, selectedCandidate)) {
            return false;
        }
        return candidateScoringSupport.promptAlignedBusinessCandidate(prompt, candidates) != null
                && !candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, llmCandidate)
                && ("dashboard".equals(artifactKind) || "page".equals(artifactKind) || "table".equals(artifactKind));
    }

    boolean llmResourceSelectionOverriddenByGovernedRanking(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringLlmIntentResolution llmIntent,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate selectedCandidate,
            String artifactKind,
            String prompt) {
        String selectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (selectedResourcePath.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        AgenticAuthoringCandidate llmCandidate = candidates.stream()
                .filter(candidate -> selectedResourcePath.equals(candidate.resourcePath()))
                .findFirst()
                .orElse(null);
        if (llmCandidate == null
                || selectedCandidate == null
                || candidateScoringSupport.sameCandidate(llmCandidate, selectedCandidate)) {
            return false;
        }
        AgenticAuthoringCandidate strongerGovernedCandidate =
                strongerGovernedOperationalCandidateForLlmSelection(prompt, artifactKind, llmCandidate, candidates);
        if (strongerGovernedCandidate != null
                && candidateScoringSupport.sameCandidate(strongerGovernedCandidate, selectedCandidate)) {
            return true;
        }
        AgenticAuthoringCandidate finalGovernedCandidate =
                strongerGovernedOperationalCandidateForFinalSelection(prompt, artifactKind, selectedCandidate, candidates);
        if (finalGovernedCandidate != null
                && candidateScoringSupport.sameCandidate(finalGovernedCandidate, selectedCandidate)) {
            return true;
        }
        AgenticAuthoringCandidate dedicatedProjectionCandidate =
                projectionRoleResolutionSupport.strongerDedicatedProjectionCandidateForGovernedNeed(
                        request,
                        prompt,
                        artifactKind,
                        llmCandidate,
                        candidates);
        return dedicatedProjectionCandidate != null
                && candidateScoringSupport.sameCandidate(dedicatedProjectionCandidate, selectedCandidate);
    }

    AgenticAuthoringCandidate strongerGovernedOperationalCandidateForLlmSelection(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate llmCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidateScoringSupport.semanticResourceNeed(prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL
                || llmCandidate == null
                || candidates == null
                || candidates.isEmpty()
                || hasEvidence(llmCandidate, "explicit-resource-path")
                || hasEvidence(llmCandidate, "quick-reply-context")
                || hasEvidence(llmCandidate, "current-page-target-resource")
                || candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, llmCandidate)
                || candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, llmCandidate)) {
            return null;
        }
        AgenticAuthoringCandidate governedCandidate = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, llmCandidate))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .findFirst()
                .orElse(null);
        if (governedCandidate == null) {
            return null;
        }
        boolean selectedIsWeakOrBroad = candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(llmCandidate)
                || hasEvidence(llmCandidate, "broad-artifact-discovery")
                && !hasEvidence(llmCandidate, "tool-search-api-resources")
                || !candidateDiscoveryTelemetrySupport.hasTrustedSelectionEvidence(llmCandidate);
        boolean selectedHasRoleMismatch =
                projectionRoleResolutionSupport.hasRoleProjectionMismatchForGenericOperation(llmCandidate);
        boolean meaningfulScoreGap = governedCandidate.score() >= llmCandidate.score() + 0.08d;
        boolean governedIsRankedAhead = candidates.indexOf(governedCandidate) >= 0
                && candidates.indexOf(llmCandidate) >= 0
                && candidates.indexOf(governedCandidate) < candidates.indexOf(llmCandidate);
        boolean governedHasStrongerToolEvidence = (governedIsRankedAhead
                || governedCandidate.score() >= llmCandidate.score() - 0.03d)
                && hasEvidence(governedCandidate, "tool-search-api-resources")
                && !hasEvidence(llmCandidate, "tool-search-api-resources")
                && !selectedHasRoleMismatch;
        boolean governedHasBetterRankedToolEvidence = governedIsRankedAhead
                && hasEvidence(governedCandidate, "tool-search-api-resources")
                && hasEvidence(llmCandidate, "tool-search-api-resources")
                && governedCandidate.score() >= llmCandidate.score() + 0.015d
                && !selectedHasRoleMismatch;
        return selectedIsWeakOrBroad || meaningfulScoreGap || selectedHasRoleMismatch && meaningfulScoreGap
                || governedHasStrongerToolEvidence
                || governedHasBetterRankedToolEvidence
                ? governedCandidate
                : null;
    }

    AgenticAuthoringCandidate strongerGovernedOperationalCandidateForFinalSelection(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (candidateScoringSupport.semanticResourceNeed(prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL
                || selectedCandidate == null
                || candidates == null
                || candidates.isEmpty()
                || hasEvidence(selectedCandidate, "explicit-resource-path")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "current-page-target-resource")) {
            return null;
        }
        AgenticAuthoringCandidate governedCandidate = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, selectedCandidate))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .filter(candidate -> hasEvidence(candidate, "semantic-retrieval")
                        || hasEvidence(candidate, "tool-search-api-resources"))
                .findFirst()
                .orElse(null);
        if (governedCandidate == null) {
            return null;
        }
        boolean selectedIsWeakOrBroad = candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(selectedCandidate)
                || hasEvidence(selectedCandidate, "broad-artifact-discovery")
                && !hasEvidence(selectedCandidate, "tool-search-api-resources")
                || !candidateDiscoveryTelemetrySupport.hasTrustedSelectionEvidence(selectedCandidate);
        boolean selectedHasRoleMismatch =
                projectionRoleResolutionSupport.hasRoleProjectionMismatchForGenericOperation(selectedCandidate);
        boolean meaningfulScoreGap = governedCandidate.score() >= selectedCandidate.score() + 0.08d;
        boolean governedIsRankedAhead = candidates.indexOf(governedCandidate) >= 0
                && candidates.indexOf(selectedCandidate) >= 0
                && candidates.indexOf(governedCandidate) < candidates.indexOf(selectedCandidate);
        boolean governedHasStrongerToolEvidence = (governedIsRankedAhead
                || governedCandidate.score() >= selectedCandidate.score() - 0.03d)
                && hasEvidence(governedCandidate, "tool-search-api-resources")
                && !hasEvidence(selectedCandidate, "tool-search-api-resources")
                && !selectedHasRoleMismatch;
        boolean governedHasBetterRankedToolEvidence = governedIsRankedAhead
                && hasEvidence(governedCandidate, "tool-search-api-resources")
                && hasEvidence(selectedCandidate, "tool-search-api-resources")
                && governedCandidate.score() >= selectedCandidate.score() + 0.015d
                && !selectedHasRoleMismatch;
        return selectedIsWeakOrBroad
                || selectedHasRoleMismatch && (governedIsRankedAhead || meaningfulScoreGap)
                || meaningfulScoreGap && governedIsRankedAhead
                || governedHasStrongerToolEvidence
                || governedHasBetterRankedToolEvidence
                ? governedCandidate
                : null;
    }

    AgenticAuthoringCandidate materializableResourceDiscoveryGovernedOperationalCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (request == null
                || selectedCandidate == null
                || candidates == null
                || candidates.isEmpty()
                || !hasMaterializableResourceDiscoveryContext(request)
                || !hasBusinessDataAuthoringSignal(request, prompt)
                || hasEvidence(selectedCandidate, "explicit-resource-path")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "current-page-target-resource")
                || candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, selectedCandidate)
                || !projectionRoleResolutionSupport.hasRoleProjectionMismatchForGenericOperation(selectedCandidate)) {
            return null;
        }
        String materializableArtifactKind = materializableResourceDiscoveryArtifactKind(request, artifactKind);
        if (projectionRoleResolutionSupport.governedResourceDiscoveryNeed(request, prompt, materializableArtifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL) {
            return null;
        }
        AgenticAuthoringCandidate focusedCandidate =
                projectionRoleResolutionSupport.resourceDiscoveryFocusedCandidate(
                        request,
                        prompt,
                        materializableArtifactKind,
                        candidates);
        AgenticAuthoringCandidate governedCandidate = focusedCandidate == null
                ? candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, selectedCandidate))
                .filter(candidate -> hasResourceDiscoveryCandidate(request, candidate)
                        || hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .findFirst()
                .orElse(null)
                : focusedCandidate;
        if (governedCandidate == null || candidateScoringSupport.sameCandidate(governedCandidate, selectedCandidate)) {
            return null;
        }
        boolean governedIsRankedAhead = candidates.indexOf(governedCandidate) >= 0
                && candidates.indexOf(selectedCandidate) >= 0
                && candidates.indexOf(governedCandidate) < candidates.indexOf(selectedCandidate);
        boolean meaningfulScoreGap = governedCandidate.score() >= selectedCandidate.score() + 0.08d;
        boolean governedHasFocusLead =
                projectionRoleResolutionSupport.isGroundedByResourceDiscoveryFocus(request, governedCandidate, candidates)
                || projectionRoleResolutionSupport.shouldPreferResourceDiscoveryFocusedCandidate(
                        request, governedCandidate, selectedCandidate);
        boolean selectedProjectionOnly =
                projectionRoleResolutionSupport.hasRoleProjectionMismatchForGenericOperation(selectedCandidate)
                && !hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE);
        return selectedProjectionOnly
                && (governedIsRankedAhead || meaningfulScoreGap || governedHasFocusLead)
                ? governedCandidate
                : null;
    }

    AgenticAuthoringCandidate domainDiscoveryScopedOperationalCandidateForResourceDiscovery(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (request == null
                || selectedCandidate == null
                || candidates == null
                || candidates.isEmpty()
                || !hasMaterializableResourceDiscoveryContext(request)
                || !hasBusinessDataAuthoringSignal(request, prompt)
                || projectionRoleResolutionSupport.governedResourceDiscoveryNeed(request, prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL
                || projectionRoleResolutionSupport.isDomainDiscoveryScopedCandidate(request, selectedCandidate)
                || hasEvidence(selectedCandidate, "explicit-resource-path")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "current-page-target-resource")
                || !hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                || candidateScoringSupport.isDerivedProjectionCandidate(selectedCandidate)) {
            return null;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, selectedCandidate))
                .filter(candidate -> projectionRoleResolutionSupport.isDomainDiscoveryScopedCandidate(request, candidate))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .filter(candidate -> hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidate -> hasEvidence(candidate, "semantic-retrieval"))
                .filter(candidate -> hasEvidence(candidate, "schema-available"))
                .filter(candidateDiscoveryTelemetrySupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateDiscoveryTelemetrySupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> candidate.score()
                        >= selectedCandidate.score() - DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE)
                .findFirst()
                .orElse(null);
    }

    AgenticAuthoringCandidate explicitResourcePathCandidate(
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        String resourcePath = explicitResourcePath(prompt);
        if (resourcePath.isBlank()) {
            return null;
        }
        String submitUrl = explicitSubmitUrl(prompt, resourcePath);
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        String normalizedResourcePath = normalizePath(resourcePath);
        Optional<AgenticAuthoringCandidate> existing = usableCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> normalizedResourcePath.equals(normalizePath(candidate.resourcePath())))
                .findFirst();
        if (existing.isPresent()) {
            AgenticAuthoringCandidate candidate = existing.get();
            if (submitUrl.isBlank() || normalizePath(submitUrl).equals(normalizePath(candidate.submitUrl()))) {
                return new AgenticAuthoringCandidate(
                        candidate.resourcePath(),
                        candidate.operation(),
                        candidate.schemaUrl(),
                        candidate.submitUrl(),
                        candidate.submitMethod(),
                        Math.max(candidate.score(), 0.99d),
                        candidate.reason(),
                        Stream.concat(
                                        candidate.evidence().stream(),
                                        Stream.of("explicit-resource-path"))
                                .distinct()
                                .toList(),
                        candidate.evidenceBundle());
            }
            AgenticAuthoringCandidate explicitSubmitCandidate = candidateScoringSupport.candidate(
                    candidate.resourcePath(),
                    normalizePath(submitUrl),
                    candidate.operation(),
                    candidate.score(),
                    candidate.reason(),
                    "explicit-submit-url");
            return new AgenticAuthoringCandidate(
                    explicitSubmitCandidate.resourcePath(),
                    explicitSubmitCandidate.operation(),
                    explicitSubmitCandidate.schemaUrl(),
                    explicitSubmitCandidate.submitUrl(),
                    explicitSubmitCandidate.submitMethod(),
                    explicitSubmitCandidate.score(),
                    explicitSubmitCandidate.reason(),
                    Stream.concat(
                                    candidate.evidence().stream(),
                                    Stream.of("explicit-submit-url"))
                            .distinct()
                            .toList(),
                    candidate.evidenceBundle());
        }
        String canonicalResourcePath = normalizePath(resourcePath);
        return candidateScoringSupport.candidate(
                canonicalResourcePath,
                submitUrl.isBlank() ? canonicalResourcePath : normalizePath(submitUrl),
                "post",
                0.99d,
                "resource path explicitly provided by the user prompt",
                "explicit-resource-path");
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

    AgenticAuthoringCandidate selectFormWriteCandidate(
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringCandidate fallback,
            String prompt) {
        if (!"form".equals(artifactKind)
                || candidates == null
                || candidates.isEmpty()) {
            return fallback;
        }
        if (candidateScoringSupport.isBroadArtifactDiscoveryOnly(candidates)) {
            return fallback;
        }
        Optional<AgenticAuthoringCandidate> createEndpointCandidate = candidates.stream()
                .filter(Objects::nonNull)
                .filter(this::isCreateEndpointCandidate)
                .findFirst();
        if (createEndpointCandidate.isPresent()
                && (fallback == null || !isCreateEndpointCandidate(fallback))) {
            return createEndpointCandidate.get();
        }
        if (fallback != null && isWriteCandidate(fallback)) {
            return fallback;
        }
        if (fallback == null
                && candidates.size() > 1
                && !candidateScoringSupport.hasStrongPromptAlignment(prompt, candidates)) {
            return null;
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(this::isWriteCandidate)
                .findFirst()
                .orElse(fallback);
    }

    boolean isCreateEndpointCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String operation = valueOrDefault(candidate.operation(), "");
        String submitMethod = valueOrDefault(candidate.submitMethod(), operation);
        String resourcePath = normalizePath(candidate.resourcePath());
        String submitUrl = normalizePath(valueOrDefault(candidate.submitUrl(), resourcePath));
        return "post".equalsIgnoreCase(operation)
                && "post".equalsIgnoreCase(submitMethod)
                && !resourcePath.isBlank()
                && resourcePath.equals(submitUrl);
    }

    boolean isWriteCandidate(AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        String operation = valueOrDefault(candidate.operation(), "");
        String schemaUrl = valueOrDefault(candidate.schemaUrl(), "");
        return "post".equalsIgnoreCase(operation) || schemaUrl.contains("operation=post");
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidateDiscoveryTelemetrySupport.hasEvidence(candidate, evidence);
    }

    private String firstNonBlank(String... values) {
        return AgenticAuthoringTextNormalizationSupport.firstNonBlank(values);
    }

    private boolean containsAny(String value, String... tokens) {
        return AgenticAuthoringTextNormalizationSupport.containsAny(value, tokens);
    }

    private boolean isReadProjectionOperation(String submitUrl, String operation) {
        return AgenticAuthoringTextNormalizationSupport.isReadProjectionOperation(submitUrl, operation);
    }

    private String normalize(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalize(value);
    }

    private String normalizePath(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalizePath(value);
    }

    private String jsonText(JsonNode node, String field) {
        return AgenticAuthoringTextNormalizationSupport.jsonText(node, field);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return AgenticAuthoringTextNormalizationSupport.valueOrDefault(value, defaultValue);
    }

    private String valueOrUnknown(String value) {
        return AgenticAuthoringTextNormalizationSupport.valueOrUnknown(value);
    }
}
