package org.praxisplatform.config.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;

@Tag("unit")
class AnalyticalRecipeAndEpistemicInstructionContributorTest {

    @Test
    void contributesKpiRecipePreferenceAndFailClosedEpistemicDiscipline() {
        AnalyticalRecipeAndEpistemicInstructionContributor contributor =
                new AnalyticalRecipeAndEpistemicInstructionContributor();
        AgentExecutionContext context = new AgentExecutionContext(
                null, null, null, "t1", "u1", "prod", "analyst", Set.of(), null, null, Map.of(), Map.of());

        var section = contributor.contribute(context, "").orElseThrow();
        assertThat(section.sectionId()).isEqualTo("praxis.core.analytical-epistemic");
        assertThat(section.content())
                .contains("kpi.addFromRecipe")
                .contains("governance.lockLevel")
                .contains("UNAUTHORIZED")
                .contains("TRUNCATED")
                .contains("K_ANONYMITY_SUPPRESSED");
    }
}
