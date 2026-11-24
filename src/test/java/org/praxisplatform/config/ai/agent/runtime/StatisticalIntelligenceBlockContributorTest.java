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
                Map.entry("median", new BigDecimal("120.00")),
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
                Map.entry("observedValue", new BigDecimal("0.021")),
                Map.entry("expectedValue", new BigDecimal("0.006")),
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

        JsonNode boxplot = blocks.get(2);
        assertThat(boxplot.path("type").asText()).isEqualTo("widget-block");
        assertThat(boxplot.path("widgetType").asText()).isEqualTo("chart");
        assertThat(boxplot.path("widget").path("id").asText()).isEqualTo("praxis-chart");
        JsonNode boxplotDoc = boxplot.path("widget").path("inputs").path("chartDocument");
        assertThat(boxplotDoc.path("kind").asText()).isEqualTo("boxplot");
        assertThat(boxplotDoc.path("dimensions")).hasSize(1);
        assertThat(boxplotDoc.path("metrics")).hasSize(1);
        assertThat(boxplotDoc.path("theme").path("surface").path("mode").asText()).isEqualTo("embedded");
        assertThat(boxplotDoc.path("boxplot").path("scaleMethod").asText()).isEqualTo("mad");
        assertThat(boxplotDoc.path("boxplot").path("quartileFields").path("outliers").asText()).isEqualTo("outliers");
        assertThat(boxplot.path("widget").path("inputs").path("config").path("type").asText()).isEqualTo("boxplot");
        assertThat(boxplot.path("epistemicMeta").path("provenanceHash").asText()).isEqualTo("a".repeat(64));
        JsonNode boxplotData = boxplot.path("widget").path("inputs").path("data");
        assertThat(boxplotData.size()).isEqualTo(1);
        assertThat(boxplotData.toString()).doesNotContain("tiny_protected_unit").doesNotContain("9999");
        assertThat(boxplotData.get(0).path("cohortKey").asText()).doesNotContain("11144477735");
        assertThat(boxplotData.get(0).path("outliers").isArray()).isTrue();
        assertThat(boxplotData.get(0).path("outliers")).hasSize(1);

        JsonNode benford = blocks.get(3);
        assertThat(benford.path("type").asText()).isEqualTo("widget-block");
        JsonNode benfordDoc = benford.path("widget").path("inputs").path("chartDocument");
        assertThat(benfordDoc.path("kind").asText()).isEqualTo("combo");
        assertThat(benfordDoc.path("dimensions")).hasSize(1);
        assertThat(benfordDoc.path("metrics")).hasSize(2);
        assertThat(benfordDoc.path("theme").path("surface").path("mode").asText()).isEqualTo("embedded");
        assertThat(benfordDoc.path("referenceBands").size()).isEqualTo(3);
        for (JsonNode band : benfordDoc.path("referenceBands")) {
            assertThat(band.has("min")).isTrue();
            assertThat(band.has("max")).isTrue();
            assertThat(band.path("axis").asText()).isEqualTo("y");
            assertThat(band.has("from")).isFalse();
            assertThat(band.has("to")).isFalse();
        }
        assertThat(benford.path("widget").path("inputs").path("config").path("type").asText()).isEqualTo("combo");
        JsonNode benfordDataRow = benford.path("widget").path("inputs").path("data").get(0);
        assertThat(benfordDataRow.has("details")).isFalse();
        assertThat(benfordDataRow.has("reason")).isFalse();

        JsonNode sanitizedSse = redactor.sanitizeEventPayload(content);
        assertThat(sanitizedSse.toString()).doesNotContain("[REDACTED]");
    }
}
