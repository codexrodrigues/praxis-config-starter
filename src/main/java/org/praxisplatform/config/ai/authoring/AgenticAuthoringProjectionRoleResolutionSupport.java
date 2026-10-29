package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentCandidateScoringSupport.CandidatePromptAlignment;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentCandidateScoringSupport.SemanticResourceNeed;

/**
 * Encapsulates projection-role resolution ({@code semantic-role:operational-resource},
 * {@code semantic-role:analytics-projection}, {@code semantic-role:profile-projection}) and
 * governed {@link AgenticAuthoringResourceSearchFocus} alignment for
 * {@link AgenticAuthoringIntentResolverService}.
 */
final class AgenticAuthoringProjectionRoleResolutionSupport {

    static final double DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE = 0.02d;
    static final int PRIMARY_ENTITY_PROJECTION_ALIGNMENT_TOLERANCE = 6;
    static final String SEMANTIC_ROLE_OPERATIONAL_RESOURCE =
            AgenticAuthoringIntentCandidateScoringSupport.SEMANTIC_ROLE_OPERATIONAL_RESOURCE;
    static final String SEMANTIC_ROLE_ANALYTICS_PROJECTION =
            AgenticAuthoringIntentCandidateScoringSupport.SEMANTIC_ROLE_ANALYTICS_PROJECTION;
    static final String SEMANTIC_ROLE_PROFILE_PROJECTION =
            AgenticAuthoringIntentCandidateScoringSupport.SEMANTIC_ROLE_PROFILE_PROJECTION;

    private final AgenticAuthoringIntentCandidateScoringSupport candidateScoringSupport;

    AgenticAuthoringProjectionRoleResolutionSupport(
            AgenticAuthoringIntentCandidateScoringSupport candidateScoringSupport) {
        this.candidateScoringSupport = Objects.requireNonNull(
                candidateScoringSupport, "candidateScoringSupport must not be null");
    }

    AgenticAuthoringCandidate strongerDedicatedProjectionCandidateForSemanticNeed(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        return strongerDedicatedProjectionCandidateForSemanticNeed(
                candidateScoringSupport.semanticResourceNeed(prompt, artifactKind),
                prompt,
                selectedCandidate,
                candidates);
    }

    AgenticAuthoringCandidate strongerDedicatedProjectionCandidateForSemanticNeed(
            SemanticResourceNeed semanticNeed,
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (selectedCandidate == null
                || candidates == null
                || candidates.isEmpty()
                || hasEvidence(selectedCandidate, "explicit-resource-path")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "current-page-target-resource")) {
            return null;
        }
        String expectedRole = switch (semanticNeed) {
            case PROFILE -> SEMANTIC_ROLE_PROFILE_PROJECTION;
            case ANALYTICS -> SEMANTIC_ROLE_ANALYTICS_PROJECTION;
            default -> "";
        };
        if (expectedRole.isBlank()
                && isMixedOperationalProjectionCandidate(selectedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)) {
            expectedRole = SEMANTIC_ROLE_ANALYTICS_PROJECTION;
        }
        if (expectedRole.isBlank()
                && isMixedOperationalProjectionCandidate(selectedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
            expectedRole = SEMANTIC_ROLE_PROFILE_PROJECTION;
        }
        if (expectedRole.isBlank()
                || !hasEvidence(selectedCandidate, expectedRole)
                && !hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)) {
            return null;
        }
        String projectionRole = expectedRole;
        boolean selectedMatchesPathIdentity = hasEvidence(selectedCandidate, projectionRole)
                && candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, selectedCandidate);
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, selectedCandidate))
                .filter(candidate -> isDedicatedProjectionCandidate(candidate, projectionRole))
                .filter(candidate -> !selectedMatchesPathIdentity
                        || candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, candidate))
                .filter(candidateScoringSupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateScoringSupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> candidate.score() >= selectedCandidate.score() + 0.03d
                        || candidateScoringSupport.directPromptCandidateAlignmentScore(prompt, candidate)
                        >= candidateScoringSupport.directPromptCandidateAlignmentScore(prompt, selectedCandidate) + 2)
                .max(Comparator
                        .comparingDouble(AgenticAuthoringCandidate::score)
                        .thenComparingInt(candidate ->
                                candidateScoringSupport.directPromptCandidateAlignmentScore(prompt, candidate)))
                .orElse(null);
    }

    AgenticAuthoringCandidate strongerDedicatedProjectionCandidateForGovernedNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        SemanticResourceNeed governedNeed = governedResourceDiscoveryNeed(request, prompt, artifactKind);
        if (governedNeed == SemanticResourceNeed.GENERIC_OPERATIONAL) {
            return null;
        }
        AgenticAuthoringCandidate candidate = strongerDedicatedProjectionCandidateForSemanticNeed(
                governedNeed,
                prompt,
                selectedCandidate,
                candidates);
        if (candidate == null
                || governedNeed != SemanticResourceNeed.ANALYTICS
                || isWithinPrimaryEntityProjectionScope(
                resourceDiscoverySearchFocus(request),
                candidate,
                candidates)) {
            return candidate;
        }
        return null;
    }

    boolean shouldRequireReviewForLowerRankedLlmSelection(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringLlmIntentResolution llmIntent,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        String llmSelectedResourcePath = llmIntent == null ? "" : valueOrDefault(llmIntent.selectedResourcePath(), "");
        if (llmSelectedResourcePath.isBlank()
                || selectedCandidate == null
                || rankedCandidates == null
                || rankedCandidates.size() <= 1
                || !normalizePath(llmSelectedResourcePath).equals(normalizePath(selectedCandidate.resourcePath()))
                || hasEvidence(selectedCandidate, "explicit-resource-path")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "current-page-target-resource")
                || candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, selectedCandidate)
                || candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, selectedCandidate)) {
            return false;
        }
        AgenticAuthoringCandidate topCandidate = rankedCandidates.stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (topCandidate == null || candidateScoringSupport.sameCandidate(topCandidate, selectedCandidate)) {
            return false;
        }
        if (resourceDiscoveryFocusSupportsLowerRankedSelectionAsScoreTie(request, selectedCandidate, topCandidate)
                || isGroundedByResourceDiscoveryFocus(request, selectedCandidate, rankedCandidates)) {
            return false;
        }
        SemanticResourceNeed resourceNeed = candidateScoringSupport.semanticResourceNeed(prompt, artifactKind);
        if (candidateScoringSupport.hasTrustedSelectionEvidence(selectedCandidate)) {
            if (resourceNeed == SemanticResourceNeed.GENERIC_OPERATIONAL
                    && hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                    && !candidateScoringSupport.isDerivedProjectionCandidate(selectedCandidate)
                    && hasRoleProjectionMismatchForGenericOperation(topCandidate)) {
                return false;
            }
            if (resourceNeed == SemanticResourceNeed.PROFILE
                    && hasEvidence(selectedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                    && !hasEvidence(topCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
                return false;
            }
            if (resourceNeed == SemanticResourceNeed.ANALYTICS
                    && hasEvidence(selectedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                    && !hasEvidence(topCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)) {
                return false;
            }
        }
        return candidateScoringSupport.hasTrustedSelectionEvidence(topCandidate)
                && !candidateScoringSupport.isWeakLexicalCandidate(topCandidate)
                && topCandidate.score() >= selectedCandidate.score();
    }

    boolean resourceDiscoveryFocusSupportsLowerRankedSelectionAsScoreTie(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringCandidate topCandidate) {
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus.isEmpty()
                || selectedCandidate == null
                || topCandidate == null
                || !hasEvidence(selectedCandidate, "tool-search-api-resources")
                || !hasEvidence(topCandidate, "tool-search-api-resources")
                || !candidateScoringSupport.hasTrustedSelectionEvidence(selectedCandidate)
                || !candidateScoringSupport.hasTrustedSelectionEvidence(topCandidate)
                || candidateScoringSupport.isWeakLexicalCandidate(selectedCandidate)
                || candidateScoringSupport.isWeakLexicalCandidate(topCandidate)) {
            return false;
        }
        double scoreMargin = topCandidate.score() - selectedCandidate.score();
        if (scoreMargin < 0d || scoreMargin >= 0.01d) {
            return false;
        }
        int selectedFocusAlignment = resourceSearchFocusAlignmentScore(focus, selectedCandidate);
        int topFocusAlignment = resourceSearchFocusAlignmentScore(focus, topCandidate);
        return selectedFocusAlignment + 1 >= topFocusAlignment;
    }

    boolean shouldRequireReviewForRoleMismatchedSelection(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        if (selectedCandidate == null
                || rankedCandidates == null
                || rankedCandidates.size() <= 1
                || hasEvidence(selectedCandidate, "explicit-resource-path")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "current-page-target-resource")
                || candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, selectedCandidate)
                || candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, selectedCandidate)) {
            return false;
        }
        SemanticResourceNeed resourceNeed = candidateScoringSupport.semanticResourceNeed(prompt, artifactKind);
        if (resourceNeed != SemanticResourceNeed.GENERIC_OPERATIONAL
                || !hasRoleProjectionMismatchForGenericOperation(selectedCandidate)) {
            return false;
        }
        return rankedCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, selectedCandidate))
                .filter(candidateScoringSupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateScoringSupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                .filter(candidate -> !candidateScoringSupport.isDerivedProjectionCandidate(candidate))
                .findAny()
                .isPresent();
    }

    boolean shouldRequireReviewForUnanchoredLowConfidenceSelection(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        if (selectedCandidate == null
                || rankedCandidates == null
                || rankedCandidates.size() <= 1
                || !"page".equals(valueOrDefault(artifactKind, ""))
                || candidateScoringSupport.semanticResourceNeed(prompt, artifactKind)
                != SemanticResourceNeed.GENERIC_OPERATIONAL
                || hasEvidence(selectedCandidate, "explicit-resource-path")
                || hasEvidence(selectedCandidate, "quick-reply-context")
                || hasEvidence(selectedCandidate, "current-page-target-resource")
                || candidateScoringSupport.promptMentionsSpecificCandidateToken(prompt, selectedCandidate)
                || candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, selectedCandidate)
                || selectedCandidate.score() >= 0.62d) {
            return false;
        }
        if (!candidateScoringSupport.hasTrustedSelectionEvidence(selectedCandidate)
                || candidateScoringSupport.isWeakLexicalCandidate(selectedCandidate)) {
            return false;
        }
        if (isGroundedByResourceDiscoveryFocus(request, selectedCandidate, rankedCandidates)) {
            return false;
        }
        String normalizedPrompt = normalize(prompt);
        boolean openNarrative = normalizedPrompt.length() >= 90
                || containsAny(normalizedPrompt, "ainda nao sei", "ainda não sei", "nao sei se", "não sei se", "outra coisa");
        if (!openNarrative) {
            return false;
        }
        boolean selectedHasVeryLowModerateConfidence = selectedCandidate.score() < 0.57d;
        return rankedCandidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, selectedCandidate))
                .filter(candidateScoringSupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateScoringSupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> selectedHasVeryLowModerateConfidence || candidate.score() >= selectedCandidate.score())
                .findAny()
                .isPresent();
    }

    AgenticAuthoringCandidate resourceDiscoveryFocusedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            List<AgenticAuthoringCandidate> candidates) {
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus == null || focus.isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        SemanticResourceNeed governedNeed = governedResourceDiscoveryNeed(request, prompt, artifactKind);
        List<CandidatePromptAlignment> alignments = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasResourceDiscoveryCandidate(request, candidate)
                        || hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidateScoringSupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateScoringSupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> governedNeed != SemanticResourceNeed.ANALYTICS
                        || isWithinPrimaryEntityProjectionScope(focus, candidate, candidates))
                .map(candidate -> new CandidatePromptAlignment(
                        candidate,
                        resourceSearchFocusAlignmentScore(focus, candidate)
                                + (isDomainDiscoveryScopedCandidate(request, candidate) ? 4 : 0)))
                .filter(alignment -> alignment.score() >= 5)
                .toList();
        if (governedNeed == SemanticResourceNeed.GENERIC_OPERATIONAL) {
            List<CandidatePromptAlignment> operationalAlignments = alignments.stream()
                    .filter(alignment -> hasEvidence(alignment.candidate(), SEMANTIC_ROLE_OPERATIONAL_RESOURCE))
                    .filter(alignment -> !candidateScoringSupport.isDerivedProjectionCandidate(alignment.candidate()))
                    .toList();
            if (!operationalAlignments.isEmpty()) {
                alignments = operationalAlignments;
            }
        }
        alignments = alignments.stream()
                .sorted(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingInt(alignment -> isDomainDiscoveryScopedCandidate(request, alignment.candidate()) ? 1 : 0)
                        .thenComparingDouble(alignment -> alignment.candidate().score())
                        .reversed())
                .toList();
        if (alignments.isEmpty()) {
            return null;
        }
        CandidatePromptAlignment best = alignments.get(0);
        CandidatePromptAlignment leadingAlignment = best;
        CandidatePromptAlignment scopedBest = alignments.stream()
                .filter(alignment -> isDomainDiscoveryScopedCandidate(request, alignment.candidate()))
                .filter(alignment -> alignment.score() >= leadingAlignment.score() - 6)
                .filter(alignment -> alignment.candidate().score()
                        >= leadingAlignment.candidate().score() - DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE)
                .findFirst()
                .orElse(null);
        if (scopedBest != null) {
            best = scopedBest;
        }
        CandidatePromptAlignment selectedAlignment = best;
        // Operations of the same resource remain separate grounding alternatives, but
        // cannot create ambiguity about which business resource the AI focus selected.
        CandidatePromptAlignment second = alignments.stream()
                .filter(alignment -> !candidateScoringSupport.sameCandidate(alignment.candidate(), selectedAlignment.candidate()))
                .findFirst()
                .orElse(null);
        if (second == null) {
            return best.candidate();
        }
        boolean semanticallySeparated = best.score() >= second.score() + 3;
        boolean rankingSeparated = best.score() > second.score()
                && best.candidate().score() >= second.candidate().score() + 0.06d;
        return semanticallySeparated || rankingSeparated ? best.candidate() : null;
    }

    boolean isDomainDiscoveryScopedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate candidate) {
        if (request == null || candidate == null || request.contextHints() == null) {
            return false;
        }
        JsonNode domainDiscovery = request.contextHints().path("domainDiscovery");
        if (domainDiscovery == null || !domainDiscovery.isArray()) {
            return false;
        }
        String candidatePath = normalizePath(candidate.resourcePath());
        String submitUrl = normalizePath(candidate.submitUrl());
        if (candidatePath.isBlank() && submitUrl.isBlank()) {
            return false;
        }
        for (JsonNode item : domainDiscovery) {
            String resourceKey = normalizeDomainCatalogResourceKey(jsonText(item, "resourceKey"));
            String resourcePath = resourcePathFromDomainCatalogResourceKey(resourceKey);
            if (resourcePath.isBlank()) {
                continue;
            }
            if (resourcePath.equals(candidatePath)
                    || !submitUrl.isBlank() && submitUrl.startsWith(resourcePath + "/")) {
                return true;
            }
        }
        return false;
    }

    AgenticAuthoringCandidate projectionResourceDiscoveryFocusedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        SemanticResourceNeed focusedNeed = governedResourceDiscoveryNeed(request, prompt, artifactKind);
        SemanticResourceNeed effectiveFocusedNeed = focusedNeed;
        if (focusedNeed == SemanticResourceNeed.GENERIC_OPERATIONAL
                || candidates == null
                || candidates.isEmpty()) {
            return null;
        }
        List<CandidatePromptAlignment> alignments = candidates.stream()
                .filter(Objects::nonNull)
                .filter(candidate -> hasResourceDiscoveryCandidate(request, candidate)
                        || hasEvidence(candidate, "tool-search-api-resources"))
                .filter(candidateScoringSupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateScoringSupport.isWeakLexicalCandidate(candidate))
                .filter(candidate -> effectiveFocusedNeed == SemanticResourceNeed.PROFILE
                        ? hasEvidence(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                        : hasEvidence(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION))
                .filter(candidate -> effectiveFocusedNeed != SemanticResourceNeed.ANALYTICS
                        || isWithinPrimaryEntityProjectionScope(
                        resourceDiscoverySearchFocus(request),
                        candidate,
                        candidates))
                .map(candidate -> new CandidatePromptAlignment(
                        candidate,
                        projectionResourceDiscoveryAlignmentScore(
                                request,
                                resourceDiscoverySearchFocus(request),
                                candidate)))
                .filter(alignment -> alignment.score() >= 5)
                .toList();
        CandidatePromptAlignment best = alignments.stream()
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .orElse(null);
        CandidatePromptAlignment dedicatedProjection = alignments.stream()
                .filter(alignment -> isDedicatedProjectionCandidate(
                        alignment.candidate(),
                        candidateScoringSupport.projectionRole(effectiveFocusedNeed)))
                .max(Comparator
                        .comparingInt(CandidatePromptAlignment::score)
                        .thenComparingDouble(alignment -> alignment.candidate().score()))
                .orElse(null);
        AgenticAuthoringCandidate selected = best == null ? null : best.candidate();
        if (best != null
                && dedicatedProjection != null
                && !candidateScoringSupport.sameCandidate(best.candidate(), dedicatedProjection.candidate())
                && (dedicatedProjection.score() >= best.score() - 4
                || isMixedOperationalProjectionCandidate(
                        best.candidate(),
                        candidateScoringSupport.projectionRole(effectiveFocusedNeed))
                && dedicatedProjection.candidate().score() >= best.candidate().score() + 0.06d)) {
            selected = dedicatedProjection.candidate();
        }
        return candidateScoringSupport.sameCandidate(selected, selectedCandidate) ? null : selected;
    }

    int projectionResourceDiscoveryAlignmentScore(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate candidate) {
        int score = resourceSearchFocusAlignmentScore(focus, candidate);
        if (isDomainDiscoveryScopedCandidate(request, candidate)) {
            score += 4;
        }
        return score;
    }

    boolean isGroundedByResourceDiscoveryFocus(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> rankedCandidates) {
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus == null
                || focus.isEmpty()
                || selectedCandidate == null
                || (!hasResourceDiscoveryCandidate(request, selectedCandidate)
                && !hasEvidence(selectedCandidate, "tool-search-api-resources"))
                || resourceSearchFocusAlignmentScore(focus, selectedCandidate) < 5) {
            return false;
        }
        AgenticAuthoringCandidate nearestCompetitor = (rankedCandidates == null ? List.<AgenticAuthoringCandidate>of() : rankedCandidates)
                .stream()
                .filter(Objects::nonNull)
                .filter(candidate -> !candidateScoringSupport.sameCandidate(candidate, selectedCandidate))
                .filter(candidateScoringSupport::hasTrustedSelectionEvidence)
                .filter(candidate -> !candidateScoringSupport.isWeakLexicalCandidate(candidate))
                .findFirst()
                .orElse(null);
        return nearestCompetitor == null
                || selectedCandidate.score() >= nearestCompetitor.score() + 0.06d
                || resourceSearchFocusAlignmentScore(focus, selectedCandidate)
                >= resourceSearchFocusAlignmentScore(focus, nearestCompetitor) + 3;
    }

    boolean shouldPreferResourceDiscoveryFocusedCandidate(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringCandidate focusedCandidate,
            AgenticAuthoringCandidate selectedCandidate) {
        if (focusedCandidate == null
                || selectedCandidate == null
                || candidateScoringSupport.sameCandidate(focusedCandidate, selectedCandidate)) {
            return false;
        }
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus == null || focus.isEmpty()) {
            return false;
        }
        int focusedScore = resourceSearchFocusAlignmentScore(focus, focusedCandidate);
        int selectedScore = resourceSearchFocusAlignmentScore(focus, selectedCandidate);
        if (isDomainDiscoveryScopedCandidate(request, selectedCandidate)
                && !isDomainDiscoveryScopedCandidate(request, focusedCandidate)
                && focusedCandidate.score()
                <= selectedCandidate.score() + DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE) {
            return false;
        }
        SemanticResourceNeed focusedNeed = resourceSearchFocusNeed(focus, "");
        if (focusedNeed == SemanticResourceNeed.ANALYTICS
                && hasPrimaryEntityProjectionScopeLead(focus, focusedCandidate, selectedCandidate)) {
            return true;
        }
        boolean focusedProjectionFitsDesiredSurface =
                focusedNeed == SemanticResourceNeed.PROFILE
                        && hasEvidence(focusedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                        || focusedNeed == SemanticResourceNeed.ANALYTICS
                        && hasEvidence(focusedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION);
        if (focusedProjectionFitsDesiredSurface
                && focusedScore >= selectedScore - 3
                && focusedCandidate.score() >= selectedCandidate.score() + 0.03d) {
            return true;
        }
        if (isDomainDiscoveryScopedCandidate(request, focusedCandidate)
                && !isDomainDiscoveryScopedCandidate(request, selectedCandidate)
                && focusedScore >= selectedScore - 6
                && focusedCandidate.score()
                >= selectedCandidate.score() - DOMAIN_DISCOVERY_SCOPED_SCORE_TOLERANCE) {
            return true;
        }
        return focusedScore >= selectedScore + 3
                || hasRoleProjectionMismatchForGenericOperation(selectedCandidate)
                && focusedScore > selectedScore;
    }

    boolean shouldPreserveSelectedProjectionForSemanticNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringCandidate focusedCandidate) {
        if (selectedCandidate == null
                || focusedCandidate == null
                || candidateScoringSupport.sameCandidate(selectedCandidate, focusedCandidate)) {
            return false;
        }
        SemanticResourceNeed effectiveNeed = governedResourceDiscoveryNeed(request, prompt, artifactKind);
        if (effectiveNeed == SemanticResourceNeed.PROFILE) {
            if (isDedicatedProjectionCandidate(selectedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                    && isMixedOperationalProjectionCandidate(focusedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
                return true;
            }
            return hasEvidence(selectedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                    && !hasEvidence(focusedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION);
        }
        if (effectiveNeed == SemanticResourceNeed.ANALYTICS) {
            if (hasPrimaryEntityProjectionScopeLead(
                    resourceDiscoverySearchFocus(request), focusedCandidate, selectedCandidate)) {
                return false;
            }
            if (isDedicatedProjectionCandidate(selectedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                    && isMixedOperationalProjectionCandidate(focusedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)) {
                return true;
            }
            if (hasEvidence(selectedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                    && candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, selectedCandidate)
                    && !candidateScoringSupport.promptMatchesCandidatePathIdentity(prompt, focusedCandidate)) {
                return true;
            }
            return hasEvidence(selectedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                    && !hasEvidence(focusedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION);
        }
        return false;
    }

    boolean hasPrimaryEntityProjectionScopeLead(
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate focusedCandidate,
            AgenticAuthoringCandidate selectedCandidate) {
        List<AgenticAuthoringCandidate> alternatives = List.of(focusedCandidate, selectedCandidate);
        // An analytical role refines the AI-authored primary entity. Its role bonus
        // cannot preserve or prefer an unrelated entity over that existing scope.
        return isWithinPrimaryEntityProjectionScope(focus, focusedCandidate, alternatives)
                && !isWithinPrimaryEntityProjectionScope(focus, selectedCandidate, alternatives);
    }

    boolean isProjectionCandidateForSemanticNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        SemanticResourceNeed effectiveNeed = governedResourceDiscoveryNeed(request, prompt, artifactKind);
        return effectiveNeed == SemanticResourceNeed.PROFILE
                && hasEvidence(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                || effectiveNeed == SemanticResourceNeed.ANALYTICS
                && hasEvidence(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION);
    }

    boolean isProjectionCandidateForPromptSemanticNeed(
            String prompt,
            String artifactKind,
            AgenticAuthoringCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        SemanticResourceNeed promptNeed = candidateScoringSupport.semanticResourceNeed(prompt, artifactKind);
        return promptNeed == SemanticResourceNeed.PROFILE
                && hasEvidence(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                || promptNeed == SemanticResourceNeed.ANALYTICS
                && hasEvidence(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION);
    }

    boolean hasProjectionResourceDiscoveryFocus(
            AgenticAuthoringIntentResolutionRequest request,
            String artifactKind) {
        SemanticResourceNeed focusedNeed = resourceSearchFocusNeed(
                resourceDiscoverySearchFocus(request),
                artifactKind);
        return focusedNeed == SemanticResourceNeed.PROFILE || focusedNeed == SemanticResourceNeed.ANALYTICS;
    }

    SemanticResourceNeed governedResourceDiscoveryNeed(
            AgenticAuthoringIntentResolutionRequest request,
            String prompt,
            String artifactKind) {
        AgenticAuthoringResourceSearchFocus focus = resourceDiscoverySearchFocus(request);
        if (focus != null && !focus.isEmpty()) {
            // The structured focus was authored by the LLM before candidate retrieval. It decides
            // the source role; local text matching only grounds and ranks candidates inside that
            // semantic decision. The raw prompt is a fallback only when no governed focus exists.
            return resourceSearchFocusNeed(focus, artifactKind);
        }
        return candidateScoringSupport.semanticResourceNeed(prompt, artifactKind);
    }

    AgenticAuthoringResourceSearchFocus resourceDiscoverySearchFocus(
            AgenticAuthoringIntentResolutionRequest request) {
        JsonNode focus = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("resourceDiscovery").path("resourceSearchFocus");
        if (focus == null || !focus.isObject()) {
            return new AgenticAuthoringResourceSearchFocus("", List.of(), "", "", "");
        }
        List<String> supportingConcepts = new ArrayList<>();
        JsonNode concepts = focus.path("supportingConcepts");
        if (concepts != null && concepts.isArray()) {
            for (JsonNode concept : concepts) {
                if (concept != null && concept.isTextual() && !concept.asText().isBlank()) {
                    supportingConcepts.add(concept.asText().trim());
                }
            }
        }
        return new AgenticAuthoringResourceSearchFocus(
                jsonText(focus, "primaryBusinessEntity"),
                supportingConcepts,
                jsonText(focus, "desiredSurface"),
                jsonText(focus, "uncertainty"),
                jsonText(focus, "rationale"));
    }

    int primaryBusinessEntityAlignmentScore(
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate candidate) {
        if (focus == null || focus.isEmpty() || candidate == null) {
            return 0;
        }
        int score = candidateScoringSupport.directPromptCandidateAlignmentScore(focus.primaryBusinessEntity(), candidate) * 2;
        score += terminalResourceSegmentAlignmentScore(focus.primaryBusinessEntity(), candidate) * 3;
        return score;
    }

    boolean isWithinPrimaryEntityProjectionScope(
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate candidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (focus == null || focus.isEmpty() || candidate == null || candidates == null || candidates.isEmpty()) {
            return true;
        }
        int bestAlignment = candidates.stream()
                .filter(Objects::nonNull)
                .mapToInt(value -> primaryBusinessEntityAlignmentScore(focus, value))
                .max()
                .orElse(0);
        if (bestAlignment <= 0) {
            return true;
        }
        int minimumAlignment = Math.max(1, bestAlignment - PRIMARY_ENTITY_PROJECTION_ALIGNMENT_TOLERANCE);
        return primaryBusinessEntityAlignmentScore(focus, candidate) >= minimumAlignment;
    }

    int resourceSearchFocusAlignmentScore(
            AgenticAuthoringResourceSearchFocus focus,
            AgenticAuthoringCandidate candidate) {
        if (focus == null || focus.isEmpty() || candidate == null) {
            return 0;
        }
        int score = primaryBusinessEntityAlignmentScore(focus, candidate);
        for (String concept : focus.supportingConcepts()) {
            score += candidateScoringSupport.directPromptCandidateAlignmentScore(concept, candidate);
        }
        SemanticResourceNeed focusedNeed = resourceSearchFocusNeed(focus, "");
        if (focusedNeed == SemanticResourceNeed.PROFILE) {
            if (hasEvidence(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
                score += 12;
                if (isDedicatedProjectionCandidate(candidate, SEMANTIC_ROLE_PROFILE_PROJECTION)) {
                    score += 6;
                }
            } else if (hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                    && !candidateScoringSupport.isDerivedProjectionCandidate(candidate)) {
                score -= 4;
            }
        } else if (focusedNeed == SemanticResourceNeed.ANALYTICS) {
            String focusSummary = String.join(" ",
                    valueOrDefault(focus.primaryBusinessEntity(), ""),
                    String.join(" ", focus.supportingConcepts()),
                    valueOrDefault(focus.rationale(), ""));
            boolean matchesCandidateIdentity = candidateScoringSupport.promptMatchesCandidatePathIdentity(focusSummary, candidate);
            if (hasEvidence(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)) {
                score += 12;
                if (isDedicatedProjectionCandidate(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                        && (matchesCandidateIdentity || candidateScoringSupport.isAnalyticalComparisonPrompt(focusSummary))) {
                    score += 6;
                } else if (isMixedOperationalProjectionCandidate(candidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                        && !matchesCandidateIdentity) {
                    score -= 4;
                }
            } else if (hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                    && !candidateScoringSupport.isDerivedProjectionCandidate(candidate)) {
                score -= 4;
            }
        } else if (hasEvidence(candidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                && !candidateScoringSupport.isDerivedProjectionCandidate(candidate)) {
            score += 2;
        }
        return score;
    }

    boolean isDedicatedProjectionCandidate(AgenticAuthoringCandidate candidate, String projectionRole) {
        return candidateScoringSupport.isDedicatedProjectionCandidate(candidate, projectionRole);
    }

    boolean isMixedOperationalProjectionCandidate(AgenticAuthoringCandidate candidate, String projectionRole) {
        return candidateScoringSupport.isMixedOperationalProjectionCandidate(candidate, projectionRole);
    }

    SemanticResourceNeed resourceSearchFocusNeed(
            AgenticAuthoringResourceSearchFocus focus,
            String artifactKind) {
        if (focus == null || focus.isEmpty()) {
            return SemanticResourceNeed.GENERIC_OPERATIONAL;
        }
        String desiredSurface = normalize(valueOrDefault(focus.desiredSurface(), ""));
        String focusText = normalize(String.join(" ",
                desiredSurface,
                String.join(" ", focus.supportingConcepts()),
                valueOrDefault(focus.rationale(), "")));
        if (hasFocusedAnalyticalAggregationSignal(focusText)) {
            return SemanticResourceNeed.ANALYTICS;
        }
        if (isOperationalOverviewWithSecondaryProfileSurface(desiredSurface)) {
            return SemanticResourceNeed.GENERIC_OPERATIONAL;
        }
        if (isOperationalTrackingFocus(focusText)) {
            return SemanticResourceNeed.GENERIC_OPERATIONAL;
        }
        // desiredSurface is LLM-authored semantic evidence. Generic supporting concepts
        // such as charts or metrics may refine an overview, but must not silently replace
        // its primary operational entity with a related analytical projection.
        if (isOperationalOverviewSurface(desiredSurface)) {
            return SemanticResourceNeed.GENERIC_OPERATIONAL;
        }
        SemanticResourceNeed surfaceNeed = candidateScoringSupport.semanticResourceNeed(desiredSurface, artifactKind);
        if (surfaceNeed == SemanticResourceNeed.GENERIC_OPERATIONAL) {
            surfaceNeed = candidateScoringSupport.semanticResourceNeed(
                    String.join(" ", desiredSurface, valueOrDefault(focus.rationale(), "")),
                    artifactKind);
        }
        if (surfaceNeed != SemanticResourceNeed.GENERIC_OPERATIONAL) {
            return surfaceNeed;
        }
        String supportingConcepts = normalize(String.join(" ", focus.supportingConcepts()));
        if (containsAny(supportingConcepts,
                "ficha", "resumo individual", "visao resumida", "visão resumida",
                "visao consolidada", "visão consolidada",
                "summary sheet", "summary view", "profile summary", "individual summary",
                "personal summary", "person summary", "record summary", "summary card",
                "detail card", "detail page", "details page")) {
            return SemanticResourceNeed.PROFILE;
        }
        if (containsAny(supportingConcepts,
                "grafico", "graficos", "chart", "charts", "indicador", "indicadores",
                "kpi", "kpis", "metrica", "metricas", "metric", "metrics",
                "analitico", "analitica", "analytics", "analytical",
                "tendencia", "trend", "trends", "distribuicao", "distribution",
                "serie temporal", "time series", "comparar", "comparacao", "comparison", "ranking",
                "agregado", "agregada", "agregados", "agregadas", "custo", "custos", "total", "totais",
                "soma", "somatorio", "somatória", "media", "média", "aggregate", "aggregated", "average")) {
            return SemanticResourceNeed.ANALYTICS;
        }
        return SemanticResourceNeed.GENERIC_OPERATIONAL;
    }

    boolean isOperationalOverviewSurface(String desiredSurface) {
        String normalized = normalize(desiredSurface);
        if (containsAny(normalized, "perfil", "profile", "ficha", "individual")) {
            return false;
        }
        boolean collectionDashboard360 = containsAny(normalized,
                "dashboard 360", "painel 360", "360 dashboard", "360 panel")
                && containsAny(normalized,
                "filtro", "filtros", "filter", "filters",
                "tabela", "table", "lista", "list", "colecao", "collection",
                "detalhe", "detalhes", "detail", "details");
        return collectionDashboard360 || containsAny(normalized,
                "acompanhar", "acompanhamento", "overview", "visao geral", "visão geral");
    }

    boolean isOperationalTrackingFocus(String focusText) {
        String normalized = normalize(focusText);
        return containsAny(normalized,
                "acompanhar", "acompanhamento", "monitorar", "monitoramento",
                "track", "tracking", "monitor", "monitoring")
                && containsAny(normalized,
                "dynamic page", "pagina dinamica", "página dinâmica", "tela",
                "dados", "data", "status", "atividades", "activities",
                "registros", "records", "detalhes", "details");
    }

    boolean hasFocusedAnalyticalAggregationSignal(String focusText) {
        String normalized = normalize(focusText);
        return containsAny(normalized,
                "custo", "custos", "agregado", "agregada", "agregados", "agregadas",
                "total", "totais", "soma", "somatorio", "somatória", "media", "média",
                "distribuicao", "distribuição", "tendencia", "tendência",
                "comparar", "comparacao", "comparação", "ranking")
                && containsAny(normalized,
                "por departamento", "por area", "por área", "por categoria", "por status", "por periodo",
                "por período", "por mes", "por mês", "por competencia", "por competência");
    }

    boolean isOperationalOverviewWithSecondaryProfileSurface(String desiredSurface) {
        String normalized = normalize(desiredSurface);
        return containsAny(normalized,
                "acompanhar", "acompanhamento", "lista", "listagem", "overview", "visao geral", "visão geral")
                && containsAny(normalized,
                "abrir perfil", "abrir detalhe", "abrir detalhes", "ver perfil", "ver detalhe", "ver detalhes");
    }

    int terminalResourceSegmentAlignmentScore(String text, AgenticAuthoringCandidate candidate) {
        List<String> tokens = candidateScoringSupport.promptSpecificTokens(text);
        if (tokens.isEmpty() || candidate == null) {
            return 0;
        }
        String terminalSegment = normalize(valueOrDefault(candidate.resourcePath(), ""))
                .replaceAll("/+$", "")
                .replaceAll("^.*/", "");
        if (terminalSegment.isBlank()) {
            return 0;
        }
        String paddedSegment = " " + terminalSegment.replaceAll("[^a-z0-9]+", " ") + " ";
        int score = (int) tokens.stream()
                .filter(token -> candidateScoringSupport.tokenMatchesCandidateText(token, paddedSegment))
                .count();
        String normalizedFocus = normalize(String.join(" ", tokens)).replaceAll("[^a-z0-9]+", " ").trim();
        if (!normalizedFocus.isBlank()
                && terminalSegment.replaceAll("[^a-z0-9]+", " ").trim().equals(normalizedFocus)) {
            score += 2;
        }
        return score;
    }

    boolean hasRoleProjectionMismatchForGenericOperation(AgenticAuthoringCandidate selectedCandidate) {
        if (hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE)
                && !candidateScoringSupport.isDerivedProjectionCandidate(selectedCandidate)) {
            return false;
        }
        return hasEvidence(selectedCandidate, SEMANTIC_ROLE_ANALYTICS_PROJECTION)
                || hasEvidence(selectedCandidate, SEMANTIC_ROLE_PROFILE_PROJECTION)
                || candidateScoringSupport.isDerivedProjectionCandidate(selectedCandidate)
                && !hasEvidence(selectedCandidate, SEMANTIC_ROLE_OPERATIONAL_RESOURCE);
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

    String normalizeDomainCatalogResourceKey(String resourceKey) {
        String normalized = valueOrDefault(resourceKey, "").trim();
        if (normalized.startsWith("/api/")) {
            normalized = normalized.substring("/api/".length()).replace('/', '.');
        }
        return normalized.replaceAll("^\\.+|\\.+$", "");
    }

    String resourcePathFromDomainCatalogResourceKey(String resourceKey) {
        String normalizedResourceKey = normalizeDomainCatalogResourceKey(resourceKey);
        if (normalizedResourceKey.isBlank()) {
            return "";
        }
        return normalizePath("/api/" + normalizedResourceKey.replace('.', '/'));
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
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

    private String jsonText(JsonNode node, String field) {
        return AgenticAuthoringTextNormalizationSupport.jsonText(node, field);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return AgenticAuthoringTextNormalizationSupport.valueOrDefault(value, defaultValue);
    }
}
