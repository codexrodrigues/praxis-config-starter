package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.springframework.util.StringUtils;

/**
 * Projects terminal quick replies, contextual component-capability suggestions, governed review
 * repair actions, and structured decision/telemetry diagnostics for {@link AgenticAuthoringTurnEngine}.
 */
final class AgenticAuthoringTurnDecisionProjectionSupport {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringTurnAdvisorySupport advisorySupport;

    AgenticAuthoringTurnDecisionProjectionSupport(
            ObjectMapper objectMapper,
            AgenticAuthoringTurnAdvisorySupport advisorySupport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.advisorySupport = Objects.requireNonNull(advisorySupport, "advisorySupport");
    }

    List<AgenticAuthoringQuickReply> terminalQuickReplies(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery) {
        if (advisorySupport.isAdvisoryCatalogIntent(intentResolution)
                || advisorySupport.isUnresolvedAdvisoryIntent(intentResolution)) {
            return List.of();
        }
        if (businessCatalogDiscovery != null
                && businessCatalogDiscovery.quickReplies() != null
                && !businessCatalogDiscovery.quickReplies().isEmpty()) {
            return businessCatalogDiscovery.quickReplies();
        }
        return intentResolution != null && intentResolution.quickReplies() != null
                ? intentResolution.quickReplies()
                : List.of();
    }

    List<AgenticAuthoringQuickReply> terminalQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringResourceCandidatesResult businessCatalogDiscovery,
            AgenticAuthoringPreviewResult preview,
            boolean canApply,
            Map<String, Object> decisionDiagnostics,
            AgenticAuthoringComponentCapabilitiesResult capabilities) {
        List<AgenticAuthoringQuickReply> replies = terminalQuickReplies(intentResolution, businessCatalogDiscovery);
        List<AgenticAuthoringQuickReply> contextual =
                contextualPreviewQuickReplies(request, intentResolution, preview, capabilities);
        boolean requiresGovernedRepair = !canApply
                && intentResolution != null
                && List.of("create", "modify", "remove", "compose", "connect", "undo")
                        .contains(safeText(intentResolution.operationKind()))
                && !advisorySupport.isAdvisoryCatalogIntent(intentResolution);
        if (contextual.isEmpty() && !requiresGovernedRepair) {
            return replies;
        }
        Map<String, AgenticAuthoringQuickReply> merged = new LinkedHashMap<>();
        replies.forEach(reply -> {
            if (reply != null && StringUtils.hasText(reply.id())) {
                merged.put(reply.id(), reply);
            }
        });
        contextual.forEach(reply -> {
            if (reply != null && StringUtils.hasText(reply.id())) {
                merged.putIfAbsent(reply.id(), reply);
            }
        });
        if (requiresGovernedRepair && !hasGovernedReviewRepair(merged.values())) {
            AgenticAuthoringQuickReply repairReply = governedReviewRepairQuickReply(
                    intentResolution,
                    decisionDiagnostics);
            merged.putIfAbsent(repairReply.id(), repairReply);
        }
        return List.copyOf(merged.values());
    }

    private boolean hasGovernedReviewRepair(Iterable<AgenticAuthoringQuickReply> replies) {
        if (replies == null) {
            return false;
        }
        for (AgenticAuthoringQuickReply reply : replies) {
            JsonNode hints = reply == null ? null : reply.contextHints();
            if (hints != null
                    && hints.isObject()
                    && "governed-review-repair".equals(hints.path("kind").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private AgenticAuthoringQuickReply governedReviewRepairQuickReply(
            AgenticAuthoringIntentResolutionResult intentResolution,
            Map<String, Object> decisionDiagnostics) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("source", "governed-review-gate");
        contextHints.put("kind", "governed-review-repair");
        contextHints.put("requiresReview", true);
        if (intentResolution != null) {
            contextHints.put("operationKind", safeText(intentResolution.operationKind()));
            contextHints.put("artifactKind", safeText(intentResolution.artifactKind()));
            contextHints.put("changeKind", safeText(intentResolution.changeKind()));
            AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
            String resourcePath = selectedCandidate == null
                    ? ""
                    : safeText(selectedCandidate.resourcePath());
            if (resourcePath.isBlank() && intentResolution.target() != null) {
                resourcePath = safeText(intentResolution.target().resourcePath());
            }
            if (!resourcePath.isBlank()) {
                contextHints.put("resourcePath", resourcePath);
            }
        }
        String reviewReason = decisionDiagnostics == null
                ? ""
                : safeText((String) decisionDiagnostics.get("reviewReason"));
        if (!reviewReason.isBlank()) {
            contextHints.put("reviewReason", reviewReason);
        }
        JsonNode semanticDecision = governedReviewRepairSemanticDecision(intentResolution);
        return new AgenticAuthoringQuickReply(
                "governed-review-revise",
                "revise",
                "Revisar pontos pendentes",
                "Revise a previa bloqueada, explique os pontos que ainda precisam de decisao e proponha um ajuste seguro antes de salvar.",
                "Continua a revisao usando a decisao e as evidencias governadas deste turno.",
                "rate_review",
                "primary",
                contextHints,
                semanticDecision,
                null);
    }

    private JsonNode governedReviewRepairSemanticDecision(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || intentResolution.semanticDecision() == null) {
            return null;
        }
        AgenticAuthoringSemanticDecision currentDecision = intentResolution.semanticDecision();
        ObjectNode constraints = currentDecision.constraints() != null
                        && currentDecision.constraints().isObject()
                ? currentDecision.constraints().deepCopy()
                : objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", "governed-review-revise");
        constraints.put("continuationOf", "governed_review");
        if (!constraints.path("conceptKeys").isArray()) {
            constraints.putArray("conceptKeys");
        }
        return objectMapper.valueToTree(currentDecision.withConstraints(constraints));
    }

    private List<AgenticAuthoringQuickReply> contextualPreviewQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringComponentCapabilitiesResult capabilities) {
        if (preview == null || !preview.valid() || advisorySupport.isAdvisoryCatalogIntent(intentResolution)) {
            return List.of();
        }
        if (capabilities == null || capabilities.catalogs() == null || capabilities.catalogs().isEmpty()) {
            return List.of();
        }
        JsonNode previewPage = previewMaterializedPage(preview);
        String chartWidgetKey = previewComponentWidgetKey(preview, "praxis-chart");
        String tableWidgetKey = previewComponentWidgetKey(preview, "praxis-table");
        JsonNode chartWidgetSnapshot = previewComponentWidget(previewPage, "praxis-chart", chartWidgetKey);
        JsonNode tableWidgetSnapshot = previewComponentWidget(previewPage, "praxis-table", tableWidgetKey);
        boolean hasChart = chartWidgetSnapshot != null;
        boolean hasTable = tableWidgetSnapshot != null;
        AgenticAuthoringCandidate selectedCandidate = intentResolution == null
                ? null
                : intentResolution.selectedCandidate();
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        if (hasChart && supportsCapability(capabilities, "praxis-chart", "set_chart_type")) {
            replies.add(contextualQuickReply(
                    "chart-change-line",
                    "Trocar para linhas",
                    "Altere o gráfico selecionado para linhas, mantendo a fonte de dados, a dimensão e a métrica atuais se esse tipo fizer sentido para os dados exibidos.",
                    "show_chart",
                    "set_chart_type",
                    "praxis-chart.type.set@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
            replies.add(contextualQuickReply(
                    "chart-change-donut",
                    "Ver como donut",
                    "Mostre o gráfico selecionado como donut somente se a dimensão atual representar uma composição categórica; caso não faça sentido, explique a alternativa visual mais adequada.",
                    "donut_large",
                    "set_chart_type",
                    "praxis-chart.type.set@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
        }
        if (hasChart && supportsCapability(capabilities, "praxis-chart", "enable_chart_drilldown")) {
            replies.add(contextualQuickReply(
                    "chart-add-detail-table",
                    "Detalhes em tabela",
                    "Acrescente um filtro e uma tabela de detalhes abaixo do gráfico, conectados à seleção do gráfico.",
                    "table_view",
                    "enable_chart_drilldown",
                    "praxis-chart.drilldown.enable@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
            ObjectNode surfaceHints = objectMapper.createObjectNode();
            surfaceHints.put("surfacePresentation", "modal");
            surfaceHints.put("surfaceActionId", "surface.open");
            surfaceHints.put("surfaceWidgetId", "praxis-table");
            replies.add(contextualQuickReply(
                    "chart-add-detail-modal",
                    "Detalhes em modal",
                    "Abra os registros da categoria selecionada do gráfico em um modal de detalhes.",
                    "open_in_new",
                    "enable_chart_drilldown",
                    "praxis-chart.drilldown.enable@0.1.0",
                    "praxis-chart",
                    chartWidgetKey,
                    selectedCandidate,
                    previewPage,
                    chartWidgetSnapshot,
                    surfaceHints,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
        }
        if (hasTable && supportsCapability(capabilities, "praxis-table", "configure_export")) {
            replies.add(contextualQuickReply(
                    "table-export-selected-rows",
                    "Exportar selecionadas",
                    "Habilite seleção na tabela e adicione uma ação para exportar apenas as linhas selecionadas.",
                    "download",
                    "configure_export",
                    "praxis-table.export.selected-rows@0.1.0",
                    "praxis-table",
                    tableWidgetKey,
                    selectedCandidate,
                    previewPage,
                    tableWidgetSnapshot,
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision()));
        }
        return replies.size() <= 5 ? replies : List.copyOf(replies.subList(0, 5));
    }

    private AgenticAuthoringQuickReply contextualQuickReply(
            String id,
            String label,
            String prompt,
            String icon,
            String changeKind,
            String capabilityId,
            String componentId,
            String widgetKey,
            AgenticAuthoringCandidate selectedCandidate,
            JsonNode previewPage,
            JsonNode targetWidgetSnapshot,
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeDecision) {
        return contextualQuickReply(
                id,
                label,
                prompt,
                icon,
                changeKind,
                capabilityId,
                componentId,
                widgetKey,
                selectedCandidate,
                previewPage,
                targetWidgetSnapshot,
                null,
                request,
                activeDecision);
    }

    private AgenticAuthoringQuickReply contextualQuickReply(
            String id,
            String label,
            String prompt,
            String icon,
            String changeKind,
            String capabilityId,
            String componentId,
            String widgetKey,
            AgenticAuthoringCandidate selectedCandidate,
            JsonNode previewPage,
            JsonNode targetWidgetSnapshot,
            JsonNode extraContextHints,
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeDecision) {
        ObjectNode hints = objectMapper.createObjectNode();
        hints.put("source", "component-capability-catalog");
        hints.put("kind", "contextual-preview-action");
        hints.put("operationKind", "modify");
        hints.put("artifactKind", contextualArtifactKind(componentId));
        hints.put("changeKind", changeKind);
        hints.put("capabilityId", capabilityId);
        if (StringUtils.hasText(componentId)) {
            hints.put("targetComponentId", componentId);
            hints.put("selectedComponentId", componentId);
        }
        if (StringUtils.hasText(widgetKey)) {
            hints.put("targetWidgetKey", widgetKey);
            hints.put("selectedWidgetKey", widgetKey);
        }
        if (selectedCandidate != null && StringUtils.hasText(selectedCandidate.resourcePath())) {
            hints.put("resourcePath", selectedCandidate.resourcePath());
            hints.put("submitUrl", firstNonBlank(selectedCandidate.submitUrl(), selectedCandidate.resourcePath()));
            hints.put("operation", firstNonBlank(selectedCandidate.operation(), selectedCandidate.submitMethod(), "get"));
            hints.put("submitMethod", firstNonBlank(selectedCandidate.submitMethod(), selectedCandidate.operation(), "get"));
            if (StringUtils.hasText(selectedCandidate.schemaUrl())) {
                hints.put("schemaUrl", selectedCandidate.schemaUrl());
            }
        }
        if (previewPage != null && previewPage.isObject()) {
            hints.set("previewPage", previewPage.deepCopy());
        }
        if (targetWidgetSnapshot != null && targetWidgetSnapshot.isObject()) {
            hints.set("targetWidgetSnapshot", targetWidgetSnapshot.deepCopy());
        }
        if (extraContextHints != null && extraContextHints.isObject()) {
            extraContextHints.fields().forEachRemaining(entry -> hints.set(entry.getKey(), entry.getValue().deepCopy()));
        }
        return new AgenticAuthoringQuickReply(
                id,
                "suggestion",
                label,
                prompt,
                "Ação sugerida a partir das capacidades confirmadas do componente.",
                icon,
                "suggestion",
                hints,
                contextualQuickReplySemanticDecision(
                        request,
                        activeDecision,
                        id,
                        prompt,
                        changeKind,
                        capabilityId,
                        componentId,
                        widgetKey,
                        selectedCandidate),
                null);
    }

    private JsonNode contextualQuickReplySemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision activeDecision,
            String quickReplyId,
            String prompt,
            String changeKind,
            String capabilityId,
            String componentId,
            String widgetKey,
            AgenticAuthoringCandidate selectedCandidate) {
        ObjectNode constraints = activeDecision != null
                        && activeDecision.constraints() != null
                        && activeDecision.constraints().isObject()
                ? activeDecision.constraints().deepCopy()
                : objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", quickReplyId);
        constraints.put("continuationOf", "contextual_preview_action");
        putText(constraints, "capabilityId", capabilityId);
        putText(constraints, "targetComponentId", componentId);
        putText(constraints, "targetWidgetKey", widgetKey);
        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                        "modify",
                        contextualArtifactKind(componentId),
                        changeKind,
                        selectedCandidate,
                        selectedCandidate == null ? List.of() : List.of(selectedCandidate),
                        activeDecision == null ? null : activeDecision.visualizationDecision(),
                        List.of(),
                        null,
                        null,
                        activeDecision,
                        request == null ? "" : request.sessionId(),
                        (request == null ? "" : request.clientTurnId()) + ":" + quickReplyId,
                        prompt,
                        prompt,
                        "The user may select this governed component capability after preview materialization.")
                .withConstraints(constraints);
        return objectMapper.valueToTree(decision);
    }

    private String contextualArtifactKind(String componentId) {
        if ("praxis-chart".equals(componentId)) {
            return "chart";
        }
        if ("praxis-table".equals(componentId)) {
            return "table";
        }
        if ("praxis-dynamic-form".equals(componentId)) {
            return "form";
        }
        return "page";
    }

    private JsonNode previewMaterializedPage(AgenticAuthoringPreviewResult preview) {
        if (preview == null) {
            return null;
        }
        JsonNode page = preview.compiledFormPatch().path("patch").path("page");
        if (page.isObject() && page.path("widgets").isArray() && !page.path("widgets").isEmpty()) {
            return page;
        }
        JsonNode plan = preview.uiCompositionPlan();
        if (plan != null && plan.isObject() && plan.path("widgets").isArray() && !plan.path("widgets").isEmpty()) {
            return plan;
        }
        return null;
    }

    JsonNode previewComponentWidget(JsonNode page, String componentId, String widgetKey) {
        if (page == null || !page.isObject() || !page.path("widgets").isArray() || !StringUtils.hasText(componentId)) {
            return null;
        }
        JsonNode fallback = null;
        for (JsonNode widget : page.path("widgets")) {
            if (!widget.isObject()) {
                continue;
            }
            String widgetComponentId = firstNonBlank(
                    widget.path("componentId").asText(""),
                    widget.path("definition").path("id").asText(""),
                    widget.path("id").asText(""));
            if (!componentId.equals(widgetComponentId)) {
                continue;
            }
            if (!StringUtils.hasText(widgetKey)) {
                return widget;
            }
            String key = firstNonBlank(
                    widget.path("key").asText(""),
                    widget.path("widgetKey").asText(""),
                    widget.path("id").asText(""));
            if (widgetKey.equals(key)) {
                return widget;
            }
            if (fallback == null) {
                fallback = widget;
            }
        }
        return fallback;
    }

    private boolean supportsCapability(
            AgenticAuthoringComponentCapabilitiesResult capabilities,
            String componentId,
            String changeKind) {
        return capabilities != null
                && capabilities.catalogs() != null
                && capabilities.catalogs().stream()
                .filter(catalog -> componentId.equals(catalog.componentId()))
                .flatMap(catalog -> catalog.capabilities() == null
                        ? java.util.stream.Stream.empty()
                        : catalog.capabilities().stream())
                .anyMatch(capability -> changeKind.equals(capability.changeKind()));
    }

    private String previewComponentWidgetKey(AgenticAuthoringPreviewResult preview, String componentId) {
        String fromPlan = componentWidgetKey(preview == null ? null : preview.uiCompositionPlan(), componentId);
        if (StringUtils.hasText(fromPlan)) {
            return fromPlan;
        }
        return componentWidgetKey(preview == null ? null : preview.compiledFormPatch(), componentId);
    }

    private String componentWidgetKey(JsonNode node, String componentId) {
        if (node == null || node.isMissingNode() || node.isNull() || !StringUtils.hasText(componentId)) {
            return "";
        }
        if (node.isObject()) {
            String nodeComponentId = firstNonBlank(
                    node.path("componentId").asText(""),
                    node.path("definition").path("id").asText(""),
                    node.path("id").asText(""));
            if (componentId.equals(nodeComponentId)) {
                return firstNonBlank(
                        node.path("key").asText(""),
                        node.path("widgetKey").asText(""),
                        node.path("id").asText(""));
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String key = componentWidgetKey(fields.next().getValue(), componentId);
                if (StringUtils.hasText(key)) {
                    return key;
                }
            }
            return "";
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String key = componentWidgetKey(child, componentId);
                if (StringUtils.hasText(key)) {
                    return key;
                }
            }
        }
        return "";
    }

    AgenticAuthoringIntentResolutionResult terminalIntentResolution(
            AgenticAuthoringIntentResolutionResult intentResolution,
            Map<String, Object> decisionDiagnostics) {
        if (intentResolution == null || intentResolution.semanticDecision() == null) {
            return intentResolution;
        }
        if (!Boolean.TRUE.equals(decisionDiagnostics.get("semanticDecisionReviewGroundedByPreview"))) {
            return intentResolution;
        }
        AgenticAuthoringSemanticDecision groundedDecision =
                intentResolution.semanticDecision().withReviewResolvedByPreviewGrounding();
        return new AgenticAuthoringIntentResolutionResult(
                intentResolution.valid(),
                intentResolution.operationKind(),
                intentResolution.artifactKind(),
                intentResolution.changeKind(),
                intentResolution.authoringProfile(),
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                intentResolution.target(),
                intentResolution.selectedCandidate(),
                intentResolution.candidates(),
                intentResolution.gate(),
                intentResolution.effectivePrompt(),
                intentResolution.assistantMessage(),
                intentResolution.apiCatalogAnswer(),
                intentResolution.quickReplies(),
                intentResolution.pendingClarification(),
                intentResolution.clarificationQuestions(),
                intentResolution.warnings(),
                intentResolution.failureCodes(),
                intentResolution.currentPageSummary(),
                intentResolution.llmDiagnostics(),
                intentResolution.visualizationDecision(),
                groundedDecision);
    }

    Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview) {
        return decisionDiagnostics(intentResolution, preview, null, null, List.of());
    }

    Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult) {
        return decisionDiagnostics(intentResolution, preview, toolLoopResult, null, List.of());
    }

    Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult,
            AgenticAuthoringTurnStreamRequest request) {
        return decisionDiagnostics(intentResolution, preview, toolLoopResult, request, List.of());
    }

    Map<String, Object> decisionDiagnostics(
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringPreviewResult preview,
            AgenticAuthoringToolLoopResult toolLoopResult,
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-decision-diagnostics.v1");
        if (intentResolution == null) {
            diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.NONE);
            diagnostics.put("llmResolutionAttempted", false);
            diagnostics.put("llmResolved", false);
            diagnostics.put("keywordFallbackApplied", false);
            diagnostics.put("semanticPolicyApplied", false);
            diagnostics.put("selectedCandidateUsesLexicalFallback", false);
            diagnostics.put("selectedCandidateUsesBroadArtifactDiscovery", false);
            diagnostics.put("selectedCandidateUsesDomainAnchor", false);
            diagnostics.put("uiCompositionPlanUsesReferenceProvider", uiCompositionPlanUsesReferenceProvider(preview));
            diagnostics.put("uiCompositionPlanUsesHardcodedAnchor", uiCompositionPlanUsesHardcodedAnchor(preview));
            diagnostics.put("uiCompositionPlanHasUnverifiedSemanticAxes", uiCompositionPlanHasUnverifiedSemanticAxes(preview));
            diagnostics.put("previewTechnicallyValid", preview != null && preview.valid());
            diagnostics.put("decisionValid", !previewHasSemanticMaterializationFailures(preview, false));
            putToolLoopDiagnostics(diagnostics, toolLoopResult);
            putAuthoringEvidenceDiagnostics(diagnostics, request);
            putSemanticAxisDiagnostics(diagnostics, preview);
            putProviderInvocationDiagnostics(diagnostics, request, providerInvocations);
            diagnostics.put("requiresReview", requiresDecisionReview(diagnostics));
            return diagnostics;
        }
        diagnostics.put("operationKind", safeText(intentResolution.operationKind()));
        diagnostics.put("artifactKind", safeText(intentResolution.artifactKind()));
        AgenticAuthoringSemanticDecision semanticDecision = intentResolution.semanticDecision();
        if (semanticDecision != null) {
            boolean semanticDecisionReviewGroundedByPreview =
                    semanticDecisionReviewGroundedByPreview(semanticDecision, preview);
            diagnostics.put("semanticDecisionSchemaVersion", safeText(semanticDecision.schemaVersion()));
            diagnostics.put("semanticDecisionId", safeText(semanticDecision.decisionId()));
            diagnostics.put("semanticDecisionReviewRequired",
                    semanticDecision.reviewRequired() && !semanticDecisionReviewGroundedByPreview);
            diagnostics.put("semanticDecisionReviewReason", safeText(semanticDecision.reviewReason()));
            diagnostics.put("semanticDecisionReviewGroundedByPreview", semanticDecisionReviewGroundedByPreview);
            diagnostics.put("semanticDecisionRefinementOf", safeText(semanticDecision.refinementOf()));
            diagnostics.put("semanticDecisionPreviousDecisionId", safeText(semanticDecision.previousDecisionId()));
            diagnostics.put("semanticDecisionVisualIntent", safeText(semanticDecision.visualIntent()));
        }
        diagnostics.put("valid", intentResolution.valid());
        diagnostics.put("retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(
                intentResolution.selectedCandidate(),
                intentResolution.candidates()));
        AgenticAuthoringCandidate selectedCandidate = intentResolution.selectedCandidate();
        if (selectedCandidate != null && StringUtils.hasText(selectedCandidate.resourcePath())) {
            diagnostics.put("selectedResourcePath", selectedCandidate.resourcePath());
        }
        JsonNode telemetry = intentResolution.llmDiagnostics() == null
                ? objectMapper.missingNode()
                : intentResolution.llmDiagnostics().path("resolutionTelemetry");
        diagnostics.put("llmResolutionAttempted", telemetry.path("llmResolutionAttempted").asBoolean(false));
        diagnostics.put("llmResolved", telemetry.path("llmResolved").asBoolean(false));
        diagnostics.put("fallbackPolicy", safeText(telemetry.path("fallbackPolicy").asText("")));
        boolean keywordFallbackApplied = telemetry.path("keywordFallbackApplied").asBoolean(false)
                && !Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"));
        diagnostics.put("keywordFallbackApplied", keywordFallbackApplied);
        diagnostics.put("semanticPolicyApplied", telemetry.path("semanticPolicyApplied").asBoolean(false));
        boolean selectedCandidateUsesLexicalFallback =
                telemetry.path("selectedCandidateUsesLexicalFallback").asBoolean(false)
                        && !Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"))
                        && !strongVerifiedSemanticDecisionEvidence(semanticDecision, selectedCandidate);
        diagnostics.put("selectedCandidateUsesLexicalFallback", selectedCandidateUsesLexicalFallback);
        boolean selectedCandidateUsesBroadArtifactDiscovery =
                telemetry.path("selectedCandidateUsesBroadArtifactDiscovery").asBoolean(false)
                        && !previewResourceSchemaVerified(preview);
        diagnostics.put("selectedCandidateUsesBroadArtifactDiscovery", selectedCandidateUsesBroadArtifactDiscovery);
        diagnostics.put("selectedCandidateUsesDomainAnchor",
                telemetry.path("selectedCandidateUsesDomainAnchor").asBoolean(false));
        diagnostics.put("candidateSetContainsLexicalFallback",
                telemetry.path("candidateSetContainsLexicalFallback").asBoolean(false));
        diagnostics.put("candidateSetContainsBroadArtifactDiscovery",
                telemetry.path("candidateSetContainsBroadArtifactDiscovery").asBoolean(false));
        diagnostics.put("candidateSetContainsDomainAnchor",
                telemetry.path("candidateSetContainsDomainAnchor").asBoolean(false));
        diagnostics.put("uiCompositionPlanUsesReferenceProvider", uiCompositionPlanUsesReferenceProvider(preview));
        diagnostics.put("uiCompositionPlanUsesHardcodedAnchor", uiCompositionPlanUsesHardcodedAnchor(preview));
        diagnostics.put("uiCompositionPlanHasUnverifiedSemanticAxes", uiCompositionPlanHasUnverifiedSemanticAxes(preview));
        diagnostics.put("previewTechnicallyValid", preview != null && preview.valid());
        boolean previewResourceSchemaVerified = previewResourceSchemaVerified(preview);
        diagnostics.put("previewResourceSchemaVerified", previewResourceSchemaVerified);
        diagnostics.put("selectedResourceSchemaGroundingMissing",
                selectedResourceRequiresSchemaGrounding(selectedCandidate, preview) && !previewResourceSchemaVerified);
        diagnostics.put("decisionValid", !previewHasSemanticMaterializationFailures(
                preview,
                Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewGroundedByPreview"))));
        putToolLoopDiagnostics(diagnostics, toolLoopResult);
        putAuthoringEvidenceDiagnostics(diagnostics, request);
        putSemanticAxisDiagnostics(diagnostics, preview);
        putProviderInvocationDiagnostics(diagnostics, request, providerInvocations);
        diagnostics.put("requiresReview", requiresDecisionReview(diagnostics));
        String reviewReason = decisionReviewReason(diagnostics);
        if (!reviewReason.isBlank()) {
            diagnostics.put("reviewReason", reviewReason);
        }
        return diagnostics;
    }

    private boolean strongVerifiedSemanticDecisionEvidence(
            AgenticAuthoringSemanticDecision semanticDecision,
            AgenticAuthoringCandidate selectedCandidate) {
        if (semanticDecision == null
                || semanticDecision.reviewRequired()
                || semanticDecision.selectedResource() == null
                || selectedCandidate == null
                || !safeText(semanticDecision.selectedResource().resourcePath())
                        .equals(safeText(selectedCandidate.resourcePath()))) {
            return false;
        }
        AgenticAuthoringSemanticDecision.RetrievalEvidence evidence = semanticDecision.retrievalEvidence();
        if (evidence == null
                || !AgenticAuthoringCandidateProvenancePolicy.SEMANTIC_RETRIEVAL.equals(evidence.retrievalSource())
                || evidence.evidence() == null) {
            return false;
        }
        return evidence.evidence().contains("llm-resource-focus")
                && evidence.evidence().contains("schema-available")
                && evidence.evidence().contains("stats-capabilities-verified");
    }

    List<AiProviderInvocationTelemetry> providerInvocations(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        JsonNode items = intentResolution == null || intentResolution.llmDiagnostics() == null
                ? objectMapper.missingNode()
                : intentResolution.llmDiagnostics().path("resolutionTelemetry").path("providerInvocations");
        if (!items.isArray()) {
            return List.of();
        }
        List<AiProviderInvocationTelemetry> invocations = new ArrayList<>();
        for (JsonNode item : items) {
            try {
                invocations.add(objectMapper.treeToValue(item, AiProviderInvocationTelemetry.class));
            } catch (Exception ignored) {
                // Detailed diagnostics are best effort and must never affect authoring.
            }
        }
        return List.copyOf(invocations);
    }

    private void putProviderInvocationDiagnostics(
            Map<String, Object> diagnostics,
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (!includeLlmDiagnostics(request)) {
            return;
        }
        List<AiProviderInvocationTelemetry> safeInvocations = providerInvocations == null
                ? List.of()
                : providerInvocations.stream().filter(Objects::nonNull).limit(12).toList();
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("schemaVersion", "praxis-agentic-authoring-provider-telemetry.v1");
        telemetry.put("providerInvocations", safeInvocations);
        telemetry.put("invocationCount", safeInvocations.size());
        telemetry.put("truncated", providerInvocations != null && providerInvocations.size() > safeInvocations.size());
        telemetry.put("successCount", safeInvocations.stream()
                .filter(invocation -> "success".equals(invocation.status()))
                .count());
        telemetry.put("failureCount", safeInvocations.stream()
                .filter(invocation -> "failure".equals(invocation.status()))
                .count());
        telemetry.put("latencyMs", safeInvocations.stream()
                .mapToLong(invocation -> Math.max(0L, invocation.latencyMs()))
                .sum());
        telemetry.put("inputTokens", tokenSum(safeInvocations, TokenKind.INPUT));
        telemetry.put("outputTokens", tokenSum(safeInvocations, TokenKind.OUTPUT));
        telemetry.put("cacheReadInputTokens", tokenSum(safeInvocations, TokenKind.CACHE_READ));
        telemetry.put("cacheWriteInputTokens", tokenSum(safeInvocations, TokenKind.CACHE_WRITE));
        telemetry.put("totalTokens", tokenSum(safeInvocations, TokenKind.TOTAL));
        telemetry.put("rawPromptCopied", false);
        telemetry.put("rawResponseCopied", false);
        telemetry.put("credentialsCopied", false);
        diagnostics.put("providerTelemetry", telemetry);
    }

    private boolean includeLlmDiagnostics(AgenticAuthoringTurnStreamRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("includeLlmDiagnostics").asBoolean(false);
    }

    private long tokenSum(List<AiProviderInvocationTelemetry> invocations, TokenKind kind) {
        return invocations.stream()
                .map(invocation -> switch (kind) {
                    case INPUT -> invocation.inputTokens();
                    case OUTPUT -> invocation.outputTokens();
                    case CACHE_READ -> invocation.cacheReadInputTokens();
                    case CACHE_WRITE -> invocation.cacheWriteInputTokens();
                    case TOTAL -> invocation.totalTokens();
                })
                .filter(Objects::nonNull)
                .filter(value -> value >= 0)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private void putToolLoopDiagnostics(
            Map<String, Object> diagnostics,
            AgenticAuthoringToolLoopResult toolLoopResult) {
        if (toolLoopResult == null) {
            diagnostics.put("toolLoopCompleted", true);
            diagnostics.put("toolLoopTerminalReason", "");
            return;
        }
        diagnostics.put("toolLoopCompleted", toolLoopResult.completed());
        diagnostics.put("toolLoopTerminalReason", safeText(toolLoopResult.terminalReason()));
        diagnostics.put("toolLoopStepCount", toolLoopResult.trace() == null ? 0 : toolLoopResult.trace().size());
    }

    private void putAuthoringEvidenceDiagnostics(
            Map<String, Object> diagnostics,
            AgenticAuthoringTurnStreamRequest request) {
        JsonNode contextHints = request == null || request.contextHints() == null
                ? objectMapper.missingNode()
                : request.contextHints();
        JsonNode evidenceContext = contextHints.isMissingNode()
                ? objectMapper.missingNode()
                : contextHints.path("authoringEvidence");
        JsonNode evidence = evidenceContext.path("evidence");
        diagnostics.put("authoringEvidenceCount", evidence.isArray() ? evidence.size() : 0);
        diagnostics.put("authoringEvidenceSourceRefs", sourceRefs(evidence));
        diagnostics.put(
                "authoringEvidenceOperationCandidateIds",
                canonicalOperationIds(evidenceContext.path("operationCandidates")));
        diagnostics.put("authoringEvidenceComponentId", safeText(evidenceContext.path("componentId").asText("")));
        diagnostics.put("authoringEvidencePhase", safeText(evidenceContext.path("phase").asText("")));

        JsonNode resourceDiscovery = contextHints.path("resourceDiscovery");
        JsonNode resourceFocus = resourceDiscovery.path("resourceSearchFocus");
        diagnostics.put(
                "resourceSearchFocusPrimaryBusinessEntity",
                safeText(resourceFocus.path("primaryBusinessEntity").asText("")));
        diagnostics.put(
                "resourceSearchFocusDesiredSurface",
                safeText(resourceFocus.path("desiredSurface").asText("")));
        diagnostics.put(
                "resourceSearchFocusUncertaintyPresent",
                !safeText(resourceFocus.path("uncertainty").asText("")).isBlank());
        diagnostics.put(
                "resourceDiscoveryCandidateSummaries",
                resourceDiscoveryCandidateSummaries(resourceDiscovery.path("candidates")));
    }

    private List<String> canonicalOperationIds(JsonNode candidates) {
        if (candidates == null || !candidates.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode candidate : candidates) {
            String id = firstNonBlank(
                    candidate.path("operationId").asText(""),
                    candidate.path("id").asText(""));
            if (!id.isBlank()) {
                ids.add(id);
            }
            if (ids.size() >= 24) {
                break;
            }
        }
        return List.copyOf(ids);
    }

    private List<Map<String, Object>> resourceDiscoveryCandidateSummaries(JsonNode candidates) {
        if (candidates == null || !candidates.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("resourcePath", safeText(candidate.path("resourcePath").asText("")));
            summary.put("score", candidate.path("score").asDouble(0d));
            List<String> evidence = new ArrayList<>();
            if (candidate.path("evidence").isArray()) {
                for (JsonNode item : candidate.path("evidence")) {
                    String value = safeText(item.asText(""));
                    if (!value.isBlank() && !evidence.contains(value)) {
                        evidence.add(value);
                    }
                    if (evidence.size() >= 16) {
                        break;
                    }
                }
            }
            summary.put("evidence", List.copyOf(evidence));
            summaries.add(Map.copyOf(summary));
            if (summaries.size() >= 12) {
                break;
            }
        }
        return List.copyOf(summaries);
    }

    private List<String> sourceRefs(JsonNode evidence) {
        if (evidence == null || !evidence.isArray()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (JsonNode item : evidence) {
            String sourceRef = safeText(item.path("sourceRef").asText(""));
            if (!sourceRef.isBlank() && !refs.contains(sourceRef)) {
                refs.add(sourceRef);
            }
            if (refs.size() >= 6) {
                break;
            }
        }
        return refs;
    }

    private boolean uiCompositionPlanUsesReferenceProvider(AgenticAuthoringPreviewResult preview) {
        return preview != null
                && preview.warnings() != null
                && preview.warnings().stream()
                .anyMatch(warning -> warning != null
                        && (warning.startsWith("ui-composition-plan-provider:quickstart-")
                        || warning.startsWith("ui-composition-plan-provider:selected-resource-")
                        || warning.startsWith("ui-composition-plan-provider:local-editorial-")));
    }

    private boolean uiCompositionPlanUsesHardcodedAnchor(AgenticAuthoringPreviewResult preview) {
        return preview != null
                && preview.warnings() != null
                && preview.warnings().stream()
                .anyMatch(warning -> warning != null
                        && warning.startsWith("ui-composition-plan-provider:quickstart-"));
    }

    private boolean uiCompositionPlanHasUnverifiedSemanticAxes(AgenticAuthoringPreviewResult preview) {
        return preview != null
                && preview.warnings() != null
                && preview.warnings().contains("semantic-axis-schema-verification-pending");
    }

    private boolean previewResourceSchemaVerified(AgenticAuthoringPreviewResult preview) {
        JsonNode grounding = preview == null || preview.uiCompositionPlan() == null
                ? objectMapper.missingNode()
                : preview.uiCompositionPlan().path("diagnostics").path("resourceSchemaGrounding");
        return grounding.path("verified").asBoolean(false)
                && "schemas.filtered".equals(grounding.path("source").asText(""));
    }

    private boolean selectedResourceRequiresSchemaGrounding(
            AgenticAuthoringCandidate selectedCandidate,
            AgenticAuthoringPreviewResult preview) {
        if (selectedCandidate == null
                || !StringUtils.hasText(selectedCandidate.resourcePath())
                || preview == null
                || preview.uiCompositionPlan() == null) {
            return false;
        }
        return hasEvidence(selectedCandidate, "semantic-retrieval")
                || hasEvidence(selectedCandidate, "tool-search-api-resources")
                || hasEvidence(selectedCandidate, "api-metadata")
                || hasEvidence(selectedCandidate, "schema-available");
    }

    private boolean semanticDecisionReviewGroundedByPreview(
            AgenticAuthoringSemanticDecision semanticDecision,
            AgenticAuthoringPreviewResult preview) {
        if (semanticDecision == null || !semanticDecision.reviewRequired() || !previewResourceSchemaVerified(preview)) {
            return false;
        }
        String reason = safeText(semanticDecision.reviewReason());
        if ("prompt-alignment-selection".equals(reason)) {
            AgenticAuthoringSemanticDecision.RetrievalEvidence retrievalEvidence = semanticDecision.retrievalEvidence();
            return retrievalEvidence != null
                    && retrievalEvidence.evidence() != null
                    && (retrievalEvidence.evidence().contains("tool-search-api-resources")
                    || "semantic_retrieval".equals(safeText(retrievalEvidence.retrievalSource())));
        }
        if ("keyword-fallback-fail-safe".equals(reason)) {
            AgenticAuthoringSemanticDecision.RetrievalEvidence retrievalEvidence = semanticDecision.retrievalEvidence();
            boolean governedEvidence = retrievalEvidence != null
                    && retrievalEvidence.evidence() != null
                    && (retrievalEvidence.evidence().contains("tool-search-api-resources")
                    || "semantic_retrieval".equals(safeText(retrievalEvidence.retrievalSource())));
            return governedEvidence
                    || semanticDecision.refinement() != null
                    && semanticDecision.refinement().preservesResource()
                    && ("current-page-bound-resource".equals(safeText(semanticDecision.previousDecisionRef()))
                            || !safeText(semanticDecision.refinementOf()).isBlank()
                            || !safeText(semanticDecision.previousDecisionId()).isBlank());
        }
        return false;
    }

    private void putSemanticAxisDiagnostics(Map<String, Object> diagnostics, AgenticAuthoringPreviewResult preview) {
        JsonNode axes = preview == null || preview.uiCompositionPlan() == null
                ? objectMapper.missingNode()
                : preview.uiCompositionPlan().path("diagnostics").path("semanticAxes");
        if (!axes.isArray()) {
            diagnostics.put("semanticAxisCount", 0);
            diagnostics.put("semanticAxisVerifiedCount", 0);
            diagnostics.put("semanticAxisPendingCount", 0);
            diagnostics.put("semanticAxesSchemaVerified", false);
            return;
        }
        int total = 0;
        int verified = 0;
        List<Map<String, Object>> axisSummaries = new ArrayList<>();
        for (JsonNode axis : axes) {
            if (axis.path("materialized").isBoolean() && !axis.path("materialized").asBoolean()) {
                continue;
            }
            total++;
            boolean schemaVerified = axis.path("schemaVerified").asBoolean(false);
            if (schemaVerified) {
                verified++;
            }
            Map<String, Object> axisSummary = new LinkedHashMap<>();
            axisSummary.put("concept", safeText(axis.path("concept").asText("")));
            axisSummary.put("field", safeText(axis.path("field").asText("")));
            axisSummary.put("label", safeText(axis.path("label").asText("")));
            axisSummary.put("schemaVerified", schemaVerified);
            axisSummary.put("schemaProbeStatus", safeText(axis.path("schemaProbeStatus").asText("")));
            axisSummary.put("provenance", safeText(axis.path("provenance").asText("")));
            axisSummaries.add(axisSummary);
        }
        diagnostics.put("semanticAxisCount", total);
        diagnostics.put("semanticAxisVerifiedCount", verified);
        diagnostics.put("semanticAxisPendingCount", Math.max(0, total - verified));
        diagnostics.put("semanticAxesSchemaVerified", total > 0 && total == verified);
        diagnostics.put("semanticAxes", axisSummaries);
    }

    boolean requiresDecisionReview(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(diagnostics.get("keywordFallbackApplied"))
                || Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewRequired"))
                || Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesLexicalFallback"))
                || Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesBroadArtifactDiscovery"))
                || Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesDomainAnchor"))
                || Boolean.TRUE.equals(diagnostics.get("selectedResourceSchemaGroundingMissing"))
                || Boolean.FALSE.equals(diagnostics.get("decisionValid"))
                || Boolean.FALSE.equals(diagnostics.get("toolLoopCompleted"))
                || Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanUsesHardcodedAnchor"))
                || Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanHasUnverifiedSemanticAxes"));
    }

    private String decisionReviewReason(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "";
        }
        if (Boolean.TRUE.equals(diagnostics.get("keywordFallbackApplied"))) {
            return "keyword-fallback-fail-safe";
        }
        if (Boolean.TRUE.equals(diagnostics.get("semanticDecisionReviewRequired"))) {
            String reason = safeText((String) diagnostics.get("semanticDecisionReviewReason"));
            return reason.isBlank() ? "semantic-decision-review-required" : reason;
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesLexicalFallback"))) {
            return "weak-lexical-evidence";
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesBroadArtifactDiscovery"))) {
            return "broad-artifact-discovery-requires-grounding";
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedCandidateUsesDomainAnchor"))) {
            return "resource-selection-domain-anchor";
        }
        if (Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanUsesHardcodedAnchor"))) {
            return "ui-composition-hardcoded-reference-provider";
        }
        if (Boolean.TRUE.equals(diagnostics.get("selectedResourceSchemaGroundingMissing"))) {
            return "resource-schema-grounding-required";
        }
        if (Boolean.FALSE.equals(diagnostics.get("decisionValid"))) {
            return "semantic-preview-materialization-mismatch";
        }
        if (Boolean.FALSE.equals(diagnostics.get("toolLoopCompleted"))) {
            String reason = safeText((String) diagnostics.get("toolLoopTerminalReason"));
            return reason.isBlank() ? "agentic-tool-loop-incomplete" : "agentic-tool-loop-" + reason;
        }
        if (Boolean.TRUE.equals(diagnostics.get("uiCompositionPlanHasUnverifiedSemanticAxes"))) {
            return "ui-composition-semantic-axis-schema-verification-pending";
        }
        return "";
    }

    private boolean previewHasSemanticMaterializationFailures(
            AgenticAuthoringPreviewResult preview,
            boolean semanticDecisionReviewGroundedByPreview) {
        return preview != null
                && preview.failureCodes() != null
                && preview.failureCodes().stream()
                .filter(Objects::nonNull)
                .anyMatch(code -> code.startsWith("semantic-preview-")
                        || (code.startsWith("semantic-decision-review-required")
                                && !semanticDecisionReviewGroundedByPreview));
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().contains(evidence);
    }

    private void putText(ObjectNode target, String fieldName, String value) {
        if (target != null && StringUtils.hasText(fieldName) && StringUtils.hasText(value)) {
            target.put(fieldName, value.trim());
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private enum TokenKind {
        INPUT,
        OUTPUT,
        CACHE_READ,
        CACHE_WRITE,
        TOTAL
    }
}
