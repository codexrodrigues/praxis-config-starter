package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTurnAdvisorySupport.AgenticAuthoringTurnRoute;
import org.springframework.util.StringUtils;

/**
 * Package-private collaborator that centralizes turn-stream event payload construction, runtime
 * evidence step emission, intent-resolved event projection, and sanitized diagnostics projection
 * for {@link AgenticAuthoringTurnEngine}.
 */
final class AgenticAuthoringTurnEventDiagnosticsSupport {

    void emitStatus(
            AgenticAuthoringTurnEventSink eventSink,
            String phase,
            String message) {
        if (eventSink == null || eventSink.terminalReached()) {
            return;
        }
        eventSink.append("status", Map.of(
                "state", "in_progress",
                "phase", safeText(phase),
                "message", presentationText(message),
                "summary", presentationText(message)));
    }

    Map<String, Object> thoughtStepPayload(
            String phase,
            String message,
            String summary) {
        return thoughtStepPayload(phase, message, summary, null);
    }

    Map<String, Object> thoughtStepPayload(
            String phase,
            String message,
            String summary,
            Object diagnostics) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", safeText(phase));
        payload.put("message", presentationText(message));
        payload.put("summary", presentationText(summary));
        if (diagnostics != null) {
            payload.put("diagnostics", diagnostics);
        }
        return payload;
    }

    Map<String, Object> streamEventPayload(
            String phase,
            String summary,
            Object diagnostics,
            String dedupeKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("message", summary);
        payload.put("summary", summary);
        payload.put("diagnostics", diagnostics);
        payload.put("streamEventDiagnostics", streamEventDiagnostics(dedupeKey, false));
        return payload;
    }

    Map<String, Object> streamEventDiagnostics(String dedupeKey, boolean technicalDuplicate) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "praxis-authoring-stream-event-diagnostics.v1");
        diagnostics.put("dedupeKey", safeText(dedupeKey));
        diagnostics.put("eventUniquenessKey", safeText(dedupeKey));
        diagnostics.put("technicalDuplicate", technicalDuplicate);
        diagnostics.put("technicalDuplicateOf", "");
        diagnostics.put("replaySafe", true);
        diagnostics.put("duplicatesDoNotIndicateExecution", true);
        return diagnostics;
    }

    void emitRuntimeComponentGroundingStep(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringTurnEventSink eventSink) {
        if (eventSink == null || eventSink.terminalReached()) {
            return;
        }
        JsonNode context = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("groundedRuntimeComponentContext");
        if (context == null || !context.isObject()) {
            return;
        }
        eventSink.append("thought.step", thoughtStepPayload(
                "runtime.context.grounding",
                "Estou aterrando o contexto dos componentes atuais como evidencia nao autoritativa.",
                "Grounded runtime component observations as untrusted evidence.",
                safeRuntimeGroundingDiagnostics(context)));
    }

    Map<String, Object> safeRuntimeGroundingDiagnostics(JsonNode context) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        JsonNode contextDiagnostics = context == null ? null : context.path("diagnostics");
        diagnostics.put("canonicalContext", context == null ? "" : safeText(context.path("canonicalContext").asText("")));
        diagnostics.put("trustLevel", context == null ? "" : safeText(context.path("trustLevel").asText("")));
        diagnostics.put("acceptedComponentCount", contextDiagnostics == null
                ? 0
                : contextDiagnostics.path("acceptedComponentCount").asInt(0));
        diagnostics.put("acceptedClaimCount", contextDiagnostics == null
                ? 0
                : contextDiagnostics.path("acceptedClaimCount").asInt(0));
        diagnostics.put("rejectedClaimCount", contextDiagnostics == null
                ? 0
                : contextDiagnostics.path("rejectedClaimCount").asInt(0));
        diagnostics.put("availableSurfaces", safeTextValues(context == null ? null : context.path("availableSurfaces"), 12));
        diagnostics.put("allowedOperations", safeTextValues(context == null ? null : context.path("allowedOperations"), 16));
        diagnostics.put("acceptedClaims", safeRuntimeClaims(context == null ? null : context.path("acceptedClaims"), 24));
        diagnostics.put("rejectedClaims", safeRejectedRuntimeClaims(context == null ? null : context.path("rejectedClaims"), 12));
        diagnostics.put("evidenceRefs", safeRuntimeEvidenceRefs(context == null ? null : context.path("evidenceRefs"), 12));
        diagnostics.put("rawRuntimeValuesCopied", false);
        return diagnostics;
    }

    void emitRuntimeRelatedSurfaceEvidenceSteps(
            AgenticAuthoringConsultativeAnswer answer,
            AgenticAuthoringTurnEventSink eventSink) {
        if (answer == null || answer.evidenceBundle() == null || eventSink == null || eventSink.terminalReached()) {
            return;
        }
        JsonNode resolution = answer.evidenceBundle().path("runtimeRelatedSurfaceResolution");
        if (!resolution.isObject()) {
            return;
        }
        String semanticDecisionRef = safeText(resolution.path("semanticDecisionRef").asText(""));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.related-surface.intent",
                "Runtime related surface read authorized by consultative semantic decision.",
                Map.of("semanticDecisionRef", semanticDecisionRef),
                "runtime.related-surface.intent:" + semanticDecisionRef));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.related-surface.candidates",
                "Runtime related surface candidates ranked with accepted and rejected claims.",
                resolution,
                "runtime.related-surface.candidates:" + semanticDecisionRef));
        JsonNode reads = answer.evidenceBundle().path("runtimeRelatedSurfaceReads");
        eventSink.append("thought.step", streamEventPayload(
                "runtime.related-surface.read",
                reads.isArray() && !reads.isEmpty()
                        ? "Runtime related surface read completed through read-only backend tool."
                        : "Runtime related surface read was blocked or returned no governed records.",
                Map.of(
                        "readCount", reads.isArray() ? reads.size() : 0,
                        "selectedCandidateRef", safeText(resolution.path("selectedCandidateRef").asText(""))),
                "runtime.related-surface.read:" + semanticDecisionRef + ":"
                        + (reads.isArray() ? reads.size() : 0)));
        emitRuntimeToolPlanEvidenceSteps(answer.evidenceBundle(), eventSink);
    }

    void emitRuntimeToolPlanEvidenceSteps(JsonNode evidenceBundle, AgenticAuthoringTurnEventSink eventSink) {
        if (evidenceBundle == null || eventSink == null || eventSink.terminalReached()) {
            return;
        }
        JsonNode plan = evidenceBundle.path("runtimeToolPlan");
        if (!plan.isObject()) {
            return;
        }
        String semanticDecisionRef = safeText(plan.path("semanticDecisionRef").asText(""));
        String intentKind = safeText(plan.path("intentKind").asText(""));
        String policyRef = safeText(plan.path("multiToolAuthorization").path("policyRef").asText(""));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.tool-plan.intent",
                "Runtime tool plan authorized by consultative semantic decision.",
                Map.of(
                        "semanticDecisionRef", safeText(plan.path("semanticDecisionRef").asText("")),
                        "intentKind", safeText(plan.path("intentKind").asText("")),
                        "readMode", safeText(plan.path("readMode").asText(""))),
                "runtime.tool-plan.intent:" + semanticDecisionRef));
        JsonNode resolution = evidenceBundle.path("runtimeRelatedSurfaceResolution");
        if (resolution.isObject()) {
            eventSink.append("thought.step", streamEventPayload(
                    "runtime.tool-plan.candidates",
                    "Runtime tool plan candidates derived from governed related surface resolution.",
                    resolution,
                    "runtime.tool-plan.candidates:" + semanticDecisionRef));
        }
        eventSink.append("thought.step", streamEventPayload(
                "runtime.tool-plan.created",
                "Runtime tool plan created with explicit budget and governed steps.",
                plan,
                "runtime.tool-plan.created:" + semanticDecisionRef + ":" + intentKind + ":" + policyRef));
        JsonNode steps = plan.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                String stepRef = safeText(step.path("stepRef").asText(""));
                eventSink.append("thought.step", streamEventPayload(
                        "runtime.tool-plan.step",
                        "Runtime tool plan step status recorded.",
                        step,
                        "runtime.tool-plan.step:" + semanticDecisionRef + ":" + firstNonBlank(stepRef, "unknown")));
            }
        }
        JsonNode reads = evidenceBundle.path("runtimeRelatedSurfaceReads");
        JsonNode executionDiagnostics = plan.path("executionDiagnostics");
        Map<String, Object> aggregateDiagnostics = new LinkedHashMap<>();
        aggregateDiagnostics.put("policyRef", safeText(plan.path("multiToolAuthorization").path("policyRef").asText("")));
        aggregateDiagnostics.put("dryRun", executionDiagnostics.path("dryRun").asBoolean(false));
        aggregateDiagnostics.put("multiToolExecutionEnabled", executionDiagnostics.path("multiToolExecutionEnabled").asBoolean(
                plan.path("planner").path("multiToolExecutionEnabled").asBoolean(false)));
        aggregateDiagnostics.put("authorizedCandidateCount", executionDiagnostics.path("authorizedCandidateCount").asInt(
                plan.path("candidateSteps").isArray() ? plan.path("candidateSteps").size() : 0));
        aggregateDiagnostics.put("candidateStepCount", plan.path("candidateSteps").isArray() ? plan.path("candidateSteps").size() : 0);
        aggregateDiagnostics.put("blockedStepCount", plan.path("blockedSteps").isArray() ? plan.path("blockedSteps").size() : 0);
        aggregateDiagnostics.put("maxPlannedSteps", executionDiagnostics.path("maxPlannedSteps").asInt(0));
        aggregateDiagnostics.put("maxExecutableSteps", executionDiagnostics.path("maxExecutableSteps").asInt(
                plan.path("steps").isArray() ? plan.path("steps").size() : 0));
        aggregateDiagnostics.put("nonExecutionReason", safeText(executionDiagnostics.path("nonExecutionReason").asText("")));
        aggregateDiagnostics.put("backendReadsPerformed", reads.isArray() && !reads.isEmpty());
        aggregateDiagnostics.put("readCount", reads.isArray() ? reads.size() : 0);
        aggregateDiagnostics.put("usedToolCalls", plan.path("budget").path("usedToolCalls").asInt(0));
        aggregateDiagnostics.put("maxToolCalls", plan.path("budget").path("maxToolCalls").asInt(0));
        aggregateDiagnostics.put("readMode", safeText(plan.path("readMode").asText("")));
        eventSink.append("thought.step", streamEventPayload(
                "runtime.tool-plan.aggregate",
                "Runtime tool plan aggregate recorded.",
                aggregateDiagnostics,
                "runtime.tool-plan.aggregate:" + semanticDecisionRef + ":" + intentKind + ":"
                        + safeText(executionDiagnostics.path("aggregateStatus").asText("")) + ":"
                        + aggregateDiagnostics.get("usedToolCalls") + ":"
                        + aggregateDiagnostics.get("readCount")));
    }

    void emitPreIntentToolPlanSkipped(
            AgenticAuthoringTurnEventSink eventSink,
            String reason,
            String errorCode) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("skipReason", safeText(reason));
        if (StringUtils.hasText(errorCode)) {
            diagnostics.put("errorCode", safeText(errorCode));
        }
        eventSink.append("thought.step", safeToolProjection(
                "tool.plan.skipped",
                "O planejamento de ferramenta pre-intent foi ignorado com motivo diagnosticado.",
                diagnostics));
    }

    void emitIntentResolutionProgress(
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return;
        }
        if (hasToolDiscoveredCandidates(intentResolution)) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "resource.discovery",
                    "Encontrei candidatos governados no catalogo do backend.",
                    "Resource candidates were retrieved from the backend catalog.",
                    resourceDiscoveryDiagnostics(intentResolution)));
        } else if (contains(intentResolution.failureCodes(), "resource-candidate-ambiguous")) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "resource.discovery",
                    "Encontrei mais de uma fonte governada possivel e vou manter a escolha para revisao.",
                    "Resource candidates returned for user selection.",
                    resourceDiscoveryDiagnostics(intentResolution)));
        }
        if (contains(intentResolution.warnings(), "llm-intent-resolution-second-pass-used")) {
            eventSink.append("thought.step", thoughtStepPayload(
                    "intent.resolve.llm",
                    "A LLM revisou os candidatos recuperados pelo backend.",
                    "The LLM reviewed refined backend resource candidates.",
                    secondPassDiagnostics(intentResolution)));
        }
    }

    void emitIntentResolved(
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringTurnStreamRequest request) {
        if (eventSink == null || eventSink.terminalReached() || intentResolution == null) {
            return;
        }
        AgenticAuthoringSemanticDecision semanticDecision = intentResolution.semanticDecision();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "praxis-agentic-authoring-intent-resolved-event.v1");
        payload.put("semanticDecisionRef", semanticDecision == null ? "" : safeText(semanticDecision.decisionId()));
        payload.put("routeClass", route == null ? "" : safeText(route.routeClass()));
        payload.put("resolved", intentResolution.valid());
        payload.put("userFacingUnderstanding", intentResolvedUserFacingUnderstanding(intentResolution, route, request));
        payload.put("requiresClarification", route != null && "needs_clarification".equals(route.routeClass()));
        payload.put("canMaterialize", route != null && route.allowsPreview() && intentResolution.valid());
        payload.put("fallbackKind", intentResolvedFallbackKind(intentResolution));
        payload.put("requiredTools", intentResolvedRequiredTools(intentResolution, route));
        payload.put("evidenceRefs", intentResolvedEvidenceRefs(intentResolution));
        payload.put("confidence", semanticDecision == null || semanticDecision.confidence() == null
                ? 0.0d
                : semanticDecision.confidence());
        payload.put("warnings", intentResolution.warnings() == null ? List.of() : intentResolution.warnings());
        eventSink.append("intent.resolved", payload);
    }

    String intentResolvedUserFacingUnderstanding(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route,
            AgenticAuthoringTurnStreamRequest request) {
        String assistantMessage = safeText(intentResolution.assistantMessage());
        if (!assistantMessage.isBlank()) {
            return curatedResourceLabel(
                    publicAssistantMessage(assistantMessage, request),
                    intentResolution.selectedCandidate());
        }
        String routeClass = route == null ? "" : safeText(route.routeClass());
        String operation = safeText(intentResolution.operationKind());
        String artifact = safeText(intentResolution.artifactKind());
        String change = safeText(intentResolution.changeKind());
        if ("needs_clarification".equals(routeClass)) {
            return "Entendi que preciso confirmar alguns detalhes antes de criar ou alterar algo.";
        }
        if ("advisory_authoring".equals(routeClass)) {
            return "Entendi que voce quer uma resposta consultiva antes de materializar uma tela.";
        }
        if ("shared_rule_authoring".equals(routeClass) || "mixed".equals(routeClass)) {
            return "Entendi que o pedido envolve regra compartilhada e precisa seguir a governanca apropriada.";
        }
        return "Entendi a intencao como " + nonBlank(operation, "authoring")
                + " de " + nonBlank(artifact, "componente")
                + (change.isBlank() ? "." : " para " + change + ".");
    }

    String curatedResourceLabel(String message, AgenticAuthoringCandidate candidate) {
        String value = safeText(message);
        if (value.isBlank() || candidate == null) {
            return value;
        }
        String fallbackLabel = AgenticAuthoringResourcePresentationLabel.fromResourcePath(candidate.resourcePath());
        String governedLabel = AgenticAuthoringResourcePresentationLabel.fromCandidate(candidate);
        if (fallbackLabel.isBlank()
                || governedLabel.isBlank()
                || fallbackLabel.equals(governedLabel)
                || "o recurso selecionado".equals(fallbackLabel)) {
            return value;
        }
        return Pattern.compile(Pattern.quote(fallbackLabel), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(value)
                .replaceAll(Matcher.quoteReplacement(governedLabel));
    }

    String intentResolvedFallbackKind(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return "";
        }
        JsonNode telemetry = intentResolution.llmDiagnostics() == null
                ? null
                : intentResolution.llmDiagnostics().path("resolutionTelemetry");
        String fallbackPolicy = telemetry == null ? "" : safeText(telemetry.path("fallbackPolicy").asText(""));
        if (!fallbackPolicy.isBlank()) {
            return fallbackPolicy;
        }
        if (contains(intentResolution.warnings(), "llm-provider-error")) {
            return "provider_error";
        }
        if (contains(intentResolution.warnings(), "keyword-fallback-applied")
                || contains(intentResolution.warnings(), "keyword-fallback-fail-safe-applied")) {
            return "deterministic_fallback";
        }
        return "";
    }

    List<String> intentResolvedRequiredTools(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        if (route == null) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        if (needsResourceDiscovery(intentResolution)) {
            tools.add(AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
        }
        if (route.allowsPreview()) {
            tools.add("preview.materialization");
        }
        if ("advisory_authoring".equals(route.routeClass())) {
            tools.add("consultative.answer");
        }
        return List.copyOf(new LinkedHashSet<>(tools));
    }

    List<Map<String, Object>> intentResolvedEvidenceRefs(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        List<Map<String, Object>> refs = new ArrayList<>();
        AgenticAuthoringCandidate selectedCandidate =
                intentResolution == null ? null : intentResolution.selectedCandidate();
        if (selectedCandidate != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("kind", "selectedResource");
            ref.put("resourcePath", safeText(selectedCandidate.resourcePath()));
            ref.put("operation", safeText(selectedCandidate.operation()));
            ref.put("score", selectedCandidate.score());
            ref.put("evidence", selectedCandidate.evidence() == null ? List.of() : selectedCandidate.evidence());
            refs.add(ref);
        }
        AgenticAuthoringSemanticDecision semanticDecision =
                intentResolution == null ? null : intentResolution.semanticDecision();
        if (semanticDecision != null && semanticDecision.retrievedEvidence() != null) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("kind", "retrievedEvidence");
            ref.put("retrievalSource", safeText(semanticDecision.retrievedEvidence().retrievalSource()));
            ref.put("evidenceCount", semanticDecision.retrievedEvidence().evidence().size());
            refs.add(ref);
        }
        return refs;
    }

    Map<String, Object> resourceDiscoveryDiagnostics(AgenticAuthoringIntentResolutionResult intentResolution) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidateCount", intentResolution.candidates() != null ? intentResolution.candidates().size() : 0);
        diagnostics.put("operationKind", safeText(intentResolution.operationKind()));
        diagnostics.put("artifactKind", safeText(intentResolution.artifactKind()));
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && selectedCandidate.resourcePath() != null && !selectedCandidate.resourcePath().isBlank()) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(
                selectedCandidate,
                intentResolution.candidates()));
        diagnostics.put("source", hasToolDiscoveredCandidates(intentResolution) ? "backend-resource-catalog" : "intent-resolution");
        return diagnostics;
    }

    Map<String, Object> secondPassDiagnostics(AgenticAuthoringIntentResolutionResult intentResolution) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("secondPass", true);
        diagnostics.put("candidateCount", intentResolution.candidates() != null ? intentResolution.candidates().size() : 0);
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && selectedCandidate.resourcePath() != null && !selectedCandidate.resourcePath().isBlank()) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(
                selectedCandidate,
                intentResolution.candidates()));
        return diagnostics;
    }

    Map<String, Object> safePreviewDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            boolean repairAttempted) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("valid", preview != null && preview.valid());
        diagnostics.put("repairClassification", AgenticAuthoringRepairClassificationPolicy.classify(
                intentResolution,
                preview));
        diagnostics.put("repairAttempted", repairAttempted);
        if (preview != null && preview.failureCodes() != null && !preview.failureCodes().isEmpty()) {
            diagnostics.put("failureCodeCount", preview.failureCodes().size());
        }
        if (preview != null && preview.warnings() != null && !preview.warnings().isEmpty()) {
            diagnostics.put("warningCount", preview.warnings().size());
        }
        return diagnostics;
    }

    AgenticAuthoringToolProgressProjection safeToolProjection(
            String phase,
            String label,
            Map<String, Object> diagnostics) {
        return new AgenticAuthoringToolProgressProjection(phase, label, diagnostics);
    }

    Map<String, Object> safeToolDiagnostics(AgenticAuthoringToolResult result) {
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

    Map<String, Object> safeToolLoopDiagnostics(AgenticAuthoringToolLoopResult result) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("completed", result != null && result.completed());
        diagnostics.put("terminalReason", result == null ? "" : safeText(result.terminalReason()));
        diagnostics.put("stepCount", result == null || result.trace() == null ? 0 : result.trace().size());
        diagnostics.put("toolCallCount", result == null || result.trace() == null
                ? 0
                : result.trace().stream().filter(step -> step != null && StringUtils.hasText(step.tool())).count());
        return diagnostics;
    }

    List<Map<String, Object>> safeToolLoopTrace(AgenticAuthoringToolLoopResult result) {
        if (result == null || result.trace() == null) {
            return List.of();
        }
        return result.trace().stream()
                .map(step -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stepIndex", step.stepIndex());
                    item.put("phase", safeText(step.phase()));
                    item.put("tool", safeText(step.tool()));
                    item.put("valid", step.valid());
                    if (StringUtils.hasText(step.errorCode())) {
                        item.put("errorCode", step.errorCode());
                    }
                    item.put("diagnostics", step.safeDiagnostics() == null ? Map.of() : step.safeDiagnostics());
                    return item;
                })
                .toList();
    }

    Map<String, Object> intentGroundingDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringTurnRoute route) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("routeClass", route == null ? "" : safeText(route.routeClass()));
        diagnostics.put("operationKind", intentResolution == null ? "" : safeText(intentResolution.operationKind()));
        diagnostics.put("artifactKind", intentResolution == null ? "" : safeText(intentResolution.artifactKind()));
        diagnostics.put("changeKind", intentResolution == null ? "" : safeText(intentResolution.changeKind()));
        diagnostics.put("candidateCount", intentResolution == null || intentResolution.candidates() == null
                ? 0
                : intentResolution.candidates().size());
        AgenticAuthoringCandidate selectedCandidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        diagnostics.put("selectedResourcePath", selectedCandidate == null ? "" : safeText(selectedCandidate.resourcePath()));
        AgenticAuthoringEvidenceBundle evidenceBundle = selectedCandidate == null ? null : selectedCandidate.evidenceBundle();
        diagnostics.put("retrievalSource", evidenceBundle == null ? "" : safeText(evidenceBundle.retrievalSource()));
        diagnostics.put("grounded", selectedCandidate != null
                && selectedCandidate.evidence() != null
                && selectedCandidate.evidence().stream()
                .anyMatch(evidence -> "domain-catalog-grounding".equals(evidence)
                        || "semantic-retrieval".equals(evidence)
                        || "schema-grounding".equals(evidence)
                        || "tool-search-api-resources".equals(evidence)));
        return diagnostics;
    }

    Map<String, Object> safeDiagnostics(AgenticAuthoringTurnStreamRequest request) {
        return Map.of(
                "targetApp", nonBlank(request.targetApp(), ""),
                "targetComponentId", nonBlank(request.targetComponentId(), ""),
                "selectedWidgetKey", nonBlank(request.selectedWidgetKey(), ""),
                "hasContextHints", request.contextHints() != null && !request.contextHints().isNull(),
                "runtimeComponentObservationCount", request.runtimeComponentObservations() == null
                        ? 0
                        : request.runtimeComponentObservations().size(),
                "hasGroundedRuntimeComponentContext", request.contextHints() != null
                        && request.contextHints().path("groundedRuntimeComponentContext").isObject(),
                "hasActiveSemanticDecision", request.activeSemanticDecision() != null,
                "componentCapabilityCatalogs", request.componentCapabilities() != null
                        && request.componentCapabilities().catalogs() != null
                        ? request.componentCapabilities().catalogs().size()
                        : 0);
    }

    boolean needsResourceDiscovery(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || hasToolDiscoveredCandidates(intentResolution)) {
            return false;
        }
        return contains(intentResolution.failureCodes(), "resource-candidate-required")
                || (intentResolution.gate() != null
                && contains(intentResolution.gate().messages(), "resource-candidate-required"));
    }

    boolean hasToolDiscoveredCandidates(AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null) {
            return false;
        }
        if (hasEvidence(intentResolution.selectedCandidate(), "tool-search-api-resources")) {
            return true;
        }
        return intentResolution.candidates() != null
                && intentResolution.candidates().stream()
                .anyMatch(candidate -> hasEvidence(candidate, "tool-search-api-resources"));
    }

    boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null && contains(candidate.evidence(), evidence);
    }

    private void copySafeDiagnostic(
            Map<String, Object> source,
            Map<String, Object> target,
            String field) {
        if (source.containsKey(field)) {
            target.put(field, source.get(field));
        }
    }

    private List<String> safeTextValues(JsonNode source, int limit) {
        List<String> values = new ArrayList<>();
        if (source == null || !source.isArray()) {
            return values;
        }
        for (JsonNode item : source) {
            if (values.size() >= limit) {
                break;
            }
            String text = item.asText("");
            if (StringUtils.hasText(text)) {
                values.add(text);
            }
        }
        return values;
    }

    private List<Map<String, Object>> safeRuntimeClaims(JsonNode claims, int limit) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (claims == null || !claims.isArray()) {
            return values;
        }
        for (JsonNode claim : claims) {
            if (values.size() >= limit || !claim.isObject()) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", safeText(claim.path("kind").asText("")));
            item.put("ref", safeText(claim.path("ref").asText("")));
            if (claim.path("observed").isBoolean()) {
                item.put("observed", claim.path("observed").asBoolean());
            }
            values.add(item);
        }
        return values;
    }

    private List<Map<String, Object>> safeRejectedRuntimeClaims(JsonNode claims, int limit) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (claims == null || !claims.isArray()) {
            return values;
        }
        for (JsonNode claim : claims) {
            if (values.size() >= limit || !claim.isObject()) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("reason", safeText(claim.path("reason").asText("")));
            item.put("componentId", safeText(claim.path("componentId").asText("")));
            item.put("schemaVersion", safeText(claim.path("schemaVersion").asText("")));
            values.add(item);
        }
        return values;
    }

    private List<Map<String, Object>> safeRuntimeEvidenceRefs(JsonNode refs, int limit) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (refs == null || !refs.isArray()) {
            return values;
        }
        for (JsonNode ref : refs) {
            if (values.size() >= limit || !ref.isObject()) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", safeText(ref.path("source").asText("")));
            item.put("componentId", safeText(ref.path("componentId").asText("")));
            item.put("instanceId", safeText(ref.path("instanceId").asText("")));
            item.put("resourceKey", safeText(ref.path("resourceKey").asText("")));
            item.put("pageId", safeText(ref.path("pageId").asText("")));
            values.add(item);
        }
        return values;
    }

    private boolean contains(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(expected::equals);
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }

    private String publicAssistantMessage(String value, AgenticAuthoringTurnStreamRequest request) {
        String responseLocale = request == null || request.contextHints() == null
                ? ""
                : request.contextHints().path("responseLocale").asText("");
        return AgenticAuthoringPresentationText.assistantReply(safeText(value), responseLocale);
    }

    private String presentationText(String value) {
        return AgenticAuthoringPresentationText.display(safeText(value));
    }
}
