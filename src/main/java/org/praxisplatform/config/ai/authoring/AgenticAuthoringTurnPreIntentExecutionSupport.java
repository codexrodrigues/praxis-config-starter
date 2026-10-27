package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.praxisplatform.config.dto.DomainRuleCatalogResponse;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.springframework.util.StringUtils;

/**
 * Package-private collaborator for {@link AgenticAuthoringTurnEngine} that encapsulates
 * pre-intent context enrichment, domain rule search presentation, semantic artifact
 * reconciliation, and Project Knowledge retrieval/context serialization.
 */
final class AgenticAuthoringTurnPreIntentExecutionSupport {

    private static final int MAX_PROJECT_KNOWLEDGE_RESOURCE_SCOPES = 6;
    private static final int MAX_PROJECT_KNOWLEDGE_INFLUENCES = 8;
    private static final int PROJECT_KNOWLEDGE_PER_RESOURCE_LIMIT = 4;

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringProjectKnowledgeService projectKnowledgeService;
    private final AgenticAuthoringTurnConsultativeQuickReplySupport consultativeQuickReplySupport;
    private final AgenticAuthoringTurnEventDiagnosticsSupport eventDiagnosticsSupport;

    AgenticAuthoringTurnPreIntentExecutionSupport(
            ObjectMapper objectMapper,
            AgenticAuthoringProjectKnowledgeService projectKnowledgeService,
            AgenticAuthoringTurnConsultativeQuickReplySupport consultativeQuickReplySupport,
            AgenticAuthoringTurnEventDiagnosticsSupport eventDiagnosticsSupport) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.projectKnowledgeService = projectKnowledgeService;
        this.consultativeQuickReplySupport = consultativeQuickReplySupport;
        this.eventDiagnosticsSupport = eventDiagnosticsSupport;
    }

    List<AgenticAuthoringQuickReply> domainRuleSearchQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            List<DomainRuleCatalogResponse.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        boolean english = responseLocale(request).toLowerCase(Locale.ROOT).startsWith("en");
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        for (DomainRuleCatalogResponse.Candidate candidate : candidates) {
            if (replies.size() >= 12
                    || candidate == null
                    || candidate.definitionId() == null
                    || !StringUtils.hasText(candidate.ruleKey())
                    || candidate.version() == null
                    || candidate.version() < 1) {
                continue;
            }
            ObjectNode contextHints = objectMapper.createObjectNode();
            ObjectNode selected = contextHints.putObject("selectedDomainDecisionRef");
            selected.put("schemaVersion", "praxis.ai.context-hints.domain-decision/v1");
            selected.put("definitionId", candidate.definitionId().toString());
            selected.put("ruleKey", candidate.ruleKey());
            selected.put("version", candidate.version());
            selected.put("source", "policy-studio-selection");
            String locale = responseLocale(request);
            if (StringUtils.hasText(locale)) {
                contextHints.put("responseLocale", locale);
            }
            String label = presentationText(candidate.ruleKey());
            String prompt = english
                    ? "Explain the governed decision %s at version %d using only attested evidence."
                            .formatted(candidate.ruleKey(), candidate.version())
                    : "Explique a decisão governada %s na versão %d usando somente evidência atestada."
                            .formatted(candidate.ruleKey(), candidate.version());
            String description = List.of(
                            safeText(candidate.status()),
                            safeText(candidate.ruleType()),
                            safeText(candidate.resourceKey()))
                    .stream()
                    .filter(StringUtils::hasText)
                    .map(this::presentationText)
                    .reduce((left, right) -> left + " · " + right)
                    .orElse(english ? "Governed decision" : "Decisão governada");
            replies.add(new AgenticAuthoringQuickReply(
                    "domain-decision:" + candidate.definitionId() + ":" + candidate.version(),
                    "domain-decision",
                    label,
                    prompt,
                    description,
                    "policy",
                    "default",
                    contextHints,
                    null,
                    domainRuleSearchCandidateNode(candidate)));
        }
        return List.copyOf(replies);
    }

    ObjectNode domainRuleSearchNode(DomainRuleCatalogResponse search) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", search.schemaVersion());
        ArrayNode candidates = node.putArray("candidates");
        if (search.candidates() != null) {
            search.candidates().stream()
                    .filter(Objects::nonNull)
                    .map(this::domainRuleSearchCandidateNode)
                    .forEach(candidates::add);
        }
        node.put("page", search.page());
        node.put("limit", search.limit());
        node.put("hasMore", search.hasMore());
        return node;
    }

    ObjectNode domainRuleSearchCandidateNode(DomainRuleCatalogResponse.Candidate candidate) {
        ObjectNode node = objectMapper.createObjectNode();
        if (candidate.definitionId() != null) {
            node.put("definitionId", candidate.definitionId().toString());
        }
        consultativeQuickReplySupport.putText(node, "ruleKey", candidate.ruleKey());
        if (candidate.version() != null) {
            node.put("version", candidate.version());
        }
        consultativeQuickReplySupport.putText(node, "ruleType", candidate.ruleType());
        consultativeQuickReplySupport.putText(node, "status", candidate.status());
        consultativeQuickReplySupport.putText(node, "contextKey", candidate.contextKey());
        consultativeQuickReplySupport.putText(node, "resourceKey", candidate.resourceKey());
        consultativeQuickReplySupport.putText(node, "serviceKey", candidate.serviceKey());
        consultativeQuickReplySupport.putText(node, "semanticOwner", candidate.semanticOwner());
        if (candidate.updatedAt() != null) {
            node.put("updatedAt", candidate.updatedAt().toString());
        }
        return node;
    }

    String responseLocale(AgenticAuthoringTurnStreamRequest request) {
        return request == null || request.contextHints() == null
                ? ""
                : request.contextHints().path("responseLocale").asText("").trim();
    }

    boolean shouldPreservePreIntentSemanticOrientation(AgenticAuthoringPreIntentToolPlan plan) {
        if (plan == null) {
            return false;
        }
        String artifactKind = safeText(plan.artifactKind());
        return plan.requiresFullIntentResolution()
                || "governed_domain_discovery".equals(plan.semanticIntentClass())
                || (!artifactKind.isBlank() && !"unknown".equals(artifactKind));
    }

    AgenticAuthoringTurnStreamRequest withProgressiveDomainKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringProjectKnowledgeProjection> projections) {
        if (request == null || projections == null || projections.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("projectKnowledge", projectKnowledgeContext(projections));
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringTurnStreamRequest withPreIntentTerminalProviderFailureContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AiProviderInvocationTelemetry> invocations) {
        if (request == null || invocations == null || invocations.isEmpty()) {
            return request;
        }
        AiProviderInvocationTelemetry last = invocations.get(invocations.size() - 1);
        if (last == null || !"failure".equals(last.status())) {
            return request;
        }
        String failureKind = safeText(last.failureKind());
        if (!Set.of("quota-exhausted", "auth-error", "provider-not-configured").contains(failureKind)) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode failure = contextHints.putObject("preIntentProviderFailure");
        failure.put("phase", safeText(last.phase()));
        failure.put("failureKind", failureKind);
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringTurnStreamRequest withPreIntentSemanticOrientationContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringPreIntentToolPlan orientation) {
        if (request == null || orientation == null) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode context = contextHints.putObject("preIntentSemanticOrientation");
        context.put("schemaVersion", "praxis-agentic-authoring-pre-intent-orientation-context.v2");
        context.put("semanticIntentClass", safeText(orientation.semanticIntentClass()));
        context.put("artifactKind", safeText(orientation.artifactKind()));
        context.put("primaryComponent", safeText(orientation.primaryComponent()));
        context.put("layoutKind", safeText(orientation.layoutKind()));
        context.put("requiresFullIntentResolution", orientation.requiresFullIntentResolution());
        context.put("source", "llm_pre_intent_tool_plan");
        if (orientation.queryConstraints() != null && !orientation.queryConstraints().isNull()) {
            context.set("queryConstraints", orientation.queryConstraints().deepCopy());
        }
        AgenticAuthoringResourceSearchFocus resourceSearchFocus = resourceSearchFocus(orientation);
        if (resourceSearchFocus != null && !resourceSearchFocus.isEmpty()) {
            context.set("resourceSearchFocus", resourceSearchFocusNode(resourceSearchFocus));
        }
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringResourceSearchFocus resourceSearchFocus(
            AgenticAuthoringPreIntentToolPlan orientation) {
        if (orientation == null || orientation.toolCalls() == null) {
            return null;
        }
        for (AgenticAuthoringToolCall toolCall : orientation.toolCalls()) {
            if (toolCall == null) {
                continue;
            }
            if (toolCall.payload() instanceof AgenticAuthoringResourceCandidatesRequest candidatesRequest
                    && candidatesRequest.resourceSearchFocus() != null) {
                return candidatesRequest.resourceSearchFocus();
            }
            String resourceKey = switch (toolCall.payload()) {
                case DomainKnowledgeToolRequest domainRequest -> domainRequest.resourceKey();
                case DomainBindingToolRequest bindingRequest -> bindingRequest.resourceKey();
                case DomainOperationVerificationToolRequest verificationRequest -> verificationRequest.resourceKey();
                default -> "";
            };
            if (StringUtils.hasText(resourceKey)) {
                return new AgenticAuthoringResourceSearchFocus(
                        resourceKey,
                        List.of(),
                        safeText(orientation.artifactKind()),
                        "",
                        "LLM-resolved canonical resource scope from the pre-intent tool plan.");
            }
        }
        return null;
    }

    AgenticAuthoringTurnStreamRequest withProgressiveDomainBindingContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringDomainBindingService.BindingProjection> bindings) {
        if (request == null || bindings == null || bindings.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode envelope = contextHints.putObject("domainBindings");
        envelope.put("schemaVersion", "praxis-agentic-authoring-domain-bindings.v1");
        envelope.put("source", "domain_knowledge_binding");
        envelope.put("bindingCount", bindings.size());
        envelope.set("entries", objectMapper.valueToTree(bindings));
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringTurnStreamRequest withVerifiedOperationContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringOperationalBindingVerificationService.OperationProjection> operations) {
        if (request == null || operations == null || operations.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode envelope = contextHints.putObject("verifiedDomainOperations");
        envelope.put("schemaVersion", "praxis-agentic-authoring-verified-domain-operations.v2");
        envelope.put("source", "schemas.filtered+resource.capabilities+schemas.actions");
        envelope.put("operationCount", operations.size());
        envelope.set("entries", objectMapper.valueToTree(operations));
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringTurnStreamRequest withVerifiedRelatedResourceSurfaceContext(
            AgenticAuthoringTurnStreamRequest request,
            List<AgenticAuthoringOperationalBindingVerificationService.RelatedResourceSurfaceProjection> surfaces) {
        if (request == null || surfaces == null || surfaces.isEmpty()) {
            return request;
        }
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode envelope = contextHints.putObject("verifiedRelatedResourceSurfaces");
        envelope.put("schemaVersion", "praxis-agentic-authoring-verified-related-resource-surfaces.v1");
        envelope.put("source", "schemas.surfaces");
        envelope.put("surfaceCount", surfaces.size());
        envelope.set("entries", objectMapper.valueToTree(surfaces));
        return copyWithContextHints(request, contextHints);
    }

    boolean hasVerifiedOperationContextForResource(
            AgenticAuthoringTurnStreamRequest request,
            String resourceKey,
            String resourcePath) {
        JsonNode envelope = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("verifiedDomainOperations");
        JsonNode entries = envelope == null ? null : envelope.path("entries");
        if (envelope == null || !envelope.isObject() || entries == null || !entries.isArray() || entries.isEmpty()) {
            return false;
        }
        String expectedPath = businessResourcePath(resourcePath);
        if (!StringUtils.hasText(expectedPath)) {
            return false;
        }
        for (JsonNode entry : entries) {
            String entryResourceKey = safeText(entry.path("resourceKey").asText());
            String entryResourcePath = businessResourcePath(entry.path("resourcePath").asText());
            if (resourceKey.equals(entryResourceKey) && expectedPath.equals(entryResourcePath)) {
                return true;
            }
        }
        return false;
    }

    boolean requiresResourceWorkspaceOperationalGrounding(
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (intentResolution == null || intentResolution.selectedCandidate() == null) {
            return false;
        }
        AgenticAuthoringSemanticDecision semanticDecision = intentResolution.semanticDecision();
        AgenticAuthoringVisualizationDecision visualizationDecision = semanticDecision != null
                && semanticDecision.visualizationDecision() != null
                        ? semanticDecision.visualizationDecision()
                        : intentResolution.visualizationDecision();
        return visualizationDecision != null
                && ("resource-master-detail".equals(safeText(visualizationDecision.layoutKind()))
                || "parent-child-related-resource".equals(safeText(visualizationDecision.layoutKind()))
                || "approval-workbench".equals(safeText(visualizationDecision.layoutKind()))
                || "resource-action-workbench".equals(safeText(visualizationDecision.layoutKind()))
                || "workflow-action-workbench".equals(safeText(visualizationDecision.layoutKind())));
    }

    AgenticAuthoringIntentResolutionResult composePlannedDashboardWithChartDecision(
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult resolution) {
        if (plannedResourceDiscovery == null
                || !plannedResourceDiscovery.valid()
                || !"dashboard".equals(safeText(plannedResourceDiscovery.artifactKind()).toLowerCase(Locale.ROOT))
                || resolution == null
                || !resolution.valid()
                || !"chart".equals(safeText(resolution.artifactKind()).toLowerCase(Locale.ROOT))
                || !Set.of("create", "explore").contains(
                        safeText(resolution.operationKind()).toLowerCase(Locale.ROOT))
                || resolution.visualizationDecision() == null) {
            return resolution;
        }
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add("llm-chart-projection-composed-into-pre-intent-dashboard-plan");
        return new AgenticAuthoringIntentResolutionResult(
                resolution.valid(),
                resolution.operationKind(),
                "dashboard",
                "create_dashboard",
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
                null);
    }

    boolean isCompatibleWithPlannedArtifact(
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (plannedResourceDiscovery == null
                || !plannedResourceDiscovery.valid()
                || plannedResourceDiscovery.resourceSearchFocus() == null
                || plannedResourceDiscovery.resourceSearchFocus().isEmpty()
                || plannedResourceDiscovery.candidates() == null
                || plannedResourceDiscovery.candidates().isEmpty()
                || intentResolution == null
                || !intentResolution.valid()) {
            return true;
        }
        String plannedArtifactKind = safeText(plannedResourceDiscovery.artifactKind()).toLowerCase(Locale.ROOT);
        if ("page".equals(plannedArtifactKind)
                || !Set.of("dashboard", "chart", "table", "form", "api_catalog")
                        .contains(plannedArtifactKind)) {
            return true;
        }
        if (!plannedArtifactKind.equals(safeText(intentResolution.artifactKind()).toLowerCase(Locale.ROOT))) {
            return false;
        }
        AgenticAuthoringVisualizationDecision decision = intentResolution.visualizationDecision();
        if (decision == null || !Set.of("dashboard", "chart").contains(plannedArtifactKind)) {
            return true;
        }
        String primaryComponent = safeText(decision.primaryComponent()).toLowerCase(Locale.ROOT);
        String layoutKind = safeText(decision.layoutKind()).toLowerCase(Locale.ROOT);
        return !Set.of("praxis-expansion", "praxis-tabs").contains(primaryComponent)
                && !Set.of("accordion_layout", "single_column_expansion_page", "tabs_layout")
                        .contains(layoutKind)
                && !decision.excludedComponentIds().contains("praxis-chart");
    }

    AgenticAuthoringTurnStreamRequest withSemanticReconciliationContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringResourceCandidatesResult plannedResourceDiscovery,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode reconciliation = contextHints.putObject("semanticReconciliation");
        reconciliation.put("schemaVersion", "praxis-agentic-authoring-semantic-reconciliation.v1");
        reconciliation.put("source", "backend-pre-intent-tool-plan");
        reconciliation.put("forceFullIntentResolution", true);
        reconciliation.put("plannedArtifactKind", safeText(plannedResourceDiscovery.artifactKind()));
        reconciliation.set(
                "plannedResourceSearchFocus",
                resourceSearchFocusNode(plannedResourceDiscovery.resourceSearchFocus()));
        reconciliation.put(
                "observedArtifactKind",
                safeText(intentResolution == null ? null : intentResolution.artifactKind()));
        if (intentResolution != null && intentResolution.visualizationDecision() != null) {
            reconciliation.set(
                    "observedVisualizationDecision",
                    objectMapper.valueToTree(intentResolution.visualizationDecision()));
        }
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringIntentResolutionResult withIntentResolutionWarning(
            AgenticAuthoringIntentResolutionResult resolution,
            String warning) {
        if (resolution == null) {
            return null;
        }
        LinkedHashSet<String> warnings = new LinkedHashSet<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        warnings.add(warning);
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
                resolution.semanticDecision());
    }

    AgenticAuthoringIntentResolutionResult blockPersistentArtifactConflict(
            String plannedArtifactKind,
            AgenticAuthoringIntentResolutionResult resolution) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>(resolution == null || resolution.warnings() == null
                ? List.of()
                : resolution.warnings());
        warnings.add("llm-intent-artifact-conflicts-with-pre-intent-plan");
        warnings.add("llm-intent-resolution-failed-closed");
        LinkedHashSet<String> failureCodes = new LinkedHashSet<>(
                resolution == null || resolution.failureCodes() == null
                        ? List.of()
                        : resolution.failureCodes());
        failureCodes.add("semantic-artifact-conflict");
        failureCodes.add("semantic-intent-confirmation-required");
        String resolvedArtifactKind = resolution == null ? "" : resolution.artifactKind();
        List<String> questions = List.of(persistentArtifactConflictQuestion(
                plannedArtifactKind,
                resolvedArtifactKind));
        AgenticAuthoringGateResult gate = new AgenticAuthoringGateResult(
                "pre-intent-artifact-invariance@0.1.0",
                "clarification_required",
                List.copyOf(failureCodes));
        return new AgenticAuthoringIntentResolutionResult(
                false,
                "unknown",
                "unknown",
                "semantic_artifact_conflict",
                "semantic-reconciliation-required",
                resolution == null ? "" : resolution.targetApp(),
                resolution == null ? "" : resolution.targetComponentId(),
                resolution == null ? null : resolution.target(),
                resolution == null ? null : resolution.selectedCandidate(),
                resolution == null ? List.of() : resolution.candidates(),
                gate,
                resolution == null ? "" : resolution.effectivePrompt(),
                "Encontrei duas interpretações incompatíveis para a estrutura da tela e bloqueei a prévia antes de materializar.",
                null,
                null,
                List.of(),
                null,
                questions,
                List.copyOf(warnings),
                List.copyOf(failureCodes),
                resolution == null ? objectMapper.createObjectNode() : resolution.currentPageSummary(),
                resolution == null ? objectMapper.createObjectNode() : resolution.llmDiagnostics(),
                null,
                null);
    }

    private String persistentArtifactConflictQuestion(
            String plannedArtifactKind,
            String resolvedArtifactKind) {
        return "Você confirma qual artefato deve governar a materialização: %s, definido pelo planejamento semântico, ou %s, definido pela resolução semântica?"
                .formatted(
                        artifactKindClarificationLabel(plannedArtifactKind),
                        artifactKindClarificationLabel(resolvedArtifactKind));
    }

    private String artifactKindClarificationLabel(String artifactKind) {
        return switch (safeText(artifactKind).toLowerCase(Locale.ROOT)) {
            case "dashboard" -> "dashboard";
            case "page" -> "página";
            case "table" -> "tabela";
            case "form" -> "formulário";
            case "chart" -> "gráfico";
            case "api_catalog" -> "catálogo de APIs";
            default -> "artefato não identificado";
        };
    }

    AgenticAuthoringTurnStreamRequest withProjectKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (projectKnowledgeService == null || eventSink.terminalReached()) {
            return request;
        }
        boolean hasExistingProjectKnowledge = request.contextHints() != null
                && request.contextHints().path("projectKnowledge").isObject();
        if (intentResolution == null && hasExistingProjectKnowledge) {
            return request;
        }
        AgenticAuthoringProjectKnowledgeQuery query =
                projectKnowledgeQuery(request, principalContext, intentResolution);
        if (hasExistingProjectKnowledge
                && !StringUtils.hasText(query.contextKey())
                && !StringUtils.hasText(query.resourceKey())
                && "context".equals(query.nodeType())) {
            return request;
        }
        if (!hasProjectKnowledgeScope(query)) {
            return request;
        }
        eventDiagnosticsSupport.emitStatus(
                eventSink,
                "projectKnowledge.retrieve",
                "Estou buscando conhecimento governado do projeto para responder com mais contexto.");
        List<AgenticAuthoringProjectKnowledgeProjection> projections = projectKnowledgeService.retrieve(query);
        if (projections.isEmpty()) {
            Map<String, Object> diagnostics = new LinkedHashMap<>(projectKnowledgeDiagnostics(projections));
            diagnostics.put("result", "empty");
            eventSink.append("thought.step", eventDiagnosticsSupport.safeToolProjection(
                    "projectKnowledge.result",
                    "Nao encontrei conhecimento governado adicional do projeto para este planejamento.",
                    diagnostics));
            if (!hasExistingProjectKnowledge
                    && !StringUtils.hasText(query.contextKey())
                    && !StringUtils.hasText(query.resourceKey())
                    && "context".equals(query.nodeType())) {
                ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                        ? request.contextHints().deepCopy()
                        : objectMapper.createObjectNode();
                contextHints.set("projectKnowledge", projectKnowledgeContext(List.of()));
                return copyWithContextHints(request, contextHints);
            }
            return request;
        }
        eventSink.append("thought.step", eventDiagnosticsSupport.safeToolProjection(
                "projectKnowledge.retrieve",
                "Conhecimento governado do projeto recuperado para apoiar o planejamento.",
                projectKnowledgeDiagnostics(projections)));
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("projectKnowledge", projectKnowledgeContext(projections));
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringTurnStreamRequest withResourceCandidateProjectKnowledgeContext(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringTurnEventSink eventSink,
            List<AgenticAuthoringCandidate> candidates) {
        if (projectKnowledgeService == null
                || eventSink.terminalReached()
                || candidates == null
                || candidates.isEmpty()) {
            return request;
        }
        LinkedHashMap<String, AgenticAuthoringProjectKnowledgeProjection> projectionsByKey =
                new LinkedHashMap<>();
        LinkedHashSet<String> resourceKeys = new LinkedHashSet<>();
        for (AgenticAuthoringCandidate candidate : candidates) {
            String resourceKey = resourceKeyFromPath(candidate == null ? null : candidate.resourcePath());
            if (!StringUtils.hasText(resourceKey) || resourceKeys.contains(resourceKey)) {
                continue;
            }
            if (resourceKeys.size() >= MAX_PROJECT_KNOWLEDGE_RESOURCE_SCOPES) {
                break;
            }
            resourceKeys.add(resourceKey);
        }
        if (resourceKeys.isEmpty()) {
            return request;
        }
        eventDiagnosticsSupport.emitStatus(
                eventSink,
                "projectKnowledge.retrieve",
                "Estou buscando conhecimento governado do projeto para os recursos recuperados.");
        for (String resourceKey : resourceKeys) {
            AgenticAuthoringProjectKnowledgeQuery query = new AgenticAuthoringProjectKnowledgeQuery(
                    principalContext.tenantId(),
                    principalContext.environment(),
                    contextKeyFromResourceKey(resourceKey),
                    resourceKey,
                    scopedProjectKnowledgeKinds(),
                    null,
                    PROJECT_KNOWLEDGE_PER_RESOURCE_LIMIT,
                    null);
            for (AgenticAuthoringProjectKnowledgeProjection projection : projectKnowledgeService.retrieve(query)) {
                if (projection == null) {
                    continue;
                }
                String projectionKey = firstText(projection.knowledgeId(), projection.conceptKey());
                if (StringUtils.hasText(projectionKey)) {
                    projectionsByKey.putIfAbsent(projectionKey, projection);
                }
                if (projectionsByKey.size() >= MAX_PROJECT_KNOWLEDGE_INFLUENCES) {
                    break;
                }
            }
            if (projectionsByKey.size() >= MAX_PROJECT_KNOWLEDGE_INFLUENCES) {
                break;
            }
        }
        List<AgenticAuthoringProjectKnowledgeProjection> projections =
                List.copyOf(projectionsByKey.values());
        Map<String, Object> diagnostics = new LinkedHashMap<>(projectKnowledgeDiagnostics(projections));
        diagnostics.put("resourceScopeCount", resourceKeys.size());
        diagnostics.put("retrievalStage", "governed-resource-candidates");
        if (projections.isEmpty()) {
            diagnostics.put("result", "empty");
            eventSink.append("thought.step", eventDiagnosticsSupport.safeToolProjection(
                    "projectKnowledge.result",
                    "Nao encontrei conhecimento governado adicional para os recursos recuperados.",
                    diagnostics));
            return request;
        }
        eventSink.append("thought.step", eventDiagnosticsSupport.safeToolProjection(
                "projectKnowledge.retrieve",
                "Conhecimento governado do projeto recuperado para os recursos candidatos.",
                diagnostics));
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        contextHints.set("projectKnowledge", projectKnowledgeContext(projections));
        return copyWithContextHints(request, contextHints);
    }

    AgenticAuthoringProjectKnowledgeQuery projectKnowledgeQuery(
            AgenticAuthoringTurnStreamRequest request,
            AiPrincipalContext principalContext,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        String contextKey = firstText(
                domainCatalogHint(request, "contextKey"), contextKeyFromCandidate(intentResolution));
        String resourceKey = firstText(
                domainCatalogHint(request, "resourceKey"), resourceKeyFromCandidate(intentResolution));
        boolean macroPack = !StringUtils.hasText(contextKey) && !StringUtils.hasText(resourceKey);
        String semanticQuery = intentResolution == null ? safeText(request.userPrompt()) : null;
        boolean semanticPreIntent = macroPack && StringUtils.hasText(semanticQuery);
        return new AgenticAuthoringProjectKnowledgeQuery(
                principalContext.tenantId(),
                principalContext.environment(),
                contextKey,
                resourceKey,
                macroPack && !semanticPreIntent
                        ? List.of("context")
                        : scopedProjectKnowledgeKinds(),
                macroPack && !semanticPreIntent ? "context" : null,
                macroPack && !semanticPreIntent ? 4 : 8,
                semanticPreIntent ? semanticQuery : null);
    }

    List<String> scopedProjectKnowledgeKinds() {
        return List.of(
                "context",
                "business_capability",
                "process",
                "business_event",
                "policy",
                "metric",
                "actor",
                "concept",
                "project_preference",
                "domain_decision_hint",
                "component_authoring_pattern",
                "resource_selection_rationale",
                "governance_constraint",
                "integration_note");
    }

    boolean hasProjectKnowledgeScope(AgenticAuthoringProjectKnowledgeQuery query) {
        return query != null
                && (StringUtils.hasText(query.contextKey())
                        || StringUtils.hasText(query.resourceKey())
                        || "context".equals(query.nodeType())
                        || StringUtils.hasText(query.semanticQuery()));
    }

    ObjectNode projectKnowledgeContext(List<AgenticAuthoringProjectKnowledgeProjection> projections) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-project-knowledge.v1");
        context.put("source", "domain_knowledge_concept");
        context.put("influenceCount", projections.size());
        context.put(
                "usageRule",
                "Use these safe projections as governed project context; never expose raw source data or treat them as executable rules.");
        ArrayNode entries = context.putArray("entries");
        for (AgenticAuthoringProjectKnowledgeProjection projection : projections) {
            ObjectNode entry = entries.addObject();
            entry.put("knowledgeId", safeText(projection.knowledgeId()));
            entry.put("conceptKey", safeText(projection.conceptKey()));
            entry.put("kind", safeText(projection.kind()));
            entry.set("scope", objectMapper.valueToTree(projection.scope()));
            entry.set("status", objectMapper.valueToTree(projection.status()));
            entry.put("visibility", safeText(projection.visibility()));
            entry.put("sourceSummary", safeText(projection.sourceSummary()));
            entry.put("influence", safeText(projection.influence()));
            entry.put("summary", safeText(projection.summary()));
            entry.set("evidence", objectMapper.valueToTree(
                    projection.evidence() == null ? List.of() : projection.evidence()));
        }
        return context;
    }

    Map<String, Object> projectKnowledgeDiagnostics(
            List<AgenticAuthoringProjectKnowledgeProjection> projections) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("source", "domain_knowledge_concept");
        diagnostics.put("influenceCount", projections.size());
        diagnostics.put("kinds", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::kind)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        diagnostics.put("visibilities", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::visibility)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        diagnostics.put("conceptKeys", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::conceptKey)
                .filter(StringUtils::hasText)
                .limit(6)
                .toList());
        diagnostics.put("sourceSummaries", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::sourceSummary)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(6)
                .toList());
        diagnostics.put("influences", projections.stream()
                .map(AgenticAuthoringProjectKnowledgeProjection::influence)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());
        return diagnostics;
    }

    ObjectNode nonMaterializedPreview(AgenticAuthoringTurnStreamRequest request) {
        ObjectNode preview = objectMapper.createObjectNode();
        JsonNode audit = AgenticAuthoringProjectKnowledgeAuditFactory.create(
                objectMapper,
                request == null ? null : request.contextHints(),
                null,
                null);
        if (audit != null) {
            preview.putObject("diagnostics").set("projectKnowledgeAudit", audit);
        }
        return preview;
    }

    AgenticAuthoringTurnStreamRequest withResourceDiscoveryContext(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringResourceCandidatesResult discovery) {
        ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                ? request.contextHints().deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode resourceDiscovery = contextHints.putObject("resourceDiscovery");
        resourceDiscovery.put("tool", AgenticAuthoringToolRegistry.SEARCH_API_RESOURCES);
        resourceDiscovery.put("retrievalQuery", safeText(discovery.retrievalQuery()));
        resourceDiscovery.put("artifactKind", safeText(discovery.artifactKind()));
        if (discovery.resourceSearchFocus() != null && !discovery.resourceSearchFocus().isEmpty()) {
            resourceDiscovery.set("resourceSearchFocus", resourceSearchFocusNode(discovery.resourceSearchFocus()));
        }
        ArrayNode candidates = resourceDiscovery.putArray("candidates");
        for (AgenticAuthoringCandidate candidate : discovery.candidates()) {
            ObjectNode candidateNode = (ObjectNode) consultativeQuickReplySupport.candidateContext(candidate);
            JsonNode fieldCatalog = analyticsFieldCatalog(discovery, candidate.resourcePath());
            if (fieldCatalog.isArray() && !fieldCatalog.isEmpty()) {
                candidateNode.set("analyticsFields", fieldCatalog);
            }
            candidates.add(candidateNode);
        }
        if (discovery.consultativeProjection() != null && discovery.consultativeProjection().hasResources()) {
            resourceDiscovery.set(
                    "consultativeProjection",
                    objectMapper.valueToTree(discovery.consultativeProjection()));
        }
        return copyWithContextHints(request, contextHints);
    }

    JsonNode analyticsFieldCatalog(
            AgenticAuthoringResourceCandidatesResult discovery,
            String resourcePath) {
        if (discovery == null || discovery.diagnostics() == null || !StringUtils.hasText(resourcePath)) {
            return objectMapper.createArrayNode();
        }
        Object catalogs = discovery.diagnostics().get("analyticsCapabilityFieldCatalogs");
        JsonNode node = objectMapper.valueToTree(catalogs);
        JsonNode catalog = node.path(resourcePath);
        return catalog.isArray() ? catalog : objectMapper.createArrayNode();
    }

    ObjectNode resourceSearchFocusNode(AgenticAuthoringResourceSearchFocus focus) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("primaryBusinessEntity", safeText(focus.primaryBusinessEntity()));
        ArrayNode supportingConcepts = node.putArray("supportingConcepts");
        for (String concept : focus.supportingConcepts()) {
            if (StringUtils.hasText(concept)) {
                supportingConcepts.add(concept.trim());
            }
        }
        node.put("desiredSurface", safeText(focus.desiredSurface()));
        node.put("uncertainty", safeText(focus.uncertainty()));
        node.put("rationale", safeText(focus.rationale()));
        return node;
    }

    String domainCatalogHint(AgenticAuthoringTurnStreamRequest request, String fieldName) {
        JsonNode domainCatalog = request.contextHints() == null
                ? null
                : request.contextHints().path("domainCatalog");
        if (domainCatalog == null || domainCatalog.isMissingNode() || !domainCatalog.hasNonNull(fieldName)) {
            return null;
        }
        String value = domainCatalog.path(fieldName).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    String contextKeyFromCandidate(AgenticAuthoringIntentResolutionResult intentResolution) {
        String resourceKey = resourceKeyFromCandidate(intentResolution);
        return contextKeyFromResourceKey(resourceKey);
    }

    String resourceKeyFromCandidate(AgenticAuthoringIntentResolutionResult intentResolution) {
        AgenticAuthoringCandidate candidate = intentResolution == null ? null : intentResolution.selectedCandidate();
        return resourceKeyFromPath(candidate == null ? null : candidate.resourcePath());
    }

    String contextKeyFromResourceKey(String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return null;
        }
        int firstDot = resourceKey.indexOf('.');
        return firstDot > 0 ? resourceKey.substring(0, firstDot) : null;
    }

    String resourceKeyFromPath(String resourcePath) {
        if (!StringUtils.hasText(resourcePath)) {
            return null;
        }
        String path = resourcePath.trim();
        if (path.startsWith("/api/")) {
            path = path.substring(5);
        } else if (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = path.replace('/', '.');
        return StringUtils.hasText(path) ? path : null;
    }

    String businessResourcePath(String path) {
        String value = safeText(path).trim();
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        value = value.replaceAll("/+$", "");
        if (value.endsWith("/filter/cursor")) {
            return value.substring(0, value.length() - "/filter/cursor".length());
        }
        if (value.endsWith("/stats")) {
            return value.substring(0, value.length() - "/stats".length());
        }
        return value;
    }

    private AgenticAuthoringTurnStreamRequest copyWithContextHints(
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

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String presentationText(String value) {
        return AgenticAuthoringPresentationText.display(safeText(value));
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }
}
