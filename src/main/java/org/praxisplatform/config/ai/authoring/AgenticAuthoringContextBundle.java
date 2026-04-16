package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

final class AgenticAuthoringContextBundle {

    private AgenticAuthoringContextBundle() {
    }

    static ObjectNode create(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("schemaVersion", "praxis-agentic-authoring-context-bundle.v1");
        bundle.set("runtimeContext", runtimeContext(objectMapper, request, currentPageSummary, target));
        bundle.set("userIntent", userIntent(objectMapper, request, effectivePrompt));
        bundle.set("retrievalContext", retrievalContext(objectMapper, candidateOptions));
        bundle.set("componentContext", componentContext(objectMapper, componentCapabilities));
        bundle.set("conversationContext", conversationContext(objectMapper, request));
        bundle.set("toolCatalog", toolCatalog(objectMapper));
        bundle.set("rules", rules(objectMapper));
        return bundle;
    }

    private static ObjectNode runtimeContext(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target) {
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.put("hostApplication", "Angular Praxis Page Builder assistant");
        runtime.put("targetApp", valueOrEmpty(request.targetApp()));
        runtime.put("targetComponentId", valueOrEmpty(request.targetComponentId()));
        runtime.put("currentRoute", valueOrEmpty(request.currentRoute()));
        runtime.put("selectedWidgetKey", valueOrEmpty(request.selectedWidgetKey()));
        runtime.set("currentPageSummary", currentPageSummary == null ? objectMapper.createObjectNode() : currentPageSummary);
        runtime.set("selectedTarget", target == null ? objectMapper.nullNode() : objectMapper.valueToTree(target));
        return runtime;
    }

    private static ObjectNode userIntent(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt) {
        ObjectNode intent = objectMapper.createObjectNode();
        intent.put("userPrompt", valueOrEmpty(effectivePrompt));
        intent.put("rawUserPrompt", valueOrEmpty(request.userPrompt()));
        intent.put("effectivePrompt", valueOrEmpty(effectivePrompt));
        return intent;
    }

    private static ObjectNode retrievalContext(
            ObjectMapper objectMapper,
            List<AgenticAuthoringCandidate> candidateOptions) {
        ObjectNode retrieval = objectMapper.createObjectNode();
        retrieval.set("candidateResources", objectMapper.valueToTree(candidateOptions == null ? List.of() : candidateOptions));
        retrieval.put("selectionRule", "Select or suggest only resourcePath values present in candidateResources.");
        retrieval.put("emptyStateRule", "When candidateResources is empty or insufficient, use toolCatalog.searchApiResources before asking the user to type endpoints manually.");
        return retrieval;
    }

    private static ObjectNode componentContext(
            ObjectMapper objectMapper,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode component = objectMapper.createObjectNode();
        component.set("componentCapabilities", objectMapper.valueToTree(componentCapabilities));
        component.put("exampleRule", "Prefer examples[].prompt, examples[].intent, and examples[].configHints from the matching component capability when inferring UI configuration.");
        return component;
    }

    private static ObjectNode conversationContext(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request) {
        ObjectNode conversation = objectMapper.createObjectNode();
        conversation.set("conversationMessages", objectMapper.valueToTree(request.conversationMessages() == null
                ? List.of()
                : request.conversationMessages()));
        conversation.set("pendingClarification", request.pendingClarification() == null
                ? objectMapper.nullNode()
                : objectMapper.valueToTree(request.pendingClarification()));
        conversation.set("attachmentSummaries", objectMapper.valueToTree(request.attachmentSummaries() == null
                ? List.of()
                : request.attachmentSummaries()));
        conversation.set("contextHints", request.contextHints() == null
                ? objectMapper.createObjectNode()
                : request.contextHints());
        return conversation;
    }

    private static ObjectNode toolCatalog(ObjectMapper objectMapper) {
        ObjectNode tools = objectMapper.createObjectNode();
        ObjectNode searchApiResources = tools.putObject("searchApiResources");
        searchApiResources.put("method", "POST");
        searchApiResources.put("endpoint", "/api/praxis/config/ai/authoring/resource-candidates");
        searchApiResources.put("purpose", "Discover API resources, schemas, submit actions, and resourcePath candidates from the backend catalog.");
        ArrayNode inputs = searchApiResources.putArray("inputs");
        inputs.add("retrievalQuery");
        inputs.add("artifactKind");
        inputs.add("limit");
        searchApiResources.put("result", "candidateResources[] with resourcePath, operation, schemaUrl, submitUrl, submitMethod, score, reason, and evidence.");
        searchApiResources.put("whenToUse", "Use when the user intent is clear enough to search resources but current candidateResources is empty, generic, or ambiguous.");
        return tools;
    }

    private static ArrayNode rules(ObjectMapper objectMapper) {
        ArrayNode rules = objectMapper.createArrayNode();
        rules.add("You are helping inside an Angular application that uses Praxis UI Page Builder metadata-driven components.");
        rules.add("Use the backend tool catalog as the menu of available retrieval operations; do not invent resources, endpoints, schemas, fields, or component capabilities.");
        rules.add("Use component capability examples to infer likely configuration choices before asking the user for low-level technical details.");
        rules.add("When more backend data is needed, return actionable quickReplies with contextHints.tool instead of a generic clarification.");
        rules.add("assistantMessage must be friendly, contextual, and actionable; avoid terse labels such as 'alimentar tela'.");
        return rules;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
