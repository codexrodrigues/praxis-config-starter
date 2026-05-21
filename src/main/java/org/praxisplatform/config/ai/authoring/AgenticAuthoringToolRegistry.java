package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.ContextRetrievalService;
import org.praxisplatform.config.service.SchemaRetrievalService;

public class AgenticAuthoringToolRegistry {

    static final String SEARCH_API_RESOURCES = "searchApiResources";
    static final String SEARCH_COMPONENT_CORPUS = "searchComponentCorpus";
    static final String GET_COMPONENT_AUTHORING_CONTEXT = "getComponentAuthoringContext";
    static final String GET_MANIFEST_SLICE = "getManifestSlice";
    static final String SEARCH_CONFIG_PATH_DOCS = "searchConfigPathDocs";
    static final String SEARCH_EXAMPLES = "searchExamples";
    static final String SEARCH_SCHEMA_FIELDS = "searchSchemaFields";

    private final Map<String, AgenticAuthoringToolExecutor> executors;

    public AgenticAuthoringToolRegistry(AgenticAuthoringResourceDiscoveryService resourceDiscoveryService) {
        this(resourceDiscoveryService, null, null, null, new ObjectMapper());
    }

    public AgenticAuthoringToolRegistry(
            AgenticAuthoringResourceDiscoveryService resourceDiscoveryService,
            ContextRetrievalService contextRetrievalService,
            AgenticAuthoringManifestService manifestService,
            SchemaRetrievalService schemaRetrievalService,
            ObjectMapper objectMapper) {
        Map<String, AgenticAuthoringToolExecutor> registered = new LinkedHashMap<>();
        register(registered, new SearchApiResourcesToolExecutor(resourceDiscoveryService));
        register(registered, new SearchComponentCorpusToolExecutor(contextRetrievalService));
        register(registered, new GetComponentAuthoringContextToolExecutor(contextRetrievalService));
        register(registered, new GetManifestSliceToolExecutor(manifestService, objectMapper));
        register(registered, new SearchConfigPathDocsToolExecutor(contextRetrievalService));
        register(registered, new SearchExamplesToolExecutor(contextRetrievalService));
        register(registered, new SearchSchemaFieldsToolExecutor(schemaRetrievalService, objectMapper));
        this.executors = Map.copyOf(registered);
    }

    List<AgenticAuthoringToolDefinition> definitions() {
        return executors.values().stream()
                .map(AgenticAuthoringToolExecutor::definition)
                .toList();
    }

    AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call) {
        return execute(call, null);
    }

    AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
        String toolName = call == null ? "" : call.name();
        return AgenticAuthoringToolResult.failure(
                toolName,
                "tool-phase-required",
                "Tool execution requires an explicit authoring phase.");
    }

    AgenticAuthoringToolResult execute(
            AgenticAuthoringToolCall call,
            AiPrincipalContext principalContext,
            String phase) {
        if (call == null || call.name() == null || call.name().isBlank()) {
            return AgenticAuthoringToolResult.failure("", "tool-name-required", "Tool name is required.");
        }
        if (phase == null || phase.isBlank()) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-phase-required",
                    "Tool execution requires an explicit authoring phase.");
        }
        AgenticAuthoringToolExecutor executor = executors.get(call.name());
        if (executor == null) {
            return AgenticAuthoringToolResult.failure(call.name(), "tool-not-found", "Tool is not registered.");
        }
        AgenticAuthoringToolDefinition definition = executor.definition();
        if (!definition.allowedRoutes().contains(call.routeClass())) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-route-not-allowed",
                    "Tool is not allowed for route " + safeRoute(call.routeClass()) + ".");
        }
        if (definition.allowedPhases() != null
                && !definition.allowedPhases().isEmpty()
                && !definition.allowedPhases().contains(phase)) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-phase-not-allowed",
                    "Tool is not allowed for phase " + phase + ".");
        }
        try {
            return executor.execute(call, principalContext);
        } catch (Exception ex) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-execution-failed",
                    ex.getMessage() != null ? ex.getMessage() : "Tool execution failed.");
        }
    }

    private void register(
            Map<String, AgenticAuthoringToolExecutor> target,
            AgenticAuthoringToolExecutor executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        target.put(executor.definition().name(), executor);
    }

    private static String safeRoute(String routeClass) {
        return routeClass == null || routeClass.isBlank() ? "unknown" : routeClass;
    }

    private static final class SearchApiResourcesToolExecutor implements AgenticAuthoringToolExecutor {

        private static final AgenticAuthoringToolDefinition DEFINITION = new AgenticAuthoringToolDefinition(
                SEARCH_API_RESOURCES,
                Set.of("component_authoring", "shared_rule_authoring", "mixed", "needs_clarification", "advisory_authoring"),
                "praxis-config-starter:/api/praxis/config/ai/authoring/resource-candidates",
                "read_only",
                "safe_grounding",
                "safe_event_projection_only");

        private final AgenticAuthoringResourceDiscoveryService resourceDiscoveryService;

        private SearchApiResourcesToolExecutor(AgenticAuthoringResourceDiscoveryService resourceDiscoveryService) {
            this.resourceDiscoveryService = Objects.requireNonNull(
                    resourceDiscoveryService,
                    "resourceDiscoveryService must not be null");
        }

        @Override
        public AgenticAuthoringToolDefinition definition() {
            return DEFINITION;
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call) {
            return execute(call, null);
        }

        @Override
        public AgenticAuthoringToolResult execute(
                AgenticAuthoringToolCall call,
                AiPrincipalContext principalContext) {
            if (!(call.payload() instanceof AgenticAuthoringResourceCandidatesRequest request)) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-payload-invalid",
                        "searchApiResources requires AgenticAuthoringResourceCandidatesRequest payload.");
            }
            AgenticAuthoringResourceCandidatesResult result = resourceDiscoveryService.search(request, principalContext);
            return AgenticAuthoringToolResult.success(
                    call.name(),
                    result,
                    Map.of(
                            "candidateCount", result.candidates() != null ? result.candidates().size() : 0,
                            "artifactKind", result.artifactKind() != null ? result.artifactKind() : "",
                            "retrievalQuery", result.retrievalQuery() != null ? result.retrievalQuery() : "",
                            "retrievalSource", AgenticAuthoringCandidateProvenancePolicy.retrievalSource(result.candidates())));
        }
    }

    private abstract static class ComponentCorpusToolExecutor implements AgenticAuthoringToolExecutor {

        private final AgenticAuthoringToolDefinition definition;
        protected final ContextRetrievalService contextRetrievalService;

        private ComponentCorpusToolExecutor(String name, ContextRetrievalService contextRetrievalService) {
            this.definition = new AgenticAuthoringToolDefinition(
                    name,
                    Set.of("component_authoring", "shared_rule_authoring", "mixed", "needs_clarification", "advisory_authoring"),
                    "praxis-config-starter:vector_store/component-corpus",
                    "read_only",
                    "safe_grounding",
                    "safe_event_projection_only");
            this.contextRetrievalService = contextRetrievalService;
        }

        @Override
        public AgenticAuthoringToolDefinition definition() {
            return definition;
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call) {
            return execute(call, null);
        }

        protected AgenticAuthoringToolResult unavailable(AgenticAuthoringToolCall call) {
            return AgenticAuthoringToolResult.failure(
                    call.name(),
                    "tool-service-unavailable",
                    call.name() + " requires ContextRetrievalService.");
        }

        protected CorpusToolRequest request(AgenticAuthoringToolCall call) {
            if (call.payload() instanceof CorpusToolRequest request) {
                return request;
            }
            return null;
        }

        protected AgenticAuthoringToolResult result(
                AgenticAuthoringToolCall call,
                CorpusToolRequest request,
                AiPrincipalContext principalContext,
                String forcedChunkKind,
                String defaultQuery) {
            if (contextRetrievalService == null) {
                return unavailable(call);
            }
            if (request == null) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-payload-invalid",
                        call.name() + " requires CorpusToolRequest payload.");
            }
            String query = firstNonBlank(request.query(), defaultQuery, request.componentId(), request.configPath());
            String chunkKind = firstNonBlank(forcedChunkKind, request.chunkKind());
            List<ContextRetrievalService.ComponentCorpusEvidence> evidence =
                    contextRetrievalService.searchComponentCorpus(
                            query,
                            request.componentId(),
                            chunkKind,
                            safeLimit(request.limit()),
                            firstNonBlank(request.tenantId(), principalContext != null ? principalContext.tenantId() : null),
                            firstNonBlank(request.environment(), principalContext != null ? principalContext.environment() : null),
                            request.releaseId());
            return AgenticAuthoringToolResult.success(
                    call.name(),
                    evidence,
                    Map.of(
                            "evidenceCount", evidence.size(),
                            "componentId", safeText(request.componentId()),
                            "chunkKind", safeText(chunkKind),
                            "releaseId", safeText(request.releaseId()),
                            "sourceRefs", evidence.stream()
                                    .map(ContextRetrievalService.ComponentCorpusEvidence::sourcePointer)
                                    .filter(sourceRef -> sourceRef != null && !sourceRef.isBlank())
                                    .limit(6)
                                    .toList()));
        }
    }

    private static final class SearchComponentCorpusToolExecutor extends ComponentCorpusToolExecutor {
        private SearchComponentCorpusToolExecutor(ContextRetrievalService contextRetrievalService) {
            super(SEARCH_COMPONENT_CORPUS, contextRetrievalService);
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
            return result(call, request(call), principalContext, null, "component corpus");
        }
    }

    private static final class GetComponentAuthoringContextToolExecutor extends ComponentCorpusToolExecutor {
        private GetComponentAuthoringContextToolExecutor(ContextRetrievalService contextRetrievalService) {
            super(GET_COMPONENT_AUTHORING_CONTEXT, contextRetrievalService);
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
            CorpusToolRequest request = request(call);
            return result(call, request, principalContext, null, "authoring manifest capabilities examples");
        }
    }

    private static final class SearchConfigPathDocsToolExecutor extends ComponentCorpusToolExecutor {
        private SearchConfigPathDocsToolExecutor(ContextRetrievalService contextRetrievalService) {
            super(SEARCH_CONFIG_PATH_DOCS, contextRetrievalService);
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
            CorpusToolRequest request = request(call);
            String query = request != null
                    ? firstNonBlank(request.query(), request.configPath(), "config path docs")
                    : "config path docs";
            CorpusToolRequest normalized = request == null ? null : request.withQuery(query);
            return result(call, normalized, principalContext, null, query);
        }
    }

    private static final class SearchExamplesToolExecutor extends ComponentCorpusToolExecutor {
        private SearchExamplesToolExecutor(ContextRetrievalService contextRetrievalService) {
            super(SEARCH_EXAMPLES, contextRetrievalService);
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
            return result(call, request(call), principalContext, "recipe", "component examples recipes");
        }
    }

    private static final class GetManifestSliceToolExecutor implements AgenticAuthoringToolExecutor {

        private static final AgenticAuthoringToolDefinition DEFINITION = new AgenticAuthoringToolDefinition(
                GET_MANIFEST_SLICE,
                Set.of("component_authoring", "shared_rule_authoring", "mixed", "needs_clarification", "advisory_authoring"),
                "praxis-config-starter:ai_registry/authoringManifest",
                "read_only",
                "safe_grounding",
                "safe_event_projection_only");

        private final AgenticAuthoringManifestService manifestService;
        private final ObjectMapper objectMapper;

        private GetManifestSliceToolExecutor(
                AgenticAuthoringManifestService manifestService,
                ObjectMapper objectMapper) {
            this.manifestService = manifestService;
            this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        }

        @Override
        public AgenticAuthoringToolDefinition definition() {
            return DEFINITION;
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call) {
            return execute(call, null);
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
            if (manifestService == null) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-service-unavailable",
                        "getManifestSlice requires AgenticAuthoringManifestService.");
            }
            if (!(call.payload() instanceof ManifestSliceToolRequest request)) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-payload-invalid",
                        "getManifestSlice requires ManifestSliceToolRequest payload.");
            }
            JsonNode manifest = manifestService.getManifest(request.componentId());
            JsonNode slice = sliceManifest(manifest, request);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("componentId", safeText(request.componentId()));
            payload.put("manifestVersion", text(manifest, "manifestVersion"));
            payload.put("sliceKind", firstNonBlank(request.sliceKind(), "manifest"));
            payload.put("operationId", safeText(request.operationId()));
            payload.set("evidence", slice);
            payload.put("sourceRef", "ai_registry:" + safeText(request.componentId()) + ":authoringManifest");
            return AgenticAuthoringToolResult.success(
                    call.name(),
                    payload,
                    Map.of(
                            "componentId", safeText(request.componentId()),
                            "operationId", safeText(request.operationId()),
                            "sliceKind", firstNonBlank(request.sliceKind(), "manifest")));
        }

        private JsonNode sliceManifest(JsonNode manifest, ManifestSliceToolRequest request) {
            String operationId = request.operationId();
            if (operationId != null && !operationId.isBlank()) {
                for (JsonNode operation : manifest.path("operations")) {
                    if (operationId.equals(text(operation, "operationId"))) {
                        return operation.deepCopy();
                    }
                }
                return objectMapper.createObjectNode().put("status", "not-found");
            }
            String sliceKind = firstNonBlank(request.sliceKind(), "manifest");
            return switch (sliceKind) {
                case "operations" -> limitedArray(manifest.path("operations"), request.limit());
                case "editableTargets" -> limitedArray(manifest.path("editableTargets"), request.limit());
                case "validators" -> limitedArray(manifest.path("validators"), request.limit());
                default -> manifest.deepCopy();
            };
        }

        private ArrayNode limitedArray(JsonNode source, Integer limit) {
            ArrayNode array = objectMapper.createArrayNode();
            int max = safeLimit(limit);
            if (source != null && source.isArray()) {
                for (JsonNode item : source) {
                    if (array.size() >= max) {
                        break;
                    }
                    array.add(item.deepCopy());
                }
            }
            return array;
        }
    }

    private static final class SearchSchemaFieldsToolExecutor implements AgenticAuthoringToolExecutor {

        private static final AgenticAuthoringToolDefinition DEFINITION = new AgenticAuthoringToolDefinition(
                SEARCH_SCHEMA_FIELDS,
                Set.of("component_authoring", "shared_rule_authoring", "mixed", "needs_clarification", "advisory_authoring"),
                "praxis-metadata-starter:/schemas/filtered",
                "read_only",
                "safe_grounding",
                "safe_event_projection_only");

        private final SchemaRetrievalService schemaRetrievalService;
        private final ObjectMapper objectMapper;

        private SearchSchemaFieldsToolExecutor(
                SchemaRetrievalService schemaRetrievalService,
                ObjectMapper objectMapper) {
            this.schemaRetrievalService = schemaRetrievalService;
            this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        }

        @Override
        public AgenticAuthoringToolDefinition definition() {
            return DEFINITION;
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call) {
            return execute(call, null);
        }

        @Override
        public AgenticAuthoringToolResult execute(AgenticAuthoringToolCall call, AiPrincipalContext principalContext) {
            if (schemaRetrievalService == null) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-service-unavailable",
                        "searchSchemaFields requires SchemaRetrievalService.");
            }
            if (!(call.payload() instanceof SchemaFieldsToolRequest request)) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-payload-invalid",
                        "searchSchemaFields requires SchemaFieldsToolRequest payload.");
            }
            AiSchemaContext schemaContext = AiSchemaContext.builder()
                    .path(request.path())
                    .operation(request.operation())
                    .schemaType(request.schemaType())
                    .build();
            JsonNode schema = schemaRetrievalService.fetchSchema(schemaContext, request.requestBaseUrl());
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("path", safeText(request.path()));
            payload.put("operation", safeText(request.operation()));
            payload.put("schemaType", safeText(request.schemaType()));
            payload.put("sourceRef", "/schemas/filtered");
            payload.set("schema", schema != null ? schema : objectMapper.nullNode());
            return AgenticAuthoringToolResult.success(
                    call.name(),
                    payload,
                    Map.of(
                            "path", safeText(request.path()),
                            "operation", safeText(request.operation()),
                            "schemaType", safeText(request.schemaType()),
                            "schemaFound", schema != null));
        }
    }

    private static int safeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 5;
        }
        return Math.min(limit, 12);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }
}

record CorpusToolRequest(
        String query,
        String componentId,
        String chunkKind,
        String configPath,
        String tenantId,
        String environment,
        String releaseId,
        Integer limit) {

    CorpusToolRequest withQuery(String query) {
        return new CorpusToolRequest(
                query,
                componentId,
                chunkKind,
                configPath,
                tenantId,
                environment,
                releaseId,
                limit);
    }
}

record ManifestSliceToolRequest(
        String componentId,
        String operationId,
        String sliceKind,
        Integer limit) {
}

record SchemaFieldsToolRequest(
        String path,
        String operation,
        String schemaType,
        String query,
        String requestBaseUrl,
        Integer limit) {
}
