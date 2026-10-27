package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnAdvisorySupport.AgenticAuthoringTurnRoute;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.LiveOptionValueCandidate;
import org.praxisplatform.config.service.LiveOptionValueRetrievalResult;
import org.springframework.util.StringUtils;

/**
 * Encapsulates live option-source field/value grounding, static enum filter grounding, and
 * post-resolution constraint reconciliation for {@link AgenticAuthoringTurnEngine}.
 */
final class AgenticAuthoringTurnLiveOptionGroundingSupport {

    record LiveOptionGroundingExecution(
            AgenticAuthoringToolResult toolResult,
            LiveOptionValueRetrievalResult result) {

        static LiveOptionGroundingExecution none() {
            return new LiveOptionGroundingExecution(null, null);
        }
    }

    record LiveOptionFieldGroundingExecution(
            AgenticAuthoringToolResult toolResult,
            ObjectNode projection) {

        static LiveOptionFieldGroundingExecution none() {
            return new LiveOptionFieldGroundingExecution(null, null);
        }
    }

    record StaticEnumFilterGroundingExecution(
            AgenticAuthoringToolResult toolResult,
            ObjectNode projection) {

        static StaticEnumFilterGroundingExecution none() {
            return new StaticEnumFilterGroundingExecution(null, null);
        }
    }

    private record StaticEnumPropertyCandidate(
            String field,
            JsonNode canonicalValue,
            int fieldMatchScore) {
    }

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringToolRegistry toolRegistry;

    AgenticAuthoringTurnLiveOptionGroundingSupport(
            ObjectMapper objectMapper,
            AgenticAuthoringToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    AgenticAuthoringIntentResolutionResult blockUngroundedLiveOptionMaterialization(
            AgenticAuthoringIntentResolutionResult resolution) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution == null || resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("live-option-value-selection-unresolved");
        warnings.add("live-option-value-materialization-failed-closed");
        LinkedHashSet<String> failureCodes = new LinkedHashSet<>(
                resolution == null || resolution.failureCodes() == null ? List.of() : resolution.failureCodes());
        failureCodes.add("live-option-value-confirmation-required");
        List<String> questions = resolution != null
                && resolution.clarificationQuestions() != null
                && !resolution.clarificationQuestions().isEmpty()
                ? resolution.clarificationQuestions()
                : List.of("Quais dos valores encontrados você deseja incluir nesse filtro?");
        AgenticAuthoringGateResult gate = new AgenticAuthoringGateResult(
                "live-option-value-grounding@0.1.0",
                "clarification_required",
                List.copyOf(failureCodes));
        return new AgenticAuthoringIntentResolutionResult(
                false,
                resolution == null ? "unknown" : resolution.operationKind(),
                resolution == null ? "unknown" : resolution.artifactKind(),
                resolution == null ? "live_option_value_confirmation" : resolution.changeKind(),
                "live-option-value-confirmation-required",
                resolution == null ? "" : resolution.targetApp(),
                resolution == null ? "" : resolution.targetComponentId(),
                resolution == null ? null : resolution.target(),
                resolution == null ? null : resolution.selectedCandidate(),
                resolution == null ? List.of() : resolution.candidates(),
                gate,
                resolution == null ? "" : resolution.effectivePrompt(),
                "Encontrei valores atuais relacionados ao pedido, mas preciso confirmar quais deles devem entrar no filtro.",
                null,
                null,
                resolution == null ? List.of() : resolution.quickReplies(),
                resolution == null ? null : resolution.pendingClarification(),
                questions,
                List.copyOf(warnings),
                List.copyOf(failureCodes),
                resolution == null ? objectMapper.createObjectNode() : resolution.currentPageSummary(),
                resolution == null ? objectMapper.createObjectNode() : resolution.llmDiagnostics(),
                resolution == null ? null : resolution.visualizationDecision(),
                resolution == null ? null : resolution.semanticDecision());
    }

    AgenticAuthoringIntentResolutionResult blockUnavailableLiveOptionMaterialization(
            AgenticAuthoringIntentResolutionResult resolution,
            AgenticAuthoringToolResult toolResult) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution == null || resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("live-option-values-unavailable");
        warnings.add("live-option-value-materialization-failed-closed");
        if (toolResult != null && StringUtils.hasText(toolResult.errorCode())) {
            warnings.add(toolResult.errorCode());
        }
        LinkedHashSet<String> failureCodes = new LinkedHashSet<>(
                resolution == null || resolution.failureCodes() == null ? List.of() : resolution.failureCodes());
        failureCodes.add("live-option-values-unavailable");
        AgenticAuthoringGateResult gate = new AgenticAuthoringGateResult(
                "live-option-value-grounding@0.1.0",
                "blocked",
                List.copyOf(failureCodes));
        return new AgenticAuthoringIntentResolutionResult(
                false,
                resolution == null ? "unknown" : resolution.operationKind(),
                resolution == null ? "unknown" : resolution.artifactKind(),
                resolution == null ? "live_option_value_grounding" : resolution.changeKind(),
                "live-option-values-unavailable",
                resolution == null ? "" : resolution.targetApp(),
                resolution == null ? "" : resolution.targetComponentId(),
                resolution == null ? null : resolution.target(),
                resolution == null ? null : resolution.selectedCandidate(),
                resolution == null ? List.of() : resolution.candidates(),
                gate,
                resolution == null ? "" : resolution.effectivePrompt(),
                "Não consegui consultar com segurança os valores atuais desse campo. A tabela não foi alterada para evitar aplicar um filtro incorreto.",
                null,
                null,
                resolution == null ? List.of() : resolution.quickReplies(),
                resolution == null ? null : resolution.pendingClarification(),
                List.of("Tente novamente quando os dados do domínio estiverem disponíveis."),
                List.copyOf(warnings),
                List.copyOf(failureCodes),
                resolution == null ? objectMapper.createObjectNode() : resolution.currentPageSummary(),
                resolution == null ? objectMapper.createObjectNode() : resolution.llmDiagnostics(),
                resolution == null ? null : resolution.visualizationDecision(),
                resolution == null ? null : resolution.semanticDecision());
    }

    LiveOptionGroundingExecution maybeRunLiveOptionValueGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        if (eventSink.terminalReached()
                || route == null
                || !route.allowsPreview()
                || intentResolution == null
                || !intentResolution.valid()
                || intentResolution.semanticDecision() == null
                || intentResolution.semanticDecision().selectedResource() == null
                || request == null
                || request.contextHints() != null
                        && (request.contextHints().path("liveOptionValueGrounding").isObject()
                                || request.contextHints().path("staticEnumFilterGrounding").isObject())) {
            return LiveOptionGroundingExecution.none();
        }
        JsonNode filter = firstSemanticTextConstraint(intentResolution.semanticDecision().constraints());
        if (filter == null) {
            return LiveOptionGroundingExecution.none();
        }
        JsonNode fieldGrounding = request.contextHints() == null
                ? null
                : request.contextHints().path("liveOptionFieldGrounding");
        if (!isCanonicalLiveOptionConstraint(filter, fieldGrounding)) {
            return LiveOptionGroundingExecution.none();
        }
        String resourcePath = intentResolution.semanticDecision().selectedResource().resourcePath();
        if (!StringUtils.hasText(resourcePath)) {
            return LiveOptionGroundingExecution.none();
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_OPTION_SOURCE_VALUES,
                route.routeClass(),
                new LiveOptionValueToolRequest(
                        resourcePath,
                        filter.path("field").asText(""),
                        filter.path("concept").asText(""),
                        filter.path("operator").asText(""),
                        filter.path("value").deepCopy(),
                        objectMapper.createObjectNode(),
                        100,
                        false));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou consultando os valores atuais do campo governado antes de materializar o filtro.",
                Map.of(
                        "tool", toolCall.name(),
                        "routeClass", safeText(route.routeClass()),
                        "semanticField", filter.path("field").asText(""))));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        eventSink.append("thought.step", safeToolProjection(
                toolResult.valid() ? "tool.result" : "tool.error",
                toolResult.valid()
                        ? "Os valores atuais foram recuperados e serão reconciliados semanticamente."
                        : "Os valores atuais não puderam ser recuperados com segurança.",
                safeToolDiagnostics(toolResult)));
        return toolResult.valid() && toolResult.payload() instanceof LiveOptionValueRetrievalResult result
                ? new LiveOptionGroundingExecution(toolResult, result)
                : new LiveOptionGroundingExecution(toolResult, null);
    }

    LiveOptionFieldGroundingExecution maybeRunLiveOptionFieldGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        if (eventSink.terminalReached()
                || route == null
                || !route.allowsPreview()
                || intentResolution == null
                || !intentResolution.valid()
                || intentResolution.semanticDecision() == null
                || intentResolution.semanticDecision().selectedResource() == null
                || request == null
                || request.contextHints() != null
                        && (request.contextHints().path("liveOptionFieldGrounding").isObject()
                                || request.contextHints().path("staticEnumFilterGrounding").isObject())) {
            return LiveOptionFieldGroundingExecution.none();
        }
        JsonNode filter = firstSemanticTextConstraint(intentResolution.semanticDecision().constraints());
        if (filter == null) {
            return LiveOptionFieldGroundingExecution.none();
        }
        String resourcePath = intentResolution.semanticDecision().selectedResource().resourcePath();
        if (!StringUtils.hasText(resourcePath)) {
            return LiveOptionFieldGroundingExecution.none();
        }
        String filterPath = resourcePath.endsWith("/filter") ? resourcePath : resourcePath + "/filter";
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_SCHEMA_FIELDS,
                route.routeClass(),
                new SchemaFieldsToolRequest(
                        filterPath,
                        "post",
                        "request",
                        filter.path("concept").asText(filter.path("field").asText("")),
                        requestBaseUrl,
                        50));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou consultando os campos governados do recurso antes de buscar valores atuais.",
                Map.of(
                        "tool", toolCall.name(),
                        "resourcePath", resourcePath)));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        ObjectNode projection = toolResult.valid()
                ? liveOptionFieldProjection(toolResult.payload(), resourcePath, filter)
                : null;
        eventSink.append("thought.step", safeToolProjection(
                projection != null ? "tool.result" : toolResult.valid() ? "tool.result" : "tool.error",
                projection != null
                        ? "Os campos de seleção governados foram recuperados para decisão semântica."
                        : toolResult.valid()
                                ? "O recurso não publicou campos de seleção governados aplicáveis."
                                : "Os campos governados não puderam ser consultados.",
                projection != null
                        ? Map.of(
                                "tool", toolCall.name(),
                                "candidateCount", projection.path("candidates").size())
                        : safeToolDiagnostics(toolResult)));
        return new LiveOptionFieldGroundingExecution(toolResult, projection);
    }

    StaticEnumFilterGroundingExecution maybeRunStaticEnumFilterGrounding(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl) {
        if (eventSink.terminalReached()
                || route == null
                || !route.allowsPreview()
                || intentResolution == null
                || !intentResolution.valid()
                || intentResolution.semanticDecision() == null
                || intentResolution.semanticDecision().selectedResource() == null
                || request == null
                || request.contextHints() != null
                        && request.contextHints().path("staticEnumFilterGrounding").isObject()) {
            return StaticEnumFilterGroundingExecution.none();
        }
        JsonNode filter = firstSemanticTextConstraint(intentResolution.semanticDecision().constraints());
        if (filter == null) {
            return StaticEnumFilterGroundingExecution.none();
        }
        String resourcePath = intentResolution.semanticDecision().selectedResource().resourcePath();
        if (!StringUtils.hasText(resourcePath)) {
            return StaticEnumFilterGroundingExecution.none();
        }
        String filterPath = resourcePath.endsWith("/filter") ? resourcePath : resourcePath + "/filter";
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_SCHEMA_FIELDS,
                route.routeClass(),
                new SchemaFieldsToolRequest(
                        filterPath,
                        "post",
                        "request",
                        filter.path("field").asText(""),
                        requestBaseUrl,
                        50));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        ObjectNode projection = toolResult.valid()
                ? staticEnumFilterGroundingProjection(toolResult.payload(), resourcePath, filter)
                : null;
        if (projection != null) {
            eventSink.append("thought.step", safeToolProjection(
                    "tool.result",
                    "Validei o valor solicitado no enum publicado pelo schema do filtro.",
                    Map.of(
                            "tool", toolCall.name(),
                            "canonicalFilterField", projection.path("canonicalFilterField").asText(""),
                            "source", "canonical-filter-schema-enum")));
        }
        return new StaticEnumFilterGroundingExecution(toolResult, projection);
    }

    ObjectNode staticEnumFilterGroundingProjection(
            Object payload,
            String resourcePath,
            JsonNode originalPredicate) {
        JsonNode schema = payload instanceof JsonNode node ? node.path("schema") : null;
        if (schema == null || !schema.path("properties").isObject() || originalPredicate == null) {
            return null;
        }

        List<StaticEnumPropertyCandidate> candidates = new ArrayList<>();
        schema.path("properties").fields().forEachRemaining(entry -> {
            JsonNode property = entry.getValue();
            JsonNode enumValues = property.path("enum");
            if (!property.isObject() || !enumValues.isArray() || enumValues.isEmpty()) {
                return;
            }
            JsonNode canonicalValue = canonicalStaticEnumValue(property, originalPredicate.path("value"));
            if (canonicalValue == null) {
                return;
            }
            candidates.add(new StaticEnumPropertyCandidate(
                    entry.getKey(),
                    canonicalValue,
                    staticEnumFieldMatchScore(entry.getKey(), property, originalPredicate)));
        });
        if (candidates.isEmpty()) {
            return null;
        }
        int highestScore = candidates.stream()
                .mapToInt(StaticEnumPropertyCandidate::fieldMatchScore)
                .max()
                .orElse(0);
        List<StaticEnumPropertyCandidate> finalists = highestScore > 0
                ? candidates.stream().filter(candidate -> candidate.fieldMatchScore() == highestScore).toList()
                : candidates;
        if (finalists.size() != 1) {
            return null;
        }
        StaticEnumPropertyCandidate selected = finalists.get(0);
        ObjectNode projection = objectMapper.createObjectNode();
        projection.put("schemaVersion", "praxis-static-enum-filter-grounding.v1");
        projection.put("resourcePath", resourcePath);
        projection.put("canonicalFilterField", selected.field());
        projection.set("canonicalValue", selected.canonicalValue());
        projection.set("originalPredicate", originalPredicate.deepCopy());
        projection.put("source", "canonical-filter-schema-enum");
        return projection;
    }

    private int staticEnumFieldMatchScore(
            String canonicalField,
            JsonNode property,
            JsonNode originalPredicate) {
        String requestedField = normalizeGroundingText(originalPredicate.path("field").asText(""));
        String concept = normalizeGroundingText(originalPredicate.path("concept").asText(""));
        List<String> canonicalNames = List.of(
                normalizeGroundingText(canonicalField),
                normalizeGroundingText(property.path("x-ui").path("name").asText("")),
                normalizeGroundingText(property.path("x-ui").path("label").asText("")));
        int score = 0;
        for (int index = 0; index < canonicalNames.size(); index++) {
            String canonicalName = canonicalNames.get(index);
            if (!StringUtils.hasText(canonicalName)) {
                continue;
            }
            if (requestedField.equals(canonicalName)) {
                score = Math.max(score, 100 - (index * 5));
            } else if (StringUtils.hasText(requestedField)
                    && (containsGroundingPhrase(requestedField, canonicalName)
                            || containsGroundingPhrase(canonicalName, requestedField))) {
                score = Math.max(score, 75 - (index * 5));
            }
            if (containsGroundingPhrase(concept, canonicalName)) {
                score = Math.max(score, 55 - (index * 5));
            }
        }
        return score;
    }

    private boolean containsGroundingPhrase(String text, String phrase) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(phrase)
                && (" " + text + " ").contains(" " + phrase + " ");
    }

    private JsonNode canonicalStaticEnumValue(JsonNode property, JsonNode requestedValue) {
        if (requestedValue == null || requestedValue.isNull()) {
            return null;
        }
        if (requestedValue.isTextual()) {
            return canonicalStaticEnumScalar(property, requestedValue.asText(""));
        }
        if (!requestedValue.isArray() || requestedValue.isEmpty()) {
            return null;
        }
        ArrayNode canonical = objectMapper.createArrayNode();
        for (JsonNode item : requestedValue) {
            if (!item.isTextual()) {
                return null;
            }
            JsonNode resolved = canonicalStaticEnumScalar(property, item.asText(""));
            if (resolved == null) {
                return null;
            }
            canonical.add(resolved);
        }
        return canonical;
    }

    private JsonNode canonicalStaticEnumScalar(JsonNode property, String requestedValue) {
        String requested = normalizeGroundingText(requestedValue);
        if (!StringUtils.hasText(requested)) {
            return null;
        }
        JsonNode enumValues = property.path("enum");
        for (JsonNode enumValue : enumValues) {
            if (enumValue.isTextual()
                    && requested.equals(normalizeGroundingText(enumValue.asText("")))) {
                return enumValue.deepCopy();
            }
        }
        JsonNode options = property.path("x-ui").path("options");
        if (options.isArray()) {
            for (JsonNode option : options) {
                if (!requested.equals(normalizeGroundingText(option.path("label").asText("")))) {
                    continue;
                }
                JsonNode optionValue = option.get("value");
                if (optionValue == null || !optionValue.isTextual()) {
                    return null;
                }
                for (JsonNode enumValue : enumValues) {
                    if (enumValue.equals(optionValue)) {
                        return enumValue.deepCopy();
                    }
                }
            }
        }
        JsonNode nearMatch = null;
        for (JsonNode enumValue : enumValues) {
            if (!enumValue.isTextual()
                    || !isNearCanonicalGroundingText(requested, normalizeGroundingText(enumValue.asText("")))) {
                continue;
            }
            if (nearMatch != null && !nearMatch.equals(enumValue)) {
                return null;
            }
            nearMatch = enumValue.deepCopy();
        }
        if (options.isArray()) {
            for (JsonNode option : options) {
                JsonNode optionValue = option.get("value");
                if (optionValue == null
                        || !optionValue.isTextual()
                        || !isNearCanonicalGroundingText(
                                requested,
                                normalizeGroundingText(option.path("label").asText("")))) {
                    continue;
                }
                if (nearMatch != null && !nearMatch.equals(optionValue)) {
                    return null;
                }
                nearMatch = optionValue.deepCopy();
            }
        }
        return nearMatch;
    }

    private boolean isNearCanonicalGroundingText(String requested, String canonical) {
        if (!StringUtils.hasText(requested)
                || !StringUtils.hasText(canonical)
                || Math.abs(requested.length() - canonical.length()) > 1) {
            return false;
        }
        int previous = 0;
        int requestedIndex = 0;
        int canonicalIndex = 0;
        while (requestedIndex < requested.length() && canonicalIndex < canonical.length()) {
            if (requested.charAt(requestedIndex) == canonical.charAt(canonicalIndex)) {
                requestedIndex++;
                canonicalIndex++;
                continue;
            }
            if (++previous > 1) {
                return false;
            }
            if (requested.length() > canonical.length()) {
                requestedIndex++;
            } else if (canonical.length() > requested.length()) {
                canonicalIndex++;
            } else {
                requestedIndex++;
                canonicalIndex++;
            }
        }
        if (requestedIndex < requested.length() || canonicalIndex < canonical.length()) {
            previous++;
        }
        return previous == 1;
    }

    AgenticAuthoringTurnStreamRequest withStaticEnumFilterGrounding(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode projection) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("staticEnumFilterGrounding", projection.deepCopy());
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringIntentResolutionResult withCanonicalStaticEnumConstraint(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode grounding) {
        if (resolution == null
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null
                || grounding == null
                || !StringUtils.hasText(grounding.path("canonicalFilterField").asText(""))
                || grounding.get("canonicalValue") == null) {
            return resolution;
        }
        String field = grounding.path("canonicalFilterField").asText("");
        JsonNode originalPredicate = grounding.path("originalPredicate");
        ObjectNode constraints = resolution.semanticDecision().constraints().deepCopy();
        boolean replaced = false;
        for (JsonNode filter : constraints.path("filters")) {
            boolean matchesOriginalPredicate = originalPredicate.isObject() && originalPredicate.equals(filter);
            boolean matchesOriginalField = originalPredicate.isObject()
                    && StringUtils.hasText(originalPredicate.path("field").asText(""))
                    && originalPredicate.path("field").asText("").equals(filter.path("field").asText(""));
            if (!replaced
                    && filter.isObject()
                    && (matchesOriginalPredicate || matchesOriginalField || field.equals(filter.path("field").asText("")))) {
                ((ObjectNode) filter).put("field", field);
                ((ObjectNode) filter).set("value", grounding.path("canonicalValue").deepCopy());
                replaced = true;
            }
        }
        if (!replaced) {
            return resolution;
        }
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("static-enum-filter-grounded-by-canonical-schema");
        AgenticAuthoringSemanticDecision groundedDecision = resolution.semanticDecision().withConstraints(constraints);
        return new AgenticAuthoringIntentResolutionResult(
                resolution.valid(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.authoringProfile(),
                resolution.targetApp(),
                resolution.targetComponentId(),
                resolution.target(),
                resolution.selectedCandidate(),
                resolution.candidates(),
                resolution.gate(),
                resolution.effectivePrompt(),
                resolution.assistantMessage(),
                resolution.assistantContent(),
                resolution.apiCatalogAnswer(),
                resolution.quickReplies(),
                resolution.pendingClarification(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.failureCodes(),
                resolution.currentPageSummary(),
                resolution.llmDiagnostics(),
                resolution.visualizationDecision(),
                groundedDecision);
    }

    ObjectNode liveOptionFieldProjection(
            Object payload,
            String resourcePath,
            JsonNode originalPredicate) {
        JsonNode schema = payload instanceof JsonNode node ? node.path("schema") : null;
        if (schema == null || !schema.path("properties").isObject()) {
            return null;
        }
        String authoredField = originalPredicate == null
                ? ""
                : originalPredicate.path("field").asText("");
        JsonNode authoredProperty = StringUtils.hasText(authoredField)
                ? schema.path("properties").path(authoredField)
                : null;
        if (authoredProperty != null
                && authoredProperty.isObject()
                && !authoredProperty.path("x-ui").path("optionSource").isObject()) {
            return null;
        }
        ObjectNode projection = objectMapper.createObjectNode();
        projection.put("schemaVersion", "praxis-live-option-field-grounding.v1");
        projection.put("resourcePath", resourcePath);
        projection.set("originalPredicate", originalPredicate.deepCopy());
        ArrayNode candidates = projection.putArray("candidates");
        schema.path("properties").fields().forEachRemaining(entry -> {
            JsonNode property = entry.getValue();
            JsonNode optionSource = property.path("x-ui").path("optionSource");
            JsonNode aiUsage = property.path("x-domain-governance").path("aiUsage");
            if (!optionSource.isObject()
                    || !"allow".equals(aiUsage.path("visibility").asText(""))
                    || !List.of("allow", "review_required").contains(aiUsage.path("reasoningUse").asText(""))) {
                return;
            }
            ObjectNode candidate = candidates.addObject();
            candidate.put("canonicalFilterField", entry.getKey());
            candidate.put("label", property.path("x-ui").path("label").asText(""));
            candidate.put("description", property.path("description").asText(""));
            candidate.put("optionSourceKey", optionSource.path("key").asText(""));
            candidate.put("optionResourcePath", optionSource.path("resourcePath").asText(""));
            candidate.put("multiple", property.path("x-ui").path("multiple").asBoolean(
                    property.path("type").asText("").equals("array")));
        });
        return candidates.isEmpty() ? null : projection;
    }

    AgenticAuthoringTurnStreamRequest withLiveOptionFieldGrounding(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode projection) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("liveOptionFieldGrounding", projection.deepCopy());
        return copyWithContextHints(request, contextHints);
    }

    JsonNode firstSemanticTextConstraint(JsonNode constraints) {
        if (constraints == null
                || !constraints.path("appliesToDataSelection").asBoolean(false)
                || !constraints.path("filters").isArray()) {
            return null;
        }
        for (JsonNode filter : constraints.path("filters")) {
            if (filter.isObject()
                    && (StringUtils.hasText(filter.path("field").asText(""))
                            || StringUtils.hasText(filter.path("concept").asText("")))
                    && isSemanticTextValue(filter.path("value"))) {
                return filter;
            }
        }
        return null;
    }

    private boolean isSemanticTextValue(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return StringUtils.hasText(value.asText(""));
        }
        if (!value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || !StringUtils.hasText(item.asText(""))) {
                return false;
            }
        }
        return true;
    }

    LiveOptionGroundingExecution maybeConfirmLiveOptionSelection(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult resolution,
            AgenticAuthoringTurnRoute route,
            String requestBaseUrl,
            LiveOptionValueRetrievalResult grounding) {
        JsonNode selection = selectedLiveOptionConstraint(resolution, grounding);
        if (selection == null) {
            return LiveOptionGroundingExecution.none();
        }
        AgenticAuthoringToolCall toolCall = new AgenticAuthoringToolCall(
                AgenticAuthoringToolRegistry.SEARCH_OPTION_SOURCE_VALUES,
                route.routeClass(),
                new LiveOptionValueToolRequest(
                        grounding.resourcePath(),
                        grounding.canonicalFilterField(),
                        selection.path("concept").asText(""),
                        "in",
                        selection.path("value").deepCopy(),
                        objectMapper.createObjectNode(),
                        Math.max(1, selection.path("value").size()),
                        true));
        eventSink.append("thought.step", safeToolProjection(
                "tool.start",
                "Estou confirmando os valores selecionados no escopo atual antes de montar o filtro.",
                Map.of(
                        "tool", toolCall.name(),
                        "canonicalFilterField", grounding.canonicalFilterField(),
                        "selectionCount", selection.path("value").size())));
        AgenticAuthoringToolResult toolResult = toolRegistry.execute(
                toolCall,
                principalContext,
                "retrieveEvidence",
                requestBaseUrl);
        eventSink.append("thought.step", safeToolProjection(
                toolResult.valid() ? "tool.result" : "tool.error",
                toolResult.valid()
                        ? "A seleção foi recarregada pela superfície canônica de valores atuais."
                        : "A seleção não pôde ser confirmada no escopo atual.",
                safeToolDiagnostics(toolResult)));
        return toolResult.valid() && toolResult.payload() instanceof LiveOptionValueRetrievalResult result
                ? new LiveOptionGroundingExecution(toolResult, result)
                : new LiveOptionGroundingExecution(toolResult, null);
    }

    AgenticAuthoringTurnStreamRequest withLiveOptionValueGrounding(
            AgenticAuthoringTurnStreamRequest request,
            LiveOptionValueRetrievalResult result) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.remove("liveOptionFieldGrounding");
        contextHints.set("liveOptionValueGrounding", objectMapper.valueToTree(result));
        return copyWithContextHints(request, contextHints);
    }

    boolean hasValidatedLiveOptionSelection(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        if (resolution == null
                || !resolution.valid()
                || resolution.semanticDecision() == null
                || grounding == null
                || !grounding.valid()
                || !grounding.exhaustive()) {
            return false;
        }
        JsonNode filters = resolution.semanticDecision().constraints() == null
                ? null
                : resolution.semanticDecision().constraints().path("filters");
        if (filters == null || !filters.isArray()) {
            return false;
        }
        for (JsonNode filter : filters) {
            if (!grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    || !"in".equals(filter.path("operator").asText(""))
                    || !filter.path("value").isArray()
                    || filter.path("value").isEmpty()) {
                continue;
            }
            for (JsonNode selectedId : filter.path("value")) {
                boolean known = grounding.candidates().stream()
                        .map(LiveOptionValueCandidate::id)
                        .anyMatch(candidateId -> candidateId != null && candidateId.equals(selectedId));
                if (!known) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    AgenticAuthoringIntentResolutionResult collapseSemanticallyCoveredLiveOptionConstraints(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        if (resolution == null
                || grounding == null
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null) {
            return resolution;
        }
        JsonNode originalFilters = resolution.semanticDecision().constraints().path("filters");
        if (!originalFilters.isArray()) {
            return resolution;
        }
        ArrayNode canonicalSelection = objectMapper.createArrayNode();
        int canonicalConstraintCount = 0;
        for (JsonNode filter : originalFilters) {
            if (!grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    || !"in".equals(filter.path("operator").asText(""))
                    || !filter.path("value").isArray()
                    || filter.path("value").isEmpty()) {
                continue;
            }
            canonicalConstraintCount++;
            for (JsonNode selectedId : filter.path("value")) {
                boolean duplicate = false;
                for (JsonNode existingId : canonicalSelection) {
                    if (existingId.equals(selectedId)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    canonicalSelection.add(selectedId.deepCopy());
                }
            }
        }
        if (canonicalSelection.isEmpty()) {
            return resolution;
        }
        List<String> selectedCandidateLabels = selectedLiveOptionCandidateLabels(
                canonicalSelection,
                grounding);
        ObjectNode constraints = resolution.semanticDecision().constraints().deepCopy();
        JsonNode filters = constraints.path("filters");
        if (!filters.isArray() || filters.size() < 2) {
            return resolution;
        }
        ArrayNode reconciledFilters = objectMapper.createArrayNode();
        int collapsedTextConstraints = 0;
        int collapsedCanonicalConstraints = 0;
        boolean canonicalSelectionAdded = false;
        for (JsonNode filter : filters) {
            boolean canonicalConstraint = grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    && "in".equals(filter.path("operator").asText(""))
                    && filter.path("value").isArray()
                    && !filter.path("value").isEmpty();
            if (canonicalConstraint) {
                if (!canonicalSelectionAdded) {
                    ObjectNode consolidated = filter.deepCopy();
                    consolidated.set("value", canonicalSelection.deepCopy());
                    reconciledFilters.add(consolidated);
                    canonicalSelectionAdded = true;
                } else {
                    collapsedCanonicalConstraints++;
                }
                continue;
            }
            if (!selectedCandidateLabels.isEmpty()
                    && isSemanticTextValue(filter.path("value"))
                    && isTextConstraintCoveredBySelectedLiveOptionLabels(
                            filter.path("value"),
                            selectedCandidateLabels)) {
                collapsedTextConstraints++;
                continue;
            }
            reconciledFilters.add(filter.deepCopy());
        }
        if (collapsedTextConstraints == 0 && collapsedCanonicalConstraints == 0) {
            return resolution;
        }
        constraints.set("filters", reconciledFilters);
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        if (collapsedTextConstraints > 0) {
            warnings.add("live-option-redundant-semantic-constraint-collapsed");
        }
        if (canonicalConstraintCount > 1 && collapsedCanonicalConstraints > 0) {
            warnings.add("live-option-duplicate-canonical-constraint-unioned");
        }
        AgenticAuthoringSemanticDecision reconciledDecision =
                resolution.semanticDecision().withConstraints(constraints);
        return new AgenticAuthoringIntentResolutionResult(
                resolution.valid(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.authoringProfile(),
                resolution.targetApp(),
                resolution.targetComponentId(),
                resolution.target(),
                resolution.selectedCandidate(),
                resolution.candidates(),
                resolution.gate(),
                resolution.effectivePrompt(),
                resolution.assistantMessage(),
                resolution.assistantContent(),
                resolution.apiCatalogAnswer(),
                resolution.quickReplies(),
                resolution.pendingClarification(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.failureCodes(),
                resolution.currentPageSummary(),
                resolution.llmDiagnostics(),
                resolution.visualizationDecision(),
                reconciledDecision);
    }

    AgenticAuthoringIntentResolutionResult preserveLiveOptionRefinementLineage(
            AgenticAuthoringIntentResolutionResult established,
            AgenticAuthoringIntentResolutionResult refinement) {
        if (established == null
                || refinement == null
                || !refinement.valid()
                || established.semanticDecision() == null
                || refinement.semanticDecision() == null
                || refinement.semanticDecision().constraints() == null) {
            return refinement;
        }
        AgenticAuthoringSemanticDecision reconciledDecision = established.semanticDecision()
                .withConstraints(refinement.semanticDecision().constraints().deepCopy());
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                established.warnings() == null ? List.of() : established.warnings());
        if (refinement.warnings() != null) {
            warnings.addAll(refinement.warnings());
        }
        warnings.add("live-option-refinement-scoped-to-constraints");
        return new AgenticAuthoringIntentResolutionResult(
                established.valid(),
                established.operationKind(),
                established.artifactKind(),
                established.changeKind(),
                established.authoringProfile(),
                established.targetApp(),
                established.targetComponentId(),
                established.target(),
                established.selectedCandidate(),
                established.candidates(),
                established.gate(),
                established.effectivePrompt(),
                established.assistantMessage(),
                established.assistantContent(),
                established.apiCatalogAnswer(),
                refinement.quickReplies(),
                refinement.pendingClarification(),
                refinement.clarificationQuestions(),
                List.copyOf(warnings),
                refinement.failureCodes(),
                established.currentPageSummary(),
                refinement.llmDiagnostics(),
                established.visualizationDecision(),
                reconciledDecision);
    }

    private List<String> selectedLiveOptionCandidateLabels(
            JsonNode selectedIds,
            LiveOptionValueRetrievalResult grounding) {
        if (selectedIds == null
                || !selectedIds.isArray()
                || selectedIds.isEmpty()
                || grounding == null
                || grounding.candidates() == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (JsonNode selectedId : selectedIds) {
            LiveOptionValueCandidate selectedCandidate = grounding.candidates().stream()
                    .filter(candidate -> candidate.id() != null && candidate.id().equals(selectedId))
                    .findFirst()
                    .orElse(null);
            if (selectedCandidate == null || !StringUtils.hasText(selectedCandidate.label())) {
                return List.of();
            }
            labels.add(normalizeGroundingText(selectedCandidate.label()));
        }
        return labels;
    }

    private boolean isTextConstraintCoveredBySelectedLiveOptionLabels(
            JsonNode value,
            List<String> selectedCandidateLabels) {
        List<String> semanticValues = new ArrayList<>();
        if (value.isTextual()) {
            semanticValues.add(value.asText(""));
        } else if (value.isArray()) {
            value.forEach(item -> semanticValues.add(item.asText("")));
        }
        return !semanticValues.isEmpty() && semanticValues.stream().allMatch(semanticValue -> {
            String normalizedValue = normalizeGroundingText(semanticValue);
            return StringUtils.hasText(normalizedValue)
                    && selectedCandidateLabels.stream().anyMatch(label ->
                            (" " + label + " ").contains(" " + normalizedValue + " "));
        });
    }

    private String normalizeGroundingText(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    boolean hasPreservedLiveOptionPredicate(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode fieldGrounding) {
        if (resolution == null
                || !resolution.valid()
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null
                || fieldGrounding == null) {
            return false;
        }
        JsonNode original = fieldGrounding.path("originalPredicate");
        String originalField = original.path("field").asText("");
        Set<String> canonicalFields = new LinkedHashSet<>();
        fieldGrounding.path("candidates").forEach(candidate -> {
            String field = candidate.path("canonicalFilterField").asText("");
            if (StringUtils.hasText(field)) {
                canonicalFields.add(field);
            }
        });
        JsonNode filters = resolution.semanticDecision().constraints().path("filters");
        if (!filters.isArray()) {
            return false;
        }
        for (JsonNode filter : filters) {
            String field = filter.path("field").asText("");
            if ((field.equals(originalField) || canonicalFields.contains(field))
                    && isSemanticTextValue(filter.path("value"))) {
                return true;
            }
        }
        return false;
    }

    boolean hasSchemaConfirmedCanonicalLiveOptionField(
            AgenticAuthoringIntentResolutionResult resolution,
            JsonNode fieldGrounding) {
        if (resolution == null
                || !resolution.valid()
                || resolution.semanticDecision() == null
                || resolution.semanticDecision().constraints() == null
                || fieldGrounding == null
                || !fieldGrounding.path("candidates").isArray()) {
            return false;
        }
        JsonNode predicate = firstSemanticTextConstraint(resolution.semanticDecision().constraints());
        if (predicate == null) {
            return false;
        }
        String authoredField = predicate.path("field").asText("");
        long exactCanonicalMatches = 0;
        for (JsonNode candidate : fieldGrounding.path("candidates")) {
            if (authoredField.equals(candidate.path("canonicalFilterField").asText(""))) {
                exactCanonicalMatches++;
            }
        }
        return exactCanonicalMatches == 1;
    }

    boolean isCanonicalLiveOptionConstraint(JsonNode predicate, JsonNode fieldGrounding) {
        if (predicate == null
                || fieldGrounding == null
                || !fieldGrounding.path("candidates").isArray()) {
            return false;
        }
        String authoredField = predicate.path("field").asText("");
        if (!StringUtils.hasText(authoredField)) {
            return false;
        }
        for (JsonNode candidate : fieldGrounding.path("candidates")) {
            if (authoredField.equals(candidate.path("canonicalFilterField").asText(""))) {
                return true;
            }
        }
        return false;
    }

    boolean hasConfirmedLiveOptionSelection(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding,
            LiveOptionValueRetrievalResult confirmation) {
        JsonNode selection = selectedLiveOptionConstraint(resolution, grounding);
        if (selection == null
                || confirmation == null
                || !confirmation.valid()
                || !"selected_ids_reload".equals(confirmation.retrievalMode())
                || !grounding.canonicalFilterField().equals(confirmation.canonicalFilterField())
                || confirmation.candidates().size() != selection.path("value").size()) {
            return false;
        }
        if (StringUtils.hasText(grounding.datasetVersion())
                && !grounding.datasetVersion().equals(confirmation.datasetVersion())) {
            return false;
        }
        for (int index = 0; index < selection.path("value").size(); index++) {
            JsonNode selectedId = selection.path("value").get(index);
            JsonNode confirmedId = confirmation.candidates().get(index).id();
            if (selectedId == null || confirmedId == null || !selectedId.equals(confirmedId)) {
                return false;
            }
            for (int duplicateIndex = index + 1;
                    duplicateIndex < selection.path("value").size();
                    duplicateIndex++) {
                if (selectedId.equals(selection.path("value").get(duplicateIndex))) {
                    return false;
                }
            }
        }
        return true;
    }

    private JsonNode selectedLiveOptionConstraint(
            AgenticAuthoringIntentResolutionResult resolution,
            LiveOptionValueRetrievalResult grounding) {
        if (resolution == null
                || resolution.semanticDecision() == null
                || grounding == null
                || resolution.semanticDecision().constraints() == null) {
            return null;
        }
        JsonNode filters = resolution.semanticDecision().constraints().path("filters");
        if (!filters.isArray()) {
            return null;
        }
        for (JsonNode filter : filters) {
            if (grounding.canonicalFilterField().equals(filter.path("field").asText(""))
                    && "in".equals(filter.path("operator").asText(""))
                    && filter.path("value").isArray()
                    && !filter.path("value").isEmpty()) {
                return filter;
            }
        }
        return null;
    }

    boolean requiresLiveOptionClarification(AgenticAuthoringIntentResolutionResult resolution) {
        return resolution != null
                && (resolution.pendingClarification() != null
                        || resolution.clarificationQuestions() != null
                                && !resolution.clarificationQuestions().isEmpty());
    }

    private static AgenticAuthoringTurnStreamRequest copyWithContextHints(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode contextHints) {
        return new AgenticAuthoringTurnStreamRequest(
                request.userPrompt(),
                request.targetApp(),
                request.targetComponentId(),
                request.currentRoute(),
                request.currentPage(),
                request.selectedWidgetKey(),
                request.provider(),
                request.model(),
                request.apiKey(),
                request.sessionId(),
                request.clientTurnId(),
                request.conversationMessages(),
                request.pendingClarification(),
                request.attachmentSummaries(),
                contextHints,
                request.componentCapabilities(),
                request.activeSemanticDecision(),
                request.diagnostics(),
                request.runtimeComponentObservations(),
                request.runtimeComponentObservationTrustBoundary());
    }

    private static AgenticAuthoringToolProgressProjection safeToolProjection(
            String phase,
            String label,
            Map<String, Object> diagnostics) {
        return new AgenticAuthoringToolProgressProjection(phase, label, diagnostics);
    }

    private static Map<String, Object> safeToolDiagnostics(AgenticAuthoringToolResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("tool", result.tool());
        diagnostics.put("valid", result.valid());
        if (result.errorCode() != null && !result.errorCode().isBlank()) {
            diagnostics.put("errorCode", result.errorCode());
        }
        if (result.safeDiagnostics() != null) {
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "candidateCount");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "evidenceCount");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "artifactKind");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "componentId");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "chunkKind");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "releaseId");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "retrievalQuery");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "retrievalSource");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "resourceDiscoveryDiagnostics");
            copySafeDiagnostic(result.safeDiagnostics(), diagnostics, "sourceRefs");
        }
        return diagnostics;
    }

    private static void copySafeDiagnostic(
            Map<String, Object> source,
            Map<String, Object> target,
            String field) {
        if (source.containsKey(field)) {
            target.put(field, source.get(field));
        }
    }

    private static String safeText(String value) {
        return value != null ? value : "";
    }
}
