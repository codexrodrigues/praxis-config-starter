package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.config.service.AiProviderCallException;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderInvocationMetrics;
import org.praxisplatform.config.service.AiProviderInvocationTelemetry;
import org.praxisplatform.config.service.AiProviderInvocationTrace;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.DomainCatalogPromptContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

public class AgenticAuthoringLlmIntentResolverService {

    private static final Logger log = LoggerFactory.getLogger(AgenticAuthoringLlmIntentResolverService.class);
    private static final String SYSTEM_PROMPT_TEMPLATE_ID = "ai-authoring/page-builder-system-prompt.v1.md";
    private static final String SYSTEM_PROMPT_TEMPLATE = loadSystemPromptTemplate();
    private static final int MAX_ASSISTANT_MESSAGE_CHARS = 700;
    private static final int MAX_PLATFORM_GUIDANCE_CONFIRMATION_TOKENS = 700;
    private static final int MAX_DECLARED_CLIENT_ACTION_INTENT_TOKENS = 500;
    private static final int MAX_TARGETED_COMPONENT_INTENT_TOKENS = 900;
    private static final int MAX_TARGETED_COMPONENT_INTENT_ATTEMPTS = 2;
    private static final int MAX_TARGETED_COMPONENT_ATTEMPT_TIMEOUT_SECONDS = 8;
    private static final int MAX_FAST_INTENT_RESOLUTION_TOKENS = 1800;
    private static final int MAX_INTENT_RESOLUTION_TOKENS = 4096;
    private static final int DEFAULT_FAST_INTENT_TIMEOUT_SECONDS = 12;
    private static final int DEFAULT_FULL_INTENT_TIMEOUT_SECONDS = 30;
    private static final int MAX_LIVE_OPTION_REFINEMENT_TIMEOUT_SECONDS = 24;
    private static final String DEFAULT_LIVE_OPTION_REFINEMENT_OPENAI_MODEL = "gpt-5.6-luna";

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final DomainCatalogPromptContextService domainCatalogPromptContextService;
    private final int fastIntentTimeoutSeconds;
    private final int fullIntentTimeoutSeconds;
    private final String liveOptionRefinementOpenAiModel;

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this(providerManagementService, objectMapper, null);
    }

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService) {
        this(
                providerManagementService,
                objectMapper,
                domainCatalogPromptContextService,
                DEFAULT_FAST_INTENT_TIMEOUT_SECONDS,
                DEFAULT_FULL_INTENT_TIMEOUT_SECONDS,
                DEFAULT_LIVE_OPTION_REFINEMENT_OPENAI_MODEL);
    }

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService,
            int fastIntentTimeoutSeconds,
            int fullIntentTimeoutSeconds) {
        this(
                providerManagementService,
                objectMapper,
                domainCatalogPromptContextService,
                fastIntentTimeoutSeconds,
                fullIntentTimeoutSeconds,
                DEFAULT_LIVE_OPTION_REFINEMENT_OPENAI_MODEL);
    }

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService,
            int fastIntentTimeoutSeconds,
            int fullIntentTimeoutSeconds,
            String liveOptionRefinementOpenAiModel) {
        this.providerManagementService = Objects.requireNonNull(providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.domainCatalogPromptContextService = domainCatalogPromptContextService;
        this.fastIntentTimeoutSeconds = positiveOrDefault(
                fastIntentTimeoutSeconds,
                DEFAULT_FAST_INTENT_TIMEOUT_SECONDS);
        this.fullIntentTimeoutSeconds = positiveOrDefault(
                fullIntentTimeoutSeconds,
                DEFAULT_FULL_INTENT_TIMEOUT_SECONDS);
        this.liveOptionRefinementOpenAiModel = StringUtils.hasText(liveOptionRefinementOpenAiModel)
                ? liveOptionRefinementOpenAiModel.trim()
                : DEFAULT_LIVE_OPTION_REFINEMENT_OPENAI_MODEL;
    }

    public Optional<AgenticAuthoringLlmIntentResolution> resolve(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment) {
        if (request == null || effectivePrompt == null || effectivePrompt.isBlank()) {
            return Optional.empty();
        }
        List<AgenticAuthoringCandidate> usableCandidates =
                candidateOptions == null ? List.of() : candidateOptions;
        List<AiProviderInvocationTelemetry> providerInvocations = new ArrayList<>();
        boolean liveOptionRefinement = hasLiveOptionRefinementContext(request);
        // Resource, field and current candidates are already governed before this terminal
        // classifier runs. Rebuilding the broader domain/RAG prompt here is both redundant and
        // expensive, and the focused live-option prompt deliberately does not consume it.
        String governedDomainContext = liveOptionRefinement
                ? ""
                : governedDomainContext(
                        request,
                        effectivePrompt,
                        tenantId,
                        environment);
        try {
            Optional<AgenticAuthoringLlmIntentResolution> declaredClientActionIntent =
                    compactDeclaredClientActionIntent(
                            request,
                            request.userPrompt(),
                            tenantId,
                            userId,
                            environment,
                            providerInvocations);
            if (declaredClientActionIntent.isPresent()) {
                return declaredClientActionIntent.map(value -> withProviderInvocations(value, providerInvocations));
            }
            Optional<AgenticAuthoringLlmIntentResolution> platformGuidanceConfirmation =
                    compactPlatformGuidanceConfirmation(
                            request,
                            effectivePrompt,
                            target,
                            componentCapabilities,
                            governedDomainContext,
                            tenantId,
                            userId,
                            environment,
                            providerInvocations);
            if (platformGuidanceConfirmation.isPresent()) {
                return platformGuidanceConfirmation.map(value -> withProviderInvocations(value, providerInvocations));
            }
            Optional<AgenticAuthoringLlmIntentResolution> targetedComponentIntent =
                    compactTargetedComponentIntent(
                            request,
                            effectivePrompt,
                            target,
                            componentCapabilities,
                            tenantId,
                            userId,
                            environment,
                            providerInvocations);
            if (targetedComponentIntent.isPresent()) {
                return targetedComponentIntent.map(value -> withProviderInvocations(value, providerInvocations));
            }
            Optional<AgenticAuthoringLlmIntentResolution> fastResolution = fastIntentResolution(
                    request,
                    effectivePrompt,
                    currentPageSummary,
                    target,
                    usableCandidates,
                    componentCapabilities,
                    governedDomainContext,
                    tenantId,
                    userId,
                    environment,
                    providerInvocations);
            if (fastResolution.isPresent()) {
                return fastResolution.map(value -> withProviderInvocations(value, providerInvocations));
            }
            PromptInput promptInput = promptInput(
                    request,
                    effectivePrompt,
                    currentPageSummary,
                    target,
                    usableCandidates,
                    componentCapabilities,
                    governedDomainContext);
            JsonNode result = invokeJson(
                    "intent_full",
                    promptInput.prompt(),
                    AiJsonSchema.ofSchema(schema()),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(MAX_INTENT_RESOLUTION_TOKENS)
                            .timeoutSeconds(fullIntentTimeoutSeconds)
                            .build(),
                    tenantId,
                    userId,
                    environment,
                    providerInvocations);
            Optional<AgenticAuthoringLlmIntentResolution> fullResolution = toResolution(result);
            if (fullResolution.isPresent() && incompleteResolvedVisualization(fullResolution.get())) {
                JsonNode repairedResult = invokeJson(
                        "intent_full_visualization_repair",
                        fullVisualizationRepairPrompt(promptInput.prompt()),
                        AiJsonSchema.ofSchema(schema()),
                        AiCallConfig.builder()
                                .provider(request.provider())
                                .model(request.model())
                                .apiKey(request.apiKey())
                                .temperature(0.0d)
                                .maxTokens(MAX_INTENT_RESOLUTION_TOKENS)
                                .timeoutSeconds(fullIntentTimeoutSeconds)
                                .build(),
                        tenantId,
                        userId,
                        environment,
                        providerInvocations);
                Optional<AgenticAuthoringLlmIntentResolution> repaired = toResolution(repairedResult);
                if (repaired.isPresent() && !incompleteResolvedVisualization(repaired.get())) {
                    fullResolution = repaired.map(value -> withWarning(
                            value,
                            "llm-full-visualization-repair-used"));
                }
            }
            return fullResolution.map(value -> withProviderInvocations(value, providerInvocations));
        } catch (RuntimeException ex) {
            return Optional.of(withProviderInvocations(failedResolution(ex, request), providerInvocations));
        }
    }

    private boolean incompleteResolvedVisualization(AgenticAuthoringLlmIntentResolution resolution) {
        if (resolution == null
                || !resolution.resolved()
                || !"create".equals(valueOrDefault(resolution.operationKind(), ""))
                || !List.of("chart", "dashboard").contains(valueOrDefault(resolution.artifactKind(), ""))) {
            return false;
        }
        AgenticAuthoringVisualizationDecision decision = resolution.visualizationDecision();
        return decision == null
                || !StringUtils.hasText(decision.primaryComponent())
                || decision.axes() == null
                || decision.axes().isEmpty();
    }

    private String fullVisualizationRepairPrompt(String originalPrompt) {
        return originalPrompt + """

                The previous full resolution declared a chart or dashboard but omitted a complete visualizationDecision.
                Repair only that semantic omission. Return a complete intent object, not a patch. Preserve the selected
                governed resource, user objective and constraints. visualizationDecision must use a governed primary
                component and include at least one business-relevant grouping/time axis with metric semantics. When the
                selected candidate publishes analyticsFields, copy field and allowed aggregation names exactly from that
                governed catalog; do not replace them with conceptual aliases. Only when analyticsFields is absent may a
                conceptual field supported by other domain/resource evidence be proposed for later validation. If no supported analytical axis
                can be proposed, set resolved=false rather than returning a resolved analytical artifact with empty axes.
                """;
    }

    private String liveOptionRefinementModel(
            AgenticAuthoringIntentResolutionRequest request,
            boolean liveOptionRefinement) {
        if (!liveOptionRefinement
                || request == null
                || !"openai".equalsIgnoreCase(valueOrDefault(request.provider(), ""))) {
            return request == null ? null : request.model();
        }
        return liveOptionRefinementOpenAiModel;
    }

    private JsonNode invokeJson(
            String phase,
            String prompt,
            AiJsonSchema schema,
            AiCallConfig callConfig,
            String tenantId,
            String userId,
            String environment,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        int attempt = nextAttempt(providerInvocations, phase);
        AiProviderInvocationTrace trace = new AiProviderInvocationTrace(
                phase,
                attempt,
                callConfig != null ? callConfig.getProvider() : null,
                callConfig != null ? callConfig.getModel() : null);
        AiCallConfig tracedConfig = callConfig == null
                ? AiCallConfig.builder().invocationTrace(trace).build()
                : callConfig.toBuilder().invocationTrace(trace).build();
        try {
            JsonNode result = providerManagementService.generateJson(
                    prompt,
                    schema,
                    tracedConfig,
                    tenantId,
                    userId,
                    environment);
            trace.succeeded();
            return result;
        } catch (RuntimeException ex) {
            trace.failed(providerFailureKind(rootCause(ex)));
            throw ex;
        } finally {
            AiProviderInvocationTelemetry invocation = trace.snapshot();
            providerInvocations.add(invocation);
            AiProviderInvocationMetrics.record(invocation);
        }
    }

    private int nextAttempt(List<AiProviderInvocationTelemetry> providerInvocations, String phase) {
        if (providerInvocations == null || providerInvocations.isEmpty()) {
            return 1;
        }
        long priorAttempts = providerInvocations.stream()
                .filter(Objects::nonNull)
                .filter(invocation -> Objects.equals(phase, invocation.phase()))
                .count();
        return Math.toIntExact(Math.min(Integer.MAX_VALUE - 1L, priorAttempts) + 1L);
    }

    private AgenticAuthoringLlmIntentResolution withProviderInvocations(
            AgenticAuthoringLlmIntentResolution resolution,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (resolution == null) {
            return null;
        }
        return new AgenticAuthoringLlmIntentResolution(
                resolution.resolved(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.selectedResourcePath(),
                resolution.resourceSearchQuery(),
                resolution.followUpKind(),
                resolution.assistantMessage(),
                resolution.quickReplies(),
                resolution.clarificationQuestions(),
                resolution.warnings(),
                resolution.consultativeRetrievalPlan(),
                resolution.visualizationDecision(),
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                resolution.queryConstraints(),
                providerInvocations == null ? List.of() : List.copyOf(providerInvocations));
    }

    private AgenticAuthoringLlmIntentResolution failedResolution(
            RuntimeException ex,
            AgenticAuthoringIntentResolutionRequest request) {
        Throwable rootCause = rootCause(ex);
        log.warn(
                "[AgenticAuthoringLlmIntentResolver] Provider intent resolution failed; kind={} cause={}",
                providerFailureKind(rootCause),
                safeProviderFailureSummary(rootCause));
        if (hasPriorPlatformGuidanceSemanticScope(request)) {
            LinkedHashSet<String> warnings = new LinkedHashSet<>(providerFailureWarnings(rootCause));
            warnings.add("platform-guidance-prior-semantic-scope-recovery-used");
            return new AgenticAuthoringLlmIntentResolution(
                    true,
                    "explain",
                    "component",
                    "answer_component_catalog_question",
                    null,
                    null,
                    "none",
                    "Aqui você pode descrever em linguagem natural a tela que deseja criar ou melhorar. Posso ajudar com formulários, tabelas, gráficos, filtros e composição de páginas, sempre usando os componentes e dados governados disponíveis e apresentando qualquer alteração para revisão antes de aplicá-la.",
                    List.of(),
                    List.of(),
                    List.copyOf(warnings),
                    null,
                    null,
                    false,
                    "platform_guidance");
        }
        return new AgenticAuthoringLlmIntentResolution(
                false,
                "unknown",
                "unknown",
                "unknown",
                null,
                null,
                "provider_error",
                "Não consegui confirmar a sua intenção com segurança agora. Confirme se você quer consultar dados disponíveis, criar uma tabela, montar um formulário ou gerar uma visualização.",
                List.of(),
                List.of("Você quer consultar dados disponíveis ou já quer criar uma tabela, formulário, gráfico ou painel?"),
                providerFailureWarnings(rootCause),
                null,
                null);
    }

    private boolean hasPriorPlatformGuidanceSemanticScope(AgenticAuthoringIntentResolutionRequest request) {
        if (request == null || request.contextHints() == null) {
            return false;
        }
        JsonNode recommendedIntent = request.contextHints().path("recommendedIntent");
        return recommendedIntent.isObject()
                && "platform-capabilities".equals(nullableText(recommendedIntent, "semanticScope"));
    }

    private List<String> providerFailureWarnings(Throwable error) {
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        warnings.add("llm-intent-resolution-failed");
        warnings.add("llm-provider-error");
        warnings.add("llm-provider-" + providerFailureKind(error));
        return List.copyOf(warnings);
    }

    private Optional<AgenticAuthoringLlmIntentResolution> fastIntentResolution(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String governedDomainContext,
            String tenantId,
            String userId,
            String environment,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        List<AgenticAuthoringCandidate> fastCandidates = fastIntentCandidateOptions(candidateOptions);
        if (!shouldTryFastIntentResolution(request, effectivePrompt, target, fastCandidates, componentCapabilities)) {
            return Optional.empty();
        }
        boolean liveOptionRefinement = hasLiveOptionRefinementContext(request);
        boolean focusedResourceAuthoring = shouldUseFocusedResourceAuthoringPrompt(
                request,
                fastCandidates);
        try {
            JsonNode result = invokeJson(
                    "intent_fast",
                    liveOptionRefinement
                            ? liveOptionRefinementPrompt(request, effectivePrompt, fastCandidates)
                            : focusedResourceAuthoring
                                    ? focusedResourceAuthoringPrompt(
                                            request,
                                            effectivePrompt,
                                            currentPageSummary,
                                            fastCandidates,
                                            componentCapabilities)
                            : fastIntentPrompt(
                                    request,
                                    effectivePrompt,
                                    currentPageSummary,
                                    target,
                                    fastCandidates,
                                    componentCapabilities,
                                    governedDomainContext),
                    AiJsonSchema.ofSchema(schema()),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(liveOptionRefinementModel(request, liveOptionRefinement))
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(MAX_FAST_INTENT_RESOLUTION_TOKENS)
                            .timeoutSeconds(liveOptionRefinement || focusedResourceAuthoring
                                    ? Math.max(
                                            fastIntentTimeoutSeconds,
                                            Math.min(
                                                    fullIntentTimeoutSeconds,
                                                    MAX_LIVE_OPTION_REFINEMENT_TIMEOUT_SECONDS))
                                    : fastIntentTimeoutSeconds)
                            .build(),
                    tenantId,
                    userId,
                    environment,
                    providerInvocations);
            Optional<AgenticAuthoringLlmIntentResolution> resolution =
                    toResolution(result).map(value -> withFastCandidateResourceWhenUnambiguous(value, fastCandidates));
            if ((liveOptionRefinement || focusedResourceAuthoring)
                    && resolution.isPresent()
                    && !resolution.get().resolved()) {
                return resolution.map(this::withFastIntentWarning);
            }
            if (resolution.isPresent() && fastIntentResolutionComplete(
                    resolution.get(),
                    target,
                    componentCapabilities)) {
                return resolution.map(this::withFastIntentWarning);
            }
            if (resolution.isPresent() && shouldRepairFastVisualization(request, resolution.get())) {
                JsonNode repairedResult = invokeJson(
                        "intent_fast_visualization_repair",
                        fastVisualizationRepairPrompt(
                                request,
                                effectivePrompt,
                                currentPageSummary,
                                target,
                                fastCandidates,
                                componentCapabilities,
                                governedDomainContext),
                        AiJsonSchema.ofSchema(schema()),
                        AiCallConfig.builder()
                                .provider(request.provider())
                                .model(request.model())
                                .apiKey(request.apiKey())
                                .temperature(0.0d)
                                .maxTokens(MAX_FAST_INTENT_RESOLUTION_TOKENS)
                                .timeoutSeconds(fastIntentTimeoutSeconds)
                                .build(),
                        tenantId,
                        userId,
                        environment,
                        providerInvocations);
                Optional<AgenticAuthoringLlmIntentResolution> repaired = toResolution(repairedResult)
                        .map(value -> withFastCandidateResourceWhenUnambiguous(value, fastCandidates));
                if (repaired.isPresent() && fastIntentResolutionComplete(
                        repaired.get(),
                        target,
                        componentCapabilities)) {
                    return repaired
                            .map(this::withFastIntentWarning)
                            .map(value -> withWarning(
                                    value,
                                    "llm-fast-visualization-repair-used"));
                }
            }
            resolution.ifPresent(value -> log.debug(
                    "[AgenticAuthoringLlmIntentResolver] Fast intent pass fell back; reason={} resolved={} operation={} artifact={} selectedResourcePresent={} visualizationPresent={} axes={}",
                    fastIntentRejectionReason(value),
                    value.resolved(),
                    valueOrDefault(value.operationKind(), ""),
                    valueOrDefault(value.artifactKind(), ""),
                    StringUtils.hasText(value.selectedResourcePath()),
                    value.visualizationDecision() != null,
                    value.visualizationDecision() == null || value.visualizationDecision().axes() == null
                            ? 0
                            : value.visualizationDecision().axes().size()));
        } catch (RuntimeException ex) {
            log.debug("[AgenticAuthoringLlmIntentResolver] Fast intent pass failed; kind={} cause={}",
                    providerFailureKind(rootCause(ex)),
                    safeProviderFailureSummary(rootCause(ex)));
        }
        return Optional.empty();
    }

    private boolean shouldUseFocusedResourceAuthoringPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            List<AgenticAuthoringCandidate> candidateOptions) {
        if (request == null
                || request.activeSemanticDecision() != null
                || request.contextHints() == null
                || candidateOptions == null
                || candidateOptions.isEmpty()) {
            return false;
        }
        JsonNode resourceDiscovery = request.contextHints().path("resourceDiscovery");
        JsonNode semanticOrientation = request.contextHints().path("preIntentSemanticOrientation");
        JsonNode filters = semanticOrientation.path("queryConstraints").path("filters");
        return resourceDiscovery.isObject()
                && "table".equals(resourceDiscovery.path("artifactKind").asText(""))
                && semanticOrientation.isObject()
                && "authoring_or_other".equals(semanticOrientation.path("semanticIntentClass").asText(""))
                && semanticOrientation.path("requiresFullIntentResolution").asBoolean(false)
                && filters.isArray()
                && !filters.isEmpty();
    }

    private String focusedResourceAuthoringPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-focused-resource-context.v1");
        context.put("userPrompt", valueOrDefault(effectivePrompt, request.userPrompt()));
        JsonNode orientation = request.contextHints().path("preIntentSemanticOrientation");
        context.set("semanticOrientation", orientation.deepCopy());
        JsonNode resourceDiscovery = request.contextHints().path("resourceDiscovery");
        ObjectNode discovery = context.putObject("resourceDiscovery");
        discovery.put("artifactKind", resourceDiscovery.path("artifactKind").asText("table"));
        if (resourceDiscovery.path("resourceSearchFocus").isObject()) {
            discovery.set("resourceSearchFocus", resourceDiscovery.path("resourceSearchFocus").deepCopy());
        }
        ArrayNode resources = discovery.putArray("candidates");
        for (AgenticAuthoringCandidate candidate : candidateOptions) {
            if (candidate == null || !StringUtils.hasText(candidate.resourcePath())) {
                continue;
            }
            ObjectNode item = resources.addObject();
            item.put("resourcePath", candidate.resourcePath());
            item.put("operation", valueOrDefault(candidate.operation(), ""));
            item.put("filterPath", valueOrDefault(candidate.submitUrl(), ""));
            item.put("filterOperation", valueOrDefault(candidate.submitMethod(), ""));
            item.put("reason", boundedText(candidate.reason(), 320));
            ArrayNode evidence = item.putArray("evidence");
            for (String value : candidate.evidence() == null ? List.<String>of() : candidate.evidence()) {
                if (StringUtils.hasText(value)) {
                    evidence.add(value);
                }
            }
        }
        ArrayNode components = context.putArray("authorableComponents");
        if (componentCapabilities != null && componentCapabilities.catalogs() != null) {
            componentCapabilities.catalogs().stream()
                    .filter(Objects::nonNull)
                    .map(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog::componentId)
                    .filter(StringUtils::hasText)
                    .filter("praxis-table"::equals)
                    .forEach(components::add);
        }
        if (currentPageSummary != null && currentPageSummary.isObject()) {
            ObjectNode page = context.putObject("currentPage");
            page.put("componentCount", currentPageSummary.path("componentCount").asInt(0));
            page.put("isEmpty", currentPageSummary.path("componentCount").asInt(0) == 0);
        }
        context.put("responseLocale", nullableText(request.contextHints(), "responseLocale"));
        return """
                You are resolving one focused, LLM-planned Praxis table-authoring request.
                Return only one JSON object matching the supplied schema.

                semanticOrientation is prior AI-authored semantic evidence, not a keyword heuristic. Preserve every
                actual data-selection predicate in semanticOrientation.queryConstraints and preserve concept, operator
                and value independently. Preserve appliesToDataSelection=true only when those predicates constrain which
                backend records are retrieved or displayed. A header, label, renderer, formatting, composed-cell or
                displayed-value edit must use appliesToDataSelection=false with an empty filters array, even when its text
                resembles a current data value. Do not drop an actual data predicate after selecting the resource. Textual
                business categories remain semantic values at this stage; later governed field and live option-value tools
                will resolve canonical fields and current IDs.

                Select selectedResourcePath only from resourceDiscovery.candidates. Select the resource whose records the
                table must display. If a related category or master-data concept constrains those records, keep it as a
                filter and do not replace the displayed entity with the category resource. Prefer the governed read/filter
                operation for visualization, never an unrelated create/POST operation.

                For the resolved request use operationKind "create", artifactKind "table", changeKind "create_artifact",
                semanticIntentClass "component_authoring", requiresGovernedAuthoring=false and followUpKind "none".
                Set visualizationDecision to a single table using primaryComponent "praxis-table", no axes, and
                includeFilters=true. Do not invent fields, labels, option IDs, components or resources. If the governed
                candidates do not establish the displayed entity unambiguously, return resolved=false with a natural,
                business-friendly clarification and structured quick replies.

                Answer in responseLocale when present; otherwise use the user's language.
                Focused context JSON: %s
                """.formatted(context.toString());
    }

    private String liveOptionRefinementPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            List<AgenticAuthoringCandidate> candidateOptions) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-live-option-refinement-context.v1");
        context.put("userPrompt", valueOrDefault(effectivePrompt, request.userPrompt()));
        context.set("activeSemanticDecision", request.activeSemanticDecision() == null
                ? objectMapper.nullNode()
                : objectMapper.valueToTree(request.activeSemanticDecision()));
        ArrayNode resources = context.putArray("candidateResources");
        for (AgenticAuthoringCandidate candidate : candidateOptions == null
                ? List.<AgenticAuthoringCandidate>of()
                : candidateOptions) {
            if (candidate == null || !StringUtils.hasText(candidate.resourcePath())) {
                continue;
            }
            ObjectNode item = resources.addObject();
            item.put("resourcePath", candidate.resourcePath());
            item.put("operation", valueOrDefault(candidate.operation(), ""));
            item.put("reason", boundedText(candidate.reason(), 320));
        }
        JsonNode valueGrounding = request.contextHints() == null
                ? null
                : request.contextHints().path("liveOptionValueGrounding");
        if (valueGrounding != null && valueGrounding.isObject()) {
            context.set("liveOptionValueGrounding", valueGrounding.deepCopy());
        } else {
            JsonNode fieldGrounding = request.contextHints() == null
                    ? null
                    : request.contextHints().path("liveOptionFieldGrounding");
            if (fieldGrounding != null && fieldGrounding.isObject()) {
                context.set("liveOptionFieldGrounding", fieldGrounding.deepCopy());
            }
        }
        context.put("responseLocale", request.contextHints() == null
                ? ""
                : nullableText(request.contextHints(), "responseLocale"));
        return """
                You are performing one focused semantic refinement of an existing governed Praxis authoring decision.
                Return only one JSON object matching the supplied schema.

                The activeSemanticDecision is canonical lineage. Preserve its operation, artifact, selected resource,
                visualization decision and every unrelated predicate that already carries governed field evidence.
                This refinement exists only for a previously confirmed record subset, so preserve
                queryConstraints.appliesToDataSelection=true while grounding its predicates.
                selectedResourcePath must remain one of candidateResources. Never invent a resource, field, label or
                option ID. An ungrounded predicate may be consolidated into the canonical live-option predicate only
                when its semantic value is represented by current candidates of that same option source; otherwise ask
                a clarification instead of preserving an invented field.

                When liveOptionFieldGrounding is present, reason from the business meaning of originalPredicate and the
                governed candidate descriptions, never from keyword overlap. If exactly one field is semantically
                appropriate, replace only that predicate field with canonicalFilterField and preserve its semantic text
                value, which may be one string or a list of strings. A concrete record name or identifier used to locate
                a writable record is a data-selection predicate and is not automatically an option-source dimension.
                If an existing governed non-option field already represents that predicate, preserve it. If none of the
                option-source candidates is semantically appropriate, preserve the governed predicate and treat live
                option grounding as not applicable; never select the sole candidate merely because it is the only one.
                If materially ambiguous, return resolved=false with a natural clarification and do not materialize. This
                stage confirms a local table filter field; it does not select option values yet and it is not shared-rule
                authoring. Keep requiresGovernedAuthoring=false.

                When liveOptionValueGrounding is present, reason semantically over all current candidates for the already
                selected canonical field; the field-selection stage is already complete and must not be reopened. Reason
                over every requested business category and semantic text predicate in the active decision and user prompt,
                not only the first predicate. If the field is multi-valued, select the union of every current candidate
                that clearly instantiates any requested category. Several matching values across organizations are
                expected and are not, by themselves, ambiguity. When an additional ungrounded text predicate is
                represented by candidates of this same canonical option source, include those candidate IDs in the union
                and remove the duplicate predicate instead of inventing or preserving another field. Preserve an
                independent predicate only when it already has governed field evidence; otherwise clarify. If membership
                is sufficiently clear, replace the canonical predicate with canonicalFilterField, operator "in", and an
                array containing only candidate IDs. Do not emit labels or textual contains filters, and do not ask for
                confirmation merely because more than one candidate clearly matches. If membership itself is materially
                ambiguous, return resolved=false with a business-friendly clarification and structured quick replies.
                Preserve every other governed part of the active decision.

                Answer in responseLocale when present; otherwise use the user's language.
                Context JSON: %s
                """.formatted(context.toString());
    }

    private boolean shouldRepairFastVisualization(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringLlmIntentResolution resolution) {
        if (request == null
                || request.contextHints() == null
                || resolution == null
                || !resolution.resolved()
                || !"create".equals(valueOrDefault(resolution.operationKind(), ""))
                || !List.of("chart", "dashboard").contains(valueOrDefault(resolution.artifactKind(), ""))) {
            return false;
        }
        String plannedArtifact = request.contextHints()
                .path("resourceDiscovery")
                .path("artifactKind")
                .asText("");
        if (!List.of("chart", "dashboard").contains(plannedArtifact)) {
            return false;
        }
        AgenticAuthoringVisualizationDecision decision = resolution.visualizationDecision();
        return decision == null
                || !StringUtils.hasText(decision.primaryComponent())
                || decision.axes() == null
                || decision.axes().isEmpty();
    }

    private String fastVisualizationRepairPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String governedDomainContext) {
        return fastIntentPrompt(
                        request,
                        effectivePrompt,
                        currentPageSummary,
                        target,
                        candidateOptions,
                        componentCapabilities,
                        governedDomainContext)
                + """

                The previous compact resolution selected an analytical artifact but omitted a complete
                visualizationDecision. Repair that semantic decision now using the same governed evidence.
                Return a complete intent object, not a patch. Preserve the AI-authored artifact in
                semanticRetrievalIntent unless the user's meaning clearly requires the other analytical
                artifact. For chart or dashboard, visualizationDecision must be non-null, primaryComponent
                must come from authorableComponents, and axes must contain at least one grounded grouping or
                time dimension with its metric semantics. If the evidence cannot support that decision, set
                resolved=false instead of inventing fields or components.
                """;
    }

    private Optional<AgenticAuthoringLlmIntentResolution> compactPlatformGuidanceConfirmation(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String governedDomainContext,
            String tenantId,
            String userId,
            String environment,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        if (!hasPriorPlatformGuidanceSemanticScope(request)) {
            return Optional.empty();
        }
        try {
            JsonNode result = invokeJson(
                    "platform_guidance_confirmation",
                    compactPlatformGuidancePrompt(
                            request,
                            effectivePrompt,
                            target,
                            componentCapabilities,
                            governedDomainContext),
                    AiJsonSchema.ofSchema(compactPlatformGuidanceSchema()),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(MAX_PLATFORM_GUIDANCE_CONFIRMATION_TOKENS)
                            .timeoutSeconds(fastIntentTimeoutSeconds)
                            .build(),
                    tenantId,
                    userId,
                    environment,
                    providerInvocations);
            if (result == null
                    || !result.isObject()
                    || !result.path("matchesSemanticScope").asBoolean(false)
                    || !"platform_guidance".equals(nullableText(result, "semanticIntentClass"))) {
                return Optional.empty();
            }
            String assistantMessage = conciseAssistantMessage(nullableText(result, "assistantMessage"));
            if (!StringUtils.hasText(assistantMessage)) {
                return Optional.empty();
            }
            return Optional.of(new AgenticAuthoringLlmIntentResolution(
                    true,
                    "explain",
                    "component",
                    "answer_component_catalog_question",
                    null,
                    null,
                    "none",
                    assistantMessage,
                    List.of(),
                    List.of(),
                    List.of("llm-compact-platform-guidance-confirmation-used"),
                    null,
                    null,
                    false,
                    "platform_guidance"));
        } catch (RuntimeException ex) {
            log.debug(
                    "[AgenticAuthoringLlmIntentResolver] Compact platform guidance confirmation fell back; kind={} cause={}",
                    providerFailureKind(rootCause(ex)),
                    safeProviderFailureSummary(rootCause(ex)));
            if (hasPriorPlatformGuidanceSemanticScope(request)) {
                return Optional.of(failedResolution(ex, request));
            }
            return Optional.empty();
        }
    }

    private String compactPlatformGuidancePrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String governedDomainContext) {
        String responseLocale = request.contextHints() == null
                ? ""
                : nullableText(request.contextHints(), "responseLocale");
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-platform-guidance-confirmation-context.v1");
        context.put("userPrompt", valueOrDefault(effectivePrompt, request.userPrompt()));
        context.put("route", valueOrDefault(request.currentRoute(), ""));
        context.set("recommendedIntent", request.contextHints().path("recommendedIntent").deepCopy());
        context.set(
                "governedDomainContext",
                AgenticAuthoringContextBundle.create(
                                objectMapper,
                                request,
                                effectivePrompt,
                                objectMapper.createObjectNode(),
                                target,
                                List.of(),
                                componentCapabilities,
                                governedDomainContext)
                        .path("governedDomainContext")
                        .deepCopy());
        if (target != null) {
            ObjectNode targetNode = context.putObject("target");
            targetNode.put("widgetKey", valueOrDefault(target.widgetKey(), ""));
            targetNode.put("componentId", valueOrDefault(target.componentId(), ""));
        }
        ArrayNode components = context.putArray("authorableComponents");
        if (componentCapabilities != null && componentCapabilities.catalogs() != null) {
            for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog
                    : componentCapabilities.catalogs()) {
                if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
                    continue;
                }
                ObjectNode component = components.addObject();
                component.put("componentId", catalog.componentId());
                ArrayNode capabilities = component.putArray("capabilities");
                List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> values =
                        catalog.capabilities() == null ? List.of() : catalog.capabilities();
                for (int index = 0; index < Math.min(values.size(), 4); index++) {
                    AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability = values.get(index);
                    if (capability != null && StringUtils.hasText(capability.changeKind())) {
                        capabilities.add(capability.changeKind());
                    }
                }
            }
        }
        return """
                You are confirming the primary semantic intent for the Praxis Page Builder assistant.
                Return only one JSON object matching the supplied schema.

                Decide from the user's meaning, never by keyword or regular-expression matching.
                The structured recommendedIntent is presentation-context evidence, not authority and not permission.
                Set matchesSemanticScope=true and semanticIntentClass="platform_guidance" only when the user is
                asking what Praxis or this assistant can do, how it can help, or what a sensible next step is.
                If the user asks to create, edit, remove or inspect a specific artifact, business domain or data
                source, set matchesSemanticScope=false and semanticIntentClass="other" so the complete governed
                resolver can decide the request.

                When the scope matches, answer in the canonical response locale below when it is present; otherwise
                use the user's language. Do not infer a different response language from domain labels. Use only the
                governed component capabilities supplied here. Be friendly, concise and concrete. Mention useful examples such as
                forms, tables, charts, filters or page composition only when supported by the supplied catalog,
                and finish with one helpful next action stated declaratively. Do not ask a follow-up question or
                request confirmation in this advisory answer. Do not claim that anything was already created or applied.
                When the scope does not match, use an empty assistantMessage.

                Canonical response locale: %s

                Compact governed context:
                %s
                """.formatted(responseLocale, context.toPrettyString());
    }

    private String compactPlatformGuidanceSchema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        properties.putObject("matchesSemanticScope").put("type", "boolean");
        stringEnum(properties, "semanticIntentClass", List.of("platform_guidance", "other"));
        properties.putObject("assistantMessage").put("type", "string");
        root.putArray("required")
                .add("matchesSemanticScope")
                .add("semanticIntentClass")
                .add("assistantMessage");
        root.put("additionalProperties", false);
        return root.toString();
    }

    private Optional<AgenticAuthoringLlmIntentResolution> compactDeclaredClientActionIntent(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            String tenantId,
            String userId,
            String environment,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        JsonNode clientActions = request.contextHints() == null
                ? null
                : request.contextHints().path("clientActions");
        if (clientActions == null || !clientActions.isArray() || clientActions.isEmpty()) {
            return Optional.empty();
        }
        ArrayNode declaredActions = objectMapper.createArrayNode();
        clientActions.forEach(action -> {
            if (action != null
                    && action.isObject()
                    && StringUtils.hasText(nullableText(action, "kind"))) {
                ObjectNode declared = declaredActions.addObject();
                declared.put("kind", nullableText(action, "kind"));
                declared.put("available", action.path("available").asBoolean(false));
                declared.put("targetComponentId", valueOrDefault(
                        nullableText(action, "targetComponentId"), ""));
            }
        });
        if (declaredActions.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode context = objectMapper.createObjectNode();
        context.put("userPrompt", boundedText(effectivePrompt, 700));
        context.set("declaredClientActions", declaredActions);
        if (request.activeSemanticDecision() != null) {
            context.put(
                    "priorObjective",
                    boundedText(valueOrDefault(
                            request.activeSemanticDecision().activeObjective(), ""), 300));
        }
        try {
            JsonNode result = invokeJson(
                    "declared_client_action_intent",
                    """
                    Decide whether the primary meaning of the current userPrompt requests one of the
                    declared client actions. Decide semantically from the whole request, never from a
                    keyword, regex or the prior objective. The current prompt is authoritative.
                    A request to reverse only the most recently materialized local change matches
                    local-undo. A request to create, edit, explain or retry prior authoring does not.
                    A hesitation, cancellation or self-correction inside the current utterance does
                    not match local-undo when the user states a final desired configuration in that
                    same utterance; honor the final desired state through the general resolver.
                    Availability governs execution only and must not change the semantic match.
                    Return only the schema-conforming JSON.

                    Governed context:
                    %s
                    """.formatted(context.toPrettyString()),
                    AiJsonSchema.ofSchema(declaredClientActionIntentSchema(declaredActions)),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(MAX_DECLARED_CLIENT_ACTION_INTENT_TOKENS)
                            .timeoutSeconds(Math.max(
                                    1,
                                    Math.min(
                                            fastIntentTimeoutSeconds,
                                            MAX_TARGETED_COMPONENT_ATTEMPT_TIMEOUT_SECONDS)))
                            .build(),
                    tenantId,
                    userId,
                    environment,
                    providerInvocations);
            if (!result.path("matchesDeclaredAction").asBoolean(false)
                    || !"local-undo".equals(nullableText(result, "actionKind"))) {
                return Optional.empty();
            }
            return Optional.of(new AgenticAuthoringLlmIntentResolution(
                    true,
                    "undo",
                    "component",
                    "undo_last_local_change",
                    null,
                    null,
                    "new_instruction",
                    conciseAssistantMessage(nullableText(result, "assistantMessage")),
                    List.of(),
                    List.of(),
                    List.of("llm-declared-client-action-intent-used"),
                    null,
                    null,
                    false,
                    "component_authoring"));
        } catch (RuntimeException ex) {
            log.debug(
                    "[AgenticAuthoringLlmIntentResolver] Declared client action intent failed open to the general semantic resolver; kind={} cause={}",
                    providerFailureKind(rootCause(ex)),
                    safeProviderFailureSummary(rootCause(ex)));
            return Optional.empty();
        }
    }

    private String declaredClientActionIntentSchema(ArrayNode declaredActions) {
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        declaredActions.forEach(action -> {
            String kind = nullableText(action, "kind");
            if (StringUtils.hasText(kind)) {
                kinds.add(kind);
            }
        });
        kinds.add("none");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        properties.putObject("matchesDeclaredAction").put("type", "boolean");
        stringEnum(properties, "actionKind", List.copyOf(kinds));
        nullableString(properties, "assistantMessage");
        root.putArray("required")
                .add("matchesDeclaredAction")
                .add("actionKind")
                .add("assistantMessage");
        root.put("additionalProperties", false);
        return root.toString();
    }

    private Optional<AgenticAuthoringLlmIntentResolution> compactTargetedComponentIntent(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities =
                targetedComponentCapabilities(request, target, componentCapabilities, effectivePrompt);
        if (!shouldTryCompactTargetedComponentIntent(request, target, capabilities)) {
            return Optional.empty();
        }
        try {
            JsonNode result = invokeTargetedComponentIntent(
                    request,
                    effectivePrompt,
                    target,
                    capabilities,
                    tenantId,
                    userId,
                    environment,
                    providerInvocations);
            return toCompactTargetedComponentResolution(result, target, componentCapabilities);
        } catch (RuntimeException ex) {
            log.debug(
                    "[AgenticAuthoringLlmIntentResolver] Compact targeted component intent failed closed; kind={} cause={}",
                    providerFailureKind(rootCause(ex)),
                    safeProviderFailureSummary(rootCause(ex)));
            return Optional.of(failedResolution(ex, request));
        }
    }

    private JsonNode invokeTargetedComponentIntent(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities,
            String tenantId,
            String userId,
            String environment,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        RuntimeException lastFailure = null;
        int attemptTimeoutSeconds = Math.max(
                1,
                Math.min(fastIntentTimeoutSeconds, MAX_TARGETED_COMPONENT_ATTEMPT_TIMEOUT_SECONDS));
        for (int attempt = 1; attempt <= MAX_TARGETED_COMPONENT_INTENT_ATTEMPTS; attempt++) {
            try {
                return invokeJson(
                        "targeted_component_intent",
                        compactTargetedComponentIntentPrompt(request, effectivePrompt, target, capabilities),
                        AiJsonSchema.ofSchema(compactTargetedComponentIntentSchema(capabilities)),
                        AiCallConfig.builder()
                                .provider(request.provider())
                                .model(request.model())
                                .apiKey(request.apiKey())
                                .temperature(0.0d)
                                .maxTokens(MAX_TARGETED_COMPONENT_INTENT_TOKENS)
                                .timeoutSeconds(attemptTimeoutSeconds)
                                .build(),
                        tenantId,
                        userId,
                        environment,
                        providerInvocations);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                String failureKind = providerFailureKind(rootCause(ex));
                if (attempt >= MAX_TARGETED_COMPONENT_INTENT_ATTEMPTS
                        || !isRetryableTargetedComponentFailure(failureKind)) {
                    throw ex;
                }
                log.debug(
                        "[AgenticAuthoringLlmIntentResolver] Retrying compact targeted component intent after transient failure; attempt={}/{} kind={}",
                        attempt,
                        MAX_TARGETED_COMPONENT_INTENT_ATTEMPTS,
                        failureKind);
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Targeted component intent resolution produced no result")
                : lastFailure;
    }

    private boolean isRetryableTargetedComponentFailure(String failureKind) {
        return switch (failureKind == null ? "" : failureKind) {
            case "timeout", "transport-error", "rate-limit", "capacity", "server-error", "unknown-error" -> true;
            default -> false;
        };
    }

    private boolean shouldTryCompactTargetedComponentIntent(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities) {
        if (request == null
                || request.pendingClarification() != null
                || forceFullIntentResolution(request)
                || target == null
                || !StringUtils.hasText(target.widgetKey())
                || !StringUtils.hasText(target.componentId())
                || !StringUtils.hasText(target.resourcePath())
                || capabilities == null
                || capabilities.isEmpty()) {
            return false;
        }
        AgenticAuthoringSemanticDecision activeDecision = request.activeSemanticDecision();
        return activeDecision == null
                || activeDecision.selectedResource() == null
                || !StringUtils.hasText(activeDecision.selectedResource().resourcePath())
                || target.resourcePath().equals(activeDecision.selectedResource().resourcePath());
    }

    private List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> targetedComponentCapabilities(
            AgenticAuthoringIntentResolutionRequest request,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String effectivePrompt) {
        if (target == null
                || !StringUtils.hasText(target.componentId())
                || componentCapabilities == null
                || componentCapabilities.catalogs() == null) {
            return List.of();
        }
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> declaredCapabilities =
                componentCapabilities.catalogs().stream()
                .filter(Objects::nonNull)
                .filter(catalog -> target.componentId().equals(catalog.componentId()))
                .flatMap(catalog -> (catalog.capabilities() == null
                        ? List.<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability>of()
                        : catalog.capabilities()).stream())
                .filter(Objects::nonNull)
                .filter(capability -> StringUtils.hasText(capability.changeKind()))
                .toList();
        // The target component is already pinned by governed page context. The prior semantic
        // decision reserves one canonical candidate for genuine refinements; prompt similarity
        // ranks additional candidates inside that scope. Neither decides the current intent.
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> ranked =
                AgenticAuthoringContextBundle.promptRelevantCapabilities(
                effectivePrompt,
                declaredCapabilities);
        List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String activeChangeKind = request != null && request.activeSemanticDecision() != null
                ? valueOrDefault(request.activeSemanticDecision().changeKind(), "")
                : "";
        declaredCapabilities.stream()
                .filter(capability -> activeChangeKind.equals(capability.changeKind()))
                .findFirst()
                .ifPresent(capability -> {
                    selected.add(capability);
                    seen.add(capability.changeKind());
                });
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : ranked) {
            if (capability != null && seen.add(capability.changeKind())) {
                selected.add(capability);
            }
        }
        return selected.stream()
                .limit(AgenticAuthoringContextBundle.MAX_COMPACT_CAPABILITIES_PER_COMPONENT)
                .toList();
    }

    private String compactTargetedComponentIntentPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-targeted-component-intent-context.v1");
        context.put("userPrompt", boundedText(valueOrDefault(effectivePrompt, request.userPrompt()), 1200));
        ObjectNode selectedTarget = context.putObject("selectedTarget");
        selectedTarget.put("widgetKey", target.widgetKey());
        selectedTarget.put("componentId", target.componentId());
        selectedTarget.put("resourcePath", target.resourcePath());
        if (request.activeSemanticDecision() != null) {
            AgenticAuthoringSemanticDecision activeDecision = request.activeSemanticDecision();
            ObjectNode active = context.putObject("activeSemanticDecision");
            active.put("decisionId", valueOrDefault(activeDecision.decisionId(), ""));
            active.put("operationKind", valueOrDefault(activeDecision.operationKind(), ""));
            active.put("artifactKind", valueOrDefault(activeDecision.artifactKind(), ""));
            active.put("changeKind", valueOrDefault(activeDecision.changeKind(), ""));
            active.put("activeObjective", boundedText(valueOrDefault(activeDecision.activeObjective(), ""), 500));
            if (activeDecision.selectedResource() != null) {
                active.put("selectedResourcePath", valueOrDefault(
                        activeDecision.selectedResource().resourcePath(), ""));
            }
        }
        if (request.conversationMessages() != null && !request.conversationMessages().isEmpty()) {
            ArrayNode conversation = context.putArray("recentConversation");
            List<AgenticAuthoringConversationMessage> messages = request.conversationMessages();
            int start = Math.max(0, messages.size() - 2);
            for (int index = start; index < messages.size(); index++) {
                AgenticAuthoringConversationMessage message = messages.get(index);
                if (message == null || !StringUtils.hasText(message.text())) {
                    continue;
                }
                ObjectNode item = conversation.addObject();
                item.put("role", valueOrDefault(message.role(), ""));
                item.put("text", boundedText(message.text(), 500));
            }
        }
        JsonNode clientActions = request.contextHints() == null
                ? null
                : request.contextHints().path("clientActions");
        if (clientActions != null && clientActions.isArray()) {
            context.set("clientActions", clientActions.deepCopy());
        }
        ArrayNode governedCapabilities = context.putArray("governedCapabilities");
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : capabilities) {
            ObjectNode capabilityNode = governedCapabilities.addObject();
            capabilityNode.put("changeKind", capability.changeKind());
            ArrayNode examples = capabilityNode.putArray("semanticExamples");
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample> values =
                    capability.examples() == null ? List.of() : capability.examples();
            for (int index = 0; index < Math.min(values.size(), 2); index++) {
                AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example = values.get(index);
                if (example == null) {
                    continue;
                }
                ObjectNode exampleNode = examples.addObject();
                exampleNode.put("request", boundedText(valueOrDefault(example.prompt(), ""), 400));
                exampleNode.put("semanticEffect", boundedText(valueOrDefault(example.intent(), ""), 500));
                ArrayNode constraints = exampleNode.putArray("constraints");
                List<String> hints = example.configHints() == null ? List.of() : example.configHints();
                for (int hintIndex = 0; hintIndex < Math.min(hints.size(), 4); hintIndex++) {
                    constraints.add(boundedText(hints.get(hintIndex), 300));
                }
            }
        }
        return """
                You resolve the primary semantic intent for an edit to the selected Praxis component.
                Return only one JSON object matching the supplied schema.

                Decide from the user's meaning, never from keywords, regexes or capability order.
                The selected target, active decision and conversation are governed evidence, not permission.
                The current userPrompt is authoritative for the objective of this turn. A prior objective that ended
                in an error is historical evidence only: do not continue its changeKind when the current request
                describes a different semantic operation. Resume the prior objective only for an explicit retry action
                or when the current request unambiguously refers to that same change. Otherwise set
                followUpKind="new_instruction" and resolve the current request independently while preserving only
                unrelated, successfully materialized component state.
                Set matchesSelectedComponentScope=true when the new request semantically concerns the selected
                component. When that scoped request contains internally conflicting or ambiguous requirements, or no
                single declared capability can faithfully represent its requested outcome, keep
                matchesSelectedComponentScope=true, semanticIntentClass="component_authoring", set operationKind,
                artifactKind and changeKind to "unknown", and ask one concise clarificationQuestions question. This
                includes ambiguous requests for mutually exclusive simultaneous subsets of the selected resource.
                Do not hide that mismatch behind a broad surface-configuration capability or claim that the current
                configuration already satisfies a different requested outcome. Set matchesSelectedComponentScope=false
                only for a clear request for a new artifact, a different component, a reusable business rule, general
                guidance or an unrelated task so the complete resolver can evaluate it.
                When the user's primary meaning is to reverse only the most recently materialized local change,
                select operationKind="undo", keep artifactKind aligned with the selected artifact (for example
                "table" for praxis-table, "form" for a form, or "component" when no narrower kind applies), and
                changeKind="undo_last_local_change". clientActions is the declared local capability catalog and
                availability snapshot; never invent an action or reinterpret undo as a renderer/configuration edit.
                Availability does not change the semantic intent: the runtime will explain when the declared action
                cannot currently execute.
                When it matches, choose changeKind only by comparing the semantic effects and constraints of the
                governed capabilities. Adding a new schema-backed item is different from formatting, moving,
                renaming, hiding or changing an existing item. Preserve the current component unless the user asks
                for a different artifact. A row-dependent visual outcome governed by a condition must select the
                capability that adds or changes a conditional renderer; a base renderer capability applies one
                presentation uniformly and is not semantically equivalent.
                Use requiresGovernedAuthoring=true for reusable business decisions and
                false for local component presentation/configuration. Do not invent a resource, capability or field.
                Keep assistantMessage short and natural in the user's language. If the scope does not match, use
                changeKind="unknown", operationKind="unknown", artifactKind="unknown" and an empty message.

                Compact governed context:
                %s
                """.formatted(context.toPrettyString());
    }

    private String compactTargetedComponentIntentSchema(
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> capabilities) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        properties.putObject("matchesSelectedComponentScope").put("type", "boolean");
        stringEnum(properties, "semanticIntentClass", List.of(
                "component_authoring", "shared_rule_authoring", "out_of_scope", "unknown"));
        stringEnum(properties, "operationKind", List.of("modify", "remove", "undo", "unknown"));
        stringEnum(properties, "artifactKind", List.of(
                "dashboard", "chart", "table", "form", "page", "component", "unknown"));
        LinkedHashSet<String> changeKinds = new LinkedHashSet<>();
        capabilities.stream()
                .map(AgenticAuthoringComponentCapabilitiesResult.ComponentCapability::changeKind)
                .filter(StringUtils::hasText)
                .forEach(changeKinds::add);
        changeKinds.add("undo_last_local_change");
        changeKinds.add("unknown");
        stringEnum(properties, "changeKind", List.copyOf(changeKinds));
        stringEnum(properties, "followUpKind", List.of("refinement", "new_instruction", "unknown"));
        properties.putObject("requiresGovernedAuthoring").put("type", "boolean");
        nullableString(properties, "assistantMessage");
        arrayOfStrings(properties, "clarificationQuestions");
        arrayOfStrings(properties, "warnings");
        root.putArray("required")
                .add("matchesSelectedComponentScope")
                .add("semanticIntentClass")
                .add("operationKind")
                .add("artifactKind")
                .add("changeKind")
                .add("followUpKind")
                .add("requiresGovernedAuthoring")
                .add("assistantMessage")
                .add("clarificationQuestions")
                .add("warnings");
        root.put("additionalProperties", false);
        return root.toString();
    }

    private Optional<AgenticAuthoringLlmIntentResolution> toCompactTargetedComponentResolution(
            JsonNode result,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        String operationKind = nullableText(result, "operationKind");
        if (result == null
                || !result.isObject()
                || !result.path("matchesSelectedComponentScope").asBoolean(false)
                || !"component_authoring".equals(nullableText(result, "semanticIntentClass"))
                || result.path("requiresGovernedAuthoring").asBoolean(false)) {
            return Optional.empty();
        }
        String artifactKind = nullableText(result, "artifactKind");
        String changeKind = nullableText(result, "changeKind");
        List<String> clarificationQuestions = strings(result.path("clarificationQuestions"));
        List<String> warnings = new ArrayList<>(strings(result.path("warnings")));
        if (!warnings.contains("llm-compact-targeted-component-intent-used")) {
            warnings.add("llm-compact-targeted-component-intent-used");
        }
        boolean scopedClarification = "unknown".equals(operationKind)
                && "unknown".equals(artifactKind)
                && "unknown".equals(changeKind)
                && !clarificationQuestions.isEmpty();
        if (scopedClarification) {
            warnings.add("llm-compact-targeted-component-clarification-used");
            return Optional.of(new AgenticAuthoringLlmIntentResolution(
                    true,
                    "unknown",
                    "component",
                    "unknown",
                    target.resourcePath(),
                    null,
                    valueOrDefault(nullableText(result, "followUpKind"), "refinement"),
                    conciseAssistantMessage(nullableText(result, "assistantMessage")),
                    List.of(),
                    clarificationQuestions,
                    List.copyOf(warnings),
                    null,
                    null,
                    false,
                    "component_authoring"));
        }
        if (!"modify".equals(operationKind) && !"undo".equals(operationKind)) {
            return Optional.empty();
        }
        boolean localUndo = "undo".equals(operationKind)
                && "undo_last_local_change".equals(changeKind);
        if (!StringUtils.hasText(artifactKind)
                || "unknown".equals(artifactKind)
                || (!localUndo
                        && !declaredChangeKind(target.componentId(), changeKind, componentCapabilities))) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringLlmIntentResolution(
                true,
                operationKind,
                artifactKind,
                changeKind,
                target.resourcePath(),
                null,
                valueOrDefault(nullableText(result, "followUpKind"), "refinement"),
                conciseAssistantMessage(nullableText(result, "assistantMessage")),
                List.of(),
                clarificationQuestions,
                List.copyOf(warnings),
                null,
                null,
                false,
                "component_authoring"));
    }

    private String boundedText(String value, int maxChars) {
        String text = valueOrDefault(value, "").trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private String fastIntentRejectionReason(AgenticAuthoringLlmIntentResolution resolution) {
        if (resolution == null) {
            return "empty-resolution";
        }
        if (!resolution.resolved()) {
            return "unresolved";
        }
        if (fastConsultativeResolutionComplete(resolution)) {
            return "complete-consultative-resolution";
        }
        if (!"create".equals(valueOrDefault(resolution.operationKind(), ""))) {
            return "operation-not-create";
        }
        String artifactKind = valueOrDefault(resolution.artifactKind(), "");
        if (!List.of("chart", "dashboard", "table", "page").contains(artifactKind)) {
            return "unsupported-artifact-kind";
        }
        if (!StringUtils.hasText(resolution.selectedResourcePath())) {
            return "missing-selected-resource";
        }
        if (List.of("chart", "dashboard").contains(artifactKind)) {
            AgenticAuthoringVisualizationDecision decision = resolution.visualizationDecision();
            if (decision == null) {
                return "missing-visualization-decision";
            }
            if (!StringUtils.hasText(decision.primaryComponent())) {
                return "missing-primary-component";
            }
            if (decision.axes() == null || decision.axes().isEmpty()) {
                return "missing-axes";
            }
        }
        return "unknown";
    }

    private boolean shouldTryFastIntentResolution(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (request == null
                || request.pendingClarification() != null
                || forceFullIntentResolution(request)) {
            return false;
        }
        boolean liveOptionRefinement = hasLiveOptionRefinementContext(request);
        boolean targetedComponentEdit = hasTargetedComponentCapabilities(target, componentCapabilities);
        if (!targetedComponentEdit
                && !liveOptionRefinement
                && (request.activeSemanticDecision() != null
                || hasConversationHistoryBeyondCurrentPrompt(request, effectivePrompt))) {
            return false;
        }
        if (!targetedComponentEdit && target != null && StringUtils.hasText(target.widgetKey())) {
            return false;
        }
        if (!isEmpty(componentCapabilities)) {
            return true;
        }
        if (!isEmpty(candidateOptions)) {
            return candidateOptions.stream()
                    .anyMatch(candidate -> hasEvidence(candidate, "explicit-source-match")
                            || hasEvidence(candidate, "context-hint")
                            || hasEvidence(candidate, "quick-reply-context")
                            || hasEvidence(candidate, "current-page")
                            || hasEvidence(candidate, "explicit-resource-path")
                            || hasEvidence(candidate, "tool-search-api-resources")
                            || hasEvidence(candidate, "domain-catalog-context"));
        }
        return true;
    }

    private boolean hasLiveOptionRefinementContext(AgenticAuthoringIntentResolutionRequest request) {
        if (request == null || request.contextHints() == null) {
            return false;
        }
        return request.contextHints().path("liveOptionFieldGrounding").isObject()
                || request.contextHints().path("liveOptionValueGrounding").isObject();
    }

    private boolean isEmpty(List<?> items) {
        return items == null || items.isEmpty();
    }

    private boolean isEmpty(AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        return componentCapabilities == null
                || componentCapabilities.catalogs() == null
                || componentCapabilities.catalogs().isEmpty();
    }

    private boolean hasConversationHistoryBeyondCurrentPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt) {
        if (request == null || request.conversationMessages() == null || request.conversationMessages().isEmpty()) {
            return false;
        }
        List<AgenticAuthoringConversationMessage> messages = request.conversationMessages().stream()
                .filter(message -> message != null && StringUtils.hasText(message.text()))
                .toList();
        if (messages.isEmpty()) {
            return false;
        }
        if (messages.size() != 1) {
            return true;
        }
        AgenticAuthoringConversationMessage message = messages.get(0);
        return !"user".equalsIgnoreCase(valueOrDefault(message.role(), ""))
                || (!sameCompactText(message.text(), effectivePrompt)
                && !sameCompactText(message.text(), request.userPrompt()));
    }

    private boolean sameCompactText(String left, String right) {
        String normalizedLeft = compactText(left);
        String normalizedRight = compactText(right);
        return !normalizedLeft.isBlank() && normalizedLeft.equals(normalizedRight);
    }

    private String compactText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ")
                        .trim();
    }

    private List<AgenticAuthoringCandidate> fastIntentCandidateOptions(List<AgenticAuthoringCandidate> candidateOptions) {
        if (candidateOptions == null || candidateOptions.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringCandidate> explicitCandidates = candidateOptions.stream()
                .filter(candidate -> hasEvidence(candidate, "explicit-source-match"))
                .toList();
        List<AgenticAuthoringCandidate> trustedCandidates = candidateOptions.stream()
                .filter(candidate -> hasEvidence(candidate, "context-hint")
                        || hasEvidence(candidate, "quick-reply-context")
                        || hasEvidence(candidate, "current-page")
                        || hasEvidence(candidate, "explicit-resource-path")
                        || hasEvidence(candidate, "tool-search-api-resources")
                        || hasEvidence(candidate, "domain-catalog-context"))
                .toList();
        List<AgenticAuthoringCandidate> scoped = new ArrayList<>();
        if (!explicitCandidates.isEmpty()) {
            scoped.addAll(explicitCandidates);
            scoped.addAll(trustedCandidates);
        } else if (!trustedCandidates.isEmpty()) {
            scoped.addAll(trustedCandidates);
        } else {
            scoped.addAll(candidateOptions.stream().limit(3).toList());
        }
        return distinctCandidatesByResourcePath(scoped).stream()
                .limit(3)
                .toList();
    }

    private List<AgenticAuthoringCandidate> distinctCandidatesByResourcePath(
            List<AgenticAuthoringCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AgenticAuthoringCandidate> distinct = new ArrayList<>();
        for (AgenticAuthoringCandidate candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String resourcePath = valueOrDefault(candidate.resourcePath(), "");
            String key = resourcePath.isBlank() ? "candidate@" + distinct.size() : resourcePath;
            if (seen.add(key)) {
                distinct.add(candidate);
            }
        }
        return List.copyOf(distinct);
    }

    private boolean fastIntentResolutionComplete(
            AgenticAuthoringLlmIntentResolution resolution,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (resolution == null || !resolution.resolved()) {
            return false;
        }
        if (fastConsultativeResolutionComplete(resolution)) {
            return true;
        }
        if ("modify".equals(valueOrDefault(resolution.operationKind(), ""))) {
            return StringUtils.hasText(resolution.selectedResourcePath())
                    && hasTargetedComponentCapabilities(target, componentCapabilities)
                    && declaredChangeKind(
                            target.componentId(),
                            resolution.changeKind(),
                            componentCapabilities);
        }
        if (!"create".equals(valueOrDefault(resolution.operationKind(), ""))) {
            return false;
        }
        String artifactKind = valueOrDefault(resolution.artifactKind(), "");
        if (!List.of("chart", "dashboard", "table", "page").contains(artifactKind)) {
            return false;
        }
        if (!StringUtils.hasText(resolution.selectedResourcePath())) {
            return false;
        }
        if (List.of("chart", "dashboard").contains(artifactKind)) {
            AgenticAuthoringVisualizationDecision decision = resolution.visualizationDecision();
            return decision != null
                    && StringUtils.hasText(decision.primaryComponent())
                    && decision.axes() != null
                    && !decision.axes().isEmpty();
        }
        return true;
    }

    private boolean fastConsultativeResolutionComplete(AgenticAuthoringLlmIntentResolution resolution) {
        String operationKind = valueOrDefault(resolution.operationKind(), "");
        if (!"explore".equals(operationKind) && !"explain".equals(operationKind)) {
            return false;
        }
        String artifactKind = valueOrDefault(resolution.artifactKind(), "");
        String changeKind = valueOrDefault(resolution.changeKind(), "");
        if ("api_catalog".equals(artifactKind)) {
            return "answer_api_catalog_question".equals(changeKind);
        }
        if ("component".equals(artifactKind)) {
            return "answer_component_catalog_question".equals(changeKind)
                    || "answer_component_capability_question".equals(changeKind);
        }
        if ("domain_decision".equals(artifactKind)) {
            return "explain_domain_decision".equals(changeKind);
        }
        return false;
    }

    private AgenticAuthoringLlmIntentResolution withFastCandidateResourceWhenUnambiguous(
            AgenticAuthoringLlmIntentResolution resolution,
            List<AgenticAuthoringCandidate> fastCandidates) {
        if (resolution == null
                || fastConsultativeResolutionComplete(resolution)
                || StringUtils.hasText(resolution.selectedResourcePath())
                || fastCandidates == null
                || fastCandidates.isEmpty()) {
            return resolution;
        }
        List<AgenticAuthoringCandidate> distinctCandidates = distinctCandidatesByResourcePath(fastCandidates);
        if (distinctCandidates.size() != 1
                || !StringUtils.hasText(distinctCandidates.get(0).resourcePath())) {
            return resolution;
        }
        return new AgenticAuthoringLlmIntentResolution(
                resolution.resolved(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                distinctCandidates.get(0).resourcePath(),
                resolution.resourceSearchQuery(),
                resolution.followUpKind(),
                resolution.assistantMessage(),
                resolution.quickReplies(),
                resolution.clarificationQuestions(),
                resolution.warnings(),
                resolution.consultativeRetrievalPlan(),
                resolution.visualizationDecision(),
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                resolution.queryConstraints(),
                resolution.providerInvocations());
    }

    private AgenticAuthoringLlmIntentResolution withFastIntentWarning(
            AgenticAuthoringLlmIntentResolution resolution) {
        List<String> warnings = new ArrayList<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        if (!warnings.contains("llm-fast-intent-resolution-used")) {
            warnings.add("llm-fast-intent-resolution-used");
        }
        return new AgenticAuthoringLlmIntentResolution(
                resolution.resolved(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.selectedResourcePath(),
                resolution.resourceSearchQuery(),
                resolution.followUpKind(),
                resolution.assistantMessage(),
                resolution.quickReplies(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.consultativeRetrievalPlan(),
                resolution.visualizationDecision(),
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                resolution.queryConstraints(),
                resolution.providerInvocations());
    }

    private AgenticAuthoringLlmIntentResolution withWarning(
            AgenticAuthoringLlmIntentResolution resolution,
            String warning) {
        List<String> warnings = new ArrayList<>(
                resolution.warnings() == null ? List.of() : resolution.warnings());
        if (StringUtils.hasText(warning) && !warnings.contains(warning)) {
            warnings.add(warning);
        }
        return new AgenticAuthoringLlmIntentResolution(
                resolution.resolved(),
                resolution.operationKind(),
                resolution.artifactKind(),
                resolution.changeKind(),
                resolution.selectedResourcePath(),
                resolution.resourceSearchQuery(),
                resolution.followUpKind(),
                resolution.assistantMessage(),
                resolution.quickReplies(),
                resolution.clarificationQuestions(),
                List.copyOf(warnings),
                resolution.consultativeRetrievalPlan(),
                resolution.visualizationDecision(),
                resolution.requiresGovernedAuthoring(),
                resolution.semanticIntentClass(),
                resolution.queryConstraints(),
                resolution.providerInvocations());
    }

    private String fastIntentPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String governedDomainContext) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-fast-intent-context.v1");
        context.put("userPrompt", valueOrDefault(effectivePrompt, request.userPrompt()));
        context.put("route", valueOrDefault(request.currentRoute(), ""));
        context.set("currentPageSummary", currentPageSummary == null ? objectMapper.createObjectNode() : currentPageSummary);
        context.set(
                "governedDomainContext",
                AgenticAuthoringContextBundle.create(
                                objectMapper,
                                request,
                                effectivePrompt,
                                currentPageSummary,
                                target,
                                candidateOptions,
                                componentCapabilities,
                                governedDomainContext)
                        .path("governedDomainContext")
                        .deepCopy());
        JsonNode authoringScopePolicy = AgenticAuthoringContextBundle.authoringScopePolicy(request);
        if (authoringScopePolicy != null) {
            context.set("authoringScopePolicy", authoringScopePolicy);
        }
        JsonNode selectedDomainDecisionRef = request.contextHints() == null
                ? null
                : request.contextHints().path("selectedDomainDecisionRef");
        if (selectedDomainDecisionRef != null && selectedDomainDecisionRef.isObject()) {
            context.putObject("contextHints")
                    .set("selectedDomainDecisionRef", selectedDomainDecisionRef.deepCopy());
        }
        JsonNode resourceDiscovery = request.contextHints() == null
                ? null
                : request.contextHints().path("resourceDiscovery");
        if (resourceDiscovery != null && resourceDiscovery.isObject()) {
            ObjectNode semanticRetrievalIntent = context.putObject("semanticRetrievalIntent");
            semanticRetrievalIntent.put(
                    "artifactKind",
                    valueOrDefault(resourceDiscovery.path("artifactKind").asText(""), ""));
            if (resourceDiscovery.path("resourceSearchFocus").isObject()) {
                semanticRetrievalIntent.set(
                        "resourceSearchFocus",
                        resourceDiscovery.path("resourceSearchFocus").deepCopy());
            }
        }
        JsonNode liveOptionFieldGrounding = request.contextHints() == null
                ? null
                : request.contextHints().path("liveOptionFieldGrounding");
        if (liveOptionFieldGrounding != null && liveOptionFieldGrounding.isObject()) {
            context.set("liveOptionFieldGrounding", liveOptionFieldGrounding.deepCopy());
        }
        JsonNode liveOptionValueGrounding = request.contextHints() == null
                ? null
                : request.contextHints().path("liveOptionValueGrounding");
        if (liveOptionValueGrounding != null && liveOptionValueGrounding.isObject()) {
            context.set("liveOptionValueGrounding", liveOptionValueGrounding.deepCopy());
        }
        if (target != null) {
            ObjectNode targetNode = context.putObject("target");
            targetNode.put("widgetKey", valueOrDefault(target.widgetKey(), ""));
            targetNode.put("componentId", valueOrDefault(target.componentId(), ""));
            targetNode.put("resourcePath", valueOrDefault(target.resourcePath(), ""));
        }
        if (request.activeSemanticDecision() != null) {
            AgenticAuthoringSemanticDecision activeDecision = request.activeSemanticDecision();
            ObjectNode active = context.putObject("activeSemanticDecision");
            active.put("decisionId", valueOrDefault(activeDecision.decisionId(), ""));
            active.put("operationKind", valueOrDefault(activeDecision.operationKind(), ""));
            active.put("artifactKind", valueOrDefault(activeDecision.artifactKind(), ""));
            active.put("changeKind", valueOrDefault(activeDecision.changeKind(), ""));
            active.put("activeObjective", valueOrDefault(activeDecision.activeObjective(), ""));
            active.put("visualIntent", valueOrDefault(activeDecision.visualIntent(), ""));
            if (activeDecision.selectedResource() != null) {
                active.put("selectedResourcePath", valueOrDefault(
                        activeDecision.selectedResource().resourcePath(), ""));
            }
        }
        if (request.conversationMessages() != null && !request.conversationMessages().isEmpty()) {
            ArrayNode conversation = context.putArray("recentConversation");
            List<AgenticAuthoringConversationMessage> messages = request.conversationMessages();
            int start = Math.max(0, messages.size() - 4);
            for (int index = start; index < messages.size(); index++) {
                AgenticAuthoringConversationMessage message = messages.get(index);
                if (message == null || !StringUtils.hasText(message.text())) {
                    continue;
                }
                ObjectNode item = conversation.addObject();
                item.put("role", valueOrDefault(message.role(), ""));
                item.put("text", message.text());
            }
        }
        ArrayNode resources = context.putArray("candidateResources");
        JsonNode discoveredCandidates = resourceDiscovery == null
                ? objectMapper.createArrayNode()
                : resourceDiscovery.path("candidates");
        for (AgenticAuthoringCandidate candidate : candidateOptions == null ? List.<AgenticAuthoringCandidate>of() : candidateOptions) {
            ObjectNode item = resources.addObject();
            item.put("resourcePath", valueOrDefault(candidate.resourcePath(), ""));
            item.put("operation", valueOrDefault(candidate.operation(), ""));
            item.put("reason", valueOrDefault(candidate.reason(), ""));
            ArrayNode evidence = item.putArray("evidence");
            for (String value : candidate.evidence() == null ? List.<String>of() : candidate.evidence()) {
                if (StringUtils.hasText(value)) {
                    evidence.add(value);
                }
            }
            JsonNode analyticsFields = analyticsFieldsForCandidate(discoveredCandidates, candidate.resourcePath());
            if (analyticsFields.isArray() && !analyticsFields.isEmpty()) {
                item.set("analyticsFields", analyticsFields.deepCopy());
            }
            AgenticAuthoringEvidenceBundle evidenceBundle = candidate.evidenceBundle();
            if (evidenceBundle != null) {
                ObjectNode bundle = item.putObject("evidenceBundle");
                bundle.put("retrievalSource", valueOrDefault(evidenceBundle.retrievalSource(), ""));
                ArrayNode evidenceItems = bundle.putArray("items");
                List<AgenticAuthoringEvidenceBundle.Evidence> values =
                        evidenceBundle.evidence() == null ? List.of() : evidenceBundle.evidence();
                for (int index = 0; index < Math.min(values.size(), 3); index++) {
                    AgenticAuthoringEvidenceBundle.Evidence evidenceItem = values.get(index);
                    if (evidenceItem == null) {
                        continue;
                    }
                    ObjectNode evidenceNode = evidenceItems.addObject();
                    evidenceNode.put("source", valueOrDefault(evidenceItem.source(), ""));
                    evidenceNode.put("kind", valueOrDefault(evidenceItem.kind(), ""));
                    evidenceNode.put("ref", valueOrDefault(evidenceItem.ref(), ""));
                    evidenceNode.put("summary", valueOrDefault(evidenceItem.summary(), ""));
                    ArrayNode matchedTerms = evidenceNode.putArray("matchedTerms");
                    List<String> terms = evidenceItem.matchedTerms() == null ? List.of() : evidenceItem.matchedTerms();
                    for (int termIndex = 0; termIndex < Math.min(terms.size(), 12); termIndex++) {
                        String term = terms.get(termIndex);
                        if (StringUtils.hasText(term)) {
                            matchedTerms.add(term);
                        }
                    }
                }
            }
        }
        ArrayNode components = context.putArray("authorableComponents");
        if (componentCapabilities != null && componentCapabilities.catalogs() != null) {
            for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
                if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
                    continue;
                }
                ObjectNode component = components.addObject();
                component.put("componentId", catalog.componentId());
                ArrayNode capabilities = component.putArray("capabilities");
                List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability> values =
                        catalog.capabilities() == null ? List.of() : catalog.capabilities();
                for (int index = 0; index < Math.min(values.size(), 4); index++) {
                    AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability = values.get(index);
                    if (capability != null && StringUtils.hasText(capability.changeKind())) {
                        capabilities.add(capability.changeKind());
                    }
                }
            }
        }
        JsonNode rankedCapabilities = AgenticAuthoringContextBundle.create(
                        objectMapper,
                        request,
                        effectivePrompt,
                        currentPageSummary,
                        target,
                        candidateOptions,
                        componentCapabilities,
                        governedDomainContext)
                .path("componentContext")
                .path("componentCapabilities");
        context.set("rankedComponentCapabilities", rankedCapabilities.deepCopy());
        return """
                You are the fast semantic intent resolver for Praxis governed page authoring.
                Return only one JSON object matching the supplied schema.

                Decide from the user's meaning, not from backend keywords.
                Set semanticIntentClass to the primary AI-authored semantic decision: platform_guidance, api_catalog_guidance, domain_decision_guidance, component_authoring, shared_rule_authoring, out_of_scope, or unknown.
                Treat semanticRetrievalIntent as prior AI-authored semantic evidence; reconcile it rather than silently replacing a concrete artifact with an unrelated container.
                When conversationContext.contextHints.preIntentSemanticOrientation contains primaryComponent, preserve
                that prior AI-authored host decision unless later governed component or resource evidence proves it
                incompatible. In particular, do not replace praxis-crud with a generic form or master-detail page merely
                because both can display fields.
                Treat activeSemanticDecision and recentConversation as prior governed lineage for the current refinement, not as permission to ignore the new user request.
                When the user's primary meaning is to reverse only the most recently materialized local change,
                select semanticIntentClass "component_authoring", operationKind "undo", keep artifactKind aligned
                with the selected artifact (for example "table" for praxis-table or "form" for a form),
                changeKind "undo_last_local_change", resolved=true and requiresGovernedAuthoring=false. Use
                conversationContext.contextHints.clientActions only as the declared local capability/availability
                catalog; never invent an action and never reinterpret undo as a component configuration edit.
                Availability does not change the semantic intent because the runtime handles unavailable actions.
                Select selectedResourcePath only from candidateResources.
                When exactly one candidateResource is supplied and it matches the requested source, copy its resourcePath into selectedResourcePath.
                When the requested output displays records of one business entity constrained by a related category or
                master-data dimension, select the displayed record entity as selectedResourcePath. Keep the related
                category as a filter concept; never select the category resource merely because it ranks slightly higher.
                Select visualizationDecision.primaryComponent only from authorableComponents.
                For an edit to an existing selected component, choose changeKind from its governed capability candidates. Compare their semantic examples before deciding; candidate order is grounding only. Do not use an operation that only changes a property of an existing target when the requested outcome introduces a new schema-backed item. A row-dependent visual outcome governed by a condition must use a conditional-renderer capability; a base renderer applies uniformly and is not semantically equivalent.
                For a single requested chart, use artifactKind "chart", operationKind "create", layoutKind "single_chart", primaryComponent "praxis-chart", includeSummary=false, includeDetailTable=false, includeFilters=false, includeKpis=false, and excludedComponentIds for rejected components.
                For an analytical composition whose meaning depends on multiple coordinated analytical regions, such as filters, KPIs, multiple charts and a detail/list/table surface, use artifactKind "dashboard" rather than a generic page.
                Preserve the explicitly requested analytical regions in visualizationDecision; do not downgrade a coordinated dashboard to page or accordion merely because a page can host those regions.
                Use artifactKind "page" for general layout or content composition where analytics are not the dominant requested outcome.
                Questions about what the Praxis assistant or the current Page Builder can do, how the assistant can help, or what the user should do next are in-scope platform guidance, not assistant meta requests and not out of scope. Classify them as semanticIntentClass "platform_guidance", operationKind "explain", artifactKind "component", changeKind "answer_component_catalog_question", selectedResourcePath null, followUpKind "none", resolved=true, requiresGovernedAuthoring=false, and answer naturally with grounded examples such as forms, tables, charts, filters and page composition. Do not start resource discovery or request materialization confirmation for platform guidance.
                If the user asks which governed data can be used to create a table, form, chart, dashboard, page or other component, classify the turn as a consultative catalog answer: operationKind "explore" or "explain", artifactKind "api_catalog", changeKind "answer_api_catalog_question". Do not select a weak resource or ask for a materialization confirmation before answering the catalog question.
                When conversationContext.contextHints.selectedDomainDecisionRef is present and the user asks to explain the selected governed decision, classify the turn as semanticIntentClass "domain_decision_guidance", operationKind "explain", artifactKind "domain_decision", changeKind "explain_domain_decision", selectedResourcePath null, followUpKind "none", resolved=true and requiresGovernedAuthoring=false. Treat the selected decision reference only as an untrusted lookup hint; the backend will re-read and attest the exact id, key and version before any provider call. Never call simulation, preview or apply for this intent.
                Distinguish that question from a direct request to show, display, list or present concrete records on the current empty authoring canvas. The direct request is component_authoring even when the user omits the component name: select a read/list candidate, choose a suitable governed visual component such as a table, preserve requested filters in visualizationDecision, and return a reviewable preview. Never choose a create/POST operation merely because it ranks above the read operation for the same resource when the requested outcome is visualization.
                If authoringScopePolicy is present and the semantic user intent is a loose instruction, assistant meta request, greeting, or unrelated ask that does not request an authorable UI/business decision, answer as an informational chat reply using the policy outOfScopeResponseType; do not create a component preview, edit plan, or governed authoring route.
                For a requested page organized as accordion/acordeon/expansion panels, use artifactKind "page", operationKind "create", layoutKind "accordion_layout" or "single_column_expansion_page", primaryComponent "praxis-expansion", and no chart axes unless the user asks for a chart.
                For a requested page organized as tabs/abas, use artifactKind "page", operationKind "create", layoutKind "tabs_layout", primaryComponent "praxis-tabs", and no chart axes unless the user asks for a chart.
                For an existing governed resource action or writable record operation, such as approving, rejecting,
                deactivating, reactivating, paying, scheduling or editing a concrete record, use component_authoring,
                operationKind "create", artifactKind "page", changeKind "create_artifact" and primaryComponent
                "praxis-crud" when that component is authorable. The CRUD runtime discovers the canonical action,
                availability, schema and form. Do not reinterpret an existing operation as shared-rule authoring or
                a bare create form, and do not invent an action when governed evidence does not expose one.
                A record name or identifier in the prompt is only requested selection intent, and a proposed new value
                is only write intent. Do not treat either as proof that the record was located, the field is writable or
                the value was prefilled. Preserve the selection predicate for governed lookup/materialization and require
                explicit runtime or tool evidence before representing those facts as confirmed.
                For chart axes, use the grouping/time field in axes[].field and numeric measures in metricField/metricAggregation.
                When a candidate publishes analyticsFields, choose grouping/time and metric fields exclusively from that
                governed catalog, copy their field names exactly, and use only published allowedAggregations. Do not
                translate canonical identifiers into conceptual aliases. Only when analyticsFields is absent may field
                names be proposed from the user's wording and other candidate evidence; canonical schema validation runs
                after this step and may correct or reject them.
                Set requiresGovernedAuthoring=true for reusable governed business decisions, policies, compliance/access/eligibility/approval/privacy/enforcement rules, backend validations, option-source eligibility, approval gates, or shared rules that must go through shared-rule authoring.
                When requiresGovernedAuthoring=true, do not classify the turn as a materializable dashboard, chart, table, form or page preview. Use operationKind "create" or "modify", artifactKind "unknown", changeKind "route_shared_rule_authoring", and leave visualizationDecision null.
                Keep requiresGovernedAuthoring=false only for local visual formatting, masks, badges, labels, component configuration, layout, filters, columns, and consultative catalog questions.
                For component authoring that requests a data subset, set queryConstraints.appliesToDataSelection=true and
                preserve every requested predicate in queryConstraints.filters. Set appliesToDataSelection=false and
                filters=[] when the request changes presentation rather than the selected backend records. Headers, labels,
                renderers, formatting, composed cells, and displayed-value mappings are presentation intent even when
                their text resembles a current record value.
                When liveOptionFieldGrounding is present, it is a governed projection of canonical option-source filter
                fields for the already selected resource. Resolve the predicate's business concept against the meaning,
                label and description of those candidates. When one candidate is semantically appropriate, copy its
                canonicalFilterField exactly into the predicate while preserving the original semantic text value,
                whether a single concept or a list of concepts, for the subsequent live-value lookup. A concrete record
                name or identifier used to locate a writable record is not automatically an option-source dimension. If
                its predicate already has a governed non-option field, preserve that field; absence from this candidate
                list means option-source grounding is not applicable. Never select the sole candidate merely because it
                is the only one. Do not choose from word overlap or invent a field. If candidates are
                materially ambiguous, ask a natural clarification before any value lookup or materialization.
                When liveOptionValueGrounding is present, it is a post-intent enumeration of current governed master-data
                values for exactly one canonical filter field. Reason semantically over all candidates. If the intended
                membership is sufficiently clear, set that predicate field to canonicalFilterField, operator to "in",
                and value to an array containing only ids present in candidates. Never send labels or the original semantic
                category as a textual contains filter. If membership is materially ambiguous, return a natural clarification
                with structured quick replies and do not claim that the filter was materialized.
                Author field with the best semantic business-field name supported by governed evidence. When the canonical
                schema name is not yet available, propose a concise conceptual field name instead of null; post-intent
                schema grounding must validate, canonicalize or reject it before materialization. Preserve concept,
                operator and value independently so no predicate is lost if field grounding needs clarification.
                Do not omit a predicate merely because the resource itself was resolved.
                Contrast the semantic scope before choosing an artifact:
                - "Create a rule so blocked suppliers cannot be selected in purchases" is a reusable business constraint: requiresGovernedAuthoring=true, artifactKind "unknown", changeKind "route_shared_rule_authoring".
                - "Show a blocked-supplier badge in this local table" is local presentation: requiresGovernedAuthoring=false and may materialize a table edit.
                - "Which governed supplier data can I use in a dashboard?" is a consultative catalog question: requiresGovernedAuthoring=false, operationKind "explore", artifactKind "api_catalog".
                Never reinterpret a requested business rule as a dashboard or page merely because the selected resource exposes fields that could be visualized.
                If the requested source/component cannot be resolved with this compact evidence, set resolved=false and leave visualizationDecision null.
                Keep assistantMessage short and natural in the user's language.
                Always include quickReplies, clarificationQuestions, warnings, visualizationDecision, consultativeRetrievalPlan and queryConstraints fields.

                Compact context:
                %s
                """.formatted(context.toPrettyString());
    }

    private JsonNode analyticsFieldsForCandidate(JsonNode discoveredCandidates, String resourcePath) {
        if (discoveredCandidates == null || !discoveredCandidates.isArray() || !StringUtils.hasText(resourcePath)) {
            return objectMapper.createArrayNode();
        }
        for (JsonNode candidate : discoveredCandidates) {
            if (resourcePath.equals(candidate.path("resourcePath").asText(""))
                    && candidate.path("analyticsFields").isArray()) {
                return candidate.path("analyticsFields");
            }
        }
        return objectMapper.createArrayNode();
    }

    private boolean hasTargetedComponentCapabilities(
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        return target != null
                && StringUtils.hasText(target.widgetKey())
                && StringUtils.hasText(target.componentId())
                && componentCapabilities != null
                && componentCapabilities.catalogs() != null
                && componentCapabilities.catalogs().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(catalog -> target.componentId().equals(catalog.componentId())
                                && catalog.capabilities() != null
                                && !catalog.capabilities().isEmpty());
    }

    private boolean declaredChangeKind(
            String componentId,
            String changeKind,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (!StringUtils.hasText(componentId)
                || !StringUtils.hasText(changeKind)
                || componentCapabilities == null
                || componentCapabilities.catalogs() == null) {
            return false;
        }
        return componentCapabilities.catalogs().stream()
                .filter(Objects::nonNull)
                .filter(catalog -> componentId.equals(catalog.componentId()))
                .flatMap(catalog -> (catalog.capabilities() == null
                        ? List.<AgenticAuthoringComponentCapabilitiesResult.ComponentCapability>of()
                        : catalog.capabilities()).stream())
                .filter(Objects::nonNull)
                .anyMatch(capability -> changeKind.equals(capability.changeKind()));
    }

    private boolean forceFullIntentResolution(AgenticAuthoringIntentResolutionRequest request) {
        return request != null
                && request.contextHints() != null
                && request.contextHints().path("semanticReconciliation")
                        .path("forceFullIntentResolution").asBoolean(false);
    }

    private boolean hasEvidence(AgenticAuthoringCandidate candidate, String evidence) {
        return candidate != null
                && candidate.evidence() != null
                && candidate.evidence().stream().anyMatch(evidence::equals);
    }

    private String providerFailureKind(Throwable error) {
        if (error instanceof AiProviderCallException callException) {
            return switch (callException.getKind()) {
                case AUTH -> "auth-error";
                case RATE_LIMIT -> "rate-limit";
                case QUOTA_EXHAUSTED -> "quota-exhausted";
                case CAPACITY -> "capacity";
                case TIMEOUT -> "timeout";
                case TRANSPORT -> "transport-error";
                case CLIENT_ERROR -> "client-error";
                case SERVER_ERROR -> "server-error";
                case UNKNOWN -> "unknown-error";
            };
        }
        String message = (error == null ? "" : String.valueOf(error.getMessage())).toLowerCase(Locale.ROOT);
        if (message.contains("401") || message.contains("403") || message.contains("unauthorized")
                || message.contains("forbidden") || message.contains("api key")) {
            return "auth-error";
        }
        if (message.contains("insufficient_quota")
                || message.contains("quota exhausted")
                || message.contains("quota exceeded")
                || message.contains("exceeded your current quota")
                || message.contains("billing")) {
            return "quota-exhausted";
        }
        if (message.contains("429") || message.contains("rate limit")) {
            return "rate-limit";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "timeout";
        }
        if (message.contains("connect") || message.contains("socket") || message.contains("unknownhost")) {
            return "transport-error";
        }
        if (message.contains("500") || message.contains("502") || message.contains("503")
                || message.contains("504")) {
            return "server-error";
        }
        if (message.contains("400") || message.contains("bad request") || message.contains("client error")) {
            return "client-error";
        }
        return "unknown-error";
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? error : current;
    }

    private String safeProviderFailureSummary(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String name = error.getClass().getSimpleName();
        if (error instanceof AiProviderCallException callException) {
            String status = callException.getStatusCode() == null
                    ? "none"
                    : String.valueOf(callException.getStatusCode());
            return name + "{provider=" + callException.getProvider()
                    + ", kind=" + callException.getKind()
                    + ", status=" + status + "}";
        }
        return name;
    }

    JsonNode diagnosticSnapshot(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String environment) {
        PromptInput promptInput = promptInput(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidateOptions,
                componentCapabilities,
                governedDomainContext(request, effectivePrompt, tenantId, environment));
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-llm-diagnostics.v1");
        diagnostics.put("promptTemplateId", SYSTEM_PROMPT_TEMPLATE_ID);
        diagnostics.set("contextBundle", promptInput.contextBundle());
        diagnostics.put("prompt", promptInput.prompt());
        return diagnostics;
    }

    JsonNode diagnosticProjection(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode contextBundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidateOptions,
                componentCapabilities,
                "");
        JsonNode governedDomainNode = contextBundle.path("governedDomainContext");
        ObjectNode governedDomain = governedDomainNode instanceof ObjectNode objectNode
                ? objectNode
                : contextBundle.putObject("governedDomainContext");
        governedDomain.put("available", false);
        governedDomain.put("resolutionStatus", "not_recaptured_for_diagnostics");
        governedDomain.put("diagnosticProjectionOnly", true);
        governedDomain.put(
                "usageRule",
                "The governed domain context used during semantic execution is intentionally not queried again for diagnostics.");
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-llm-diagnostics.v1");
        diagnostics.put("promptTemplateId", SYSTEM_PROMPT_TEMPLATE_ID);
        diagnostics.put("captureKind", "non_retrieving_projection");
        diagnostics.put("exactProviderPromptIncluded", false);
        diagnostics.set("contextBundle", contextBundle);
        return diagnostics;
    }

    private PromptInput promptInput(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String governedDomainContext) {
        ObjectNode contextBundle = AgenticAuthoringContextBundle.create(
                objectMapper,
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidateOptions,
                componentCapabilities,
                governedDomainContext);
        return new PromptInput(
                contextBundle,
                SYSTEM_PROMPT_TEMPLATE.formatted(contextBundle.toPrettyString()));
    }

    private String governedDomainContext(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            String tenantId,
            String environment) {
        if (domainCatalogPromptContextService == null || request == null) {
            return "";
        }
        try {
            ObjectNode contextHints = request.contextHints() != null && request.contextHints().isObject()
                    ? request.contextHints().deepCopy()
                    : objectMapper.createObjectNode();
            if (!contextHints.path("domainCatalog").isObject()
                    && !StringUtils.hasText(contextHints.path("domainCatalogServiceKey").asText(""))) {
                contextHints.putObject("domainCatalog").put("enabled", true);
            }
            return StringUtils.hasText(effectivePrompt)
                    ? domainCatalogPromptContextService.buildPromptContext(
                            effectivePrompt,
                            contextHints,
                            tenantId,
                            environment)
                    : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private Optional<AgenticAuthoringLlmIntentResolution> toResolution(JsonNode result) {
        if (result == null || !result.isObject()) {
            return Optional.empty();
        }
        if (missingRequiredIntentFields(result)) {
            log.warn("Ignoring incomplete LLM intent resolution payload; required semantic routing fields are missing.");
            return Optional.empty();
        }
        boolean resolved = result.path("resolved").asBoolean(false);
        List<AgenticAuthoringQuickReply> quickReplies = quickReplies(result.path("quickReplies"));
        List<String> clarificationQuestions = strings(result.path("clarificationQuestions"));
        List<String> warnings = strings(result.path("warnings"));
        String operationKind = text(result, "operationKind");
        String artifactKind = text(result, "artifactKind");
        String changeKind = text(result, "changeKind");
        String selectedResourcePath = nullableText(result, "selectedResourcePath");
        String resourceSearchQuery = nullableText(result, "resourceSearchQuery");
        String followUpKind = text(result, "followUpKind");
        String assistantMessage = conciseAssistantMessage(nullableText(result, "assistantMessage"));
        AgenticAuthoringVisualizationDecision visualizationDecision =
                visualizationDecision(result.path("visualizationDecision"));
        AgenticAuthoringConsultativeRetrievalPlan consultativeRetrievalPlan =
                consultativeRetrievalPlan(result.path("consultativeRetrievalPlan"));
        boolean requiresGovernedAuthoring = result.path("requiresGovernedAuthoring").asBoolean(false);
        String semanticIntentClass = semanticIntentClass(
                nullableText(result, "semanticIntentClass"),
                operationKind,
                artifactKind,
                changeKind,
                requiresGovernedAuthoring);
        JsonNode queryConstraints = result.path("queryConstraints").isObject()
                ? result.path("queryConstraints").deepCopy()
                : null;
        if ("platform_guidance".equals(semanticIntentClass)) {
            boolean tupleAlreadyConsistent = resolved
                    && "explain".equals(operationKind)
                    && "component".equals(artifactKind)
                    && "answer_component_catalog_question".equals(changeKind)
                    && selectedResourcePath == null
                    && visualizationDecision == null
                    && !requiresGovernedAuthoring;
            if (!tupleAlreadyConsistent) {
                ArrayList<String> normalizedWarnings = new ArrayList<>(warnings);
                if (!normalizedWarnings.contains("llm-semantic-intent-tuple-normalized")) {
                    normalizedWarnings.add("llm-semantic-intent-tuple-normalized");
                }
                warnings = List.copyOf(normalizedWarnings);
            }
            resolved = true;
            operationKind = "explain";
            artifactKind = "component";
            changeKind = "answer_component_catalog_question";
            selectedResourcePath = null;
            resourceSearchQuery = null;
            followUpKind = "none";
            clarificationQuestions = List.of();
            visualizationDecision = null;
            requiresGovernedAuthoring = false;
        } else if ("domain_decision_guidance".equals(semanticIntentClass)) {
            boolean tupleAlreadyConsistent = resolved
                    && "explain".equals(operationKind)
                    && "domain_decision".equals(artifactKind)
                    && "explain_domain_decision".equals(changeKind)
                    && selectedResourcePath == null
                    && visualizationDecision == null
                    && !requiresGovernedAuthoring;
            if (!tupleAlreadyConsistent) {
                ArrayList<String> normalizedWarnings = new ArrayList<>(warnings);
                if (!normalizedWarnings.contains("llm-semantic-intent-tuple-normalized")) {
                    normalizedWarnings.add("llm-semantic-intent-tuple-normalized");
                }
                warnings = List.copyOf(normalizedWarnings);
            }
            resolved = true;
            operationKind = "explain";
            artifactKind = "domain_decision";
            changeKind = "explain_domain_decision";
            selectedResourcePath = null;
            resourceSearchQuery = null;
            followUpKind = "none";
            clarificationQuestions = List.of();
            visualizationDecision = null;
            requiresGovernedAuthoring = false;
        } else if ("shared_rule_authoring".equals(semanticIntentClass)) {
            boolean tupleAlreadyConsistent = resolved
                    && ("create".equals(operationKind) || "modify".equals(operationKind))
                    && "unknown".equals(artifactKind)
                    && "route_shared_rule_authoring".equals(changeKind)
                    && visualizationDecision == null
                    && requiresGovernedAuthoring;
            if (!tupleAlreadyConsistent) {
                ArrayList<String> normalizedWarnings = new ArrayList<>(warnings);
                if (!normalizedWarnings.contains("llm-semantic-intent-tuple-normalized")) {
                    normalizedWarnings.add("llm-semantic-intent-tuple-normalized");
                }
                warnings = List.copyOf(normalizedWarnings);
            }
            resolved = true;
            operationKind = "modify".equals(operationKind) ? "modify" : "create";
            artifactKind = "unknown";
            changeKind = "route_shared_rule_authoring";
            visualizationDecision = null;
            requiresGovernedAuthoring = true;
        } else if ("component_authoring".equals(semanticIntentClass)
                && resolved
                && "create".equals(operationKind)
                && !"unknown".equals(artifactKind)
                && ("materialize".equals(changeKind)
                        || "materialize_component".equals(changeKind)
                        || "author_component".equals(changeKind))
                && ("none".equals(followUpKind) || "new_instruction".equals(followUpKind))) {
            ArrayList<String> normalizedWarnings = new ArrayList<>(warnings);
            if (!normalizedWarnings.contains("llm-semantic-intent-tuple-normalized")) {
                normalizedWarnings.add("llm-semantic-intent-tuple-normalized");
            }
            warnings = List.copyOf(normalizedWarnings);
            changeKind = "create_artifact";
        }
        AgenticAuthoringVisualizationDecision normalizedVisualizationDecision =
                normalizeVisualizationDecisionForArtifact(
                        resolved,
                        operationKind,
                        artifactKind,
                        visualizationDecision);
        if (normalizedVisualizationDecision != visualizationDecision) {
            ArrayList<String> normalizedWarnings = new ArrayList<>(warnings);
            if (!normalizedWarnings.contains("llm-visualization-primary-component-normalized")) {
                normalizedWarnings.add("llm-visualization-primary-component-normalized");
            }
            warnings = List.copyOf(normalizedWarnings);
            visualizationDecision = normalizedVisualizationDecision;
        }
        if (!resolved
                && operationKind.isBlank()
                && artifactKind.isBlank()
                && changeKind.isBlank()
                && (assistantMessage == null || assistantMessage.isBlank())
                && quickReplies.isEmpty()
                && clarificationQuestions.isEmpty()
                && warnings.isEmpty()
                && consultativeRetrievalPlan == null
                && visualizationDecision == null) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringLlmIntentResolution(
                resolved,
                operationKind,
                artifactKind,
                changeKind,
                selectedResourcePath,
                resourceSearchQuery,
                followUpKind,
                assistantMessage,
                quickReplies,
                clarificationQuestions,
                warnings,
                consultativeRetrievalPlan,
                visualizationDecision,
                requiresGovernedAuthoring,
                semanticIntentClass,
                queryConstraints,
                List.of()));
    }

    private AgenticAuthoringVisualizationDecision normalizeVisualizationDecisionForArtifact(
            boolean resolved,
            String operationKind,
            String artifactKind,
            AgenticAuthoringVisualizationDecision decision) {
        if (!resolved
                || !"create".equals(operationKind)
                || !"dashboard".equals(artifactKind)
                || decision == null
                || !"praxis-crud".equals(valueOrDefault(decision.primaryComponent(), ""))) {
            return decision;
        }
        boolean hasAnalyticalAxes = decision.axes() != null && !decision.axes().isEmpty();
        boolean chartExcluded = decision.excludedComponentIds() != null
                && decision.excludedComponentIds().contains("praxis-chart");
        String primaryComponent = hasAnalyticalAxes && !chartExcluded
                ? "praxis-chart"
                : "praxis-dynamic-page-builder";
        return new AgenticAuthoringVisualizationDecision(
                decision.schemaVersion(),
                decision.intent(),
                decision.layoutKind(),
                primaryComponent,
                decision.axes(),
                decision.includeSummary(),
                decision.includeDetailTable(),
                decision.excludedComponentIds(),
                decision.includeFilters(),
                decision.includeKpis(),
                valueOrDefault(decision.provenance(), "llm-authored-semantic-decision")
                        + " + canonical-component-alignment");
    }

    private String semanticIntentClass(
            String authoredClass,
            String operationKind,
            String artifactKind,
            String changeKind,
            boolean requiresGovernedAuthoring) {
        if (StringUtils.hasText(authoredClass)) {
            return authoredClass;
        }
        if (requiresGovernedAuthoring || "route_shared_rule_authoring".equals(changeKind)) {
            return "shared_rule_authoring";
        }
        if (("explain".equals(operationKind) || "explore".equals(operationKind))
                && "api_catalog".equals(artifactKind)
                && "answer_api_catalog_question".equals(changeKind)) {
            return "api_catalog_guidance";
        }
        if (("explain".equals(operationKind) || "explore".equals(operationKind))
                && "component".equals(artifactKind)
                && ("answer_component_catalog_question".equals(changeKind)
                        || "answer_component_capability_question".equals(changeKind))) {
            return "platform_guidance";
        }
        if ("explain".equals(operationKind)
                && "domain_decision".equals(artifactKind)
                && "explain_domain_decision".equals(changeKind)) {
            return "domain_decision_guidance";
        }
        if (!"unknown".equals(operationKind) && !"unknown".equals(artifactKind)) {
            return "component_authoring";
        }
        return "unknown";
    }

    private boolean missingRequiredIntentFields(JsonNode result) {
        return !result.has("requiresGovernedAuthoring");
    }

    private AgenticAuthoringConsultativeRetrievalPlan consultativeRetrievalPlan(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<String> requiredContext = strings(node.path("requiredContext"));
        List<String> semanticQueries = strings(node.path("semanticQueries"));
        List<String> expectedEvidence = strings(node.path("expectedEvidence"));
        String answerStrategy = text(node, "answerStrategy");
        if (requiredContext.isEmpty() && semanticQueries.isEmpty() && expectedEvidence.isEmpty() && answerStrategy.isBlank()) {
            return null;
        }
        return new AgenticAuthoringConsultativeRetrievalPlan(
                valueOrDefault(nullableText(node, "schemaVersion"), "praxis-agentic-authoring-consultative-retrieval-plan.v1"),
                requiredContext,
                semanticQueries,
                answerStrategy,
                expectedEvidence);
    }

    private AgenticAuthoringVisualizationDecision visualizationDecision(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<AgenticAuthoringVisualizationAxisDecision> axes = visualizationAxes(node.path("axes"));
        String intent = text(node, "intent");
        String layoutKind = text(node, "layoutKind");
        String primaryComponent = valueOrDefault(nullableText(node, "primaryComponentId"), text(node, "primaryComponent"));
        if (intent.isBlank() && layoutKind.isBlank() && primaryComponent.isBlank() && axes.isEmpty()) {
            return null;
        }
        return new AgenticAuthoringVisualizationDecision(
                valueOrDefault(nullableText(node, "schemaVersion"), "praxis-agentic-authoring-visualization-decision.v1"),
                intent,
                layoutKind,
                primaryComponent,
                axes,
                node.path("includeSummary").asBoolean(true),
                node.path("includeDetailTable").asBoolean(true),
                strings(node.path("excludedComponentIds")),
                node.path("includeFilters").asBoolean(true),
                node.path("includeKpis").asBoolean(true),
                valueOrDefault(nullableText(node, "provenance"), "llm-authored-semantic-decision"));
    }

    private List<AgenticAuthoringVisualizationAxisDecision> visualizationAxes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AgenticAuthoringVisualizationAxisDecision> axes = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String field = text(item, "field");
            String concept = text(item, "concept");
            if (field.isBlank() || concept.isBlank()) {
                continue;
            }
            axes.add(new AgenticAuthoringVisualizationAxisDecision(
                    concept,
                    field,
                    valueOrDefault(nullableText(item, "label"), field),
                    valueOrDefault(nullableText(item, "chartType"), "bar"),
                    valueOrDefault(nullableText(item, "orientation"), "vertical"),
                    valueOrDefault(nullableText(item, "metricAggregation"), "count"),
                    nullableText(item, "metricField"),
                    valueOrDefault(nullableText(item, "metricLabel"), "Total"),
                    valueOrDefault(nullableText(item, "provenance"), "llm-authored-semantic-axis")));
        }
        return List.copyOf(axes);
    }

    private List<AgenticAuthoringQuickReply> quickReplies(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        for (JsonNode item : node) {
            String id = text(item, "id");
            String kind = text(item, "kind");
            String label = text(item, "label");
            String prompt = text(item, "prompt");
            if (id.isBlank() || kind.isBlank() || label.isBlank()) {
                continue;
            }
            if (isRedactedPrompt(prompt)) {
                prompt = label;
            }
            replies.add(new AgenticAuthoringQuickReply(
                    id,
                    kind,
                    label,
                    prompt,
                    nullableText(item, "description"),
                    nullableText(item, "icon"),
                    nullableText(item, "tone"),
                    item.path("contextHints").isObject() ? item.path("contextHints") : null,
                    item.path("semanticDecision").isObject() ? item.path("semanticDecision") : null,
                    item.path("value").isMissingNode() ? null : item.path("value")));
        }
        return List.copyOf(replies);
    }

    private String conciseAssistantMessage(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.length() <= MAX_ASSISTANT_MESSAGE_CHARS) {
            return assistantMessage;
        }
        int cutoff = assistantMessage.lastIndexOf('.', MAX_ASSISTANT_MESSAGE_CHARS - 1);
        if (cutoff < 240) {
            cutoff = assistantMessage.lastIndexOf(' ', MAX_ASSISTANT_MESSAGE_CHARS - 1);
        }
        if (cutoff < 240) {
            cutoff = MAX_ASSISTANT_MESSAGE_CHARS - 1;
        }
        return assistantMessage.substring(0, cutoff).trim() + "...";
    }

    private boolean isRedactedPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        return value.isBlank() || "[REDACTED]".equalsIgnoreCase(value);
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private String schema() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        properties.putObject("resolved").put("type", "boolean");
        stringEnum(properties, "operationKind", List.of("create", "modify", "remove", "compose", "connect", "undo", "explore", "explain", "unknown"));
        stringEnum(properties, "artifactKind", List.of("dashboard", "chart", "table", "form", "page", "api_catalog", "component", "domain_decision", "unknown"));
        stringEnum(properties, "semanticIntentClass", List.of(
                "platform_guidance",
                "api_catalog_guidance",
                "domain_decision_guidance",
                "component_authoring",
                "shared_rule_authoring",
                "out_of_scope",
                "unknown"));
        properties.putObject("changeKind")
                .put("type", "string")
                .put("description", "Canonical governed materialization change selected from the matching component capability when available. Distinguish adding a new item from changing a property such as the position, format, visibility, or label of an existing target.");
        nullableString(properties, "selectedResourcePath");
        nullableString(properties, "resourceSearchQuery");
        stringEnum(properties, "followUpKind", List.of(
                "clarification_answer",
                "new_instruction",
                "refinement",
                "api_catalog_followup",
                "none",
                "unknown"));
        nullableString(properties, "assistantMessage");
        properties.putObject("requiresGovernedAuthoring").put("type", "boolean");
        arrayOfStrings(properties, "clarificationQuestions");
        arrayOfStrings(properties, "warnings");
        properties.set("visualizationDecision", visualizationDecisionSchema());
        properties.set("consultativeRetrievalPlan", consultativeRetrievalPlanSchema());
        properties.set("queryConstraints", queryConstraintsSchema());

        ObjectNode reply = objectMapper.createObjectNode();
        reply.put("type", "object");
        ObjectNode replyProps = reply.putObject("properties");
        replyProps.putObject("id").put("type", "string");
        replyProps.putObject("kind").put("type", "string");
        replyProps.putObject("label").put("type", "string");
        replyProps.putObject("prompt").put("type", "string");
        nullableString(replyProps, "description");
        nullableString(replyProps, "icon");
        nullableString(replyProps, "tone");
        // Provider-authored replies are presentation suggestions. Canonical contextHints,
        // semanticDecision and value are enriched by the governed backend, not accepted as an
        // arbitrary open object from the model.
        ArrayNode replyRequired = reply.putArray("required");
        replyRequired.add("id")
                .add("kind")
                .add("label")
                .add("prompt")
                .add("description")
                .add("icon")
                .add("tone");
        reply.put("additionalProperties", false);
        properties.putObject("quickReplies")
                .put("type", "array")
                .set("items", reply);

        ArrayNode required = root.putArray("required");
        required.add("resolved")
                .add("operationKind")
                .add("artifactKind")
                .add("semanticIntentClass")
                .add("changeKind")
                .add("selectedResourcePath")
                .add("resourceSearchQuery")
                .add("followUpKind")
                .add("assistantMessage")
                .add("requiresGovernedAuthoring")
                .add("visualizationDecision")
                .add("consultativeRetrievalPlan")
                .add("queryConstraints")
                .add("quickReplies")
                .add("clarificationQuestions")
                .add("warnings");
        root.put("additionalProperties", false);
        return root.toString();
    }

    private ObjectNode queryConstraintsSchema() {
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("type", "object");
        ObjectNode properties = constraints.putObject("properties");
        properties.putObject("appliesToDataSelection")
                .put("type", "boolean")
                .put(
                        "description",
                        "True only when filters constrain which backend records are retrieved or displayed; false for headers, labels, renderers, formatting, composed cells, and displayed-value edits.");
        ObjectNode filters = properties.putObject("filters");
        filters.put("type", "array");
        ObjectNode filter = filters.putObject("items");
        filter.put("type", "object");
        ObjectNode filterProperties = filter.putObject("properties");
        filterProperties.putObject("concept").put("type", "string");
        filterProperties.putObject("field").put("type", "string");
        stringEnum(filterProperties, "operator", List.of("eq", "contains", "in", "gte", "lte", "between"));
        ObjectNode value = filterProperties.putObject("value");
        ArrayNode valueAlternatives = value.putArray("anyOf");
        valueAlternatives.addObject().put("type", "string");
        valueAlternatives.addObject().put("type", "number");
        valueAlternatives.addObject().put("type", "boolean");
        valueAlternatives.addObject().put("type", "null");
        ObjectNode valueArray = valueAlternatives.addObject();
        valueArray.put("type", "array");
        valueArray.put("minItems", 1);
        valueArray.put("maxItems", 100);
        ObjectNode valueArrayItems = valueArray.putObject("items");
        ArrayNode valueArrayItemTypes = valueArrayItems.putArray("anyOf");
        valueArrayItemTypes.addObject().put("type", "string");
        valueArrayItemTypes.addObject().put("type", "number");
        valueArrayItemTypes.addObject().put("type", "boolean");
        filter.putArray("required").add("concept").add("field").add("operator").add("value");
        filter.put("additionalProperties", false);
        constraints.putArray("required").add("appliesToDataSelection").add("filters");
        constraints.put("additionalProperties", false);
        return constraints;
    }

    private ObjectNode consultativeRetrievalPlanSchema() {
        ObjectNode plan = objectMapper.createObjectNode();
        ArrayNode types = plan.putArray("type");
        types.add("object").add("null");
        ObjectNode properties = plan.putObject("properties");
        properties.putObject("schemaVersion").put("type", "string");
        ObjectNode requiredContext = properties.putObject("requiredContext");
        requiredContext.put("type", "array");
        ObjectNode requiredContextItems = requiredContext.putObject("items");
        requiredContextItems.put("type", "string");
        requiredContextItems.putArray("enum")
                .add("platform_capabilities")
                .add("component_registry")
                .add("domain_catalog")
                .add("api_resources")
                .add("runtime_context")
                .add("conversation_context");
        arrayOfStrings(properties, "semanticQueries");
        properties.putObject("answerStrategy").put("type", "string");
        arrayOfStrings(properties, "expectedEvidence");
        plan.putArray("required")
                .add("schemaVersion")
                .add("requiredContext")
                .add("semanticQueries")
                .add("answerStrategy")
                .add("expectedEvidence");
        plan.put("additionalProperties", false);
        return plan;
    }

    private ObjectNode visualizationDecisionSchema() {
        ObjectNode decision = objectMapper.createObjectNode();
        ArrayNode decisionTypes = decision.putArray("type");
        decisionTypes.add("object").add("null");
        ObjectNode properties = decision.putObject("properties");
        properties.putObject("schemaVersion").put("type", "string");
        properties.putObject("intent").put("type", "string");
        properties.putObject("layoutKind").put("type", "string");
        properties.putObject("primaryComponent").put("type", "string");
        nullableString(properties, "primaryComponentId");
        properties.putObject("includeSummary").put("type", "boolean");
        properties.putObject("includeDetailTable").put("type", "boolean");
        arrayOfStrings(properties, "excludedComponentIds");
        properties.putObject("includeFilters").put("type", "boolean");
        properties.putObject("includeKpis").put("type", "boolean");
        properties.putObject("provenance").put("type", "string");

        ObjectNode axis = objectMapper.createObjectNode();
        axis.put("type", "object");
        ObjectNode axisProperties = axis.putObject("properties");
        axisProperties.putObject("concept").put("type", "string");
        axisProperties.putObject("field").put("type", "string");
        axisProperties.putObject("label").put("type", "string");
        stringEnum(axisProperties, "chartType", List.of("bar", "horizontal-bar", "line", "area", "pie", "donut"));
        stringEnum(axisProperties, "orientation", List.of("vertical", "horizontal", "temporal"));
        stringEnum(axisProperties, "metricAggregation", List.of("count", "sum", "avg", "min", "max"));
        nullableString(axisProperties, "metricField");
        axisProperties.putObject("metricLabel").put("type", "string");
        axisProperties.putObject("provenance").put("type", "string");
        axis.putArray("required")
                .add("concept")
                .add("field")
                .add("label")
                .add("chartType")
                .add("orientation")
                .add("metricAggregation")
                .add("metricField")
                .add("metricLabel")
                .add("provenance");
        axis.put("additionalProperties", false);

        properties.putObject("axes")
                .put("type", "array")
                .set("items", axis);
        decision.putArray("required")
                .add("schemaVersion")
                .add("intent")
                .add("layoutKind")
                .add("primaryComponent")
                .add("primaryComponentId")
                .add("axes")
                .add("includeSummary")
                .add("includeDetailTable")
                .add("excludedComponentIds")
                .add("includeFilters")
                .add("includeKpis")
                .add("provenance");
        decision.put("additionalProperties", false);
        return decision;
    }

    private void nullableString(ObjectNode properties, String name) {
        ArrayNode types = properties.putObject(name).putArray("type");
        types.add("string").add("null");
    }

    private void arrayOfStrings(ObjectNode properties, String name) {
        properties.putObject(name)
                .put("type", "array")
                .putObject("items")
                .put("type", "string");
    }

    private void stringEnum(ObjectNode properties, String name, List<String> values) {
        ObjectNode field = properties.putObject(name);
        field.put("type", "string");
        ArrayNode allowed = field.putArray("enum");
        values.forEach(allowed::add);
    }

    private String text(JsonNode node, String field) {
        return nullableText(node, field) == null ? "" : nullableText(node, field);
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : null;
    }

    private static String loadSystemPromptTemplate() {
        try (InputStream inputStream = AgenticAuthoringLlmIntentResolverService.class
                .getClassLoader()
                .getResourceAsStream(SYSTEM_PROMPT_TEMPLATE_ID)) {
            if (inputStream == null) {
                throw new IllegalStateException("Agentic authoring system prompt not found: " + SYSTEM_PROMPT_TEMPLATE_ID);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read agentic authoring system prompt: " + SYSTEM_PROMPT_TEMPLATE_ID, exception);
        }
    }

    private record PromptInput(
            ObjectNode contextBundle,
            String prompt) {
    }
}
