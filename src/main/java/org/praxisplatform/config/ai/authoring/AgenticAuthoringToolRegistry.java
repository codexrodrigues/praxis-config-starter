package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
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
    static final String DISCOVER_PRESENTATION_AFFORDANCES = "presentationAffordanceDiscovery";
    static final String RESOLVE_RUNTIME_RELATED_SURFACE = "resolveRuntimeRelatedSurface";

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
        this(
                resourceDiscoveryService,
                contextRetrievalService,
                manifestService,
                schemaRetrievalService,
                objectMapper,
                AgenticAuthoringPresentationAffordanceDiscoveryService.defaultService(objectMapper));
    }

    public AgenticAuthoringToolRegistry(
            AgenticAuthoringResourceDiscoveryService resourceDiscoveryService,
            ContextRetrievalService contextRetrievalService,
            AgenticAuthoringManifestService manifestService,
            SchemaRetrievalService schemaRetrievalService,
            ObjectMapper objectMapper,
            AgenticAuthoringPresentationAffordanceDiscoveryService presentationAffordanceDiscoveryService) {
        Map<String, AgenticAuthoringToolExecutor> registered = new LinkedHashMap<>();
        register(registered, new SearchApiResourcesToolExecutor(resourceDiscoveryService));
        register(registered, new SearchComponentCorpusToolExecutor(contextRetrievalService));
        register(registered, new GetComponentAuthoringContextToolExecutor(contextRetrievalService));
        register(registered, new GetManifestSliceToolExecutor(manifestService, objectMapper));
        register(registered, new SearchConfigPathDocsToolExecutor(contextRetrievalService));
        register(registered, new SearchExamplesToolExecutor(contextRetrievalService));
        register(registered, new SearchSchemaFieldsToolExecutor(schemaRetrievalService, objectMapper));
        register(registered, new PresentationAffordanceDiscoveryToolExecutor(
                presentationAffordanceDiscoveryService != null
                        ? presentationAffordanceDiscoveryService
                        : AgenticAuthoringPresentationAffordanceDiscoveryService.defaultService(objectMapper)));
        register(registered, new RuntimeRelatedSurfaceReadToolExecutor(objectMapper));
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
                case "presentationAffordances" -> manifestService.listPresentationAffordances(request.componentId());
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

    private static final class PresentationAffordanceDiscoveryToolExecutor implements AgenticAuthoringToolExecutor {

        private static final AgenticAuthoringToolDefinition DEFINITION = new AgenticAuthoringToolDefinition(
                DISCOVER_PRESENTATION_AFFORDANCES,
                Set.of("component_authoring", "mixed", "needs_clarification", "advisory_authoring"),
                "praxis-config-starter:ai-authoring/presentation-affordances",
                "read_only",
                "safe_grounding",
                "safe_event_projection_only");

        private final AgenticAuthoringPresentationAffordanceDiscoveryService discoveryService;

        private PresentationAffordanceDiscoveryToolExecutor(
                AgenticAuthoringPresentationAffordanceDiscoveryService discoveryService) {
            this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService must not be null");
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
            if (!(call.payload() instanceof PresentationAffordanceDiscoveryToolRequest request)) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-payload-invalid",
                        "presentationAffordanceDiscovery requires PresentationAffordanceDiscoveryToolRequest payload.");
            }
            String componentId = firstNonBlank(request.targetComponentId(), request.componentId());
            JsonNode payload = discoveryService.discover(request).orElse(null);
            if (payload == null) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "presentation-affordance-target-unsupported",
                        "No presentation affordance catalog is registered for target " + safeText(componentId) + ".");
            }
            return AgenticAuthoringToolResult.success(
                    call.name(),
                    payload,
                    Map.of(
                            "componentId", payload.path("componentId").asText(safeText(componentId)),
                            "targetKind", payload.path("targetKind").asText(""),
                            "dataType", payload.path("dataType").asText("unknown"),
                            "requiresTypeConfirmation", payload.path("requiresTypeConfirmation").asBoolean(false),
                            "affordanceCount", payload.path("affordances").isArray()
                                    ? payload.path("affordances").size()
                                    : 0,
                            "sourceRef", payload.path("sourceRef").asText("")));
        }
    }

    private static final class RuntimeRelatedSurfaceReadToolExecutor implements AgenticAuthoringToolExecutor {

        private static final AgenticAuthoringToolDefinition DEFINITION = new AgenticAuthoringToolDefinition(
                RESOLVE_RUNTIME_RELATED_SURFACE,
                Set.of("advisory_authoring", "component_authoring", "mixed", "needs_clarification"),
                Set.of("retrieveEvidence"),
                "praxis-config-starter:runtime-related-surface-read",
                "read_only",
                "governed_runtime_context_reconciliation",
                "safe_event_projection_only");

        private final ObjectMapper objectMapper;

        private RuntimeRelatedSurfaceReadToolExecutor(ObjectMapper objectMapper) {
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
            if (!(call.payload() instanceof RuntimeRelatedSurfaceReadToolRequest request)) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "tool-payload-invalid",
                        "resolveRuntimeRelatedSurface requires RuntimeRelatedSurfaceReadToolRequest payload.");
            }
            RuntimeSurfaceResolution resolution = resolve(request);
            if (!resolution.valid()) {
                return AgenticAuthoringToolResult.failure(call.name(), resolution.errorCode(), resolution.errorMessage());
            }
            String endpoint = endpoint(request.requestBaseUrl(), resolution.resourcePath());
            ObjectNode filter = objectMapper.createObjectNode();
            putFilterValue(filter, resolution.filterField(), resolution.selectedId());
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofMillis(15_000))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(filter)))
                        .build();
                HttpResponse<String> response = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(5_000))
                        .build()
                        .send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    return AgenticAuthoringToolResult.failure(
                            call.name(),
                            statusCode(response.statusCode()),
                            "Related surface read returned HTTP " + response.statusCode() + ".");
                }
                JsonNode body = objectMapper.readTree(response.body());
                int recordLimit = Math.min(safeLimit(request.limit()), 8);
                ArrayNode records = safeRecords(
                        extractRecords(body),
                        recordLimit,
                        resolution.allowedRecordFields());
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("schemaVersion", "praxis-runtime-related-surface-read.v1");
                payload.put("surfaceRef", resolution.surfaceRef());
                payload.put("resourcePath", resolution.resourcePath());
                payload.put("operation", "POST /filter");
                payload.put("filterField", resolution.filterField());
                payload.put("selectedId", resolution.selectedId());
                ObjectNode queryMapping = payload.putObject("queryMapping");
                queryMapping.put("sourceField", resolution.sourceField());
                queryMapping.put("targetFilterField", resolution.filterField());
                if (!resolution.targetPath().isBlank()) {
                    queryMapping.put("targetPath", resolution.targetPath());
                }
                queryMapping.put("valueSource", "selectionDigest.selectedIds[0]");
                ObjectNode selection = payload.putObject("selection");
                selection.put("sourceWidget", resolution.sourceWidget());
                selection.put("idField", resolution.sourceField());
                selection.put("selectedCount", 1);
                selection.put("selectedIdsRedacted", false);
                payload.set("projectionFields", textArray(resolution.allowedRecordFields()));
                payload.put("redactionApplied", true);
                payload.set("omittedFields", objectMapper.createArrayNode());
                payload.put("recordLimit", recordLimit);
                payload.put("recordCount", records.size());
                payload.put("truncated", false);
                payload.set("records", records);
                payload.put("sourceRef", resolution.resourcePath() + "/filter");
                payload.put("rawRuntimeValuesCopied", false);
                return AgenticAuthoringToolResult.success(
                        call.name(),
                        payload,
                        Map.of(
                                "surfaceRef", resolution.surfaceRef(),
                                "resourcePath", resolution.resourcePath(),
                                "filterField", resolution.filterField(),
                                "recordCount", records.size(),
                                "rawRuntimeValuesCopied", false));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "runtime-related-surface-read-interrupted",
                        "Related surface read was interrupted.");
            } catch (Exception ex) {
                return AgenticAuthoringToolResult.failure(
                        call.name(),
                        "runtime-related-surface-read-failed",
                        ex.getMessage() != null ? ex.getMessage() : "Related surface read failed.");
            }
        }

        private RuntimeSurfaceResolution resolve(RuntimeRelatedSurfaceReadToolRequest request) {
            if (request == null || request.runtimeConsultableContext() == null
                    || !request.runtimeConsultableContext().isObject()) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-context-required",
                        "Runtime consultable context is required.");
            }
            String surfaceRef = firstNonBlank(request.surfaceRef(), firstText(request.runtimeConsultableContext().path("availableSurfaces")));
            if (surfaceRef == null) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-required",
                        "A runtime surfaceRef is required.");
            }
            if (request.requestBaseUrl() == null || request.requestBaseUrl().isBlank()) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-base-url-required",
                        "A requestBaseUrl is required to read backend related surfaces.");
            }
            if (!hasActiveSurfaceRef(request.runtimeConsultableContext(), surfaceRef)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-not-active",
                        "The requested surface is not declared as active in runtime affordances.");
            }
            JsonNode relation = findRelation(request.runtimeConsultableContext(), surfaceRef);
            if (relation == null) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-relation-not-declared",
                        "The requested surface is not declared in relationSurfaceRefs.");
            }
            String operationId = text(relation, "operationId");
            if (operationId == null || operationId.isBlank()) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-operation-not-declared",
                        "The related surface does not declare a governed operationId.");
            }
            if (!hasActiveOperationRef(request.runtimeConsultableContext(), operationId)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-operation-not-active",
                        "The related surface operation is not declared as active in runtime affordances.");
            }
            String resourcePath = firstNonBlank(
                    text(relation.path("target"), "resourcePath"),
                    text(relation, "targetResourcePath"),
                    findTargetResourcePath(request.runtimeConsultableContext(), relation, surfaceRef));
            String targetWidget = declaredTargetWidgetRef(relation);
            if (!declaresRuntimeSurfaceInstanceRef(relation)
                    && targetWidget != null
                    && !hasWidget(request.runtimeConsultableContext(), targetWidget)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-target-widget-not-found",
                        "The related surface targetWidget is not present in the grounded runtime context.");
            }
            if (resourcePath == null) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-resource-not-declared",
                        "The related surface does not declare a governed target resourcePath.");
            }
            JsonNode queryMapping = relation.path("queryMapping");
            String sourceField = text(queryMapping, "sourceField");
            String targetFilterField = text(queryMapping, "targetFilterField");
            if (sourceField == null || sourceField.isBlank()
                    || targetFilterField == null || targetFilterField.isBlank()) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-query-mapping-required",
                        "relationSurfaceRef must declare queryMapping.sourceField and queryMapping.targetFilterField.");
            }
            if (!declaresRuntimeSurfaceInstanceRef(relation)
                    && declaredTargetWidgetRef(relation) == null
                    && resourcePathAmbiguous(request.runtimeConsultableContext(), resourcePath)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-resource-path-ambiguous",
                        "The related surface resourcePath matches multiple runtime components; runtimeSurfaceInstanceRef or targetWidget is required.");
            }
            JsonNode targetComponent = findTargetComponent(request.runtimeConsultableContext(), relation, resourcePath, surfaceRef);
            String targetPath = text(queryMapping, "targetPath");
            if (targetComponent == null && declaresRuntimeSurfaceInstanceRef(relation)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-instance-not-found",
                        "The related surface runtimeSurfaceInstanceRef is not present in the grounded runtime context.");
            }
            if (targetComponent == null && resourcePathAmbiguous(request.runtimeConsultableContext(), resourcePath)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-resource-path-ambiguous",
                        "The related surface resourcePath matches multiple runtime components; runtimeSurfaceInstanceRef or targetWidget is required.");
            }
            if (targetPath != null && !targetPath.isBlank() && !targetPath.matches("^filters\\.[A-Za-z_][A-Za-z0-9_]*$")) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-target-path-invalid",
                        "queryMapping.targetPath must be filters.<safeIdentifier>.");
            }
            boolean targetFilterDeclaredByQueryPath = targetPath != null
                    && !targetPath.isBlank()
                    && ("filters." + targetFilterField).equals(targetPath);
            if (targetPath != null && !targetPath.isBlank() && !targetFilterDeclaredByQueryPath) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-target-path-filter-mismatch",
                        "queryMapping.targetPath identifier must match queryMapping.targetFilterField.");
            }
            if (targetComponent != null
                    && !targetFilterDeclaredByQueryPath
                    && !hasText(targetComponent.path("snapshot").path("schemaFieldRefs"), targetFilterField)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-target-filter-field-not-declared",
                        "queryMapping.targetFilterField must be declared by queryMapping.targetPath or by the target surface schemaFieldRefs.");
            }
            if (targetComponent == null && !targetFilterDeclaredByQueryPath) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-target-filter-field-not-declared",
                        "queryMapping.targetFilterField must be declared by queryMapping.targetPath when target surface schemaFieldRefs are unavailable.");
            }
            JsonNode selectionDigest = findSelectionDigest(request.runtimeConsultableContext(), relation, surfaceRef);
            if (selectionDigest == null || !selectionDigest.isObject()) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-selection-required",
                        "A selected source record is required to read the related surface.");
            }
            if (selectionCount(selectionDigest) > 1) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-multiple-selection-unsupported",
                        "Related surface read currently requires a single selected source record.");
            }
            String selectedId = firstText(selectionDigest.path("selectedIds"));
            String idField = text(selectionDigest, "idField");
            if (selectedId == null || idField == null || idField.isBlank()) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-selection-id-required",
                        "selectionDigest must include selectedIds and idField.");
            }
            if (!sourceField.equals(idField)) {
                return RuntimeSurfaceResolution.invalid(
                        "runtime-surface-source-field-mismatch",
                        "queryMapping.sourceField must match selectionDigest.idField.");
            }
            return RuntimeSurfaceResolution.valid(
                    surfaceRef,
                    normalizeResourcePath(resourcePath),
                    targetFilterField,
                    selectedId,
                    sourceField,
                    targetPath,
                    firstNonBlank(text(relation.path("source"), "widget"), text(relation, "sourceWidget")),
                    schemaFieldRefs(targetComponent));
        }

        private boolean hasActiveSurfaceRef(JsonNode runtimeContext, String surfaceRef) {
            for (JsonNode component : runtimeContext.path("components")) {
                if (hasText(component.path("affordances").path("activeSurfaceRefs"), surfaceRef)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasActiveOperationRef(JsonNode runtimeContext, String operationId) {
            for (JsonNode component : runtimeContext.path("components")) {
                JsonNode affordances = component.path("affordances");
                if (hasText(affordances.path("activeActionRefs"), operationId)
                        || hasText(affordances.path("activeOperationRefs"), operationId)) {
                    return true;
                }
            }
            return false;
        }

        private JsonNode findRelation(JsonNode runtimeContext, String surfaceRef) {
            for (JsonNode component : runtimeContext.path("components")) {
                for (JsonNode relation : component.path("snapshot").path("relationSurfaceRefs")) {
                    if (surfaceRef.equals(text(relation, "id"))
                            || surfaceRef.equals(text(relation, "surfaceRef"))
                            || surfaceRef.equals(text(relation, "targetSurface"))
                            || surfaceRef.equals(text(relation, "targetWidget"))
                            || surfaceRef.equals(text(relation.path("target"), "widget"))) {
                        return relation;
                    }
                }
            }
            return null;
        }

        private String findTargetResourcePath(JsonNode runtimeContext, JsonNode relation, String surfaceRef) {
            String targetWidget = targetWidgetRef(relation, surfaceRef);
            if (targetWidget == null) {
                return null;
            }
            for (JsonNode component : runtimeContext.path("components")) {
                if (!targetWidget.equals(text(component.path("identity"), "widgetKey"))) {
                    continue;
                }
                String resourcePath = text(component.path("refs"), "resourcePath");
                if (resourcePath != null) {
                    return resourcePath;
                }
            }
            return null;
        }

        private String targetWidgetRef(JsonNode relation, String surfaceRef) {
            return firstNonBlank(
                    declaredTargetWidgetRef(relation),
                    surfaceRef);
        }

        private String declaredTargetWidgetRef(JsonNode relation) {
            return firstNonBlank(
                    text(relation.path("target"), "widget"),
                    text(relation, "targetWidget"),
                    text(relation, "targetSurface"));
        }

        private boolean hasWidget(JsonNode runtimeContext, String widgetKey) {
            for (JsonNode component : runtimeContext.path("components")) {
                if (widgetKey.equals(text(component.path("identity"), "widgetKey"))) {
                    return true;
                }
            }
            return false;
        }

        private JsonNode findTargetComponent(
                JsonNode runtimeContext,
                JsonNode relation,
                String resourcePath,
                String surfaceRef) {
            String runtimeSurfaceInstanceRef = runtimeSurfaceInstanceRef(relation);
            String targetWidget = declaredTargetWidgetRef(relation);
            String normalizedResourcePath = normalizeResourcePath(resourcePath);
            JsonNode resourceMatch = null;
            for (JsonNode component : runtimeContext.path("components")) {
                if (runtimeSurfaceInstanceRef != null
                        && runtimeSurfaceInstanceRef.equals(text(component.path("refs"), "runtimeSurfaceInstanceRef"))) {
                    return component;
                }
                String widgetKey = text(component.path("identity"), "widgetKey");
                if (targetWidget != null && targetWidget.equals(widgetKey)) {
                    return component;
                }
                String componentResourcePath = normalizeResourcePath(text(component.path("refs"), "resourcePath"));
                if (resourceMatch == null && !componentResourcePath.isBlank()
                        && componentResourcePath.equals(normalizedResourcePath)) {
                    resourceMatch = component;
                }
                if (resourceMatch == null && surfaceRef.equals(widgetKey)) {
                    resourceMatch = component;
                }
            }
            return resourceMatch;
        }

        private boolean declaresRuntimeSurfaceInstanceRef(JsonNode relation) {
            return runtimeSurfaceInstanceRef(relation) != null;
        }

        private String runtimeSurfaceInstanceRef(JsonNode relation) {
            return firstNonBlank(
                    text(relation, "runtimeSurfaceInstanceRef"),
                    text(relation, "targetRuntimeSurfaceInstanceRef"),
                    text(relation.path("target"), "runtimeSurfaceInstanceRef"));
        }

        private boolean resourcePathAmbiguous(JsonNode runtimeContext, String resourcePath) {
            String normalizedResourcePath = normalizeResourcePath(resourcePath);
            if (normalizedResourcePath.isBlank()) {
                return false;
            }
            int matches = 0;
            for (JsonNode component : runtimeContext.path("components")) {
                String componentResourcePath = normalizeResourcePath(text(component.path("refs"), "resourcePath"));
                if (componentResourcePath.equals(normalizedResourcePath)) {
                    matches++;
                    if (matches > 1) {
                        return true;
                    }
                }
            }
            return false;
        }

        private JsonNode findSelectionDigest(JsonNode runtimeContext, JsonNode relation, String surfaceRef) {
            String sourceWidget = firstNonBlank(text(relation.path("source"), "widget"), text(relation, "sourceWidget"));
            JsonNode fallback = null;
            for (JsonNode component : runtimeContext.path("components")) {
                JsonNode selectionDigest = component.path("snapshot").path("selectionDigest");
                if (!hasSelectionIds(selectionDigest)) {
                    continue;
                }
                String widgetKey = text(component.path("identity"), "widgetKey");
                if (sourceWidget == null || sourceWidget.equals(widgetKey)) {
                    return selectionDigest;
                }
                if (fallback == null && hasText(component.path("affordances").path("activeSurfaceRefs"), surfaceRef)) {
                    fallback = selectionDigest;
                }
            }
            return fallback;
        }

        private boolean hasSelectionIds(JsonNode selectionDigest) {
            return selectionDigest != null
                    && selectionDigest.isObject()
                    && hasText(selectionDigest.path("selectedIds"), null);
        }

        private int selectionCount(JsonNode selectionDigest) {
            JsonNode selectedIds = selectionDigest == null ? null : selectionDigest.path("selectedIds");
            if (selectedIds == null || !selectedIds.isArray()) {
                return 0;
            }
            int count = 0;
            for (JsonNode selectedId : selectedIds) {
                if (!selectedId.asText("").isBlank()) {
                    count++;
                }
            }
            return count;
        }

        private boolean hasText(JsonNode values, String expected) {
            if (values == null || !values.isArray()) {
                return false;
            }
            for (JsonNode value : values) {
                String text = value.asText("");
                if (expected == null && !text.isBlank()) {
                    return true;
                }
                if (expected != null && expected.equals(text)) {
                    return true;
                }
            }
            return false;
        }

        private String endpoint(String requestBaseUrl, String resourcePath) {
            String base = requestBaseUrl.replaceAll("/+$", "");
            return base + "/api/" + resourcePath.replaceAll("^/+", "").replaceAll("/+$", "") + "/filter";
        }

        private String normalizeResourcePath(String resourcePath) {
            String normalized = resourcePath == null ? "" : resourcePath.trim().replaceAll("^/+", "").replaceAll("/+$", "");
            if (normalized.startsWith("api/")) {
                return normalized.substring(4);
            }
            return normalized;
        }

        private JsonNode extractRecords(JsonNode body) {
            JsonNode data = body == null ? null : body.path("data");
            if (data != null && data.isArray()) {
                return data;
            }
            if (data != null && data.path("content").isArray()) {
                return data.path("content");
            }
            if (data != null && data.path("_embedded").isObject()) {
                for (JsonNode value : data.path("_embedded")) {
                    if (value.isArray()) {
                        return value;
                    }
                }
            }
            if (body != null && body.path("content").isArray()) {
                return body.path("content");
            }
            return body != null && body.isArray() ? body : objectMapper.createArrayNode();
        }

        private Set<String> schemaFieldRefs(JsonNode component) {
            Set<String> refs = new LinkedHashSet<>();
            if (component == null) {
                return refs;
            }
            JsonNode schemaFieldRefs = component.path("snapshot").path("schemaFieldRefs");
            if (!schemaFieldRefs.isArray()) {
                return refs;
            }
            for (JsonNode ref : schemaFieldRefs) {
                String field = ref.asText("");
                if (!field.isBlank()) {
                    refs.add(field);
                }
            }
            return refs;
        }

        private ArrayNode textArray(Set<String> values) {
            ArrayNode array = objectMapper.createArrayNode();
            if (values == null) {
                return array;
            }
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    array.add(value);
                }
            }
            return array;
        }

        private ArrayNode safeRecords(JsonNode source, int limit, Set<String> allowedFields) {
            ArrayNode records = objectMapper.createArrayNode();
            if (source == null || !source.isArray()) {
                return records;
            }
            for (JsonNode item : source) {
                if (records.size() >= limit) {
                    break;
                }
                JsonNode record = item.path("content").isObject() ? item.path("content") : item;
                ObjectNode safe = objectMapper.createObjectNode();
                copySafeScalars(record, safe, 12, allowedFields);
                if (!safe.isEmpty()) {
                    records.add(safe);
                }
            }
            return records;
        }

        private void copySafeScalars(JsonNode source, ObjectNode target, int limit, Set<String> allowedFields) {
            if (source == null || !source.isObject()) {
                return;
            }
            int count = 0;
            for (var field : source.properties()) {
                if (count >= limit) {
                    break;
                }
                String fieldName = field.getKey();
                JsonNode value = field.getValue();
                if (allowedFields != null && !allowedFields.isEmpty() && !allowedFields.contains(fieldName)) {
                    continue;
                }
                if (!isSafeRecordField(fieldName, value)) {
                    continue;
                }
                copySafe(source, target, fieldName);
                count++;
            }
        }

        private boolean isSafeRecordField(String fieldName, JsonNode value) {
            if (fieldName == null || fieldName.isBlank() || value == null || value.isNull()) {
                return false;
            }
            String normalized = fieldName.toLowerCase();
            if (normalized.contains("cpf")
                    || normalized.contains("cnpj")
                    || normalized.contains("email")
                    || normalized.contains("telefone")
                    || normalized.contains("phone")
                    || normalized.contains("salary")
                    || normalized.contains("salario")
                    || normalized.contains("password")
                    || normalized.contains("secret")
                    || normalized.contains("token")) {
                return false;
            }
            return value.isTextual() || value.isBoolean() || value.isInt() || value.isLong();
        }

        private void copySafe(JsonNode source, ObjectNode target, String field) {
            JsonNode value = source == null ? null : source.path(field);
            if (value == null || value.isMissingNode() || value.isNull()) {
                return;
            }
            if (value.isTextual()) {
                target.put(field, value.asText());
            } else if (value.isBoolean()) {
                target.put(field, value.asBoolean());
            } else if (value.isInt() || value.isLong()) {
                target.put(field, value.asLong());
            }
        }

        private String firstText(JsonNode array) {
            if (array == null || !array.isArray()) {
                return null;
            }
            for (JsonNode item : array) {
                String value = item.asText("");
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }

        private String statusCode(int statusCode) {
            if (statusCode == 401 || statusCode == 403) {
                return "runtime-related-surface-access-denied";
            }
            if (statusCode == 404) {
                return "runtime-related-surface-not-found";
            }
            return "runtime-related-surface-http-error";
        }

        private void putFilterValue(ObjectNode filter, String field, String value) {
            if (value != null && value.matches("-?\\d+")) {
                try {
                    filter.put(field, Long.parseLong(value));
                    return;
                } catch (NumberFormatException ignored) {
                    // Fall through to textual value when the integer does not fit in a long.
                }
            }
            filter.put(field, safeText(value));
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

record RuntimeRelatedSurfaceReadToolRequest(
        JsonNode runtimeConsultableContext,
        String surfaceRef,
        String resourcePath,
        String filterField,
        String requestBaseUrl,
        Integer limit) {
}

record RuntimeSurfaceResolution(
        boolean valid,
        String surfaceRef,
        String resourcePath,
        String filterField,
        String selectedId,
        String sourceField,
        String targetPath,
        String sourceWidget,
        Set<String> allowedRecordFields,
        String errorCode,
        String errorMessage) {

    static RuntimeSurfaceResolution valid(
            String surfaceRef,
            String resourcePath,
            String filterField,
            String selectedId,
            String sourceField,
            String targetPath,
            String sourceWidget,
            Set<String> allowedRecordFields) {
        return new RuntimeSurfaceResolution(
                true,
                surfaceRef,
                resourcePath,
                filterField,
                selectedId,
                sourceField == null ? "" : sourceField,
                targetPath == null ? "" : targetPath,
                sourceWidget == null ? "" : sourceWidget,
                allowedRecordFields == null ? Set.of() : Set.copyOf(allowedRecordFields),
                null,
                null);
    }

    static RuntimeSurfaceResolution invalid(String errorCode, String errorMessage) {
        return new RuntimeSurfaceResolution(false, null, null, null, null, null, null, null, Set.of(), errorCode, errorMessage);
    }
}
