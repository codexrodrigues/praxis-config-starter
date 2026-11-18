package org.praxisplatform.config.ai.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.DualProjectedPayload;
import org.praxisplatform.uischema.annotation.CalculationMemory;
import org.praxisplatform.uischema.annotation.CalculationStep;
import org.praxisplatform.uischema.annotation.CalculationTrail;
import org.praxisplatform.uischema.annotation.StepKind;
import org.praxisplatform.uischema.schema.CalculationMemoryMetadataResolver;

@Tag("unit")
class AgentCalculationMemoryEvaluatorTest {

    @CalculationMemory(
            title = "Memória de cálculo salarial",
            subtitleExpr = "'Competência ' + coalesce(row.period, 'atual')",
            legalBasis = "Art. 37, XI, CF/88",
            emptyText = "Sem cálculos aplicáveis.",
            trails = {
                @CalculationTrail(
                        id = "net-pay",
                        title = "Composição líquida",
                        ruleCode = "WF-PAY-NET",
                        formula = "Bruto - Deduções",
                        baseLabel = "Bruto integral",
                        baseExpr = "coalesce(row.grossAmount, 0)",
                        baseFormat = "currency:BRL",
                        deductionLabel = "Deduções",
                        deductionExpr = "coalesce(row.deductionAmount, 0)",
                        deductionFormat = "currency:BRL",
                        resultLabel = "Líquido",
                        resultExpr = "max(coalesce(row.grossAmount, 0) - coalesce(row.deductionAmount, 0), 0)",
                        resultFormat = "currency:BRL",
                        legalPurpose = "Explicabilidade do pagamento líquido."),
                @CalculationTrail(
                        id = "withholding-steps",
                        title = "Detalhamento de alíquota",
                        ruleCode = "WF-PAY-TAX",
                        visibleWhen = "coalesce(row.taxRate, 0) > 0",
                        resultLabel = "Dias úteis",
                        resultExpr = "coalesce(row.workedDays, 0)",
                        resultFormat = "days",
                        steps = {
                            @CalculationStep(
                                    label = "Alíquota previdenciária",
                                    expr = "row.taxRate",
                                    format = "percent",
                                    kind = StepKind.FACTOR),
                            @CalculationStep(
                                    label = "Base tributável confidencial",
                                    expr = "row.confidentialBonus",
                                    format = "currency:BRL",
                                    kind = StepKind.ADDITION)
                        })
            })
    public static class SampleCompensationDto {
    }

    @Test
    void evaluatesFixedAndExtensibleTrailsWithPtBrFormattingAndDualProjectionMasking() {
        Map<String, Object> meta = CalculationMemoryMetadataResolver.resolve(SampleCompensationDto.class);
        assertThat(meta).isNotNull();

        AgentEntityDescriptor descriptor = new AgentEntityDescriptor(
                "payroll.records", "payroll.records", "/api/payroll/records",
                "Folha", "Lançamentos financeiros", "payments",
                "id", "employeeLabel", "period", List.of("period"),
                null, null, null, "/payroll/{id}", "payroll", 10);

        Map<String, Object> uiRecord = Map.of(
                "id", 501L,
                "employeeLabel", "Mariana Silva (CPF 52998224725)",
                "period", "2026-03",
                "grossAmount", new BigDecimal("12500.00"),
                "deductionAmount", new BigDecimal("2500.00"),
                "taxRate", new BigDecimal("14"),
                "workedDays", 22,
                "confidentialBonus", new BigDecimal("3000.00"),
                "secretAuditNote", "INTERNAL");

        Map<String, Object> modelSafeRecord = Map.of(
                "id", 501L,
                "employeeLabel", "Mariana Silva (CPF 52998224725)",
                "period", "2026-03",
                "grossAmount", new BigDecimal("12500.00"),
                "deductionAmount", new BigDecimal("2500.00"),
                "taxRate", new BigDecimal("14"),
                "workedDays", 22);

        DualProjectedPayload projected = new DualProjectedPayload(
                uiRecord,
                modelSafeRecord,
                List.of("secretAuditNote"),
                List.of("confidentialBonus"));

        AgentEvaluatedCalculationMemory evaluated = AgentCalculationMemoryEvaluator.evaluate(
                descriptor, "501", "Mariana Silva (CPF 52998224725)", meta, projected);

        assertThat(evaluated).isNotNull();
        assertThat(evaluated.subtitle()).isEqualTo("Competência 2026-03");
        assertThat(evaluated.recordTitle()).contains("[CPF PROTEGIDO]").doesNotContain("52998224725");
        assertThat(evaluated.uiTrails()).hasSize(2);
        assertThat(evaluated.modelSafeTrails()).hasSize(2);

        var uiNet = evaluated.uiTrails().get(0);
        assertThat(uiNet.resultFormatted()).isEqualTo("R$ 10.000,00");
        assertThat(uiNet.steps()).extracting(s -> s.formattedValue())
                .containsExactly("R$ 12.500,00", "R$ 2.500,00", "R$ 10.000,00");

        var uiSteps = evaluated.uiTrails().get(1);
        assertThat(uiSteps.resultFormatted()).isEqualTo("22 dias");
        assertThat(uiSteps.steps().get(0).formattedValue()).isEqualTo("14%");
        assertThat(uiSteps.steps().get(1).formattedValue()).isEqualTo("R$ 3.000,00");

        var safeSteps = evaluated.modelSafeTrails().get(1);
        assertThat(safeSteps.steps().get(0).formattedValue()).isEqualTo("14%");
        assertThat(safeSteps.steps().get(1).formattedValue()).isEqualTo("[PROTEGIDO]");
        assertThat(safeSteps.steps().get(1).rawValue()).isNull();
    }

    @Test
    void respectsVisibleWhenConditionWhenFalse() {
        Map<String, Object> meta = CalculationMemoryMetadataResolver.resolve(SampleCompensationDto.class);
        DualProjectedPayload projected = new DualProjectedPayload(
                Map.of("grossAmount", 5000, "deductionAmount", 500, "taxRate", 0),
                Map.of("grossAmount", 5000, "deductionAmount", 500, "taxRate", 0),
                List.of(),
                List.of());

        AgentEvaluatedCalculationMemory evaluated = AgentCalculationMemoryEvaluator.evaluate(
                null, "502", "Sem taxa", meta, projected);

        assertThat(evaluated).isNotNull();
        assertThat(evaluated.uiTrails()).hasSize(1);
        assertThat(evaluated.uiTrails().get(0).id()).isEqualTo("net-pay");
    }

    @CalculationMemory(
            titleExpr = "'Memória ' + coalesce(row.maskedTag, '') + ' · CPF 52998224725'",
            subtitleExpr = "'Obs: ' + coalesce(row.hiddenHeaderNote, 'padrão')",
            legalBasisExpr = "'Base ' + coalesce(row.maskedBasis, 'CF/88')",
            trails = {
                @CalculationTrail(
                        id = "short-circuit-ternary",
                        title = "Ternário sem contaminação",
                        resultLabel = "Valor apurado",
                        resultExpr = "row.useSecret ? row.confidentialBonus : row.grossAmount",
                        resultFormat = "currency:BRL"),
                @CalculationTrail(
                        id = "nested-member",
                        title = "Acesso aninhado governado",
                        resultLabel = "Dado aninhado",
                        resultExpr = "row.nested.secretNote",
                        resultFormat = "text")
            })
    public static class GovernedHeaderAndConditionalDto {
    }

    @Test
    void enforcesModelSafeHeadersShortCircuitConditionalsAndNestedMemberTracking() {
        Map<String, Object> meta = CalculationMemoryMetadataResolver.resolve(GovernedHeaderAndConditionalDto.class);
        assertThat(meta).isNotNull();

        Map<String, Object> uiRecord = Map.of(
                "maskedTag", "Sigilosa",
                "hiddenHeaderNote", "Auditoria interna X",
                "maskedBasis", "Portaria Reservada",
                "useSecret", false,
                "confidentialBonus", new BigDecimal("9999.00"),
                "grossAmount", new BigDecimal("12500.00"),
                "nested", Map.of("secretNote", "4500.00"));

        Map<String, Object> safeRecord = Map.of(
                "useSecret", false,
                "grossAmount", new BigDecimal("12500.00"),
                "nested", Map.of("secretNote", "4500.00"));

        DualProjectedPayload projected = new DualProjectedPayload(
                uiRecord,
                safeRecord,
                List.of("hiddenHeaderNote"),
                List.of("maskedTag", "maskedBasis", "confidentialBonus", "nested.secretNote"));

        AgentEvaluatedCalculationMemory evaluated = AgentCalculationMemoryEvaluator.evaluate(
                null, "601", "Folha 601", meta, projected);

        assertThat(evaluated).isNotNull();
        // UI retains original interpolated headers (without CPF check on title/subtitle)
        assertThat(evaluated.title()).contains("Sigilosa");
        assertThat(evaluated.subtitle()).isEqualTo("Obs: Auditoria interna X");
        assertThat(evaluated.legalBasis()).isEqualTo("Base Portaria Reservada");

        // Model-safe headers mask governed expressions and Mod-11 tax IDs
        assertThat(evaluated.modelSafeTitle()).isEqualTo("[PROTEGIDO]");
        assertThat(evaluated.modelSafeSubtitle()).isEqualTo("[OMITIDO POR GOVERNANCA]");
        assertThat(evaluated.modelSafeLegalBasis()).isEqualTo("[PROTEGIDO]");

        // Untaken secret branch (row.confidentialBonus) does NOT contaminate taken branch (row.grossAmount)
        assertThat(evaluated.modelSafeTrails().get(0).resultFormatted()).isEqualTo("R$ 12.500,00");
        // Nested member access row.nested.secretNote respects nested.secretNote governance
        assertThat(evaluated.uiTrails().get(1).resultFormatted()).isEqualTo("4500.00");
        assertThat(evaluated.modelSafeTrails().get(1).resultFormatted()).isEqualTo("[PROTEGIDO]");
    }
}
