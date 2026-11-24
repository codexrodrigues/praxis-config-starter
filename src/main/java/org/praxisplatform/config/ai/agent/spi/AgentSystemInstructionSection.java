package org.praxisplatform.config.ai.agent.spi;

import java.util.Objects;

/**
 * Named section emitted by an {@link AgentSystemInstructionContributor} and positioned explicitly
 * within the composed system prompt to preserve prefix-cache stability and untrusted-data isolation.
 */
public record AgentSystemInstructionSection(
        String sectionId,
        SectionPlacement placement,
        String content
) {

    public AgentSystemInstructionSection {
        sectionId = Objects.requireNonNull(sectionId, "sectionId must not be null").trim();
        placement = Objects.requireNonNull(placement, "placement must not be null");
        content = content == null ? "" : content.trim();
    }

    /**
     * Ordering bucket within the final composed system prompt.
     */
    public enum SectionPlacement {
        /** Static universal operational policies (ordered first to maximize LLM prompt-cache hits). */
        STABLE_POLICY_PREFIX,
        /** Tenant- or session-scoped resource discovery and domain catalog summary. */
        TENANT_CATALOG_SECTION,
        /** Dynamic per-turn host UI/route context and fenced untrusted user hints (always placed last). */
        VOLATILE_TURN_CONTEXT
    }
}
