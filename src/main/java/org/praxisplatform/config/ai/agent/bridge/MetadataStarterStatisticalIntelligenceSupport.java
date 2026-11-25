package org.praxisplatform.config.ai.agent.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.spi.AgentAnomalyScanRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEpistemicEnvelope;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalAnalysisResult;
import org.praxisplatform.config.ai.agent.spi.AgentStatisticalInferenceRequest;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyColumnMapping;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyDecompositionStep;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyFinding;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyMethod;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanResponse;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalyScanSummary;
import org.praxisplatform.intelligence.contracts.anomaly.AnomalySeverity;
import org.praxisplatform.intelligence.contracts.epistemic.EpistemicEnvelope;
import org.praxisplatform.intelligence.contracts.error.IntelligenceErrorCode;
import org.praxisplatform.intelligence.contracts.error.IntelligenceExecutionException;
import org.praxisplatform.intelligence.contracts.inference.InferenceGroupResult;
import org.praxisplatform.intelligence.contracts.inference.InferenceResponse;
import org.praxisplatform.intelligence.contracts.inference.InferenceTestKind;
import org.praxisplatform.intelligence.contracts.provenance.SuppressionStatus;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.stats.dto.AnomalyScanStatsRequest;
import org.praxisplatform.uischema.stats.dto.InferenceStatsRequest;
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
        AgentEpistemicEnvelope envelope = switch (availabilityState) {
            case AgentEpistemicEnvelope.AVAILABILITY_INSUFFICIENT_SAMPLE ->
                    AgentEpistemicEnvelope.insufficientSample(
                            AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY, resourceKey, null, 0L, note);
            case AgentEpistemicEnvelope.AVAILABILITY_K_ANONYMITY_SUPPRESSED ->
                    AgentEpistemicEnvelope.suppressed(
                            AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY, resourceKey, null, 0L, 1, note);
            case AgentEpistemicEnvelope.AVAILABILITY_DEGRADED ->
                    new AgentEpistemicEnvelope(
                            AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY,
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
                    AgentEpistemicEnvelope.KIND_CALIBRATED_PROBABILITY, resourceKey, null, note);
        };
        Map<String, Object> modelPayload = new LinkedHashMap<>();
        populateBaseMetadata(modelPayload, entityKey, resourceKey, resourcePath, title, analysisKind, envelope);
        modelPayload.put("availabilityState", availabilityState);
        modelPayload.put("errorCode", errorCode);
        modelPayload.put("failClosedNote", mask(firstNonBlank(note, "Analytical execution unavailable in fail-closed mode.")));
        modelPayload.put("findings", List.of());
        modelPayload.put("groups", List.of());

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

    private static void enforceNotBlocked(Set<String> blockedFields, String field) {
        if (StringUtils.hasText(field)
                && blockedFields.contains(field.trim().toLowerCase(Locale.ROOT))) {
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
