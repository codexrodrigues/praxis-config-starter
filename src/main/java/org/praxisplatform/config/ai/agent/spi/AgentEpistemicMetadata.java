package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Objects;

/**
 * Epistemic qualification attached to analytical or rule-engine evidence exposed to the operational agent.
 */
public record AgentEpistemicMetadata(
        AgentEpistemicKind kind,
        boolean counterfactualAllowed,
        boolean requiresUncertaintyBounds,
        Double calibratedConfidence,
        String provenanceLabel,
        List<String> caveats
) {

    public AgentEpistemicMetadata {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }

    public static AgentEpistemicMetadata deterministicLaw(String provenanceLabel) {
        return new AgentEpistemicMetadata(
                AgentEpistemicKind.DETERMINISTIC_LAW,
                true,
                false,
                1.0d,
                provenanceLabel,
                List.of()
        );
    }

    public static AgentEpistemicMetadata calibratedProbability(
            double confidence,
            String provenanceLabel,
            List<String> caveats
    ) {
        return new AgentEpistemicMetadata(
                AgentEpistemicKind.CALIBRATED_PROBABILITY,
                false,
                true,
                confidence,
                provenanceLabel,
                caveats
        );
    }

    public static AgentEpistemicMetadata stochasticSimulation(String provenanceLabel, List<String> caveats) {
        return new AgentEpistemicMetadata(
                AgentEpistemicKind.STOCHASTIC_SIMULATION,
                true,
                true,
                null,
                provenanceLabel,
                caveats
        );
    }

    public static AgentEpistemicMetadata causalHypothesis(String provenanceLabel, List<String> caveats) {
        return new AgentEpistemicMetadata(
                AgentEpistemicKind.CAUSAL_HYPOTHESIS,
                false,
                true,
                null,
                provenanceLabel,
                caveats
        );
    }
}
