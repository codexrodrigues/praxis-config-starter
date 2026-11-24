package org.praxisplatform.config.ai.agent.provider;

import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;

/**
 * Optional SPI evaluated by {@link RoutingAiToolCallingProvider} before dispatching a turn to a
 * candidate AI provider/model. Enables host sovereignty, data-residency, cost-tier, or tenant-level
 * governance policies over failover candidates.
 */
@FunctionalInterface
public interface AiProviderEligibilityPolicy {

    /**
     * Evaluates whether {@code providerId} (optionally paired with {@code model}) is allowed to serve
     * the current request context.
     */
    EligibilityResult evaluate(String providerId, String model, AgentExecutionContext context);

    record EligibilityResult(boolean eligible, String reason) {
        public static EligibilityResult allow() {
            return new EligibilityResult(true, null);
        }

        public static EligibilityResult deny(String reason) {
            return new EligibilityResult(false, reason == null || reason.isBlank()
                    ? "Provider denied by eligibility policy"
                    : reason.trim());
        }
    }
}
