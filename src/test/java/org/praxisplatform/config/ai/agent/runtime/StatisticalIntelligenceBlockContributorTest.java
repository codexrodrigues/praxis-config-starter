package org.praxisplatform.config.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.agent.spi.AgentEpistemicEnvelope;
import org.praxisplatform.config.ai.agent.spi.AgentExtensionArtifact;
import org.praxisplatform.config.service.AiSensitiveDataRedactor;

@Tag("unit")
class StatisticalIntelligenceBlockContributorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AiSensitiveDataRedactor redactor = new AiSensitiveDataRedactor();

    @Test
    void composesEpistemicBadgeBoxplotAndBenfordBlocksWithoutSseRedactionOrSuppressedLeakage() {
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope envelope = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY,
                AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE,
                true,
                0.0008d,
                500L,
                2,
                "a".repeat(64),
                "WF-STAT-CARD-01",
                "workforce.payroll.items",
                "Robust MAD/ESD + Nigrini-Benford scan prepared by ops.auditor@workforce.org.");

        Map<String, Object> exactMadFinding = Map.ofEntries(
                Map.entry("findingId", "f-1"),
                Map.entry("method", "ROBUST_MAD_ZSCORE"),
                Map.entry("scope", "INDIVIDUAL_ENTITY"),
                Map.entry("cohortKey", "dept_ops|grade_b (contact 11144477735)"),
                Map.entry("label", "dept_ops|grade_b (contact 11144477735)"),
                Map.entry("min", new BigDecimal("80.00")),
                Map.entry("q1", new BigDecimal("100.00")),
                Map.entry("median", new BigDecimal("120.00")),
                Map.entry("q3", new BigDecimal("140.00")),
                Map.entry("max", new BigDecimal("180.00")),
                Map.entry("mad", new BigDecimal("15.00")),
                Map.entry("observedValue", new BigDecimal("5400.00")),
                Map.entry("expectedValue", new BigDecimal("1500.00")),
                Map.entry("residualValue", new BigDecimal("3900.00")),
                Map.entry("robustScore", 6.4d),
                Map.entry("qValueFdr", 0.0008d),
                Map.entry("severity", "CRITICAL"),
                Map.entry("financialImpact", new BigDecimal("3900.00")),
                Map.entry("suppressionStatus", "EXACT_RELEASE"));

        Map<String, Object> suppressedFinding = Map.of(
                "findingId", "f-sup",
                "method", "ROBUST_MAD_ZSCORE",
                "scope", "COHORT_GROUP",
                "cohortKey", "tiny_protected_unit",
                "residualValue", new BigDecimal("9999.00"),
                "suppressionStatus", "SUPPRESSED_PRIMARY_K_ANONYMITY",
                "suppressed", true);

        Map<String, Object> benfordFinding = Map.ofEntries(
                Map.entry("findingId", "f-benford-1"),
                Map.entry("method", "NIGRINI_BENFORD"),
                Map.entry("scope", "DISTRIBUTION_FORENSIC"),
                Map.entry("cohortKey", "manual_retroactive_entries"),
                Map.entry("nigriniMad", new BigDecimal("0.021")),
                Map.entry("observedValue", new BigDecimal("0.301")),
                Map.entry("expectedValue", new BigDecimal("0.301")),
                Map.entry("qValueFdr", 0.004d),
                Map.entry("severity", "WARNING"),
                Map.entry("suppressionStatus", "EXACT_RELEASE"),
                Map.entry("details", "should be stripped by allowlist"),
                Map.entry("reason", "should be stripped by allowlist"));

        Map<String, Object> uiPayload = Map.of(
                "entityKey", "workforce.payroll.items",
                "resourceKey", "workforce.payroll.items",
                "resourcePath", "/api/workforce/payroll-items",
                "title", "Payroll Audit — 11144477735",
                "scanCounters", Map.of(
                        "rowsScanned", 500L,
                        "groupsEvaluated", 12,
                        "findingsCount", 3,
                        "criticalCount", 1,
                        "warningCount", 1,
                        "suppressedCells", 2,
                        "totalFinancialExposure", new BigDecimal("3900.00"),
                        "truncated", false),
                "cohortPoints", List.of(exactMadFinding, suppressedFinding),
                "benfordFindings", List.of(benfordFinding),
                "findings", List.of(exactMadFinding, suppressedFinding, benfordFinding));

        artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_ANOMALY_SCAN,
                "scan_resource_anomalies",
                uiPayload,
                envelope));

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(
                objectMapper,
                List.of(new StatisticalIntelligenceBlockContributor()));
        var composed = composer.compose("Auditoria concluida.", artifacts);
        JsonNode content = composed.assistantContent();

        JsonNode blocks = content.path("blocks");
        assertThat(blocks.size()).isEqualTo(4); // paragraph + epistemic-badge + boxplot + combo

        JsonNode badge = blocks.get(1);
        assertThat(badge.path("type").asText()).isEqualTo("epistemic-badge");
        assertThat(badge.path("epistemicKind").asText()).isEqualTo("CALIBRATED_PROBABILITY");
        assertThat(badge.path("availabilityState").asText()).isEqualTo("AVAILABLE");
        assertThat(badge.path("statisticallySignificant").asBoolean()).isTrue();
        assertThat(badge.path("suppressedCellCount").asInt()).isEqualTo(2);
        assertThat(badge.path("rowsScanned").asLong()).isEqualTo(500L);
        assertThat(badge.path("title").asText()).doesNotContain("11144477735");
        assertThat(badge.path("methodologyNote").asText()).doesNotContain("ops.auditor@workforce.org");
        assertThat(badge.path("nigriniMad").asDouble()).isEqualTo(0.021d);
        assertThat(badge.path("nigriniConformity").asText()).isEqualTo("NAO_CONFORMIDADE");

        JsonNode boxplot = blocks.get(2);
        assertThat(boxplot.path("type").asText()).isEqualTo("widget-block");
        assertThat(boxplot.path("widgetType").asText()).isEqualTo("praxis-chart");
        assertThat(boxplot.path("widget").path("id").asText()).isEqualTo("praxis-chart");
        JsonNode boxplotCfg = boxplot.path("widget").path("inputs").path("config");
        assertThat(boxplotCfg.path("boxplot").path("scaleMethod").asText()).isEqualTo("mad");
        assertThat(boxplotCfg.path("boxplot").path("quartileFields").path("outliers").asText()).isEqualTo("outliers");
        JsonNode boxplotDoc = boxplot.path("widget").path("inputs").path("chartDocument");
        assertThat(boxplotDoc.path("kind").asText()).isEqualTo("boxplot");
        assertThat(boxplotDoc.path("dimensions")).hasSize(1);
        assertThat(boxplotDoc.path("metrics")).hasSize(1);
        assertThat(boxplotDoc.path("theme").path("surface").path("mode").asText()).isEqualTo("embedded");
        assertThat(boxplotDoc.path("boxplot").path("scaleMethod").asText()).isEqualTo("mad");
        assertThat(boxplotDoc.path("boxplot").path("quartileFields").path("min").asText()).isEqualTo("min");
        assertThat(boxplotDoc.path("boxplot").path("quartileFields").path("q1").asText()).isEqualTo("q1");
        assertThat(boxplotDoc.path("boxplot").path("quartileFields").path("median").asText()).isEqualTo("median");
        assertThat(boxplotDoc.path("boxplot").path("quartileFields").path("q3").asText()).isEqualTo("q3");
        assertThat(boxplotDoc.path("boxplot").path("quartileFields").path("max").asText()).isEqualTo("max");
        assertThat(boxplotDoc.path("boxplot").path("quartileFields").path("outliers").asText()).isEqualTo("outliers");
        assertThat(boxplot.path("widget").path("inputs").path("config").path("type").asText()).isEqualTo("boxplot");
        assertThat(boxplot.path("epistemicMeta").path("provenanceHash").asText()).isEqualTo("a".repeat(64));
        JsonNode boxplotData = boxplot.path("widget").path("inputs").path("data");
        assertThat(boxplotData.size()).isEqualTo(1);
        assertThat(boxplotData.toString()).doesNotContain("tiny_protected_unit").doesNotContain("9999");
        assertThat(boxplotData.get(0).path("cohortKey").asText()).doesNotContain("11144477735");
        assertThat(boxplotData.get(0).path("min").asDouble())
                .isLessThanOrEqualTo(boxplotData.get(0).path("q1").asDouble());
        assertThat(boxplotData.get(0).path("q1").asDouble())
                .isLessThanOrEqualTo(boxplotData.get(0).path("median").asDouble());
        assertThat(boxplotData.get(0).path("median").asDouble())
                .isLessThanOrEqualTo(boxplotData.get(0).path("q3").asDouble());
        assertThat(boxplotData.get(0).path("q3").asDouble())
                .isLessThanOrEqualTo(boxplotData.get(0).path("max").asDouble());
        assertThat(boxplotData.get(0).path("outliers").isArray()).isTrue();
        assertThat(boxplotData.get(0).path("outliers")).hasSize(1);

        JsonNode benford = blocks.get(3);
        assertThat(benford.path("type").asText()).isEqualTo("widget-block");
        assertThat(benford.path("subtitle").asText()).contains("0.0210");
        JsonNode benfordDoc = benford.path("widget").path("inputs").path("chartDocument");
        assertThat(benfordDoc.path("kind").asText()).isEqualTo("combo");
        assertThat(benfordDoc.path("dimensions")).hasSize(1);
        assertThat(benfordDoc.path("metrics")).hasSize(2);
        assertThat(benfordDoc.path("metrics").get(0).path("seriesKind").asText()).isEqualTo("bar");
        assertThat(benfordDoc.path("metrics").get(1).path("seriesKind").asText()).isEqualTo("line");
        assertThat(benfordDoc.path("theme").path("surface").path("mode").asText()).isEqualTo("embedded");
        assertThat(benfordDoc.has("referenceBands")).isFalse();
        assertThat(benford.path("widget").path("inputs").path("config").path("type").asText()).isEqualTo("combo");
        assertThat(benford.path("widget").path("inputs").path("config").has("referenceBands")).isFalse();
        JsonNode benfordDataRow = benford.path("widget").path("inputs").path("data").get(0);
        assertThat(benfordDataRow.has("details")).isFalse();
        assertThat(benfordDataRow.has("reason")).isFalse();

        JsonNode sanitizedSse = redactor.sanitizeEventPayload(content);
        assertThat(sanitizedSse.toString()).doesNotContain("[REDACTED]");
    }

    @Test
    void projectsFivePointCohortQuartilesAndNineDigitBenfordBarsAndLineFromDecompositionSteps() {
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope envelope = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY,
                AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE,
                true,
                0.002d,
                120L,
                0,
                "b".repeat(64),
                "WF-STAT-CARD-02",
                "workforce.payroll.items",
                "Analise 9 digitos Benford + quartis.");

        List<Map<String, Object>> cohortSteps = List.of(
                Map.of("stepKey", "cohort_min", "label", "Minimo", "value", 100.0d),
                Map.of("stepKey", "cohort_q1", "label", "Q1", "value", 150.0d),
                Map.of("stepKey", "cohort_median", "label", "Mediana", "value", 200.0d),
                Map.of("stepKey", "cohort_q3", "label", "Q3", "value", 250.0d),
                Map.of("stepKey", "cohort_max", "label", "Maximo", "value", 350.0d),
                Map.of("stepKey", "cohort_sample_size", "label", "Amostra", "value", 48L));
        Map<String, Object> madFinding = Map.ofEntries(
                Map.entry("findingId", "f-cohort"),
                Map.entry("method", "ROBUST_MAD_ZSCORE"),
                Map.entry("cohortKey", "eng_senior"),
                Map.entry("observedValue", 420.0d),
                Map.entry("expectedValue", 200.0d),
                Map.entry("residualValue", 220.0d),
                Map.entry("decompositionSteps", cohortSteps),
                Map.entry("suppressionStatus", "EXACT_RELEASE"));

        List<Map<String, Object>> benfordSteps = new java.util.ArrayList<>();
        benfordSteps.add(Map.of("stepKey", "nigrini_mad", "value", 0.0045d));
        for (int d = 1; d <= 9; d++) {
            double exp = Math.log10(1.0d + 1.0d / d);
            double obs = d == 1 ? 0.310d : exp;
            benfordSteps.add(Map.of("stepKey", "benford_digit_" + d + "_observed", "value", obs));
            benfordSteps.add(Map.of("stepKey", "benford_digit_" + d + "_expected", "value", exp));
        }
        Map<String, Object> benfordFinding = Map.of(
                "findingId", "f-benford-9",
                "method", "NIGRINI_BENFORD",
                "observedValue", 0.310d,
                "expectedValue", 0.301d,
                "decompositionSteps", benfordSteps,
                "suppressionStatus", "EXACT_RELEASE");

        artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_ANOMALY_SCAN,
                "scan_resource_anomalies",
                Map.of(
                        "entityKey", "workforce.payroll.items",
                        "resourceKey", "workforce.payroll.items",
                        "resourcePath", "/api/workforce/payroll-items",
                        "cohortPoints", List.of(madFinding),
                        "benfordFindings", List.of(benfordFinding),
                        "findings", List.of(madFinding, benfordFinding)),
                envelope));

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(
                objectMapper, List.of(new StatisticalIntelligenceBlockContributor()));
        JsonNode blocks = composer.compose("Analise completa.", artifacts).assistantContent().path("blocks");

        JsonNode badge = blocks.get(1);
        assertThat(badge.path("nigriniMad").asDouble()).isEqualTo(0.0045d);
        assertThat(badge.path("nigriniConformity").asText()).isEqualTo("CONFORMIDADE_ESTREITA");

        JsonNode boxRow = blocks.get(2).path("widget").path("inputs").path("data").get(0);
        assertThat(boxRow.path("min").asDouble()).isEqualTo(100.0d);
        assertThat(boxRow.path("q1").asDouble()).isEqualTo(150.0d);
        assertThat(boxRow.path("median").asDouble()).isEqualTo(200.0d);
        assertThat(boxRow.path("q3").asDouble()).isEqualTo(250.0d);
        assertThat(boxRow.path("max").asDouble()).isEqualTo(350.0d);
        assertThat(boxRow.path("sampleSize").asLong()).isEqualTo(48L);

        JsonNode benfordBlock = blocks.get(3);
        assertThat(benfordBlock.path("subtitle").asText()).contains("Conformidade estreita").contains("0.0045");
        JsonNode benfordCfg = benfordBlock.path("widget").path("inputs").path("config");
        assertThat(benfordCfg.path("axes").path("x").path("label").asText()).isEqualTo("Dígito inicial (1-9)");
        assertThat(benfordCfg.path("axes").path("y").path("label").asText()).isEqualTo("Proporção de frequência");
        assertThat(benfordCfg.path("series").get(0).path("type").asText()).isEqualTo("bar");
        assertThat(benfordCfg.path("series").get(1).path("type").asText()).isEqualTo("line");
        JsonNode benfordData = benfordBlock.path("widget").path("inputs").path("data");
        assertThat(benfordData).hasSize(9);
        assertThat(benfordData.get(0).path("digit").asText()).isEqualTo("1");
        assertThat(benfordData.get(8).path("digit").asText()).isEqualTo("9");
    }

    @Test
    void deduplicatesCohortOutliersRejectsMissingQuartilesFallsBackToTopLevelGroupsAndNeverUsesObservedValueAsMad() {
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope envelope = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY,
                AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE,
                true,
                0.001d,
                80L,
                0,
                "c".repeat(64),
                "WF-STAT-CARD-03",
                "workforce.payroll.items",
                "Consolidacao por coorte.");

        Map<String, Object> outlierOne = Map.ofEntries(
                Map.entry("findingId", "f-1"),
                Map.entry("method", "ROBUST_MAD_ZSCORE"),
                Map.entry("cohortKey", "eng_mid"),
                Map.entry("min", 100.0d),
                Map.entry("q1", 120.0d),
                Map.entry("median", 150.0d),
                Map.entry("q3", 180.0d),
                Map.entry("max", 220.0d),
                Map.entry("residualValue", 320.0d),
                Map.entry("suppressionStatus", "EXACT_RELEASE"));
        Map<String, Object> outlierTwo = Map.ofEntries(
                Map.entry("findingId", "f-2"),
                Map.entry("method", "ROBUST_MAD_ZSCORE"),
                Map.entry("cohortKey", "eng_mid"),
                Map.entry("min", 100.0d),
                Map.entry("q1", 120.0d),
                Map.entry("median", 150.0d),
                Map.entry("q3", 180.0d),
                Map.entry("max", 220.0d),
                Map.entry("residualValue", 440.0d),
                Map.entry("suppressionStatus", "EXACT_RELEASE"));
        Map<String, Object> incompleteCohort = Map.of(
                "findingId", "f-inc",
                "method", "ROBUST_MAD_ZSCORE",
                "cohortKey", "incomplete_box",
                "median", 200.0d,
                "mad", 25.0d,
                "residualValue", 999.0d,
                "suppressionStatus", "EXACT_RELEASE");
        Map<String, Object> benfordWithoutMad = Map.of(
                "findingId", "f-benford-nomad",
                "method", "NIGRINI_BENFORD",
                "observedValue", 0.301d,
                "expectedValue", 0.301d,
                "suppressionStatus", "EXACT_RELEASE");

        artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_ANOMALY_SCAN,
                "scan_resource_anomalies",
                Map.of(
                        "entityKey", "workforce.payroll.items",
                        "groupsEvaluated", 7,
                        "findings", List.of(outlierOne, outlierTwo, incompleteCohort, benfordWithoutMad)),
                envelope));

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(
                objectMapper, List.of(new StatisticalIntelligenceBlockContributor()));
        JsonNode blocks = composer.compose("Resumo.", artifacts).assistantContent().path("blocks");

        JsonNode badge = blocks.get(1);
        assertThat(badge.path("groupsEvaluated").asInt()).isEqualTo(7);
        assertThat(badge.has("nigriniMad")).isFalse();

        JsonNode boxData = blocks.get(2).path("widget").path("inputs").path("data");
        assertThat(boxData).hasSize(1);
        assertThat(boxData.get(0).path("cohortKey").asText()).isEqualTo("eng_mid");
        assertThat(boxData.get(0).path("outliers")).hasSize(2);
        assertThat(boxData.get(0).path("outliers").get(0).asDouble()).isEqualTo(320.0d);
        assertThat(boxData.get(0).path("outliers").get(1).asDouble()).isEqualTo(440.0d);
    }
}
