package org.praxisplatform.config.ai.agent.spi;

/**
 * Neutral epistemic envelope binding quantitative tool outputs to scientific status, FDR
 * significance, k-anonymity suppression counts, and immutable provenance hashes without coupling
 * {@code praxis-config-starter} to optional analytics modules.
 */
public record AgentEpistemicEnvelope(
        String epistemicKind,
        String availabilityState,
        boolean statisticallySignificant,
        Double qValueFdr,
        long sampleSize,
        int suppressedCellCount,
        String provenanceHash,
        String modelCardId,
        String datasetKey,
        String methodologyNote
) {
    public static final String KIND_DETERMINISTIC_LAW = "DETERMINISTIC_LAW";
    public static final String KIND_CALIBRATED_PROBABILITY = "CALIBRATED_PROBABILITY";
    public static final String KIND_STOCHASTIC_SIMULATION = "STOCHASTIC_SIMULATION";
    public static final String KIND_CAUSAL_HYPOTHESIS = "CAUSAL_HYPOTHESIS";
    public static final String KIND_OPTIMIZATION_RESULT = "OPTIMIZATION_RESULT";

    public static final String AVAILABILITY_AVAILABLE = "AVAILABLE";
    public static final String AVAILABILITY_INSUFFICIENT_SAMPLE = "INSUFFICIENT_SAMPLE";
    public static final String AVAILABILITY_K_ANONYMITY_SUPPRESSED = "K_ANONYMITY_SUPPRESSED";
    public static final String AVAILABILITY_ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE";
    public static final String AVAILABILITY_DEGRADED = "DEGRADED";

    public AgentEpistemicEnvelope {
        epistemicKind = nonBlankOrDefault(epistemicKind, KIND_CALIBRATED_PROBABILITY);
        availabilityState = nonBlankOrDefault(availabilityState, AVAILABILITY_ENGINE_UNAVAILABLE);
        if (qValueFdr != null && (!Double.isFinite(qValueFdr) || qValueFdr < 0.0d || qValueFdr > 1.0d)) {
            throw new IllegalArgumentException("qValueFdr must be in [0.0, 1.0], got " + qValueFdr);
        }
        if (sampleSize < 0L) {
            throw new IllegalArgumentException("sampleSize must be >= 0, got " + sampleSize);
        }
        if (suppressedCellCount < 0) {
            throw new IllegalArgumentException("suppressedCellCount must be >= 0, got " + suppressedCellCount);
        }
        if (!AVAILABILITY_AVAILABLE.equals(availabilityState) && statisticallySignificant) {
            throw new IllegalArgumentException(
                    "statisticallySignificant must be false when availabilityState is " + availabilityState);
        }
        datasetKey = datasetKey != null ? datasetKey.trim() : "";
    }

    public static AgentEpistemicEnvelope deterministicLaw(
            String datasetKey,
            long sampleSize,
            String provenanceHash,
            String methodologyNote) {
        return new AgentEpistemicEnvelope(
                KIND_DETERMINISTIC_LAW,
                AVAILABILITY_AVAILABLE,
                true,
                null,
                sampleSize,
                0,
                provenanceHash,
                null,
                datasetKey,
                methodologyNote);
    }

    public static AgentEpistemicEnvelope unavailable(
            String epistemicKind,
            String datasetKey,
            String modelCardId,
            String methodologyNote) {
        return new AgentEpistemicEnvelope(
                epistemicKind,
                AVAILABILITY_ENGINE_UNAVAILABLE,
                false,
                null,
                0L,
                0,
                null,
                modelCardId,
                datasetKey,
                methodologyNote);
    }

    public static AgentEpistemicEnvelope insufficientSample(
            String epistemicKind,
            String datasetKey,
            String modelCardId,
            long sampleSize,
            String methodologyNote) {
        return new AgentEpistemicEnvelope(
                epistemicKind,
                AVAILABILITY_INSUFFICIENT_SAMPLE,
                false,
                null,
                sampleSize,
                0,
                null,
                modelCardId,
                datasetKey,
                methodologyNote);
    }

    public static AgentEpistemicEnvelope suppressed(
            String epistemicKind,
            String datasetKey,
            String modelCardId,
            long sampleSize,
            int suppressedCellCount,
            String methodologyNote) {
        return new AgentEpistemicEnvelope(
                epistemicKind,
                AVAILABILITY_K_ANONYMITY_SUPPRESSED,
                false,
                null,
                sampleSize,
                suppressedCellCount,
                null,
                modelCardId,
                datasetKey,
                methodologyNote);
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }
}
