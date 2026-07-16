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
    private static final int MAX_FAST_INTENT_RESOLUTION_TOKENS = 1800;
    private static final int MAX_INTENT_RESOLUTION_TOKENS = 4096;
    private static final int DEFAULT_FAST_INTENT_TIMEOUT_SECONDS = 12;
    private static final int DEFAULT_FULL_INTENT_TIMEOUT_SECONDS = 30;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final DomainCatalogPromptContextService domainCatalogPromptContextService;
    private final int fastIntentTimeoutSeconds;
    private final int fullIntentTimeoutSeconds;

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
                DEFAULT_FULL_INTENT_TIMEOUT_SECONDS);
    }

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService,
            int fastIntentTimeoutSeconds,
            int fullIntentTimeoutSeconds) {
        this.providerManagementService = Objects.requireNonNull(providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.domainCatalogPromptContextService = domainCatalogPromptContextService;
        this.fastIntentTimeoutSeconds = positiveOrDefault(
                fastIntentTimeoutSeconds,
                DEFAULT_FAST_INTENT_TIMEOUT_SECONDS);
        this.fullIntentTimeoutSeconds = positiveOrDefault(
                fullIntentTimeoutSeconds,
                DEFAULT_FULL_INTENT_TIMEOUT_SECONDS);
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
        try {
            Optional<AgenticAuthoringLlmIntentResolution> platformGuidanceConfirmation =
                    compactPlatformGuidanceConfirmation(
                            request,
                            effectivePrompt,
                            target,
                            componentCapabilities,
                            tenantId,
                            userId,
                            environment,
                            providerInvocations);
            if (platformGuidanceConfirmation.isPresent()) {
                return platformGuidanceConfirmation.map(value -> withProviderInvocations(value, providerInvocations));
            }
            Optional<AgenticAuthoringLlmIntentResolution> fastResolution = fastIntentResolution(
                    request,
                    effectivePrompt,
                    currentPageSummary,
                    target,
                    usableCandidates,
                    componentCapabilities,
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
                    tenantId,
                    environment);
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
            return toResolution(result).map(value -> withProviderInvocations(value, providerInvocations));
        } catch (RuntimeException ex) {
            return Optional.of(withProviderInvocations(failedResolution(ex, request), providerInvocations));
        }
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
            String tenantId,
            String userId,
            String environment,
            List<AiProviderInvocationTelemetry> providerInvocations) {
        List<AgenticAuthoringCandidate> fastCandidates = fastIntentCandidateOptions(candidateOptions);
        if (!shouldTryFastIntentResolution(request, effectivePrompt, target, fastCandidates, componentCapabilities)) {
            return Optional.empty();
        }
        try {
            JsonNode result = invokeJson(
                    "intent_fast",
                    fastIntentPrompt(
                            request,
                            effectivePrompt,
                            currentPageSummary,
                            target,
                            fastCandidates,
                            componentCapabilities),
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
            Optional<AgenticAuthoringLlmIntentResolution> resolution =
                    toResolution(result).map(value -> withFastCandidateResourceWhenUnambiguous(value, fastCandidates));
            if (resolution.isPresent() && fastIntentResolutionComplete(
                    resolution.get(),
                    target,
                    componentCapabilities)) {
                return resolution.map(this::withFastIntentWarning);
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

    private Optional<AgenticAuthoringLlmIntentResolution> compactPlatformGuidanceConfirmation(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
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
                            componentCapabilities),
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
            return Optional.empty();
        }
    }

    private String compactPlatformGuidancePrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-platform-guidance-confirmation-context.v1");
        context.put("userPrompt", valueOrDefault(effectivePrompt, request.userPrompt()));
        context.put("route", valueOrDefault(request.currentRoute(), ""));
        context.set("recommendedIntent", request.contextHints().path("recommendedIntent").deepCopy());
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

                When the scope matches, answer naturally in the user's language using only the governed component
                capabilities supplied here. Be friendly, concise and concrete. Mention useful examples such as
                forms, tables, charts, filters or page composition only when supported by the supplied catalog,
                and finish with one helpful next action stated declaratively. Do not ask a follow-up question or
                request confirmation in this advisory answer. Do not claim that anything was already created or applied.
                When the scope does not match, use an empty assistantMessage.

                Compact governed context:
                %s
                """.formatted(context.toPrettyString());
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
        boolean targetedComponentEdit = hasTargetedComponentCapabilities(target, componentCapabilities);
        if (!targetedComponentEdit
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
                resolution.semanticIntentClass());
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
                resolution.semanticIntentClass());
    }

    private String fastIntentPrompt(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("schemaVersion", "praxis-agentic-authoring-fast-intent-context.v1");
        context.put("userPrompt", valueOrDefault(effectivePrompt, request.userPrompt()));
        context.put("route", valueOrDefault(request.currentRoute(), ""));
        context.set("currentPageSummary", currentPageSummary == null ? objectMapper.createObjectNode() : currentPageSummary);
        JsonNode authoringScopePolicy = AgenticAuthoringContextBundle.authoringScopePolicy(request);
        if (authoringScopePolicy != null) {
            context.set("authoringScopePolicy", authoringScopePolicy);
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
                        "")
                .path("componentContext")
                .path("componentCapabilities");
        context.set("rankedComponentCapabilities", rankedCapabilities.deepCopy());
        return """
                You are the fast semantic intent resolver for Praxis governed page authoring.
                Return only one JSON object matching the supplied schema.

                Decide from the user's meaning, not from backend keywords.
                Set semanticIntentClass to the primary AI-authored semantic decision: platform_guidance, api_catalog_guidance, component_authoring, shared_rule_authoring, out_of_scope, or unknown.
                Treat semanticRetrievalIntent as prior AI-authored semantic evidence; reconcile it rather than silently replacing a concrete artifact with an unrelated container.
                Treat activeSemanticDecision and recentConversation as prior governed lineage for the current refinement, not as permission to ignore the new user request.
                Select selectedResourcePath only from candidateResources.
                When exactly one candidateResource is supplied and it matches the requested source, copy its resourcePath into selectedResourcePath.
                Select visualizationDecision.primaryComponent only from authorableComponents.
                For an edit to an existing selected component, choose changeKind from its governed capability candidates. Compare their semantic examples before deciding; candidate order is grounding only. Do not use an operation that only changes a property of an existing target when the requested outcome introduces a new schema-backed item.
                For a single requested chart, use artifactKind "chart", operationKind "create", layoutKind "single_chart", primaryComponent "praxis-chart", includeSummary=false, includeDetailTable=false, includeFilters=false, includeKpis=false, and excludedComponentIds for rejected components.
                For an analytical composition whose meaning depends on multiple coordinated analytical regions, such as filters, KPIs, multiple charts and a detail/list/table surface, use artifactKind "dashboard" rather than a generic page.
                Preserve the explicitly requested analytical regions in visualizationDecision; do not downgrade a coordinated dashboard to page or accordion merely because a page can host those regions.
                Use artifactKind "page" for general layout or content composition where analytics are not the dominant requested outcome.
                Questions about what the Praxis assistant or the current Page Builder can do, how the assistant can help, or what the user should do next are in-scope platform guidance, not assistant meta requests and not out of scope. Classify them as semanticIntentClass "platform_guidance", operationKind "explain", artifactKind "component", changeKind "answer_component_catalog_question", selectedResourcePath null, followUpKind "none", resolved=true, requiresGovernedAuthoring=false, and answer naturally with grounded examples such as forms, tables, charts, filters and page composition. Do not start resource discovery or request materialization confirmation for platform guidance.
                If the user asks which governed data can be used to create a table, form, chart, dashboard, page or other component, classify the turn as a consultative catalog answer: operationKind "explore" or "explain", artifactKind "api_catalog", changeKind "answer_api_catalog_question". Do not select a weak resource or ask for a materialization confirmation before answering the catalog question.
                If authoringScopePolicy is present and the semantic user intent is a loose instruction, assistant meta request, greeting, or unrelated ask that does not request an authorable UI/business decision, answer as an informational chat reply using the policy outOfScopeResponseType; do not create a component preview, edit plan, or governed authoring route.
                For a requested page organized as accordion/acordeon/expansion panels, use artifactKind "page", operationKind "create", layoutKind "accordion_layout" or "single_column_expansion_page", primaryComponent "praxis-expansion", and no chart axes unless the user asks for a chart.
                For a requested page organized as tabs/abas, use artifactKind "page", operationKind "create", layoutKind "tabs_layout", primaryComponent "praxis-tabs", and no chart axes unless the user asks for a chart.
                For chart axes, use the grouping/time field in axes[].field and numeric measures in metricField/metricAggregation.
                Field names may be proposed from the user's wording and candidate evidence; canonical schema validation runs after this step and may correct or reject them.
                Set requiresGovernedAuthoring=true for reusable governed business decisions, policies, compliance/access/eligibility/approval/privacy/enforcement rules, backend validations, option-source eligibility, approval gates, or shared rules that must go through shared-rule authoring.
                When requiresGovernedAuthoring=true, do not classify the turn as a materializable dashboard, chart, table, form or page preview. Use operationKind "create" or "modify", artifactKind "unknown", changeKind "route_shared_rule_authoring", and leave visualizationDecision null.
                Keep requiresGovernedAuthoring=false only for local visual formatting, masks, badges, labels, component configuration, layout, filters, columns, and consultative catalog questions.
                Contrast the semantic scope before choosing an artifact:
                - "Create a rule so blocked suppliers cannot be selected in purchases" is a reusable business constraint: requiresGovernedAuthoring=true, artifactKind "unknown", changeKind "route_shared_rule_authoring".
                - "Show a blocked-supplier badge in this local table" is local presentation: requiresGovernedAuthoring=false and may materialize a table edit.
                - "Which governed supplier data can I use in a dashboard?" is a consultative catalog question: requiresGovernedAuthoring=false, operationKind "explore", artifactKind "api_catalog".
                Never reinterpret a requested business rule as a dashboard or page merely because the selected resource exposes fields that could be visualized.
                If the requested source/component cannot be resolved with this compact evidence, set resolved=false and leave visualizationDecision null.
                Keep assistantMessage short and natural in the user's language.
                Always include quickReplies, clarificationQuestions, warnings, visualizationDecision and consultativeRetrievalPlan fields.

                Compact context:
                %s
                """.formatted(context.toPrettyString());
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
                tenantId,
                environment);
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-agentic-authoring-llm-diagnostics.v1");
        diagnostics.put("promptTemplateId", SYSTEM_PROMPT_TEMPLATE_ID);
        diagnostics.set("contextBundle", promptInput.contextBundle());
        diagnostics.put("prompt", promptInput.prompt());
        return diagnostics;
    }

    private PromptInput promptInput(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String environment) {
        String governedDomainContext = governedDomainContext(request, effectivePrompt, tenantId, environment);
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
        if (domainCatalogPromptContextService == null || request == null || request.contextHints() == null) {
            return "";
        }
        try {
            return StringUtils.hasText(effectivePrompt)
                    ? domainCatalogPromptContextService.buildPromptContext(
                            effectivePrompt,
                            request.contextHints(),
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
                && ("materialize".equals(changeKind) || "materialize_component".equals(changeKind))
                && ("none".equals(followUpKind) || "new_instruction".equals(followUpKind))) {
            ArrayList<String> normalizedWarnings = new ArrayList<>(warnings);
            if (!normalizedWarnings.contains("llm-semantic-intent-tuple-normalized")) {
                normalizedWarnings.add("llm-semantic-intent-tuple-normalized");
            }
            warnings = List.copyOf(normalizedWarnings);
            changeKind = "create_artifact";
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
                semanticIntentClass));
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
        stringEnum(properties, "operationKind", List.of("create", "modify", "remove", "compose", "connect", "explore", "explain", "unknown"));
        stringEnum(properties, "artifactKind", List.of("dashboard", "chart", "table", "form", "page", "api_catalog", "component", "unknown"));
        stringEnum(properties, "semanticIntentClass", List.of(
                "platform_guidance",
                "api_catalog_guidance",
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
        replyProps.putObject("contextHints").put("type", "object").put("additionalProperties", true);
        ArrayNode replyRequired = reply.putArray("required");
        replyRequired.add("id").add("kind").add("label").add("prompt");
        reply.put("additionalProperties", true);
        properties.putObject("quickReplies")
                .put("type", "array")
                .set("items", reply);

        ArrayNode required = root.putArray("required");
        required.add("resolved")
                .add("operationKind")
                .add("artifactKind")
                .add("semanticIntentClass")
                .add("changeKind")
                .add("followUpKind")
                .add("requiresGovernedAuthoring")
                .add("visualizationDecision")
                .add("consultativeRetrievalPlan")
                .add("quickReplies")
                .add("clarificationQuestions")
                .add("warnings");
        root.put("additionalProperties", false);
        return root.toString();
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
