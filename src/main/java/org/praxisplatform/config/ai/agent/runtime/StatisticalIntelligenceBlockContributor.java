package org.praxisplatform.config.ai.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.praxisplatform.config.ai.agent.spi.AgentEpistemicEnvelope;
import org.praxisplatform.config.ai.agent.spi.AgentExtensionArtifact;
import org.praxisplatform.config.ai.agent.spi.OperationalAssistantBlockContributor;
import org.springframework.util.StringUtils;

/**
 * Contribuidor deterministico para artefatos de inferencia estatistica e varredura de anomalias.
 *
 * <p>Emite selo epistêmico ({@code epistemic-badge}) e blocos de graficos {@code boxplot} (escala
 * robusta MAD/ESD) ou {@code combo} (Nigrini-Benford com faixas de conformidade), omitindo
 * estritamente qualquer celula suprimida por k-anonimato e evitando nomes de chaves redatados por
 * {@code AiSensitiveDataRedactor}.
 */
public class StatisticalIntelligenceBlockContributor implements OperationalAssistantBlockContributor {

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public List<ObjectNode> contribute(AgentTurnArtifactStore artifacts, ObjectMapper objectMapper) {
        if (artifacts == null || objectMapper == null) {
            return List.of();
        }
        List<AgentExtensionArtifact> extensions = artifacts.extensionArtifacts();
        if (extensions.isEmpty()) {
            return List.of();
        }
        List<ObjectNode> blocks = new ArrayList<>();
        for (AgentExtensionArtifact artifact : extensions) {
            if (artifact == null) {
                continue;
            }
            if (!AgentExtensionArtifact.KIND_STATISTICAL_INFERENCE.equals(artifact.kind())
                    && !AgentExtensionArtifact.KIND_ANOMALY_SCAN.equals(artifact.kind())) {
                continue;
            }
            ObjectNode badge = buildEpistemicBadgeBlock(artifact, objectMapper);
            if (badge != null) {
                blocks.add(badge);
            }
            AgentEpistemicEnvelope envelope = artifact.epistemicEnvelope();
            if (envelope != null
                    && !AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE.equals(envelope.availabilityState())) {
                continue;
            }
            ObjectNode boxplot = buildBoxplotWidgetBlock(artifact, objectMapper);
            if (boxplot != null) {
                blocks.add(boxplot);
            }
            ObjectNode benford = buildBenfordComboWidgetBlock(artifact, objectMapper);
            if (benford != null) {
                blocks.add(benford);
            }
        }
        return blocks;
    }

    private ObjectNode buildEpistemicBadgeBlock(AgentExtensionArtifact artifact, ObjectMapper objectMapper) {
        AgentEpistemicEnvelope env = artifact.epistemicEnvelope();
        Map<String, Object> uiPayload = artifact.uiPayload();
        ObjectNode badge = objectMapper.createObjectNode();
        badge.put("type", "epistemic-badge");
        String defaultTitle = AgentExtensionArtifact.KIND_ANOMALY_SCAN.equals(artifact.kind())
                ? "Auditoria Estatística de Anomalias"
                : "Inferência Estatística por Coortes";
        String title = textValue(uiPayload.get("title"));
        badge.put("title", mask(StringUtils.hasText(title) ? title : defaultTitle));
        putIfText(badge, "analysisKind", artifact.kind());
        putIfText(badge, "entityKey", textValue(uiPayload.get("entityKey")));
        putIfText(badge, "resourceKey", textValue(uiPayload.get("resourceKey")));
        putIfText(badge, "resourcePath", textValue(uiPayload.get("resourcePath")));

        if (env != null) {
            putIfText(badge, "datasetKey", mask(env.datasetKey()));
            putIfText(badge, "epistemicKind", env.epistemicKind());
            putIfText(badge, "availabilityState", env.availabilityState());
            badge.put("statisticallySignificant", env.statisticallySignificant());
            if (env.qValueFdr() != null) {
                badge.put("qValueFdr", env.qValueFdr());
            }
            badge.put("sampleSize", env.sampleSize());
            badge.put("suppressedCellCount", env.suppressedCellCount());
            putIfText(badge, "provenanceHash", env.provenanceHash());
            putIfText(badge, "modelCardId", mask(env.modelCardId()));
            putIfText(badge, "methodologyNote", mask(env.methodologyNote()));
        }

        Object rawCounters = uiPayload.get("scanCounters");
        if (rawCounters instanceof Map<?, ?> counters) {
            copyIfNumber(badge, counters, "rowsScanned");
            copyIfNumber(badge, counters, "groupsEvaluated");
            copyIfNumber(badge, counters, "findingsCount");
            copyIfNumber(badge, counters, "criticalCount");
            copyIfNumber(badge, counters, "warningCount");
            if (counters.get("totalFinancialExposure") instanceof Number n) {
                badge.put("totalFinancialExposure", n.doubleValue());
            } else if (counters.get("totalFinancialExposure") != null) {
                putIfText(badge, "totalFinancialExposure", counters.get("totalFinancialExposure").toString());
            }
            if (counters.get("truncated") instanceof Boolean b) {
                badge.put("truncated", b);
            }
        }
        putIfText(badge, "failClosedNote", mask(textValue(uiPayload.get("failClosedNote"))));
        putIfText(badge, "errorCode", textValue(uiPayload.get("errorCode")));
        return badge;
    }

    private ObjectNode buildBoxplotWidgetBlock(AgentExtensionArtifact artifact, ObjectMapper objectMapper) {
        Map<String, Object> uiPayload = artifact.uiPayload();
        List<Map<String, Object>> exactRows = extractExactCohortPoints(uiPayload);
        if (exactRows.isEmpty()) {
            return null;
        }
        ObjectNode block = baseChartWidgetBlock(
                artifact,
                objectMapper,
                firstNonBlank(textValue(uiPayload.get("boxplotTitle")), "Dispersão Robusta por Coorte (MAD / ESD)"),
                "Coortes elegíveis com controle FDR (células suprimidas omitidas)");
        ObjectNode inputs = (ObjectNode) block.path("widget").path("inputs");

        String chartId = "agent-intel-boxplot-" + safeSlug(textValue(uiPayload.get("entityKey")));
        ObjectNode chartDocument = inputs.putObject("chartDocument");
        chartDocument.put("version", "0.1.0");
        chartDocument.put("kind", "boxplot");
        chartDocument.put("chartId", chartId);
        ObjectNode docSizing = chartDocument.putObject("sizing");
        docSizing.put("mode", "fixed");
        docSizing.put("height", 260);
        ObjectNode docSource = chartDocument.putObject("source");
        docSource.put("kind", "derived");
        ArrayNode dimensions = chartDocument.putArray("dimensions");
        ObjectNode dim = dimensions.addObject();
        dim.put("field", "label");
        dim.put("role", "category");
        ArrayNode metrics = chartDocument.putArray("metrics");
        ObjectNode metricNode = metrics.addObject();
        metricNode.put("field", "residualValue");
        metricNode.put("aggregation", "sum");
        metricNode.put("label", "Desvio residual");
        ObjectNode boxplotCfg = chartDocument.putObject("boxplot");
        boxplotCfg.put("scaleMethod", "mad");
        boxplotCfg.put("showOutliers", true);
        boxplotCfg.put("showSampleSize", true);
        ObjectNode quartileFields = boxplotCfg.putObject("quartileFields");
        quartileFields.put("median", "median");
        quartileFields.put("outliers", "outliers");
        ObjectNode docLegend = chartDocument.putObject("legend");
        docLegend.put("enabled", false);
        ObjectNode docTooltip = chartDocument.putObject("tooltip");
        docTooltip.put("enabled", true);
        ObjectNode docTheme = chartDocument.putObject("theme");
        docTheme.putObject("surface").put("mode", "embedded");
        appendEpistemicMeta(chartDocument.putObject("epistemicMeta"), artifact.epistemicEnvelope());

        ObjectNode config = inputs.putObject("config");
        config.put("id", chartId);
        config.put("type", "boxplot");
        ObjectNode cfgSizing = config.putObject("sizing");
        cfgSizing.put("mode", "fixed");
        cfgSizing.put("height", 260);
        ObjectNode axes = config.putObject("axes");
        ObjectNode xAxis = axes.putObject("x");
        xAxis.put("field", "label");
        xAxis.put("label", "Coorte");
        xAxis.put("type", "category");
        ObjectNode yAxis = axes.putObject("y");
        yAxis.put("field", "residualValue");
        yAxis.put("label", "Desvio residual");
        yAxis.put("type", "value");
        ArrayNode series = config.putArray("series");
        ObjectNode sNode = series.addObject();
        sNode.put("id", "residualValue");
        sNode.put("name", "Desvio residual");
        ObjectNode sMetric = sNode.putObject("metric");
        sMetric.put("field", "residualValue");
        sMetric.put("aggregation", "sum");
        ObjectNode cfgTheme = config.putObject("theme");
        cfgTheme.putObject("surface").put("mode", "embedded");
        cfgTheme.putObject("legend").put("visible", false);
        cfgTheme.putObject("tooltip").put("enabled", true);

        ArrayNode data = inputs.putArray("data");
        for (Map<String, Object> row : exactRows) {
            ObjectNode masked = objectMapper.valueToTree(OperationalAssistantBlockComposer.maskPiiInStructure(row));
            data.add(masked);
        }
        return block;
    }

    private ObjectNode buildBenfordComboWidgetBlock(AgentExtensionArtifact artifact, ObjectMapper objectMapper) {
        Map<String, Object> uiPayload = artifact.uiPayload();
        List<Map<String, Object>> benfordRows = extractExactBenfordRows(uiPayload);
        if (benfordRows.isEmpty()) {
            return null;
        }
        ObjectNode block = baseChartWidgetBlock(
                artifact,
                objectMapper,
                firstNonBlank(textValue(uiPayload.get("benfordTitle")), "Auditoria Forense Nigrini-Benford"),
                "Faixas de conformidade Nigrini (0.006 / 0.012 / 0.015)");
        ObjectNode inputs = (ObjectNode) block.path("widget").path("inputs");

        String chartId = "agent-intel-benford-" + safeSlug(textValue(uiPayload.get("entityKey")));
        ObjectNode chartDocument = inputs.putObject("chartDocument");
        chartDocument.put("version", "0.1.0");
        chartDocument.put("kind", "combo");
        chartDocument.put("chartId", chartId);
        ObjectNode docSizing = chartDocument.putObject("sizing");
        docSizing.put("mode", "fixed");
        docSizing.put("height", 260);
        ObjectNode docSource = chartDocument.putObject("source");
        docSource.put("kind", "derived");
        ArrayNode dimensions = chartDocument.putArray("dimensions");
        ObjectNode dim = dimensions.addObject();
        dim.put("field", "label");
        dim.put("role", "category");
        ArrayNode metrics = chartDocument.putArray("metrics");
        ObjectNode observedMetric = metrics.addObject();
        observedMetric.put("field", "observedValue");
        observedMetric.put("aggregation", "sum");
        observedMetric.put("label", "Frequência observada");
        ObjectNode expectedMetric = metrics.addObject();
        expectedMetric.put("field", "expectedValue");
        expectedMetric.put("aggregation", "sum");
        expectedMetric.put("label", "Esperado Benford");

        ArrayNode bands = chartDocument.putArray("referenceBands");
        addReferenceBand(bands, "Conformidade estreita (<= 0.006)", 0.0d, 0.006d, "success");
        addReferenceBand(bands, "Conformidade aceitável (0.006 - 0.012)", 0.006d, 0.012d, "info");
        addReferenceBand(bands, "Marginalmente aceitável (0.012 - 0.015)", 0.012d, 0.015d, "warning");
        ObjectNode docLegend = chartDocument.putObject("legend");
        docLegend.put("enabled", true);
        ObjectNode docTooltip = chartDocument.putObject("tooltip");
        docTooltip.put("enabled", true);
        ObjectNode docTheme = chartDocument.putObject("theme");
        docTheme.putObject("surface").put("mode", "embedded");
        appendEpistemicMeta(chartDocument.putObject("epistemicMeta"), artifact.epistemicEnvelope());

        ObjectNode config = inputs.putObject("config");
        config.put("id", chartId);
        config.put("type", "combo");
        ObjectNode cfgSizing = config.putObject("sizing");
        cfgSizing.put("mode", "fixed");
        cfgSizing.put("height", 260);
        ObjectNode axes = config.putObject("axes");
        ObjectNode xAxis = axes.putObject("x");
        xAxis.put("field", "label");
        xAxis.put("label", "Coorte");
        xAxis.put("type", "category");
        ObjectNode yAxis = axes.putObject("y");
        yAxis.put("field", "observedValue");
        yAxis.put("label", "Desvio MAD Benford");
        yAxis.put("type", "value");
        ArrayNode series = config.putArray("series");
        ObjectNode sObserved = series.addObject();
        sObserved.put("id", "observedValue");
        sObserved.put("name", "Frequência observada");
        ObjectNode sObsMetric = sObserved.putObject("metric");
        sObsMetric.put("field", "observedValue");
        sObsMetric.put("aggregation", "sum");
        ObjectNode sExpected = series.addObject();
        sExpected.put("id", "expectedValue");
        sExpected.put("name", "Esperado Benford");
        ObjectNode sExpMetric = sExpected.putObject("metric");
        sExpMetric.put("field", "expectedValue");
        sExpMetric.put("aggregation", "sum");
        ArrayNode cfgBands = config.putArray("referenceBands");
        addReferenceBand(cfgBands, "Conformidade estreita (<= 0.006)", 0.0d, 0.006d, "success");
        addReferenceBand(cfgBands, "Conformidade aceitável (0.006 - 0.012)", 0.006d, 0.012d, "info");
        addReferenceBand(cfgBands, "Marginalmente aceitável (0.012 - 0.015)", 0.012d, 0.015d, "warning");
        ObjectNode cfgTheme = config.putObject("theme");
        cfgTheme.putObject("surface").put("mode", "embedded");
        cfgTheme.putObject("legend").put("visible", true);
        cfgTheme.putObject("tooltip").put("enabled", true);

        ArrayNode data = inputs.putArray("data");
        for (Map<String, Object> row : benfordRows) {
            ObjectNode masked = objectMapper.valueToTree(OperationalAssistantBlockComposer.maskPiiInStructure(row));
            data.add(masked);
        }
        return block;
    }

    private ObjectNode baseChartWidgetBlock(
            AgentExtensionArtifact artifact,
            ObjectMapper objectMapper,
            String title,
            String subtitle) {
        Map<String, Object> uiPayload = artifact.uiPayload();
        ObjectNode block = objectMapper.createObjectNode();
        block.put("type", "widget-block");
        block.put("widgetType", "chart");
        putIfText(block, "title", mask(title));
        putIfText(block, "subtitle", mask(subtitle));
        putIfText(block, "entityKey", textValue(uiPayload.get("entityKey")));
        putIfText(block, "resourceKey", textValue(uiPayload.get("resourceKey")));
        putIfText(block, "resourcePath", textValue(uiPayload.get("resourcePath")));
        appendEpistemicMeta(block.putObject("epistemicMeta"), artifact.epistemicEnvelope());

        ObjectNode widget = block.putObject("widget");
        widget.put("id", "praxis-chart");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("enableCustomization", false);
        return block;
    }

    private static List<Map<String, Object>> extractExactCohortPoints(Map<String, Object> uiPayload) {
        List<Map<String, Object>> raw = asMapList(uiPayload.get("cohortPoints"));
        if (raw.isEmpty()) {
            raw = asMapList(uiPayload.get("findings"));
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            if (!isExactRelease(item)) {
                continue;
            }
            String method = textValue(item.get("method")).toUpperCase(Locale.ROOT);
            if ("NIGRINI_BENFORD".equals(method)) {
                continue;
            }
            if (!method.isEmpty()
                    && !"ROBUST_MAD_ZSCORE".equals(method)
                    && !"GRUBBS_ESD".equals(method)
                    && !"PAIRED_RESIDUAL_SHIFT".equals(method)) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            String cohortKey = firstNonBlank(textValue(item.get("cohortKey")), textValue(item.get("groupKey")));
            point.put("cohortKey", cohortKey);
            point.put("label", firstNonBlank(textValue(item.get("label")), cohortKey));
            copyIfPresent(item, point, "method");
            copyFirstPresent(item, point, "median", "cohortMedian");
            copyFirstPresent(item, point, "mad", "cohortMad");
            copyIfPresent(item, point, "observedValue");
            copyIfPresent(item, point, "expectedValue");
            copyIfPresent(item, point, "residualValue");
            if (item.get("residualValue") != null) {
                point.put("outliers", List.of(item.get("residualValue")));
            }
            copyIfPresent(item, point, "robustScore");
            copyIfPresent(item, point, "qValueFdr");
            copyIfPresent(item, point, "severity");
            copyIfPresent(item, point, "financialImpact");
            point.put("suppressionStatus", "EXACT_RELEASE");
            point.put("suppressed", false);
            filtered.add(point);
        }
        return filtered;
    }

    private static List<Map<String, Object>> extractExactBenfordRows(Map<String, Object> uiPayload) {
        List<Map<String, Object>> explicit = asMapList(uiPayload.get("benfordFindings"));
        List<Map<String, Object>> source = !explicit.isEmpty() ? explicit : asMapList(uiPayload.get("findings"));
        if (source.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : source) {
            if (!isExactRelease(item)) {
                continue;
            }
            String method = textValue(item.get("method")).toUpperCase(Locale.ROOT);
            if (explicit.isEmpty() && !"NIGRINI_BENFORD".equals(method)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            copyIfPresent(item, row, "findingId");
            copyIfPresent(item, row, "method");
            copyIfPresent(item, row, "scope");
            String cohortKey = firstNonBlank(textValue(item.get("cohortKey")), textValue(item.get("groupKey")));
            if (StringUtils.hasText(cohortKey)) {
                row.put("cohortKey", cohortKey);
                row.put("label", firstNonBlank(textValue(item.get("label")), cohortKey));
            } else {
                copyIfPresent(item, row, "label");
            }
            copyIfPresent(item, row, "metricKey");
            copyIfPresent(item, row, "observedValue");
            copyIfPresent(item, row, "expectedValue");
            copyIfPresent(item, row, "residualValue");
            copyIfPresent(item, row, "robustScore");
            copyIfPresent(item, row, "pValue");
            copyIfPresent(item, row, "qValueFdr");
            copyIfPresent(item, row, "effectSize");
            copyIfPresent(item, row, "severity");
            copyIfPresent(item, row, "financialImpact");
            copyIfPresent(item, row, "affectedCount");
            row.put("suppressionStatus", "EXACT_RELEASE");
            row.put("suppressed", false);
            filtered.add(row);
        }
        return filtered;
    }

    private static boolean isExactRelease(Map<String, Object> item) {
        if (item == null || Boolean.TRUE.equals(item.get("suppressed"))) {
            return false;
        }
        String status = textValue(item.get("suppressionStatus"));
        return "EXACT_RELEASE".equalsIgnoreCase(status);
    }

    private static void appendEpistemicMeta(ObjectNode target, AgentEpistemicEnvelope env) {
        if (target == null || env == null) {
            return;
        }
        putIfText(target, "kind", env.epistemicKind());
        putIfText(target, "availabilityState", env.availabilityState());
        target.put("statisticallySignificant", env.statisticallySignificant());
        target.put("sampleSize", env.sampleSize());
        target.put("suppressedCellCount", env.suppressedCellCount());
        if (env.qValueFdr() != null) {
            target.put("qValueFdr", env.qValueFdr());
        }
        putIfText(target, "modelCardId", mask(env.modelCardId()));
        putIfText(target, "provenanceHash", env.provenanceHash());
    }

    private static void addReferenceBand(
            ArrayNode bands,
            String label,
            double minValue,
            double maxValue,
            String tone) {
        ObjectNode band = bands.addObject();
        band.put("label", label);
        band.put("min", minValue);
        band.put("max", maxValue);
        band.put("axis", "y");
        band.put("tone", tone);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> {
                    if (k != null) {
                        copy.put(k.toString(), v);
                    }
                });
                out.add(copy);
            }
        }
        return out;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key) && src.get(key) != null) {
            dst.put(key, src.get(key));
        }
    }

    private static void copyFirstPresent(
            Map<String, Object> src,
            Map<String, Object> dst,
            String primaryKey,
            String fallbackKey) {
        Object val = src.get(primaryKey);
        if (val == null) {
            val = src.get(fallbackKey);
        }
        if (val != null) {
            dst.put(primaryKey, val);
        }
    }

    private static void copyIfNumber(ObjectNode target, Map<?, ?> counters, String field) {
        Object val = counters.get(field);
        if (val instanceof Number n) {
            target.put(field, n.longValue());
        }
    }

    private static void putIfText(ObjectNode node, String field, String value) {
        if (StringUtils.hasText(value)) {
            node.put(field, value.trim());
        }
    }

    private static String textValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (StringUtils.hasText(second) ? second.trim() : null);
    }

    private static String mask(String value) {
        return OperationalAssistantBlockComposer.maskPiiText(value);
    }

    private static String safeSlug(String value) {
        if (!StringUtils.hasText(value)) {
            return "resource";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
