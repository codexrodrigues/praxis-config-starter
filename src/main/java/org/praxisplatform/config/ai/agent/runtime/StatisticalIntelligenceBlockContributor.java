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
        if (!badge.has("groupsEvaluated")) {
            copyIfNumber(badge, uiPayload, "groupsEvaluated");
        }
        putIfText(badge, "failClosedNote", mask(textValue(uiPayload.get("failClosedNote"))));
        putIfText(badge, "errorCode", textValue(uiPayload.get("errorCode")));
        NigriniSummary nigrini = extractNigriniSummary(uiPayload);
        if (nigrini != null) {
            badge.put("nigriniMad", nigrini.mad());
            putIfText(badge, "nigriniConformity", nigrini.conformity());
        }
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
        quartileFields.put("min", "min");
        quartileFields.put("q1", "q1");
        quartileFields.put("median", "median");
        quartileFields.put("q3", "q3");
        quartileFields.put("max", "max");
        quartileFields.put("outliers", "outliers");
        quartileFields.put("sampleSize", "sampleSize");
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
        ObjectNode cfgBoxplot = config.putObject("boxplot");
        cfgBoxplot.put("scaleMethod", "mad");
        cfgBoxplot.put("showOutliers", true);
        cfgBoxplot.put("showSampleSize", true);
        ObjectNode cfgQuartileFields = cfgBoxplot.putObject("quartileFields");
        cfgQuartileFields.put("min", "min");
        cfgQuartileFields.put("q1", "q1");
        cfgQuartileFields.put("median", "median");
        cfgQuartileFields.put("q3", "q3");
        cfgQuartileFields.put("max", "max");
        cfgQuartileFields.put("outliers", "outliers");
        cfgQuartileFields.put("sampleSize", "sampleSize");
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
        NigriniSummary nigrini = extractNigriniSummary(uiPayload);
        String subtitle = nigrini != null
                ? String.format(
                        Locale.ROOT,
                        "Conformidade Nigrini: %s (MAD %.4f)",
                        formatNigriniConformity(nigrini.conformity()),
                        nigrini.mad())
                : "Distribuição dos primeiros dígitos (Lei de Benford)";
        ObjectNode block = baseChartWidgetBlock(
                artifact,
                objectMapper,
                firstNonBlank(textValue(uiPayload.get("benfordTitle")), "Auditoria Forense Nigrini-Benford"),
                subtitle);
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
        observedMetric.put("seriesKind", "bar");
        observedMetric.put("label", "Frequência observada");
        ObjectNode expectedMetric = metrics.addObject();
        expectedMetric.put("field", "expectedValue");
        expectedMetric.put("aggregation", "sum");
        expectedMetric.put("seriesKind", "line");
        expectedMetric.put("label", "Esperado Benford");

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
        xAxis.put("label", "Dígito inicial (1-9)");
        xAxis.put("type", "category");
        ObjectNode yAxis = axes.putObject("y");
        yAxis.put("field", "observedValue");
        yAxis.put("label", "Proporção de frequência");
        yAxis.put("type", "value");
        ArrayNode series = config.putArray("series");
        ObjectNode sObserved = series.addObject();
        sObserved.put("id", "observedValue");
        sObserved.put("name", "Frequência observada");
        sObserved.put("type", "bar");
        ObjectNode sObsMetric = sObserved.putObject("metric");
        sObsMetric.put("field", "observedValue");
        sObsMetric.put("aggregation", "sum");
        ObjectNode sExpected = series.addObject();
        sExpected.put("id", "expectedValue");
        sExpected.put("name", "Esperado Benford");
        sExpected.put("type", "line");
        ObjectNode sExpMetric = sExpected.putObject("metric");
        sExpMetric.put("field", "expectedValue");
        sExpMetric.put("aggregation", "sum");
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
        block.put("widgetType", "praxis-chart");
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
        Map<String, Map<String, Object>> byCohort = new LinkedHashMap<>();
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
            String cohortKey = firstNonBlank(textValue(item.get("cohortKey")), textValue(item.get("groupKey")));
            String dedupKey = method + "|" + cohortKey;
            Map<String, Object> existing = byCohort.get(dedupKey);
            if (existing != null) {
                appendOutliers(item, existing);
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("cohortKey", cohortKey);
            point.put("label", firstNonBlank(textValue(item.get("label")), cohortKey));
            copyIfPresent(item, point, "method");
            copyFirstPresent(item, point, "min", "cohortMin");
            copyFirstPresent(item, point, "q1", "cohortQ1");
            copyFirstPresent(item, point, "median", "cohortMedian");
            copyFirstPresent(item, point, "q3", "cohortQ3");
            copyFirstPresent(item, point, "max", "cohortMax");
            copyFirstPresent(item, point, "mad", "cohortMad");
            copyFirstPresent(item, point, "sampleSize", "cohortSampleSize");
            extractCohortStepsOntoPoint(item, point);
            if (!validateAndNormalizeQuartiles(point)) {
                continue;
            }
            copyIfPresent(item, point, "observedValue");
            copyIfPresent(item, point, "expectedValue");
            copyIfPresent(item, point, "residualValue");
            appendOutliers(item, point);
            copyIfPresent(item, point, "robustScore");
            copyIfPresent(item, point, "qValueFdr");
            copyIfPresent(item, point, "severity");
            copyIfPresent(item, point, "financialImpact");
            point.put("suppressionStatus", "EXACT_RELEASE");
            point.put("suppressed", false);
            byCohort.put(dedupKey, point);
        }
        List<Map<String, Object>> filtered = new ArrayList<>(byCohort.size());
        for (Map<String, Object> point : byCohort.values()) {
            Object rawOutliers = point.get("outliers");
            if (rawOutliers instanceof List<?> list) {
                point.put("outliers", List.copyOf(list));
            }
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
        for (Map<String, Object> item : source) {
            if (!isExactRelease(item)) {
                continue;
            }
            String method = textValue(item.get("method")).toUpperCase(Locale.ROOT);
            if (explicit.isEmpty() && !"NIGRINI_BENFORD".equals(method)) {
                continue;
            }
            List<Map<String, Object>> digitRows = extractBenfordDigitRows(item);
            if (!digitRows.isEmpty()) {
                return digitRows;
            }
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

    private static List<Map<String, Object>> extractBenfordDigitRows(Map<String, Object> item) {
        List<Map<String, Object>> preBuilt = asMapList(item.get("digitDistribution"));
        if (!preBuilt.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>(preBuilt.size());
            for (Map<String, Object> entry : preBuilt) {
                String digit = textValue(entry.get("digit"));
                if (!StringUtils.hasText(digit)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("digit", digit);
                row.put("label", firstNonBlank(textValue(entry.get("label")), "Dígito " + digit));
                copyIfPresent(entry, row, "observedValue");
                copyIfPresent(entry, row, "expectedValue");
                row.put("suppressionStatus", "EXACT_RELEASE");
                row.put("suppressed", false);
                rows.add(row);
            }
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        List<Map<String, Object>> steps = asMapList(item.get("decompositionSteps"));
        if (steps.isEmpty()) {
            return List.of();
        }
        Map<Integer, Object> observed = new java.util.TreeMap<>();
        Map<Integer, Object> expected = new java.util.TreeMap<>();
        for (Map<String, Object> step : steps) {
            String key = textValue(step.get("stepKey"));
            Object val = step.get("value");
            if (val == null || !key.startsWith("benford_digit_") || key.length() < 16) {
                continue;
            }
            char digitChar = key.charAt("benford_digit_".length());
            if (digitChar >= '1' && digitChar <= '9') {
                int digit = digitChar - '0';
                if (key.endsWith("_observed")) {
                    observed.put(digit, val);
                } else if (key.endsWith("_expected")) {
                    expected.put(digit, val);
                }
            }
        }
        if (observed.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(9);
        for (int digit = 1; digit <= 9; digit++) {
            Object obs = observed.get(digit);
            Object exp = expected.get(digit);
            if (obs == null && exp == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("digit", String.valueOf(digit));
            row.put("label", "Dígito " + digit);
            if (obs != null) {
                row.put("observedValue", obs);
            }
            if (exp != null) {
                row.put("expectedValue", exp);
            }
            row.put("suppressionStatus", "EXACT_RELEASE");
            row.put("suppressed", false);
            rows.add(row);
        }
        return rows;
    }

    private static void extractCohortStepsOntoPoint(Map<String, Object> item, Map<String, Object> point) {
        List<Map<String, Object>> steps = asMapList(item.get("decompositionSteps"));
        if (steps.isEmpty()) {
            return;
        }
        for (Map<String, Object> step : steps) {
            String key = textValue(step.get("stepKey"));
            Object val = step.get("value");
            if (val == null) {
                continue;
            }
            switch (key) {
                case "cohort_min" -> point.putIfAbsent("min", val);
                case "cohort_q1" -> point.putIfAbsent("q1", val);
                case "cohort_median" -> point.putIfAbsent("median", val);
                case "cohort_q3" -> point.putIfAbsent("q3", val);
                case "cohort_max" -> point.putIfAbsent("max", val);
                case "cohort_sample_size" -> {
                    if (val instanceof Number n) {
                        point.putIfAbsent("sampleSize", n.longValue());
                    }
                }
                default -> { }
            }
        }
    }

    private static void appendOutliers(Map<String, Object> item, Map<String, Object> target) {
        Object existing = target.get("outliers");
        List<Object> outliers = existing instanceof List<?> list
                ? new ArrayList<>(list)
                : new ArrayList<>();
        if (item.get("outliers") instanceof java.util.Collection<?> explicitOutliers && !explicitOutliers.isEmpty()) {
            for (Object val : explicitOutliers) {
                if (asFiniteDouble(val) != null) {
                    outliers.add(val);
                }
            }
        } else if (asFiniteDouble(item.get("residualValue")) != null) {
            outliers.add(item.get("residualValue"));
        }
        if (!outliers.isEmpty()) {
            target.put("outliers", outliers);
        }
    }

    private static boolean validateAndNormalizeQuartiles(Map<String, Object> point) {
        Double minVal = asFiniteDouble(point.get("min"));
        Double q1Val = asFiniteDouble(point.get("q1"));
        Double medianVal = asFiniteDouble(point.get("median"));
        Double q3Val = asFiniteDouble(point.get("q3"));
        Double maxVal = asFiniteDouble(point.get("max"));
        if (minVal == null || q1Val == null || medianVal == null || q3Val == null || maxVal == null) {
            return false;
        }
        q1Val = Math.min(q1Val, medianVal);
        q3Val = Math.max(q3Val, medianVal);
        minVal = Math.min(minVal, q1Val);
        maxVal = Math.max(maxVal, q3Val);
        point.put("min", minVal);
        point.put("q1", q1Val);
        point.put("median", medianVal);
        point.put("q3", q3Val);
        point.put("max", maxVal);
        return true;
    }

    private static Double firstFiniteDouble(Object... candidates) {
        for (Object candidate : candidates) {
            Double val = asFiniteDouble(candidate);
            if (val != null) {
                return val;
            }
        }
        return null;
    }

    private static Double findStepValue(Object rawSteps, String expectedKey) {
        List<Map<String, Object>> steps = asMapList(rawSteps);
        for (Map<String, Object> step : steps) {
            if (expectedKey.equals(textValue(step.get("stepKey")))) {
                Double val = asFiniteDouble(step.get("value"));
                if (val != null) {
                    return val;
                }
            }
        }
        return null;
    }

    private static Double asFiniteDouble(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    private record NigriniSummary(double mad, String conformity) { }

    private static NigriniSummary extractNigriniSummary(Map<String, Object> uiPayload) {
        if (uiPayload == null || uiPayload.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> explicit = asMapList(uiPayload.get("benfordFindings"));
        List<Map<String, Object>> source = !explicit.isEmpty() ? explicit : asMapList(uiPayload.get("findings"));
        for (Map<String, Object> item : source) {
            if (!isExactRelease(item)) {
                continue;
            }
            String method = textValue(item.get("method")).toUpperCase(Locale.ROOT);
            if (explicit.isEmpty() && !"NIGRINI_BENFORD".equals(method)) {
                continue;
            }
            Double madVal = firstFiniteDouble(
                    item.get("nigriniMad"),
                    findStepValue(item.get("decompositionSteps"), "nigrini_mad"),
                    item.get("mad"),
                    item.get("cohortMad"),
                    item.get("robustScore"));
            if (madVal != null) {
                String conformity = textValue(item.get("nigriniConformity"));
                if (!StringUtils.hasText(conformity)) {
                    conformity = classifyNigriniConformity(madVal);
                }
                return new NigriniSummary(madVal, conformity);
            }
        }
        return null;
    }

    private static String classifyNigriniConformity(double mad) {
        if (mad <= 0.006d) {
            return "CONFORMIDADE_ESTREITA";
        }
        if (mad <= 0.012d) {
            return "CONFORMIDADE_ACEITAVEL";
        }
        if (mad <= 0.015d) {
            return "MARGINALMENTE_ACEITAVEL";
        }
        return "NAO_CONFORMIDADE";
    }

    private static String formatNigriniConformity(String conformity) {
        if (!StringUtils.hasText(conformity)) {
            return "Não avaliada";
        }
        return switch (conformity.trim().toUpperCase(Locale.ROOT)) {
            case "CONFORMIDADE_ESTREITA" -> "Conformidade estreita";
            case "CONFORMIDADE_ACEITAVEL" -> "Conformidade aceitável";
            case "MARGINALMENTE_ACEITAVEL" -> "Marginalmente aceitável";
            default -> "Não conformidade";
        };
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
