package org.praxisplatform.config.ai.agent.bridge;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.spi.AgentAnomalyScanRequest;
import org.praxisplatform.config.ai.agent.spi.AgentCounterfactualSearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEpistemicEnvelope;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalAnalysisResult;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalInferenceRequest;
import org.praxisplatform.config.ai.agent.spi.AgentStochasticSimulationRequest;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyColumnMapping;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyDecompositionStep;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyFinding;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyMethod;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanSummary;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalySeverity;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualCandidate;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualDirection;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualFeasibilityStatus;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverDelta;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverSpec;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualLeverType;
import org.praxisplatform.intelligence.contracts.counterfactual.CounterfactualResponse;
import org.praxisplatform.intelligence.contracts.epistemic.EpistemicEnvelope;
import org.praxisplatform.intelligence.contracts.error.IntelligenceErrorCode;
import org.praxisplatform.intelligence.contracts.error.IntelligenceExecutionException;
import org.praxisplatform.intelligence.contracts.inference.InferenceGroupResult;
import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;
import org.praxisplatform.intelligence.contracts.inference.InferenceTestKind;
import org.praxisplatform.intelligence.contracts.provenance.SuppressionStatus;
import org.praxisplatform.intelligence.contracts.simulation.AnchorRevalidationStatus;
import org.praxisplatform.intelligence.contracts.simulation.AnchorScenarioPoint;
import org.praxisplatform.intelligence.contracts.simulation.DependenceMethod;
import org.praxisplatform.intelligence.contracts.simulation.FactorCorrelation;
import org.praxisplatform.intelligence.contracts.simulation.FactorDistributionKind;
import org.praxisplatform.intelligence.contracts.simulation.FanChartPoint;
import org.praxisplatform.intelligence.contracts.simulation.SamplingStrategy;
import org.praxisplatform.intelligence.contracts.simulation.SimulationModelPoint;
import org.praxisplatform.intelligence.contracts.simulation.SimulationThresholdSpec;
import org.praxisplatform.intelligence.contracts.simulation.StochasticFactorSpec;
import org.praxisplatform.intelligence.contracts.simulation.StochasticSimulationResponse;
import org.praxisplatform.intelligence.contracts.simulation.ThresholdBreachOutcome;
import org.praxisplatform.intelligence.contracts.simulation.ThresholdDirection;
import org.praxisplatform.intelligence.contracts.simulation.TornadoSensitivityDriver;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.dto.AnomalyScanStatsRequest;
import org.praxisplatform.uischema.stats.dto.CounterfactualStatsRequest;
import org.praxisplatform.uischema.stats.dto.InferenceStatsRequest;
import org.praxisplatform.uischema.stats.dto.StochasticSimulationStatsRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Isolates references to {@code praxis-intelligence-contracts} inside an optional bridge helper so
 * {@code praxis-config-starter} runs cleanly when intelligence contracts are absent on the classpath.
 */
final class MetadataStarterStatisticalIntelligenceSupport {

    private static final String NON_ANOMALY_NOTE =
            "Variacao estatisticamente nao significativa apos controle FDR; nao classificar como anomalia.";

    private MetadataStarterStatisticalIntelligenceSupport() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static AgentStatisticalAnalysisResult runInference(
            BaseResourceQueryService<?, ?, ?> service,
            GenericFilterDTO filterDto,
            String entityKey,
            String resourceKey,
            String resourcePath,
            String resourceTitle,
            Set<String> blockedFields,
            AgentStatisticalInferenceRequest request) {
        if (request.tests() == null || request.tests().isEmpty()) {
            throw new IllegalArgumentException("At least one inference test kind is required.");
        }
        List<InferenceTestKind> tests = new ArrayList<>(request.tests().size());
        for (String rawTest : request.tests()) {
            if (!StringUtils.hasText(rawTest)) {
                throw new IllegalArgumentException("Inference test entry must not be blank.");
            }
            tests.add(InferenceTestKind.valueOf(rawTest.trim().toUpperCase(Locale.ROOT)));
        }

        enforceNotBlocked(blockedFields, request.groupByField());
        enforceNotBlocked(blockedFields, request.expectedField());
        enforceNotBlocked(blockedFields, request.observedField());
        enforceNotBlocked(blockedFields, request.residualField());

        InferenceStatsRequest statsRequest = new InferenceStatsRequest(
                filterDto,
                UUID.randomUUID().toString(),
                resourceKey,
                tests,
                trimToNull(request.groupByField()),
                trimToNull(request.expectedField()),
                trimToNull(request.observedField()),
                trimToNull(request.residualField()),
                request.variableOrRetroactiveEligible(),
                request.alpha(),
                request.materialityThreshold(),
                request.minBucketCount(),
                request.seed(),
                request.asOfDate());

        String title = (StringUtils.hasText(resourceTitle) ? resourceTitle.trim() : entityKey)
                + " — Inferência Estatística";
        try {
            InferenceResponse response = ((BaseResourceQueryService) service).inferenceStats(statsRequest);
            AgentEpistemicEnvelope envelope = toAgentEnvelope(
                    EpistemicEnvelope.fromInferenceResponse(response, resourceKey));
            return buildInferenceResult(entityKey, resourceKey, resourcePath, title, envelope, response);
        } catch (IntelligenceExecutionException ex) {
            if (ex.errorCode() == IntelligenceErrorCode.INVALID_REQUEST) {
                throw new IllegalArgumentException(ex.detail(), ex);
            }
            return failClosedResult(
                    entityKey,
                    resourceKey,
                    resourcePath,
                    title,
                    "inference",
                    mapErrorCodeToAvailability(ex.errorCode()),
                    ex.errorCode().code(),
                    ex.detail());
        } catch (UnsupportedOperationException ex) {
            return failClosedResult(
                    entityKey,
                    resourceKey,
                    resourcePath,
                    title,
                    "inference",
                    AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE,
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code(),
                    firstNonBlank(ex.getMessage(), "Statistical inference not supported or disabled on this resource."));
        } catch (ResponseStatusException ex) {
            return mapStatusExceptionFailClosed(
                    entityKey, resourceKey, resourcePath, title, "inference", ex);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static AgentStatisticalAnalysisResult scanAnomalies(
            BaseResourceQueryService<?, ?, ?> service,
            GenericFilterDTO filterDto,
            String entityKey,
            String resourceKey,
            String resourcePath,
            String resourceTitle,
            Set<String> blockedFields,
            AgentAnomalyScanRequest request) {
        if (request.methods() == null || request.methods().isEmpty()) {
            throw new IllegalArgumentException("At least one anomaly scan method is required.");
        }
        Set<AnomalyMethod> methods = new LinkedHashSet<>();
        for (String rawMethod : request.methods()) {
            if (!StringUtils.hasText(rawMethod)) {
                throw new IllegalArgumentException("Anomaly scan method entry must not be blank.");
            }
            methods.add(AnomalyMethod.valueOf(rawMethod.trim().toUpperCase(Locale.ROOT)));
        }

        AnomalyColumnMapping canonical = AnomalyColumnMapping.canonical();
        Map<String, String> colMap = request.columnMapping() != null ? request.columnMapping() : Map.of();
        AnomalyColumnMapping resolvedMapping = new AnomalyColumnMapping(
                firstNonBlank(colMap.get("entityKeyColumn"), canonical.entityKeyColumn()),
                firstNonBlank(colMap.get("cohortColumn"), canonical.cohortColumn()),
                firstNonBlank(colMap.get("groupColumn"), canonical.groupColumn()),
                firstNonBlank(colMap.get("metricColumn"), canonical.metricColumn()),
                firstNonBlank(colMap.get("expectedColumn"), canonical.expectedColumn()),
                firstNonBlank(colMap.get("observedColumn"), canonical.observedColumn()),
                firstNonBlank(colMap.get("residualColumn"), canonical.residualColumn()),
                firstNonBlank(colMap.get("variableFlagColumn"), canonical.variableFlagColumn()),
                firstNonBlank(colMap.get("unitColumn"), canonical.unitColumn()));

        colMap.forEach((slot, fieldName) -> enforceNotBlocked(blockedFields, fieldName));
        enforceNotBlocked(blockedFields, resolvedMapping.entityKeyColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.cohortColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.groupColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.metricColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.expectedColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.observedColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.residualColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.variableFlagColumn());
        enforceNotBlocked(blockedFields, resolvedMapping.unitColumn());

        AnomalyScanStatsRequest statsRequest = new AnomalyScanStatsRequest(
                filterDto,
                UUID.randomUUID().toString(),
                resourceKey,
                methods,
                resolvedMapping,
                request.alpha(),
                request.materialityThreshold(),
                request.madThreshold(),
                request.minCohortSize(),
                request.minGroupSize(),
                request.enableBenford(),
                request.minBenfordSamples(),
                request.minBucketCount(),
                request.maxFindings(),
                request.seed(),
                request.asOfDate());

        String title = (StringUtils.hasText(resourceTitle) ? resourceTitle.trim() : entityKey)
                + " — Varredura Estatística de Anomalias";
        try {
            AnomalyScanResponse response = ((BaseResourceQueryService) service).anomalyScanStats(statsRequest);
            double effectiveAlpha = request.alpha() != null ? request.alpha() : 0.05d;
            AgentEpistemicEnvelope envelope = toAgentEnvelope(
                    EpistemicEnvelope.fromAnomalyScanResponse(response, resourceKey, effectiveAlpha));
            return buildAnomalyScanResult(
                    entityKey, resourceKey, resourcePath, title, envelope, response, effectiveAlpha);
        } catch (IntelligenceExecutionException ex) {
            if (ex.errorCode() == IntelligenceErrorCode.INVALID_REQUEST) {
                throw new IllegalArgumentException(ex.detail(), ex);
            }
            return failClosedResult(
                    entityKey,
                    resourceKey,
                    resourcePath,
                    title,
                    "anomaly-scan",
                    mapErrorCodeToAvailability(ex.errorCode()),
                    ex.errorCode().code(),
                    ex.detail());
        } catch (UnsupportedOperationException ex) {
            return failClosedResult(
                    entityKey,
                    resourceKey,
                    resourcePath,
                    title,
                    "anomaly-scan",
                    AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE,
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code(),
                    firstNonBlank(ex.getMessage(), "Anomaly scanning not supported or disabled on this resource."));
        } catch (ResponseStatusException ex) {
            return mapStatusExceptionFailClosed(
                    entityKey, resourceKey, resourcePath, title, "anomaly-scan", ex);
        }
    }

    private static AgentStatisticalAnalysisResult buildInferenceResult(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            AgentEpistemicEnvelope envelope,
            InferenceResponse response) {
        List<Map<String, Object>> sanitizedGroups = new ArrayList<>(response.results().size());
        for (InferenceGroupResult r : response.results()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("groupKey", mask(r.groupKey()));
            entry.put("cohortKey", mask(r.groupKey()));
            entry.put("suppressionStatus", r.suppressionStatus().name());
            if (r.suppressionStatus() != SuppressionStatus.EXACT_RELEASE) {
                entry.put("suppressed", true);
                sanitizedGroups.add(entry);
                continue;
            }
            entry.put("suppressed", false);
            entry.put("testKind", r.testKind().name());
            entry.put("statistic", r.statistic());
            entry.put("pValue", r.pValue());
            entry.put("qValueFdr", r.qValueFdr());
            entry.put("effectSize", r.effectSize());
            if (StringUtils.hasText(r.effectSizeKind())) {
                entry.put("effectSizeKind", mask(r.effectSizeKind()));
            }
            if (r.ciLow() != null) {
                entry.put("ciLow", r.ciLow());
            }
            if (r.ciHigh() != null) {
                entry.put("ciHigh", r.ciHigh());
            }
            entry.put("sampleSizeA", r.sampleSizeA());
            entry.put("sampleSizeB", r.sampleSizeB());
            if (r.aggregateImpact() != null) {
                entry.put("aggregateImpact", r.aggregateImpact());
            }
            entry.put("statisticallySignificant", r.statisticallySignificant());
            entry.put("materiallySignificant", r.materiallySignificant());
            if (StringUtils.hasText(r.methodNotes())) {
                entry.put("methodNotes", mask(r.methodNotes()));
            }
            if (!r.statisticallySignificant()) {
                entry.put("nonAnomalyNote", NON_ANOMALY_NOTE);
            }
            sanitizedGroups.add(entry);
        }

        Map<String, Object> modelPayload = new LinkedHashMap<>();
        populateBaseMetadata(modelPayload, entityKey, resourceKey, resourcePath, title, "inference", envelope);
        modelPayload.put("groups", sanitizedGroups);

        Map<String, Object> uiPayload = new LinkedHashMap<>(modelPayload);
        uiPayload.put("groupsEvaluated", response.results().size());
        Map<String, Object> scanCounters = new LinkedHashMap<>();
        if (uiPayload.get("scanCounters") instanceof Map<?, ?> existingCounters) {
            existingCounters.forEach((k, v) -> {
                if (k != null && v != null) {
                    scanCounters.put(String.valueOf(k), v);
                }
            });
        }
        scanCounters.putIfAbsent("groupsEvaluated", response.results().size());
        uiPayload.put("scanCounters", Map.copyOf(scanCounters));
        return new AgentStatisticalAnalysisResult(
                entityKey, resourceKey, resourcePath, title, "inference", envelope, modelPayload, uiPayload);
    }

    private static AgentStatisticalAnalysisResult buildAnomalyScanResult(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            AgentEpistemicEnvelope envelope,
            AnomalyScanResponse response,
            double effectiveAlpha) {
        List<Map<String, Object>> sanitizedFindings = new ArrayList<>(response.findings().size());
        List<Map<String, Object>> cohortPoints = new ArrayList<>();
        List<Map<String, Object>> benfordFindings = new ArrayList<>();

        for (AnomalyFinding f : response.findings()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("findingId", f.findingId());
            entry.put("method", f.method().name());
            entry.put("scope", f.scope().name());
            if (StringUtils.hasText(f.cohortKey())) {
                entry.put("cohortKey", mask(f.cohortKey()));
                entry.put("label", mask(f.cohortKey()));
            }
            entry.put("suppressionStatus", f.suppressionStatus().name());

            if (f.suppressionStatus() != SuppressionStatus.EXACT_RELEASE) {
                entry.put("suppressed", true);
                sanitizedFindings.add(entry);
                continue;
            }

            entry.put("suppressed", false);
            if (StringUtils.hasText(f.opaqueEntityKey())) {
                entry.put("opaqueEntityKey", mask(f.opaqueEntityKey()));
            }
            if (StringUtils.hasText(f.metricKey())) {
                entry.put("metricKey", mask(f.metricKey()));
            }
            if (f.observedValue() != null) {
                entry.put("observedValue", f.observedValue());
            }
            if (f.expectedValue() != null) {
                entry.put("expectedValue", f.expectedValue());
            }
            if (f.residualValue() != null) {
                entry.put("residualValue", f.residualValue());
            }
            if (f.cohortMedian() != null) {
                entry.put("cohortMedian", f.cohortMedian());
                entry.put("median", f.cohortMedian());
            }
            if (f.cohortMad() != null) {
                entry.put("cohortMad", f.cohortMad());
                entry.put("mad", f.cohortMad());
            }
            entry.put("robustScore", f.robustScore());
            if (f.pValue() != null) {
                entry.put("pValue", f.pValue());
            }
            if (f.qValueFdr() != null) {
                entry.put("qValueFdr", f.qValueFdr());
            }
            if (f.effectSize() != null) {
                entry.put("effectSize", f.effectSize());
            }
            entry.put("severity", f.severity().name());
            if (f.financialImpact() != null) {
                entry.put("financialImpact", f.financialImpact());
            }
            entry.put("affectedCount", f.affectedCount());
            if (!f.decompositionSteps().isEmpty()) {
                List<Map<String, Object>> steps = new ArrayList<>(f.decompositionSteps().size());
                Map<Integer, Double> benfordObserved = new java.util.TreeMap<>();
                Map<Integer, Double> benfordExpected = new java.util.TreeMap<>();
                for (AnomalyDecompositionStep step : f.decompositionSteps()) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("stepKey", step.stepKey());
                    s.put("label", mask(step.label()));
                    s.put("value", step.value());
                    if (StringUtils.hasText(step.unit())) {
                        s.put("unit", step.unit());
                    }
                    s.put("role", step.role().name());
                    steps.add(s);

                    extractStructuredStepMetric(step, entry, benfordObserved, benfordExpected);
                }
                entry.put("decompositionSteps", steps);
                if (f.method() == AnomalyMethod.NIGRINI_BENFORD && !benfordObserved.isEmpty()) {
                    List<Map<String, Object>> digits = new ArrayList<>(9);
                    for (int digit = 1; digit <= 9; digit++) {
                        Double obs = benfordObserved.get(digit);
                        Double exp = benfordExpected.get(digit);
                        if (obs == null && exp == null) {
                            continue;
                        }
                        Map<String, Object> digitRow = new LinkedHashMap<>();
                        digitRow.put("digit", String.valueOf(digit));
                        digitRow.put("label", "Dígito " + digit);
                        if (obs != null) {
                            digitRow.put("observedValue", obs);
                        }
                        if (exp != null) {
                            digitRow.put("expectedValue", exp);
                        }
                        digits.add(digitRow);
                    }
                    if (!digits.isEmpty()) {
                        entry.put("digitDistribution", digits);
                    }
                }
            }
            boolean nonSignificant = f.qValueFdr() != null
                    ? f.qValueFdr() > effectiveAlpha
                    : f.severity() == AnomalySeverity.INFO;
            if (nonSignificant) {
                entry.put("nonAnomalyNote", NON_ANOMALY_NOTE);
            }
            sanitizedFindings.add(entry);

            if (f.method() == AnomalyMethod.NIGRINI_BENFORD) {
                benfordFindings.add(entry);
            } else if (f.method() == AnomalyMethod.ROBUST_MAD_ZSCORE
                    || f.method() == AnomalyMethod.GRUBBS_ESD
                    || f.method() == AnomalyMethod.PAIRED_RESIDUAL_SHIFT) {
                cohortPoints.add(entry);
            }
        }

        AnomalyScanSummary scanSummary = response.summary();
        Map<String, Object> scanCounters = new LinkedHashMap<>();
        scanCounters.put("rowsScanned", scanSummary.rowsScanned());
        scanCounters.put("groupsEvaluated", scanSummary.groupsEvaluated());
        scanCounters.put("findingsCount", scanSummary.findingsTotal());
        scanCounters.put("criticalCount", scanSummary.criticalCount());
        scanCounters.put("warningCount", scanSummary.warningCount());
        scanCounters.put("suppressedCells", scanSummary.suppressedCells());
        if (scanSummary.totalFinancialExposure() != null) {
            scanCounters.put("totalFinancialExposure", scanSummary.totalFinancialExposure());
        }
        scanCounters.put("truncated", scanSummary.truncated());

        Map<String, Object> modelPayload = new LinkedHashMap<>();
        populateBaseMetadata(modelPayload, entityKey, resourceKey, resourcePath, title, "anomaly-scan", envelope);
        modelPayload.put("scanCounters", scanCounters);
        modelPayload.put("findings", sanitizedFindings);

        Map<String, Object> uiPayload = new LinkedHashMap<>(modelPayload);
        uiPayload.put("cohortPoints", cohortPoints);
        uiPayload.put("benfordFindings", benfordFindings);
        return new AgentStatisticalAnalysisResult(
                entityKey, resourceKey, resourcePath, title, "anomaly-scan", envelope, modelPayload, uiPayload);
    }

    private static void extractStructuredStepMetric(
            AnomalyDecompositionStep step,
            Map<String, Object> entry,
            Map<Integer, Double> benfordObserved,
            Map<Integer, Double> benfordExpected) {
        if (step == null || !StringUtils.hasText(step.stepKey()) || step.value() == null) {
            return;
        }
        String key = step.stepKey().trim();
        switch (key) {
            case "cohort_min" -> entry.putIfAbsent("min", step.value());
            case "cohort_q1" -> entry.putIfAbsent("q1", step.value());
            case "cohort_median" -> entry.putIfAbsent("median", step.value());
            case "cohort_q3" -> entry.putIfAbsent("q3", step.value());
            case "cohort_max" -> entry.putIfAbsent("max", step.value());
            case "cohort_sample_size" -> entry.putIfAbsent("sampleSize", step.value().longValue());
            case "nigrini_mad" -> {
                if (step.value() != null) {
                    entry.put("nigriniMad", step.value());
                    entry.put("nigriniConformity", classifyNigriniConformity(step.value().doubleValue()));
                }
            }
            default -> {
                if (key.startsWith("benford_digit_") && key.length() >= 16) {
                    char digitChar = key.charAt("benford_digit_".length());
                    if (digitChar >= '1' && digitChar <= '9') {
                        int digit = digitChar - '0';
                        if (key.endsWith("_observed")) {
                            benfordObserved.put(digit, step.value().doubleValue());
                        } else if (key.endsWith("_expected")) {
                            benfordExpected.put(digit, step.value().doubleValue());
                        }
                    }
                }
            }
        }
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static AgentStatisticalAnalysisResult runStochasticSimulation(
            BaseResourceQueryService service,
            GenericFilterDTO filterDto,
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            Set<String> blockedFields,
            AgentStochasticSimulationRequest request) {
        if (service == null || request == null) {
            throw new IllegalArgumentException("service and request are required");
        }
        enforceNotBlocked(blockedFields, request.datasetKey());
        List<SimulationModelPoint> modelPoints = convertModelPoints(request.modelPoints(), blockedFields);
        List<StochasticFactorSpec> factors = convertStochasticFactors(request.stochasticFactors(), blockedFields);
        List<FactorCorrelation> correlations = convertCorrelations(request.correlations(), blockedFields);
        List<SimulationThresholdSpec> thresholds = convertThresholds(request.thresholds(), blockedFields);

        SamplingStrategy samplingStrategy = parseEnum(
                SamplingStrategy.class, request.samplingStrategy(), SamplingStrategy.QUASI_MONTE_CARLO_SOBOL);
        DependenceMethod dependenceMethod = parseEnum(
                DependenceMethod.class, request.dependenceMethod(), DependenceMethod.IMAN_CONOVER);
        int scenarioCount = request.scenarioCount() != null ? request.scenarioCount() : 2000;
        int horizonPeriods = request.horizonPeriods() != null ? request.horizonPeriods() : 12;
        String datasetKey = resourceKey;
        String analysisTitle = firstNonBlank(title, entityKey) + " — Simulação Estocástica";

        StochasticSimulationStatsRequest<GenericFilterDTO> statsRequest = new StochasticSimulationStatsRequest<>(
                filterDto,
                UUID.randomUUID().toString(),
                datasetKey,
                scenarioCount,
                horizonPeriods,
                request.seed(),
                request.asOfDate(),
                samplingStrategy,
                dependenceMethod,
                modelPoints,
                factors,
                correlations,
                thresholds,
                request.quantiles(),
                request.anchorQuantiles(),
                request.minBucketCount());

        StochasticSimulationResponse response;
        try {
            response = service.stochasticSimulationStats(statsRequest);
        } catch (IntelligenceExecutionException ex) {
            if (ex.errorCode() == IntelligenceErrorCode.INVALID_REQUEST) {
                throw new IllegalArgumentException(firstNonBlank(ex.detail(), ex.getMessage()), ex);
            }
            String availability = mapErrorCodeToAvailability(ex.errorCode());
            String code = ex.errorCode() != null ? ex.errorCode().code() : "INTELLIGENCE_EXECUTION_ERROR";
            return failClosedResult(
                    entityKey, resourceKey, resourcePath, analysisTitle,
                    "stochastic-simulation", availability, code, firstNonBlank(ex.detail(), ex.getMessage()));
        } catch (UnsupportedOperationException ex) {
            return failClosedResult(
                    entityKey, resourceKey, resourcePath, analysisTitle,
                    "stochastic-simulation", AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE,
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code(),
                    firstNonBlank(ex.getMessage(), "Simulação estocástica não suportada ou desabilitada neste recurso."));
        } catch (ResponseStatusException ex) {
            return mapStatusExceptionFailClosed(
                    entityKey, resourceKey, resourcePath, analysisTitle, "stochastic-simulation", ex);
        }

        if (response == null) {
            return failClosedResult(
                    entityKey, resourceKey, resourcePath, analysisTitle,
                    "stochastic-simulation", AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE,
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code(),
                    "Motor de simulação estocástica não retornou resposta.");
        }

        AgentEpistemicEnvelope envelope = toAgentEnvelope(
                EpistemicEnvelope.fromStochasticSimulationResponse(response, datasetKey));
        return buildStochasticSimulationResult(
                entityKey, resourceKey, resourcePath, analysisTitle,
                samplingStrategy, dependenceMethod, thresholds, blockedFields, envelope, response);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static AgentStatisticalAnalysisResult runCounterfactualSearch(
            BaseResourceQueryService service,
            GenericFilterDTO filterDto,
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            Set<String> blockedFields,
            AgentCounterfactualSearchRequest request) {
        if (service == null || request == null) {
            throw new IllegalArgumentException("service and request are required");
        }
        enforceNotBlocked(blockedFields, request.datasetKey());
        enforceNotBlocked(blockedFields, request.targetMetricKey());
        List<CounterfactualLeverSpec> levers = convertMutableLevers(request.mutableLevers(), blockedFields);
        CounterfactualDirection direction = parseEnum(
                CounterfactualDirection.class, request.direction(), CounterfactualDirection.DECREASE_TO_TARGET);
        String datasetKey = resourceKey;
        String analysisTitle = firstNonBlank(title, entityKey) + " — Análise Contrafactual";

        CounterfactualStatsRequest<GenericFilterDTO> statsRequest = new CounterfactualStatsRequest<>(
                filterDto,
                UUID.randomUUID().toString(),
                datasetKey,
                request.targetMetricKey(),
                direction,
                request.baselineValue(),
                request.targetValue(),
                request.targetUpperBound(),
                levers,
                request.maxCandidates(),
                request.maxChangedLevers(),
                request.seed(),
                request.asOfDate());

        CounterfactualResponse response;
        try {
            response = service.counterfactualStats(statsRequest);
        } catch (IntelligenceExecutionException ex) {
            if (ex.errorCode() == IntelligenceErrorCode.INVALID_REQUEST) {
                throw new IllegalArgumentException(firstNonBlank(ex.detail(), ex.getMessage()), ex);
            }
            String availability = mapErrorCodeToAvailability(ex.errorCode());
            String code = ex.errorCode() != null ? ex.errorCode().code() : "INTELLIGENCE_EXECUTION_ERROR";
            return failClosedResult(
                    entityKey, resourceKey, resourcePath, analysisTitle,
                    "counterfactual-search", availability, code, firstNonBlank(ex.detail(), ex.getMessage()));
        } catch (UnsupportedOperationException ex) {
            return failClosedResult(
                    entityKey, resourceKey, resourcePath, analysisTitle,
                    "counterfactual-search", AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE,
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code(),
                    firstNonBlank(ex.getMessage(), "Busca contrafactual não suportada ou desabilitada neste recurso."));
        } catch (ResponseStatusException ex) {
            return mapStatusExceptionFailClosed(
                    entityKey, resourceKey, resourcePath, analysisTitle, "counterfactual-search", ex);
        }

        if (response == null) {
            return failClosedResult(
                    entityKey, resourceKey, resourcePath, analysisTitle,
                    "counterfactual-search", AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE,
                    IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code(),
                    "Motor de busca contrafactual não retornou resposta.");
        }

        AgentEpistemicEnvelope envelope = toAgentEnvelope(
                EpistemicEnvelope.fromCounterfactualResponse(response, datasetKey));
        return buildCounterfactualResult(
                entityKey, resourceKey, resourcePath, analysisTitle,
                request.targetMetricKey(), direction, envelope, response);
    }

    private static AgentStatisticalAnalysisResult buildStochasticSimulationResult(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            SamplingStrategy samplingStrategy,
            DependenceMethod dependenceMethod,
            List<SimulationThresholdSpec> requestedThresholds,
            Set<String> blockedFields,
            AgentEpistemicEnvelope envelope,
            StochasticSimulationResponse response) {
        List<Map<String, Object>> fanChart = new ArrayList<>();
        if (response.fanSeries() != null) {
            for (FanChartPoint pt : response.fanSeries()) {
                if (pt == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("periodIndex", pt.periodIndex());
                row.put("periodLabel", "T+" + pt.periodIndex());
                if (pt.mean() != null) {
                    row.put("mean", pt.mean());
                }
                if (pt.stdDev() != null) {
                    row.put("stdDev", pt.stdDev());
                }
                Map<String, BigDecimal> quantilesOut = new LinkedHashMap<>();
                if (pt.quantiles() != null) {
                    pt.quantiles().forEach((k, v) -> {
                        if (k != null && v != null) {
                            String normalized = normalizeQuantileKey(k);
                            quantilesOut.put(normalized, v);
                            row.put(normalized, v);
                        }
                    });
                }
                row.put("quantiles", quantilesOut);
                if (pt.byFundingSource() != null && !pt.byFundingSource().isEmpty()) {
                    Map<String, BigDecimal> bySource = new LinkedHashMap<>();
                    pt.byFundingSource().forEach((k, v) -> {
                        if (k != null && v != null && !isBlockedField(blockedFields, k)) {
                            bySource.put(mask(k), v);
                        }
                    });
                    if (!bySource.isEmpty()) {
                        row.put("byFundingSource", bySource);
                    }
                }
                fanChart.add(row);
            }
        }

        Map<String, SimulationThresholdSpec> thresholdSpecsById = new LinkedHashMap<>();
        if (requestedThresholds != null) {
            for (SimulationThresholdSpec spec : requestedThresholds) {
                if (spec != null && StringUtils.hasText(spec.thresholdId())) {
                    thresholdSpecsById.put(spec.thresholdId().trim(), spec);
                }
            }
        }

        List<Map<String, Object>> thresholdOutcomes = new ArrayList<>();
        if (response.thresholdBreaches() != null) {
            for (ThresholdBreachOutcome outcome : response.thresholdBreaches()) {
                if (outcome == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("thresholdId", mask(outcome.thresholdId()));
                SimulationThresholdSpec matchedSpec = StringUtils.hasText(outcome.thresholdId())
                        ? thresholdSpecsById.get(outcome.thresholdId().trim())
                        : null;
                row.put("label", mask(firstNonBlank(
                        matchedSpec != null ? matchedSpec.label() : null,
                        outcome.thresholdId())));
                if (matchedSpec != null && matchedSpec.limitValue() != null) {
                    row.put("limitValue", matchedSpec.limitValue());
                }
                if (outcome.direction() != null) {
                    row.put("direction", outcome.direction().name());
                }
                if (StringUtils.hasText(outcome.fundingSourceKey())) {
                    row.put("fundingSourceKey", mask(outcome.fundingSourceKey()));
                }
                row.put("breachProbability", outcome.breachProbability());
                row.put("ciLow", outcome.ciLow());
                row.put("ciHigh", outcome.ciHigh());
                if (outcome.expectedShortfallOrExcess() != null) {
                    row.put("expectedShortfallOrExcess", outcome.expectedShortfallOrExcess());
                }
                if (outcome.safeCutoffPeriodIndex() != null) {
                    row.put("safeCutoffPeriodIndex", outcome.safeCutoffPeriodIndex());
                }
                thresholdOutcomes.add(row);
            }
        }

        List<Map<String, Object>> tornadoDrivers = new ArrayList<>();
        if (response.tornadoDrivers() != null) {
            List<TornadoSensitivityDriver> sorted = response.tornadoDrivers().stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator
                            .comparing(TornadoSensitivityDriver::swingMagnitude,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparingInt(TornadoSensitivityDriver::rank))
                    .toList();
            for (TornadoSensitivityDriver driver : sorted) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("factorKey", mask(driver.factorKey()));
                row.put("factorLabel", mask(driver.factorKey()));
                row.put("rankCorrelation", driver.spearmanCorrelation());
                if (driver.p10ConditionalOutcome() != null) {
                    row.put("p10Outcome", driver.p10ConditionalOutcome());
                }
                if (driver.p90ConditionalOutcome() != null) {
                    row.put("p90Outcome", driver.p90ConditionalOutcome());
                }
                if (driver.swingMagnitude() != null) {
                    row.put("swingMagnitude", driver.swingMagnitude());
                }
                row.put("rank", driver.rank());
                tornadoDrivers.add(row);
            }
        }

        List<Map<String, Object>> anchorScenarios = new ArrayList<>();
        boolean allAnchorsWithinTolerance = response.anchorScenarios() != null && !response.anchorScenarios().isEmpty();
        Double maxAnchorRelativeErrorPct = null;
        if (response.anchorScenarios() != null) {
            for (AnchorScenarioPoint anchor : response.anchorScenarios()) {
                if (anchor == null) {
                    continue;
                }
                if (anchor.revalidationStatus() != AnchorRevalidationStatus.REVALIDATED_WITHIN_TOLERANCE) {
                    allAnchorsWithinTolerance = false;
                }
                if (anchor.relativeDriftPct() != null) {
                    double absErr = Math.abs(anchor.relativeDriftPct());
                    maxAnchorRelativeErrorPct = maxAnchorRelativeErrorPct == null
                            ? absErr
                            : Math.max(maxAnchorRelativeErrorPct, absErr);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                if (StringUtils.hasText(anchor.anchorLabel())) {
                    row.put("anchorLabel", mask(anchor.anchorLabel()));
                }
                row.put("quantile", anchor.quantile());
                row.put("scenarioIndex", anchor.scenarioIndex());
                if (anchor.surrogateTerminalValue() != null) {
                    row.put("monteCarloValue", anchor.surrogateTerminalValue());
                }
                if (anchor.exactRevalidatedValue() != null) {
                    row.put("deterministicBigDecimalValue", anchor.exactRevalidatedValue());
                }
                if (anchor.relativeDriftPct() != null) {
                    row.put("relativeErrorPct", anchor.relativeDriftPct());
                }
                if (anchor.revalidationStatus() != null) {
                    row.put("revalidationStatus", anchor.revalidationStatus().name());
                }
                if (anchor.realizedFactorValues() != null && !anchor.realizedFactorValues().isEmpty()) {
                    Map<String, Double> realized = new LinkedHashMap<>();
                    anchor.realizedFactorValues().forEach((k, v) -> {
                        if (k != null && v != null && !isBlockedField(blockedFields, k)) {
                            realized.put(mask(k), v);
                        }
                    });
                    if (!realized.isEmpty()) {
                        row.put("realizedFactorValues", realized);
                    }
                }
                anchorScenarios.add(row);
            }
        }

        boolean degraded = !AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE.equals(envelope.availabilityState());
        Map<String, Object> modelPayload = new LinkedHashMap<>();
        populateBaseMetadata(modelPayload, entityKey, resourceKey, resourcePath, title, "stochastic-simulation", envelope);
        modelPayload.put("availabilityState", envelope.availabilityState());
        modelPayload.put("advisoryOnly", true);
        modelPayload.put("scenarioCount", response.scenarioCount());
        modelPayload.put("horizonPeriods", response.horizonPeriods());
        modelPayload.put("effectiveSamplingStrategy", samplingStrategy.name());
        modelPayload.put("effectiveDependenceMethod", dependenceMethod.name());
        modelPayload.put("suppressedModelPoints", response.suppressedModelPoints());
        modelPayload.put("suppressionStatus", response.suppressionStatus().name());
        if (response.terminalMean() != null) {
            modelPayload.put("terminalMean", response.terminalMean());
        }
        if (response.terminalStdDev() != null) {
            modelPayload.put("terminalStdDev", response.terminalStdDev());
        }
        if (response.terminalCvar95() != null) {
            modelPayload.put("terminalCvar95", response.terminalCvar95());
        }
        if (response.terminalQuantiles() != null && !response.terminalQuantiles().isEmpty()) {
            Map<String, BigDecimal> normalizedTerminal = new LinkedHashMap<>();
            response.terminalQuantiles().forEach((k, v) -> {
                if (k != null && v != null) {
                    normalizedTerminal.put(normalizeQuantileKey(k), v);
                }
            });
            modelPayload.put("terminalQuantiles", normalizedTerminal);
        }
        modelPayload.put("allAnchorsWithinTolerance", allAnchorsWithinTolerance);
        if (maxAnchorRelativeErrorPct != null) {
            modelPayload.put("maxAnchorRelativeErrorPct", maxAnchorRelativeErrorPct);
        }
        if (degraded) {
            String degradedCode;
            if (AgentEpistemicEnvelope.AVAILABILITY_INSUFFICIENT_SAMPLE.equals(envelope.availabilityState())) {
                degradedCode = IntelligenceErrorCode.INSUFFICIENT_SAMPLE_SIZE.code();
            } else if (AgentEpistemicEnvelope.AVAILABILITY_K_ANONYMITY_SUPPRESSED.equals(envelope.availabilityState())) {
                degradedCode = IntelligenceErrorCode.K_ANONYMITY_SUPPRESSED.code();
            } else if (!allAnchorsWithinTolerance) {
                degradedCode = "ANCHOR_REVALIDATION_DEGRADED";
            } else {
                degradedCode = "STOCHASTIC_SIMULATION_DEGRADED";
            }
            modelPayload.put("errorCode", degradedCode);
            modelPayload.put("failClosedNote", mask(firstNonBlank(
                    envelope.methodologyNote(),
                    "Simulação estocástica degradada em modo fail-closed após revalidação determinística.")));
            modelPayload.put("fanChart", List.of());
            modelPayload.put("thresholdOutcomes", List.of());
            modelPayload.put("tornadoDrivers", List.of());
        } else {
            modelPayload.put("fanChart", fanChart);
            modelPayload.put("thresholdOutcomes", thresholdOutcomes);
            modelPayload.put("tornadoDrivers", tornadoDrivers);
        }
        modelPayload.put("anchorScenarios", anchorScenarios);

        Map<String, Object> uiPayload = new LinkedHashMap<>(modelPayload);
        return new AgentStatisticalAnalysisResult(
                entityKey, resourceKey, resourcePath, title, "stochastic-simulation", envelope, modelPayload, uiPayload);
    }

    private static AgentStatisticalAnalysisResult buildCounterfactualResult(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            String targetMetricKey,
            CounterfactualDirection direction,
            AgentEpistemicEnvelope envelope,
            CounterfactualResponse response) {
        List<Map<String, Object>> validatedCandidates = new ArrayList<>();
        int nonValidatedInList = 0;
        if (response.candidates() != null) {
            for (CounterfactualCandidate candidate : response.candidates()) {
                if (candidate == null
                        || candidate.feasibilityStatus() != CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE) {
                    nonValidatedInList++;
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("candidateId", mask(candidate.candidateId()));
                entry.put("rank", candidate.rank());
                if (candidate.predictedValue() != null) {
                    entry.put("predictedValue", candidate.predictedValue());
                }
                if (candidate.predictedDelta() != null) {
                    entry.put("predictedDelta", candidate.predictedDelta());
                }
                entry.put("distanceScore", candidate.distanceScore());
                entry.put("feasibilityStatus", CounterfactualFeasibilityStatus.VALIDATED_BY_RULES_ENGINE.name());
                if (candidate.ruleValidationMessages() != null && !candidate.ruleValidationMessages().isEmpty()) {
                    entry.put("ruleValidationMessages", candidate.ruleValidationMessages().stream()
                            .map(MetadataStarterStatisticalIntelligenceSupport::mask)
                            .toList());
                } else {
                    entry.put("ruleValidationMessages", List.of());
                }
                List<Map<String, Object>> deltas = new ArrayList<>();
                if (candidate.leverDeltas() != null) {
                    for (CounterfactualLeverDelta delta : candidate.leverDeltas()) {
                        if (delta == null) {
                            continue;
                        }
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("leverKey", mask(delta.leverKey()));
                        if (delta.fromValue() != null) {
                            d.put("fromValue", maskObjectValue(delta.fromValue()));
                        }
                        if (delta.toValue() != null) {
                            d.put("toValue", maskObjectValue(delta.toValue()));
                        }
                        if (delta.relativeChange() != null) {
                            d.put("relativeChange", delta.relativeChange());
                        }
                        if (delta.estimatedContribution() != null) {
                            d.put("estimatedContribution", delta.estimatedContribution());
                        }
                        deltas.add(d);
                    }
                }
                entry.put("leverDeltas", deltas);
                validatedCandidates.add(entry);
            }
        }

        boolean available = AgentEpistemicEnvelope.AVAILABILITY_AVAILABLE.equals(envelope.availabilityState());
        List<Map<String, Object>> exposedCandidates = available ? validatedCandidates : List.of();
        int totalExcluded = Math.max(0, response.rejectedCount()) + nonValidatedInList
                + (available ? 0 : validatedCandidates.size());
        int rawCandidateCount = response.candidates() != null ? response.candidates().size() : 0;

        Map<String, Object> modelPayload = new LinkedHashMap<>();
        populateBaseMetadata(modelPayload, entityKey, resourceKey, resourcePath, title, "counterfactual-search", envelope);
        modelPayload.put("availabilityState", envelope.availabilityState());
        modelPayload.put("advisoryOnly", true);
        modelPayload.put("requiresHumanGovernanceApproval", true);
        if (StringUtils.hasText(targetMetricKey)) {
            modelPayload.put("targetMetricKey", mask(targetMetricKey));
        }
        modelPayload.put("direction", direction.name());
        if (response.baselineValue() != null) {
            modelPayload.put("baselineValue", response.baselineValue());
        }
        if (response.targetValue() != null) {
            modelPayload.put("targetValue", response.targetValue());
        }
        modelPayload.put("converged", response.converged());
        modelPayload.put("evaluatedCandidateCount", rawCandidateCount + Math.max(0, response.rejectedCount()));
        modelPayload.put("validatedCandidateCount", exposedCandidates.size());
        modelPayload.put("excludedCandidateCount", totalExcluded);
        modelPayload.put("candidates", exposedCandidates);
        if (!available) {
            String cfCode;
            if (!response.converged()) {
                cfCode = IntelligenceErrorCode.NON_CONVERGENT_OPTIMIZER.code();
            } else if (validatedCandidates.isEmpty() && response.rejectedCount() > 0) {
                cfCode = "ALL_CANDIDATES_REJECTED_BY_RULES";
            } else {
                cfCode = "COUNTERFACTUAL_UNVERIFIED_OR_DEGRADED";
            }
            modelPayload.put("errorCode", cfCode);
            modelPayload.put("failClosedNote", mask(firstNonBlank(
                    envelope.methodologyNote(),
                    "Busca contrafactual em modo fail-closed sem recomendações elegíveis.")));
        }

        Map<String, Object> uiPayload = new LinkedHashMap<>(modelPayload);
        return new AgentStatisticalAnalysisResult(
                entityKey, resourceKey, resourcePath, title, "counterfactual-search", envelope, modelPayload, uiPayload);
    }

    private static List<SimulationModelPoint> convertModelPoints(
            List<Map<String, Object>> rawPoints,
            Set<String> blockedFields) {
        if (rawPoints == null || rawPoints.isEmpty()) {
            return List.of();
        }
        List<SimulationModelPoint> points = new ArrayList<>(rawPoints.size());
        int index = 0;
        for (Map<String, Object> raw : rawPoints) {
            index++;
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String pointKey = firstNonBlank(stringVal(raw.get("pointKey")), "point_" + index);
            String segmentLabel = stringVal(raw.get("segmentLabel"));
            String fundingSourceKey = stringVal(raw.get("fundingSourceKey"));
            enforceNotBlocked(blockedFields, pointKey);
            enforceNotBlocked(blockedFields, segmentLabel);
            enforceNotBlocked(blockedFields, fundingSourceKey);
            Map<String, Double> factorSensitivities = doubleMapVal(raw.get("factorSensitivities"));
            for (String sensKey : factorSensitivities.keySet()) {
                enforceNotBlocked(blockedFields, sensKey);
            }
            Integer weightCount = intVal(raw.get("weightCount"), 1);
            BigDecimal baseAmount = bigDecimalVal(raw.get("baseAmount"), BigDecimal.ZERO);
            List<Double> growthFactors = doubleListVal(raw.get("periodGrowthFactors"));
            BigDecimal fixedComponent = bigDecimalVal(raw.get("fixedComponent"), BigDecimal.ZERO);
            points.add(new SimulationModelPoint(
                    pointKey,
                    segmentLabel,
                    fundingSourceKey,
                    weightCount,
                    baseAmount,
                    growthFactors,
                    factorSensitivities,
                    fixedComponent));
        }
        return points;
    }

    private static List<StochasticFactorSpec> convertStochasticFactors(
            List<Map<String, Object>> rawFactors,
            Set<String> blockedFields) {
        if (rawFactors == null || rawFactors.isEmpty()) {
            return List.of();
        }
        List<StochasticFactorSpec> out = new ArrayList<>(rawFactors.size());
        for (Map<String, Object> raw : rawFactors) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String factorKey = stringVal(raw.get("factorKey"));
            enforceNotBlocked(blockedFields, factorKey);
            FactorDistributionKind dist = parseEnum(
                    FactorDistributionKind.class, stringVal(raw.get("distribution")), FactorDistributionKind.NORMAL);
            Map<String, Double> parameters = doubleMapVal(raw.get("parameters"));
            List<Double> empiricalQuantiles = doubleListVal(raw.get("empiricalQuantiles"));
            List<Double> empiricalValues = doubleListVal(raw.get("empiricalValues"));
            Double lowerBound = doubleVal(raw.get("lowerBound"), null);
            Double upperBound = doubleVal(raw.get("upperBound"), null);
            boolean applyPerPeriod = Boolean.TRUE.equals(raw.get("applyPerPeriod"));
            out.add(new StochasticFactorSpec(
                    factorKey, dist, parameters, empiricalQuantiles, empiricalValues, lowerBound, upperBound, applyPerPeriod));
        }
        return out;
    }

    private static List<FactorCorrelation> convertCorrelations(
            List<Map<String, Object>> rawCorrelations,
            Set<String> blockedFields) {
        if (rawCorrelations == null || rawCorrelations.isEmpty()) {
            return List.of();
        }
        List<FactorCorrelation> out = new ArrayList<>(rawCorrelations.size());
        for (Map<String, Object> raw : rawCorrelations) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String factorA = stringVal(raw.get("factorA"));
            String factorB = stringVal(raw.get("factorB"));
            enforceNotBlocked(blockedFields, factorA);
            enforceNotBlocked(blockedFields, factorB);
            double rankCorrelation = doubleVal(raw.get("rankCorrelation"), 0.0d);
            out.add(new FactorCorrelation(factorA, factorB, rankCorrelation));
        }
        return out;
    }

    private static List<SimulationThresholdSpec> convertThresholds(
            List<Map<String, Object>> rawThresholds,
            Set<String> blockedFields) {
        if (rawThresholds == null || rawThresholds.isEmpty()) {
            return List.of();
        }
        List<SimulationThresholdSpec> out = new ArrayList<>(rawThresholds.size());
        for (Map<String, Object> raw : rawThresholds) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String thresholdId = firstNonBlank(stringVal(raw.get("thresholdId")), stringVal(raw.get("id")));
            String label = firstNonBlank(stringVal(raw.get("label")), thresholdId);
            String fundingSourceKey = stringVal(raw.get("fundingSourceKey"));
            enforceNotBlocked(blockedFields, thresholdId);
            enforceNotBlocked(blockedFields, fundingSourceKey);
            ThresholdDirection dir = parseEnum(
                    ThresholdDirection.class, stringVal(raw.get("direction")), ThresholdDirection.UPPER_CAP);
            BigDecimal limitValue = bigDecimalVal(raw.get("limitValue"), BigDecimal.ZERO);
            Integer targetPeriodIndex = intVal(raw.get("targetPeriodIndex"), null);
            out.add(new SimulationThresholdSpec(thresholdId, label, dir, limitValue, fundingSourceKey, targetPeriodIndex));
        }
        return out;
    }

    private static List<CounterfactualLeverSpec> convertMutableLevers(
            List<Map<String, Object>> rawLevers,
            Set<String> blockedFields) {
        if (rawLevers == null || rawLevers.isEmpty()) {
            return List.of();
        }
        List<CounterfactualLeverSpec> out = new ArrayList<>(rawLevers.size());
        for (Map<String, Object> raw : rawLevers) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            String leverKey = stringVal(raw.get("leverKey"));
            enforceNotBlocked(blockedFields, leverKey);
            CounterfactualLeverType leverType = parseEnum(
                    CounterfactualLeverType.class, stringVal(raw.get("leverType")), CounterfactualLeverType.CONTINUOUS);
            Double minValue = doubleVal(raw.get("minValue"), null);
            Double maxValue = doubleVal(raw.get("maxValue"), null);
            Double stepSize = doubleVal(raw.get("stepSize"), null);
            List<String> allowedCategories = stringListVal(raw.get("allowedCategories"));
            for (String cat : allowedCategories) {
                enforceNotBlocked(blockedFields, cat);
            }
            double costWeight = doubleVal(raw.get("costWeight"), 1.0d);
            double linearSensitivity = doubleVal(raw.get("linearSensitivity"), 1.0d);
            Map<String, Double> categoryImpacts = doubleMapVal(raw.get("categoryImpacts"));
            for (String impactKey : categoryImpacts.keySet()) {
                enforceNotBlocked(blockedFields, impactKey);
            }
            int monotoneSign = intVal(raw.get("monotoneSign"), 0);
            out.add(new CounterfactualLeverSpec(
                    leverKey,
                    leverType,
                    raw.get("currentValue"),
                    minValue,
                    maxValue,
                    stepSize,
                    allowedCategories,
                    costWeight,
                    linearSensitivity,
                    categoryImpacts,
                    monotoneSign));
        }
        return out;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, E defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        return Enum.valueOf(enumType, raw.trim().toUpperCase(Locale.ROOT));
    }

    private static String normalizeQuantileKey(String rawKey) {
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("p")) {
            String digits = key.substring(1);
            if (digits.length() == 1 && Character.isDigit(digits.charAt(0))) {
                return "p0" + digits;
            }
            return key;
        }
        try {
            double val = Double.parseDouble(key);
            if (val > 0.0d && val < 1.0d) {
                long pct = Math.round(val * 100.0d);
                return String.format(Locale.ROOT, "p%02d", pct);
            }
        } catch (NumberFormatException ignored) {
            // Keep original normalized token
        }
        return rawKey.trim();
    }

    private static Object maskObjectValue(Object value) {
        if (value instanceof String s) {
            return mask(s);
        }
        return value;
    }

    private static String stringVal(Object value) {
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private static Integer intVal(Object value, Integer defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Integer.parseInt(s.trim());
        }
        return defaultValue;
    }

    private static Double doubleVal(Object value, Double defaultValue) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Double.parseDouble(s.trim());
        }
        return defaultValue;
    }

    private static BigDecimal bigDecimalVal(Object value, BigDecimal defaultValue) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return new BigDecimal(s.trim());
        }
        return defaultValue;
    }

    private static List<Double> doubleListVal(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            Double parsed = doubleVal(item, null);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    private static List<String> stringListVal(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item != null && StringUtils.hasText(item.toString())) {
                out.add(item.toString().trim());
            }
        }
        return out;
    }

    private static Map<String, Double> doubleMapVal(Object value) {
        if (!(value instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            Double parsed = doubleVal(v, null);
            if (k != null && parsed != null) {
                out.put(k.toString().trim(), parsed);
            }
        });
        return out;
    }

    private static String epistemicKindFor(String analysisKind) {
        if ("stochastic-simulation".equals(analysisKind)) {
            return AgentEpistemicEnvelope.KIND_STOCHASTIC_SIMULATION;
        }
        if ("counterfactual-search".equals(analysisKind)) {
            return AgentEpistemicEnvelope.KIND_OPTIMIZATION_RESULT;
        }
        return AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY;
    }

    private static void populateBaseMetadata(
            Map<String, Object> target,
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            String analysisKind,
            AgentEpistemicEnvelope envelope) {
        target.put("entityKey", entityKey);
        target.put("resourceKey", resourceKey);
        target.put("resourcePath", resourcePath);
        target.put("title", mask(title));
        target.put("analysisKind", analysisKind);
        target.put("epistemicEnvelope", envelopeToMap(envelope));
    }

    private static AgentStatisticalAnalysisResult failClosedResult(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            String analysisKind,
            String availabilityState,
            String errorCode,
            String note) {
        String epistemicKind = epistemicKindFor(analysisKind);
        AgentEpistemicEnvelope envelope = switch (availabilityState) {
            case AgentEpistemicEnvelope.AVAILABILITY_INSUFFICIENT_SAMPLE ->
                    AgentEpistemicEnvelope.insufficientSample(
                            epistemicKind, resourceKey, null, 0L, note);
            case AgentEpistemicEnvelope.AVAILABILITY_K_ANONYMITY_SUPPRESSED ->
                    AgentEpistemicEnvelope.suppressed(
                            epistemicKind, resourceKey, null, 0L, 1, note);
            case AgentEpistemicEnvelope.AVAILABILITY_DEGRADED ->
                    new AgentEpistemicEnvelope(
                            epistemicKind,
                            AgentEpistemicEnvelope.AVAILABILITY_DEGRADED,
                            false,
                            null,
                            0L,
                            0,
                            null,
                            null,
                            resourceKey,
                            note);
            default -> AgentEpistemicEnvelope.unavailable(
                    epistemicKind, resourceKey, null, note);
        };
        Map<String, Object> modelPayload = new LinkedHashMap<>();
        populateBaseMetadata(modelPayload, entityKey, resourceKey, resourcePath, title, analysisKind, envelope);
        modelPayload.put("availabilityState", availabilityState);
        modelPayload.put("errorCode", errorCode);
        modelPayload.put("failClosedNote", mask(firstNonBlank(note, "Analytical execution unavailable in fail-closed mode.")));
        modelPayload.put("findings", List.of());
        modelPayload.put("groups", List.of());
        if ("stochastic-simulation".equals(analysisKind)) {
            modelPayload.put("advisoryOnly", true);
            modelPayload.put("fanChart", List.of());
            modelPayload.put("thresholdOutcomes", List.of());
            modelPayload.put("tornadoDrivers", List.of());
            modelPayload.put("anchorScenarios", List.of());
        } else if ("counterfactual-search".equals(analysisKind)) {
            modelPayload.put("advisoryOnly", true);
            modelPayload.put("requiresHumanGovernanceApproval", true);
            modelPayload.put("validatedCandidateCount", 0);
            modelPayload.put("excludedCandidateCount", 0);
            modelPayload.put("candidates", List.of());
        }

        Map<String, Object> uiPayload = new LinkedHashMap<>(modelPayload);
        return new AgentStatisticalAnalysisResult(
                entityKey, resourceKey, resourcePath, title, analysisKind, envelope, modelPayload, uiPayload);
    }

    private static AgentStatisticalAnalysisResult mapStatusExceptionFailClosed(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            String analysisKind,
            ResponseStatusException ex) {
        int code = ex.getStatusCode().value();
        if (code == 400 || code == 403 || code == 404) {
            throw ex;
        }
        String classificationHint = firstNonBlank(ex.getReason(), ex.getMessage());
        String lower = classificationHint != null ? classificationHint.toLowerCase(Locale.ROOT) : "";
        String safeReason = sanitizeFailClosedReason(ex.getReason(), code);
        String availability;
        String errCode;
        if (code == 503 || code == 501) {
            availability = AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE;
            errCode = code == 501
                    ? "STATISTICAL_OPERATION_NOT_IMPLEMENTED"
                    : IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code();
        } else if (lower.contains("k_anonym") || lower.contains("anonim") || lower.contains("suppressed")) {
            availability = AgentEpistemicEnvelope.AVAILABILITY_K_ANONYMITY_SUPPRESSED;
            errCode = IntelligenceErrorCode.K_ANONYMITY_SUPPRESSED.code();
        } else if (lower.contains("sample") || lower.contains("amostra") || lower.contains("insufficient")) {
            availability = AgentEpistemicEnvelope.AVAILABILITY_INSUFFICIENT_SAMPLE;
            errCode = IntelligenceErrorCode.INSUFFICIENT_SAMPLE_SIZE.code();
        } else if (code == 409
                || lower.contains("drift")
                || lower.contains("converg")
                || lower.contains("singular")
                || lower.contains("revalidat")) {
            availability = AgentEpistemicEnvelope.AVAILABILITY_DEGRADED;
            errCode = lower.contains("converg")
                    ? IntelligenceErrorCode.NON_CONVERGENT_OPTIMIZER.code()
                    : IntelligenceErrorCode.DRIFT_CIRCUIT_OPEN.code();
        } else {
            availability = AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE;
            errCode = IntelligenceErrorCode.INTELLIGENCE_ENGINE_UNAVAILABLE.code();
        }
        return failClosedResult(entityKey, resourceKey, resourcePath, title, analysisKind, availability, errCode, safeReason);
    }

    private static String sanitizeFailClosedReason(String reason, int httpStatus) {
        if (StringUtils.hasText(reason)) {
            String singleLine = reason.replaceAll("[\\p{Cntrl}]+", " ").trim();
            return singleLine.length() > 300 ? singleLine.substring(0, 300) : singleLine;
        }
        return switch (httpStatus) {
            case 501 -> "Statistical operation not implemented on this resource.";
            case 503 -> "Statistical intelligence runtime unavailable.";
            default -> "Analytical execution unavailable in fail-closed mode.";
        };
    }

    private static String mapErrorCodeToAvailability(IntelligenceErrorCode code) {
        if (code == null) {
            return AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE;
        }
        return switch (code) {
            case INSUFFICIENT_SAMPLE_SIZE -> AgentEpistemicEnvelope.AVAILABILITY_INSUFFICIENT_SAMPLE;
            case K_ANONYMITY_SUPPRESSED -> AgentEpistemicEnvelope.AVAILABILITY_K_ANONYMITY_SUPPRESSED;
            case NON_CONVERGENT_OPTIMIZER, SINGULAR_MATRIX, DRIFT_CIRCUIT_OPEN, PAYLOAD_TOO_LARGE ->
                    AgentEpistemicEnvelope.AVAILABILITY_DEGRADED;
            case INTELLIGENCE_ENGINE_UNAVAILABLE, CONTRACT_VERSION_MISMATCH, JOB_TIMEOUT, INTERNAL_AUTH_FAILED, INVALID_REQUEST ->
                    AgentEpistemicEnvelope.AVAILABILITY_ENGINE_UNAVAILABLE;
        };
    }

    private static AgentEpistemicEnvelope toAgentEnvelope(EpistemicEnvelope src) {
        return new AgentEpistemicEnvelope(
                src.epistemicKind().name(),
                src.availabilityState().name(),
                src.statisticallySignificant(),
                src.qValueFdr(),
                src.sampleSize(),
                src.suppressedCellCount(),
                src.provenanceHash(),
                src.modelCardId(),
                src.datasetKey(),
                src.methodologyNote());
    }

    private static Map<String, Object> envelopeToMap(AgentEpistemicEnvelope env) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("epistemicKind", env.epistemicKind());
        map.put("availabilityState", env.availabilityState());
        map.put("statisticallySignificant", env.statisticallySignificant());
        if (env.qValueFdr() != null) {
            map.put("qValueFdr", env.qValueFdr());
        }
        map.put("sampleSize", env.sampleSize());
        map.put("suppressedCellCount", env.suppressedCellCount());
        if (StringUtils.hasText(env.provenanceHash())) {
            map.put("provenanceHash", env.provenanceHash());
        }
        if (StringUtils.hasText(env.modelCardId())) {
            map.put("modelCardId", mask(env.modelCardId()));
        }
        map.put("datasetKey", mask(env.datasetKey()));
        if (StringUtils.hasText(env.methodologyNote())) {
            map.put("methodologyNote", mask(env.methodologyNote()));
        }
        return map;
    }

    private static boolean isBlockedField(Set<String> blockedFields, String field) {
        if (blockedFields == null || blockedFields.isEmpty() || !StringUtils.hasText(field)) {
            return false;
        }
        String normalized = field.trim().toLowerCase(Locale.ROOT);
        if (blockedFields.contains(normalized)) {
            return true;
        }
        int dotIdx = normalized.indexOf('.');
        if (dotIdx > 0 && blockedFields.contains(normalized.substring(0, dotIdx))) {
            return true;
        }
        int lastDotIdx = normalized.lastIndexOf('.');
        if (lastDotIdx >= 0
                && lastDotIdx + 1 < normalized.length()
                && blockedFields.contains(normalized.substring(lastDotIdx + 1))) {
            return true;
        }
        for (String segment : normalized.split("\\.")) {
            if (!segment.isBlank() && blockedFields.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static void enforceNotBlocked(Set<String> blockedFields, String field) {
        if (isBlockedField(blockedFields, field)) {
            throw new SecurityException("Stats field restricted by governance policy: " + field.trim());
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : (StringUtils.hasText(second) ? second.trim() : null);
    }

    private static String mask(String value) {
        return OperationalAssistantBlockComposer.maskPiiText(value);
    }
}
