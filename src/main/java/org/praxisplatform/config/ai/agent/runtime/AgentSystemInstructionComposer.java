package org.praxisplatform.config.ai.agent.runtime;

import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionContributor;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionSection;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionSection.SectionPlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composes modular {@link AgentSystemInstructionContributor} sections in deterministic placement
 * order ({@link SectionPlacement#STABLE_POLICY_PREFIX}, then
 * {@link SectionPlacement#TENANT_CATALOG_SECTION}, then
 * {@link SectionPlacement#VOLATILE_TURN_CONTEXT}) to maximize prefix cache hits and keep
 * untrusted turn hints cleanly fenced at the tail of the prompt.
 */
public final class AgentSystemInstructionComposer {

    private final List<AgentSystemInstructionContributor> contributors;

    public AgentSystemInstructionComposer(List<AgentSystemInstructionContributor> contributors) {
        List<AgentSystemInstructionContributor> sorted = contributors == null
                ? new ArrayList<>()
                : new ArrayList<>(contributors.stream().filter(Objects::nonNull).toList());
        sorted.sort(Comparator.comparingInt(AgentSystemInstructionContributor::getOrder)
                .thenComparing(c -> c.getClass().getName()));
        this.contributors = List.copyOf(sorted);
    }

    public static AgentSystemInstructionComposer defaultComposer() {
        return new AgentSystemInstructionComposer(List.of(
                new CoreOperationalRulesInstructionContributor(),
                new SecurityBoundaryInstructionContributor(),
                new AnalyticalRecipeAndEpistemicInstructionContributor(),
                new ResourceDiscoverySummaryInstructionContributor(),
                new HostContextSystemInstructionContributor()
        ));
    }

    public List<AgentSystemInstructionContributor> contributors() {
        return contributors;
    }

    public String compose(AgentExecutionContext context, String resourceDiscoverySummary) {
        Map<SectionPlacement, List<AgentSystemInstructionSection>> buckets = new EnumMap<>(SectionPlacement.class);
        for (SectionPlacement placement : SectionPlacement.values()) {
            buckets.put(placement, new ArrayList<>());
        }
        for (AgentSystemInstructionContributor contributor : contributors) {
            contributor.contribute(context, resourceDiscoverySummary)
                    .filter(section -> section.content() != null && !section.content().isBlank())
                    .ifPresent(section -> buckets.get(section.placement()).add(section));
        }
        StringBuilder out = new StringBuilder();
        for (SectionPlacement placement : List.of(
                SectionPlacement.STABLE_POLICY_PREFIX,
                SectionPlacement.TENANT_CATALOG_SECTION,
                SectionPlacement.VOLATILE_TURN_CONTEXT
        )) {
            for (AgentSystemInstructionSection section : buckets.get(placement)) {
                if (!out.isEmpty()) {
                    out.append("\n\n");
                }
                out.append(section.content());
            }
        }
        return out.toString();
    }
}
