package org.praxisplatform.config.ai.agent.spi;

import java.util.Map;

/**
 * Open extension artifact captured during an operational agent turn so deterministic UI block
 * contributors can materialize specialized widgets without modifying {@code AgentTurnArtifactStore}
 * fields.
 */
public record AgentExtensionArtifact(
        String kind,
        String producerId,
        Map<String, Object> uiPayload,
        AgentEpistemicEnvelope epistemicEnvelope
) {
    public static final String KIND_STATISTICAL_INFERENCE = "statistical-inference";
    public static final String KIND_ANOMALY_SCAN = "anomaly-scan";

    public AgentExtensionArtifact {
        kind = kind == null ? "" : kind.trim();
        producerId = producerId == null ? "" : producerId.trim();
        uiPayload = AgentMapCopies.immutableOrderedMap(uiPayload);
    }
}
