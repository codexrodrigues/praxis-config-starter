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
    private static final int MAX_FAST_INTENT_RESOLUTION_TOKENS = 1800;
    private static final int MAX_INTENT_RESOLUTION_TOKENS = 4096;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final DomainCatalogPromptContextService domainCatalogPromptContextService;

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper) {
        this(providerManagementService, objectMapper, null);
    }

    public AgenticAuthoringLlmIntentResolverService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            DomainCatalogPromptContextService domainCatalogPromptContextService) {
        this.providerManagementService = Objects.requireNonNull(providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.domainCatalogPromptContextService = domainCatalogPromptContextService;
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
        try {
            Optional<AgenticAuthoringLlmIntentResolution> fastResolution = fastIntentResolution(
                    request,
                    effectivePrompt,
                    currentPageSummary,
                    target,
                    usableCandidates,
                    componentCapabilities,
                    tenantId,
                    userId,
                    environment);
            if (fastResolution.isPresent()) {
                return fastResolution;
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
            JsonNode result = providerManagementService.generateJson(
                    promptInput.prompt(),
                    AiJsonSchema.ofSchema(schema()),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(MAX_INTENT_RESOLUTION_TOKENS)
                            .timeoutSeconds(15)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            return toResolution(result);
        } catch (RuntimeException ex) {
            return Optional.of(failedResolution(ex));
        }
    }

    private AgenticAuthoringLlmIntentResolution failedResolution(RuntimeException ex) {
        Throwable rootCause = rootCause(ex);
        log.warn(
                "[AgenticAuthoringLlmIntentResolver] Provider intent resolution failed; kind={} cause={}",
                providerFailureKind(rootCause),
                safeProviderFailureSummary(rootCause));
        return new AgenticAuthoringLlmIntentResolution(
                false,
                "unknown",
                "unknown",
                "unknown",
                null,
                null,
                "provider_error",
                "Tive um problema para concluir essa leitura agora. Posso continuar com o recurso mais provavel, mas antes de criar algo preciso confirmar se ele representa o dominio certo.",
                List.of(),
                List.of("Qual recurso de negocio deve orientar esta decisao?"),
                providerFailureWarnings(rootCause),
                null,
                null);
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
            String environment) {
        List<AgenticAuthoringCandidate> fastCandidates = fastIntentCandidateOptions(candidateOptions);
        if (!shouldTryFastIntentResolution(request, effectivePrompt, target, fastCandidates)) {
            return Optional.empty();
        }
        try {
            JsonNode result = providerManagementService.generateJson(
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
                            .timeoutSeconds(15)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            Optional<AgenticAuthoringLlmIntentResolution> resolution =
                    toResolution(result).map(value -> withFastCandidateResourceWhenUnambiguous(value, fastCandidates));
            if (resolution.isPresent() && fastIntentResolutionComplete(resolution.get())) {
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

    private String fastIntentRejectionReason(AgenticAuthoringLlmIntentResolution resolution) {
        if (resolution == null) {
            return "empty-resolution";
        }
        if (!resolution.resolved()) {
            return "unresolved";
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
            List<AgenticAuthoringCandidate> candidateOptions) {
        if (request == null
                || request.pendingClarification() != null
                || request.activeSemanticDecision() != null
                || hasConversationHistoryBeyondCurrentPrompt(request, effectivePrompt)
                || candidateOptions == null
                || candidateOptions.isEmpty()) {
            return false;
        }
        if (target != null && StringUtils.hasText(target.widgetKey())) {
            return false;
        }
        return candidateOptions.stream()
                .anyMatch(candidate -> hasEvidence(candidate, "explicit-source-match")
                        || hasEvidence(candidate, "context-hint")
                        || hasEvidence(candidate, "quick-reply-context")
                        || hasEvidence(candidate, "current-page")
                        || hasEvidence(candidate, "explicit-resource-path")
                        || hasEvidence(candidate, "tool-search-api-resources")
                        || hasEvidence(candidate, "domain-catalog-context"));
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

    private boolean fastIntentResolutionComplete(AgenticAuthoringLlmIntentResolution resolution) {
        if (resolution == null || !resolution.resolved()) {
            return false;
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

    private AgenticAuthoringLlmIntentResolution withFastCandidateResourceWhenUnambiguous(
            AgenticAuthoringLlmIntentResolution resolution,
            List<AgenticAuthoringCandidate> fastCandidates) {
        if (resolution == null
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
                resolution.requiresGovernedAuthoring());
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
                resolution.requiresGovernedAuthoring());
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
        if (target != null) {
            ObjectNode targetNode = context.putObject("target");
            targetNode.put("widgetKey", valueOrDefault(target.widgetKey(), ""));
            targetNode.put("componentId", valueOrDefault(target.componentId(), ""));
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
        return """
                You are the fast semantic intent resolver for Praxis governed page authoring.
                Return only one JSON object matching the supplied schema.

                Decide from the user's meaning, not from backend keywords.
                Select selectedResourcePath only from candidateResources.
                When exactly one candidateResource is supplied and it matches the requested source, copy its resourcePath into selectedResourcePath.
                Select visualizationDecision.primaryComponent only from authorableComponents.
                For a single requested chart, use artifactKind "chart", operationKind "create", layoutKind "single_chart", primaryComponent "praxis-chart", includeSummary=false, includeDetailTable=false, includeFilters=false, includeKpis=false, and excludedComponentIds for rejected components.
                For a requested page organized as accordion/acordeon/expansion panels, use artifactKind "page", operationKind "create", layoutKind "accordion_layout" or "single_column_expansion_page", primaryComponent "praxis-expansion", and no chart axes unless the user asks for a chart.
                For a requested page organized as tabs/abas, use artifactKind "page", operationKind "create", layoutKind "tabs_layout", primaryComponent "praxis-tabs", and no chart axes unless the user asks for a chart.
                For chart axes, use the grouping/time field in axes[].field and numeric measures in metricField/metricAggregation.
                Field names may be proposed from the user's wording and candidate evidence; canonical schema validation runs after this step and may correct or reject them.
                Set requiresGovernedAuthoring=true only for reusable governed business decisions, policies, compliance/access/eligibility/approval/privacy/enforcement rules that must go through shared-rule authoring. Keep it false for local visual formatting, masks, badges, labels, component configuration, layout, filters, and columns.
                If the requested source/component cannot be resolved with this compact evidence, set resolved=false and leave visualizationDecision null.
                Keep assistantMessage short and natural in the user's language.
                Always include quickReplies, clarificationQuestions, warnings, visualizationDecision and consultativeRetrievalPlan fields.

                Compact context:
                %s
                """.formatted(context.toPrettyString());
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
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        PromptInput promptInput = promptInput(
                request,
                effectivePrompt,
                currentPageSummary,
                target,
                candidateOptions,
                componentCapabilities,
                null,
                null);
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
                requiresGovernedAuthoring));
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
                    item.path("contextHints").isObject() ? item.path("contextHints") : null));
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
        properties.putObject("changeKind").put("type", "string");
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
