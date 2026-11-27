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

    @Test
    void rendersSimulationFanChartConfidenceBandsHorizontalTornadoAndSuppressesChartsWhenDegraded() {
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope availableEnv = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION,
                AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE,
                true,
                null,
                2000L,
                0,
                "e".repeat(64),
                "WF-SIM-01",
                "workforce.payroll.simulation",
                "Simulacao Monte Carlo com ancoras BigDecimal dentro da tolerancia.");

        List<Map<String, Object>> fanRows = List.of(
                Map.ofEntries(
                        Map.entry("periodIndex", 1),
                        Map.entry("periodLabel", "T+1"),
                        Map.entry("p05", 95000.0d),
                        Map.entry("p10", 97000.0d),
                        Map.entry("p25", 99000.0d),
                        Map.entry("p50", 101000.0d),
                        Map.entry("p75", 103000.0d),
                        Map.entry("p90", 105000.0d),
                        Map.entry("p95", 107000.0d)),
                Map.ofEntries(
                        Map.entry("periodIndex", 2),
                        Map.entry("periodLabel", "T+2"),
                        Map.entry("p05", 96000.0d),
                        Map.entry("p10", 98500.0d),
                        Map.entry("p25", 101000.0d),
                        Map.entry("p50", 104000.0d),
                        Map.entry("p75", 106500.0d),
                        Map.entry("p90", 109000.0d),
                        Map.entry("p95", 112000.0d)));
        List<Map<String, Object>> drivers = List.of(
                Map.of("factorKey", "overtimeHours", "factorLabel", "Horas Extras", "swingMagnitude", 4500.0d, "rankCorrelation", 0.35d),
                Map.of("factorKey", "baseAdjustment", "factorLabel", "Reajuste Base", "swingMagnitude", 14200.0d, "rankCorrelation", 0.82d));

        artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_STOCHASTIC_SIMULATION,
                "run_resource_stochastic_simulation",
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "resourceKey", "workforce.payroll-slips",
                        "resourcePath", "/api/workforce/payroll-slips",
                        "scenarioCount", 2000,
                        "horizonPeriods", 2,
                        "fanChart", fanRows,
                        "tornadoDrivers", drivers),
                availableEnv));

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(
                objectMapper, List.of(new StatisticalIntelligenceBlockContributor()));
        JsonNode blocks = composer.compose("Simulacao executada.", artifacts).assistantContent().path("blocks");

        assertThat(blocks).hasSize(4); // paragraph + epistemic-badge + fan-chart + tornado
        JsonNode badge = blocks.get(1);
        assertThat(badge.path("epistemicKind").asText()).isEqualTo(AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION);
        assertThat(badge.path("scenarioCount").asInt()).isEqualTo(2000);
        assertThat(badge.has("statisticallySignificant")).isFalse();

        JsonNode fanBlock = blocks.get(2);
        assertThat(fanBlock.path("type").asText()).isEqualTo("widget-block");
        JsonNode fanDoc = fanBlock.path("widget").path("inputs").path("chartDocument");
        JsonNode fanConfig = fanBlock.path("widget").path("inputs").path("config");
        assertThat(fanConfig.path("type").asText()).isEqualTo("line");
        assertThat(fanConfig.path("series").get(0).path("connectNulls").asBoolean()).isFalse();
        assertThat(fanConfig.path("series").get(0).path("lineStyle").asText()).isEqualTo("dashed");
        assertThat(fanConfig.path("series").get(0).path("smooth").asBoolean()).isFalse();
        assertThat(fanConfig.path("series").get(0).path("metric").path("aggregation").asText()).isEqualTo("avg");
        assertThat(fanDoc.path("dimensions").get(0).path("label").asText()).isEqualTo("Horizonte");
        assertThat(fanDoc.path("metrics").get(0).path("lineStyle").asText()).isEqualTo("dashed");
        assertThat(fanDoc.path("metrics").get(0).path("smooth").asBoolean()).isFalse();
        assertThat(fanDoc.path("metrics").get(0).path("aggregation").asText()).isEqualTo("avg");
        assertThat(fanDoc.path("epistemicMeta").has("statisticallySignificant")).isFalse();
        JsonNode bands = fanConfig.path("series").get(0).path("confidenceBands");
        assertThat(bands).hasSize(3);
        assertThat(bands.get(0).path("lowerField").asText()).isEqualTo("p05");
        assertThat(bands.get(0).path("upperField").asText()).isEqualTo("p95");
        assertThat(bands.get(1).path("lowerField").asText()).isEqualTo("p10");
        assertThat(bands.get(1).path("upperField").asText()).isEqualTo("p90");
        assertThat(bands.get(2).path("lowerField").asText()).isEqualTo("p25");
        assertThat(bands.get(2).path("upperField").asText()).isEqualTo("p75");
        JsonNode docBands = fanBlock.path("widget").path("inputs").path("chartDocument")
                .path("metrics").get(0).path("confidenceBands");
        assertThat(docBands).hasSize(3);
        assertThat(fanBlock.path("widget").path("inputs").path("chartDocument").path("epistemicMeta").path("kind").asText())
                .isEqualTo(AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION);

        JsonNode tornadoBlock = blocks.get(3);
        JsonNode tornadoDoc = tornadoBlock.path("widget").path("inputs").path("chartDocument");
        JsonNode tornadoConfig = tornadoBlock.path("widget").path("inputs").path("config");
        assertThat(tornadoConfig.path("type").asText()).isEqualTo("horizontal-bar");
        assertThat(tornadoConfig.path("orientation").asText()).isEqualTo("horizontal");
        assertThat(tornadoConfig.path("yAxisInverse").asBoolean()).isTrue();
        assertThat(tornadoConfig.path("axes").path("x").path("inverse").asBoolean()).isTrue();
        assertThat(tornadoDoc.path("yAxisInverse").asBoolean()).isTrue();
        assertThat(tornadoDoc.path("dimensions").get(0).path("label").asText()).isEqualTo("Fator estocástico");
        assertThat(tornadoDoc.path("dimensions").get(0).path("inverse").asBoolean()).isTrue();
        JsonNode tornadoData = tornadoBlock.path("widget").path("inputs").path("data");
        assertThat(tornadoData).hasSize(2);
        assertThat(tornadoData.get(0).path("factorKey").asText()).isEqualTo("baseAdjustment");
        assertThat(tornadoData.get(1).path("factorKey").asText()).isEqualTo("overtimeHours");

        // Fail-closed: DEGRADED suppresses all simulation charts
        AgentTurnArtifactStore degradedArtifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope degradedEnv = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION,
                AgentEpistemicEnvelope.AVAILABILITY_DEGRADED,
                false,
                null,
                2000L,
                0,
                "f".repeat(64),
                "WF-SIM-01",
                "workforce.payroll.simulation",
                "Anchor drift exceeded tolerance.");
        degradedArtifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_STOCHASTIC_SIMULATION,
                "run_resource_stochastic_simulation",
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "errorCode", "ANCHOR_REVALIDATION_DEGRADED",
                        "failClosedNote", "Divergencia acima da tolerancia na revalidacao deterministica.",
                        "fanChart", fanRows,
                        "tornadoDrivers", drivers),
                degradedEnv));
        JsonNode degradedBlocks = composer.compose("Degradado.", degradedArtifacts).assistantContent().path("blocks");
        assertThat(degradedBlocks).hasSize(2); // paragraph + epistemic-badge ONLY
        assertThat(degradedBlocks.get(1).path("type").asText()).isEqualTo("epistemic-badge");
        assertThat(degradedBlocks.get(1).path("availabilityState").asText()).isEqualTo(AgentEpistemicEnvelope.AVAILABILITY_DEGRADED);
    }

    @Test
    void rendersCounterfactualRecourseStrictlyWithRulesValidatedCandidatesAndSuppressesWhenDegraded() {
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope availableEnv = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_OPTIMIZATION_RESULT,
                AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE,
                true,
                null,
                120L,
                0,
                "g".repeat(64),
                "WF-CF-01",
                "workforce.payroll.counterfactual",
                "Busca contrafactual validada.");

        List<Map<String, Object>> mixedCandidates = List.of(
                Map.of(
                        "candidateId", "cf-valid-1",
                        "rank", 1,
                        "predictedValue", 48.2d,
                        "predictedDelta", -3.1d,
                        "feasibilityStatus", "VALIDATED_BY_RULES_ENGINE"),
                Map.of(
                        "candidateId", "cf-unverified-leak",
                        "rank", 2,
                        "predictedValue", 47.0d,
                        "predictedDelta", -4.3d,
                        "feasibilityStatus", "CANDIDATE_UNVERIFIED"),
                Map.of(
                        "candidateId", "cf-rejected-leak",
                        "rank", 3,
                        "predictedValue", 46.5d,
                        "predictedDelta", -4.8d,
                        "feasibilityStatus", "REJECTED_BY_RULES_ENGINE"));

        artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_COUNTERFACTUAL_SEARCH,
                "run_resource_counterfactual_search",
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "targetMetricKey", "payrollRatio",
                        "direction", "DECREASE_TO_TARGET",
                        "baselineValue", 51.3d,
                        "targetValue", 48.5d,
                        "converged", true,
                        "candidates", mixedCandidates),
                availableEnv));

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(
                objectMapper, List.of(new StatisticalIntelligenceBlockContributor()));
        JsonNode blocks = composer.compose("Alternativas encontradas.", artifacts).assistantContent().path("blocks");

        assertThat(blocks).hasSize(3); // paragraph + epistemic-badge + counterfactual-recourse
        JsonNode recourse = blocks.get(2);
        assertThat(recourse.path("type").asText()).isEqualTo("counterfactual-recourse");
        assertThat(recourse.path("advisoryOnly").asBoolean()).isTrue();
        assertThat(recourse.path("requiresHumanGovernanceApproval").asBoolean()).isTrue();
        assertThat(recourse.path("validatedCandidateCount").asInt()).isEqualTo(1);
        assertThat(recourse.path("candidates")).hasSize(1);
        assertThat(recourse.path("candidates").get(0).path("candidateId").asText()).isEqualTo("cf-valid-1");

        // Fail-closed when DEGRADED: suppress counterfactual-recourse card
        AgentTurnArtifactStore degradedArtifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope degradedEnv = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_OPTIMIZATION_RESULT,
                AgentEpistemicEnvelope.AVAILABILITY_DEGRADED,
                false,
                null,
                120L,
                0,
                "h".repeat(64),
                "WF-CF-01",
                "workforce.payroll.counterfactual",
                "Otimizador nao convergiu.");
        degradedArtifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_COUNTERFACTUAL_SEARCH,
                "run_resource_counterfactual_search",
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "errorCode", "NON_CONVERGENT_OPTIMIZER",
                        "failClosedNote", "Nao houve convergencia factivel.",
                        "candidates", mixedCandidates),
                degradedEnv));
        JsonNode degradedBlocks = composer.compose("Nao convergiu.", degradedArtifacts).assistantContent().path("blocks");
        assertThat(degradedBlocks).hasSize(2);
        assertThat(degradedBlocks.get(1).path("type").asText()).isEqualTo("epistemic-badge");
        assertThat(degradedBlocks.get(1).path("availabilityState").asText()).isEqualTo(AgentEpistemicEnvelope.AVAILABILITY_DEGRADED);
        assertThat(degradedBlocks.get(1).has("statisticallySignificant")).isFalse();
    }

    @Test
    void emitsCompactCollectionsForThresholdsAndAnchorsAndExposesAnchorsOnlyWhenDegraded() {
        AgentTurnArtifactStore artifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope availableEnv = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION,
                AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE,
                false,
                null,
                2000L,
                0,
                "x".repeat(64),
                "WF-SIM-02",
                "workforce.payroll.simulation",
                "Simulacao com limiares e ancoras.");

        List<Map<String, Object>> fanRows = List.of(
                Map.of("periodIndex", 1, "periodLabel", "T+1", "p10", 97000.0d, "p50", 101000.0d, "p90", 105000.0d),
                Map.of("periodIndex", 2, "periodLabel", "T+2", "p10", 98500.0d, "p50", 104000.0d, "p90", 109000.0d));
        List<Map<String, Object>> drivers = List.of(
                Map.of("factorKey", "baseRate", "factorLabel", "Reajuste", "swingMagnitude", 9000.0d, "rankCorrelation", 0.8d));
        List<Map<String, Object>> thresholds = List.of(
                Map.ofEntries(
                        Map.entry("thresholdId", "cap_1"),
                        Map.entry("label", "Teto Operacional"),
                        Map.entry("direction", "UPPER_CAP"),
                        Map.entry("limitValue", 106000.0d),
                        Map.entry("breachProbability", 0.22d),
                        Map.entry("ciLow", 0.20d),
                        Map.entry("ciHigh", 0.24d)));
        List<Map<String, Object>> anchors = List.of(
                Map.of(
                        "anchorLabel", "P50",
                        "quantile", 0.50d,
                        "monteCarloValue", 104000.0d,
                        "deterministicBigDecimalValue", 104010.0d,
                        "relativeErrorPct", 0.0001d,
                        "revalidationStatus", "REVALIDATED_WITHIN_TOLERANCE"));

        artifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_STOCHASTIC_SIMULATION,
                "run_resource_stochastic_simulation",
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "resourceKey", "workforce.payroll-slips",
                        "resourcePath", "/api/workforce/payroll-slips",
                        "fanChart", fanRows,
                        "tornadoDrivers", drivers,
                        "thresholdOutcomes", thresholds,
                        "anchorScenarios", anchors),
                availableEnv));

        OperationalAssistantBlockComposer composer = new OperationalAssistantBlockComposer(
                objectMapper, List.of(new StatisticalIntelligenceBlockContributor()));
        JsonNode blocks = composer.compose("Simulacao completa.", artifacts).assistantContent().path("blocks");

        assertThat(blocks).hasSize(6); // paragraph + badge + fan + tornado + thresholds compact-collection + anchors compact-collection
        JsonNode fanDoc = blocks.get(2).path("widget").path("inputs").path("chartDocument");
        assertThat(fanDoc.path("referenceLines")).hasSize(1);
        assertThat(fanDoc.path("referenceLines").get(0).path("value").asDouble()).isEqualTo(106000.0d);
        assertThat(blocks.get(2).path("widget").path("inputs").path("config").path("referenceLines")).hasSize(1);

        JsonNode thresholdsBlock = blocks.get(4);
        assertThat(thresholdsBlock.path("type").asText()).isEqualTo("compact-collection");
        assertThat(thresholdsBlock.path("variant").asText()).isEqualTo("records");
        assertThat(thresholdsBlock.path("rows")).hasSize(1);
        assertThat(thresholdsBlock.path("rows").get(0).path("limitValue").asDouble()).isEqualTo(106000.0d);

        JsonNode anchorsBlock = blocks.get(5);
        assertThat(anchorsBlock.path("type").asText()).isEqualTo("compact-collection");
        assertThat(anchorsBlock.path("variant").asText()).isEqualTo("records");
        assertThat(anchorsBlock.path("rows")).hasSize(1);
        assertThat(anchorsBlock.path("rows").get(0).path("anchorLabel").asText()).isEqualTo("P50");

        // When DEGRADED with anchorScenarios: withhold charts & thresholds, but keep epistemic-badge + anchor audit table
        AgentTurnArtifactStore degradedArtifacts = new AgentTurnArtifactStore();
        AgentEpistemicEnvelope degradedEnv = new AgentEpistemicEnvelope(
                AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION,
                AgentEpistemicEnvelope.AVAILABILITY_DEGRADED,
                false,
                null,
                2000L,
                0,
                "y".repeat(64),
                "WF-SIM-02",
                "workforce.payroll.simulation",
                "Deriva acima da tolerancia.");
        degradedArtifacts.recordExtensionArtifact(new AgentExtensionArtifact(
                AgentExtensionArtifact.KIND_STOCHASTIC_SIMULATION,
                "run_resource_stochastic_simulation",
                Map.of(
                        "entityKey", "workforce.payroll-slips",
                        "fanChart", fanRows,
                        "tornadoDrivers", drivers,
                        "thresholdOutcomes", thresholds,
                        "anchorScenarios", anchors),
                degradedEnv));
        JsonNode degradedBlocks = composer.compose("Degradado com auditoria.", degradedArtifacts).assistantContent().path("blocks");
        assertThat(degradedBlocks).hasSize(3); // paragraph + badge + anchors table ONLY
        assertThat(degradedBlocks.get(1).path("type").asText()).isEqualTo("epistemic-badge");
        assertThat(degradedBlocks.get(2).path("type").asText()).isEqualTo("compact-collection");
        assertThat(degradedBlocks.get(2).path("rows").get(0).path("anchorLabel").asText()).isEqualTo("P50");
    }
}
