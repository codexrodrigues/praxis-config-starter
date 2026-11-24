package org.praxisplatform.config.ai.agent.security;

/**
 * Enforcement mode for {@link AgentSecurityGuard}.
 */
public enum AgentSecurityMode {
    /** Quarantines untrusted suspicious payloads and blocks unsafe mutations when a turn is tainted or ungrounded. */
    ENFORCE,
    /** Records security findings and marks state for telemetry without quarantining or blocking execution. */
    AUDIT_ONLY
}
