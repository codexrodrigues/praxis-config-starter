package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService.SchemaFieldDescriptor;
import org.praxisplatform.config.service.SchemaFetchResult;

/**
 * Package-private helper responsible for reconciling semantic chart axes, filter fields, KPI
 * dimensions, and metric bindings against governed {@code /schemas/filtered} contracts, as well as
 * pruning orphan widgets, bindings, canvas items, groupings, device layouts, and slot assignments.
 */
final class AgenticAuthoringSemanticAxisSchemaVerifier {

    private static final Set<String> SEMANTIC_AXIS_STOP_WORDS = Set.of(
            "por",
            "para",
            "com",
            "dos",
            "das",
            "the",
            "and",
            "total",
            "registros",
            "registro");

    private final ObjectMapper objectMapper;

    AgenticAuthoringSemanticAxisSchemaVerifier(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    void reconcileSemanticAxesWithSchema(
            AgenticAuthoringPlanRequest request,
            ObjectNode uiCompositionPlan,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            Map<String, SchemaFieldDescriptor> statsRequestFields,
            SchemaFetchResult schemaResult,
            List<String> warnings,
            boolean allowSchemaSafeAxisRepair) {
        Set<String> contextTokens = authoringContextTokens(request, uiCompositionPlan);
        Set<String> promptTokens = authoringPromptTokens(request);
        Set<String> promptGroupingTokens = authoringPromptGroupingTokens(request);
        Set<String> promptAxisTokens = promptGroupingTokens.isEmpty() ? promptTokens : promptGroupingTokens;
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (axes.isArray()) {
            for (JsonNode axis : axes) {
                if (axis instanceof ObjectNode axisObject) {
                    Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
                    if (schemaField.isPresent()) {
                        alignSemanticAxis(axisObject, schemaField.get(), schemaResult);
                    } else {
                        markSemanticAxisUnsupported(axisObject);
                        addWarningOnce(warnings, "semantic-axis-schema-verification-unsupported-axis");
                    }
                }
            }
        }
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets instanceof ArrayNode widgetArray) {
            Set<String> assignedChartFields = exactSafeChartFields(widgetArray, schemaFields);
            for (int i = widgetArray.size() - 1; i >= 0; i--) {
                JsonNode widget = widgetArray.get(i);
                alignAuxiliaryWidgetBindings(widget, schemaFields, filterSchemaFields, schemaResult, warnings);
                alignDataQueryContextFilters(widget, filterSchemaFields, schemaResult, warnings);
                JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
                if (!(axis instanceof ObjectNode axisObject)) {
                    continue;
                }
                String semanticallySelectedField = axisObject.path("field").asText("");
                Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
                boolean exactSemanticSchemaField = schemaFields.containsKey(normalize(semanticallySelectedField))
                        || (schemaField.isPresent()
                                && isCanonicalDisplayProjection(semanticallySelectedField, schemaField.get().name()));
                schemaField = governedStatsAxisField(widget, schemaField, schemaFields, statsRequestFields, warnings);
                if (schemaField.isPresent() && isSafeGenericGroupByChartField(schemaField.get(), widget)) {
                    // An exact schema field selected by the semantic decision is already grounded.
                    // Prompt token scoring is only a repair mechanism for unresolved/approximate fields;
                    // allowing it here would replace a canonical LLM decision with lexical coincidence.
                    Optional<SchemaFieldDescriptor> promptAlignedSchemaField = exactSemanticSchemaField
                            ? Optional.empty()
                            : promptAlignedSafeGroupingField(
                                    schemaFields,
                                    widget,
                                    assignedChartFields,
                                    promptAxisTokens,
                                    schemaField.get());
                    SchemaFieldDescriptor selectedField = promptAlignedSchemaField.orElse(schemaField.get());
                    String requestedField = axisObject.path("field").asText("");
                    String previousAxisLabel = axisObject.path("label").asText("");
                    alignSemanticAxis(axisObject, selectedField, schemaResult);
                    alignChartBinding(widget, selectedField);
                    alignTemporalChartOperation(widget, selectedField, warnings);
                    alignChartMetricBinding(widget, schemaFields, schemaResult, warnings, contextTokens);
                    if (!normalize(requestedField).equals(normalize(selectedField.name()))) {
                        alignDiagnosticsAxis(uiCompositionPlan, requestedField, selectedField, schemaResult);
                    }
                    if (promptAlignedSchemaField.isPresent()) {
                        alignChartDisplayText(widget, previousAxisLabel, selectedField);
                        alignDiagnosticsAxis(uiCompositionPlan, requestedField, selectedField, schemaResult);
                        addWarningOnce(warnings, "semantic-chart-axis-repaired-with-prompt-aligned-schema-field");
                    }
                    assignedChartFields.add(normalize(selectedField.name()));
                } else {
                    boolean allowGenericAxisRepair = allowsGenericInferredAxisRepair(axisObject);
                    boolean allowStatsAxisRepair = allowsStatsAxisRepair(axisObject, widget);
                    boolean allowContextualAxisRepair = allowsContextualAxisRepair(axisObject, contextTokens);
                    boolean allowStatusLikeAxisRepair = isStatusLikeConcept(semanticAxisTokens(axisObject));
                    Optional<SchemaFieldDescriptor> repairedSchemaField = Optional.empty();
                    boolean repairedFromPrompt = false;
                    if (allowSchemaSafeAxisRepair
                            || allowGenericAxisRepair
                            || allowStatsAxisRepair
                            || allowContextualAxisRepair
                            || allowStatusLikeAxisRepair) {
                        repairedSchemaField = promptAlignedSafeGroupingField(
                                schemaFields,
                                widget,
                                assignedChartFields,
                                promptAxisTokens,
                                schemaField.orElse(null));
                        repairedFromPrompt = repairedSchemaField.isPresent();
                        boolean allowsSchemaScoredFallback = allowSchemaSafeAxisRepair
                                || allowGenericAxisRepair
                                || allowStatsAxisRepair;
                        if (repairedSchemaField.isEmpty() && allowsSchemaScoredFallback) {
                            repairedSchemaField = preferredSafeGroupingField(schemaFields, widget, assignedChartFields, contextTokens);
                        }
                        if (repairedSchemaField.isPresent()) {
                            repairedSchemaField = governedStatsAxisField(
                                    widget,
                                    repairedSchemaField,
                                    schemaFields,
                                    statsRequestFields,
                                    warnings);
                        }
                    }
                    if (repairedSchemaField.isPresent()) {
                        SchemaFieldDescriptor repairedField = repairedSchemaField.get();
                        String requestedField = axisObject.path("field").asText("");
                        String previousAxisLabel = axisObject.path("label").asText("");
                        alignSemanticAxis(axisObject, repairedField, schemaResult);
                        alignChartBinding(widget, repairedField);
                        alignTemporalChartOperation(widget, repairedField, warnings);
                        alignChartMetricBinding(widget, schemaFields, schemaResult, warnings, contextTokens);
                        if (repairedFromPrompt) {
                            alignChartDisplayText(widget, previousAxisLabel, repairedField);
                        }
                        alignDiagnosticsAxis(uiCompositionPlan, requestedField, repairedField, schemaResult);
                        assignedChartFields.add(normalize(repairedField.name()));
                        addWarningOnce(warnings, repairedFromPrompt
                                ? "semantic-chart-axis-repaired-with-prompt-aligned-schema-field"
                                : "semantic-chart-axis-repaired-with-schema-field");
                        continue;
                    }
                    if (schemaField.isPresent()) {
                        markSemanticAxisUnsupported(axisObject);
                        markDiagnosticsAxisUnsupported(uiCompositionPlan, schemaField.get().name());
                        addWarningOnce(warnings, "semantic-chart-group-by-unsupported-field-type");
                    } else if (allowSchemaSafeAxisRepair) {
                        markSemanticAxisDropped(axisObject, "schema-safe-axis-repair");
                        markDiagnosticsAxisDropped(uiCompositionPlan, axisObject.path("field").asText(""), "schema-safe-axis-repair");
                        addWarningOnce(warnings, "semantic-chart-axis-dropped-without-safe-schema-field");
                    } else {
                        markSemanticAxisDropped(axisObject, "unsupported-semantic-axis");
                        markDiagnosticsAxisDropped(uiCompositionPlan, axisObject.path("field").asText(""), "unsupported-semantic-axis");
                    }
                    widgetArray.remove(i);
                }
            }
            if (!containsUnsupportedSemanticAxes(uiCompositionPlan)) {
                warnings.remove("semantic-axis-schema-verification-unsupported-axis");
            }
            alignDashboardFilterFieldsWithResolvedChartAxes(widgetArray, filterSchemaFields, schemaResult, warnings);
            normalizeFilterQueryContextBindings(uiCompositionPlan, widgetArray, warnings);
            Set<String> widgetKeys = widgetKeys(widgetArray);
            pruneOrphanWidgetBindings(uiCompositionPlan, widgetKeys, warnings);
            pruneOrphanCanvasItems(uiCompositionPlan, widgetKeys, warnings);
            pruneOrphanGroupingItems(uiCompositionPlan, widgetKeys, warnings);
            pruneOrphanDeviceLayoutItems(uiCompositionPlan, widgetKeys, warnings);
            pruneOrphanSlotAssignments(uiCompositionPlan, widgetKeys, warnings);
        }
    }

    private void alignDashboardFilterFieldsWithResolvedChartAxes(
            ArrayNode widgetArray,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        LinkedHashSet<String> resolvedAxisFields = new LinkedHashSet<>();
        for (JsonNode widget : widgetArray) {
            if (!"praxis-chart".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
            String field = axis.path("field").asText("");
            if (field.isBlank()
                    || "unsupported".equals(axis.path("schemaProbeStatus").asText(""))
                    || axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            resolvedAxisFields.add(field);
        }
        if (resolvedAxisFields.isEmpty()) {
            return;
        }
        for (JsonNode widget : widgetArray) {
            if (!"praxis-filter".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            JsonNode inputs = widget.path("inputs");
            if (!(inputs instanceof ObjectNode inputsObject)) {
                continue;
            }
            JsonNode selectedFields = inputsObject.path("selectedFieldIds");
            ArrayNode fieldsArray = selectedFields instanceof ArrayNode existing
                    ? existing
                    : inputsObject.putArray("selectedFieldIds");
            if (!fieldsArray.isEmpty()) {
                continue;
            }
            resolvedAxisFields.forEach(fieldsArray::add);
            alignFilterFields(widget, filterSchemaFields, schemaResult, warnings);
        }
    }

    void alignDataQueryContextFilters(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        if (!(queryContextFilters(widget) instanceof ObjectNode filters) || filters.isEmpty()) {
            return;
        }
        ObjectNode aligned = objectMapper.createObjectNode();
        filters.fields().forEachRemaining(entry -> {
            Optional<SchemaFieldDescriptor> resolved = resolveSchemaField(
                    axisProbe(entry.getKey()),
                    filterSchemaFields);
            if (resolved.isPresent()) {
                aligned.set(resolved.get().name(), entry.getValue().deepCopy());
            }
        });
        if (aligned.size() != filters.size()) {
            addWarningOnce(warnings, queryFilterWarning(widget, "schema-grounding-incomplete"));
            return;
        }
        filters.removeAll();
        filters.setAll(aligned);
        addWarningOnce(warnings, queryFilterWarning(widget, "schema-grounded"));
    }

    JsonNode queryContextFilters(JsonNode widget) {
        String componentId = widget == null ? "" : widget.path("componentId").asText("");
        if ("praxis-table".equals(componentId)) {
            return widget.path("inputs").path("queryContext").path("filters");
        }
        if ("praxis-crud".equals(componentId)) {
            return widget.path("inputs").path("metadata").path("queryContext").path("filters");
        }
        return MissingNode.getInstance();
    }

    String queryFilterWarning(JsonNode widget, String suffix) {
        return "praxis-crud".equals(widget.path("componentId").asText(""))
                ? "crud-query-filter-" + suffix
                : "table-query-filter-" + suffix;
    }

    void normalizeFilterQueryContextBindings(
            ObjectNode uiCompositionPlan,
            List<String> warnings) {
        JsonNode widgets = uiCompositionPlan.path("widgets");
        if (widgets instanceof ArrayNode widgetArray) {
            normalizeFilterQueryContextBindings(uiCompositionPlan, widgetArray, warnings);
        }
    }

    private void normalizeFilterQueryContextBindings(
            ObjectNode uiCompositionPlan,
            ArrayNode widgetArray,
            List<String> warnings) {
        Set<String> filterKeys = new LinkedHashSet<>();
        if (widgetArray != null) {
            for (JsonNode widget : widgetArray) {
                if (!"praxis-filter".equals(widget.path("componentId").asText(""))) {
                    continue;
                }
                String key = firstNonBlank(widget.path("key").asText(""), widget.path("id").asText(""));
                if (key.isBlank()) {
                    continue;
                }
                filterKeys.add(key);
                if (widget instanceof ObjectNode widgetObject) {
                    ObjectNode outputs = widgetObject.path("outputs") instanceof ObjectNode existing
                            ? existing
                            : widgetObject.putObject("outputs");
                    outputs.put("change", "emit");
                    outputs.put("requestSearch", "emit");
                    outputs.put("clear", "emit");
                }
            }
        }
        if (filterKeys.isEmpty()) {
            return;
        }
        JsonNode bindings = uiCompositionPlan.path("bindings");
        if (!(bindings instanceof ArrayNode bindingsArray)) {
            return;
        }
        for (JsonNode binding : bindingsArray) {
            if (!(binding instanceof ObjectNode bindingObject)) {
                continue;
            }
            JsonNode from = binding.path("from");
            JsonNode to = binding.path("to");
            String fromWidget = bindingWidgetKey(from);
            String fromPort = bindingPort(from);
            String toPort = bindingPort(to);
            if (!filterKeys.contains(fromWidget)
                    || !Set.of("change", "requestSearch", "submit").contains(fromPort)
                    || !"queryContext".equals(toPort)) {
                continue;
            }
            ObjectNode transform = bindingObject.putObject("transform");
            transform.put("kind", "template");
            transform.put("id", bindingObject.path("id").asText() + "-query-context");
            transform.putObject("output").put("semanticKind", "query-context").put("stableShape", true);
            ObjectNode template = transform.putObject("template");
            template.put("filters", "${payload}");
            addWarningOnce(warnings, "ui-composition-plan-filter-query-context-normalized");
        }
    }

    private Set<String> exactSafeChartFields(
            ArrayNode widgetArray,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        Set<String> fields = new LinkedHashSet<>();
        if (widgetArray == null) {
            return fields;
        }
        for (JsonNode widget : widgetArray) {
            JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
            if (!(axis instanceof ObjectNode axisObject)) {
                continue;
            }
            Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisObject, schemaFields);
            if (schemaField.isPresent()
                    && isSafeGenericGroupByChartField(schemaField.get(), widget)) {
                fields.add(normalize(schemaField.get().name()));
            }
        }
        return fields;
    }

    boolean allowsSchemaSafeAxisRepair(AgenticAuthoringPlanRequest request) {
        String prompt = normalize(String.join(" ",
                request == null ? "" : request.userPrompt(),
                request == null || request.intentResolution() == null ? "" : request.intentResolution().effectivePrompt()));
        if (prompt.isBlank()) {
            return false;
        }
        boolean asksForSafeAxis = containsAny(prompt,
                "eixo seguro",
                "eixos seguros",
                "safe axis",
                "safe axes",
                "campo seguro",
                "campos seguros",
                "campos suportados",
                "eixos suportados");
        boolean asksForSchemaGrounding = containsAny(prompt,
                "schema",
                "esquema",
                "confirmado",
                "confirmados",
                "verificado",
                "verificados",
                "suportado",
                "suportados");
        return asksForSafeAxis && asksForSchemaGrounding;
    }

    private boolean allowsGenericInferredAxisRepair(ObjectNode semanticAxis) {
        if (semanticAxis == null) {
            return false;
        }
        String provenance = semanticAxis.path("provenance").asText("");
        String probeStatus = semanticAxis.path("schemaProbeStatus").asText("");
        return "generic-dashboard-field-inference".equals(provenance)
                && (probeStatus.isBlank() || "pending".equals(probeStatus));
    }

    private boolean allowsStatsAxisRepair(ObjectNode semanticAxis, JsonNode widget) {
        if (semanticAxis == null || widget == null) {
            return false;
        }
        String statsOperation = statsOperation(widget);
        if (statsOperation.isBlank() || "group-by".equalsIgnoreCase(statsOperation)) {
            return false;
        }
        String field = normalize(semanticAxis.path("field").asText(""));
        String concept = normalize(semanticAxis.path("concept").asText(""));
        String provenance = normalize(semanticAxis.path("provenance").asText(""));
        String probeStatus = normalize(semanticAxis.path("schemaProbeStatus").asText(""));
        return (field.isBlank() || "unresolved".equals(field))
                && (concept.isBlank() || "unresolved".equals(concept))
                && (probeStatus.isBlank() || "pending".equals(probeStatus))
                && (provenance.isBlank()
                || "llm-authored-semantic-axis".equals(provenance)
                || "schema-grounding-required".equals(provenance));
    }

    private boolean allowsContextualAxisRepair(ObjectNode semanticAxis, Set<String> contextTokens) {
        if (semanticAxis == null || contextTokens == null || contextTokens.isEmpty()) {
            return false;
        }
        String field = normalize(semanticAxis.path("field").asText(""));
        String concept = normalize(semanticAxis.path("concept").asText(""));
        String provenance = normalize(semanticAxis.path("provenance").asText(""));
        String probeStatus = normalize(semanticAxis.path("schemaProbeStatus").asText(""));
        return (field.isBlank() || "unresolved".equals(field))
                && (concept.isBlank() || "unresolved".equals(concept))
                && (probeStatus.isBlank() || "pending".equals(probeStatus))
                && ("schema-grounding-required".equals(provenance)
                || "llm-authored-semantic-axis".equals(provenance)
                || provenance.isBlank());
    }

    private boolean isSafeGenericGroupByChartField(SchemaFieldDescriptor field, JsonNode widget) {
        if (field == null) {
            return false;
        }
        String statsOperation = statsOperation(widget);
        if (!statsOperation.isBlank() && !"group-by".equalsIgnoreCase(statsOperation)) {
            return true;
        }
        if (isTemporalChartRequest(widget) && isTemporalSchemaField(field)) {
            return true;
        }
        String type = normalize(field.type());
        String format = normalize(field.format());
        if (field.hasEnum()) {
            return true;
        }
        if (containsAnyToken(field.fieldTokens(), "data", "date")
                || containsAnyToken(field.labelTokens(), "data", "date")) {
            return false;
        }
        if (type.isBlank()) {
            return true;
        }
        if ("integer".equals(type) || "long".equals(type)) {
            return containsAnyToken(field.fieldTokens(), "mes", "ano", "competencia", "periodo")
                    || containsAnyToken(field.labelTokens(), "mes", "ano", "competencia", "periodo");
        }
        if ("string".equals(type)) {
            return format.isBlank() || "text".equals(format);
        }
        return "boolean".equals(type);
    }

    private boolean isTemporalChartRequest(JsonNode widget) {
        JsonNode config = widget.path("inputs").path("config");
        String chartType = normalize(config.path("type").asText(""));
        String xType = normalize(config.path("axes").path("x").path("type").asText(""));
        String semanticConcept = normalize(config.path("semanticAxis").path("concept").asText(""));
        return "line".equals(chartType)
                || "area".equals(chartType)
                || chartType.contains("line")
                || chartType.contains("area")
                || chartType.contains("time")
                || chartType.contains("serie")
                || chartType.contains("series")
                || "time".equals(semanticConcept)
                || "temporal".equals(semanticConcept)
                || semanticConcept.contains("time")
                || semanticConcept.contains("temporal")
                || semanticConcept.contains("tempo")
                || semanticConcept.contains("mensal")
                || semanticConcept.contains("mes")
                || semanticConcept.contains("data")
                || semanticConcept.contains("date")
                || "time".equals(xType);
    }

    private boolean isTemporalSchemaField(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String type = normalize(field.type());
        String format = normalize(field.format());
        if ("date".equals(type) || "datetime".equals(type) || "date".equals(format)
                || "date-time".equals(format) || "datetime".equals(format)) {
            return true;
        }
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(field.fieldTokens());
        tokens.addAll(field.labelTokens());
        tokens.addAll(field.descriptionTokens());
        return containsAnyToken(tokens, "data", "date", "temporal", "tempo", "ocorrido", "competencia", "periodo");
    }

    private Optional<SchemaFieldDescriptor> preferredSafeGroupingField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            JsonNode widget,
            Set<String> assignedChartFields,
            Set<String> contextTokens) {
        if ("timeseries".equalsIgnoreCase(statsOperation(widget))) {
            return preferredTemporalField(schemaFields, assignedChartFields);
        }
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (assignedChartFields != null && assignedChartFields.contains(normalize(field.name()))) {
                continue;
            }
            if (!isSafeGenericGroupByChartField(field, widget)) {
                continue;
            }
            int score = groupingFieldScore(field) + (semanticMatchScore(contextTokens, field) * 40);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private Optional<SchemaFieldDescriptor> promptAlignedSafeGroupingField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            JsonNode widget,
            Set<String> assignedChartFields,
            Set<String> promptTokens,
            SchemaFieldDescriptor currentField) {
        if (promptTokens == null || promptTokens.isEmpty()) {
            return Optional.empty();
        }
        boolean temporalChartRequest = isTemporalChartRequest(widget);
        int currentScore = currentField == null ? 0 : semanticMatchScore(promptTokens, currentField);
        SchemaFieldDescriptor best = null;
        int bestScore = currentScore;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (currentField != null && normalize(field.name()).equals(normalize(currentField.name()))) {
                continue;
            }
            if (assignedChartFields != null && assignedChartFields.contains(normalize(field.name()))) {
                continue;
            }
            if (temporalChartRequest && !isTemporalSchemaField(field)) {
                continue;
            }
            if (!isSafeGenericGroupByChartField(field, widget)) {
                continue;
            }
            int score = semanticMatchScore(promptTokens, field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        if (best != null && bestScore >= 3 && (currentField == null || bestScore >= currentScore + 3)) {
            return Optional.of(best);
        }
        return statusLikeBooleanGroupingField(schemaFields, assignedChartFields, promptTokens, currentField);
    }

    private Optional<SchemaFieldDescriptor> statusLikeBooleanGroupingField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> assignedChartFields,
            Set<String> promptTokens,
            SchemaFieldDescriptor currentField) {
        if (!isStatusLikeConcept(promptTokens)) {
            return Optional.empty();
        }
        List<SchemaFieldDescriptor> candidates = schemaFields.values().stream()
                .filter(field -> currentField == null || !normalize(field.name()).equals(normalize(currentField.name())))
                .filter(field -> assignedChartFields == null || !assignedChartFields.contains(normalize(field.name())))
                .filter(field -> "boolean".equals(normalize(field.type())))
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private Optional<SchemaFieldDescriptor> preferredTemporalField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> assignedChartFields) {
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (assignedChartFields != null && assignedChartFields.contains(normalize(field.name()))) {
                continue;
            }
            int score = temporalFieldScore(field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private Optional<SchemaFieldDescriptor> governedStatsAxisField(
            JsonNode widget,
            Optional<SchemaFieldDescriptor> currentField,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> statsRequestFields,
            List<String> warnings) {
        if (!"timeseries".equalsIgnoreCase(statsOperation(widget))) {
            return currentField;
        }
        if (currentField.isPresent() && isStrictTemporalStatsField(currentField.get())) {
            return currentField;
        }
        Optional<SchemaFieldDescriptor> governedField = preferredStrictTemporalStatsField(
                schemaFields,
                statsRequestFields);
        if (governedField.isEmpty()) {
            return currentField;
        }
        if (currentField.isEmpty()
                || !normalize(currentField.get().name()).equals(normalize(governedField.get().name()))) {
            addWarningOnce(warnings, "semantic-chart-timeseries-axis-repaired-with-governed-temporal-field");
        }
        return governedField;
    }

    private Optional<SchemaFieldDescriptor> preferredStrictTemporalStatsField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> statsRequestFields) {
        if (schemaFields == null || schemaFields.isEmpty()) {
            return Optional.empty();
        }
        Set<String> requestTokens = statsRequestFieldTokens(statsRequestFields);
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (!isStrictTemporalStatsField(field)) {
                continue;
            }
            int score = temporalFieldScore(field);
            if (!requestTokens.isEmpty()) {
                score += semanticMatchScore(requestTokens, field) * 40;
            }
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private Set<String> statsRequestFieldTokens(Map<String, SchemaFieldDescriptor> statsRequestFields) {
        if (statsRequestFields == null || statsRequestFields.isEmpty()) {
            return Set.of();
        }
        SchemaFieldDescriptor fieldRequest = statsRequestFields.get("field");
        if (fieldRequest == null) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(fieldRequest.labelTokens());
        tokens.addAll(fieldRequest.descriptionTokens());
        return tokens;
    }

    private boolean isStrictTemporalStatsField(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String type = normalize(field.type());
        String format = normalize(field.format());
        return "date".equals(type)
                || "datetime".equals(type)
                || "date".equals(format)
                || "date-time".equals(format)
                || "datetime".equals(format);
    }

    private int temporalFieldScore(SchemaFieldDescriptor field) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(field.fieldTokens());
        tokens.addAll(field.labelTokens());
        tokens.addAll(field.descriptionTokens());
        int score = 0;
        String type = normalize(field.type());
        String format = normalize(field.format());
        if ("date".equals(format) || "date-time".equals(format) || "datetime".equals(format)) {
            score += 200;
        }
        if ("date".equals(type) || "datetime".equals(type)) {
            score += 160;
        }
        if (containsAnyToken(tokens,
                "data",
                "date",
                "hora",
                "temporal",
                "tempo",
                "ocorrido",
                "ocorrencia",
                "competencia",
                "periodo",
                "mes",
                "ano")) {
            score += 50;
        }
        if (containsAnyToken(tokens, "id", "uuid", "codigo")) {
            score -= 120;
        }
        if (isNumericSchemaField(field)) {
            score -= 120;
        }
        return score;
    }

    private void alignDiagnosticsAxis(
            ObjectNode uiCompositionPlan,
            String requestedField,
            SchemaFieldDescriptor field,
            SchemaFetchResult schemaResult) {
        if (uiCompositionPlan == null || field == null) {
            return;
        }
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        for (JsonNode axis : axes) {
            if (axis instanceof ObjectNode axisObject
                    && normalize(requestedField).equals(normalize(axisObject.path("field").asText("")))) {
                alignSemanticAxis(axisObject, field, schemaResult);
                axisObject.put("materialized", true);
                return;
            }
        }
    }

    private void markDiagnosticsAxisUnsupported(ObjectNode uiCompositionPlan, String fieldName) {
        if (uiCompositionPlan == null || value(fieldName).isBlank()) {
            return;
        }
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        for (JsonNode axis : axes) {
            if (axis instanceof ObjectNode axisObject
                    && normalize(fieldName).equals(normalize(axisObject.path("field").asText("")))) {
                markSemanticAxisUnsupported(axisObject);
            }
        }
    }

    private void markDiagnosticsAxisDropped(ObjectNode uiCompositionPlan, String fieldName, String reason) {
        if (uiCompositionPlan == null || value(fieldName).isBlank()) {
            return;
        }
        JsonNode axes = uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        for (JsonNode axis : axes) {
            if (axis instanceof ObjectNode axisObject
                    && normalize(fieldName).equals(normalize(axisObject.path("field").asText("")))) {
                markSemanticAxisDropped(axisObject, reason);
            }
        }
    }

    private void pruneOrphanWidgetBindings(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode bindings = uiCompositionPlan.path("bindings");
        if (!(bindings instanceof ArrayNode bindingsArray)) {
            return;
        }
        for (int i = bindingsArray.size() - 1; i >= 0; i--) {
            JsonNode binding = bindingsArray.get(i);
            String fromWidget = bindingWidgetKey(binding.path("from"));
            String toWidget = bindingWidgetKey(binding.path("to"));
            boolean fromMissing = !fromWidget.isBlank() && !widgetKeys.contains(fromWidget);
            boolean toMissing = !toWidget.isBlank() && !widgetKeys.contains(toWidget);
            if (fromMissing || toMissing) {
                bindingsArray.remove(i);
                addWarningOnce(warnings, "ui-composition-plan-orphan-binding-removed");
            }
        }
    }

    private Set<String> widgetKeys(ArrayNode widgets) {
        Set<String> widgetKeys = new LinkedHashSet<>();
        if (widgets == null) {
            return widgetKeys;
        }
        for (JsonNode widget : widgets) {
            String key = firstNonBlank(
                    widget.path("key").asText(""),
                    widget.path("id").asText(""));
            if (!key.isBlank()) {
                widgetKeys.add(key);
            }
        }
        return widgetKeys;
    }

    private void pruneOrphanCanvasItems(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode items = uiCompositionPlan.path("canvas").path("items");
        if (!(items instanceof ObjectNode itemsObject)) {
            return;
        }
        List<String> orphanKeys = new ArrayList<>();
        itemsObject.fieldNames().forEachRemaining(key -> {
            if (!widgetKeys.contains(key)) {
                orphanKeys.add(key);
            }
        });
        for (String orphanKey : orphanKeys) {
            itemsObject.remove(orphanKey);
            addWarningOnce(warnings, "ui-composition-plan-orphan-canvas-item-removed");
        }
    }

    private void pruneOrphanGroupingItems(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode grouping = uiCompositionPlan.path("grouping");
        if (!(grouping instanceof ArrayNode groupingArray)) {
            return;
        }
        for (int i = groupingArray.size() - 1; i >= 0; i--) {
            JsonNode group = groupingArray.get(i);
            JsonNode widgetKeysNode = group.path("widgetKeys");
            if (!(widgetKeysNode instanceof ArrayNode groupWidgetKeys)) {
                continue;
            }
            for (int keyIndex = groupWidgetKeys.size() - 1; keyIndex >= 0; keyIndex--) {
                String key = groupWidgetKeys.path(keyIndex).asText("");
                if (!key.isBlank() && !widgetKeys.contains(key)) {
                    groupWidgetKeys.remove(keyIndex);
                    addWarningOnce(warnings, "ui-composition-plan-orphan-grouping-item-removed");
                }
            }
            if (groupWidgetKeys.isEmpty()) {
                groupingArray.remove(i);
                addWarningOnce(warnings, "ui-composition-plan-empty-grouping-removed");
            }
        }
        if (groupingArray.isEmpty()) {
            uiCompositionPlan.remove("grouping");
        }
    }

    private void pruneOrphanDeviceLayoutItems(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode deviceLayouts = uiCompositionPlan.path("deviceLayouts");
        if (!(deviceLayouts instanceof ObjectNode deviceLayoutsObject)) {
            return;
        }
        deviceLayoutsObject.fields().forEachRemaining(entry -> {
            JsonNode items = entry.getValue().path("canvas").path("items");
            if (!(items instanceof ObjectNode itemsObject)) {
                return;
            }
            List<String> orphanKeys = new ArrayList<>();
            itemsObject.fieldNames().forEachRemaining(key -> {
                if (!widgetKeys.contains(key)) {
                    orphanKeys.add(key);
                }
            });
            for (String orphanKey : orphanKeys) {
                itemsObject.remove(orphanKey);
                addWarningOnce(warnings, "ui-composition-plan-orphan-device-layout-item-removed");
            }
        });
    }

    private void pruneOrphanSlotAssignments(
            ObjectNode uiCompositionPlan,
            Set<String> widgetKeys,
            List<String> warnings) {
        JsonNode slotAssignments = uiCompositionPlan.path("slotAssignments");
        if (!(slotAssignments instanceof ObjectNode slotAssignmentsObject)) {
            return;
        }
        List<String> orphanKeys = new ArrayList<>();
        slotAssignmentsObject.fieldNames().forEachRemaining(key -> {
            if (!widgetKeys.contains(key)) {
                orphanKeys.add(key);
            }
        });
        for (String orphanKey : orphanKeys) {
            slotAssignmentsObject.remove(orphanKey);
            addWarningOnce(warnings, "ui-composition-plan-orphan-slot-assignment-removed");
        }
        if (slotAssignmentsObject.isEmpty()) {
            uiCompositionPlan.remove("slotAssignments");
        }
    }

    private String bindingWidgetKey(JsonNode endpoint) {
        return firstNonBlank(
                endpoint.path("widgetKey").asText(""),
                firstNonBlank(
                        endpoint.path("widget").asText(""),
                        endpoint.path("ref").path("widget").asText("")));
    }

    private String bindingPort(JsonNode endpoint) {
        return firstNonBlank(
                endpoint.path("port").asText(""),
                endpoint.path("ref").path("port").asText(""));
    }

    private void alignAuxiliaryWidgetBindings(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Map<String, SchemaFieldDescriptor> filterSchemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        if (widget == null || !widget.isObject()) {
            return;
        }
        String componentId = widget.path("componentId").asText("");
        if ("praxis-filter".equals(componentId)) {
            alignFilterFields(widget, filterSchemaFields, schemaResult, warnings);
        }
        if ("praxis-rich-content".equals(componentId) && "kpi-band".equals(widget.path("role").asText(""))) {
            alignKpiFields(widget, schemaFields, schemaResult, warnings);
        }
    }

    private void alignFilterFields(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        JsonNode selectedFields = widget.path("inputs").path("selectedFieldIds");
        if (!(selectedFields instanceof ArrayNode fieldsArray)) {
            return;
        }
        for (int i = fieldsArray.size() - 1; i >= 0; i--) {
            String requestedField = fieldsArray.get(i).asText("");
            Optional<SchemaFieldDescriptor> schemaField = Optional.ofNullable(
                    schemaFields.get(normalize(requestedField)));
            if (schemaField.isPresent()) {
                SchemaFieldDescriptor selectedField = preferredFilterInputField(schemaField.get(), schemaFields)
                        .orElse(schemaField.get());
                fieldsArray.set(i, objectMapper.getNodeFactory().textNode(selectedField.name()));
                if (!normalize(selectedField.name()).equals(normalize(schemaField.get().name()))) {
                    addWarningOnce(warnings, "semantic-filter-schema-field-replaced-with-selectable-field");
                }
            } else {
                Optional<SchemaFieldDescriptor> selectedField = preferredFilterInputField(
                        syntheticRequestedFilterField(requestedField),
                        schemaFields);
                if (selectedField.isPresent()) {
                    fieldsArray.set(i, objectMapper.getNodeFactory().textNode(selectedField.get().name()));
                    addWarningOnce(warnings, "semantic-filter-schema-field-replaced-with-selectable-field");
                } else {
                    fieldsArray.remove(i);
                    addWarningOnce(warnings, "semantic-filter-schema-verification-unsupported-field");
                }
            }
        }
        LinkedHashSet<String> uniqueFields = new LinkedHashSet<>();
        boolean removedDuplicate = false;
        for (JsonNode field : fieldsArray) {
            String name = field.asText("");
            if (name.isBlank()) {
                continue;
            }
            removedDuplicate = !uniqueFields.add(name) || removedDuplicate;
        }
        if (removedDuplicate) {
            fieldsArray.removeAll();
            uniqueFields.forEach(fieldsArray::add);
            addWarningOnce(warnings, "semantic-filter-schema-field-deduplicated");
        }
    }

    private SchemaFieldDescriptor syntheticRequestedFilterField(String requestedField) {
        String field = value(requestedField);
        return new SchemaFieldDescriptor(
                field,
                field,
                "",
                "",
                "",
                "",
                false,
                "",
                false,
                "",
                false,
                false,
                tokens(field),
                tokens(field),
                Set.of());
    }

    Optional<SchemaFieldDescriptor> preferredTemporalRangeFilterField(
            SchemaFieldDescriptor requestedField,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        if (requestedField == null || schemaFields == null || schemaFields.isEmpty()) {
            return Optional.empty();
        }
        Set<String> requestedTokens = new LinkedHashSet<>();
        requestedTokens.addAll(requestedField.fieldTokens());
        requestedTokens.addAll(requestedField.labelTokens());
        requestedTokens.addAll(requestedField.descriptionTokens());
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        boolean ambiguous = false;
        for (SchemaFieldDescriptor candidate : schemaFields.values()) {
            if (!isTemporalRangeFilterField(candidate)) {
                continue;
            }
            int score = semanticMatchScore(requestedTokens, candidate);
            if (score < 6 && !temporalRangeStemMatches(requestedField, candidate)) {
                continue;
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                ambiguous = false;
            } else if (score > 0 && score == bestScore) {
                ambiguous = true;
            }
        }
        return best != null && bestScore >= 3 && !ambiguous
                ? Optional.of(best)
                : Optional.empty();
    }

    private boolean temporalRangeStemMatches(
            SchemaFieldDescriptor requestedField,
            SchemaFieldDescriptor candidate) {
        String requestedName = normalize(requestedField.name()).replaceAll("[^a-z0-9]", "");
        String candidateName = normalize(candidate.name()).replaceAll("[^a-z0-9]", "");
        return !requestedName.isBlank()
                && (candidateName.equals(requestedName)
                || candidateName.startsWith(requestedName + "between")
                || candidateName.startsWith(requestedName + "range"));
    }

    boolean isTemporalRangeFilterField(SchemaFieldDescriptor field) {
        if (field == null || !"array".equals(normalize(field.type()))) {
            return false;
        }
        String controlType = normalize(field.controlType());
        return containsAny(controlType, "date-range", "date_range", "daterange", "date range");
    }

    Optional<SchemaFieldDescriptor> preferredFilterInputField(
            SchemaFieldDescriptor requestedField,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        if (requestedField == null || schemaFields == null || schemaFields.isEmpty()) {
            return Optional.empty();
        }
        if (isSelectableFilterInputField(requestedField)) {
            return Optional.of(requestedField);
        }
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        for (SchemaFieldDescriptor candidate : schemaFields.values()) {
            if (candidate == null || normalize(candidate.name()).equals(normalize(requestedField.name()))) {
                continue;
            }
            if (!isSelectableFilterInputField(candidate)) {
                continue;
            }
            int score = filterInputSemanticMatchScore(requestedField, candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best != null && bestScore >= 4 ? Optional.of(best) : Optional.empty();
    }

    private boolean isSelectableFilterInputField(SchemaFieldDescriptor field) {
        if (field == null) {
            return false;
        }
        String controlType = normalize(field.controlType());
        return isTemporalRangeFilterField(field)
                || field.multiple()
                || field.hasEnum()
                || !field.endpoint().isBlank()
                || containsAny(controlType, "select", "autocomplete", "radio");
    }

    private int filterInputSemanticMatchScore(
            SchemaFieldDescriptor requestedField,
            SchemaFieldDescriptor candidate) {
        Set<String> requestedTokens = new LinkedHashSet<>();
        requestedTokens.addAll(requestedField.fieldTokens());
        requestedTokens.addAll(requestedField.labelTokens());
        requestedTokens.addAll(requestedField.descriptionTokens());
        int score = semanticMatchScore(requestedTokens, candidate);
        if (isCanonicalFilterProjection(requestedField.name(), candidate.name())) {
            score += 100;
        }
        if (score <= 0) {
            return 0;
        }
        if (candidate.multiple()) {
            score += 2;
        }
        if (!candidate.endpoint().isBlank()) {
            score += 2;
        }
        if (containsAny(normalize(candidate.controlType()), "select", "autocomplete")) {
            score += 1;
        }
        return score;
    }

    private boolean isCanonicalDisplayProjection(String analyticalField, String schemaField) {
        String analytical = normalize(analyticalField).replaceAll("[^a-z0-9]", "");
        String candidate = normalize(schemaField).replaceAll("[^a-z0-9]", "");
        if (analytical.isBlank() || candidate.isBlank() || analytical.equals(candidate)) {
            return false;
        }
        for (String suffix : List.of("nome", "name", "label", "descricao", "description", "titulo", "title")) {
            if (candidate.endsWith(suffix)
                    && candidate.substring(0, candidate.length() - suffix.length()).equals(analytical)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCanonicalFilterProjection(String analyticalField, String filterField) {
        String analytical = normalize(analyticalField).replaceAll("[^a-z0-9]", "");
        String candidate = normalize(filterField).replaceAll("[^a-z0-9]", "");
        if (analytical.isBlank() || candidate.isBlank() || analytical.equals(candidate)) {
            return false;
        }
        String analyticalStem = analytical.endsWith("id") && analytical.length() > 2
                ? analytical.substring(0, analytical.length() - 2)
                : analytical;
        for (String suffix : List.of("idsin", "idin", "ids", "id", "between", "range", "in")) {
            if (candidate.endsWith(suffix)) {
                String stem = candidate.substring(0, candidate.length() - suffix.length());
                if (stem.equals(analytical) || (!analyticalStem.isBlank() && stem.equals(analyticalStem))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void alignKpiFields(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        JsonNode kpis = widget.path("inputs").path("kpis");
        if (kpis.isMissingNode()) {
            kpis = widget.path("inputs").path("document").path("kpis");
        }
        if (kpis.isMissingNode()) {
            kpis = widget.path("inputs").path("document").path("nodes").path(0).path("items");
        }
        if (!(kpis instanceof ArrayNode kpiArray)) {
            return;
        }
        for (int i = kpiArray.size() - 1; i >= 0; i--) {
            JsonNode kpi = kpiArray.get(i);
            String dimensionField = kpi.path("dimensionField").asText("");
            if (dimensionField.isBlank()) {
                continue;
            }
            Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisProbe(dimensionField), schemaFields);
            if (schemaField.isPresent()) {
                if (kpi instanceof ObjectNode kpiObject) {
                    kpiObject.put("dimensionField", schemaField.get().name());
                    kpiObject.put("schemaVerified", true);
                    kpiObject.put("schemaProbeStatus", "verified");
                    kpiObject.put("schemaEvidenceSource", "schemas.filtered");
                    kpiObject.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
                }
            } else {
                kpiArray.remove(i);
                addWarningOnce(warnings, "semantic-kpi-schema-verification-unsupported-field");
            }
        }
    }

    private ObjectNode axisProbe(String field) {
        ObjectNode probe = objectMapper.createObjectNode();
        probe.put("field", field == null ? "" : field);
        probe.put("label", field == null ? "" : field);
        probe.put("concept", field == null ? "" : field);
        return probe;
    }

    private void alignSemanticAxis(
            ObjectNode semanticAxis,
            SchemaFieldDescriptor field,
            SchemaFetchResult schemaResult) {
        String requestedField = semanticAxis.path("field").asText("");
        if (!normalize(requestedField).equals(normalize(field.name()))) {
            semanticAxis.put("requestedField", requestedField);
            semanticAxis.put("field", field.name());
            if (!field.label().isBlank()) {
                semanticAxis.put("label", field.label());
                semanticAxis.put("schemaLabel", field.label());
            }
        }
        semanticAxis.put("schemaVerified", true);
        semanticAxis.put("schemaProbeStatus", "verified");
        ObjectNode evidence = semanticAxis.putObject("schemaEvidence");
        evidence.put("source", "schemas.filtered");
        evidence.put("endpointUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
    }

    private void markSemanticAxisUnsupported(ObjectNode semanticAxis) {
        semanticAxis.put("schemaVerified", false);
        semanticAxis.put("schemaProbeStatus", "unsupported");
    }

    private void markSemanticAxisDropped(ObjectNode semanticAxis, String reason) {
        markSemanticAxisUnsupported(semanticAxis);
        semanticAxis.put("materialized", false);
        semanticAxis.put("materializationReason", valueOrDefault(reason, "not-materialized"));
    }

    private void alignChartBinding(JsonNode widget, SchemaFieldDescriptor field) {
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null) {
            return;
        }
        JsonNode xAxis = config.path("axes").path("x");
        if (xAxis instanceof ObjectNode xAxisObject) {
            xAxisObject.put("field", field.name());
            if (!field.label().isBlank()) {
                xAxisObject.put("label", field.label());
            }
        }
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                if (item instanceof ObjectNode seriesObject) {
                    seriesObject.put("categoryField", field.name());
                    JsonNode metric = seriesObject.path("metric");
                    if (metric instanceof ObjectNode metricObject
                            && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                        metricObject.put("dimensionField", field.name());
                    }
                }
            }
        }
        JsonNode dimensions = config.path("dataSource").path("query").path("dimensions");
        if (dimensions instanceof ArrayNode dimensionsArray && !dimensionsArray.isEmpty()) {
            dimensionsArray.set(0, objectMapper.getNodeFactory().textNode(field.name()));
        }
        JsonNode statsRequest = config.path("dataSource").path("query").path("statsRequest");
        if (statsRequest instanceof ObjectNode statsRequestObject) {
            statsRequestObject.put("field", field.name());
        }
    }

    private void alignChartDisplayText(JsonNode widget, String previousLabel, SchemaFieldDescriptor field) {
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null || field == null) {
            return;
        }
        String replacement = firstNonBlank(field.label(), field.name());
        replaceConfigText(config, "title", previousLabel, replacement);
        replaceConfigText(config, "subtitle", previousLabel, replacement);
    }

    private void replaceConfigText(ObjectNode config, String property, String previousLabel, String replacement) {
        String current = config.path(property).asText("");
        if (current.isBlank() || previousLabel == null || previousLabel.isBlank() || replacement == null || replacement.isBlank()) {
            return;
        }
        String updated = current.replace(previousLabel, replacement);
        if (!updated.equals(current)) {
            config.put(property, updated);
        }
    }

    private void alignTemporalChartOperation(
            JsonNode widget,
            SchemaFieldDescriptor field,
            List<String> warnings) {
        if (!isTemporalChartRequest(widget) || !isStrictTemporalStatsField(field)) {
            return;
        }
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null) {
            return;
        }
        JsonNode xAxis = config.path("axes").path("x");
        if (xAxis instanceof ObjectNode xAxisObject) {
            xAxisObject.put("type", "time");
        }
        JsonNode dataSource = config.path("dataSource");
        JsonNode query = dataSource.path("query");
        if (query instanceof ObjectNode queryObject) {
            queryObject.put("statsOperation", "timeseries");
            String resourcePath = businessResourcePath(dataSource.path("resourcePath").asText(""));
            if (!resourcePath.isBlank()) {
                queryObject.put("statsPath", resourcePath + "/stats/timeseries");
            }
            queryObject.put("granularity", "month");
            queryObject.remove("orderBy");
        }
        JsonNode statsRequest = query.path("statsRequest");
        if (statsRequest instanceof ObjectNode statsRequestObject) {
            statsRequestObject.put("granularity", "MONTH");
            statsRequestObject.put("fillGaps", false);
            statsRequestObject.remove("orderBy");
        }
        if (dataSource instanceof ObjectNode dataSourceObject) {
            dataSourceObject.put("statsEndpointInference", "canonical-resource-stats-timeseries");
        }
        addWarningOnce(warnings, "semantic-chart-temporal-operation-repaired-with-schema-field");
    }

    private void alignChartMetricBinding(
            JsonNode widget,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings,
            Set<String> contextTokens) {
        ObjectNode config = widget.path("inputs").path("config") instanceof ObjectNode objectNode
                ? objectNode
                : null;
        if (config == null) {
            return;
        }
        inferMetricFieldFromContext(config, schemaFields, contextTokens, warnings);
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                if (!(item instanceof ObjectNode seriesObject)) {
                    continue;
                }
                JsonNode metric = seriesObject.path("metric");
                if (metric instanceof ObjectNode metricObject) {
                    alignMetricField(metricObject, schemaFields, schemaResult, warnings);
                }
            }
        }
        JsonNode metrics = config.path("dataSource").path("query").path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject) {
                    alignMetricField(metricObject, schemaFields, schemaResult, warnings);
                }
            }
        }
        JsonNode statsMetric = config.path("dataSource").path("query").path("statsRequest").path("metric");
        if (statsMetric instanceof ObjectNode metricObject) {
            alignMetricField(metricObject, schemaFields, schemaResult, warnings);
        }
        alignQueryMetricsWithStatsMetric(config, warnings);
    }

    private void alignQueryMetricsWithStatsMetric(ObjectNode config, List<String> warnings) {
        JsonNode statsMetric = config.path("dataSource").path("query").path("statsRequest").path("metric");
        if (!(statsMetric instanceof ObjectNode statsMetricObject)) {
            return;
        }
        String operation = normalize(firstNonBlank(
                statsMetricObject.path("operation").asText(""),
                statsMetricObject.path("aggregation").asText(""))).replace('_', '-');
        String field = statsMetricObject.path("field").asText("");
        if (!Set.of("sum", "avg", "min", "max").contains(operation) || field.isBlank()) {
            return;
        }
        JsonNode metrics = config.path("dataSource").path("query").path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject
                        && normalize(metricObject.path("field").asText("")).equals(normalize(field))
                        && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                    metricObject.put("aggregation", operation);
                    addWarningOnce(warnings, "semantic-chart-query-metric-aligned-with-stats-metric");
                }
            }
        }
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                JsonNode metric = item.path("metric");
                if (metric instanceof ObjectNode metricObject
                        && normalize(metricObject.path("field").asText("")).equals(normalize(field))
                        && "count".equals(normalize(metricObject.path("aggregation").asText("")))) {
                    metricObject.put("aggregation", operation);
                    addWarningOnce(warnings, "semantic-chart-query-metric-aligned-with-stats-metric");
                }
            }
        }
    }

    private void inferMetricFieldFromContext(
            ObjectNode config,
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> contextTokens,
            List<String> warnings) {
        if (config == null || schemaFields == null || schemaFields.isEmpty()
                || contextTokens == null || contextTokens.isEmpty()) {
            return;
        }
        String inferredAggregation = inferredMetricAggregationFromContext(contextTokens);
        if (inferredAggregation.isBlank()) {
            return;
        }
        Optional<SchemaFieldDescriptor> metricField = preferredNumericMetricField(schemaFields, contextTokens);
        if (metricField.isEmpty()) {
            return;
        }
        SchemaFieldDescriptor field = metricField.get();
        boolean updated = false;
        JsonNode series = config.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) {
                JsonNode metric = item.path("metric");
                if (metric instanceof ObjectNode metricObject && metricNeedsInference(metricObject)) {
                    metricObject.put("field", field.name());
                    metricObject.put("aggregation", inferredAggregation);
                    updated = true;
                }
            }
        }
        JsonNode metrics = config.path("dataSource").path("query").path("metrics");
        if (metrics.isArray()) {
            for (JsonNode metric : metrics) {
                if (metric instanceof ObjectNode metricObject && (metricObject.path("field").asText("").isBlank()
                        || "total".equals(normalize(metricObject.path("field").asText(""))))) {
                    metricObject.put("field", field.name());
                    metricObject.put("aggregation", inferredAggregation);
                    metricObject.put("alias", field.name());
                    updated = true;
                }
            }
        }
        JsonNode statsMetric = config.path("dataSource").path("query").path("statsRequest").path("metric");
        if (statsMetric instanceof ObjectNode metricObject
                && (metricObject.path("field").asText("").isBlank()
                || "total".equals(normalize(metricObject.path("field").asText(""))))) {
            metricObject.put("field", field.name());
            metricObject.put("operation", inferredAggregation.toUpperCase(Locale.ROOT));
            metricObject.put("alias", field.name());
            updated = true;
        }
        if (updated) {
            addWarningOnce(warnings, "semantic-chart-metric-inferred-from-schema-context");
        }
    }

    private String inferredMetricAggregationFromContext(Set<String> contextTokens) {
        if (containsAnyToken(contextTokens, "soma", "somar", "somando", "sum", "totalizar")) {
            return "sum";
        }
        if (containsAnyToken(contextTokens, "media", "medio", "avg", "average")) {
            return "avg";
        }
        if (containsAnyToken(contextTokens, "maior", "maximo", "max", "teto")) {
            return "max";
        }
        if (containsAnyToken(contextTokens, "menor", "minimo", "min", "piso")) {
            return "min";
        }
        if (containsAnyToken(contextTokens, "evolucao", "progressao", "trajetoria", "reajuste", "reajustes")
                && containsAnyToken(contextTokens, "salario", "salarios", "salarial", "salariais", "remuneracao", "valor")) {
            return "avg";
        }
        return "";
    }

    private Optional<SchemaFieldDescriptor> preferredNumericMetricField(
            Map<String, SchemaFieldDescriptor> schemaFields,
            Set<String> contextTokens) {
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            if (!isNumericSchemaField(field)) {
                continue;
            }
            int score = semanticMatchScore(contextTokens, field);
            Set<String> fieldTokens = new LinkedHashSet<>();
            fieldTokens.addAll(field.fieldTokens());
            fieldTokens.addAll(field.labelTokens());
            fieldTokens.addAll(field.descriptionTokens());
            if (containsAnyToken(fieldTokens, "id", "uuid", "codigo", "cod")) {
                score -= 12;
            }
            if (containsAnyToken(fieldTokens, "valor", "salario", "liquido", "bruto", "amount", "total")) {
                score += 4;
            }
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return bestScore >= 2 ? Optional.of(best) : Optional.empty();
    }

    private boolean metricNeedsInference(ObjectNode metric) {
        if (metric == null) {
            return false;
        }
        String field = metric.path("field").asText("");
        return field.isBlank() || "total".equals(normalize(field));
    }

    private void alignMetricField(
            ObjectNode metric,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        String requestedField = metric.path("field").asText("");
        if (requestedField.isBlank() || "total".equals(normalize(requestedField))) {
            return;
        }
        Optional<ComparisonOutputMetric> comparisonOutput = comparisonOutputMetric(requestedField);
        if (comparisonOutput.isPresent()) {
            alignComparisonOutputMetric(
                    metric,
                    comparisonOutput.get(),
                    schemaFields,
                    schemaResult,
                    warnings);
            return;
        }
        Optional<SchemaFieldDescriptor> schemaField = resolveSchemaField(axisProbe(requestedField), schemaFields);
        String metricOperation = normalize(metric.path("operation").asText(metric.path("aggregation").asText("")));
        if (Set.of("sum", "avg", "min", "max").contains(metricOperation)
                && (schemaField.isEmpty() || !isNumericSchemaField(schemaField.get()))) {
            Set<String> metricTokens = new LinkedHashSet<>();
            addTokens(metricTokens, requestedField);
            addTokens(metricTokens, metric.path("label").asText(""));
            Optional<SchemaFieldDescriptor> numericAlternative = preferredNumericMetricField(schemaFields, metricTokens);
            if (numericAlternative.isPresent()) {
                schemaField = numericAlternative;
            }
        }
        if (schemaField.isEmpty()) {
            metric.put("schemaVerified", false);
            metric.put("schemaProbeStatus", "unsupported");
            addWarningOnce(warnings, "semantic-chart-metric-schema-verification-unsupported-field");
            return;
        }
        SchemaFieldDescriptor field = schemaField.get();
        if (!normalize(requestedField).equals(normalize(field.name()))) {
            metric.put("requestedField", requestedField);
            metric.put("field", field.name());
            String alias = metric.path("alias").asText("");
            if (!alias.isBlank() && normalize(alias).equals(normalize(requestedField))) {
                metric.put("alias", field.name());
            }
        }
        if (!field.label().isBlank() && metric.path("label").asText("").isBlank()) {
            metric.put("label", field.label());
        }
        repairCountAggregationForNumericMetric(metric, field, warnings);
        metric.put("schemaVerified", true);
        metric.put("schemaProbeStatus", "verified");
        metric.put("schemaEvidenceSource", "schemas.filtered");
        metric.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
    }

    private void alignComparisonOutputMetric(
            ObjectNode metric,
            ComparisonOutputMetric output,
            Map<String, SchemaFieldDescriptor> schemaFields,
            SchemaFetchResult schemaResult,
            List<String> warnings) {
        Optional<SchemaFieldDescriptor> schemaField = schemaFields.values().stream()
                .filter(field -> normalize(field.name()).equals(normalize(output.sourceField())))
                .findFirst()
                .or(() -> resolveSchemaField(axisProbe(output.sourceField()), schemaFields));
        if (schemaField.isEmpty()) {
            metric.put("schemaVerified", false);
            metric.put("schemaProbeStatus", "unsupported-derived-comparison-output");
            addWarningOnce(warnings, "semantic-chart-metric-schema-verification-unsupported-field");
            return;
        }

        String requestedField = metric.path("field").asText("");
        String canonicalField = "__praxisComparison_"
                + schemaField.get().name()
                + "_"
                + output.period();
        if (!requestedField.equals(canonicalField)) {
            metric.put("requestedField", requestedField);
            metric.put("field", canonicalField);
            if (normalize(metric.path("alias").asText("")).equals(normalize(requestedField))) {
                metric.put("alias", canonicalField);
            }
        }
        metric.put("schemaVerified", true);
        metric.put("schemaProbeStatus", "verified-derived-comparison-output");
        metric.put("schemaEvidenceSource", "schemas.filtered");
        metric.put("schemaEvidenceUrl", schemaResult == null ? "" : value(schemaResult.getEndpointUrl()));
    }

    private Optional<ComparisonOutputMetric> comparisonOutputMetric(String field) {
        String prefix = "__praxisComparison_";
        if (field == null || !field.startsWith(prefix)) {
            return Optional.empty();
        }
        for (String period : List.of("current", "previous")) {
            String suffix = "_" + period;
            if (field.endsWith(suffix) && field.length() > prefix.length() + suffix.length()) {
                return Optional.of(new ComparisonOutputMetric(
                        field.substring(prefix.length(), field.length() - suffix.length()),
                        period));
            }
        }
        return Optional.empty();
    }

    private void repairCountAggregationForNumericMetric(
            ObjectNode metric,
            SchemaFieldDescriptor field,
            List<String> warnings) {
        if (field == null || !isNumericSchemaField(field)) {
            return;
        }
        String aggregation = metric.path("aggregation").asText("");
        String operation = metric.path("operation").asText("");
        if ("count".equals(normalize(operation))) {
            String alias = metric.path("alias").asText("");
            if (!alias.isBlank() && normalize(alias).equals(normalize(field.name()))) {
                metric.put("alias", "total");
            }
            metric.remove("field");
            addWarningOnce(warnings, "semantic-chart-count-metric-field-removed-for-stats-contract");
        }
        if ("count".equals(normalize(aggregation))) {
            addWarningOnce(warnings, "semantic-chart-count-metric-preserved-for-record-count");
        }
    }

    private boolean isNumericSchemaField(SchemaFieldDescriptor field) {
        return field != null && Set.of("number", "integer").contains(normalize(field.type()));
    }

    private Optional<SchemaFieldDescriptor> resolveSchemaField(
            ObjectNode semanticAxis,
            Map<String, SchemaFieldDescriptor> schemaFields) {
        String requestedField = semanticAxis.path("field").asText("");
        SchemaFieldDescriptor exact = schemaFields.get(normalize(requestedField));
        if (exact != null) {
            return Optional.of(exact);
        }
        if (isAggregateCountAxis(semanticAxis)) {
            return preferredGroupingField(schemaFields);
        }
        Set<String> requestedTokens = semanticAxisTokens(semanticAxis);
        SchemaFieldDescriptor best = null;
        int bestScore = 0;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            int score = semanticMatchScore(requestedTokens, field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return bestScore >= 2 ? Optional.of(best) : Optional.empty();
    }

    private boolean isAggregateCountAxis(ObjectNode semanticAxis) {
        String raw = normalize(String.join(" ",
                semanticAxis.path("concept").asText(""),
                semanticAxis.path("field").asText(""),
                semanticAxis.path("label").asText("")));
        if (!containsAny(raw, "quantidade", "qtd", "count", "total", "registro", "registros")) {
            return false;
        }
        Set<String> tokens = new LinkedHashSet<>(semanticAxisTokens(semanticAxis));
        tokens.removeAll(Set.of(
                "recordcount",
                "record",
                "records",
                "quantidade",
                "qtd",
                "count",
                "contagem",
                "registro",
                "registros"));
        return tokens.isEmpty();
    }

    private Optional<SchemaFieldDescriptor> preferredGroupingField(Map<String, SchemaFieldDescriptor> schemaFields) {
        SchemaFieldDescriptor best = null;
        int bestScore = Integer.MIN_VALUE;
        for (SchemaFieldDescriptor field : schemaFields.values()) {
            int score = groupingFieldScore(field);
            if (score > bestScore) {
                best = field;
                bestScore = score;
            }
        }
        return best == null || bestScore <= 0 ? Optional.empty() : Optional.of(best);
    }

    private int groupingFieldScore(SchemaFieldDescriptor field) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.addAll(field.fieldTokens());
        tokens.addAll(field.labelTokens());
        tokens.addAll(field.descriptionTokens());
        if (tokens.isEmpty()) {
            return 0;
        }
        int score = 10;
        if (containsAnyToken(tokens, "status", "situacao", "andamento", "estado")) {
            score += 100;
        }
        if (containsAnyToken(tokens, "categoria", "classe", "tipo", "natureza")) {
            score += 90;
        }
        if (containsAnyToken(tokens, "mes", "competencia", "periodo")) {
            score += 80;
        }
        if (containsAnyToken(tokens, "ano", "exercicio")) {
            score += 75;
        }
        if (containsAnyToken(tokens, "data", "created", "criacao")) {
            score += 60;
        }
        if (containsAnyToken(tokens, "id", "uuid", "codigo")) {
            score -= 80;
        }
        if (containsAnyToken(tokens,
                "valor",
                "total",
                "quantidade",
                "desconto",
                "bruto",
                "liquido",
                "preco",
                "amount")) {
            score -= 100;
        }
        return score;
    }

    private boolean containsAnyToken(Set<String> tokens, String... expected) {
        for (String token : expected) {
            if (tokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String statsOperation(JsonNode widget) {
        return value(widget.path("inputs").path("config").path("dataSource").path("query").path("statsOperation").asText(""));
    }

    private int semanticMatchScore(Set<String> requestedTokens, SchemaFieldDescriptor field) {
        if (requestedTokens.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String token : requestedTokens) {
            if (field.fieldTokens().contains(token)) {
                score += 3;
            } else if (field.labelTokens().contains(token)) {
                score += 3;
            } else if (field.descriptionTokens().contains(token)) {
                score += 2;
            }
        }
        if (score > 0 && containsAnyToken(field.fieldTokens(), "id", "uuid", "codigo")) {
            score -= 1;
        }
        if (score > 0 && containsAnyToken(field.fieldTokens(), "nome", "name", "label", "descricao", "description")) {
            score += 1;
        }
        return score;
    }

    private boolean isStatusLikeConcept(Set<String> tokens) {
        return containsAnyToken(tokens,
                "status",
                "situacao",
                "estado",
                "state",
                "active",
                "inactive",
                "ativo",
                "inativo",
                "andamento");
    }

    private Set<String> semanticAxisTokens(ObjectNode semanticAxis) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, semanticAxis.path("concept").asText(""));
        addTokens(tokens, semanticAxis.path("field").asText(""));
        addTokens(tokens, semanticAxis.path("label").asText(""));
        return tokens;
    }

    private Set<String> authoringContextTokens(
            AgenticAuthoringPlanRequest request,
            JsonNode uiCompositionPlan) {
        Set<String> tokens = new LinkedHashSet<>();
        if (request != null) {
            addPromptTokens(tokens, request);
            AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
            if (intent != null) {
                AgenticAuthoringVisualizationDecision visualization = intent.visualizationDecision();
                if (visualization != null) {
                    addTokens(tokens, visualization.intent());
                    addTokens(tokens, visualization.layoutKind());
                    addTokens(tokens, visualization.provenance());
                    if (visualization.axes() != null) {
                        for (AgenticAuthoringVisualizationAxisDecision axis : visualization.axes()) {
                            addTokens(tokens, axis.concept());
                            addTokens(tokens, axis.field());
                            addTokens(tokens, axis.label());
                            addTokens(tokens, axis.metricField());
                            addTokens(tokens, axis.metricLabel());
                        }
                    }
                }
            }
        }
        JsonNode diagnostics = uiCompositionPlan == null ? MissingNode.getInstance() : uiCompositionPlan.path("diagnostics");
        addTokens(tokens, diagnostics.path("visualizationDecisionIntent").asText(""));
        addTokens(tokens, diagnostics.path("visualizationDecisionProvenance").asText(""));
        return tokens;
    }

    private Set<String> authoringPromptTokens(AgenticAuthoringPlanRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        addPromptTokens(tokens, request);
        return tokens;
    }

    private Set<String> authoringPromptGroupingTokens(AgenticAuthoringPlanRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        String prompt = normalize(String.join(" ",
                request == null ? "" : request.userPrompt(),
                request == null || request.intentResolution() == null ? "" : request.intentResolution().effectivePrompt()))
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (prompt.isBlank()) {
            return tokens;
        }
        String candidate = textAfterLastPromptMarker(prompt, " por ");
        if (candidate.isBlank()) {
            candidate = textAfterLastPromptMarker(prompt, " by ");
        }
        if (candidate.isBlank()) {
            return tokens;
        }
        int stop = firstPromptStopIndex(candidate,
                " somando ",
                " soma ",
                " somar ",
                " agregando ",
                " usando ",
                " use ",
                " usar ",
                " com ",
                " filtro ",
                " filtrar ",
                " filtrado ",
                " e ",
                " nao ",
                " sem ",
                " without ");
        addTokens(tokens, stop >= 0 ? candidate.substring(0, stop) : candidate);
        return tokens;
    }

    private String textAfterLastPromptMarker(String prompt, String marker) {
        int index = prompt.lastIndexOf(marker);
        return index < 0 ? "" : prompt.substring(index + marker.length());
    }

    private int firstPromptStopIndex(String value, String... stops) {
        int best = -1;
        for (String stop : stops) {
            int index = value.indexOf(stop);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private void addPromptTokens(Set<String> tokens, AgenticAuthoringPlanRequest request) {
        if (request == null) {
            return;
        }
        addTokens(tokens, request.userPrompt());
        AgenticAuthoringIntentResolutionResult intent = request.intentResolution();
        if (intent != null) {
            addTokens(tokens, intent.effectivePrompt());
        }
    }

    SchemaFieldDescriptor schemaFieldDescriptor(String field, JsonNode property) {
        JsonNode xUi = property.path("x-ui");
        if ((xUi.isMissingNode() || xUi.isNull()) && property.path("items").path("x-ui").isObject()) {
            xUi = property.path("items").path("x-ui");
        }
        String label = firstNonBlank(
                xUi.path("label").asText(""),
                property.path("title").asText(""));
        String description = firstNonBlank(
                property.path("description").asText(""),
                xUi.path("helpText").asText(""));
        return new SchemaFieldDescriptor(
                field,
                label,
                description,
                property.path("type").asText(""),
                property.path("items").path("type").asText(""),
                property.path("format").asText(""),
                property.path("enum").isArray() && !property.path("enum").isEmpty(),
                xUi.path("controlType").asText(""),
                xUi.path("multiple").asBoolean(false),
                xUi.path("endpoint").asText(""),
                xUi.path("hidden").asBoolean(false),
                xUi.path("tableHidden").asBoolean(false),
                tokens(field),
                tokens(label),
                tokens(description));
    }

    Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, value);
        return tokens;
    }

    private void addTokens(Set<String> tokens, String value) {
        String tokenizable = value == null
                ? ""
                : value.replaceAll("([a-z])([A-Z])", "$1 $2");
        for (String token : normalize(tokenizable).replaceAll("[^a-z0-9]+", " ").split("\\s+")) {
            if (token.length() >= 3 && !SEMANTIC_AXIS_STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
    }

    private void addWarningOnce(List<String> warnings, String warning) {
        if (warnings != null && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    boolean containsUnverifiedSemanticAxes(JsonNode uiCompositionPlan) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if (!axis.path("schemaVerified").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsUnsupportedSemanticAxes(JsonNode uiCompositionPlan) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return false;
        }
        for (JsonNode axis : axes) {
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            if ("unsupported".equals(axis.path("schemaProbeStatus").asText(""))) {
                return true;
            }
        }
        return false;
    }

    void markOrphanUnverifiedSemanticAxesAsDropped(
            ObjectNode uiCompositionPlan,
            List<String> warnings) {
        JsonNode axes = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            return;
        }
        Set<String> materializedChartFields = materializedChartSemanticAxisFields(uiCompositionPlan);
        for (JsonNode axis : axes) {
            if (!(axis instanceof ObjectNode axisObject)
                    || axisObject.path("schemaVerified").asBoolean(false)
                    || axisObject.path("materialized").isBoolean() && !axisObject.path("materialized").asBoolean()) {
                continue;
            }
            String field = normalize(axisObject.path("field").asText(""));
            if (field.isBlank() || !materializedChartFields.contains(field)) {
                markSemanticAxisDropped(axisObject, "chart-axis-not-materialized");
            }
        }
        if (!containsUnsupportedSemanticAxes(uiCompositionPlan)) {
            warnings.remove("semantic-axis-schema-verification-unsupported-axis");
        }
        if (!containsUnverifiedSemanticAxes(uiCompositionPlan)) {
            warnings.remove("semantic-axis-schema-verification-pending");
        }
    }

    private Set<String> materializedChartSemanticAxisFields(JsonNode uiCompositionPlan) {
        Set<String> fields = new LinkedHashSet<>();
        JsonNode widgets = uiCompositionPlan == null
                ? MissingNode.getInstance()
                : uiCompositionPlan.path("widgets");
        if (!widgets.isArray()) {
            return fields;
        }
        for (JsonNode widget : widgets) {
            if (!"praxis-chart".equals(widget.path("componentId").asText(""))) {
                continue;
            }
            JsonNode axis = widget.path("inputs").path("config").path("semanticAxis");
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            String field = normalize(axis.path("field").asText(""));
            if (!field.isBlank()) {
                fields.add(field);
            }
        }
        return fields;
    }

    private String businessResourcePath(String resourcePath) {
        String value = value(resourcePath).replaceAll("/+$", "");
        for (String suffix : List.of(
                "/stats/group-by",
                "/stats/timeseries",
                "/stats/distribution",
                "/stats/comparison",
                "/filter/cursor",
                "/filter",
                "/all")) {
            if (value.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        return value;
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String first, String second) {
        return value(first).isBlank() ? value(second) : value(first);
    }

    private String normalize(String value) {
        String normalized = java.text.Normalizer.normalize(value(value), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String valueOrDefault(String value, String fallback) {
        String sanitized = value(value).trim();
        return sanitized.isBlank() ? value(fallback).trim() : sanitized;
    }

    private record ComparisonOutputMetric(String sourceField, String period) {
    }
}
