package org.praxisplatform.config.ai.agent.security;

import java.util.Objects;

/**
 * Outcome returned by each stage of {@link AgentSecurityGuard}.
 */
public record AgentSecurityVerdict(
        Decision decision,
        String ruleId,
        String reason
) {

    public enum Decision {
        ALLOW,
        WARN,
        QUARANTINE,
        BLOCK
    }

    public AgentSecurityVerdict {
        decision = Objects.requireNonNull(decision, "decision must not be null");
        ruleId = ruleId == null ? "" : ruleId.trim();
        reason = reason == null ? "" : reason.trim();
    }

    public boolean isBlocked() {
        return decision == Decision.BLOCK;
    }

    public static AgentSecurityVerdict allow() {
        return new AgentSecurityVerdict(Decision.ALLOW, "allow", "");
    }

    public static AgentSecurityVerdict warn(String ruleId, String reason) {
        return new AgentSecurityVerdict(Decision.WARN, ruleId, reason);
    }

    public static AgentSecurityVerdict quarantine(String ruleId, String reason) {
        return new AgentSecurityVerdict(Decision.QUARANTINE, ruleId, reason);
    }

    public static AgentSecurityVerdict block(String ruleId, String reason) {
        return new AgentSecurityVerdict(Decision.BLOCK, ruleId, reason);
    }
}
