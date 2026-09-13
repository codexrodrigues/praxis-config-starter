package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Consistent Config evidence. Neither NEVER_APPLIED nor ALLOW authorizes a domain operation by itself. */
public record OperationalPolicyResolution(State resolutionState, OperationalPolicyTarget target,
        String resolutionFingerprint, Policy policy, Instant observedAt) {
    public OperationalPolicyResolution {
        Objects.requireNonNull(resolutionState); Objects.requireNonNull(target); Objects.requireNonNull(observedAt);
        Objects.requireNonNull(resolutionFingerprint);
        if ((resolutionState == State.ELIGIBLE_APPLIED_HEAD) != (policy != null)) throw new IllegalArgumentException("Policy must match resolution state");
    }
    public enum State { NEVER_APPLIED, ELIGIBLE_APPLIED_HEAD, PREVIOUSLY_APPLIED_WITHOUT_ELIGIBLE_HEAD, INCONSISTENT_OR_UNAVAILABLE }
    public enum Effect { BLOCK, ALLOW }
    public record Policy(UUID materializationId, UUID definitionId, int definitionVersion, String sourceHash, Effect effect, JsonNode payload) {
        public Policy {
            Objects.requireNonNull(materializationId); Objects.requireNonNull(definitionId); Objects.requireNonNull(sourceHash);
            Objects.requireNonNull(effect); Objects.requireNonNull(payload);
            if (definitionVersion < 1 || !payload.isObject()) throw new IllegalArgumentException("Invalid operational policy");
            payload = payload.deepCopy();
        }
        @Override public JsonNode payload() { return payload.deepCopy(); }
        @Override public String toString() { return "Policy[materializationId=" + materializationId + ", effect=" + effect + "]"; }
    }
}
