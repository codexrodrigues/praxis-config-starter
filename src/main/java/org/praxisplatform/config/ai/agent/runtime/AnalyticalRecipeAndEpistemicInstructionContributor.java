package org.praxisplatform.config.ai.agent.runtime;

import java.util.Optional;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionContributor;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionSection;

/**
 * Contributes fail-closed epistemic distinction and analytical recipe discipline (Rule 14).
 */
public final class AnalyticalRecipeAndEpistemicInstructionContributor implements AgentSystemInstructionContributor {

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE + 300;
    }

    @Override
    public Optional<AgentSystemInstructionSection> contribute(
            AgentExecutionContext context,
            String resourceDiscoverySummary) {
        String content = """
                14. Rigor Analítico e Distinção Epistêmica (Fail-Closed):
                - Distinga sempre fatos e regras determinísticas (DETERMINISTIC_LAW: receitas analíticas verificadas, memória de cálculo e regras vigentes) de projeções probabilísticas (CALIBRATED_PROBABILITY), simulações hipotéticas (STOCHASTIC_SIMULATION), hipóteses causais/correlacionais (CAUSAL_HYPOTHESIS) e otimizações condicionadas (OPTIMIZATION_RESULT).
                - Resultados de 'execute_verified_analytical_recipe' carregam carimbo de proveniência imutável (provenanceDigest e lockedFilters); resultados de 'aggregate_resource_stats' têm modo EXPLORATORY_UNVERIFIED e devem declarar exatamente os filtros utilizados.
                - Achados com statisticallySignificant=false ou qValueFdr > alpha NÃO são anomalias ou irregularidades e devem ser relatados explicitamente como variação estatisticamente não significativa.
                - Quando suppressedCellCount > 0 ou suppressionStatus != EXACT_RELEASE, é estritamente proibido inferir, subtrair ou estimar contagens e valores de células protegidas por k-anonimato.
                - Quando availabilityState for ENGINE_UNAVAILABLE, INSUFFICIENT_SAMPLE ou K_ANONYMITY_SUPPRESSED, informe exatamente a indisponibilidade ou restrição amostral em modo fail-closed sem inventar nem estimar métricas numéricas.
                - Nunca extrapole tendências, intervalos de confiança ou relações causais que não constem explicitamente na evidência devolvida pelas ferramentas.""";
        return Optional.of(new AgentSystemInstructionSection(
                "praxis.core.analytical-epistemic",
                AgentSystemInstructionSection.SectionPlacement.STABLE_POLICY_PREFIX,
                content));
    }
}
