package org.praxisplatform.config.ai.agent.security;

import java.util.Objects;

/**
 * Structured non-PII diagnostic finding recorded during a turn's security checks.
 */
public record AgentSecurityFinding(
        String stage,
        String ruleId,
        AgentSecurityVerdict.Decision decision,
        String location,
        String detail
) {
    public AgentSecurityFinding {
        stage = Objects.requireNonNull(stage, "stage must not be null").trim();
        ruleId = ruleId == null ? "" : ruleId.trim();
        decision = Objects.requireNonNull(decision, "decision must not be null");
        location = location == null ? "" : location.trim();
        detail = detail == null ? "" : detail.trim();
    }
}
