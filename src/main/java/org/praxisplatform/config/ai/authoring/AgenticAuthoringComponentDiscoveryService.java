package org.praxisplatform.config.ai.authoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Produces a bounded component selection projection after semantic intent resolution.
 *
 * <p>The service does not resolve primary intent. It ranks the governed component capability
 * catalog only from the already resolved semantic decision and its visualization constraints.</p>
 */
public class AgenticAuthoringComponentDiscoveryService {

    private static final int DEFAULT_LIMIT = 5;

    public ComponentDiscoveryResult discover(
            AgenticAuthoringSemanticDecision decision,
            AgenticAuthoringComponentCapabilitiesResult capabilities) {
        if (decision == null || capabilities == null || capabilities.catalogs() == null) {
            return ComponentDiscoveryResult.empty("semantic-decision-or-capability-catalog-unavailable");
        }
        AgenticAuthoringVisualizationDecision visualization = decision.visualizationDecision();
        Set<String> excluded = visualization == null || visualization.excludedComponentIds() == null
                ? Set.of()
                : Set.copyOf(visualization.excludedComponentIds());
        Set<String> semanticRefs = semanticRefs(decision, visualization);
        String primaryComponent = visualization == null ? "" : safe(visualization.primaryComponent());
        List<ComponentCandidate> candidates = new ArrayList<>();
        List<ComponentCandidate> rejected = new ArrayList<>();
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog
                : capabilities.catalogs()) {
            if (catalog == null || safe(catalog.componentId()).isBlank()) {
                continue;
            }
            CandidateScore score = score(catalog, semanticRefs, primaryComponent);
            ComponentCandidate candidate = new ComponentCandidate(
                    catalog.componentId(),
                    catalog.version(),
                    score.score(),
                    score.matchedCapabilityIds(),
                    List.of("ai_registry:component:" + catalog.componentId()),
                    excluded.contains(catalog.componentId()) ? "excluded-by-semantic-decision" : score.reason());
            if (excluded.contains(catalog.componentId()) || score.score() <= 0) {
                rejected.add(candidate);
            } else {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingInt(ComponentCandidate::score).reversed()
                .thenComparing(ComponentCandidate::componentId));
        rejected.sort(Comparator.comparing(ComponentCandidate::componentId));
        List<ComponentCandidate> accepted = candidates.stream().limit(DEFAULT_LIMIT).toList();
        List<ComponentCandidate> overflow = candidates.stream().skip(DEFAULT_LIMIT).toList();
        if (!overflow.isEmpty()) {
            rejected.addAll(overflow.stream()
                    .map(candidate -> new ComponentCandidate(
                            candidate.componentId(), candidate.manifestVersion(), candidate.score(),
                            candidate.matchedCapabilityIds(), candidate.evidenceRefs(), "candidate-budget-pruned"))
                    .toList());
        }
        return new ComponentDiscoveryResult(
                "praxis-agentic-authoring-component-selection.v1",
                "resolved-semantic-decision+governed-component-capabilities",
                List.copyOf(semanticRefs),
                accepted,
                List.copyOf(rejected),
                CatalogEvidence.from(capabilities.diagnostics()),
                accepted.isEmpty() ? "no-compatible-component-candidate" : "bounded-candidates-ranked");
    }

    private CandidateScore score(
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog,
            Set<String> semanticRefs,
            String primaryComponent) {
        int score = catalog.componentId().equals(primaryComponent) ? 1000 : 0;
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability
                : catalog.capabilities() == null
                ? List.<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability>of()
                : catalog.capabilities()) {
            if (capability == null) {
                continue;
            }
            boolean capabilityMatched = semanticRefs.stream().anyMatch(ref -> matches(capability, ref));
            if (capabilityMatched) {
                matched.add(safe(capability.id()));
                score += "component.author".equals(capability.id()) ? 25 : 100;
            }
        }
        if (score == 0 && semanticRefs.contains(normalize(catalog.componentId()))) {
            score = 500;
        }
        return new CandidateScore(
                score,
                List.copyOf(matched),
                catalog.componentId().equals(primaryComponent)
                        ? "primary-component-from-semantic-decision"
                        : matched.isEmpty() ? "no-semantic-capability-match" : "capability-match-after-semantic-scope");
    }

    private boolean matches(
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability,
            String semanticRef) {
        if (semanticRef.isBlank()) {
            return false;
        }
        if (semanticRef.equals(normalize(capability.id()))
                || semanticRef.equals(normalize(capability.changeKind()))) {
            return true;
        }
        return capability.triggerTerms() != null && capability.triggerTerms().stream()
                .map(AgenticAuthoringComponentDiscoveryService::normalize)
                // Trigger terms rank candidates only after the LLM has resolved the semantic
                // decision. Keep the comparison exact: containment makes broad decision refs
                // such as "create artifact" match every generic "create" capability and turns
                // the bounded projection back into a noisy flat catalog.
                .anyMatch(term -> !term.isBlank() && term.equals(semanticRef));
    }

    private Set<String> semanticRefs(
            AgenticAuthoringSemanticDecision decision,
            AgenticAuthoringVisualizationDecision visualization) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        add(refs, decision.artifactKind());
        add(refs, decision.changeKind());
        add(refs, decision.artifactIntent());
        add(refs, decision.visualIntent());
        if (visualization != null) {
            add(refs, visualization.intent());
            add(refs, visualization.layoutKind());
            add(refs, visualization.primaryComponent());
            if (visualization.includeDetailTable()) add(refs, "table");
            if (visualization.includeFilters()) add(refs, "filter");
            if (visualization.includeKpis()) add(refs, "summary");
        }
        return refs;
    }

    private void add(Set<String> refs, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) refs.add(normalized);
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record CandidateScore(int score, List<String> matchedCapabilityIds, String reason) {}

    public record ComponentDiscoveryResult(
            String schemaVersion,
            String source,
            List<String> semanticRefs,
            List<ComponentCandidate> acceptedCandidates,
            List<ComponentCandidate> rejectedCandidates,
            CatalogEvidence catalogEvidence,
            String outcome) {
        static ComponentDiscoveryResult empty(String outcome) {
            return new ComponentDiscoveryResult(
                    "praxis-agentic-authoring-component-selection.v1", "none", List.of(), List.of(), List.of(), null, outcome);
        }
    }

    public record CatalogEvidence(String source, boolean degraded, String degradationReason) {
        static CatalogEvidence from(
                AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityDiagnostics diagnostics) {
            return diagnostics == null
                    ? new CatalogEvidence("unknown", false, "")
                    : new CatalogEvidence(
                            safe(diagnostics.source()), diagnostics.degraded(), safe(diagnostics.degradationReason()));
        }
    }

    public record ComponentCandidate(
            String componentId,
            String manifestVersion,
            int score,
            List<String> matchedCapabilityIds,
            List<String> evidenceRefs,
            String reason) {}
}
