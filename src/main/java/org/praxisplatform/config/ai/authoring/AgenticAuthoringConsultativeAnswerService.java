package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.util.StringUtils;

@Slf4j
public class AgenticAuthoringConsultativeAnswerService {
    private static final int MAX_CONVERSATION_REFERENCE_MESSAGES = 8;
    private static final int MAX_CONVERSATION_REFERENCE_CHARS = 4_000;
    private static final int MAX_CONVERSATION_REFERENCE_MESSAGE_CHARS = 900;
    private static final long MAX_RUNTIME_DISAMBIGUATION_CONTEXT_TTL_MS = 300_000L;

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService;
    private final AgenticAuthoringToolRegistry toolRegistry;
    private final RuntimeToolPlannerPolicy runtimeToolPlannerPolicy;
    private final RuntimeRelatedSurfaceIntentPolicy runtimeRelatedSurfaceIntentPolicy;

    public AgenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService) {
        this(providerManagementService, objectMapper, apiCatalogProjectionService, null);
    }

    public AgenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService,
            AgenticAuthoringToolRegistry toolRegistry) {
        this(providerManagementService, objectMapper, apiCatalogProjectionService, toolRegistry,
                RuntimeToolPlannerPolicy.singleReadBeta());
    }

    public AgenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService,
            AgenticAuthoringToolRegistry toolRegistry,
            String runtimeToolPolicyRef) {
        this(providerManagementService, objectMapper, apiCatalogProjectionService, toolRegistry,
                runtimeToolPolicyRef, "", "");
    }

    public AgenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService,
            AgenticAuthoringToolRegistry toolRegistry,
            String runtimeToolPolicyRef,
            String runtimeRelatedSurfaceIntentPolicyRef,
            String temporalComparisonFieldRef) {
        this(providerManagementService, objectMapper, apiCatalogProjectionService, toolRegistry,
                RuntimeToolPlannerPolicy.fromConfiguredPolicyRef(runtimeToolPolicyRef),
                RuntimeRelatedSurfaceIntentPolicy.fromConfiguredPolicyRef(
                        runtimeRelatedSurfaceIntentPolicyRef,
                        temporalComparisonFieldRef));
    }

    AgenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService,
            AgenticAuthoringToolRegistry toolRegistry,
            RuntimeToolPlannerPolicy runtimeToolPlannerPolicy) {
        this(providerManagementService, objectMapper, apiCatalogProjectionService, toolRegistry,
                runtimeToolPlannerPolicy, RuntimeRelatedSurfaceIntentPolicy.llm());
    }

    AgenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService,
            AgenticAuthoringToolRegistry toolRegistry,
            RuntimeToolPlannerPolicy runtimeToolPlannerPolicy,
            RuntimeRelatedSurfaceIntentPolicy runtimeRelatedSurfaceIntentPolicy) {
        this.providerManagementService = Objects.requireNonNull(
                providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.apiCatalogProjectionService = apiCatalogProjectionService;
        this.toolRegistry = toolRegistry;
        this.runtimeToolPlannerPolicy = runtimeToolPlannerPolicy == null
                ? RuntimeToolPlannerPolicy.singleReadBeta()
                : runtimeToolPlannerPolicy;
        this.runtimeRelatedSurfaceIntentPolicy = runtimeRelatedSurfaceIntentPolicy == null
                ? RuntimeRelatedSurfaceIntentPolicy.llm()
                : runtimeRelatedSurfaceIntentPolicy;
    }

    public Optional<AgenticAuthoringConsultativeAnswer> answer(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment) {
        if (request == null || !StringUtils.hasText(request.userPrompt())) {
            return Optional.empty();
        }
        boolean domainAvailabilityQuestion = isDomainAvailabilityQuestion(request.userPrompt());
        ObjectNode initialRuntimeContext = runtimeConsultableContext(request);
        boolean pendingRuntimeDisambiguationContext =
                safePendingRuntimeRelatedSurfaceDisambiguationContext(request, initialRuntimeContext) != null;
        if (!pendingRuntimeDisambiguationContext
                && !domainAvailabilityQuestion
                && clearlyRequestsMaterialization(request.userPrompt())) {
            return Optional.empty();
        }
        boolean explicitNoMaterialization = explicitlyForbidsMaterialization(request.userPrompt());
        AgenticAuthoringConsultativeApiCatalogProjection projection = null;
        try {
            if (!domainAvailabilityQuestion || pendingRuntimeDisambiguationContext) {
                Optional<AgenticAuthoringConsultativeAnswer> earlyRuntimeContextAnswer =
                        runtimeContextConsultativeAnswer(request, null, tenantId, userId, environment);
                if (earlyRuntimeContextAnswer.isPresent()) {
                    return earlyRuntimeContextAnswer;
                }
            }
            if (domainAvailabilityQuestion) {
                projection = apiCatalogProjection(
                        request.userPrompt(),
                        tenantId,
                        environment);
                if (pendingRuntimeDisambiguationContext) {
                    Optional<AgenticAuthoringConsultativeAnswer> runtimeContextAnswer =
                            runtimeContextConsultativeAnswer(request, projection, tenantId, userId, environment);
                    if (runtimeContextAnswer.isPresent()) {
                        return runtimeContextAnswer;
                    }
                }
                String unsupportedDomainMessage = AgenticAuthoringConsultativeGroundingAlignment.unsupportedDomainMessage(
                        request.userPrompt(),
                        projection == null ? List.of() : projection.resources());
                if (StringUtils.hasText(unsupportedDomainMessage)) {
                    return Optional.of(new AgenticAuthoringConsultativeAnswer(
                            "domain_api",
                            changeKind("domain_api"),
                            unsupportedDomainMessage,
                            null,
                            warnings("domain_api", null)));
                }
                if (shouldUseGroundedProjectionAnswer(projection)) {
                    return Optional.of(new AgenticAuthoringConsultativeAnswer(
                            "domain_api",
                            changeKind("domain_api"),
                            sanitizeUserFacingAnswer(projection.assistantMessage()),
                            projection,
                            warnings("domain_api", projection)));
                }
                String generated = providerManagementService.generateText(
                        directAnswerPrompt(
                                request,
                                evidenceBundle(request, "domain_api", componentCapabilities, projection)),
                        AiCallConfig.builder()
                                .provider(request.provider())
                                .model(request.model())
                                .apiKey(request.apiKey())
                                .temperature(0.2d)
                                .maxTokens(2400)
                                .build(),
                        tenantId,
                        userId,
                        environment);
                ParsedConsultativeAnswer parsed = parseConsultativeAnswer(generated);
                String category = category(parsed.category());
                String fallback = fallbackMessage("domain_api", projection, componentCapabilities);
                String message = guardedDomainAnswer(
                        request.userPrompt(),
                        "domain_api",
                        projection,
                        safeAnswer(parsed.answer(), safeAnswer(generated, fallback)));
                if (message.isBlank()) {
                    return explicitNoMaterializationFallback(request.userPrompt(), componentCapabilities, projection);
                }
                return Optional.of(new AgenticAuthoringConsultativeAnswer(
                        "domain_api".equals(category) ? category : "domain_api",
                        changeKind("domain_api"),
                        message,
                        projection,
                        warnings("domain_api", projection)));
            }
            if (explicitNoMaterialization) {
                if (isComponentCatalogQuestion(request.userPrompt())) {
                    String message = componentCatalogFallbackMessage(componentCapabilities);
                    if (StringUtils.hasText(message)) {
                        return Optional.of(new AgenticAuthoringConsultativeAnswer(
                                "component_catalog",
                                changeKind("component_catalog"),
                                message,
                                null,
                                warnings("component_catalog", null)));
                    }
                }
                String generated = providerManagementService.generateText(
                        directAnswerPrompt(request, evidenceBundle(request, "auto", componentCapabilities, null)),
                        AiCallConfig.builder()
                                .provider(request.provider())
                                .model(request.model())
                                .apiKey(request.apiKey())
                                .temperature(0.2d)
                                .maxTokens(2400)
                                .build(),
                        tenantId,
                        userId,
                        environment);
                ParsedConsultativeAnswer parsed = parseConsultativeAnswer(generated);
                String category = category(parsed.category());
                if ("domain_api".equals(category)) {
                    projection = apiCatalogProjection(
                            request.userPrompt(),
                            tenantId,
                            environment);
                    Optional<AgenticAuthoringConsultativeAnswer> runtimeContextAnswer =
                            runtimeContextConsultativeAnswer(request, projection, tenantId, userId, environment);
                    if (runtimeContextAnswer.isPresent()) {
                        return runtimeContextAnswer;
                    }
                    if (projection != null && projection.hasResources()) {
                        generated = providerManagementService.generateText(
                                directAnswerPrompt(
                                        request,
                                        evidenceBundle(request, category, componentCapabilities, projection)),
                                AiCallConfig.builder()
                                        .provider(request.provider())
                                        .model(request.model())
                                        .apiKey(request.apiKey())
                                        .temperature(0.2d)
                                        .maxTokens(2400)
                                        .build(),
                                tenantId,
                                userId,
                                environment);
                        parsed = parseConsultativeAnswer(generated);
                        category = category(parsed.category());
                    }
                }
                String fallback = fallbackMessage(category, projection, componentCapabilities);
                String message = guardedDomainAnswer(
                        request.userPrompt(),
                        category,
                        projection,
                        safeAnswer(parsed.answer(), safeAnswer(generated, fallback)));
                if (message.isBlank()) {
                    return explicitNoMaterializationFallback(request.userPrompt(), componentCapabilities, projection);
                }
                return Optional.of(new AgenticAuthoringConsultativeAnswer(
                        category,
                        changeKind(category),
                        message,
                        projection,
                        warnings(category, projection)));
            }
            String generated = providerManagementService.generateText(
                    answerPrompt(request, evidenceBundle(request, "auto", componentCapabilities, null)),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.2d)
                            .maxTokens(2400)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            ParsedConsultativeAnswer parsed = parseConsultativeAnswer(generated);
            if (!parsed.consultative()) {
                return Optional.empty();
            }
            String category = category(parsed.category());
            if ("domain_api".equals(category)) {
                projection = apiCatalogProjection(
                        request.userPrompt(),
                        tenantId,
                        environment);
                Optional<AgenticAuthoringConsultativeAnswer> runtimeContextAnswer =
                        runtimeContextConsultativeAnswer(request, projection, tenantId, userId, environment);
                if (runtimeContextAnswer.isPresent()) {
                    return runtimeContextAnswer;
                }
                if (projection != null && projection.hasResources()) {
                    generated = providerManagementService.generateText(
                            answerPrompt(
                                    request,
                                    evidenceBundle(request, category, componentCapabilities, projection)),
                            AiCallConfig.builder()
                                    .provider(request.provider())
                                    .model(request.model())
                                    .apiKey(request.apiKey())
                                    .temperature(0.2d)
                                    .maxTokens(2400)
                                    .build(),
                            tenantId,
                            userId,
                            environment);
                    parsed = parseConsultativeAnswer(generated);
                    if (!parsed.consultative()) {
                        return Optional.empty();
                    }
                    category = category(parsed.category());
                }
            }
            String fallback = fallbackMessage(category, projection, componentCapabilities);
            String message = guardedDomainAnswer(
                    request.userPrompt(),
                    category,
                    projection,
                    safeAnswer(parsed.answer(), fallback));
            if (message.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AgenticAuthoringConsultativeAnswer(
                    category,
                    changeKind(category),
                    message,
                    projection,
                        warnings(category, projection)));
        } catch (RuntimeException ex) {
            if (domainAvailabilityQuestion || explicitNoMaterialization) {
                log.warn("[AgenticAuthoring] Consultative fast answer failed; returning grounded no-materialization fallback. reason={}",
                        ex.getClass().getSimpleName());
                if (explicitNoMaterialization) {
                    Optional<String> capabilityMessage = componentCapabilityFallbackMessage(
                            request.userPrompt(),
                            componentCapabilities);
                    if (capabilityMessage.isPresent()) {
                        List<String> fallbackWarnings = new ArrayList<>(warnings("component_capability", projection));
                        fallbackWarnings.add("llm-consultative-answer-fallback-used");
                        return Optional.of(new AgenticAuthoringConsultativeAnswer(
                                "component_capability",
                                changeKind("component_capability"),
                                capabilityMessage.get(),
                                projection,
                                fallbackWarnings.stream().distinct().toList()));
                    }
                }
                String unsupportedDomainMessage = AgenticAuthoringConsultativeGroundingAlignment.unsupportedDomainMessage(
                        request.userPrompt(),
                        projection == null ? List.of() : projection.resources());
                if (StringUtils.hasText(unsupportedDomainMessage)) {
                    return Optional.of(new AgenticAuthoringConsultativeAnswer(
                            "domain_api",
                            changeKind("domain_api"),
                            unsupportedDomainMessage,
                            projection,
                            warnings("domain_api", projection)));
                }
                return explicitNoMaterializationFallback(request.userPrompt(), componentCapabilities, projection);
            }
            log.warn("[AgenticAuthoring] Consultative fast answer failed; falling back to regular route. reason={}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public Optional<AgenticAuthoringConsultativeAnswer> answer(
            AgenticAuthoringPlanRequest request,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String tenantId,
            String userId,
            String environment) {
        if (request == null) {
            return Optional.empty();
        }
        return answer(
                new AgenticAuthoringTurnStreamRequest(
                        request.userPrompt(),
                        "praxis-ui-angular",
                        "praxis-dynamic-page-builder",
                        "/page-builder-ia",
                        request.currentPage(),
                        null,
                        request.provider(),
                        request.model(),
                        request.apiKey(),
                        request.sessionId(),
                        request.clientTurnId(),
                        request.conversationMessages(),
                        request.pendingClarification(),
                        request.attachmentSummaries(),
                        request.contextHints(),
                        componentCapabilities),
                componentCapabilities,
                tenantId,
                userId,
                environment);
    }

    boolean shouldPreferPreResolutionConsultativeAnswer(AgenticAuthoringTurnStreamRequest request) {
        if (request == null || !StringUtils.hasText(request.userPrompt())) {
            return false;
        }
        String prompt = request.userPrompt();
        if (clearlyRequestsMaterialization(prompt)) {
            return false;
        }
        ObjectNode initialRuntimeContext = runtimeConsultableContext(request);
        boolean pendingRuntimeDisambiguationContext =
                safePendingRuntimeRelatedSurfaceDisambiguationContext(request, initialRuntimeContext) != null;
        return pendingRuntimeDisambiguationContext
                || isDomainAvailabilityQuestion(prompt)
                || isComponentCatalogQuestion(prompt)
                || explicitlyForbidsMaterialization(prompt)
                || (initialRuntimeContext != null
                && !initialRuntimeContext.isEmpty()
                && startsLikeConsultativeQuestion(normalizeForIntentConstraint(prompt)));
    }

    private String directAnswerPrompt(AgenticAuthoringTurnStreamRequest request, JsonNode evidence) {
        String userPrompt = request == null ? "" : request.userPrompt();
        return """
                You are Praxis, a governed AI authoring assistant.
                The user explicitly asked not to create, preview, apply, save or materialize anything yet.
                Answer as a consultative conversation using the recent conversation and grounded evidence below.

                Return:

                CONSULTATIVE_CATEGORY: domain_api|component_catalog|component_capability|platform_guidance
                ANSWER:
                <final user-facing answer>

                Style:
                - Same language as the user; default to pt-BR.
                - Answer the question now; do not ask whether the user wants a brief or detailed answer.
                - Use the current user question as the only new instruction.
                - Use recent assistant messages only to resolve continuations, pronouns, omitted targets and references to choices the assistant just offered.
                - If the current user question is a terse reference such as a number or "first option", resolve it semantically against the latest relevant assistant choice/list before considering generic table meanings such as page, row, filter value or page size.
                - Be specific and useful. For component catalog questions, list the relevant component families and when to use each.
                - Prefer plain business/product language over implementation/config names.
                - Do not claim that anything was created, saved or previewed.
                - When discussing APIs, explain what data they represent and what screens they can support; avoid raw endpoint paths unless the user explicitly asks for endpoint paths.
                - Never expose internal evidence mechanics or diagnostic words such as schema, resourceKey, submitUrl, endpoint,
                  projection, compact projection, warning, sourceRefs or internal warning codes. Say "campos confirmados",
                  "dados confirmados", "fonte de dados" or "informações disponíveis" instead.
                - For domain/API questions, only present resources, fields, actions and screens supported by the grounded evidence as confirmed.
                  Do not invent typical domain APIs, fields, datasets, screens or business facts. If evidence is compact or incomplete,
                  say what is confirmed and what still needs confirmation instead of filling gaps from general knowledge.
                - For domain/API questions, first compare the business concepts asked by the user with the confirmed resources in evidence.
                  If the requested domain is not confirmed in this host, explicitly say that you did not find confirmed governed data for that domain
                  before mentioning any alternative confirmed resources.
                - If fields, operations or metrics are not present in evidence, do not propose concrete metrics or business facts.
                  Recommend screen types at a product level and state that columns, filters and metrics depend on confirmed fields.
                - End with a complete, concrete next step. Do not leave a dangling sentence or end with a question.
                - For platform or component questions, stay on platform/component concepts. Do not introduce business-domain examples
                  from project knowledge or domain resources unless the user explicitly asks about data, APIs, domain, resources or business entities.

                User question:
                %s

                Recent conversation:
                %s

                Grounded evidence:
                %s
                """.formatted(
                        value(userPrompt),
                        conversationTranscript(request),
                        evidence == null ? "{}" : evidence.toPrettyString());
    }

    private String answerPrompt(AgenticAuthoringTurnStreamRequest request, JsonNode evidence) {
        String userPrompt = request == null ? "" : request.userPrompt();
        return """
                You are Praxis, a governed AI authoring assistant.
                Decide from the user's full intent whether this should be answered conversationally without creating,
                changing, previewing, applying or saving UI. Use the recent conversation and grounded evidence below for the answer.

                Return exactly one of these formats:

                NOT_CONSULTATIVE

                or:

                CONSULTATIVE_CATEGORY: domain_api|component_catalog|component_capability|platform_guidance
                ANSWER:
                <final user-facing answer>

                Classification examples:
                - "Quais componentes posso criar aqui? ... sem criar nada." => component_catalog.
                - "Como habilitar exportar linhas selecionadas na tabela?" => component_capability.
                - "Quais APIs, dados ou recursos existem sobre este assunto?" => domain_api.
                - "O que posso fazer aqui?" or "Como faço um painel administrativo?" => platform_guidance.
                - "Crie/monte/adicione agora uma tela, dashboard, formulario ou componente" => NOT_CONSULTATIVE.
                - "Grafico de barras de Indicadores Incidentes por Severidade. Apenas grafico, sem tabela, filtros ou KPIs." => NOT_CONSULTATIVE.

                Style:
                - Same language as the user; default to pt-BR.
                - Be direct and detailed enough to be useful.
                - Use the current user question as the only new instruction.
                - Use recent assistant messages only to resolve continuations, pronouns, omitted targets and references to choices the assistant just offered.
                - If the current user question is a terse reference such as a number or "first option", resolve it semantically against the latest relevant assistant choice/list before considering generic table meanings such as page, row, filter value or page size.
                - Prefer plain business language over implementation/config names.
                - Do not claim that anything was created, saved or previewed.
                - Answer the question now; do not ask whether the user wants a brief or detailed answer.
                - Exploratory questions about what can be created, recommended, configured, enabled or consulted are consultative,
                  especially when the user says not to create anything yet.
                - Only return NOT_CONSULTATIVE when the user is commanding an immediate materialization, change, removal,
                  preview, application or save operation now.
                - When discussing APIs, explain what data they represent and what screens they can support; avoid raw endpoint paths unless the user explicitly asks for endpoint paths.
                - Never expose internal evidence mechanics or diagnostic words such as schema, resourceKey, submitUrl, endpoint,
                  projection, compact projection, warning, sourceRefs or internal warning codes. Say "campos confirmados",
                  "dados confirmados", "fonte de dados" or "informações disponíveis" instead.
                - For domain/API questions, only present resources, fields, actions and screens supported by the grounded evidence as confirmed.
                  Do not invent typical domain APIs, fields, datasets, screens or business facts. If evidence is compact or incomplete,
                  say what is confirmed and what still needs confirmation instead of filling gaps from general knowledge.
                - For domain/API questions, first compare the business concepts asked by the user with the confirmed resources in evidence.
                  If the requested domain is not confirmed in this host, explicitly say that you did not find confirmed governed data for that domain
                  before mentioning any alternative confirmed resources.
                - If fields, operations or metrics are not present in evidence, do not propose concrete metrics or business facts.
                - If resolving a short reference to a previous suggestion, answer or continue that referenced suggestion directly; do not ask the user what the number means unless the prior assistant message does not contain any plausible choice/list.
                  Recommend screen types at a product level and state that columns, filters and metrics depend on confirmed fields.
                - End with a complete, concrete next step. Do not leave a dangling sentence or end with a question.
                - When discussing component capability, explain the user-facing options first; put technical config names only if truly necessary and label them as implementation details.
                - For platform or component questions, stay on platform/component concepts. Do not introduce business-domain examples
                  from project knowledge or domain resources unless the user explicitly asks about data, APIs, domain, resources or business entities.

                User question:
                %s

                Recent conversation:
                %s

                Grounded evidence:
                %s
                """.formatted(
                        value(userPrompt),
                        conversationTranscript(request),
                        evidence == null ? "{}" : evidence.toPrettyString());
    }

    private AgenticAuthoringConsultativeApiCatalogProjection apiCatalogProjection(
            String query,
            String tenantId,
            String environment) {
        if (apiCatalogProjectionService == null) {
            return null;
        }
        AgenticAuthoringConsultativeApiCatalogProjection projection =
                apiCatalogProjectionService.projectCompact(query, tenantId, environment);
        if (projection != null && projection.hasResources()) {
            return projection;
        }
        return null;
    }

    private ObjectNode evidenceBundle(
            AgenticAuthoringTurnStreamRequest request,
            String category,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            AgenticAuthoringConsultativeApiCatalogProjection projection) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", "praxis-agentic-authoring-consultative-answer-context.v1");
        root.put("category", category);
        root.put("runtime", "Praxis Page Builder authors governed semantic decisions and materializes them only after review.");
        if (projection != null && projection.hasResources()) {
            root.set("domainApiCatalog", objectMapper.valueToTree(projection));
        }
        root.set("componentCatalogs", componentCatalogSummary(componentCapabilities, 12));
        JsonNode presentationAffordances = presentationAffordanceDiscoveryEvidence(request);
        if (presentationAffordances != null && !presentationAffordances.isMissingNode() && !presentationAffordances.isNull()) {
            root.set("presentationAffordanceDiscovery", presentationAffordances);
        }
        ObjectNode runtimeConsultableContext = runtimeConsultableContext(request);
        if (runtimeConsultableContext != null) {
            root.set("groundedRuntimeComponentContext", runtimeConsultableContext);
        }
        if ("domain_api".equals(category)
                && request != null
                && request.contextHints() != null
                && request.contextHints().path("projectKnowledge").isObject()) {
            root.set("projectKnowledge", request.contextHints().path("projectKnowledge"));
        }
        return root;
    }

    private Optional<AgenticAuthoringConsultativeAnswer> runtimeContextConsultativeAnswer(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringConsultativeApiCatalogProjection projection,
            String tenantId,
            String userId,
            String environment) {
        ObjectNode runtimeContext = runtimeConsultableContext(request);
        if (runtimeContext == null || !runtimeContext.path("hasConsultableRuntimeSurface").asBoolean(false)) {
            return Optional.empty();
        }
        RuntimeRelatedSurfaceConsultativeIntent consultativeIntent =
                runtimeRelatedSurfaceConsultativeIntent(request, runtimeContext, tenantId, userId, environment);
        String intentKind = consultativeIntent.kind();
        RuntimeRelatedSurfaceReadAttempt relatedSurfaceAttempt =
                resolveRuntimeRelatedSurface(request, runtimeContext, consultativeIntent, tenantId, userId, environment);
        JsonNode relatedSurfaceRead = relatedSurfaceAttempt.read();
        if (relatedSurfaceAttempt.hasReads()) {
            boolean compareIntent = "runtime_related_surface_compare".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind));
            boolean compareEvidenceAvailable = !compareIntent
                    || runtimeRelatedSurfaceCompareEvidenceAvailable(relatedSurfaceAttempt.reads(), relatedSurfaceAttempt.toolPlan());
            String message = "runtime_related_surface_summary".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind))
                    ? relatedSurfaceSummaryAnswer(relatedSurfaceAttempt.reads())
                    : compareIntent && compareEvidenceAvailable
                    ? relatedSurfaceCompareSkeletonAnswer(relatedSurfaceAttempt.reads(), relatedSurfaceAttempt.toolPlan())
                    : compareIntent
                    ? relatedSurfaceCompareBlockedAnswer(relatedSurfaceAttempt.reads(), relatedSurfaceAttempt.toolPlan())
                    : relatedSurfaceAttempt.reads().size() > 1
                    ? relatedSurfaceReadsAnswer(relatedSurfaceAttempt.reads())
                    : relatedSurfaceAnswer(relatedSurfaceRead);
            List<String> warnings = new ArrayList<>(warnings("runtime_component_context", projection));
            warnings.add("runtime-related-surface-read-tool-used");
            if ("runtime_related_surface_summary".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind))) {
                warnings.add("runtime-related-surface-summary-aggregate-used");
            } else if (compareIntent && compareEvidenceAvailable) {
                warnings.add("runtime-related-surface-compare-aggregate-used");
            } else if (compareIntent) {
                warnings.add("runtime-related-surface-compare-aggregate-blocked");
            }
            return Optional.of(new AgenticAuthoringConsultativeAnswer(
                    "domain_api",
                    changeKind("domain_api"),
                    sanitizeUserFacingAnswer(message),
                    projection,
                    warnings.stream().distinct().toList(),
                    runtimeConsultativeEvidenceBundle(runtimeContext, relatedSurfaceAttempt)));
        }
        boolean availabilityIntent = "runtime_related_surface_availability".equals(intentKind);
        boolean compareIntent = "runtime_related_surface_compare".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind));
        boolean disambiguationIntent = "runtime_surface_disambiguation".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind));
        boolean unsupportedIntent = runtimeRelatedSurfaceIntentBlocksRead(intentKind);
        boolean ambiguousRuntimeSurfaceChoice =
                acceptedRuntimeRelatedSurfaceCandidateCount(relatedSurfaceAttempt.resolution()) > 1;
        boolean dryRunReadFree = runtimeToolPlannerDryRunEnabled() && !availabilityIntent;
        boolean readonlySkeletonReadFree = runtimeToolPlannerReadonlySkeletonEnabled() && !availabilityIntent && !unsupportedIntent;
        List<RuntimeSurfaceOption> surfaceOptions = runtimeSurfaceOptions(runtimeContext, 4);
        List<String> surfaces = !surfaceOptions.isEmpty()
                ? surfaceOptions.stream().map(RuntimeSurfaceOption::label).toList()
                : texts(runtimeContext.path("availableSurfaces"), 4).stream()
                .map(this::humanizeRuntimeSurfaceLabel)
                .filter(StringUtils::hasText)
                .toList();
        List<String> selectedIds = selectedIds(runtimeContext);
        StringBuilder message = new StringBuilder();
        if (availabilityIntent) {
            message.append("Posso usar dados já governados nesta tela para criar novas experiências");
        } else {
            message.append("Posso usar a seleção atual para criar ou abrir visões relacionadas");
        }
        if (!surfaces.isEmpty()) {
            message.append(". As opções confirmadas agora são: ").append(humanJoin(surfaces)).append(".");
        } else {
            message.append(".");
        }
        if (!selectedIds.isEmpty()) {
            message.append(" Como há ")
                    .append(selectedIds.size() == 1 ? "uma linha selecionada" : selectedIds.size() + " linhas selecionadas")
                    .append(", consigo manter a próxima criação contextual sem expor dados brutos da linha.");
        }
        if (availabilityIntent) {
            message.append(" Escolha uma dessas opções ou peça em linguagem natural o tipo de tela que você quer montar.");
        } else if (dryRunReadFree) {
            message.append(" Eu ainda não consultei os registros neste turno; antes de criar, vou validar a fonte governada e preparar uma prévia para revisão.");
        } else if (readonlySkeletonReadFree) {
            message.append(" Eu consigo planejar a próxima tela com segurança, mas ainda preciso confirmar a leitura governada antes de listar dados ou montar a prévia.");
        } else if (compareIntent) {
            message.append(" Para comparar informações, preciso que você escolha o recorte principal; depois eu preparo uma prévia com evidência governada.");
        } else if (ambiguousRuntimeSurfaceChoice) {
            message.append(" Preciso que você escolha qual visão quer usar antes de consultar ou criar a próxima tela.");
        } else if (unsupportedIntent) {
            message.append(" Para criar uma tabela, formulário ou painel, escolha uma opção abaixo ou diga algo como: crie uma tabela com ")
                    .append(surfaces.isEmpty() ? "esses dados" : surfaces.get(0))
                    .append(".");
        } else {
            message.append(" Posso confirmar o contexto governado, mas não vou inventar registros; quando você pedir para criar, eu valido a fonte antes da prévia.");
        }
        List<String> warnings = new ArrayList<>(warnings("runtime_component_context", projection));
        warnings.add("runtime-component-context-consultative-answer-used");
        if (availabilityIntent) {
            warnings.add("runtime-related-surface-availability-read-free");
        } else if (dryRunReadFree) {
            warnings.add("runtime-related-surface-dry-run-read-free");
        } else if (readonlySkeletonReadFree) {
            warnings.add("runtime-related-surface-readonly-beta-planning-only");
        } else if (compareIntent) {
            warnings.add("runtime-related-surface-compare-planning-only");
        } else if (disambiguationIntent) {
            warnings.add("runtime-related-surface-disambiguation-read-free");
        } else if (unsupportedIntent) {
            warnings.add("runtime-related-surface-intent-not-supported");
        } else {
            warnings.add("runtime-related-surface-read-tool-required");
        }
        ObjectNode evidenceBundle = runtimeConsultativeEvidenceBundle(runtimeContext, relatedSurfaceAttempt);
        return Optional.of(new AgenticAuthoringConsultativeAnswer(
                "domain_api",
                changeKind("domain_api"),
                sanitizeUserFacingAnswer(message.toString()),
                projection,
                warnings.stream().distinct().toList(),
                evidenceBundle,
                runtimeRelatedSurfaceQuickReplies(evidenceBundle, surfaceOptions)));
    }

    private RuntimeRelatedSurfaceReadAttempt resolveRuntimeRelatedSurface(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext,
            RuntimeRelatedSurfaceConsultativeIntent consultativeIntent,
            String tenantId,
            String userId,
            String environment) {
	        String intentKind = consultativeIntent == null ? "" : consultativeIntent.kind();
	        boolean availabilityIntent = "runtime_related_surface_availability".equals(intentKind);
		        ObjectNode resolution = runtimeRelatedSurfaceResolution(runtimeContext, null);
		        attachRuntimeRelatedSurfaceTargetCandidateResolutionDiagnostics(resolution, consultativeIntent);
		        attachRuntimeRelatedSurfaceTargetRefinementDiagnostics(resolution, consultativeIntent);
		        attachRuntimeRelatedSurfaceComparisonDimension(resolution, runtimeContext, consultativeIntent);
	        attachRuntimeRelatedSurfaceListTarget(resolution, consultativeIntent);
	        attachRuntimeRelatedSurfaceSummaryTarget(resolution, consultativeIntent);
	        attachRuntimeRelatedSurfaceDetailTarget(resolution, consultativeIntent);
        if (availabilityIntent) {
            if (resolution.path("budget").isObject()) {
                ((ObjectNode) resolution.path("budget")).put("usedToolCalls", 0);
            }
            log.info("[AgenticAuthoring] Runtime related surface availability resolved without read-only backend tool.");
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    resolution,
                    runtimeToolPlan(resolution, intentKind, false, false, ""));
        }
        if (runtimeRelatedSurfaceIntentBlocksRead(intentKind, resolution)) {
            if (resolution.path("budget").isObject()) {
                ((ObjectNode) resolution.path("budget")).put("usedToolCalls", 0);
            }
            log.info("[AgenticAuthoring] Runtime related surface read skipped; intentKind={} is not executable in this cut.",
                    intentKind);
            String blockedFailureCode = runtimeRelatedSurfaceBlockedFailureCode(intentKind, resolution);
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    resolution,
                    runtimeToolPlan(resolution, intentKind, false, false, blockedFailureCode));
        }
        if (runtimeToolPlannerDryRunEnabled()) {
            if (resolution.path("budget").isObject()) {
                ((ObjectNode) resolution.path("budget")).put("usedToolCalls", 0);
            }
            log.info("[AgenticAuthoring] Runtime related surface read skipped by backend dry-run policy; intentKind={}.",
                    intentKind);
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    resolution,
                    runtimeToolPlan(resolution, intentKind, false, false, "runtime-multi-tool-dry-run-read-free"));
        }
        if (runtimeToolPlannerReadonlyExecutionEnabled()
                && runtimeRelatedSurfaceReadonlyExecutionIntent(intentKind)
                && (acceptedRuntimeRelatedSurfaceCandidateCount(resolution) >= minimumAcceptedCandidatesForReadonlyExecution(intentKind)
                || "runtime_related_surface_list".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind))
                        && acceptedRuntimeRelatedSurfaceListTarget(resolution))) {
            return executeRuntimeRelatedSurfacePlan(
                    request,
                    runtimeContext,
                    intentKind,
                    resolution,
                    tenantId,
                    userId,
                    environment);
        }
        if (runtimeToolPlannerReadonlyExecutionEnabled()
                && runtimeRelatedSurfaceReadonlyExecutionIntent(intentKind)) {
            if (resolution.path("budget").isObject()) {
                ((ObjectNode) resolution.path("budget")).put("usedToolCalls", 0);
            }
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    objectMapper.createArrayNode(),
                    resolution,
                    runtimeToolPlan(resolution, intentKind, false, false, "runtime-multi-tool-readonly-requires-multiple-accepted-surfaces"));
        }
        if (runtimeToolPlannerReadonlySkeletonEnabled()) {
            if (resolution.path("budget").isObject()) {
                ((ObjectNode) resolution.path("budget")).put("usedToolCalls", 0);
            }
            log.info("[AgenticAuthoring] Runtime related surface read skipped by backend readonly-beta skeleton; intentKind={}.",
                    intentKind);
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    resolution,
                    runtimeToolPlan(resolution, intentKind, false, false, "runtime-multi-tool-readonly-beta-planning-only"));
        }
        if (toolRegistry == null || request == null || runtimeContext == null) {
            log.info("[AgenticAuthoring] Runtime related surface read skipped; toolRegistryAvailable={} requestAvailable={} runtimeContextAvailable={}.",
                    toolRegistry != null,
                    request != null,
                    runtimeContext != null);
            ObjectNode unavailableResolution = runtimeRelatedSurfaceResolution(null, "runtime-related-surface-tool-unavailable");
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    unavailableResolution,
                    runtimeToolPlan(unavailableResolution, intentKind, false, false, "runtime-related-surface-tool-unavailable"));
        }
        String requestBaseUrl = text(request.contextHints(), "requestBaseUrl");
        if (!StringUtils.hasText(requestBaseUrl)) {
            log.info("[AgenticAuthoring] Runtime related surface read skipped; requestBaseUrl is not available.");
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    resolution,
                    runtimeToolPlan(resolution, intentKind, false, false, "runtime-related-surface-base-url-required"));
        }
        String surfaceRef = resolution.path("selectedCandidateRef").asText("");
        if (!StringUtils.hasText(surfaceRef)) {
            log.info("[AgenticAuthoring] Runtime related surface read skipped; no selected candidate.");
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    resolution,
                    runtimeToolPlan(resolution, intentKind, false, false, "runtime-related-surface-candidate-required"));
        }
        AgenticAuthoringToolResult result = toolRegistry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                        "advisory_authoring",
                        new RuntimeRelatedSurfaceReadToolRequest(
                                runtimeContext,
                                surfaceRef,
                                null,
                                null,
                                requestBaseUrl,
                                8)),
                new AiPrincipalContext(tenantId, userId, environment, true),
                "retrieveEvidence");
        if (result == null || !result.valid() || result.payload() == null) {
            log.info("[AgenticAuthoring] Runtime related surface read did not return evidence; resultAvailable={} valid={} errorCode={}.",
                    result != null,
                    result != null && result.valid(),
                    result == null ? "" : result.errorCode());
            if (result != null && StringUtils.hasText(result.errorCode())) {
                addSelectedCandidateFailure(resolution, result.errorCode());
            }
            return new RuntimeRelatedSurfaceReadAttempt(
                    null,
                    resolution,
                    runtimeToolPlan(resolution, intentKind, true, false, result == null ? "runtime-related-surface-read-failed" : result.errorCode()));
        }
        ObjectNode read = objectMapper.valueToTree(result.payload());
        ObjectNode diagnostics = read.withObject("/diagnostics");
        diagnostics.put("candidateRef", surfaceRef);
        diagnostics.put("stepRef", "runtime-tool-step:" + surfaceRef);
        diagnostics.set("resolution", resolution.deepCopy());
        return new RuntimeRelatedSurfaceReadAttempt(
                read,
                resolution,
                runtimeToolPlan(resolution, intentKind, true, true, ""));
    }

    private RuntimeRelatedSurfaceReadAttempt executeRuntimeRelatedSurfacePlan(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext,
            String intentKind,
            ObjectNode resolution,
            String tenantId,
            String userId,
            String environment) {
        ObjectNode plan = runtimeToolPlan(resolution, intentKind, false, false, "");
        ArrayNode steps = plan.withArray("steps");
        if (steps.isEmpty()) {
            markRuntimeToolPlanExecutionFailure(plan, "runtime-related-surface-step-required", 0);
            return new RuntimeRelatedSurfaceReadAttempt(null, objectMapper.createArrayNode(), resolution, plan);
        }
        if (toolRegistry == null || request == null || runtimeContext == null) {
            markRuntimeToolPlanExecutionFailure(plan, "runtime-related-surface-tool-unavailable", 0);
            return new RuntimeRelatedSurfaceReadAttempt(null, objectMapper.createArrayNode(), resolution, plan);
        }
        String requestBaseUrl = text(request.contextHints(), "requestBaseUrl");
        if (!StringUtils.hasText(requestBaseUrl)) {
            markRuntimeToolPlanExecutionFailure(plan, "runtime-related-surface-base-url-required", 0);
            return new RuntimeRelatedSurfaceReadAttempt(null, objectMapper.createArrayNode(), resolution, plan);
        }
        ArrayNode reads = objectMapper.createArrayNode();
        int executed = 0;
        String failureCode = "";
        for (JsonNode stepNode : steps) {
            if (executed >= 2 || !stepNode.isObject()) {
                continue;
            }
            ObjectNode step = (ObjectNode) stepNode;
            String surfaceRef = text(step, "surfaceRef");
            String stepRef = text(step, "stepRef");
            if (!StringUtils.hasText(surfaceRef)) {
                failureCode = "runtime-related-surface-step-surface-required";
                step.put("status", "failed");
                step.put("failureCode", failureCode);
                break;
            }
            AgenticAuthoringToolResult result = toolRegistry.execute(
                    new AgenticAuthoringToolCall(
                            AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE,
                            "advisory_authoring",
                            new RuntimeRelatedSurfaceReadToolRequest(
                                    runtimeContext,
                                    surfaceRef,
                                    null,
                                    null,
                                    requestBaseUrl,
                                    8)),
                    new AiPrincipalContext(tenantId, userId, environment, true),
                    "retrieveEvidence");
            executed++;
            if (result == null || !result.valid() || result.payload() == null) {
                failureCode = result == null ? "runtime-related-surface-read-failed" : firstNonBlank(result.errorCode(), "runtime-related-surface-read-failed");
                step.put("status", "failed");
                step.put("failureCode", failureCode);
                step.set("blockedBy", textArray(List.of(failureCode), 4));
                break;
            }
            ObjectNode read = objectMapper.valueToTree(result.payload());
            read.put("stepRef", firstNonBlank(stepRef, "runtime-tool-step:" + surfaceRef));
            ObjectNode diagnostics = read.withObject("/diagnostics");
            diagnostics.put("candidateRef", text(step, "candidateRef"));
            diagnostics.put("stepRef", firstNonBlank(stepRef, "runtime-tool-step:" + surfaceRef));
            step.put("status", "executed");
            step.put("executionStatus", "executed");
            reads.add(read);
        }
        if (StringUtils.hasText(failureCode)) {
            markRuntimeToolPlanExecutionFailure(plan, failureCode, executed);
            return new RuntimeRelatedSurfaceReadAttempt(null, objectMapper.createArrayNode(), resolution, plan);
        }
        markRuntimeToolPlanExecutionSuccess(plan, executed, reads.size());
        return new RuntimeRelatedSurfaceReadAttempt(reads.isEmpty() ? null : reads.get(0), reads, resolution, plan);
    }

    private void markRuntimeToolPlanExecutionSuccess(ObjectNode plan, int usedToolCalls, int usedReads) {
        if (plan == null) {
            return;
        }
        ObjectNode planner = plan.withObject("/planner");
        planner.put("executionMode", "read_only");
        planner.put("planningOnlyForPolicySkeleton", false);
        ObjectNode budget = plan.withObject("/budget");
        budget.put("usedToolCalls", usedToolCalls);
        budget.put("consumesGlobalToolBudget", usedToolCalls > 0);
        budget.put("exhausted", usedToolCalls >= budget.path("maxToolCalls").asInt(0));
        ObjectNode relatedBudget = budget.withObject("/runtimeRelatedSurfaceToolBudget");
        relatedBudget.put("usedToolCalls", usedToolCalls);
        relatedBudget.put("usedReads", usedReads);
        ObjectNode diagnostics = plan.withObject("/executionDiagnostics");
        diagnostics.put("planningOnly", false);
        diagnostics.put("multiToolExecutionEnabled", true);
        diagnostics.put("maxExecutableSteps", Math.min(2, plan.path("steps").size()));
        diagnostics.put("usedToolCalls", usedToolCalls);
        diagnostics.put("backendReadsPerformed", usedReads > 0);
        diagnostics.put("nonExecutionReason", "");
        diagnostics.put("failureCode", "");
        diagnostics.put("aggregateStatus", "success");
        if ("runtime_related_surface_compare".equals(text(plan, "intentKind"))) {
            ObjectNode aggregationPolicy = plan.withObject("/aggregationPolicy");
            aggregationPolicy.put("compareEvidenceEmitted", true);
            aggregationPolicy.put("compareExecutionStage", "terminal_governed_compare_evidence");
            diagnostics.put("compareEvidenceEmitted", true);
            diagnostics.put("compareExecutionStage", "terminal_governed_compare_evidence");
        }
        plan.put("readFree", false);
        plan.put("executionPolicy", "runtime_related_surface_compare".equals(text(plan, "intentKind"))
                ? "multi-tool-readonly-beta-governed-compare"
                : "multi-tool-readonly-beta");
    }

    private void markRuntimeToolPlanExecutionFailure(ObjectNode plan, String failureCode, int usedToolCalls) {
        if (plan == null) {
            return;
        }
        ObjectNode budget = plan.withObject("/budget");
        budget.put("usedToolCalls", Math.max(0, usedToolCalls));
        budget.put("consumesGlobalToolBudget", usedToolCalls > 0);
        ObjectNode relatedBudget = budget.withObject("/runtimeRelatedSurfaceToolBudget");
        relatedBudget.put("usedToolCalls", Math.max(0, usedToolCalls));
        relatedBudget.put("usedReads", 0);
        ObjectNode diagnostics = plan.withObject("/executionDiagnostics");
        diagnostics.put("planningOnly", false);
        diagnostics.put("multiToolExecutionEnabled", true);
        diagnostics.put("maxExecutableSteps", Math.min(2, plan.path("steps").size()));
        diagnostics.put("usedToolCalls", Math.max(0, usedToolCalls));
        diagnostics.put("backendReadsPerformed", usedToolCalls > 0);
        diagnostics.put("nonExecutionReason", firstNonBlank(failureCode, "runtime-related-surface-read-failed"));
        diagnostics.put("failureCode", firstNonBlank(failureCode, "runtime-related-surface-read-failed"));
        diagnostics.put("aggregateStatus", "failed");
        plan.put("readFree", false);
        plan.put("executionPolicy", "multi-tool-readonly-beta-fail-closed");
    }

    private String relatedSurfaceAnswer(JsonNode relatedSurfaceRead) {
        List<String> lines = new ArrayList<>();
        for (JsonNode record : relatedSurfaceRead.path("records")) {
            String name = firstRecordDisplayValue(record);
            if (!StringUtils.hasText(name)) {
                name = compactRecord(record);
            }
            String papel = firstNonBlank(text(record, "papel"), text(record, "role"), text(record, "status"));
            boolean principal = record.path("principal").asBoolean(false);
            StringBuilder line = new StringBuilder(name);
            if (StringUtils.hasText(papel)) {
                line.append(" (").append(papel).append(principal ? ", principal" : "").append(")");
            } else if (principal) {
                line.append(" (principal)");
            }
            lines.add(line.toString());
        }
        String surface = text(relatedSurfaceRead, "surfaceRef");
        if (lines.isEmpty()) {
            return "A superficie runtime relacionada " + surface
                    + " foi consultada por uma tool backend read-only, mas nao retornou registros governados para a selecao atual.";
        }
        return "A superficie runtime relacionada " + surface
                + " foi reconciliada com o backend governado. Registros encontrados para a selecao atual: "
                + humanJoin(lines) + ".";
    }

    private String relatedSurfaceReadsAnswer(JsonNode relatedSurfaceReads) {
        List<String> parts = new ArrayList<>();
        int total = 0;
        for (JsonNode read : relatedSurfaceReads) {
            List<String> lines = new ArrayList<>();
            for (JsonNode record : read.path("records")) {
                String value = firstRecordDisplayValue(record);
                if (!StringUtils.hasText(value)) {
                    value = compactRecord(record);
                }
                lines.add(value);
            }
            total += read.path("recordCount").asInt(lines.size());
            String surfaceRef = text(read, "surfaceRef");
            if (lines.isEmpty()) {
                parts.add(surfaceRef + ": nenhum registro governado retornado");
            } else {
                parts.add(surfaceRef + ": " + humanJoin(lines));
            }
        }
        return "As superfícies runtime relacionadas foram reconciliadas com o backend governado. "
                + "Registros encontrados (" + total + "): " + humanJoin(parts) + ".";
    }

    private String relatedSurfaceSummaryAnswer(JsonNode relatedSurfaceReads) {
        List<String> parts = new ArrayList<>();
        int total = 0;
        for (JsonNode read : relatedSurfaceReads) {
            List<String> labels = new ArrayList<>();
            for (JsonNode record : read.path("records")) {
                String value = firstRecordDisplayValue(record);
                if (!StringUtils.hasText(value)) {
                    value = compactRecord(record);
                }
                labels.add(value);
            }
            int count = read.path("recordCount").asInt(labels.size());
            total += count;
            String surfaceRef = text(read, "surfaceRef");
            if (labels.isEmpty()) {
                parts.add(surfaceRef + ": nenhum registro governado retornado");
            } else {
                parts.add(surfaceRef + ": " + count + " registro" + (count == 1 ? "" : "s")
                        + " (" + humanJoin(labels) + ")");
            }
        }
        if (parts.isEmpty()) {
            return "O resumo governado não encontrou registros sanitizados nas superfícies relacionadas aceitas.";
        }
        return "Resumo governado das superfícies runtime relacionadas: "
                + humanJoin(parts)
                + ". Total de registros sanitizados considerados: "
                + total
                + ".";
    }

    private String relatedSurfaceCompareSkeletonAnswer(JsonNode relatedSurfaceReads, ObjectNode toolPlan) {
        List<String> surfaces = new ArrayList<>();
        for (JsonNode read : relatedSurfaceReads) {
            String surfaceRef = text(read, "surfaceRef");
            if (StringUtils.hasText(surfaceRef)) {
                surfaces.add(surfaceRef);
            }
        }
        String fieldRef = text(toolPlan == null ? null : toolPlan.path("aggregationPolicy").path("comparisonDimension"), "fieldRef");
        String dimension = StringUtils.hasText(fieldRef)
                ? " com dimensão comparável governada `" + fieldRef + "`"
                : "";
        return "O compare governado foi materializado a partir de evidência sanitizada: as superfícies "
                + (surfaces.isEmpty() ? "relacionadas" : humanJoin(surfaces))
                + " foram lidas por tools backend read-only"
                + dimension
                + ", sem nova tool e sem copiar valores crus do runtime.";
    }

    private String relatedSurfaceCompareBlockedAnswer(JsonNode relatedSurfaceReads, ObjectNode toolPlan) {
        List<String> surfaces = new ArrayList<>();
        for (JsonNode read : relatedSurfaceReads) {
            String surfaceRef = text(read, "surfaceRef");
            if (StringUtils.hasText(surfaceRef)) {
                surfaces.add(surfaceRef);
            }
        }
        String fieldRef = text(toolPlan == null ? null : toolPlan.path("aggregationPolicy").path("comparisonDimension"), "fieldRef");
        String dimension = StringUtils.hasText(fieldRef)
                ? " `" + fieldRef + "`"
                : "";
        return "As superfícies "
                + (surfaces.isEmpty() ? "relacionadas" : humanJoin(surfaces))
                + " foram lidas por tools backend read-only, mas o compare governado ficou bloqueado: a dimensão comparável"
                + dimension
                + " não foi projetada em todas as leituras sanitizadas. Nenhuma evidência comparativa terminal foi emitida.";
    }

    private ObjectNode runtimeConsultativeEvidenceBundle(
            ObjectNode runtimeContext,
            RuntimeRelatedSurfaceReadAttempt relatedSurfaceAttempt) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.set("runtimeConsultableContext", runtimeContext == null
                ? objectMapper.createObjectNode()
                : runtimeContext.deepCopy());
        if (relatedSurfaceAttempt != null && relatedSurfaceAttempt.resolution() != null) {
            evidence.set("runtimeRelatedSurfaceResolution", relatedSurfaceAttempt.resolution().deepCopy());
        }
        ObjectNode toolPlanEvidence = relatedSurfaceAttempt != null && relatedSurfaceAttempt.toolPlan() != null
                ? relatedSurfaceAttempt.toolPlan().deepCopy()
                : null;
        ArrayNode reads = relatedSurfaceAttempt == null || relatedSurfaceAttempt.reads() == null
                ? objectMapper.createArrayNode()
                : relatedSurfaceAttempt.reads().deepCopy();
        if (reads.size() == 1 && reads.get(0).isObject()
                && relatedSurfaceAttempt != null
                && toolPlanEvidence != null
                && "success".equals(text(toolPlanEvidence.path("executionDiagnostics"), "aggregateStatus"))) {
            ObjectNode read = (ObjectNode) reads.get(0).deepCopy();
            read.put("aliasOf", "runtimeRelatedSurfaceReads[0]");
            evidence.set("runtimeRelatedSurfaceRead", read);
        }
        evidence.set("runtimeRelatedSurfaceReads", reads);
        if (reads.isEmpty()
                && relatedSurfaceAttempt != null
                && relatedSurfaceAttempt.resolution() != null
                && toolPlanEvidence != null) {
            ObjectNode disambiguation = runtimeRelatedSurfaceDisambiguationEvidence(
                    relatedSurfaceAttempt.resolution(),
                    toolPlanEvidence);
            if (disambiguation != null) {
                evidence.set("runtimeRelatedSurfaceDisambiguation", disambiguation);
            }
        }
        if (relatedSurfaceAttempt != null
                && toolPlanEvidence != null
                && "runtime_related_surface_summary".equals(text(toolPlanEvidence, "intentKind"))
                && "success".equals(text(toolPlanEvidence.path("executionDiagnostics"), "aggregateStatus"))
                && !reads.isEmpty()) {
            evidence.set("runtimeRelatedSurfaceSummary", runtimeRelatedSurfaceSummaryEvidence(reads, toolPlanEvidence));
        }
        if (relatedSurfaceAttempt != null
                && toolPlanEvidence != null
                && "runtime_related_surface_compare".equals(text(toolPlanEvidence, "intentKind"))
                && "success".equals(text(toolPlanEvidence.path("executionDiagnostics"), "aggregateStatus"))
                && !reads.isEmpty()) {
            ObjectNode compare = runtimeRelatedSurfaceCompareEvidence(reads, toolPlanEvidence);
            if (compare != null) {
                evidence.set("runtimeRelatedSurfaceCompare", compare);
            } else {
                markRuntimeRelatedSurfaceCompareEvidenceBlocked(toolPlanEvidence, reads);
            }
        }
        if (toolPlanEvidence != null) {
            evidence.set("runtimeToolPlan", toolPlanEvidence);
        }
        return evidence;
    }

    private ObjectNode runtimeRelatedSurfaceDisambiguationEvidence(ObjectNode resolution, ObjectNode toolPlan) {
        if (resolution == null || toolPlan == null || !resolution.path("candidates").isArray()) {
            return null;
        }
        String intentKind = normalizeRuntimeRelatedSurfaceIntentKind(text(toolPlan, "intentKind"));
        if (!"runtime_surface_disambiguation".equals(intentKind)
                && !"runtime_related_surface_detail".equals(intentKind)) {
            return null;
        }
        ArrayNode options = objectMapper.createArrayNode();
        int rank = 1;
        for (JsonNode candidate : resolution.path("candidates")) {
            if (!candidate.isObject() || !"accepted".equals(text(candidate, "status"))) {
                continue;
            }
            String surfaceRef = text(candidate, "surfaceRef");
            if (!StringUtils.hasText(surfaceRef)) {
                continue;
            }
            String candidateRef = firstNonBlank(text(candidate, "candidateRef"), "runtime-surface-candidate:" + surfaceRef);
            ObjectNode option = options.addObject();
            option.put("optionRef", "runtime-surface-option:" + surfaceRef);
            option.put("surfaceRef", surfaceRef);
            option.put("candidateRef", candidateRef);
            copySafeScalar(candidate, option, "label");
            if (candidate.path("semanticAliases").isArray()) {
                option.set("semanticAliases", textArray(candidate.path("semanticAliases"), 12));
            }
            option.put("rank", rank++);
            option.put("status", "available");
            option.put("readModeIfSelected", "detail");
            option.put("requiresFollowUpSelection", true);
            option.set("acceptedClaimRefs", runtimeToolPlanAcceptedClaimRefs(candidateRef));
            option.set("scoreReasons", candidate.path("scoreReasons").isArray()
                    ? candidate.path("scoreReasons").deepCopy()
                    : objectMapper.createArrayNode());
            option.put("projectionPolicyRef", "runtime-related-surface-projection:declared-fields-v1");
            option.put("redactionPolicyRef", "runtime-related-surface-redaction:sensitive-scalars-v1");
        }
        if (options.size() < 2) {
            return null;
        }
        ObjectNode disambiguation = objectMapper.createObjectNode();
        disambiguation.put("schemaVersion", "praxis-runtime-related-surface-disambiguation.v1");
        disambiguation.put("intentKind", intentKind);
        disambiguation.put("semanticDecisionRef", text(toolPlan, "semanticDecisionRef"));
        disambiguation.put("status", "requires_target_selection");
        disambiguation.put("readMode", "none");
        disambiguation.put("rawRuntimeValuesCopied", false);
        disambiguation.put("backendReadsPerformed", false);
        disambiguation.put("absenceIsNotEvidence", true);
        disambiguation.set("options", options);
        disambiguation.put("optionCount", options.size());
        disambiguation.set("failureCodes", textArray(List.of(firstNonBlank(
                text(toolPlan.path("executionDiagnostics"), "failureCode"),
                "runtime-related-surface-target-selection-required")), 4));
        return disambiguation;
    }

    private List<AgenticAuthoringQuickReply> runtimeRelatedSurfaceQuickReplies(
            JsonNode evidenceBundle,
            List<RuntimeSurfaceOption> surfaceOptions) {
        List<AgenticAuthoringQuickReply> disambiguationReplies =
                runtimeRelatedSurfaceDisambiguationQuickReplies(evidenceBundle);
        if (!disambiguationReplies.isEmpty()) {
            return disambiguationReplies;
        }
        if (surfaceOptions == null || surfaceOptions.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RuntimeSurfaceOption option : surfaceOptions) {
            if (option == null || !StringUtils.hasText(option.surfaceRef()) || !seen.add(option.surfaceRef())) {
                continue;
            }
            String label = runtimeSurfaceUserLabel(option.surfaceRef(), option.label());
            if (!StringUtils.hasText(label)) {
                continue;
            }
            ObjectNode contextHints = objectMapper.createObjectNode();
            contextHints.put("schemaVersion", "praxis-runtime-related-surface-create-quick-reply-context.v1");
            contextHints.put("source", "groundedRuntimeComponentContext.relationSurfaceRefs");
            contextHints.put("surfaceRef", option.surfaceRef());
            String resourceLabel = runtimeSurfaceResourceUserLabel(option.surfaceRef(), option.resourcePath());
            if (StringUtils.hasText(resourceLabel)) {
                contextHints.put("resourceLabel", resourceLabel);
            }
            if (StringUtils.hasText(option.resourcePath())) {
                contextHints.put("resourcePath", option.resourcePath());
            }
            contextHints.put("artifactKind", "table");
            ObjectNode presentation = objectMapper.createObjectNode();
            presentation.put(
                    "bestFor",
                    "Boa quando você quer navegar, filtrar e comparar registros de " + label);
            presentation.put(
                    "returns",
                    "Pré-visualização com colunas, filtros e fonte semântica preservada");
            presentation.put(
                    "nextStep",
                    "Criar a tabela e revisar antes de salvar");
            contextHints.set("presentation", presentation);
            replies.add(new AgenticAuthoringQuickReply(
                    "runtime-related-surface-create-table:" + option.surfaceRef(),
                    "runtime_related_surface_create",
                    "Criar tabela: " + label,
                    "Crie uma tabela usando " + label + ".",
                    "Usa a fonte governada já presente nesta tela para preparar uma prévia de tabela.",
                    "table_view",
                    "resource",
                    contextHints));
            if (replies.size() >= 3) {
                break;
            }
        }
        return List.copyOf(replies);
    }

    private List<RuntimeSurfaceOption> runtimeSurfaceOptions(ObjectNode runtimeContext, int limit) {
        if (runtimeContext == null || limit <= 0) {
            return List.of();
        }
        List<RuntimeSurfaceOption> options = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode relation : relationSurfaceRefs(runtimeContext)) {
            String surfaceRef = firstNonBlank(
                    text(relation, "surfaceRef"),
                    text(relation, "targetSurface"),
                    text(relation, "id"));
            if (!StringUtils.hasText(surfaceRef) || !seen.add(surfaceRef)) {
                continue;
            }
            String label = runtimeSurfaceUserLabel(surfaceRef, text(relation, "label"));
            String resourcePath = firstNonBlank(
                    text(relation, "targetResourcePath"),
                    text(relation.path("target"), "resourcePath"));
            options.add(new RuntimeSurfaceOption(surfaceRef, label, resourcePath));
            if (options.size() >= limit) {
                break;
            }
        }
        if (!options.isEmpty()) {
            return List.copyOf(options);
        }
        for (String surface : texts(runtimeContext.path("availableSurfaces"), limit)) {
            if (!StringUtils.hasText(surface) || !seen.add(surface)) {
                continue;
            }
            options.add(new RuntimeSurfaceOption(surface, humanizeRuntimeSurfaceLabel(surface), ""));
        }
        return List.copyOf(options);
    }

    private String humanizeRuntimeSurfaceLabel(String value) {
        String text = value(value);
        if (text.isBlank()) {
            return "";
        }
        String separated = text
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("(?<=[a-z])(?=[A-Z])", " ")
                .trim();
        if (text.contains(" ")) {
            return text;
        }
        if (separated.isBlank()) {
            return text;
        }
        return separated.substring(0, 1).toUpperCase(Locale.ROOT) + separated.substring(1);
    }

    private String runtimeSurfaceUserLabel(String surfaceRef, String publishedLabel) {
        return firstNonBlank(publishedLabel, humanizeRuntimeSurfaceLabel(surfaceRef));
    }

    private String runtimeSurfaceResourceUserLabel(String surfaceRef, String resourcePath) {
        return humanizeRuntimeSurfaceLabel(lastPathSegment(resourcePath));
    }

    private String lastPathSegment(String value) {
        String text = value(value).replaceAll("/+$", "");
        if (text.isBlank()) {
            return "";
        }
        int slash = text.lastIndexOf('/');
        return slash >= 0 ? text.substring(slash + 1) : text;
    }

    private List<AgenticAuthoringQuickReply> runtimeRelatedSurfaceDisambiguationQuickReplies(JsonNode evidenceBundle) {
        JsonNode disambiguation = evidenceBundle == null
                ? null
                : evidenceBundle.path("runtimeRelatedSurfaceDisambiguation");
        JsonNode options = disambiguation == null ? null : disambiguation.path("options");
        if (options == null || !options.isArray() || options.size() < 2) {
            return List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        Set<String> seenSurfaceRefs = new LinkedHashSet<>();
        for (JsonNode option : options) {
            if (!option.isObject()) {
                continue;
            }
            String surfaceRef = text(option, "surfaceRef");
            String optionRef = text(option, "optionRef");
            String candidateRef = text(option, "candidateRef");
            if (!safeIdentifier(surfaceRef)
                    || !"runtime-surface-option:".concat(surfaceRef).equals(optionRef)
                    || !StringUtils.hasText(candidateRef)
                    || !candidateRef.startsWith("runtime-surface-candidate:")
                    || !seenSurfaceRefs.add(surfaceRef)) {
                continue;
            }
            ObjectNode selection = runtimeRelatedSurfaceDisambiguationSelectionValue(optionRef, candidateRef, surfaceRef);
            ObjectNode contextHints = objectMapper.createObjectNode();
            contextHints.put("schemaVersion", "praxis-runtime-related-surface-quick-reply-context.v1");
            contextHints.put("source", "runtimeRelatedSurfaceDisambiguation.options");
            contextHints.put("optionRef", optionRef);
            contextHints.put("candidateRef", candidateRef);
            contextHints.put("surfaceRef", surfaceRef);
            contextHints.put("requiresActiveSemanticDecision", true);
            ObjectNode semanticDecision = runtimeRelatedSurfaceDisambiguationSemanticDecision(
                    optionRef,
                    candidateRef,
                    surfaceRef);
            String label = runtimeSurfaceUserLabel(surfaceRef, text(option, "label"));
            replies.add(new AgenticAuthoringQuickReply(
                    "runtime-related-surface-detail:" + surfaceRef,
                    "runtime_related_surface_detail",
                    "Detalhar " + label,
                    "Detalhe " + label + ".",
                    "Seleciona esta visão relacionada usando decisão semântica governada pelo backend.",
                    "list",
                    "resource",
                    contextHints,
                    semanticDecision,
                    selection));
            if (replies.size() >= 4) {
                break;
            }
        }
        return List.copyOf(replies);
    }

    private record RuntimeSurfaceOption(String surfaceRef, String label, String resourcePath) {
    }

    private ObjectNode runtimeRelatedSurfaceDisambiguationSemanticDecision(
            String optionRef,
            String candidateRef,
            String surfaceRef) {
        ObjectNode decision = objectMapper.createObjectNode();
        decision.put("schemaVersion", "praxis-agentic-authoring-semantic-decision.v1");
        decision.put("decisionId", "runtime-related-surface-detail:" + surfaceRef);
        decision.put("operationKind", "consult");
        decision.put("artifactKind", "runtime_related_surface");
        decision.put("artifactIntent", "runtime_related_surface_detail");
        decision.put("changeKind", "runtime_related_surface_detail");
        decision.put("userGoal", "Detalhar a superficie relacionada " + surfaceRef + ".");
        decision.put("activeObjective", "Consultar uma superficie relacionada escolhida por desambiguacao governada.");
        decision.put("rationale", "Opcao emitida pelo backend a partir de runtimeRelatedSurfaceDisambiguation.options[].");
        decision.put("confidence", 0.99d);
        ObjectNode constraints = decision.putObject("constraints");
        constraints.set(
                "runtimeRelatedSurfaceDisambiguationSelection",
                runtimeRelatedSurfaceDisambiguationSelectionValue(optionRef, candidateRef, surfaceRef));
        return decision;
    }

    private ObjectNode runtimeRelatedSurfaceDisambiguationSelectionValue(
            String optionRef,
            String candidateRef,
            String surfaceRef) {
        ObjectNode selection = objectMapper.createObjectNode();
        selection.put("optionRef", optionRef);
        selection.put("candidateRef", candidateRef);
        selection.put("surfaceRef", surfaceRef);
        return selection;
    }

    private ObjectNode runtimeRelatedSurfaceSummaryEvidence(ArrayNode reads, ObjectNode toolPlan) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("schemaVersion", "praxis-runtime-related-surface-summary.v1");
        summary.put("intentKind", "runtime_related_surface_summary");
        summary.put("aggregationMode", firstNonBlank(
                text(toolPlan == null ? null : toolPlan.path("aggregationPolicy"), "mode"),
                "governed_summary"));
        ArrayNode sourceReadRefs = summary.putArray("sourceReadRefs");
        ArrayNode surfaceRefs = summary.putArray("surfaceRefs");
        ObjectNode recordCountsBySurface = summary.putObject("recordCountsBySurface");
        ArrayNode facts = summary.putArray("facts");
        ArrayNode omittedFields = summary.putArray("omittedFields");
        Set<String> omitted = new LinkedHashSet<>();
        int totalRecordCount = 0;
        int factIndex = 1;
        for (JsonNode read : reads) {
            String surfaceRef = firstNonBlank(text(read, "surfaceRef"), "unknown");
            String stepRef = firstNonBlank(text(read, "stepRef"), "runtime-tool-step:" + surfaceRef);
            int recordCount = read.path("recordCount").asInt(read.path("records").size());
            totalRecordCount += recordCount;
            sourceReadRefs.add(stepRef);
            surfaceRefs.add(surfaceRef);
            recordCountsBySurface.put(surfaceRef, recordCount);
            for (JsonNode omittedField : read.path("omittedFields")) {
                if (omittedField.isTextual() && StringUtils.hasText(omittedField.asText())) {
                    omitted.add(omittedField.asText());
                }
            }
            ObjectNode fact = facts.addObject();
            fact.put("factRef", "runtime-summary-fact:" + surfaceRef + ":" + factIndex++);
            fact.put("surfaceRef", surfaceRef);
            fact.put("sourceReadRef", stepRef);
            fact.put("kind", "record_group_summary");
            fact.put("text", recordCount + " registro" + (recordCount == 1 ? "" : "s")
                    + " sanitizado" + (recordCount == 1 ? "" : "s")
                    + " encontrado" + (recordCount == 1 ? "" : "s")
                    + " na superfície " + surfaceRef + ".");
            fact.set("projectionFieldRefs", textArray(read.path("projectionFields"), 12));
            fact.put("redactionApplied", read.path("redactionApplied").asBoolean(true));
        }
        omitted.forEach(omittedFields::add);
        summary.put("totalRecordCount", totalRecordCount);
        summary.put("rawRuntimeValuesCopied", false);
        summary.put("redactionApplied", true);
        summary.put("truncated", anyReadTruncated(reads));
        summary.set("warnings", aggregateRuntimeRelatedSurfaceReadWarnings(reads));
        if (toolPlan != null && toolPlan.path("executionDiagnostics").isObject()) {
            ObjectNode diagnostics = summary.putObject("diagnostics");
            diagnostics.set("toolPlanExecution", toolPlan.path("executionDiagnostics").deepCopy());
        }
        return summary;
    }

    private ObjectNode runtimeRelatedSurfaceCompareEvidence(ArrayNode reads, ObjectNode toolPlan) {
        JsonNode dimension = toolPlan == null
                ? null
                : toolPlan.path("aggregationPolicy").path("comparisonDimension");
        String fieldRef = text(dimension, "fieldRef");
        if (!runtimeRelatedSurfaceCompareEvidenceAvailable(reads, toolPlan)) {
            return null;
        }
        Set<String> allowedFactKinds = allowedCompareFactKinds(dimension);
        ObjectNode compare = objectMapper.createObjectNode();
        compare.put("schemaVersion", "praxis-runtime-related-surface-compare.v1");
        compare.put("intentKind", "runtime_related_surface_compare");
        compare.put("aggregationMode", "governed_compare");
        ArrayNode sourceReadRefs = compare.putArray("sourceReadRefs");
        ArrayNode surfaceRefs = compare.putArray("surfaceRefs");
        ObjectNode recordCountsBySurface = compare.putObject("recordCountsBySurface");
        ObjectNode distributionsBySurface = compare.putObject("categoricalDistributionBySurface");
        ArrayNode facts = compare.putArray("facts");
        int totalRecordCount = 0;
        int factIndex = 1;
        List<String> comparedSurfaceRefs = new ArrayList<>();
        for (JsonNode read : reads) {
            String surfaceRef = firstNonBlank(text(read, "surfaceRef"), "unknown");
            String stepRef = firstNonBlank(text(read, "stepRef"), "runtime-tool-step:" + surfaceRef);
            int recordCount = read.path("recordCount").asInt(read.path("records").size());
            totalRecordCount += recordCount;
            comparedSurfaceRefs.add(surfaceRef);
            sourceReadRefs.add(stepRef);
            surfaceRefs.add(surfaceRef);
            recordCountsBySurface.put(surfaceRef, recordCount);
            if (allowedFactKinds.contains("surface_record_count")) {
                ObjectNode countFact = facts.addObject();
                countFact.put("factRef", "runtime-compare-fact:" + surfaceRef + ":" + factIndex++);
                countFact.put("kind", "surface_record_count");
                countFact.put("surfaceRef", surfaceRef);
                countFact.put("sourceReadRef", stepRef);
                countFact.put("recordCount", recordCount);
                countFact.put("redactionApplied", read.path("redactionApplied").asBoolean(true));
            }

            ObjectNode distribution = distributionsBySurface.putObject(surfaceRef);
            for (JsonNode record : read.path("records")) {
                JsonNode value = record.path(fieldRef);
                String category = safeComparisonCategory(value);
                distribution.put(category, distribution.path(category).asInt(0) + 1);
            }
            if (allowedFactKinds.contains("categorical_distribution")) {
                ObjectNode distributionFact = facts.addObject();
                distributionFact.put("factRef", "runtime-compare-fact:" + surfaceRef + ":" + factIndex++);
                distributionFact.put("kind", "categorical_distribution");
                distributionFact.put("surfaceRef", surfaceRef);
                distributionFact.put("sourceReadRef", stepRef);
                distributionFact.put("fieldRef", fieldRef);
                distributionFact.set("distribution", distribution.deepCopy());
                distributionFact.put("redactionApplied", read.path("redactionApplied").asBoolean(true));
            }
            if (allowedFactKinds.contains("projection_redaction_coverage")) {
                ObjectNode coverageFact = facts.addObject();
                coverageFact.put("factRef", "runtime-compare-fact:" + surfaceRef + ":" + factIndex++);
                coverageFact.put("kind", "projection_redaction_coverage");
                coverageFact.put("surfaceRef", surfaceRef);
                coverageFact.put("sourceReadRef", stepRef);
                coverageFact.set("projectionFieldRefs", textArray(read.path("projectionFields"), 24));
                coverageFact.put("projectionFieldCount", read.path("projectionFields").isArray()
                        ? read.path("projectionFields").size()
                        : 0);
                coverageFact.set("omittedFieldRefs", textArray(read.path("omittedFields"), 24));
                coverageFact.put("omittedFieldCount", read.path("omittedFields").isArray()
                        ? read.path("omittedFields").size()
                        : 0);
                coverageFact.put("redactionApplied", read.path("redactionApplied").asBoolean(true));
                coverageFact.put("truncated", read.path("truncated").asBoolean(false));
                coverageFact.put("rawRuntimeValuesCopied", false);
            }
        }
        if (allowedFactKinds.contains("record_count_delta")) {
            appendCompareDeltaFact(
                    facts,
                    recordCountsBySurface,
                    comparedSurfaceRefs,
                    factIndex++);
        }
        if (allowedFactKinds.contains("category_overlap")) {
            appendCompareCategoryOverlapFact(
                    facts,
                    distributionsBySurface,
                    comparedSurfaceRefs,
                    fieldRef,
                    factIndex++);
        }
        if (allowedFactKinds.contains("record_presence_matrix")) {
            appendComparePresenceMatrixFact(
                    facts,
                    distributionsBySurface,
                    comparedSurfaceRefs,
                    fieldRef,
                    factIndex++);
        }
        if (allowedFactKinds.contains("temporal_coverage") && temporalComparisonDimension(dimension)) {
            appendCompareTemporalCoverageFacts(
                    facts,
                    reads,
                    comparedSurfaceRefs,
                    fieldRef,
                    factIndex);
        }
        compare.set("comparisonDimension", dimension.deepCopy());
        compare.put("totalRecordCount", totalRecordCount);
        compare.put("rawRuntimeValuesCopied", false);
        compare.put("redactionApplied", true);
        compare.put("truncated", anyReadTruncated(reads));
        compare.set("warnings", aggregateRuntimeRelatedSurfaceReadWarnings(reads));
        ObjectNode diagnostics = compare.putObject("diagnostics");
        diagnostics.set("toolPlanExecution", toolPlan.path("executionDiagnostics").deepCopy());
        return compare;
    }

    private boolean runtimeRelatedSurfaceCompareEvidenceAvailable(ArrayNode reads, ObjectNode toolPlan) {
        JsonNode dimension = toolPlan == null
                ? null
                : toolPlan.path("aggregationPolicy").path("comparisonDimension");
        String fieldRef = text(dimension, "fieldRef");
        if (!safeIdentifier(fieldRef) || reads == null || reads.size() != 2) {
            return false;
        }
        for (JsonNode read : reads) {
            if (!readDeclaresProjectionField(read, fieldRef)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> allowedCompareFactKinds(JsonNode dimension) {
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode factKind : dimension == null ? objectMapper.createArrayNode() : dimension.path("allowedFactKinds")) {
            if (factKind.isTextual() && StringUtils.hasText(factKind.asText())) {
                allowed.add(factKind.asText());
            }
        }
        return allowed;
    }

    private void markRuntimeRelatedSurfaceCompareEvidenceBlocked(ObjectNode toolPlan, ArrayNode reads) {
        if (toolPlan == null) {
            return;
        }
        String fieldRef = text(toolPlan.path("aggregationPolicy").path("comparisonDimension"), "fieldRef");
        ObjectNode aggregationPolicy = toolPlan.withObject("/aggregationPolicy");
        aggregationPolicy.put("compareEvidenceEmitted", false);
        aggregationPolicy.put("compareExecutionStage", "terminal_governed_compare_blocked");
        ObjectNode diagnostics = toolPlan.withObject("/executionDiagnostics");
        diagnostics.put("compareEvidenceEmitted", false);
        diagnostics.put("compareExecutionStage", "terminal_governed_compare_blocked");
        diagnostics.put("compareEvidenceFailureCode", "runtime-related-surface-compare-projection-field-missing");
        diagnostics.set("compareEvidenceMissingProjectionSurfaceRefs",
                compareEvidenceMissingProjectionSurfaceRefs(reads, fieldRef));
    }

    private ArrayNode compareEvidenceMissingProjectionSurfaceRefs(ArrayNode reads, String fieldRef) {
        ArrayNode missing = objectMapper.createArrayNode();
        if (reads == null || !safeIdentifier(fieldRef)) {
            return missing;
        }
        for (JsonNode read : reads) {
            if (!readDeclaresProjectionField(read, fieldRef)) {
                missing.add(firstNonBlank(text(read, "surfaceRef"), "unknown"));
            }
        }
        return missing;
    }

    private void appendCompareDeltaFact(
            ArrayNode facts,
            ObjectNode recordCountsBySurface,
            List<String> comparedSurfaceRefs,
            int factIndex) {
        if (comparedSurfaceRefs.size() != 2) {
            return;
        }
        String leftSurfaceRef = comparedSurfaceRefs.get(0);
        String rightSurfaceRef = comparedSurfaceRefs.get(1);
        int leftCount = recordCountsBySurface.path(leftSurfaceRef).asInt(0);
        int rightCount = recordCountsBySurface.path(rightSurfaceRef).asInt(0);
        ObjectNode delta = facts.addObject();
        delta.put("factRef", "runtime-compare-fact:record-count-delta:" + factIndex);
        delta.put("kind", "record_count_delta");
        delta.put("leftSurfaceRef", leftSurfaceRef);
        delta.put("rightSurfaceRef", rightSurfaceRef);
        delta.put("leftRecordCount", leftCount);
        delta.put("rightRecordCount", rightCount);
        delta.put("absoluteDelta", Math.abs(leftCount - rightCount));
        delta.put("direction", leftCount == rightCount ? "equal" : leftCount > rightCount ? "left_greater" : "right_greater");
        delta.put("redactionApplied", true);
    }

    private void appendCompareCategoryOverlapFact(
            ArrayNode facts,
            ObjectNode distributionsBySurface,
            List<String> comparedSurfaceRefs,
            String fieldRef,
            int factIndex) {
        if (comparedSurfaceRefs.size() != 2) {
            return;
        }
        String leftSurfaceRef = comparedSurfaceRefs.get(0);
        String rightSurfaceRef = comparedSurfaceRefs.get(1);
        Set<String> leftCategories = categoryKeys(distributionsBySurface.path(leftSurfaceRef));
        Set<String> rightCategories = categoryKeys(distributionsBySurface.path(rightSurfaceRef));
        Set<String> shared = new LinkedHashSet<>(leftCategories);
        shared.retainAll(rightCategories);
        Set<String> leftOnly = new LinkedHashSet<>(leftCategories);
        leftOnly.removeAll(rightCategories);
        Set<String> rightOnly = new LinkedHashSet<>(rightCategories);
        rightOnly.removeAll(leftCategories);

        ObjectNode overlap = facts.addObject();
        overlap.put("factRef", "runtime-compare-fact:category-overlap:" + factIndex);
        overlap.put("kind", "category_overlap");
        overlap.put("fieldRef", fieldRef);
        overlap.put("leftSurfaceRef", leftSurfaceRef);
        overlap.put("rightSurfaceRef", rightSurfaceRef);
        overlap.put("sharedCategoryCount", shared.size());
        overlap.put("leftOnlyCategoryCount", leftOnly.size());
        overlap.put("rightOnlyCategoryCount", rightOnly.size());
        ArrayNode sharedCategories = overlap.putArray("sharedCategories");
        shared.forEach(sharedCategories::add);
        ArrayNode leftOnlyCategories = overlap.putArray("leftOnlyCategories");
        leftOnly.forEach(leftOnlyCategories::add);
        ArrayNode rightOnlyCategories = overlap.putArray("rightOnlyCategories");
        rightOnly.forEach(rightOnlyCategories::add);
        overlap.put("redactionApplied", true);
    }

    private void appendComparePresenceMatrixFact(
            ArrayNode facts,
            ObjectNode distributionsBySurface,
            List<String> comparedSurfaceRefs,
            String fieldRef,
            int factIndex) {
        if (comparedSurfaceRefs.size() != 2) {
            return;
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String surfaceRef : comparedSurfaceRefs) {
            categories.addAll(categoryKeys(distributionsBySurface.path(surfaceRef)));
        }
        ObjectNode matrix = facts.addObject();
        matrix.put("factRef", "runtime-compare-fact:record-presence-matrix:" + factIndex);
        matrix.put("kind", "record_presence_matrix");
        matrix.put("fieldRef", fieldRef);
        ArrayNode surfaceRefs = matrix.putArray("surfaceRefs");
        comparedSurfaceRefs.forEach(surfaceRefs::add);
        ArrayNode categoryRefs = matrix.putArray("categories");
        categories.forEach(categoryRefs::add);
        ObjectNode presenceBySurface = matrix.putObject("presenceBySurface");
        for (String surfaceRef : comparedSurfaceRefs) {
            Set<String> surfaceCategories = categoryKeys(distributionsBySurface.path(surfaceRef));
            ObjectNode surfacePresence = presenceBySurface.putObject(surfaceRef);
            for (String category : categories) {
                surfacePresence.put(category, surfaceCategories.contains(category));
            }
        }
        matrix.put("absenceIsNotEvidence", true);
        matrix.put("redactionApplied", true);
        matrix.put("rawRuntimeValuesCopied", false);
    }

    private void appendCompareTemporalCoverageFacts(
            ArrayNode facts,
            ArrayNode reads,
            List<String> comparedSurfaceRefs,
            String fieldRef,
            int factIndex) {
        if (reads == null || comparedSurfaceRefs.size() != 2) {
            return;
        }
        int index = factIndex;
        for (JsonNode read : reads) {
            String surfaceRef = firstNonBlank(text(read, "surfaceRef"), "unknown");
            String stepRef = firstNonBlank(text(read, "stepRef"), "runtime-tool-step:" + surfaceRef);
            TemporalCoverage coverage = temporalCoverage(read.path("records"), fieldRef);
            ObjectNode fact = facts.addObject();
            fact.put("factRef", "runtime-compare-fact:temporal-coverage:" + index++);
            fact.put("kind", "temporal_coverage");
            fact.put("surfaceRef", surfaceRef);
            fact.put("sourceReadRef", stepRef);
            fact.put("fieldRef", fieldRef);
            fact.put("minValue", coverage.minValue());
            fact.put("maxValue", coverage.maxValue());
            fact.put("recordCountWithValue", coverage.recordCountWithValue());
            fact.put("recordCountMissingValue", coverage.recordCountMissingValue());
            fact.put("redactionApplied", read.path("redactionApplied").asBoolean(true));
            fact.put("rawRuntimeValuesCopied", false);
        }
    }

    private TemporalCoverage temporalCoverage(JsonNode records, String fieldRef) {
        Instant minInstant = null;
        Instant maxInstant = null;
        String minValue = "";
        String maxValue = "";
        int withValue = 0;
        int missingValue = 0;
        for (JsonNode record : records) {
            TemporalPoint point = temporalPoint(record.path(fieldRef));
            if (point == null) {
                missingValue++;
                continue;
            }
            withValue++;
            if (minInstant == null || point.instant().isBefore(minInstant)) {
                minInstant = point.instant();
                minValue = point.normalizedValue();
            }
            if (maxInstant == null || point.instant().isAfter(maxInstant)) {
                maxInstant = point.instant();
                maxValue = point.normalizedValue();
            }
        }
        return new TemporalCoverage(minValue, maxValue, withValue, missingValue);
    }

    private TemporalPoint temporalPoint(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isTextual()) {
            return null;
        }
        String raw = value.asText("").trim();
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            Instant instant = Instant.parse(raw);
            return new TemporalPoint(instant, instant.toString());
        } catch (DateTimeParseException ignored) {
            // Try a plain ISO date next; compare it at UTC start of day.
        }
        try {
            LocalDate date = LocalDate.parse(raw);
            return new TemporalPoint(date.atStartOfDay().toInstant(ZoneOffset.UTC), date.toString());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Set<String> categoryKeys(JsonNode distribution) {
        Set<String> keys = new LinkedHashSet<>();
        if (distribution == null || !distribution.isObject()) {
            return keys;
        }
        distribution.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private boolean readDeclaresProjectionField(JsonNode read, String fieldRef) {
        if (!safeIdentifier(fieldRef)) {
            return false;
        }
        for (JsonNode projectionField : read.path("projectionFields")) {
            if (projectionField.isTextual() && fieldRef.equals(projectionField.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private String safeComparisonCategory(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "__missing__";
        }
        if (value.isTextual() && StringUtils.hasText(value.asText())) {
            return value.asText().trim();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return "__non_scalar__";
    }

    private boolean anyReadTruncated(JsonNode reads) {
        if (reads == null || !reads.isArray()) {
            return false;
        }
        for (JsonNode read : reads) {
            if (read.path("truncated").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode aggregateRuntimeRelatedSurfaceReadWarnings(JsonNode reads) {
        ArrayNode warnings = objectMapper.createArrayNode();
        Set<String> seen = new LinkedHashSet<>();
        if (reads != null && reads.isArray()) {
            for (JsonNode read : reads) {
                for (JsonNode warning : read.path("warnings")) {
                    if (warning.isTextual() && StringUtils.hasText(warning.asText()) && seen.add(warning.asText())) {
                        warnings.add(warning.asText());
                    }
                }
            }
        }
        return warnings;
    }

    private RuntimeRelatedSurfaceConsultativeIntent runtimeRelatedSurfaceConsultativeIntent(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext,
            String tenantId,
            String userId,
            String environment) {
        AgenticAuthoringSemanticDecision activeDecision = request == null ? null : request.activeSemanticDecision();
        String semanticKind = firstNonBlank(
                activeDecision == null ? "" : activeDecision.artifactIntent(),
                activeDecision == null ? "" : activeDecision.changeKind(),
                activeDecision == null ? "" : activeDecision.operationKind());
        if (StringUtils.hasText(semanticKind)) {
            String normalized = normalizeRuntimeRelatedSurfaceIntentKind(semanticKind);
            RuntimeRelatedSurfaceDisambiguationSelection disambiguationSelection =
                    runtimeRelatedSurfaceDisambiguationSelection(activeDecision);
            return new RuntimeRelatedSurfaceConsultativeIntent(
                    normalized,
                    "consultativeIntent:" + normalized,
                    activeDecision.confidence() == null ? 0.75d : activeDecision.confidence(),
                    List.of("active-semantic-decision-runtime-related-surface-intent-kind"),
                    false,
                    "",
                    disambiguationSelection.surfaceRef(),
                    disambiguationSelection.surfaceRef(),
                    disambiguationSelection.surfaceRef(),
                    disambiguationSelection.candidateRef(),
                    disambiguationSelection.optionRef(),
                    false,
                    "none",
                    null,
                    null);
        }
        Optional<RuntimeRelatedSurfaceConsultativeIntent> backendPolicyIntent =
                resolveRuntimeRelatedSurfaceIntentWithBackendPolicy(runtimeContext);
        if (backendPolicyIntent.isPresent()) {
            return backendPolicyIntent.get();
        }
        Optional<RuntimeRelatedSurfaceConsultativeIntent> llmIntent =
                resolveRuntimeRelatedSurfaceIntentWithLlm(request, runtimeContext, tenantId, userId, environment);
        if (llmIntent.isPresent()) {
            RuntimeRelatedSurfaceTargetCandidateResolution candidateResolution =
                    resolveRuntimeRelatedSurfaceTargetFromCandidateCatalog(request, runtimeContext, llmIntent.get())
                            .orElse(null);
            if (candidateResolution != null && candidateResolution.intent() != null) {
                return candidateResolution.intent();
            }
            RuntimeRelatedSurfaceTargetRefinement refinement = refineRuntimeRelatedSurfaceTargetWithLlm(
                    request,
                    runtimeContext,
                    candidateResolution == null ? llmIntent.get() : candidateResolution.intentWithDiagnostics(),
                    tenantId,
                    userId,
                    environment).orElse(null);
            if (refinement != null && refinement.intent() != null) {
                return refinement.intent();
            }
            if (refinement != null && refinement.diagnostics() != null) {
                RuntimeRelatedSurfaceConsultativeIntent intentForDiagnostics =
                        candidateResolution == null ? llmIntent.get() : candidateResolution.intentWithDiagnostics();
                return withTargetRefinementDiagnostics(intentForDiagnostics, refinement.diagnostics());
            }
            return candidateResolution == null ? llmIntent.get() : candidateResolution.intentWithDiagnostics();
        }
        boolean hasPendingDisambiguationContext = safePendingRuntimeRelatedSurfaceDisambiguationContext(
                request,
                runtimeContext) != null;
        boolean focusedDetailPrompt = runtimeRelatedSurfacePromptRequestsFocusedDetail(
                request == null ? "" : request.userPrompt());
        String fallbackTargetResolutionMode = hasPendingDisambiguationContext || focusedDetailPrompt ? "optional" : "none";
        RuntimeRelatedSurfaceConsultativeIntent fallbackIntent = new RuntimeRelatedSurfaceConsultativeIntent(
                "runtime_surface_disambiguation",
                "consultativeIntent:runtime_surface_disambiguation",
                0.50d,
                List.of("runtime-related-surface-intent-fallback-read-free"),
                true,
                "",
                "",
                "",
                "",
                "",
                "",
                false,
                fallbackTargetResolutionMode,
                null,
                null);
        RuntimeRelatedSurfaceTargetCandidateResolution candidateResolution =
                resolveRuntimeRelatedSurfaceTargetFromCandidateCatalog(request, runtimeContext, fallbackIntent)
                        .orElse(null);
        if (candidateResolution != null && candidateResolution.intent() != null) {
            return candidateResolution.intent();
        }
        RuntimeRelatedSurfaceTargetRefinement refinement = refineRuntimeRelatedSurfaceTargetWithLlm(
                request,
                runtimeContext,
                candidateResolution == null ? fallbackIntent : candidateResolution.intentWithDiagnostics(),
                tenantId,
                userId,
                environment).orElse(null);
        if (refinement != null && refinement.intent() != null) {
            return refinement.intent();
        }
        if (refinement != null && refinement.diagnostics() != null) {
            RuntimeRelatedSurfaceConsultativeIntent intentForDiagnostics =
                    candidateResolution == null ? fallbackIntent : candidateResolution.intentWithDiagnostics();
            return withTargetRefinementDiagnostics(intentForDiagnostics, refinement.diagnostics());
        }
        return candidateResolution == null ? fallbackIntent : candidateResolution.intentWithDiagnostics();
    }

    private Optional<RuntimeRelatedSurfaceConsultativeIntent> resolveRuntimeRelatedSurfaceIntentWithBackendPolicy(
            ObjectNode runtimeContext) {
        if (runtimeContext == null || runtimeRelatedSurfaceIntentPolicy == null
                || !runtimeRelatedSurfaceIntentPolicy.temporalCompareSmokeEnabled()) {
            return Optional.empty();
        }
        String fieldRef = runtimeRelatedSurfaceIntentPolicy.temporalComparisonFieldRef();
        if (!safeIdentifier(fieldRef)
                || runtimeRelatedSurfaceComparableFieldSurfaceCount(runtimeContext, fieldRef) < 2) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeRelatedSurfaceConsultativeIntent(
                "runtime_related_surface_compare",
                "consultativeIntent:runtime_related_surface_compare",
                0.99d,
                List.of(
                        "backend-owned-runtime-related-surface-intent-policy",
                        "runtime-related-surface-temporal-compare-smoke"),
                false,
                fieldRef,
                "",
	                "",
	                "",
                "",
		                "",
		                true,
		                "none",
		                null,
		                null));
	    }

    private int runtimeRelatedSurfaceComparableFieldSurfaceCount(ObjectNode runtimeContext, String fieldRef) {
        if (runtimeContext == null || !safeIdentifier(fieldRef)) {
            return 0;
        }
        Set<String> declaredSurfaceRefs = new LinkedHashSet<>();
        for (JsonNode surface : runtimeContext.path("availableSurfaces")) {
            if (!surface.isTextual()) {
                continue;
            }
            String surfaceRef = surface.asText();
            if (runtimeSurfaceDeclaresSchemaField(runtimeContext, surfaceRef, fieldRef)
                    && !runtimeSurfaceRedactsSchemaField(runtimeContext, surfaceRef, fieldRef)) {
                declaredSurfaceRefs.add(surfaceRef);
            }
        }
        return declaredSurfaceRefs.size();
    }

    private Optional<RuntimeRelatedSurfaceConsultativeIntent> resolveRuntimeRelatedSurfaceIntentWithLlm(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext,
            String tenantId,
            String userId,
            String environment) {
        if (request == null || runtimeContext == null || providerManagementService == null) {
            return Optional.empty();
        }
        try {
            String generated = providerManagementService.generateText(
                    runtimeRelatedSurfaceIntentPrompt(request, runtimeContext),
                    AiCallConfig.builder()
                            .provider(request.provider())
                            .model(request.model())
                            .apiKey(request.apiKey())
                            .temperature(0.0d)
                            .maxTokens(320)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            return parseRuntimeRelatedSurfaceIntent(generated);
        } catch (RuntimeException ex) {
            log.warn("[AgenticAuthoring] Runtime related surface semantic intent resolution failed; falling back conservatively. reason={}",
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<RuntimeRelatedSurfaceTargetCandidateResolution> resolveRuntimeRelatedSurfaceTargetFromCandidateCatalog(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext,
            RuntimeRelatedSurfaceConsultativeIntent initialIntent) {
        if (request == null || runtimeContext == null || initialIntent == null
                || runtimeRelatedSurfaceIntentHasTarget(initialIntent)) {
            return Optional.empty();
        }
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(initialIntent.kind());
        String targetResolutionMode = normalizeRuntimeRelatedSurfaceTargetResolutionMode(
                initialIntent.targetResolutionMode(),
                initialIntent);
        if ("runtime_surface_disambiguation".equals(normalized)
                && ("required".equals(targetResolutionMode)
                || "optional".equals(targetResolutionMode)
                && runtimeRelatedSurfacePromptRequestsFocusedDetail(request.userPrompt()))) {
            normalized = "runtime_related_surface_detail";
        }
        if (!Set.of(
                "runtime_related_surface_list",
                "runtime_related_surface_summary",
                "runtime_related_surface_detail").contains(normalized)) {
            return Optional.empty();
        }
        if (!runtimeRelatedSurfaceTargetRefinementAllowed(targetResolutionMode)) {
            return Optional.empty();
        }
        ObjectNode resolution = runtimeRelatedSurfaceResolution(runtimeContext, null);
        if (acceptedRuntimeRelatedSurfaceCandidateCount(resolution) < 2) {
            return Optional.empty();
        }
        RuntimeRelatedSurfaceTargetCatalogMatch match =
                runtimeRelatedSurfaceTargetCatalogMatch(request.userPrompt(), resolution);
        ObjectNode diagnostics = runtimeRelatedSurfaceTargetCandidateResolutionDiagnostics(
                initialIntent,
                match,
                acceptedRuntimeRelatedSurfaceCandidateCount(resolution));
        RuntimeRelatedSurfaceConsultativeIntent intentWithDiagnostics =
                withTargetCandidateResolutionDiagnostics(initialIntent, diagnostics);
        if (!match.accepted()) {
            return Optional.of(new RuntimeRelatedSurfaceTargetCandidateResolution(
                    null,
                    intentWithDiagnostics,
                    diagnostics));
        }
        RuntimeRelatedSurfaceConsultativeIntent acceptedIntent = new RuntimeRelatedSurfaceConsultativeIntent(
                normalized,
                "consultativeIntent:" + normalized,
                Math.max(initialIntent.confidence(), 0.82d),
                appendReason(initialIntent.reasons(), "backend-runtime-related-surface-target-candidate-catalog"),
                initialIntent.fallbackApplied(),
                initialIntent.comparisonDimensionFieldRef(),
                "runtime_related_surface_list".equals(normalized) ? match.surfaceRef() : "",
                "runtime_related_surface_summary".equals(normalized) ? match.surfaceRef() : "",
                "runtime_related_surface_detail".equals(normalized) ? match.surfaceRef() : "",
                "",
                "",
                initialIntent.requiresTemporalComparisonDimension(),
                "none",
                diagnostics,
                initialIntent.targetRefinementDiagnostics());
        return Optional.of(new RuntimeRelatedSurfaceTargetCandidateResolution(
                acceptedIntent,
                acceptedIntent,
                diagnostics));
    }

    private RuntimeRelatedSurfaceConsultativeIntent withTargetCandidateResolutionDiagnostics(
            RuntimeRelatedSurfaceConsultativeIntent intent,
            ObjectNode diagnostics) {
        if (intent == null || diagnostics == null) {
            return intent;
        }
        return new RuntimeRelatedSurfaceConsultativeIntent(
                intent.kind(),
                intent.semanticDecisionRef(),
                intent.confidence(),
                intent.reasons(),
                intent.fallbackApplied(),
                intent.comparisonDimensionFieldRef(),
                intent.listTargetSurfaceRef(),
                intent.summaryTargetSurfaceRef(),
                intent.detailTargetSurfaceRef(),
                intent.detailTargetCandidateRef(),
                intent.detailTargetOptionRef(),
                intent.requiresTemporalComparisonDimension(),
                intent.targetResolutionMode(),
                diagnostics,
                intent.targetRefinementDiagnostics());
    }

    private ObjectNode runtimeRelatedSurfaceTargetCandidateResolutionDiagnostics(
            RuntimeRelatedSurfaceConsultativeIntent initialIntent,
            RuntimeRelatedSurfaceTargetCatalogMatch match,
            int candidateCount) {
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-runtime-related-surface-target-candidate-resolution.v1");
        diagnostics.put("source", "backend_runtime_target_catalog");
        diagnostics.put("targetResolutionMode", normalizeRuntimeRelatedSurfaceTargetResolutionMode(
                initialIntent == null ? "" : initialIntent.targetResolutionMode(),
                initialIntent));
        diagnostics.put("intentKind", normalizeRuntimeRelatedSurfaceIntentKind(initialIntent == null ? "" : initialIntent.kind()));
        diagnostics.put("candidateCount", candidateCount);
        diagnostics.put("status", match.accepted() ? "accepted" : match.status());
        diagnostics.put("provenance", match.accepted() ? "backend_reconciled" : "backend_rejected");
        diagnostics.put("accepted", match.accepted());
        diagnostics.put("targetSurfaceRef", match.accepted() && safeIdentifier(match.surfaceRef()) ? match.surfaceRef() : "");
        if (match.accepted() && StringUtils.hasText(match.candidateRef())) {
            diagnostics.put("candidateRef", match.candidateRef());
        }
        if (match.accepted() && StringUtils.hasText(match.runtimeSurfaceInstanceRef())) {
            diagnostics.put("runtimeSurfaceInstanceRef", match.runtimeSurfaceInstanceRef());
        }
        if (StringUtils.hasText(match.matchedTermKind())) {
            diagnostics.put("matchedTermKind", match.matchedTermKind());
        }
        diagnostics.put("score", Math.max(match.score(), 0));
        if (!match.accepted() && StringUtils.hasText(match.failureCode())) {
            diagnostics.put("failureCode", match.failureCode());
        }
        if (!match.accepted() && match.evaluatedCandidates() != null && !match.evaluatedCandidates().isEmpty()) {
            diagnostics.set("evaluatedCandidates", match.evaluatedCandidates().deepCopy());
        }
        return diagnostics;
    }

    private RuntimeRelatedSurfaceTargetCatalogMatch runtimeRelatedSurfaceTargetCatalogMatch(
            String userPrompt,
            ObjectNode resolution) {
        String normalizedPrompt = normalizedTargetCatalogText(userPrompt);
        if (!StringUtils.hasText(normalizedPrompt) || resolution == null || !resolution.path("candidates").isArray()) {
            return RuntimeRelatedSurfaceTargetCatalogMatch.rejected(
                    "not_found",
                    "runtime-related-surface-target-candidate-not-found");
        }
        RuntimeRelatedSurfaceTargetCatalogMatch best = RuntimeRelatedSurfaceTargetCatalogMatch.rejected(
                "not_found",
                "runtime-related-surface-target-candidate-not-found");
        ArrayNode evaluatedCandidates = objectMapper.createArrayNode();
        Map<String, Integer> acceptedTermCardinality = runtimeRelatedSurfaceAcceptedTermCardinality(
                resolution.path("candidates"));
        boolean ambiguous = false;
        for (JsonNode candidate : resolution.path("candidates")) {
            if (!candidate.isObject() || !"accepted".equals(text(candidate, "status"))) {
                continue;
            }
            RuntimeRelatedSurfaceTargetCatalogMatch match =
                    runtimeRelatedSurfaceTargetCatalogMatchForCandidate(
                            normalizedPrompt,
                            candidate,
                            acceptedTermCardinality);
            evaluatedCandidates.add(runtimeRelatedSurfaceTargetCatalogCandidateDiagnostic(candidate, match, normalizedPrompt));
            if (match.score() <= 0) {
                continue;
            }
            if (match.score() > best.score()) {
                best = match;
                ambiguous = false;
            } else if (match.score() == best.score()) {
                ambiguous = true;
            }
        }
        if (best.score() < 60) {
            return RuntimeRelatedSurfaceTargetCatalogMatch.rejected(
                    "not_found",
                    "runtime-related-surface-target-candidate-not-found",
                    evaluatedCandidates);
        }
        if (ambiguous) {
            return RuntimeRelatedSurfaceTargetCatalogMatch.rejected(
                    "ambiguous",
                    "runtime-related-surface-target-candidate-ambiguous",
                    evaluatedCandidates);
        }
        return best;
    }

    private ObjectNode runtimeRelatedSurfaceTargetCatalogCandidateDiagnostic(
            JsonNode candidate,
            RuntimeRelatedSurfaceTargetCatalogMatch match,
            String normalizedPrompt) {
        ObjectNode diagnostic = objectMapper.createObjectNode();
        copySafeScalar(candidate, diagnostic, "surfaceRef");
        copySafeScalar(candidate, diagnostic, "candidateRef");
        copySafeScalar(candidate, diagnostic, "runtimeSurfaceInstanceRef");
        diagnostic.put("matched", match != null && match.accepted());
        diagnostic.put("score", Math.max(match == null ? 0 : match.score(), 0));
        if (match != null && StringUtils.hasText(match.matchedTermKind())) {
            diagnostic.put("matchedTermKind", match.matchedTermKind());
        }
        int ignoredNegatedTermCount = runtimeRelatedSurfaceTargetCatalogIgnoredNegatedTermCount(
                normalizedPrompt,
                candidate);
        diagnostic.put("ignoredNegatedTermCount", ignoredNegatedTermCount);
        if (match == null || !match.accepted()) {
            diagnostic.put("failureCode", ignoredNegatedTermCount > 0
                    ? "runtime-related-surface-target-candidate-negated"
                    : "runtime-related-surface-target-candidate-not-found");
        }
        return diagnostic;
    }

    private RuntimeRelatedSurfaceTargetCatalogMatch runtimeRelatedSurfaceTargetCatalogMatchForCandidate(
            String normalizedPrompt,
            JsonNode candidate,
            Map<String, Integer> acceptedTermCardinality) {
        RuntimeRelatedSurfaceTargetCatalogMatch best = RuntimeRelatedSurfaceTargetCatalogMatch.rejected(
                "not_found",
                "runtime-related-surface-target-candidate-not-found");
        best = strongerRuntimeRelatedSurfaceTargetCatalogMatch(best, normalizedPrompt, candidate, acceptedTermCardinality, "surfaceRef", text(candidate, "surfaceRef"), 100);
        best = strongerRuntimeRelatedSurfaceTargetCatalogMatch(best, normalizedPrompt, candidate, acceptedTermCardinality, "candidateRef", text(candidate, "candidateRef"), 100);
        best = strongerRuntimeRelatedSurfaceTargetCatalogMatch(best, normalizedPrompt, candidate, acceptedTermCardinality, "runtimeSurfaceInstanceRef", text(candidate, "runtimeSurfaceInstanceRef"), 100);
        best = strongerRuntimeRelatedSurfaceTargetCatalogMatch(best, normalizedPrompt, candidate, acceptedTermCardinality, "label", text(candidate, "label"), 80);
        if (candidate.path("semanticAliases").isArray()) {
            for (JsonNode alias : candidate.path("semanticAliases")) {
                if (alias.isTextual()) {
                    best = strongerRuntimeRelatedSurfaceTargetCatalogMatch(best, normalizedPrompt, candidate, acceptedTermCardinality, "semanticAlias", alias.asText(), 70);
                }
            }
        }
        return best.score() > 0
                ? best
                : RuntimeRelatedSurfaceTargetCatalogMatch.rejected(
                "not_found",
                "runtime-related-surface-target-candidate-not-found");
    }

    private RuntimeRelatedSurfaceTargetCatalogMatch strongerRuntimeRelatedSurfaceTargetCatalogMatch(
            RuntimeRelatedSurfaceTargetCatalogMatch current,
            String normalizedPrompt,
            JsonNode candidate,
            Map<String, Integer> acceptedTermCardinality,
            String termKind,
            String term,
            int score) {
        String normalizedTerm = normalizedTargetCatalogText(term);
        if (!safeRuntimeRelatedSurfaceTargetTerm(normalizedTerm)) {
            return current;
        }
        if (acceptedTermCardinality != null && acceptedTermCardinality.getOrDefault(normalizedTerm, 0) > 1) {
            return current;
        }
        if (!normalizedPromptContainsAffirmedTargetTerm(normalizedPrompt, normalizedTerm)) {
            return current;
        }
        if (score <= current.score()) {
            return current;
        }
        return RuntimeRelatedSurfaceTargetCatalogMatch.accepted(
                text(candidate, "surfaceRef"),
                text(candidate, "candidateRef"),
                text(candidate, "runtimeSurfaceInstanceRef"),
                termKind,
                normalizedTerm,
                score);
    }

    private Map<String, Integer> runtimeRelatedSurfaceAcceptedTermCardinality(JsonNode candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (candidates == null || !candidates.isArray()) {
            return counts;
        }
        for (JsonNode candidate : candidates) {
            if (!candidate.isObject() || !"accepted".equals(text(candidate, "status"))) {
                continue;
            }
            Set<String> terms = new LinkedHashSet<>();
            addRuntimeRelatedSurfaceTargetCatalogTerm(terms, text(candidate, "surfaceRef"));
            addRuntimeRelatedSurfaceTargetCatalogTerm(terms, text(candidate, "candidateRef"));
            addRuntimeRelatedSurfaceTargetCatalogTerm(terms, text(candidate, "runtimeSurfaceInstanceRef"));
            addRuntimeRelatedSurfaceTargetCatalogTerm(terms, text(candidate, "label"));
            if (candidate.path("semanticAliases").isArray()) {
                for (JsonNode alias : candidate.path("semanticAliases")) {
                    if (alias.isTextual()) {
                        addRuntimeRelatedSurfaceTargetCatalogTerm(terms, alias.asText());
                    }
                }
            }
            for (String term : terms) {
                counts.merge(term, 1, Integer::sum);
            }
        }
        return counts;
    }

    private boolean normalizedPromptContainsTargetTerm(String normalizedPrompt, String normalizedTerm) {
        return StringUtils.hasText(normalizedPrompt)
                && StringUtils.hasText(normalizedTerm)
                && (" " + normalizedPrompt + " ").contains(" " + normalizedTerm + " ");
    }

    private boolean normalizedPromptContainsAffirmedTargetTerm(String normalizedPrompt, String normalizedTerm) {
        if (!normalizedPromptContainsTargetTerm(normalizedPrompt, normalizedTerm)) {
            return false;
        }
        List<String> promptTokens = Arrays.stream(normalizedPrompt.split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
        List<String> termTokens = Arrays.stream(normalizedTerm.split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
        if (promptTokens.isEmpty() || termTokens.isEmpty() || termTokens.size() > promptTokens.size()) {
            return true;
        }
        for (int index = 0; index <= promptTokens.size() - termTokens.size(); index++) {
            boolean matches = true;
            for (int offset = 0; offset < termTokens.size(); offset++) {
                if (!promptTokens.get(index + offset).equals(termTokens.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches && !runtimeRelatedSurfaceTargetTermIsNegated(promptTokens, index)) {
                return true;
            }
        }
        return false;
    }

    private int runtimeRelatedSurfaceTargetCatalogIgnoredNegatedTermCount(
            String normalizedPrompt,
            JsonNode candidate) {
        if (!StringUtils.hasText(normalizedPrompt) || candidate == null || !candidate.isObject()) {
            return 0;
        }
        Set<String> normalizedTerms = new LinkedHashSet<>();
        addRuntimeRelatedSurfaceTargetCatalogTerm(normalizedTerms, text(candidate, "surfaceRef"));
        addRuntimeRelatedSurfaceTargetCatalogTerm(normalizedTerms, text(candidate, "candidateRef"));
        addRuntimeRelatedSurfaceTargetCatalogTerm(normalizedTerms, text(candidate, "runtimeSurfaceInstanceRef"));
        addRuntimeRelatedSurfaceTargetCatalogTerm(normalizedTerms, text(candidate, "label"));
        if (candidate.path("semanticAliases").isArray()) {
            for (JsonNode alias : candidate.path("semanticAliases")) {
                if (alias.isTextual()) {
                    addRuntimeRelatedSurfaceTargetCatalogTerm(normalizedTerms, alias.asText());
                }
            }
        }
        if (normalizedTerms.isEmpty()) {
            return 0;
        }
        List<String> promptTokens = Arrays.stream(normalizedPrompt.split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
        int count = 0;
        for (String normalizedTerm : normalizedTerms) {
            if (runtimeRelatedSurfaceTargetTermHasNegatedOccurrence(promptTokens, normalizedTerm)) {
                count++;
            }
        }
        return count;
    }

    private void addRuntimeRelatedSurfaceTargetCatalogTerm(Set<String> normalizedTerms, String term) {
        String normalizedTerm = normalizedTargetCatalogText(term);
        if (safeRuntimeRelatedSurfaceTargetTerm(normalizedTerm)) {
            normalizedTerms.add(normalizedTerm);
        }
    }

    private boolean runtimeRelatedSurfaceTargetTermHasNegatedOccurrence(
            List<String> promptTokens,
            String normalizedTerm) {
        if (promptTokens == null || promptTokens.isEmpty() || !StringUtils.hasText(normalizedTerm)) {
            return false;
        }
        List<String> termTokens = Arrays.stream(normalizedTerm.split("\\s+"))
                .filter(StringUtils::hasText)
                .toList();
        if (termTokens.isEmpty() || termTokens.size() > promptTokens.size()) {
            return false;
        }
        for (int index = 0; index <= promptTokens.size() - termTokens.size(); index++) {
            boolean matches = true;
            for (int offset = 0; offset < termTokens.size(); offset++) {
                if (!promptTokens.get(index + offset).equals(termTokens.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches && runtimeRelatedSurfaceTargetTermIsNegated(promptTokens, index)) {
                return true;
            }
        }
        return false;
    }

    private boolean runtimeRelatedSurfaceTargetTermIsNegated(List<String> promptTokens, int termStartIndex) {
        int from = Math.max(0, termStartIndex - 4);
        for (int index = from; index < termStartIndex; index++) {
            if (Set.of("nao", "sem", "exceto", "menos", "excluir", "exclua", "ignorar", "ignore")
                    .contains(promptTokens.get(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean safeRuntimeRelatedSurfaceTargetTerm(String normalizedTerm) {
        if (!StringUtils.hasText(normalizedTerm) || normalizedTerm.length() < 4) {
            return false;
        }
        return !Set.of(
                "dados",
                "relacionados",
                "selecionada",
                "superficie",
                "detalhe",
                "detalhes",
                "resumo",
                "resuma",
                "listar",
                "mostrar").contains(normalizedTerm);
    }

    private boolean runtimeRelatedSurfacePromptRequestsFocusedDetail(String userPrompt) {
        String normalized = normalizedTargetCatalogText(userPrompt);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        return normalizedPromptContainsTargetTerm(normalized, "drill down")
                || normalizedPromptContainsTargetTerm(normalized, "detalhe")
                || normalizedPromptContainsTargetTerm(normalized, "detalhado")
                || normalizedPromptContainsTargetTerm(normalized, "detalhada")
                || normalizedPromptContainsTargetTerm(normalized, "aprofundar")
                || normalizedPromptContainsTargetTerm(normalized, "inspecionar");
    }

    private String normalizedTargetCatalogText(String text) {
        String normalized = Normalizer.normalize(value(text), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private Optional<RuntimeRelatedSurfaceTargetRefinement> refineRuntimeRelatedSurfaceTargetWithLlm(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext,
            RuntimeRelatedSurfaceConsultativeIntent initialIntent,
            String tenantId,
            String userId,
            String environment) {
        if (providerManagementService == null || request == null || runtimeContext == null || initialIntent == null) {
            return Optional.empty();
        }
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(initialIntent.kind());
        if ("runtime_related_surface_availability".equals(normalized)
                || "runtime_related_surface_compare".equals(normalized)
                || runtimeRelatedSurfaceIntentHasTarget(initialIntent)) {
            return Optional.empty();
        }
        String targetResolutionMode = normalizeRuntimeRelatedSurfaceTargetResolutionMode(
                initialIntent.targetResolutionMode(),
                initialIntent);
        if (!runtimeRelatedSurfaceTargetRefinementAllowed(targetResolutionMode)) {
            return Optional.empty();
        }
        ObjectNode resolution = runtimeRelatedSurfaceResolution(runtimeContext, null);
        if (acceptedRuntimeRelatedSurfaceCandidateCount(resolution) < 2) {
            return Optional.empty();
        }
        if (!Set.of(
                "runtime_surface_disambiguation",
                "runtime_related_surface_list",
                "runtime_related_surface_summary",
                "runtime_related_surface_detail").contains(normalized)) {
            return Optional.empty();
        }
        try {
            String generated = providerManagementService.generateText(
                    runtimeRelatedSurfaceTargetResolutionPrompt(request, runtimeContext, resolution, initialIntent),
                    AiCallConfig.builder()
                            .temperature(0.0d)
                            .maxTokens(160)
                            .build(),
                    tenantId,
                    userId,
                    environment);
            Optional<RuntimeRelatedSurfaceTargetDecision> decision = parseRuntimeRelatedSurfaceTargetDecision(generated);
            if (decision.isEmpty()) {
                return Optional.of(new RuntimeRelatedSurfaceTargetRefinement(
                        null,
                        runtimeRelatedSurfaceTargetRefinementDiagnostics(
                                initialIntent,
                                null,
                                "",
                                false,
                                "runtime-related-surface-target-refinement-unparseable")));
            }
            RuntimeRelatedSurfaceTargetDecision targetDecision = decision.get();
            String targetKind = normalizeRuntimeRelatedSurfaceIntentKind(targetDecision.kind());
            String requestedTargetSurfaceRef = targetDecision.targetSurfaceRef();
            JsonNode targetCandidate = acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(
                    resolution, requestedTargetSurfaceRef);
            String targetSurfaceRef = targetCandidate == null ? "" : text(targetCandidate, "surfaceRef");
            if ("runtime_related_surface_compare".equals(targetKind)) {
                ObjectNode diagnostics = runtimeRelatedSurfaceTargetRefinementDiagnostics(
                        initialIntent,
                        targetDecision,
                        "",
                        true,
                        "");
                return Optional.of(new RuntimeRelatedSurfaceTargetRefinement(new RuntimeRelatedSurfaceConsultativeIntent(
                        targetKind,
                        "consultativeIntent:" + targetKind,
                        Math.max(initialIntent.confidence(), targetDecision.confidence()),
                        appendReason(initialIntent.reasons(), "llm-runtime-related-surface-target-resolution"),
                        initialIntent.fallbackApplied(),
                        initialIntent.comparisonDimensionFieldRef(),
                        "",
                        "",
                        "",
                        "",
                        "",
                        initialIntent.requiresTemporalComparisonDimension(),
                        "none",
                        initialIntent.targetCandidateResolutionDiagnostics(),
                        diagnostics),
                        diagnostics));
            }
            if (!Set.of(
                    "runtime_related_surface_list",
                    "runtime_related_surface_summary",
                    "runtime_related_surface_detail").contains(targetKind)
                    || !StringUtils.hasText(targetSurfaceRef)
                    || targetCandidate == null) {
                return Optional.of(new RuntimeRelatedSurfaceTargetRefinement(
                        null,
                        runtimeRelatedSurfaceTargetRefinementDiagnostics(
                                initialIntent,
                                targetDecision,
                                targetSurfaceRef,
                                false,
                                "runtime-related-surface-target-refinement-not-reconciled")));
            }
            ObjectNode diagnostics = runtimeRelatedSurfaceTargetRefinementDiagnostics(
                    initialIntent,
                    targetDecision,
                    targetSurfaceRef,
                    true,
                    "");
            return Optional.of(new RuntimeRelatedSurfaceTargetRefinement(new RuntimeRelatedSurfaceConsultativeIntent(
                    targetKind,
                    "consultativeIntent:" + targetKind,
                    Math.max(initialIntent.confidence(), targetDecision.confidence()),
                    appendReason(initialIntent.reasons(), "llm-runtime-related-surface-target-resolution"),
                    initialIntent.fallbackApplied(),
                    initialIntent.comparisonDimensionFieldRef(),
                    "runtime_related_surface_list".equals(targetKind) ? targetSurfaceRef : "",
                    "runtime_related_surface_summary".equals(targetKind) ? targetSurfaceRef : "",
                    "runtime_related_surface_detail".equals(targetKind) ? targetSurfaceRef : "",
                    "",
                    "",
                    initialIntent.requiresTemporalComparisonDimension(),
                    "none",
                    initialIntent.targetCandidateResolutionDiagnostics(),
                    diagnostics),
                    diagnostics));
        } catch (RuntimeException ex) {
            log.warn("[AgenticAuthoring] Runtime related surface target resolution failed; keeping initial intent. reason={}",
                    ex.getClass().getSimpleName());
            return Optional.of(new RuntimeRelatedSurfaceTargetRefinement(
                    null,
                    runtimeRelatedSurfaceTargetRefinementDiagnostics(
                            initialIntent,
                            null,
                            "",
                            false,
                            "runtime-related-surface-target-refinement-failed")));
        }
    }

    private RuntimeRelatedSurfaceConsultativeIntent withTargetRefinementDiagnostics(
            RuntimeRelatedSurfaceConsultativeIntent intent,
            ObjectNode diagnostics) {
        if (intent == null || diagnostics == null) {
            return intent;
        }
        return new RuntimeRelatedSurfaceConsultativeIntent(
                intent.kind(),
                intent.semanticDecisionRef(),
                intent.confidence(),
                intent.reasons(),
                intent.fallbackApplied(),
                intent.comparisonDimensionFieldRef(),
                intent.listTargetSurfaceRef(),
                intent.summaryTargetSurfaceRef(),
                intent.detailTargetSurfaceRef(),
                intent.detailTargetCandidateRef(),
                intent.detailTargetOptionRef(),
                intent.requiresTemporalComparisonDimension(),
                intent.targetResolutionMode(),
                intent.targetCandidateResolutionDiagnostics(),
                diagnostics);
    }

    private ObjectNode runtimeRelatedSurfaceTargetRefinementDiagnostics(
            RuntimeRelatedSurfaceConsultativeIntent initialIntent,
            RuntimeRelatedSurfaceTargetDecision targetDecision,
            String reconciledTargetSurfaceRef,
            boolean accepted,
            String failureCode) {
        ObjectNode diagnostics = objectMapper.createObjectNode();
        diagnostics.put("schemaVersion", "praxis-runtime-related-surface-target-refinement.v1");
        diagnostics.put("source", "llm_semantic_target_resolution");
        diagnostics.put("targetResolutionMode", normalizeRuntimeRelatedSurfaceTargetResolutionMode(
                initialIntent == null ? "" : initialIntent.targetResolutionMode(),
                initialIntent));
        diagnostics.put("initialKind", normalizeRuntimeRelatedSurfaceIntentKind(initialIntent == null ? "" : initialIntent.kind()));
        diagnostics.put("refinedKind", targetDecision == null ? "" : normalizeRuntimeRelatedSurfaceIntentKind(targetDecision.kind()));
        diagnostics.put("targetSurfaceRef", safeIdentifier(reconciledTargetSurfaceRef) ? reconciledTargetSurfaceRef : "");
        String requested = targetDecision == null ? "" : targetDecision.targetSurfaceRef();
        if (safeIdentifier(requested) && !requested.equals(reconciledTargetSurfaceRef)) {
            diagnostics.put("requestedTargetSurfaceRef", requested);
        }
        diagnostics.put("provenance", accepted ? "backend_reconciled" : "backend_rejected");
        diagnostics.put("confidence", targetDecision == null ? 0.0d : targetDecision.confidence());
        diagnostics.put("accepted", accepted);
        if (StringUtils.hasText(failureCode)) {
            diagnostics.put("failureCode", failureCode);
        }
        return diagnostics;
    }

    private boolean runtimeRelatedSurfaceIntentHasTarget(RuntimeRelatedSurfaceConsultativeIntent intent) {
        if (intent == null) {
            return false;
        }
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(intent.kind());
        if ("runtime_related_surface_list".equals(normalized)) {
            return StringUtils.hasText(intent.listTargetSurfaceRef());
        }
        if ("runtime_related_surface_summary".equals(normalized)) {
            return StringUtils.hasText(intent.summaryTargetSurfaceRef());
        }
        if ("runtime_related_surface_detail".equals(normalized)) {
            return StringUtils.hasText(intent.detailTargetSurfaceRef());
        }
        return false;
    }

    private boolean runtimeRelatedSurfaceTargetRefinementAllowed(String targetResolutionMode) {
        String normalized = value(targetResolutionMode).toLowerCase(Locale.ROOT).replace('-', '_');
        return "optional".equals(normalized) || "required".equals(normalized);
    }

    private String normalizeRuntimeRelatedSurfaceTargetResolutionMode(
            String mode,
            RuntimeRelatedSurfaceConsultativeIntent intent) {
        if (intent == null) {
            return "none";
        }
        return normalizeRuntimeRelatedSurfaceTargetResolutionMode(
                mode,
                intent.kind(),
                intent.listTargetSurfaceRef(),
                intent.summaryTargetSurfaceRef(),
                intent.detailTargetSurfaceRef());
    }

    private String normalizeRuntimeRelatedSurfaceTargetResolutionMode(
            String mode,
            String intentKind,
            String listTargetSurfaceRef,
            String summaryTargetSurfaceRef,
            String detailTargetSurfaceRef) {
        String normalizedMode = value(mode).toLowerCase(Locale.ROOT).replace('-', '_');
        String normalizedKind = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        if (StringUtils.hasText(listTargetSurfaceRef)
                || StringUtils.hasText(summaryTargetSurfaceRef)
                || StringUtils.hasText(detailTargetSurfaceRef)) {
            return "none";
        }
        if (Set.of("optional", "required").contains(normalizedMode)) {
            return normalizedMode;
        }
        if ("runtime_related_surface_detail".equals(normalizedKind)) {
            return "required";
        }
        if ("runtime_surface_disambiguation".equals(normalizedKind)) {
            return "optional";
        }
        if ("none".equals(normalizedMode)) {
            return "none";
        }
        return "none";
    }

    private List<String> appendReason(List<String> reasons, String reason) {
        List<String> result = new ArrayList<>();
        if (reasons != null) {
            result.addAll(reasons.stream().filter(StringUtils::hasText).toList());
        }
        if (StringUtils.hasText(reason) && !result.contains(reason)) {
            result.add(reason);
        }
        return result.isEmpty() ? List.of(reason) : List.copyOf(result);
    }

    private String runtimeRelatedSurfaceTargetResolutionPrompt(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext,
            ObjectNode resolution,
            RuntimeRelatedSurfaceConsultativeIntent initialIntent) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("initialKind", normalizeRuntimeRelatedSurfaceIntentKind(initialIntent.kind()));
        evidence.put("initialConfidence", initialIntent.confidence());
        ArrayNode candidates = evidence.putArray("acceptedCandidates");
        for (JsonNode candidate : resolution.path("candidates")) {
            if (!candidate.isObject() || !"accepted".equals(text(candidate, "status"))) {
                continue;
            }
            ObjectNode safe = candidates.addObject();
            copySafeScalar(candidate, safe, "surfaceRef");
            copySafeScalar(candidate, safe, "candidateRef");
            copySafeScalar(candidate, safe, "runtimeSurfaceInstanceRef");
            copySafeScalar(candidate, safe, "label");
            if (candidate.path("semanticAliases").isArray()) {
                safe.set("semanticAliases", textArray(candidate.path("semanticAliases"), 12));
            }
        }
        ObjectNode pendingDisambiguationContext =
                safePendingRuntimeRelatedSurfaceDisambiguationContext(
                        request,
                        resolution.path("candidates"),
                        runtimePageIds(request, runtimeContext));
        if (pendingDisambiguationContext != null) {
            evidence.set("pendingDisambiguationContext", pendingDisambiguationContext);
        }
        return """
                You are resolving a governed runtime-related surface target after an initial semantic intent classification.
                Do not answer the user. Do not route by keywords alone. Choose only among acceptedCandidates.
                Return a target only when the user goal semantically identifies exactly one accepted candidate.
                If the target is still ambiguous, return KIND: runtime_surface_disambiguation and TARGET_SURFACE_REF blank.
                If the user asks for focused detail/drill-down/inspection, choose runtime_related_surface_detail.
                If the user asks to list/show records/items from the target, choose runtime_related_surface_list.
                If the user asks to summarize/overview the target, choose runtime_related_surface_summary.
                If the user asks to compare/analyze two or more related surfaces, choose runtime_related_surface_compare
                and leave TARGET_SURFACE_REF blank. A missing comparison dimension must not downgrade compare to
                disambiguation; backend reconciliation will block it fail-closed before any read.
                Example: a goal equivalent to "compare participants and events from the selected record" is
                runtime_related_surface_compare with TARGET_SURFACE_REF blank, not runtime_surface_disambiguation.

                Return exactly:
                KIND: <runtime_related_surface_list | runtime_related_surface_summary | runtime_related_surface_detail | runtime_related_surface_compare | runtime_surface_disambiguation>
                CONFIDENCE: <0.00-1.00>
                TARGET_SURFACE_REF: <one accepted surfaceRef or blank>
                REASON: <short governed reason>

                Important: TARGET_SURFACE_REF must be the accepted candidate surfaceRef value, not candidateRef
                and not runtimeSurfaceInstanceRef.

                User goal:
                %s

                Governed target evidence, sanitized:
                %s
                """.formatted(value(request.userPrompt()), evidence.toPrettyString());
    }

    private Optional<RuntimeRelatedSurfaceTargetDecision> parseRuntimeRelatedSurfaceTargetDecision(String generated) {
        String text = value(generated);
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        String kind = "";
        String targetSurfaceRef = "";
        double confidence = 0.0d;
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("KIND:")) {
                kind = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("CONFIDENCE:")) {
                try {
                    confidence = Double.parseDouble(trimmed.substring(trimmed.indexOf(':') + 1).trim());
                } catch (NumberFormatException ignored) {
                    confidence = 0.0d;
                }
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("TARGET_SURFACE_REF:")) {
                targetSurfaceRef = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }
        }
        if (!StringUtils.hasText(kind) || confidence > 0.0d && confidence < 0.55d) {
            return Optional.empty();
        }
        return Optional.of(new RuntimeRelatedSurfaceTargetDecision(
                normalizeRuntimeRelatedSurfaceIntentKind(kind),
                safeIdentifier(targetSurfaceRef) ? targetSurfaceRef : "",
                confidence <= 0.0d ? 0.55d : Math.min(confidence, 1.0d)));
    }

    private String runtimeRelatedSurfaceIntentPrompt(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext) {
        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.set("availableSurfaces", textArray(runtimeContext.path("availableSurfaces"), 12));
        evidence.set("allowedOperations", textArray(runtimeContext.path("allowedOperations"), 12));
        ObjectNode pendingDisambiguationContext =
                safePendingRuntimeRelatedSurfaceDisambiguationContext(request, runtimeContext);
        if (pendingDisambiguationContext != null) {
            evidence.set("pendingDisambiguationContext", pendingDisambiguationContext);
        }
        ArrayNode components = evidence.putArray("components");
        for (JsonNode component : runtimeContext.path("components")) {
            ObjectNode item = components.addObject();
            item.set("identity", safeObject(component.path("identity"), Set.of(
                    "componentId", "componentType", "widgetKey")));
            item.set("refs", safeObject(component.path("refs"), Set.of(
                    "resourcePath", "resourceKey", "pageId", "runtimeSurfaceInstanceRef")));
            JsonNode selection = component.path("snapshot").path("selectionDigest");
            if (selection.isObject()) {
                ObjectNode safeSelection = item.putObject("selectionDigest");
                safeSelection.put("selectedCount", selection.path("selectedCount").asInt(0));
                copySafeScalar(selection, safeSelection, "idField");
            }
            item.set("activeSurfaceRefs", textArray(component.path("affordances").path("activeSurfaceRefs"), 20));
            item.set("activeActionRefs", textArray(component.path("affordances").path("activeActionRefs"), 20));
            item.set("schemaFieldRefs", textArray(component.path("snapshot").path("schemaFieldRefs"), 40));
            item.set("relationSurfaceRefs", safeRelationSurfaceRefs(
                    component.path("snapshot").path("relationSurfaceRefs"), 20));
        }
        return """
                You are classifying a consultative runtime-related surface intent for a governed platform.
                Do not answer the user. Do not choose by keywords alone. Use the user goal and governed runtime evidence.

                Return exactly:
                KIND: <one of runtime_related_surface_list | runtime_related_surface_availability | runtime_related_surface_summary | runtime_related_surface_detail | runtime_related_surface_compare | runtime_surface_disambiguation>
                CONFIDENCE: <0.00-1.00>
                TARGET_RESOLUTION_MODE: <none | optional | required>
                COMPARISON_DIMENSION_FIELD: <safe field ref or blank; only for compare>
                LIST_TARGET_SURFACE_REF: <one available surfaceRef or blank; only for targeted list>
                SUMMARY_TARGET_SURFACE_REF: <one available surfaceRef or blank; only for targeted summary>
                DETAIL_TARGET_SURFACE_REF: <one available surfaceRef or blank; only for detail>
                REASON: <short governed reason>

                Semantics:
                - list: user asks for records/items from one related surface.
                - availability: user asks what related data/surfaces can be consulted, without asking for records.
                - summary: user asks to summarize related evidence.
                - detail: user asks for detail of one governed related entity.
                - compare: user asks to compare/combine two or more related surfaces.
                - disambiguation: evidence is insufficient or ambiguous.
                - targetResolutionMode none: the first decision is already complete and the backend should not
                  spend a second target-resolution call.
                - targetResolutionMode optional: a single related-surface target might be inferable for list or
                  summary, but the first decision could not safely choose it.
                - targetResolutionMode required: the user is asking for a focused/detail/follow-up target and
                  the backend must resolve a single accepted surface before any read.

                Precedence:
                - If the user asks for synthesis, overview, conclusion, rollup, summary, or "resuma",
                  classify as runtime_related_surface_summary even when the prompt mentions concrete surfaces or records.
                  Use TARGET_RESOLUTION_MODE none for natural multi-surface summaries. Use optional only when
                  the user goal appears to ask for a single-surface summary but the target is not yet safe to emit.
                - If the user asks to compare, contrast, correlate, combine analytically, or "compare",
                  classify as runtime_related_surface_compare even when the prompt mentions concrete surfaces or records.
                  A missing, ambiguous, redacted, or non-governed comparison dimension must not change KIND;
                  keep runtime_related_surface_compare so backend reconciliation can reject the dimension fail-closed.
                  Example: a goal equivalent to "compare participants and events from the selected record" is
                  runtime_related_surface_compare with COMPARISON_DIMENSION_FIELD blank when no safe field is identified.
                  Use TARGET_RESOLUTION_MODE none for compare.
                - If the user asks for details, deeper inspection, or focused drill-down of exactly one related
                  surface, classify as runtime_related_surface_detail even when the target surface contains
                  records/items.
                - For compare, propose COMPARISON_DIMENSION_FIELD when the user semantically asks for a specific
                  safe fieldRef. Backend reconciliation will decide whether that field is declared, projected,
                  redacted, and allowed for all compared surfaces. Leave it blank only when no specific field
                  can be identified from the user goal and governed evidence.
                - For detail, propose DETAIL_TARGET_SURFACE_REF only when the user's goal semantically identifies
                  exactly one available related surface from the governed evidence. Leave it blank when ambiguous.
                  If the user explicitly mentions an available canonical surfaceRef, use that surfaceRef as the
                  semantic detail target; backend reconciliation must still validate it against current candidates.
                  Use TARGET_RESOLUTION_MODE required when detail/follow-up requires one target and
                  DETAIL_TARGET_SURFACE_REF is blank; use none when DETAIL_TARGET_SURFACE_REF is present.
                - pendingDisambiguationContext is previous-turn grounding only. It never authorizes a read by itself.
                  Use it only to understand the follow-up target semantically, then return LIST_TARGET_SURFACE_REF
                  for a targeted list, SUMMARY_TARGET_SURFACE_REF for a targeted summary, or
                  DETAIL_TARGET_SURFACE_REF for detail if exactly one option matches an available surfaceRef
                  in the current governed evidence.
                - Classify as runtime_related_surface_list only when the user wants the records/items themselves,
                  without asking for synthesis, comparison, detail, availability, or disambiguation.
                  Use TARGET_RESOLUTION_MODE none for natural multi-surface lists. Use optional only when the
                  prompt may refer to exactly one related surface but the first decision cannot safely choose it.
                - For targeted list, propose LIST_TARGET_SURFACE_REF only when the user's goal semantically
                  identifies exactly one available related surface from the governed evidence. Leave it blank
                  for multi-surface list.
                - For targeted summary, propose SUMMARY_TARGET_SURFACE_REF only when the user's goal semantically
                  identifies exactly one available related surface from the governed evidence. Leave it blank
                  for multi-surface summary.

                User goal:
                %s

                Governed runtime evidence, sanitized:
                %s
                """.formatted(value(request.userPrompt()), evidence.toPrettyString());
    }

    private ObjectNode safePendingRuntimeRelatedSurfaceDisambiguationContext(
            AgenticAuthoringTurnStreamRequest request,
            ObjectNode runtimeContext) {
        if (runtimeContext == null) {
            return null;
        }
        return safePendingRuntimeRelatedSurfaceDisambiguationContext(
                request,
                runtimeRelatedSurfaceResolution(runtimeContext, null).path("candidates"),
                runtimePageIds(request, runtimeContext));
    }

    private ObjectNode safePendingRuntimeRelatedSurfaceDisambiguationContext(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode acceptedCandidates,
            Set<String> currentPageIds) {
        JsonNode diagnostics = request == null ? null : request.diagnostics();
        JsonNode context = diagnostics == null
                ? null
                : diagnostics.path("runtimeRelatedSurfaceDisambiguationContext");
        JsonNode options = context == null ? null : context.path("options");
        if (context == null
                || !context.isObject()
                || options == null
                || !options.isArray()
                || options.isEmpty()
                || !"praxis-runtime-related-surface-disambiguation-context.v1".equals(text(context, "schemaVersion"))
                || !"grounding_only".equals(text(context, "authority"))
                || context.path("rawRuntimeValuesCopied").asBoolean(true)) {
            return null;
        }
        String sessionId = text(context, "sessionId");
        String requestSessionId = request == null ? "" : value(request.sessionId());
        String sourceTurnId = text(context, "sourceTurnId");
        String currentTurnId = request == null ? "" : value(request.clientTurnId());
        String pageId = text(context, "pageId");
        String capturedAt = text(context, "capturedAt");
        long ttlMs = context.path("ttlMs").asLong(-1L);
        if (!StringUtils.hasText(sessionId)
                || !StringUtils.hasText(requestSessionId)
                || !sessionId.equals(requestSessionId)
                || !StringUtils.hasText(sourceTurnId)
                || !StringUtils.hasText(currentTurnId)
                || sourceTurnId.equals(currentTurnId)
                || !StringUtils.hasText(pageId)
                || currentPageIds == null
                || !currentPageIds.contains(pageId)
                || !runtimeDisambiguationContextFresh(capturedAt, ttlMs)) {
            return null;
        }
        ObjectNode safe = objectMapper.createObjectNode();
        safe.put("schemaVersion", "praxis-runtime-related-surface-disambiguation-context.v1");
        safe.put("source", "previous-turn-diagnostics");
        safe.put("authority", "grounding_only");
        safe.put("sessionId", sessionId);
        safe.put("sourceTurnId", sourceTurnId);
        safe.put("pageId", pageId);
        safe.put("capturedAt", capturedAt);
        safe.put("ttlMs", ttlMs);
        ArrayNode safeOptions = safe.putArray("options");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode option : options) {
            String surfaceRef = text(option, "surfaceRef");
            String optionRef = text(option, "optionRef");
            String candidateRef = text(option, "candidateRef");
            if (!safeIdentifier(surfaceRef)
                    || !("runtime-surface-option:" + surfaceRef).equals(optionRef)
                    || !StringUtils.hasText(candidateRef)
                    || !candidateRef.startsWith("runtime-surface-candidate:")
                    || !acceptedRuntimeRelatedSurfaceCandidate(acceptedCandidates, surfaceRef, candidateRef)
                    || !seen.add(surfaceRef)) {
                continue;
            }
            ObjectNode safeOption = safeOptions.addObject();
            safeOption.put("surfaceRef", surfaceRef);
            safeOption.put("optionRef", optionRef);
            safeOption.put("candidateRef", candidateRef);
            copySafeScalar(option, safeOption, "label");
            if (option.path("semanticAliases").isArray()) {
                safeOption.set("semanticAliases", textArray(option.path("semanticAliases"), 12));
            }
        }
        if (safeOptions.size() < 2) {
            return null;
        }
        safe.put("optionCount", safeOptions.size());
        safe.put("rawRuntimeValuesCopied", false);
        return safe;
    }

    private boolean runtimeDisambiguationContextFresh(String capturedAt, long ttlMs) {
        if (!StringUtils.hasText(capturedAt) || ttlMs <= 0L || ttlMs > MAX_RUNTIME_DISAMBIGUATION_CONTEXT_TTL_MS) {
            return false;
        }
        try {
            return !Instant.parse(capturedAt).plusMillis(ttlMs).isBefore(Instant.now());
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private boolean acceptedRuntimeRelatedSurfaceCandidate(JsonNode acceptedCandidates, String surfaceRef, String candidateRef) {
        if (acceptedCandidates == null || !acceptedCandidates.isArray()) {
            return false;
        }
        for (JsonNode candidate : acceptedCandidates) {
            if ("accepted".equals(text(candidate, "status"))
                    && surfaceRef.equals(text(candidate, "surfaceRef"))
                    && candidateRef.equals(text(candidate, "candidateRef"))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> runtimePageIds(AgenticAuthoringTurnStreamRequest request, ObjectNode runtimeContext) {
        Set<String> pageIds = new LinkedHashSet<>();
        JsonNode currentPage = request == null ? null : request.currentPage();
        if (currentPage != null && currentPage.isObject()) {
            for (String key : List.of("pageId", "id", "pageKey", "key")) {
                String pageId = text(currentPage, key);
                if (StringUtils.hasText(pageId)) {
                    pageIds.add(pageId);
                }
            }
        }
        if (runtimeContext == null) {
            return pageIds;
        }
        for (JsonNode component : runtimeContext.path("components")) {
            String pageId = text(component.path("refs"), "pageId");
            if (StringUtils.hasText(pageId)) {
                pageIds.add(pageId);
            }
        }
        return pageIds;
    }

    private Optional<RuntimeRelatedSurfaceConsultativeIntent> parseRuntimeRelatedSurfaceIntent(String generated) {
        String text = value(generated);
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        String kind = "";
        String comparisonDimensionFieldRef = "";
        String listTargetSurfaceRef = "";
        String summaryTargetSurfaceRef = "";
        String detailTargetSurfaceRef = "";
        String targetResolutionMode = "";
        double confidence = 0.0d;
        List<String> reasons = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("KIND:")) {
                kind = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("CONFIDENCE:")) {
                try {
                    confidence = Double.parseDouble(trimmed.substring(trimmed.indexOf(':') + 1).trim());
                } catch (NumberFormatException ignored) {
                    confidence = 0.0d;
                }
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("REASON:")) {
                String reason = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                if (StringUtils.hasText(reason)) {
                    reasons.add(reason);
                }
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("COMPARISON_DIMENSION_FIELD:")) {
                comparisonDimensionFieldRef = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("TARGET_RESOLUTION_MODE:")) {
                targetResolutionMode = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("LIST_TARGET_SURFACE_REF:")) {
                listTargetSurfaceRef = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("SUMMARY_TARGET_SURFACE_REF:")) {
                summaryTargetSurfaceRef = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("DETAIL_TARGET_SURFACE_REF:")) {
                detailTargetSurfaceRef = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }
        }
        if (!StringUtils.hasText(kind)) {
            return Optional.empty();
        }
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(kind);
        if (confidence > 0.0d && confidence < 0.55d) {
            normalized = "runtime_surface_disambiguation";
        }
        return Optional.of(new RuntimeRelatedSurfaceConsultativeIntent(
                normalized,
                "consultativeIntent:" + normalized,
                confidence <= 0.0d ? 0.55d : Math.min(confidence, 1.0d),
                reasons.isEmpty() ? List.of("llm-runtime-related-surface-intent") : reasons,
                false,
                comparisonDimensionFieldRef,
                listTargetSurfaceRef,
                summaryTargetSurfaceRef,
	                detailTargetSurfaceRef,
	                "",
	                "",
	                false,
	                normalizeRuntimeRelatedSurfaceTargetResolutionMode(
                            targetResolutionMode,
                            normalized,
	                            listTargetSurfaceRef,
	                            summaryTargetSurfaceRef,
	                            detailTargetSurfaceRef),
		                null,
		                null));
	    }

    private RuntimeRelatedSurfaceDisambiguationSelection runtimeRelatedSurfaceDisambiguationSelection(
            AgenticAuthoringSemanticDecision activeDecision) {
        JsonNode selection = activeDecision == null || activeDecision.constraints() == null
                ? null
                : activeDecision.constraints().path("runtimeRelatedSurfaceDisambiguationSelection");
        if (selection == null || !selection.isObject()) {
            return RuntimeRelatedSurfaceDisambiguationSelection.empty();
        }
        String optionRef = text(selection, "optionRef");
        String candidateRef = text(selection, "candidateRef");
        String surfaceRef = text(selection, "surfaceRef");
        if (!StringUtils.hasText(surfaceRef) && optionRef.startsWith("runtime-surface-option:")) {
            surfaceRef = optionRef.substring("runtime-surface-option:".length()).trim();
        }
        if (!safeIdentifier(surfaceRef)) {
            surfaceRef = "";
        }
        if (StringUtils.hasText(optionRef) && !optionRef.equals("runtime-surface-option:" + surfaceRef)) {
            optionRef = "__invalid__";
        }
        if (StringUtils.hasText(candidateRef) && !candidateRef.startsWith("runtime-surface-candidate:")) {
            candidateRef = "__invalid__";
        }
        return new RuntimeRelatedSurfaceDisambiguationSelection(surfaceRef, candidateRef, optionRef);
    }

    private String normalizeRuntimeRelatedSurfaceIntentKind(String kind) {
        String normalized = value(kind).toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "runtime_related_surface_availability",
                    "runtime_surface_availability",
                    "related_surface_availability" -> "runtime_related_surface_availability";
            case "runtime_related_surface_compare",
                    "runtime_surface_compare",
                    "related_surface_compare" -> "runtime_related_surface_compare";
            case "runtime_related_surface_summary",
                    "runtime_surface_summary",
                    "related_surface_summary" -> "runtime_related_surface_summary";
            case "runtime_related_surface_detail",
                    "runtime_surface_detail",
                    "related_surface_detail" -> "runtime_related_surface_detail";
            case "runtime_surface_disambiguation",
                    "runtime_related_surface_disambiguation" -> "runtime_surface_disambiguation";
            case "runtime_related_surface_read",
                    "runtime_related_surface_list",
                    "runtime_surface_list",
                    "related_surface_list" -> "runtime_related_surface_list";
            default -> "runtime_related_surface_list";
        };
    }

    private boolean runtimeRelatedSurfaceIntentBlocksRead(String intentKind) {
        return runtimeRelatedSurfaceIntentBlocksRead(intentKind, null);
    }

    private boolean runtimeRelatedSurfaceIntentBlocksRead(String intentKind, ObjectNode resolution) {
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        if ("runtime_related_surface_list".equals(normalized)
                && resolution != null
                && StringUtils.hasText(text(resolution.path("listTargetDiagnostics"), "failureCode"))) {
            return true;
        }
        if ("runtime_related_surface_summary".equals(normalized)
                && resolution != null
                && StringUtils.hasText(text(resolution.path("summaryTargetDiagnostics"), "failureCode"))) {
            return true;
        }
        if ("runtime_related_surface_summary".equals(normalized) && runtimeToolPlannerReadonlyExecutionEnabled()) {
            return false;
        }
        if ("runtime_related_surface_detail".equals(normalized)
                && runtimeToolPlannerReadonlyExecutionEnabled()
                && acceptedRuntimeRelatedSurfaceDetailTarget(resolution)) {
            return false;
        }
        if ("runtime_related_surface_compare".equals(normalized)
                && runtimeToolPlannerReadonlyExecutionEnabled()
                && acceptedRuntimeRelatedSurfaceComparisonDimension(resolution)) {
            return false;
        }
        return Set.of(
                "runtime_related_surface_summary",
                "runtime_related_surface_detail",
                "runtime_related_surface_compare",
                "runtime_surface_disambiguation").contains(normalized);
    }

    private String runtimeRelatedSurfaceBlockedFailureCode(String intentKind) {
        return runtimeRelatedSurfaceBlockedFailureCode(intentKind, null);
    }

	    private String runtimeRelatedSurfaceBlockedFailureCode(String intentKind, ObjectNode resolution) {
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        if ("runtime_related_surface_list".equals(normalized) && resolution != null) {
            String listFailureCode = text(resolution.path("listTargetDiagnostics"), "failureCode");
            if (StringUtils.hasText(listFailureCode)) {
                return listFailureCode;
            }
        }
        if ("runtime_related_surface_summary".equals(normalized) && resolution != null) {
            String summaryFailureCode = text(resolution.path("summaryTargetDiagnostics"), "failureCode");
            if (StringUtils.hasText(summaryFailureCode)) {
                return summaryFailureCode;
            }
        }
        if ("runtime_related_surface_compare".equals(normalized)) {
            return "runtime-related-surface-compare-not-enabled";
        }
        if ("runtime_related_surface_detail".equals(normalized)) {
            String detailFailureCode = text(resolution.path("detailTargetDiagnostics"), "failureCode");
            if (StringUtils.hasText(detailFailureCode)) {
                return detailFailureCode;
            }
            return acceptedRuntimeRelatedSurfaceCandidateCount(resolution) > 1
                    ? "runtime-related-surface-detail-target-ambiguous"
                    : "runtime-related-surface-detail-target-required";
        }
        return "runtime-related-surface-intent-not-supported";
    }

    private void attachRuntimeRelatedSurfaceTargetRefinementDiagnostics(
            ObjectNode resolution,
            RuntimeRelatedSurfaceConsultativeIntent consultativeIntent) {
        if (resolution == null
                || consultativeIntent == null
                || consultativeIntent.targetRefinementDiagnostics() == null
                || consultativeIntent.targetRefinementDiagnostics().isMissingNode()
                || !consultativeIntent.targetRefinementDiagnostics().isObject()) {
            return;
        }
        resolution.set("targetRefinementDiagnostics", consultativeIntent.targetRefinementDiagnostics().deepCopy());
    }

    private void attachRuntimeRelatedSurfaceTargetCandidateResolutionDiagnostics(
            ObjectNode resolution,
            RuntimeRelatedSurfaceConsultativeIntent consultativeIntent) {
        if (resolution == null
                || consultativeIntent == null
                || consultativeIntent.targetCandidateResolutionDiagnostics() == null
                || consultativeIntent.targetCandidateResolutionDiagnostics().isMissingNode()
                || !consultativeIntent.targetCandidateResolutionDiagnostics().isObject()) {
            return;
        }
        resolution.set(
                "targetCandidateResolution",
                consultativeIntent.targetCandidateResolutionDiagnostics().deepCopy());
    }

    private void attachRuntimeRelatedSurfaceListTarget(
            ObjectNode resolution,
            RuntimeRelatedSurfaceConsultativeIntent consultativeIntent) {
        if (resolution == null
                || consultativeIntent == null
                || !"runtime_related_surface_list".equals(normalizeRuntimeRelatedSurfaceIntentKind(consultativeIntent.kind()))) {
            return;
        }
        String targetSurfaceRef = consultativeIntent.listTargetSurfaceRef();
        if (!StringUtils.hasText(targetSurfaceRef)) {
            return;
        }
        JsonNode candidate = acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(resolution, targetSurfaceRef);
        if (candidate != null && candidate.isObject()) {
            String optionRef = "runtime-surface-option:" + targetSurfaceRef;
            if (StringUtils.hasText(consultativeIntent.detailTargetOptionRef())
                    && !consultativeIntent.detailTargetOptionRef().equals(optionRef)) {
                ObjectNode diagnostics = resolution.withObject("/listTargetDiagnostics");
                diagnostics.put("status", "rejected");
                diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
                diagnostics.put("requestedOptionRef", consultativeIntent.detailTargetOptionRef());
                diagnostics.put("failureCode", "runtime-related-surface-list-target-not-reconciled");
                return;
            }
            String candidateRef = firstNonBlank(
                    text(candidate, "candidateRef"),
                    "runtime-surface-candidate:" + text(candidate, "surfaceRef"));
            if (StringUtils.hasText(consultativeIntent.detailTargetCandidateRef())
                    && !consultativeIntent.detailTargetCandidateRef().equals(candidateRef)) {
                ObjectNode diagnostics = resolution.withObject("/listTargetDiagnostics");
                diagnostics.put("status", "rejected");
                diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
                diagnostics.put("requestedCandidateRef", consultativeIntent.detailTargetCandidateRef());
                diagnostics.put("failureCode", "runtime-related-surface-list-target-not-reconciled");
                return;
            }
            String source = StringUtils.hasText(consultativeIntent.detailTargetOptionRef())
                    ? "runtime_related_surface_disambiguation_selection"
                    : "semantic_decision";
            acceptRuntimeRelatedSurfaceListTarget(resolution, candidate, source);
            if (StringUtils.hasText(consultativeIntent.detailTargetOptionRef())) {
                resolution.withObject("/listTarget")
                        .put("optionRef", consultativeIntent.detailTargetOptionRef());
            }
            return;
        }
        ObjectNode diagnostics = resolution.withObject("/listTargetDiagnostics");
        diagnostics.put("status", "rejected");
        diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
        diagnostics.put("failureCode", "runtime-related-surface-list-target-not-reconciled");
    }

    private void acceptRuntimeRelatedSurfaceListTarget(ObjectNode resolution, JsonNode candidate, String source) {
        if (resolution == null || candidate == null || !candidate.isObject()) {
            return;
        }
        String surfaceRef = text(candidate, "surfaceRef");
        if (!StringUtils.hasText(surfaceRef)) {
            return;
        }
        String candidateRef = firstNonBlank(text(candidate, "candidateRef"), "runtime-surface-candidate:" + surfaceRef);
        ObjectNode target = resolution.putObject("listTarget");
        target.put("surfaceRef", surfaceRef);
        target.put("candidateRef", candidateRef);
        target.put("source", firstNonBlank(source, "semantic_decision"));
        target.put("provenance", "backend_reconciled");
        resolution.put("selectedCandidateRef", surfaceRef);
        resolution.put("selectedCandidateEvidenceRef", candidateRef);
    }

    private void attachRuntimeRelatedSurfaceSummaryTarget(
            ObjectNode resolution,
            RuntimeRelatedSurfaceConsultativeIntent consultativeIntent) {
        if (resolution == null
                || consultativeIntent == null
                || !"runtime_related_surface_summary".equals(normalizeRuntimeRelatedSurfaceIntentKind(consultativeIntent.kind()))) {
            return;
        }
        String targetSurfaceRef = consultativeIntent.summaryTargetSurfaceRef();
        if (!StringUtils.hasText(targetSurfaceRef)) {
            return;
        }
        JsonNode candidate = acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(resolution, targetSurfaceRef);
        if (candidate != null && candidate.isObject()) {
            String optionRef = "runtime-surface-option:" + targetSurfaceRef;
            if (StringUtils.hasText(consultativeIntent.detailTargetOptionRef())
                    && !consultativeIntent.detailTargetOptionRef().equals(optionRef)) {
                ObjectNode diagnostics = resolution.withObject("/summaryTargetDiagnostics");
                diagnostics.put("status", "rejected");
                diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
                diagnostics.put("requestedOptionRef", consultativeIntent.detailTargetOptionRef());
                diagnostics.put("failureCode", "runtime-related-surface-summary-target-not-reconciled");
                return;
            }
            String candidateRef = firstNonBlank(
                    text(candidate, "candidateRef"),
                    "runtime-surface-candidate:" + text(candidate, "surfaceRef"));
            if (StringUtils.hasText(consultativeIntent.detailTargetCandidateRef())
                    && !consultativeIntent.detailTargetCandidateRef().equals(candidateRef)) {
                ObjectNode diagnostics = resolution.withObject("/summaryTargetDiagnostics");
                diagnostics.put("status", "rejected");
                diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
                diagnostics.put("requestedCandidateRef", consultativeIntent.detailTargetCandidateRef());
                diagnostics.put("failureCode", "runtime-related-surface-summary-target-not-reconciled");
                return;
            }
            String source = StringUtils.hasText(consultativeIntent.detailTargetOptionRef())
                    ? "runtime_related_surface_disambiguation_selection"
                    : "semantic_decision";
            acceptRuntimeRelatedSurfaceSummaryTarget(resolution, candidate, source);
            if (StringUtils.hasText(consultativeIntent.detailTargetOptionRef())) {
                resolution.withObject("/summaryTarget")
                        .put("optionRef", consultativeIntent.detailTargetOptionRef());
            }
            return;
        }
        ObjectNode diagnostics = resolution.withObject("/summaryTargetDiagnostics");
        diagnostics.put("status", "rejected");
        diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
        diagnostics.put("failureCode", "runtime-related-surface-summary-target-not-reconciled");
    }

    private void acceptRuntimeRelatedSurfaceSummaryTarget(ObjectNode resolution, JsonNode candidate, String source) {
        if (resolution == null || candidate == null || !candidate.isObject()) {
            return;
        }
        String surfaceRef = text(candidate, "surfaceRef");
        if (!StringUtils.hasText(surfaceRef)) {
            return;
        }
        String candidateRef = firstNonBlank(text(candidate, "candidateRef"), "runtime-surface-candidate:" + surfaceRef);
        ObjectNode target = resolution.putObject("summaryTarget");
        target.put("surfaceRef", surfaceRef);
        target.put("candidateRef", candidateRef);
        target.put("source", firstNonBlank(source, "semantic_decision"));
        target.put("provenance", "backend_reconciled");
        resolution.put("selectedCandidateRef", surfaceRef);
        resolution.put("selectedCandidateEvidenceRef", candidateRef);
    }

    private void attachRuntimeRelatedSurfaceDetailTarget(
            ObjectNode resolution,
            RuntimeRelatedSurfaceConsultativeIntent consultativeIntent) {
        if (resolution == null
                || consultativeIntent == null
                || !"runtime_related_surface_detail".equals(normalizeRuntimeRelatedSurfaceIntentKind(consultativeIntent.kind()))) {
            return;
        }
        int acceptedCount = acceptedRuntimeRelatedSurfaceCandidateCount(resolution);
        String targetSurfaceRef = consultativeIntent.detailTargetSurfaceRef();
        if (!StringUtils.hasText(targetSurfaceRef)) {
            if (acceptedCount == 1) {
                JsonNode candidate = firstAcceptedRuntimeRelatedSurfaceCandidate(resolution);
                acceptRuntimeRelatedSurfaceDetailTarget(resolution, candidate, "single_accepted_candidate");
                return;
            }
            ObjectNode diagnostics = resolution.withObject("/detailTargetDiagnostics");
            diagnostics.put("status", acceptedCount > 1 ? "ambiguous" : "missing");
            diagnostics.put("failureCode", acceptedCount > 1
                    ? "runtime-related-surface-detail-target-ambiguous"
                    : "runtime-related-surface-detail-target-required");
            return;
        }
        JsonNode candidate = acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(resolution, targetSurfaceRef);
        if (candidate != null && candidate.isObject()) {
            String optionRef = "runtime-surface-option:" + targetSurfaceRef;
            if (StringUtils.hasText(consultativeIntent.detailTargetOptionRef())
                    && !consultativeIntent.detailTargetOptionRef().equals(optionRef)) {
                ObjectNode diagnostics = resolution.withObject("/detailTargetDiagnostics");
                diagnostics.put("status", "rejected");
                diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
                diagnostics.put("requestedOptionRef", consultativeIntent.detailTargetOptionRef());
                diagnostics.put("failureCode", "runtime-related-surface-detail-target-not-reconciled");
                return;
            }
            String candidateRef = firstNonBlank(
                    text(candidate, "candidateRef"),
                    "runtime-surface-candidate:" + text(candidate, "surfaceRef"));
            if (StringUtils.hasText(consultativeIntent.detailTargetCandidateRef())
                    && !consultativeIntent.detailTargetCandidateRef().equals(candidateRef)) {
                ObjectNode diagnostics = resolution.withObject("/detailTargetDiagnostics");
                diagnostics.put("status", "rejected");
                diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
                diagnostics.put("requestedCandidateRef", consultativeIntent.detailTargetCandidateRef());
                diagnostics.put("failureCode", "runtime-related-surface-detail-target-not-reconciled");
                return;
            }
            String source = StringUtils.hasText(consultativeIntent.detailTargetOptionRef())
                    ? "runtime_related_surface_disambiguation_selection"
                    : "semantic_decision";
            acceptRuntimeRelatedSurfaceDetailTarget(resolution, candidate, source);
            if (StringUtils.hasText(consultativeIntent.detailTargetOptionRef())) {
                resolution.withObject("/detailTarget")
                        .put("optionRef", consultativeIntent.detailTargetOptionRef());
            }
            return;
        }
        ObjectNode diagnostics = resolution.withObject("/detailTargetDiagnostics");
        diagnostics.put("status", "rejected");
        diagnostics.put("requestedSurfaceRef", targetSurfaceRef);
        diagnostics.put("failureCode", "runtime-related-surface-detail-target-not-reconciled");
    }

    private void acceptRuntimeRelatedSurfaceDetailTarget(ObjectNode resolution, JsonNode candidate, String source) {
        if (resolution == null || candidate == null || !candidate.isObject()) {
            return;
        }
        String surfaceRef = text(candidate, "surfaceRef");
        if (!StringUtils.hasText(surfaceRef)) {
            return;
        }
        String candidateRef = firstNonBlank(text(candidate, "candidateRef"), "runtime-surface-candidate:" + surfaceRef);
        ObjectNode target = resolution.putObject("detailTarget");
        target.put("surfaceRef", surfaceRef);
        target.put("candidateRef", candidateRef);
        target.put("source", firstNonBlank(source, "semantic_decision"));
        target.put("provenance", "backend_reconciled");
        resolution.put("selectedCandidateRef", surfaceRef);
        resolution.put("selectedCandidateEvidenceRef", candidateRef);
    }

    private void attachRuntimeRelatedSurfaceComparisonDimension(
            ObjectNode resolution,
            ObjectNode runtimeContext,
            RuntimeRelatedSurfaceConsultativeIntent consultativeIntent) {
        if (resolution == null
                || runtimeContext == null
                || consultativeIntent == null
                || !"runtime_related_surface_compare".equals(normalizeRuntimeRelatedSurfaceIntentKind(consultativeIntent.kind()))) {
            return;
        }
        String fieldRef = consultativeIntent.comparisonDimensionFieldRef();
        if (!StringUtils.hasText(fieldRef)) {
            ObjectNode inferred = backendInferredRuntimeRelatedSurfaceComparisonDimension(resolution, runtimeContext);
            ObjectNode accepted = acceptedRuntimeRelatedSurfaceComparisonDimension((JsonNode) inferred);
            if (accepted != null) {
                resolution.set("comparisonDimension", accepted);
                return;
            }
            if (resolution.path("comparisonDimensionDiagnostics").isObject()) {
                return;
            }
            ObjectNode diagnostics = resolution.withObject("/comparisonDimensionDiagnostics");
            diagnostics.put("status", "missing");
            diagnostics.put("failureCode", "runtime-related-surface-compare-dimension-required");
            return;
        }
        ObjectNode candidate = backendReconciledRuntimeRelatedSurfaceComparisonDimension(
                resolution,
                runtimeContext,
                fieldRef,
                consultativeIntent.requiresTemporalComparisonDimension());
        ObjectNode accepted = acceptedRuntimeRelatedSurfaceComparisonDimension((JsonNode) candidate);
        if (accepted != null) {
            resolution.set("comparisonDimension", accepted);
            return;
        }
        if (resolution.path("comparisonDimensionDiagnostics").isObject()) {
            return;
        }
        ObjectNode diagnostics = resolution.withObject("/comparisonDimensionDiagnostics");
        diagnostics.put("status", "rejected");
        diagnostics.put("failureCode", "runtime-related-surface-compare-dimension-not-accepted");
    }

    private ObjectNode backendReconciledRuntimeRelatedSurfaceComparisonDimension(
            ObjectNode resolution,
            ObjectNode runtimeContext,
            String fieldRef,
            boolean requiresTemporalType) {
        if (!safeIdentifier(fieldRef)) {
            return null;
        }
        List<String> acceptedSurfaces = new ArrayList<>();
        List<String> missingSurfaces = new ArrayList<>();
        List<String> redactedSurfaces = new ArrayList<>();
        List<String> fieldTypes = new ArrayList<>();
        List<String> temporalTypeMissingSurfaces = new ArrayList<>();
        for (JsonNode candidate : resolution.path("candidates")) {
            if (!"accepted".equals(text(candidate, "status"))) {
                continue;
            }
            String surfaceRef = text(candidate, "surfaceRef");
            if (!StringUtils.hasText(surfaceRef)) {
                continue;
            }
            acceptedSurfaces.add(surfaceRef);
            if (!runtimeSurfaceDeclaresSchemaField(runtimeContext, surfaceRef, fieldRef)) {
                missingSurfaces.add(surfaceRef);
            } else if (runtimeSurfaceRedactsSchemaField(runtimeContext, surfaceRef, fieldRef)) {
                redactedSurfaces.add(surfaceRef);
            }
            String fieldType = runtimeSurfaceSchemaFieldType(runtimeContext, surfaceRef, fieldRef);
            if (StringUtils.hasText(fieldType)) {
                fieldTypes.add(fieldType);
            } else {
                temporalTypeMissingSurfaces.add(surfaceRef);
            }
        }
        String temporalFieldType = reconciledTemporalFieldType(fieldTypes, temporalTypeMissingSurfaces, acceptedSurfaces);
        if (acceptedSurfaces.size() < 2 || !missingSurfaces.isEmpty() || !redactedSurfaces.isEmpty()
                || temporalFieldType == null
                || (requiresTemporalType && !StringUtils.hasText(temporalFieldType))) {
            ObjectNode diagnostics = resolution.withObject("/comparisonDimensionDiagnostics");
            diagnostics.put("status", "rejected");
            diagnostics.put("fieldRef", fieldRef);
            diagnostics.set("acceptedSurfaceRefs", textArray(acceptedSurfaces, 8));
            diagnostics.set("missingSurfaceRefs", textArray(missingSurfaces, 8));
            diagnostics.set("redactedSurfaceRefs", textArray(redactedSurfaces, 8));
            diagnostics.set("fieldTypes", textArray(fieldTypes, 8));
            diagnostics.set("temporalTypeMissingSurfaceRefs", textArray(temporalTypeMissingSurfaces, 8));
            diagnostics.put("failureCode", acceptedSurfaces.size() < 2
                    ? "runtime-related-surface-compare-dimension-requires-two-surfaces"
                    : !redactedSurfaces.isEmpty()
                    ? "runtime-related-surface-compare-dimension-field-redacted"
                    : temporalFieldType == null && !fieldTypes.isEmpty()
                    ? "runtime-related-surface-compare-dimension-temporal-type-not-reconciled"
                    : requiresTemporalType && !StringUtils.hasText(temporalFieldType)
                    ? "runtime-related-surface-compare-dimension-temporal-type-not-reconciled"
                    : "runtime-related-surface-compare-dimension-field-not-declared");
            return null;
        }
        ObjectNode dimension = objectMapper.createObjectNode();
        dimension.put("fieldRef", fieldRef);
        dimension.put("source", "semantic_decision");
        dimension.put("provenance", "backend_reconciled");
        if (StringUtils.hasText(temporalFieldType)) {
            dimension.put("fieldType", temporalFieldType);
        }
        applyRuntimeRelatedSurfaceComparisonDimensionPolicy(dimension);
        dimension.set("surfaceRefs", textArray(acceptedSurfaces, 8));
        return dimension;
    }

    private ObjectNode backendInferredRuntimeRelatedSurfaceComparisonDimension(
            ObjectNode resolution,
            ObjectNode runtimeContext) {
        List<String> acceptedSurfaces = new ArrayList<>();
        List<String> commonFields = new ArrayList<>();
        boolean initialized = false;
        for (JsonNode candidate : resolution.path("candidates")) {
            if (!"accepted".equals(text(candidate, "status"))) {
                continue;
            }
            String surfaceRef = text(candidate, "surfaceRef");
            if (!StringUtils.hasText(surfaceRef)) {
                continue;
            }
            acceptedSurfaces.add(surfaceRef);
            List<String> fields = runtimeSurfaceComparableSchemaFieldRefs(runtimeContext, surfaceRef);
            if (!initialized) {
                commonFields.addAll(fields);
                initialized = true;
            } else {
                commonFields.retainAll(fields);
            }
        }
        List<String> safeCommonFields = commonFields.stream()
                .filter(this::safeIdentifier)
                .distinct()
                .toList();
        if (acceptedSurfaces.size() < 2 || safeCommonFields.size() != 1) {
            ObjectNode diagnostics = resolution.withObject("/comparisonDimensionDiagnostics");
            diagnostics.put("status", "missing");
            diagnostics.set("acceptedSurfaceRefs", textArray(acceptedSurfaces, 8));
            diagnostics.set("commonFieldRefs", textArray(safeCommonFields, 8));
            diagnostics.put("failureCode", acceptedSurfaces.size() < 2
                    ? "runtime-related-surface-compare-dimension-requires-two-surfaces"
                    : safeCommonFields.isEmpty()
                    ? "runtime-related-surface-compare-dimension-required"
                    : "runtime-related-surface-compare-dimension-ambiguous");
            return null;
        }
        ObjectNode dimension = objectMapper.createObjectNode();
        dimension.put("fieldRef", safeCommonFields.get(0));
        dimension.put("source", "backend_contract");
        dimension.put("provenance", "backend_reconciled");
        String temporalFieldType = runtimeSurfaceReconciledTemporalSchemaFieldType(
                runtimeContext,
                acceptedSurfaces,
                safeCommonFields.get(0));
        if (StringUtils.hasText(temporalFieldType)) {
            dimension.put("fieldType", temporalFieldType);
        }
        applyRuntimeRelatedSurfaceComparisonDimensionPolicy(dimension);
        dimension.set("surfaceRefs", textArray(acceptedSurfaces, 8));
        return dimension;
    }

    private String runtimeSurfaceReconciledTemporalSchemaFieldType(
            ObjectNode runtimeContext,
            List<String> surfaceRefs,
            String fieldRef) {
        List<String> fieldTypes = new ArrayList<>();
        List<String> missingTypeSurfaceRefs = new ArrayList<>();
        for (String surfaceRef : surfaceRefs) {
            String fieldType = runtimeSurfaceSchemaFieldType(runtimeContext, surfaceRef, fieldRef);
            if (StringUtils.hasText(fieldType)) {
                fieldTypes.add(fieldType);
            } else {
                missingTypeSurfaceRefs.add(surfaceRef);
            }
        }
        String reconciled = reconciledTemporalFieldType(fieldTypes, missingTypeSurfaceRefs, surfaceRefs);
        return reconciled == null ? "" : reconciled;
    }

    private String reconciledTemporalFieldType(
            List<String> fieldTypes,
            List<String> missingTypeSurfaceRefs,
            List<String> acceptedSurfaceRefs) {
        List<String> temporalTypes = fieldTypes.stream()
                .map(this::normalizedTemporalComparisonType)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (temporalTypes.isEmpty() && fieldTypes.isEmpty()) {
            return "";
        }
        long temporalTypedSurfaceCount = fieldTypes.stream()
                .map(this::normalizedTemporalComparisonType)
                .filter(StringUtils::hasText)
                .count();
        if (temporalTypes.size() == 1
                && temporalTypedSurfaceCount == fieldTypes.size()
                && missingTypeSurfaceRefs.isEmpty()
                && fieldTypes.size() >= acceptedSurfaceRefs.size()) {
            return temporalTypes.get(0);
        }
        if (temporalTypes.isEmpty()) {
            return "";
        }
        return null;
    }

    private void applyRuntimeRelatedSurfaceComparisonDimensionPolicy(ObjectNode dimension) {
        dimension.putArray("allowedFactKinds")
                .add("surface_record_count")
                .add("categorical_distribution")
                .add("projection_redaction_coverage")
                .add("record_count_delta")
                .add("category_overlap")
                .add("record_presence_matrix");
        if (temporalComparisonDimension(dimension)) {
            dimension.withArray("allowedFactKinds").add("temporal_coverage");
        }
        dimension.put("requiresBothSurfaces", true);
        dimension.put("redactionRequired", true);
    }

    private boolean runtimeSurfaceDeclaresSchemaField(ObjectNode runtimeContext, String surfaceRef, String fieldRef) {
        return runtimeSurfaceSchemaFieldRefs(runtimeContext, surfaceRef).contains(fieldRef);
    }

    private boolean runtimeSurfaceRedactsSchemaField(ObjectNode runtimeContext, String surfaceRef, String fieldRef) {
        return runtimeSurfaceRedactedFieldRefs(runtimeContext, surfaceRef).contains(fieldRef);
    }

    private String runtimeSurfaceSchemaFieldType(ObjectNode runtimeContext, String surfaceRef, String fieldRef) {
        if (runtimeContext == null || !StringUtils.hasText(surfaceRef) || !safeIdentifier(fieldRef)) {
            return "";
        }
        JsonNode relation = runtimeRelationSurfaceRef(runtimeContext, surfaceRef);
        String targetWidget = firstNonBlank(
                text(relation, "targetWidget"),
                text(relation.path("target"), "widget"));
        String targetResourcePath = firstNonBlank(
                text(relation, "targetResourcePath"),
                text(relation.path("target"), "resourcePath"));
        String runtimeSurfaceInstanceRef = runtimeSurfaceInstanceRef(relation);
        if (!StringUtils.hasText(runtimeSurfaceInstanceRef)
                && !StringUtils.hasText(targetWidget)
                && runtimeSurfaceResourcePathAmbiguous(runtimeContext, targetResourcePath)) {
            return "";
        }
        for (JsonNode component : runtimeContext.path("components")) {
            if (StringUtils.hasText(runtimeSurfaceInstanceRef)) {
                if (!runtimeSurfaceInstanceRef.equals(text(component.path("refs"), "runtimeSurfaceInstanceRef"))) {
                    continue;
                }
            } else {
                String widgetKey = text(component.path("identity"), "widgetKey");
                String resourcePath = text(component.path("refs"), "resourcePath");
                if (!surfaceRef.equals(widgetKey)
                        && !targetWidget.equals(widgetKey)
                        && !targetResourcePath.equals(resourcePath)) {
                    continue;
                }
            }
            for (JsonNode descriptor : component.path("snapshot").path("schemaFieldDescriptors")) {
                String descriptorFieldRef = firstNonBlank(
                        text(descriptor, "fieldRef"),
                        text(descriptor, "ref"),
                        text(descriptor, "field"),
                        text(descriptor, "path"),
                        text(descriptor, "name"));
                if (!fieldRef.equals(descriptorFieldRef)) {
                    continue;
                }
                String fieldType = normalizedTemporalComparisonType(descriptor);
                if (StringUtils.hasText(fieldType)) {
                    return fieldType;
                }
                fieldType = firstNonBlank(
                        text(descriptor, "fieldType"),
                        text(descriptor, "valueType"),
                        text(descriptor, "dataType"),
                        text(descriptor, "semanticType"),
                        text(descriptor, "type"),
                        text(descriptor, "format"),
                        text(descriptor, "controlType"));
                if (StringUtils.hasText(fieldType)) {
                    return fieldType.trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        return "";
    }

    private List<String> runtimeSurfaceComparableSchemaFieldRefs(ObjectNode runtimeContext, String surfaceRef) {
        List<String> redactedFields = runtimeSurfaceRedactedFieldRefs(runtimeContext, surfaceRef);
        return runtimeSurfaceSchemaFieldRefs(runtimeContext, surfaceRef).stream()
                .filter(field -> !redactedFields.contains(field))
                .toList();
    }

    private List<String> runtimeSurfaceSchemaFieldRefs(ObjectNode runtimeContext, String surfaceRef) {
        return runtimeSurfaceFieldRefs(runtimeContext, surfaceRef, false);
    }

    private List<String> runtimeSurfaceRedactedFieldRefs(ObjectNode runtimeContext, String surfaceRef) {
        return runtimeSurfaceFieldRefs(runtimeContext, surfaceRef, true);
    }

    private List<String> runtimeSurfaceFieldRefs(ObjectNode runtimeContext, String surfaceRef, boolean redactedOnly) {
        JsonNode relation = runtimeRelationSurfaceRef(runtimeContext, surfaceRef);
        String targetWidget = firstNonBlank(
                text(relation, "targetWidget"),
                text(relation.path("target"), "widget"));
        String targetResourcePath = firstNonBlank(
                text(relation, "targetResourcePath"),
                text(relation.path("target"), "resourcePath"));
        String runtimeSurfaceInstanceRef = runtimeSurfaceInstanceRef(relation);
        if (!StringUtils.hasText(runtimeSurfaceInstanceRef)
                && !StringUtils.hasText(targetWidget)
                && runtimeSurfaceResourcePathAmbiguous(runtimeContext, targetResourcePath)) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (JsonNode component : runtimeContext.path("components")) {
            if (StringUtils.hasText(runtimeSurfaceInstanceRef)) {
                if (!runtimeSurfaceInstanceRef.equals(text(component.path("refs"), "runtimeSurfaceInstanceRef"))) {
                    continue;
                }
            } else {
                String widgetKey = text(component.path("identity"), "widgetKey");
                String resourcePath = text(component.path("refs"), "resourcePath");
                if (!surfaceRef.equals(widgetKey)
                        && !targetWidget.equals(widgetKey)
                        && !targetResourcePath.equals(resourcePath)) {
                    continue;
                }
            }
            if (redactedOnly) {
                collectRuntimeSurfaceFieldRefs(fields, component.path("diagnostics").path("omittedFields"));
                collectRuntimeSurfaceFieldRefs(fields, component.path("diagnostics").path("redactedFieldRefs"));
                collectRuntimeSurfaceFieldRefs(fields, component.path("diagnostics").path("sensitiveFieldRefs"));
                collectRuntimeSurfaceFieldRefs(fields, component.path("diagnostics").path("hiddenFieldRefs"));
                collectRuntimeSurfaceFieldRefs(fields, component.path("snapshot").path("omittedFields"));
                collectRuntimeSurfaceFieldRefs(fields, component.path("snapshot").path("redactedFieldRefs"));
                collectRuntimeSurfaceFieldRefs(fields, component.path("snapshot").path("sensitiveFieldRefs"));
                collectRuntimeSurfaceFieldRefs(fields, component.path("snapshot").path("hiddenFieldRefs"));
            } else {
                collectRuntimeSurfaceFieldRefs(fields, component.path("snapshot").path("schemaFieldRefs"));
            }
        }
        return fields.stream().filter(this::safeIdentifier).distinct().toList();
    }

    private void collectRuntimeSurfaceFieldRefs(List<String> fields, JsonNode nodes) {
        for (JsonNode node : nodes) {
            String fieldRef = node.isTextual()
                    ? node.asText("")
                    : firstNonBlank(
                    text(node, "fieldRef"),
                    text(node, "field"),
                    text(node, "ref"),
                    text(node, "path"),
                    text(node, "name"));
            if (StringUtils.hasText(fieldRef)) {
                fields.add(fieldRef);
            }
        }
    }

    private JsonNode runtimeRelationSurfaceRef(ObjectNode runtimeContext, String surfaceRef) {
        if (runtimeContext == null || !StringUtils.hasText(surfaceRef)) {
            return objectMapper.createObjectNode();
        }
        for (JsonNode relation : relationSurfaceRefs(runtimeContext)) {
            String candidate = firstNonBlank(
                    text(relation, "id"),
                    text(relation, "surfaceRef"),
                    text(relation, "targetSurface"),
                    text(relation, "targetWidget"),
                    text(relation.path("target"), "widget"));
            if (surfaceRef.equals(candidate)) {
                return relation;
            }
        }
        return objectMapper.createObjectNode();
    }

    private String runtimeSurfaceInstanceRef(JsonNode relation) {
        return firstNonBlank(
                text(relation, "runtimeSurfaceInstanceRef"),
                text(relation, "targetRuntimeSurfaceInstanceRef"),
                text(relation.path("target"), "runtimeSurfaceInstanceRef"));
    }

    private boolean runtimeSurfaceInstanceExists(ObjectNode runtimeContext, String runtimeSurfaceInstanceRef) {
        if (runtimeContext == null || !StringUtils.hasText(runtimeSurfaceInstanceRef)) {
            return false;
        }
        for (JsonNode component : runtimeContext.path("components")) {
            if (runtimeSurfaceInstanceRef.equals(text(component.path("refs"), "runtimeSurfaceInstanceRef"))) {
                return true;
            }
        }
        return false;
    }

    private boolean runtimeSurfaceTargetWidgetExists(ObjectNode runtimeContext, String targetWidget) {
        if (runtimeContext == null || !StringUtils.hasText(targetWidget)) {
            return false;
        }
        for (JsonNode component : runtimeContext.path("components")) {
            if (targetWidget.equals(text(component.path("identity"), "widgetKey"))) {
                return true;
            }
        }
        return false;
    }

    private boolean runtimeSurfaceResourcePathAmbiguous(ObjectNode runtimeContext, String resourcePath) {
        if (runtimeContext == null || !StringUtils.hasText(resourcePath)) {
            return false;
        }
        int matches = 0;
        for (JsonNode component : runtimeContext.path("components")) {
            if (resourcePath.equals(text(component.path("refs"), "resourcePath"))) {
                matches++;
                if (matches > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean acceptedRuntimeRelatedSurfaceComparisonDimension(ObjectNode resolution) {
        return resolution != null
                && resolution.path("comparisonDimension").isObject()
                && acceptedRuntimeRelatedSurfaceComparisonDimension(resolution.path("comparisonDimension")) != null;
    }

    private ObjectNode acceptedRuntimeRelatedSurfaceComparisonDimension(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return null;
        }
        String fieldRef = text(candidate, "fieldRef");
        String source = text(candidate, "source");
        String provenance = text(candidate, "provenance");
        if (!safeIdentifier(fieldRef)
                || !Set.of("semantic_decision", "backend_contract").contains(source)
                || !"backend_reconciled".equals(provenance)
                || !candidate.path("redactionRequired").asBoolean(false)
                || !candidate.path("requiresBothSurfaces").asBoolean(false)) {
            return null;
        }
        ArrayNode allowedFactKinds = objectMapper.createArrayNode();
        for (JsonNode factKind : candidate.path("allowedFactKinds")) {
            if (!factKind.isTextual()) {
                continue;
            }
            String kind = factKind.asText("");
            if ("surface_record_count".equals(kind)
                    || "categorical_distribution".equals(kind)
                    || "projection_redaction_coverage".equals(kind)
                    || "record_count_delta".equals(kind)
                    || "category_overlap".equals(kind)
                    || "record_presence_matrix".equals(kind)
                    || ("temporal_coverage".equals(kind) && temporalComparisonDimension(candidate))) {
                allowedFactKinds.add(kind);
            }
        }
        if (allowedFactKinds.isEmpty()) {
            return null;
        }
        ObjectNode accepted = objectMapper.createObjectNode();
        accepted.put("fieldRef", fieldRef);
        accepted.put("source", source);
        accepted.put("provenance", "backend_reconciled");
        accepted.set("allowedFactKinds", allowedFactKinds);
        String fieldType = normalizedTemporalComparisonType(candidate);
        if (StringUtils.hasText(fieldType)) {
            accepted.put("fieldType", fieldType);
        }
        accepted.put("requiresBothSurfaces", true);
        accepted.put("redactionRequired", true);
        accepted.put("status", "accepted");
        accepted.put("claimRef", "runtime-compare-dimension:" + fieldRef);
        return accepted;
    }

    private boolean temporalComparisonDimension(JsonNode dimension) {
        return StringUtils.hasText(normalizedTemporalComparisonType(dimension));
    }

    private String normalizedTemporalComparisonType(JsonNode dimension) {
        if (dimension == null || !dimension.isObject()) {
            return "";
        }
        for (String field : List.of("fieldType", "valueType", "dataType", "semanticType", "type", "format")) {
            String temporalType = normalizedTemporalComparisonType(text(dimension, field));
            if (StringUtils.hasText(temporalType)) {
                return temporalType;
            }
        }
        String controlType = text(dimension, "controlType").toLowerCase(Locale.ROOT).trim();
        if (controlType.contains("date") || controlType.contains("time")) {
            return controlType.contains("time") ? "date-time" : "date";
        }
        return "";
    }

    private String normalizedTemporalComparisonType(String rawValue) {
        String value = rawValue == null ? "" : rawValue.toLowerCase(Locale.ROOT).trim();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if ("date".equals(value)) {
            return "date";
        }
        if ("datetime".equals(value)
                || "date-time".equals(value)
                || "timestamp".equals(value)
                || "instant".equals(value)
                || "temporal".equals(value)) {
            return "date-time";
        }
        return "";
    }

    private record TemporalPoint(Instant instant, String normalizedValue) {
    }

    private record TemporalCoverage(
            String minValue,
            String maxValue,
            int recordCountWithValue,
            int recordCountMissingValue) {
    }

    private boolean safeIdentifier(String value) {
        return StringUtils.hasText(value) && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private boolean runtimeRelatedSurfaceReadonlyExecutionIntent(String intentKind) {
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        return "runtime_related_surface_list".equals(normalized)
                || "runtime_related_surface_summary".equals(normalized)
                || "runtime_related_surface_detail".equals(normalized)
                || "runtime_related_surface_compare".equals(normalized);
    }

    private int minimumAcceptedCandidatesForReadonlyExecution(String intentKind) {
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        return "runtime_related_surface_summary".equals(normalized)
                || "runtime_related_surface_detail".equals(normalized)
                ? 1
                : 2;
    }

    private int readonlyPlannedStepCount(ObjectNode resolution, String intentKind) {
        String normalized = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        if ("runtime_related_surface_list".equals(normalized)
                && acceptedRuntimeRelatedSurfaceListTarget(resolution)) {
            return 1;
        }
        if ("runtime_related_surface_summary".equals(normalized)
                && acceptedRuntimeRelatedSurfaceSummaryTarget(resolution)) {
            return 1;
        }
        if ("runtime_related_surface_detail".equals(normalized)) {
            return acceptedRuntimeRelatedSurfaceDetailTarget(resolution) ? 1 : 0;
        }
        return Math.min(2, acceptedRuntimeRelatedSurfaceCandidateCount(resolution));
    }

    private boolean runtimeToolPlannerDryRunEnabled() {
        RuntimeToolPlannerPolicy plannerPolicy = runtimeToolPlannerPolicy == null
                ? RuntimeToolPlannerPolicy.singleReadBeta()
                : runtimeToolPlannerPolicy;
        return plannerPolicy.dryRunMultiToolEnabled();
    }

    private boolean runtimeToolPlannerReadonlySkeletonEnabled() {
        RuntimeToolPlannerPolicy plannerPolicy = runtimeToolPlannerPolicy == null
                ? RuntimeToolPlannerPolicy.singleReadBeta()
                : runtimeToolPlannerPolicy;
        return plannerPolicy.readonlyMultiToolSkeletonEnabled();
    }

    private boolean runtimeToolPlannerReadonlyExecutionEnabled() {
        RuntimeToolPlannerPolicy plannerPolicy = runtimeToolPlannerPolicy == null
                ? RuntimeToolPlannerPolicy.singleReadBeta()
                : runtimeToolPlannerPolicy;
        return plannerPolicy.readonlyMultiToolExecutionEnabled();
    }

    private ObjectNode runtimeToolPlan(
            ObjectNode resolution,
            String intentKind,
            boolean toolAttempted,
            boolean toolSucceeded,
            String failureCode) {
        String normalizedIntentKind = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        boolean availability = "runtime_related_surface_availability".equals(normalizedIntentKind);
        boolean blockedIntent = runtimeRelatedSurfaceIntentBlocksRead(normalizedIntentKind, resolution);
        boolean compareIntent = "runtime_related_surface_compare".equals(normalizedIntentKind);
        String effectiveFailureCode = firstNonBlank(failureCode, blockedIntent
                ? runtimeRelatedSurfaceBlockedFailureCode(normalizedIntentKind, resolution)
                : "");
        RuntimeToolPlannerPolicy plannerPolicy = runtimeToolPlannerPolicy == null
                ? RuntimeToolPlannerPolicy.singleReadBeta()
                : runtimeToolPlannerPolicy;
        boolean dryRunMultiTool = plannerPolicy.dryRunMultiToolEnabled() && !availability;
        boolean readonlySkeletonMultiTool = plannerPolicy.readonlyMultiToolSkeletonEnabled() && !availability && !blockedIntent;
        boolean readonlyExecutionMultiTool = plannerPolicy.readonlyMultiToolExecutionEnabled() && !availability && !blockedIntent;
        boolean readonlyMultiTool = readonlySkeletonMultiTool || readonlyExecutionMultiTool;
        boolean readonlyPlannedSteps = readonlyMultiTool
                && runtimeRelatedSurfaceReadonlyExecutionIntent(normalizedIntentKind)
                && acceptedRuntimeRelatedSurfaceCandidateCount(resolution) >= minimumAcceptedCandidatesForReadonlyExecution(normalizedIntentKind);
        int readonlyPlannedStepCount = readonlyPlannedStepCount(resolution, normalizedIntentKind);
        int plannedMaxToolCalls = dryRunMultiTool
                ? 0
                : readonlySkeletonMultiTool || availability || blockedIntent ? 0
                : readonlyExecutionMultiTool ? readonlyPlannedStepCount : 1;
        int plannedMaxRelatedSurfaceReads = dryRunMultiTool
                ? 0
                : readonlySkeletonMultiTool || availability || blockedIntent ? 0
                : readonlyExecutionMultiTool ? readonlyPlannedStepCount : 1;
        int plannedMaxTotalRecordsReturned = dryRunMultiTool
                ? 0
                : readonlySkeletonMultiTool || availability || blockedIntent ? 0
                : readonlyExecutionMultiTool ? readonlyPlannedStepCount * 8 : 8;
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("schemaVersion", "praxis-runtime-tool-plan.v1");
        plan.put("semanticDecisionRef", "consultativeIntent:" + normalizedIntentKind);
        plan.put("intentKind", normalizedIntentKind);
        plan.put("readMode", availability || blockedIntent
                ? "none"
                : "runtime_related_surface_list".equals(normalizedIntentKind)
                        && acceptedRuntimeRelatedSurfaceListTarget(resolution)
                ? "list_targeted"
                : "runtime_related_surface_summary".equals(normalizedIntentKind)
                        && acceptedRuntimeRelatedSurfaceSummaryTarget(resolution)
                ? "summary_targeted"
                : "runtime_related_surface_detail".equals(normalizedIntentKind)
                ? "detail"
                : "runtime_related_surface_summary".equals(normalizedIntentKind) ? "summary"
                : compareIntent ? "compare" : "single");
        ObjectNode planner = plan.putObject("planner");
        planner.put("schemaVersion", "praxis-runtime-tool-planner.v1");
        planner.put("phase", "multi-surface-multi-tool-skeleton");
        planner.put("rankingSource", "runtimeRelatedSurfaceResolution");
        planner.put("multiToolExecutionEnabled", plannerPolicy.multiToolExecutionEnabled());
        planner.put("multiToolPlanningEnabled", dryRunMultiTool || readonlyMultiTool || compareIntent && blockedIntent);
        planner.put("dryRun", dryRunMultiTool);
        planner.put("executionMode", plannerPolicy.executionMode());
        planner.put("planningOnlyForUnsupportedIntents", blockedIntent && !dryRunMultiTool && !readonlySkeletonMultiTool);
        planner.put("planningOnlyForPolicySkeleton", readonlySkeletonMultiTool);
        planner.put("readFreeIntent", availability);
        planner.put("maxToolCallsMayExceedOne", plannerPolicy.maxToolCallsMayExceedOne());
        planner.put("backendPolicyRef", plannerPolicy.policyRef());
        ObjectNode multiToolAuthorization = plan.putObject("multiToolAuthorization");
        multiToolAuthorization.put("schemaVersion", "praxis-runtime-tool-multi-tool-authorization.v1");
        multiToolAuthorization.put("source", "backend_policy");
        multiToolAuthorization.put("policyRef", plannerPolicy.policyRef());
        multiToolAuthorization.put("allowed", plannerPolicy.multiToolAuthorized());
        multiToolAuthorization.put("reason", dryRunMultiTool
                ? "runtime-multi-tool-policy-dry-run"
                : plannerPolicy.readonlyMultiToolExecutionEnabled() ? "runtime-multi-tool-readonly-beta"
                : plannerPolicy.readonlyMultiToolSkeletonEnabled() ? "runtime-multi-tool-readonly-beta-skeleton"
                : "runtime-multi-tool-policy-not-enabled");
        ObjectNode aggregationPolicy = plan.putObject("aggregationPolicy");
        aggregationPolicy.put("schemaVersion", "praxis-runtime-tool-aggregation-policy.v1");
        aggregationPolicy.put("mode", dryRunMultiTool
                ? "dry_run_multi_read"
                : readonlySkeletonMultiTool ? "planning_only_multi_read"
                : readonlyExecutionMultiTool && "runtime_related_surface_list".equals(normalizedIntentKind)
                        && acceptedRuntimeRelatedSurfaceListTarget(resolution) ? "governed_list_targeted"
                : readonlyExecutionMultiTool && "runtime_related_surface_summary".equals(normalizedIntentKind)
                        && acceptedRuntimeRelatedSurfaceSummaryTarget(resolution) ? "governed_summary_targeted"
                : readonlyExecutionMultiTool && "runtime_related_surface_summary".equals(normalizedIntentKind) ? "governed_summary"
                : readonlyExecutionMultiTool && "runtime_related_surface_detail".equals(normalizedIntentKind) ? "governed_detail"
                : readonlyExecutionMultiTool && compareIntent ? "governed_compare"
                : readonlyExecutionMultiTool ? "bounded_multi_read"
                : compareIntent && blockedIntent ? "compare_planning_only"
                : availability || blockedIntent ? "none" : "single_read");
        aggregationPolicy.put("maxInputReads", dryRunMultiTool || readonlyMultiTool
                ? Math.max(1, readonlyPlannedStepCount > 0 ? readonlyPlannedStepCount : plannerPolicy.maxRelatedSurfaceReads())
                : plannedMaxRelatedSurfaceReads);
        aggregationPolicy.put("conflictResolution", "fail_closed");
        aggregationPolicy.put("rawRuntimeValuesCopied", false);
        if (compareIntent && acceptedRuntimeRelatedSurfaceComparisonDimension(resolution)) {
            aggregationPolicy.set("comparisonDimension", resolution.path("comparisonDimension").deepCopy());
            aggregationPolicy.put("compareEvidenceEmitted", false);
            aggregationPolicy.put("compareExecutionStage", "skeleton_without_terminal_compare_evidence");
        }
        ObjectNode budget = plan.putObject("budget");
        budget.put("maxToolCalls", plannedMaxToolCalls);
        budget.put("maxRelatedSurfaceReads", plannedMaxRelatedSurfaceReads);
        budget.put("maxRecordsPerRead", availability || blockedIntent ? 0 : 8);
        budget.put("maxTotalRecordsReturned", plannedMaxTotalRecordsReturned);
        budget.put("usedToolCalls", toolAttempted ? 1 : 0);
        budget.put("globalMaxToolCalls", plannedMaxToolCalls);
        budget.put("consumesGlobalToolBudget", toolAttempted);
        budget.put("exhausted", toolAttempted && !availability);
        ObjectNode relatedSurfaceToolBudget = budget.putObject("runtimeRelatedSurfaceToolBudget");
        relatedSurfaceToolBudget.put("maxToolCalls", readonlyPlannedSteps
                ? readonlyPlannedStepCount
                : plannedMaxToolCalls);
        relatedSurfaceToolBudget.put("maxReads", readonlyPlannedSteps
                ? readonlyPlannedStepCount
                : plannedMaxRelatedSurfaceReads);
        relatedSurfaceToolBudget.put("usedToolCalls", toolAttempted ? 1 : 0);
        relatedSurfaceToolBudget.put("usedReads", toolSucceeded ? 1 : 0);
        ArrayNode steps = plan.putArray("steps");
        ArrayNode blockedSteps = plan.putArray("blockedSteps");
        ArrayNode candidateSteps = plan.putArray("candidateSteps");
        populateRuntimeToolPlanCandidateSteps(candidateSteps, resolution, normalizedIntentKind, availability, blockedIntent, plannerPolicy);
        appendRuntimeToolPlanExecutionDiagnostics(plan, candidateSteps, dryRunMultiTool, readonlySkeletonMultiTool, plannerPolicy, effectiveFailureCode);
        ObjectNode executionDiagnostics = plan.withObject("/executionDiagnostics");
        if (toolSucceeded) {
            executionDiagnostics.put("aggregateStatus", "success");
        } else if (toolAttempted) {
            executionDiagnostics.put("aggregateStatus", "failed");
            executionDiagnostics.put("failureCode", firstNonBlank(effectiveFailureCode, "runtime-related-surface-read-failed"));
        } else if (blockedIntent) {
            executionDiagnostics.put("aggregateStatus", compareIntent ? "blocked" : "not_executed");
            executionDiagnostics.put("failureCode", effectiveFailureCode);
        }
        String surfaceRef = resolution == null ? "" : text(resolution, "selectedCandidateRef");
        String candidateRef = resolution == null ? "" : text(resolution, "selectedCandidateEvidenceRef");
        if (readonlyPlannedSteps) {
            appendRuntimeToolPlanReadonlyPlannedSteps(steps, resolution, normalizedIntentKind);
        } else if (StringUtils.hasText(surfaceRef) && !availability && !blockedIntent && !dryRunMultiTool && !readonlySkeletonMultiTool && !readonlyExecutionMultiTool) {
            ObjectNode step = steps.addObject();
            step.put("stepRef", "runtime-tool-step:" + surfaceRef);
            step.put("kind", "runtime_related_surface_read");
            step.put("surfaceRef", surfaceRef);
            step.put("candidateRef", firstNonBlank(candidateRef, "runtime-surface-candidate:" + surfaceRef));
            step.put("toolName", AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE);
            step.put("toolPurpose", "retrieveEvidence");
            step.put("status", toolSucceeded ? "executed" : toolAttempted ? "failed" : "planned");
            step.set("dependsOn", objectMapper.createArrayNode());
            applyRuntimeToolStepPolicies(step, 1, 8);
            step.set("acceptedClaimRefs", runtimeToolPlanAcceptedClaimRefs(firstNonBlank(candidateRef, "runtime-surface-candidate:" + surfaceRef)));
            ArrayNode blockedBy = step.putArray("blockedBy");
            if (StringUtils.hasText(failureCode)) {
                blockedBy.add(failureCode);
            }
        } else if (StringUtils.hasText(surfaceRef) && blockedIntent && !dryRunMultiTool && !readonlySkeletonMultiTool) {
            ObjectNode blocked = blockedSteps.addObject();
            blocked.put("stepRef", "runtime-tool-step:" + surfaceRef);
            blocked.put("kind", normalizedIntentKind);
            blocked.put("surfaceRef", surfaceRef);
            blocked.put("candidateRef", firstNonBlank(candidateRef, "runtime-surface-candidate:" + surfaceRef));
            blocked.put("status", "blocked");
            blocked.put("failureCode", effectiveFailureCode);
            blocked.set("dependsOn", objectMapper.createArrayNode());
            applyRuntimeToolStepPolicies(blocked, 0, 0);
            blocked.set("acceptedClaimRefs", runtimeToolPlanAcceptedClaimRefs(firstNonBlank(candidateRef, "runtime-surface-candidate:" + surfaceRef)));
            blocked.set("blockedBy", textArray(List.of(effectiveFailureCode), 4));
            blocked.put("executionPolicy", compareIntent ? "compare-planning-only" : "intent-not-supported-in-current-cut");
        } else if (StringUtils.hasText(failureCode) && !dryRunMultiTool && !readonlySkeletonMultiTool) {
            ObjectNode blocked = blockedSteps.addObject();
            blocked.put("stepRef", "runtime-tool-step:none");
            blocked.put("status", availability ? "blocked_read_free" : "blocked");
            blocked.put("failureCode", failureCode);
            applyRuntimeToolStepPolicies(blocked, 0, 0);
        }
        if (availability) {
            plan.put("readFree", true);
            plan.put("executionPolicy", "availability-read-free");
        } else if (dryRunMultiTool) {
            plan.put("readFree", true);
            plan.put("executionPolicy", "multi-tool-dry-run");
        } else if (readonlySkeletonMultiTool) {
            plan.put("readFree", true);
            plan.put("executionPolicy", "multi-tool-readonly-beta-planning-only");
        } else if (readonlyExecutionMultiTool) {
            plan.put("readFree", false);
            plan.put("executionPolicy", "multi-tool-readonly-beta");
        } else if (blockedIntent) {
            plan.put("readFree", true);
            plan.put("executionPolicy", compareIntent ? "compare-planning-only" : "intent-not-supported-in-current-cut");
        }
        enforceRuntimeToolPlanMultiToolGuardrail(plan);
        return plan;
    }

    private void enforceRuntimeToolPlanMultiToolGuardrail(ObjectNode plan) {
        if (plan == null) {
            return;
        }
        boolean multiToolAllowed = plan.path("planner").path("multiToolExecutionEnabled").asBoolean(false)
                && plan.path("planner").path("maxToolCallsMayExceedOne").asBoolean(false)
                && plan.path("multiToolAuthorization").path("allowed").asBoolean(false);
        ObjectNode budget = plan.withObject("/budget");
        int maxToolCalls = budget.path("maxToolCalls").asInt(0);
        int globalMaxToolCalls = budget.path("globalMaxToolCalls").asInt(0);
        boolean clamped = false;
        if (!multiToolAllowed && maxToolCalls > 1) {
            budget.put("maxToolCalls", 1);
            clamped = true;
        }
        if (!multiToolAllowed && globalMaxToolCalls > 1) {
            budget.put("globalMaxToolCalls", 1);
            clamped = true;
        }
        for (String collection : List.of("steps", "candidateSteps", "blockedSteps")) {
            JsonNode steps = plan.path(collection);
            if (!steps.isArray()) {
                continue;
            }
            for (JsonNode step : steps) {
                if (!step.isObject()) {
                    continue;
                }
                ObjectNode stepObject = (ObjectNode) step;
                ObjectNode stepBudget = stepObject.withObject("/stepBudget");
                if (!multiToolAllowed && stepBudget.path("maxToolCalls").asInt(0) > 1) {
                    stepBudget.put("maxToolCalls", 1);
                    stepBudget.put("consumesGlobalToolBudget", true);
                    clamped = true;
                }
            }
        }
        if (clamped) {
            ObjectNode guardrail = plan.putObject("multiToolGuardrail");
            guardrail.put("schemaVersion", "praxis-runtime-tool-multi-tool-guardrail.v1");
            guardrail.put("status", "clamped");
            guardrail.put("failureCode", "runtime-multi-tool-policy-not-enabled");
            guardrail.put("policyRef", text(plan.path("multiToolAuthorization"), "policyRef"));
            ArrayNode blockedSteps = plan.withArray("blockedSteps");
            ObjectNode blocked = blockedSteps.addObject();
            blocked.put("stepRef", "runtime-tool-step:multi-tool");
            blocked.put("kind", "runtime_tool_plan_guardrail");
            blocked.put("status", "blocked");
            blocked.put("failureCode", "runtime-multi-tool-policy-not-enabled");
            blocked.set("blockedBy", textArray(List.of("runtime-multi-tool-policy-not-enabled"), 4));
            applyRuntimeToolStepPolicies(blocked, 0, 0);
        }
    }

    private void appendRuntimeToolPlanReadonlyPlannedSteps(ArrayNode steps, ObjectNode resolution, String intentKind) {
        if (steps == null || resolution == null || !resolution.path("candidates").isArray()) {
            return;
        }
        String normalizedIntentKind = normalizeRuntimeRelatedSurfaceIntentKind(intentKind);
        int maxPlannedSteps = "runtime_related_surface_detail".equals(normalizedIntentKind)
                || "runtime_related_surface_list".equals(normalizedIntentKind)
                        && acceptedRuntimeRelatedSurfaceListTarget(resolution)
                || "runtime_related_surface_summary".equals(normalizedIntentKind)
                        && acceptedRuntimeRelatedSurfaceSummaryTarget(resolution)
                ? 1
                : 2;
        String listTargetSurfaceRef = "runtime_related_surface_list".equals(normalizedIntentKind)
                ? text(resolution.path("listTarget"), "surfaceRef")
                : "";
        String summaryTargetSurfaceRef = "runtime_related_surface_summary".equals(normalizedIntentKind)
                ? text(resolution.path("summaryTarget"), "surfaceRef")
                : "";
        String detailTargetSurfaceRef = "runtime_related_surface_detail".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind))
                ? text(resolution.path("detailTarget"), "surfaceRef")
                : "";
        int planned = 0;
        for (JsonNode candidate : resolution.path("candidates")) {
            if (planned >= maxPlannedSteps || !candidate.isObject() || !"accepted".equals(text(candidate, "status"))) {
                continue;
            }
            String surfaceRef = text(candidate, "surfaceRef");
            if (!StringUtils.hasText(surfaceRef)) {
                continue;
            }
            if (StringUtils.hasText(detailTargetSurfaceRef) && !detailTargetSurfaceRef.equals(surfaceRef)) {
                continue;
            }
            if (StringUtils.hasText(listTargetSurfaceRef) && !listTargetSurfaceRef.equals(surfaceRef)) {
                continue;
            }
            if (StringUtils.hasText(summaryTargetSurfaceRef) && !summaryTargetSurfaceRef.equals(surfaceRef)) {
                continue;
            }
            String candidateRef = firstNonBlank(text(candidate, "candidateRef"), "runtime-surface-candidate:" + surfaceRef);
            ObjectNode step = steps.addObject();
            step.put("stepRef", "runtime-tool-step:" + surfaceRef);
            step.put("kind", "runtime_related_surface_read");
            step.put("surfaceRef", surfaceRef);
            step.put("candidateRef", candidateRef);
            step.put("toolName", AgenticAuthoringToolRegistry.RESOLVE_RUNTIME_RELATED_SURFACE);
            step.put("toolPurpose", "retrieveEvidence");
            step.put("status", "planned");
            step.put("executionStatus", "planning_only_policy_skeleton");
            step.set("dependsOn", objectMapper.createArrayNode());
            applyRuntimeToolStepPolicies(step, 1, 8);
            step.set("acceptedClaimRefs", runtimeToolPlanAcceptedClaimRefs(candidateRef));
            step.set("blockedBy", objectMapper.createArrayNode());
            planned++;
        }
    }

    private int acceptedRuntimeRelatedSurfaceCandidateCount(ObjectNode resolution) {
        if (resolution == null || !resolution.path("candidates").isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode candidate : resolution.path("candidates")) {
            if (candidate.isObject() && "accepted".equals(text(candidate, "status"))) {
                count++;
            }
        }
        return count;
    }

    private boolean acceptedRuntimeRelatedSurfaceDetailTarget(ObjectNode resolution) {
        if (resolution == null || !resolution.path("detailTarget").isObject()) {
            return false;
        }
        JsonNode target = resolution.path("detailTarget");
        return "backend_reconciled".equals(text(target, "provenance"))
                && StringUtils.hasText(text(target, "surfaceRef"))
                && acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(resolution, text(target, "surfaceRef")) != null;
    }

    private boolean acceptedRuntimeRelatedSurfaceListTarget(ObjectNode resolution) {
        if (resolution == null || !resolution.path("listTarget").isObject()) {
            return false;
        }
        JsonNode target = resolution.path("listTarget");
        return "backend_reconciled".equals(text(target, "provenance"))
                && StringUtils.hasText(text(target, "surfaceRef"))
                && acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(resolution, text(target, "surfaceRef")) != null;
    }

    private boolean acceptedRuntimeRelatedSurfaceSummaryTarget(ObjectNode resolution) {
        if (resolution == null || !resolution.path("summaryTarget").isObject()) {
            return false;
        }
        JsonNode target = resolution.path("summaryTarget");
        return "backend_reconciled".equals(text(target, "provenance"))
                && StringUtils.hasText(text(target, "surfaceRef"))
                && acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(resolution, text(target, "surfaceRef")) != null;
    }

    private JsonNode firstAcceptedRuntimeRelatedSurfaceCandidate(ObjectNode resolution) {
        if (resolution == null || !resolution.path("candidates").isArray()) {
            return null;
        }
        for (JsonNode candidate : resolution.path("candidates")) {
            if (candidate.isObject() && "accepted".equals(text(candidate, "status"))) {
                return candidate;
            }
        }
        return null;
    }

    private JsonNode acceptedRuntimeRelatedSurfaceCandidateBySurfaceRef(ObjectNode resolution, String surfaceRef) {
        if (resolution == null || !StringUtils.hasText(surfaceRef) || !resolution.path("candidates").isArray()) {
            return null;
        }
        for (JsonNode candidate : resolution.path("candidates")) {
            if (candidate.isObject()
                    && "accepted".equals(text(candidate, "status"))
                    && (surfaceRef.equals(text(candidate, "surfaceRef"))
                    || surfaceRef.equals(text(candidate, "candidateRef"))
                    || surfaceRef.equals(text(candidate, "runtimeSurfaceInstanceRef")))) {
                return candidate;
            }
        }
        return null;
    }

    private void populateRuntimeToolPlanCandidateSteps(
            ArrayNode candidateSteps,
            ObjectNode resolution,
            String intentKind,
            boolean availability,
            boolean blockedIntent,
            RuntimeToolPlannerPolicy plannerPolicy) {
        if (candidateSteps == null || resolution == null) {
            return;
        }
        appendRuntimeToolPlanCandidateSteps(candidateSteps, resolution.path("candidates"), "accepted", intentKind, availability, blockedIntent, plannerPolicy);
        appendRuntimeToolPlanCandidateSteps(candidateSteps, resolution.path("blockedCandidates"), "rejected", intentKind, availability, blockedIntent, plannerPolicy);
    }

    private void appendRuntimeToolPlanCandidateSteps(
            ArrayNode candidateSteps,
            JsonNode candidates,
            String defaultStatus,
            String intentKind,
            boolean availability,
            boolean blockedIntent,
            RuntimeToolPlannerPolicy plannerPolicy) {
        if (candidateSteps == null || !candidates.isArray()) {
            return;
        }
        boolean dryRunMultiTool = plannerPolicy != null && plannerPolicy.dryRunMultiToolEnabled() && !availability;
        boolean readonlySkeletonMultiTool = plannerPolicy != null && plannerPolicy.readonlyMultiToolSkeletonEnabled() && !availability && !blockedIntent;
        boolean readonlyExecutionMultiTool = plannerPolicy != null && plannerPolicy.readonlyMultiToolExecutionEnabled() && !availability && !blockedIntent;
        int rank = candidateSteps.size() + 1;
        for (JsonNode candidate : candidates) {
            String surfaceRef = text(candidate, "surfaceRef");
            String candidateRef = firstNonBlank(text(candidate, "candidateRef"), "runtime-surface-candidate:" + firstNonBlank(surfaceRef, "unknown"));
            ObjectNode step = candidateSteps.addObject();
            step.put("stepRef", StringUtils.hasText(surfaceRef)
                    ? "runtime-tool-step:" + surfaceRef
                    : "runtime-tool-step:none");
            step.put("kind", "runtime_related_surface_read_candidate");
            step.put("surfaceRef", surfaceRef);
            step.put("candidateRef", candidateRef);
            step.put("rank", rank++);
            step.put("candidateStatus", firstNonBlank(text(candidate, "status"), defaultStatus));
            step.put("executionStatus", availability
                    ? "read_free"
                    : dryRunMultiTool ? "dry_run_planned"
                    : readonlySkeletonMultiTool ? "planning_only_policy_skeleton"
                    : readonlyExecutionMultiTool ? "planned_for_read_only_execution"
                    : "runtime_related_surface_compare".equals(normalizeRuntimeRelatedSurfaceIntentKind(intentKind))
                    ? "compare_planning_only"
                    : blockedIntent ? "blocked_by_intent" : "eligible_for_single_tool_cut");
            applyRuntimeToolStepPolicies(step, availability || blockedIntent || dryRunMultiTool || readonlySkeletonMultiTool ? 0 : 1,
                    availability || blockedIntent || dryRunMultiTool || readonlySkeletonMultiTool ? 0 : 8);
            step.set("scoreReasons", candidate.path("scoreReasons").isArray()
                    ? candidate.path("scoreReasons").deepCopy()
                    : objectMapper.createArrayNode());
            step.set("failureCodes", candidate.path("failureCodes").isArray()
                    ? candidate.path("failureCodes").deepCopy()
                    : objectMapper.createArrayNode());
            step.set("acceptedClaimRefs", runtimeToolPlanAcceptedClaimRefs(candidateRef));
        }
    }

    private void appendRuntimeToolPlanExecutionDiagnostics(
            ObjectNode plan,
            ArrayNode candidateSteps,
            boolean dryRunMultiTool,
            boolean readonlySkeletonMultiTool,
            RuntimeToolPlannerPolicy plannerPolicy,
            String failureCode) {
        if (plan == null) {
            return;
        }
        ObjectNode diagnostics = plan.putObject("executionDiagnostics");
        diagnostics.put("schemaVersion", "praxis-runtime-tool-plan-execution-diagnostics.v1");
        diagnostics.put("policyRef", plannerPolicy == null ? "" : plannerPolicy.policyRef());
        diagnostics.put("dryRun", dryRunMultiTool);
        boolean blockedCompareIntent = StringUtils.hasText(failureCode)
                && "runtime-related-surface-compare-not-enabled".equals(failureCode);
        diagnostics.put("planningOnly", readonlySkeletonMultiTool || blockedCompareIntent);
        diagnostics.put("multiToolExecutionEnabled", plannerPolicy != null && plannerPolicy.multiToolExecutionEnabled());
        boolean multiToolPolicy = dryRunMultiTool || readonlySkeletonMultiTool
                || plannerPolicy != null && plannerPolicy.readonlyMultiToolExecutionEnabled();
        diagnostics.put("authorizedCandidateCount", multiToolPolicy && candidateSteps != null ? candidateSteps.size() : 0);
        diagnostics.put("maxPlannedSteps", multiToolPolicy && plannerPolicy != null
                ? Math.max(1, plannerPolicy.maxRelatedSurfaceReads())
                : 0);
        diagnostics.put("maxExecutableSteps", plannerPolicy != null && plannerPolicy.multiToolExecutionEnabled()
                ? Math.min(2, plan.path("steps").size())
                : 0);
        diagnostics.put("usedToolCalls", 0);
        diagnostics.put("backendReadsPerformed", false);
        diagnostics.put("nonExecutionReason", dryRunMultiTool
                ? "runtime-multi-tool-dry-run-read-free"
                : readonlySkeletonMultiTool ? "runtime-multi-tool-readonly-beta-planning-only"
                : firstNonBlank(failureCode, ""));
    }

    private void applyRuntimeToolStepPolicies(ObjectNode step, int maxToolCalls, int maxRecordsReturned) {
        if (step == null) {
            return;
        }
        ObjectNode stepBudget = step.putObject("stepBudget");
        stepBudget.put("maxToolCalls", maxToolCalls);
        stepBudget.put("maxRecordsReturned", maxRecordsReturned);
        stepBudget.put("consumesGlobalToolBudget", maxToolCalls > 0);
        step.put("projectionPolicyRef", "runtime-related-surface-projection:declared-fields-v1");
        step.put("redactionPolicyRef", "runtime-related-surface-redaction:sensitive-scalars-v1");
    }

    private ArrayNode runtimeToolPlanAcceptedClaimRefs(String candidateRef) {
        ArrayNode refs = objectMapper.createArrayNode();
        for (String kind : List.of("relation", "queryMapping", "operation", "resource", "selection", "projection")) {
            refs.add(candidateRef + ":" + kind);
        }
        return refs;
    }

    private ObjectNode runtimeRelatedSurfaceResolution(ObjectNode runtimeContext, String failureCode) {
        ObjectNode resolution = objectMapper.createObjectNode();
        resolution.put("schemaVersion", "praxis-runtime-related-surface-resolution.v1");
        resolution.put("semanticDecisionRef", "consultativeIntent:runtime_related_surface_read");
        ObjectNode budget = resolution.putObject("budget");
        budget.put("maxToolCalls", 1);
        budget.put("maxRelatedSurfaceReads", 1);
        budget.put("usedToolCalls", 0);
        ArrayNode candidates = resolution.putArray("candidates");
        ArrayNode blocked = resolution.putArray("blockedCandidates");
        if (runtimeContext == null || !runtimeContext.isObject()) {
            if (StringUtils.hasText(failureCode)) {
                ObjectNode rejected = blocked.addObject();
                rejected.put("candidateRef", "runtime-surface-candidate:none");
                rejected.put("status", "rejected");
                rejected.set("failureCodes", textArray(List.of(failureCode), 4));
            }
            return resolution;
        }
        ObjectNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonNode relation : relationSurfaceRefs(runtimeContext)) {
            CandidateAssessment assessment = assessCandidate(runtimeContext, relation);
            ObjectNode candidate = candidateNode(assessment, relation);
            if (assessment.accepted()) {
                candidates.add(candidate);
                if (assessment.score() > bestScore) {
                    bestScore = assessment.score();
                    best = candidate;
                }
            } else {
                blocked.add(candidate);
            }
        }
        if (best != null) {
            String candidateRef = text(best, "candidateRef");
            resolution.put("selectedCandidateRef", text(best, "surfaceRef"));
            resolution.put("selectedCandidateEvidenceRef", candidateRef);
            budget.put("usedToolCalls", 1);
        } else if (blocked.isEmpty()) {
            List<String> groundingFailures = runtimeGroundingFailureCodes(runtimeContext);
            if (!groundingFailures.isEmpty()) {
                ObjectNode rejected = blocked.addObject();
                rejected.put("candidateRef", "runtime-surface-candidate:none");
                rejected.put("status", "rejected");
                rejected.set("failureCodes", textArray(groundingFailures, 8));
            } else if (StringUtils.hasText(failureCode)) {
                ObjectNode rejected = blocked.addObject();
                rejected.put("candidateRef", "runtime-surface-candidate:none");
                rejected.put("status", "rejected");
                rejected.set("failureCodes", textArray(List.of(failureCode), 4));
            }
        } else if (StringUtils.hasText(failureCode)) {
            ObjectNode rejected = blocked.addObject();
            rejected.put("candidateRef", "runtime-surface-candidate:none");
            rejected.put("status", "rejected");
            rejected.set("failureCodes", textArray(List.of(failureCode), 4));
        }
        return resolution;
    }

    private List<JsonNode> relationSurfaceRefs(ObjectNode runtimeContext) {
        List<JsonNode> refs = new ArrayList<>();
        for (JsonNode component : runtimeContext.path("components")) {
            JsonNode relationRefs = component.path("snapshot").path("relationSurfaceRefs");
            if (!relationRefs.isArray()) {
                continue;
            }
            for (JsonNode relation : relationRefs) {
                if (relation.isObject()) {
                    refs.add(relation);
                }
            }
        }
        return refs;
    }

    private List<String> runtimeGroundingFailureCodes(ObjectNode runtimeContext) {
        List<String> failures = new ArrayList<>();
        if (runtimeContext == null || !runtimeContext.path("rejectedClaims").isArray()) {
            return failures;
        }
        for (JsonNode rejectedClaim : runtimeContext.path("rejectedClaims")) {
            String reason = text(rejectedClaim, "reason");
            if ("stale_observation".equals(reason)) {
                failures.add("runtime-surface-observation-stale");
            } else if ("inactive_observation".equals(reason)) {
                failures.add("runtime-surface-component-inactive");
            } else if ("unsupported_trust_boundary".equals(reason)) {
                failures.add("runtime-surface-trust-boundary-unsupported");
            } else if ("unsupported_schema_version".equals(reason)) {
                failures.add("runtime-surface-observation-schema-unsupported");
            } else if ("invalid_observation".equals(reason)) {
                failures.add("runtime-surface-observation-invalid");
            }
        }
        return failures.stream().distinct().toList();
    }

    private CandidateAssessment assessCandidate(ObjectNode runtimeContext, JsonNode relation) {
        String surfaceRef = firstNonBlank(
                text(relation, "id"),
                text(relation, "surfaceRef"),
                text(relation, "targetSurface"),
                text(relation, "targetWidget"),
                text(relation.path("target"), "widget"));
        String sourceWidget = firstNonBlank(text(relation, "sourceWidget"), text(relation.path("source"), "widget"));
        String targetWidget = firstNonBlank(text(relation, "targetWidget"), text(relation.path("target"), "widget"));
        String targetResourcePath = firstNonBlank(
                text(relation, "targetResourcePath"),
                text(relation.path("target"), "resourcePath"));
        String runtimeSurfaceInstanceRef = runtimeSurfaceInstanceRef(relation);
        String operationId = text(relation, "operationId");
        JsonNode queryMapping = relation.path("queryMapping");
        String sourceField = text(queryMapping, "sourceField");
        String targetFilterField = text(queryMapping, "targetFilterField");
        String targetPath = text(queryMapping, "targetPath");
        List<String> failures = new ArrayList<>();
        failures.addAll(runtimeFreshnessFailures(runtimeContext));
        List<String> scoreReasons = new ArrayList<>();
        int score = 0;
        if (!StringUtils.hasText(surfaceRef)) {
            failures.add("runtime-surface-required");
        }
        if (!hasRuntimeText(runtimeContext, "activeSurfaceRefs", surfaceRef)) {
            failures.add("runtime-surface-not-active");
        } else {
            score += 20;
            scoreReasons.add("surface-active");
        }
        if (!StringUtils.hasText(operationId) || !hasRuntimeText(runtimeContext, "activeActionRefs", operationId)
                && !hasRuntimeText(runtimeContext, "activeOperationRefs", operationId)) {
            failures.add("runtime-surface-operation-not-active");
        } else {
            score += 20;
            scoreReasons.add("operation-active");
        }
        if (!StringUtils.hasText(sourceField) || !StringUtils.hasText(targetFilterField)) {
            failures.add("runtime-surface-query-mapping-required");
        } else {
            score += 15;
            scoreReasons.add("query-mapping-complete");
        }
        if (StringUtils.hasText(targetPath)) {
            if (!targetPath.matches("^filters\\.[A-Za-z_][A-Za-z0-9_]*$")) {
                failures.add("runtime-surface-target-path-invalid");
            } else if (!("filters." + targetFilterField).equals(targetPath)) {
                failures.add("runtime-surface-target-path-filter-mismatch");
            } else {
                score += 10;
                scoreReasons.add("target-filter-declared-by-query-path");
            }
        }
        JsonNode selectionDigest = selectionDigest(runtimeContext, sourceWidget, surfaceRef, sourceField);
        if (selectionDigest == null || !selectionDigest.isObject()) {
            failures.add("runtime-surface-selection-required");
        } else if (!sourceField.equals(text(selectionDigest, "idField"))) {
            failures.add("runtime-surface-source-field-mismatch");
        } else if (selectionDigest.path("selectedIds").isArray() && selectionDigest.path("selectedIds").size() == 1) {
            score += 25;
            scoreReasons.add("single-selection-compatible");
        } else if (selectionDigest.path("selectedIds").isArray() && selectionDigest.path("selectedIds").size() > 1) {
            failures.add("runtime-surface-multiple-selection-unsupported");
        } else {
            failures.add("runtime-surface-selection-required");
        }
        if (StringUtils.hasText(sourceWidget)) {
            score += 15;
            scoreReasons.add("direct-relation-from-source-widget");
        }
        if (StringUtils.hasText(targetWidget) && !StringUtils.hasText(runtimeSurfaceInstanceRef)) {
            if (runtimeSurfaceTargetWidgetExists(runtimeContext, targetWidget)) {
                score += 5;
                scoreReasons.add("target-widget-declared");
            } else {
                failures.add("runtime-surface-target-widget-not-found");
            }
        } else if (StringUtils.hasText(targetWidget)) {
            score += 5;
            scoreReasons.add("target-widget-declared");
        }
        if (StringUtils.hasText(runtimeSurfaceInstanceRef)) {
            if (runtimeSurfaceInstanceExists(runtimeContext, runtimeSurfaceInstanceRef)) {
                score += 10;
                scoreReasons.add("runtime-surface-instance-declared");
            } else {
                failures.add("runtime-surface-instance-not-found");
            }
        } else if (!StringUtils.hasText(targetWidget)
                && runtimeSurfaceResourcePathAmbiguous(runtimeContext, targetResourcePath)) {
            failures.add("runtime-surface-resource-path-ambiguous");
        }
        return new CandidateAssessment(
                "runtime-surface-candidate:" + firstNonBlank(sourceWidget, "unknown") + "->" + firstNonBlank(surfaceRef, "unknown"),
                surfaceRef,
                score,
                failures.isEmpty(),
                scoreReasons,
                failures);
    }

    private ObjectNode candidateNode(CandidateAssessment assessment, JsonNode relation) {
        ObjectNode candidate = objectMapper.createObjectNode();
        candidate.put("candidateRef", assessment.candidateRef());
        candidate.put("surfaceRef", assessment.surfaceRef());
        copySafeScalar(relation, candidate, "runtimeSurfaceInstanceRef");
        copySafeScalar(relation, candidate, "label");
        if (relation != null && relation.path("semanticAliases").isArray()) {
            candidate.set("semanticAliases", textArray(relation.path("semanticAliases"), 12));
        }
        candidate.put("rank", assessment.accepted() ? 1 : 0);
        candidate.put("status", assessment.accepted() ? "accepted" : "rejected");
        candidate.set("scoreReasons", textArray(assessment.scoreReasons(), 12));
        candidate.set("failureCodes", textArray(assessment.failureCodes(), 12));
        candidate.set("acceptedClaims", acceptedClaimArray(assessment));
        candidate.set("rejectedClaims", rejectedClaimArray(assessment));
        return candidate;
    }

    private ArrayNode acceptedClaimArray(CandidateAssessment assessment) {
        ArrayNode claims = objectMapper.createArrayNode();
        if (!assessment.accepted()) {
            return claims;
        }
        for (String kind : List.of("relation", "queryMapping", "operation", "resource", "selection", "projection")) {
            ObjectNode claim = claims.addObject();
            claim.put("kind", kind);
            claim.put("evidenceRef", assessment.candidateRef() + ":" + kind);
            claim.put("reason", kind + "-accepted");
        }
        return claims;
    }

    private ArrayNode rejectedClaimArray(CandidateAssessment assessment) {
        ArrayNode claims = objectMapper.createArrayNode();
        for (String failure : assessment.failureCodes()) {
            ObjectNode claim = claims.addObject();
            claim.put("kind", "candidate");
            claim.put("evidenceRef", assessment.candidateRef());
            claim.put("reason", failure);
        }
        return claims;
    }

    private JsonNode selectionDigest(ObjectNode runtimeContext, String sourceWidget, String surfaceRef, String sourceField) {
        JsonNode fallback = null;
        JsonNode compatibleFieldFallback = null;
        boolean compatibleFieldAmbiguous = false;
        for (JsonNode component : runtimeContext.path("components")) {
            JsonNode selectionDigest = component.path("snapshot").path("selectionDigest");
            if (!selectionDigest.isObject() || !selectionDigest.path("selectedIds").isArray()
                    || selectionDigest.path("selectedIds").isEmpty()) {
                continue;
            }
            String widgetKey = text(component.path("identity"), "widgetKey");
            if (StringUtils.hasText(sourceWidget) && sourceWidget.equals(widgetKey)) {
                return selectionDigest;
            }
            if (fallback == null && hasText(component.path("affordances").path("activeSurfaceRefs"), surfaceRef)) {
                fallback = selectionDigest;
            }
            if (StringUtils.hasText(sourceField) && sourceField.equals(text(selectionDigest, "idField"))) {
                if (compatibleFieldFallback == null) {
                    compatibleFieldFallback = selectionDigest;
                } else {
                    compatibleFieldAmbiguous = true;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        return compatibleFieldAmbiguous ? null : compatibleFieldFallback;
    }

    private boolean hasRuntimeText(ObjectNode runtimeContext, String arrayName, String expected) {
        if (!StringUtils.hasText(expected)) {
            return false;
        }
        for (JsonNode component : runtimeContext.path("components")) {
            JsonNode affordances = component.path("affordances");
            if (hasText(affordances.path(arrayName), expected)) {
                return true;
            }
        }
        return false;
    }

    private List<String> runtimeFreshnessFailures(ObjectNode runtimeContext) {
        List<String> failures = new ArrayList<>();
        if (runtimeContext == null || !runtimeContext.path("components").isArray()) {
            failures.add("runtime-surface-freshness-components-required");
            return failures;
        }
        Instant freshnessReference = runtimeFreshnessReference(runtimeContext);
        for (JsonNode component : runtimeContext.path("components")) {
            JsonNode lifecycle = component.path("lifecycle");
            if (!lifecycle.isObject()) {
                failures.add("runtime-surface-freshness-lifecycle-required");
                continue;
            }
            if (!lifecycle.path("active").asBoolean(false)) {
                failures.add("runtime-surface-component-inactive");
            }
            if (lifecycle.has("visible") && !lifecycle.path("visible").asBoolean(true)) {
                failures.add("runtime-surface-component-not-visible");
            }
            String capturedAt = text(lifecycle, "capturedAt");
            long ttlMs = lifecycle.path("ttlMs").asLong(-1L);
            if (!StringUtils.hasText(capturedAt) || ttlMs <= 0L || ttlMs > 30_000L) {
                failures.add("runtime-surface-freshness-ttl-invalid");
                continue;
            }
            try {
                Instant captured = Instant.parse(capturedAt);
                if (captured.plusMillis(ttlMs).isBefore(freshnessReference)) {
                    failures.add("runtime-surface-observation-stale");
                }
            } catch (DateTimeParseException ex) {
                failures.add("runtime-surface-freshness-captured-at-invalid");
            }
        }
        return failures.stream().distinct().toList();
    }

    private Instant runtimeFreshnessReference(ObjectNode runtimeContext) {
        String generatedAt = text(runtimeContext, "generatedAt");
        if (StringUtils.hasText(generatedAt)) {
            try {
                return Instant.parse(generatedAt);
            } catch (DateTimeParseException ignored) {
                // Fall through to wall-clock validation when the grounded context has no valid admission time.
            }
        }
        return Instant.now();
    }

    private void addSelectedCandidateFailure(ObjectNode resolution, String failureCode) {
        if (resolution == null || !StringUtils.hasText(failureCode)) {
            return;
        }
        String surfaceRef = resolution.path("selectedCandidateRef").asText("");
        ObjectNode blocked = resolution.withArray("blockedCandidates").addObject();
        blocked.put("candidateRef", resolution.path("selectedCandidateEvidenceRef").asText("runtime-surface-candidate:" + surfaceRef));
        blocked.put("surfaceRef", surfaceRef);
        blocked.put("status", "rejected");
        blocked.set("failureCodes", textArray(List.of(failureCode), 4));
        resolution.remove("selectedCandidateRef");
    }

    private String firstRecordDisplayValue(JsonNode record) {
        for (String field : List.of("funcionarioNome", "nome", "name", "label", "titulo", "title", "evento", "event", "sku", "papel", "status")) {
            String value = text(record, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String compactRecord(JsonNode record) {
        List<String> values = new ArrayList<>();
        if (record != null && record.isObject()) {
            record.properties().forEach(entry -> {
                if (values.size() < 3 && entry.getValue().isValueNode()) {
                    String value = entry.getValue().asText("");
                    if (StringUtils.hasText(value)) {
                        values.add(entry.getKey() + "=" + value);
                    }
                }
            });
        }
        return values.isEmpty() ? "registro governado" : String.join(", ", values);
    }

    private ObjectNode runtimeConsultableContext(AgenticAuthoringTurnStreamRequest request) {
        JsonNode context = request == null || request.contextHints() == null
                ? null
                : request.contextHints().path("groundedRuntimeComponentContext");
        if (context == null || !context.isObject()) {
            return null;
        }
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.put("schemaVersion", "praxis-agentic-authoring-runtime-consultable-context.v1");
        runtime.put("canonicalContext", value(context.path("canonicalContext").asText()));
        runtime.put("trustLevel", value(context.path("trustLevel").asText()));
        copySafeScalar(context, runtime, "generatedAt");
        runtime.set("policy", context.path("policy").isObject()
                ? context.path("policy").deepCopy()
                : objectMapper.createObjectNode());
        runtime.set("availableSurfaces", textArray(context.path("availableSurfaces"), 12));
        runtime.set("allowedOperations", textArray(context.path("allowedOperations"), 16));
        runtime.set("allowedFields", textArray(context.path("allowedFields"), 24));
        runtime.set("acceptedClaims", safeClaimArray(context.path("acceptedClaims"), 40));
        runtime.set("rejectedClaims", safeRejectedClaimArray(context.path("rejectedClaims"), 20));
        runtime.set("evidenceRefs", safeEvidenceRefArray(context.path("evidenceRefs"), 20));
        runtime.set("components", runtimeConsultableComponents(context.path("components"), 12));
        runtime.set("diagnostics", context.path("diagnostics").isObject()
                ? context.path("diagnostics").deepCopy()
                : objectMapper.createObjectNode());
        runtime.put("hasConsultableRuntimeSurface",
                !runtime.path("availableSurfaces").isEmpty()
                        || hasClaimKind(runtime.path("acceptedClaims"), "surface")
                        || hasOperation(runtime.path("allowedOperations"), "dynamicPage.surface.open"));
        runtime.put("requiresReadOnlySurfaceTool", runtime.path("hasConsultableRuntimeSurface").asBoolean(false));
        runtime.put("rawRuntimeValuesCopied", false);
        return runtime;
    }

    private ArrayNode runtimeConsultableComponents(JsonNode components, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (components == null || !components.isArray()) {
            return safe;
        }
        int count = 0;
        for (JsonNode component : components) {
            if (count >= limit || !component.isObject()) {
                break;
            }
            ObjectNode item = safe.addObject();
            item.set("identity", safeObject(component.path("identity"), Set.of(
                    "instanceId", "componentId", "componentType", "widgetKey", "ownerPackage", "routeKey")));
            item.set("refs", safeObject(component.path("refs"), Set.of(
                    "componentMetadataId", "resourcePath", "resourceKey", "pageId", "runtimeSurfaceInstanceRef")));
            item.set("lifecycle", safeObject(component.path("lifecycle"), Set.of(
                    "active", "visible", "focused", "capturedAt", "ttlMs")));
            JsonNode snapshot = component.path("snapshot");
            if (snapshot.isObject()) {
                ObjectNode safeSnapshot = item.putObject("snapshot");
                safeSnapshot.set("selectionDigest", safeObject(snapshot.path("selectionDigest"), Set.of(
                        "selectedCount", "selectedIds", "idField", "fieldRefs", "filterCandidateCount", "truncatedRows")));
                safeSnapshot.set("schemaFieldRefs", textArray(snapshot.path("schemaFieldRefs"), 80));
                safeSnapshot.set("schemaFieldDescriptors", safeSchemaFieldDescriptors(snapshot.path("schemaFieldDescriptors"), 80));
                safeSnapshot.set("omittedFields", textArray(snapshot.path("omittedFields"), 80));
                safeSnapshot.set("redactedFieldRefs", textArray(snapshot.path("redactedFieldRefs"), 80));
                safeSnapshot.set("sensitiveFieldRefs", textArray(snapshot.path("sensitiveFieldRefs"), 80));
                safeSnapshot.set("hiddenFieldRefs", textArray(snapshot.path("hiddenFieldRefs"), 80));
                safeSnapshot.set("relationSurfaceRefs", safeRelationSurfaceRefs(snapshot.path("relationSurfaceRefs"), 40));
            }
            JsonNode affordances = component.path("affordances");
            if (affordances.isObject()) {
                ObjectNode safeAffordances = item.putObject("affordances");
                safeAffordances.set("activeSurfaceRefs", textArray(affordances.path("activeSurfaceRefs"), 40));
                safeAffordances.set("activeActionRefs", textArray(affordances.path("activeActionRefs"), 80));
                safeAffordances.set("activeOperationRefs", textArray(affordances.path("activeOperationRefs"), 80));
            }
            count++;
        }
        return safe;
    }

    private ArrayNode safeRelationSurfaceRefs(JsonNode source, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return safe;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit || !item.isObject()) {
                break;
            }
            ObjectNode ref = safe.addObject();
            copySafeScalar(item, ref, "id");
            copySafeScalar(item, ref, "label");
            copySafeScalar(item, ref, "relation");
            copySafeScalar(item, ref, "operationId");
            copySafeScalar(item, ref, "statePath");
            copySafeScalar(item, ref, "sourceWidget");
            copySafeScalar(item, ref, "targetWidget");
            copySafeScalar(item, ref, "targetResourcePath");
            copySafeScalar(item, ref, "runtimeSurfaceInstanceRef");
            copySafeScalar(item, ref, "targetRuntimeSurfaceInstanceRef");
            copySafeScalar(item, ref, "sourceRuntimeSurfaceInstanceRef");
            copySafeScalar(item, ref, "targetSurface");
            copySafeScalar(item, ref, "surfaceRef");
            copySafeScalar(item, ref, "queryContextPath");
            ref.set("source", safeObject(item.path("source"), Set.of(
                    "widget", "componentType", "port", "childWidgetKey", "runtimeSurfaceInstanceRef")));
            ref.set("target", safeObject(item.path("target"), Set.of(
                    "widget", "componentType", "port", "childWidgetKey", "resourcePath", "runtimeSurfaceInstanceRef")));
            ref.set("queryMapping", safeObject(item.path("queryMapping"), Set.of(
                    "sourceField", "targetFilterField", "targetPath", "valueSource")));
            ref.set("semanticAliases", safeRuntimeSurfaceSemanticAliases(item));
            count++;
        }
        return safe;
    }

    private ArrayNode safeRuntimeSurfaceSemanticAliases(JsonNode relation) {
        ArrayNode aliases = objectMapper.createArrayNode();
        Set<String> values = new LinkedHashSet<>();
        addSafeRuntimeSurfaceAlias(values, text(relation, "surfaceRef"));
        addSafeRuntimeSurfaceAlias(values, text(relation, "targetWidget"));
        addSafeRuntimeSurfaceAlias(values, text(relation.path("target"), "widget"));
        String targetResourcePath = firstNonBlank(
                text(relation, "targetResourcePath"),
                text(relation.path("target"), "resourcePath"));
        addSafeRuntimeSurfaceAlias(values, targetResourcePath);
        for (String segment : value(targetResourcePath).split("[/._:-]+")) {
            addSafeRuntimeSurfaceAlias(values, segment);
        }
        if (values.stream().anyMatch(alias -> alias.contains("timeline") || alias.contains("evento"))) {
            values.add("timeline");
            values.add("linha do tempo");
            values.add("eventos");
        }
        if (values.stream().anyMatch(alias -> alias.contains("team") || alias.contains("participante"))) {
            values.add("team");
            values.add("equipe");
            values.add("participantes");
        }
        int count = 0;
        for (String alias : values) {
            if (count >= 12) {
                break;
            }
            aliases.add(alias);
            count++;
        }
        return aliases;
    }

    private void addSafeRuntimeSurfaceAlias(Set<String> aliases, String value) {
        String normalized = value(value)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        if (normalized.length() >= 3 && normalized.length() <= 60) {
            aliases.add(normalized);
        }
    }

    private ArrayNode safeSchemaFieldDescriptors(JsonNode source, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return safe;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit || !item.isObject()) {
                break;
            }
            ObjectNode descriptor = objectMapper.createObjectNode();
            String fieldRef = firstNonBlank(
                    text(item, "fieldRef"),
                    text(item, "ref"),
                    text(item, "field"),
                    text(item, "path"),
                    text(item, "name"));
            if (!safeIdentifier(fieldRef)) {
                continue;
            }
            descriptor.put("fieldRef", fieldRef);
            copySafeScalar(item, descriptor, "fieldType");
            copySafeScalar(item, descriptor, "valueType");
            copySafeScalar(item, descriptor, "dataType");
            copySafeScalar(item, descriptor, "semanticType");
            copySafeScalar(item, descriptor, "type");
            copySafeScalar(item, descriptor, "format");
            copySafeScalar(item, descriptor, "controlType");
            safe.add(descriptor);
            count++;
        }
        return safe;
    }

    private ArrayNode safeClaimArray(JsonNode claims, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (claims == null || !claims.isArray()) {
            return safe;
        }
        int count = 0;
        for (JsonNode claim : claims) {
            if (count >= limit || !claim.isObject()) {
                break;
            }
            ObjectNode item = safe.addObject();
            copySafeScalar(claim, item, "kind");
            copySafeScalar(claim, item, "ref");
            copySafeScalar(claim, item, "digest");
            copySafeScalar(claim, item, "observed");
            count++;
        }
        return safe;
    }

    private ArrayNode safeRejectedClaimArray(JsonNode claims, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (claims == null || !claims.isArray()) {
            return safe;
        }
        int count = 0;
        for (JsonNode claim : claims) {
            if (count >= limit || !claim.isObject()) {
                break;
            }
            ObjectNode item = safe.addObject();
            copySafeScalar(claim, item, "reason");
            copySafeScalar(claim, item, "message");
            copySafeScalar(claim, item, "instanceId");
            copySafeScalar(claim, item, "componentId");
            copySafeScalar(claim, item, "schemaVersion");
            count++;
        }
        return safe;
    }

    private ArrayNode safeEvidenceRefArray(JsonNode refs, int limit) {
        return safeObjectArray(refs, Set.of(
                "source", "instanceId", "componentId", "componentMetadataId",
                "resourcePath", "resourceKey", "pageId", "snapshotHash"), limit);
    }

    private ArrayNode safeObjectArray(JsonNode source, Set<String> allowedKeys, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return safe;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit || !item.isObject()) {
                break;
            }
            safe.add(safeObject(item, allowedKeys));
            count++;
        }
        return safe;
    }

    private ObjectNode safeObject(JsonNode source, Set<String> allowedKeys) {
        ObjectNode safe = objectMapper.createObjectNode();
        if (source == null || !source.isObject() || allowedKeys == null) {
            return safe;
        }
        allowedKeys.forEach(key -> copySafeScalar(source, safe, key));
        return safe;
    }

    private ArrayNode textArray(JsonNode source, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (source == null || !source.isArray()) {
            return safe;
        }
        int count = 0;
        for (JsonNode item : source) {
            if (count >= limit) {
                break;
            }
            String text = item.isTextual() ? item.asText().trim() : "";
            if (StringUtils.hasText(text)) {
                safe.add(text);
                count++;
            }
        }
        return safe;
    }

    private ArrayNode textArray(List<String> source, int limit) {
        ArrayNode safe = objectMapper.createArrayNode();
        if (source == null) {
            return safe;
        }
        for (String item : source) {
            if (safe.size() >= limit) {
                break;
            }
            if (StringUtils.hasText(item)) {
                safe.add(item);
            }
        }
        return safe;
    }

    private boolean hasText(JsonNode source, String expected) {
        if (source == null || !source.isArray() || !StringUtils.hasText(expected)) {
            return false;
        }
        for (JsonNode item : source) {
            if (expected.equals(item.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private void copySafeScalar(JsonNode source, ObjectNode target, String fieldName) {
        JsonNode value = source == null ? null : source.path(fieldName);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            target.put(fieldName, value.asText());
        } else if (value.isBoolean()) {
            target.put(fieldName, value.asBoolean());
        } else if (value.isInt() || value.isLong()) {
            target.put(fieldName, value.asLong());
        } else if (value.isArray()) {
            target.set(fieldName, textArray(value, 80));
        }
    }

    private boolean hasClaimKind(JsonNode claims, String kind) {
        if (claims == null || !claims.isArray()) {
            return false;
        }
        for (JsonNode claim : claims) {
            if (kind.equals(claim.path("kind").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOperation(JsonNode operations, String operation) {
        if (operations == null || !operations.isArray()) {
            return false;
        }
        for (JsonNode item : operations) {
            if (operation.equals(item.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private List<String> texts(JsonNode source, int limit) {
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

    private String firstText(JsonNode source) {
        if (source == null || !source.isArray()) {
            return "";
        }
        for (JsonNode item : source) {
            String text = item.asText("");
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private List<String> selectedIds(JsonNode runtimeContext) {
        List<String> ids = new ArrayList<>();
        JsonNode components = runtimeContext.path("components");
        if (!components.isArray()) {
            return ids;
        }
        for (JsonNode component : components) {
            JsonNode selectedIds = component.path("snapshot").path("selectionDigest").path("selectedIds");
            if (!selectedIds.isArray()) {
                continue;
            }
            for (JsonNode id : selectedIds) {
                if (ids.size() >= 8) {
                    return ids;
                }
                String text = id.asText("");
                if (StringUtils.hasText(text)) {
                    ids.add(text);
                }
            }
        }
        return ids;
    }

    private JsonNode presentationAffordanceDiscoveryEvidence(AgenticAuthoringTurnStreamRequest request) {
        if (toolRegistry == null || request == null) {
            return null;
        }
        JsonNode hints = request.contextHints();
        String componentId = firstNonBlank(
                text(hints, "targetComponentId"),
                text(hints, "selectedComponentId"),
                text(hints, "componentId"),
                text(hints, "surfaceWidgetId"),
                request.targetComponentId());
        if (!"praxis-table".equals(componentId)) {
            return null;
        }
        String targetField = firstNonBlank(
                text(hints, "targetField"),
                text(hints, "columnField"),
                text(hints, "selectedField"),
                text(hints, "field"));
        String dataType = firstNonBlank(
                text(hints, "outputType"),
                text(hints, "dataType"),
                text(hints, "inferredType"),
                fieldDescriptorText(hints, targetField, "outputType"),
                fieldDescriptorText(hints, targetField, "type"));
        AgenticAuthoringToolResult result = toolRegistry.execute(
                new AgenticAuthoringToolCall(
                        AgenticAuthoringToolRegistry.DISCOVER_PRESENTATION_AFFORDANCES,
                        "component_authoring",
                        new PresentationAffordanceDiscoveryToolRequest(
                                null,
                                componentId,
                                firstNonBlank(text(hints, "targetKind"), "column"),
                                targetField,
                                text(hints, "columnField"),
                                dataType,
                                text(hints, "outputType"),
                                text(hints, "inferredType"),
                                request.userPrompt(),
                                20)),
                null,
                "retrieveEvidence");
        if (!result.valid() || !(result.payload() instanceof JsonNode payload)) {
            return null;
        }
        return payload;
    }

    private String fieldDescriptorText(JsonNode hints, String targetField, String propertyName) {
        if (!StringUtils.hasText(targetField) || hints == null || !hints.isObject()) {
            return "";
        }
        for (String arrayName : List.of("schemaFields", "fieldCatalog", "fieldMetadata", "columns")) {
            JsonNode array = hints.path(arrayName);
            if (!array.isArray()) {
                continue;
            }
            for (JsonNode item : array) {
                if (targetField.equals(value(item.path("field").asText()))
                        || targetField.equals(value(item.path("name").asText()))
                        || targetField.equals(value(item.path("path").asText()))) {
                    String text = text(item, propertyName);
                    if (StringUtils.hasText(text)) {
                        return text;
                    }
                }
            }
        }
        return "";
    }

    private ArrayNode componentCatalogSummary(
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            int maxCatalogs) {
        ArrayNode catalogs = objectMapper.createArrayNode();
        if (componentCapabilities == null || componentCapabilities.catalogs() == null) {
            return catalogs;
        }
        int catalogCount = 0;
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            if (catalog == null || !StringUtils.hasText(catalog.componentId()) || catalogCount >= maxCatalogs) {
                continue;
            }
            ObjectNode item = catalogs.addObject();
            item.put("componentId", catalog.componentId());
            ArrayNode capabilities = item.putArray("capabilities");
            int capabilityCount = 0;
            for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : nullToEmpty(catalog.capabilities())) {
                if (capability == null || capabilityCount >= 8) {
                    continue;
                }
                ObjectNode capabilityNode = capabilities.addObject();
                capabilityNode.put("id", value(capability.id()));
                capabilityNode.put("changeKind", value(capability.changeKind()));
                capabilityNode.set("userTerms", objectMapper.valueToTree(limit(capability.triggerTerms(), 10)));
                capabilityNode.set("examples", objectMapper.valueToTree(examplePrompts(capability.examples(), 2)));
                capabilityCount++;
            }
            catalogCount++;
        }
        return catalogs;
    }

    private Optional<AgenticAuthoringConsultativeAnswer> explicitNoMaterializationFallback(
            String userPrompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            AgenticAuthoringConsultativeApiCatalogProjection projection) {
        if (projection == null || !projection.hasResources()) {
            Optional<String> capabilityMessage = componentCapabilityFallbackMessage(userPrompt, componentCapabilities);
            if (capabilityMessage.isPresent()) {
                List<String> warnings = new ArrayList<>(warnings("component_capability", projection));
                warnings.add("llm-consultative-answer-fallback-used");
                return Optional.of(new AgenticAuthoringConsultativeAnswer(
                        "component_capability",
                        changeKind("component_capability"),
                        capabilityMessage.get(),
                        projection,
                        warnings.stream().distinct().toList()));
            }
        }
        String category = projection != null && projection.hasResources() ? "domain_api" : "component_catalog";
        String message = fallbackMessage(category, projection, componentCapabilities);
        if (message.isBlank()) {
            return Optional.empty();
        }
        List<String> warnings = new ArrayList<>(warnings(category, projection));
        warnings.add("llm-consultative-answer-fallback-used");
        return Optional.of(new AgenticAuthoringConsultativeAnswer(
                category,
                changeKind(category),
                message,
                projection,
                warnings.stream().distinct().toList()));
    }

    private String fallbackMessage(
            String category,
            AgenticAuthoringConsultativeApiCatalogProjection projection,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (projection != null && StringUtils.hasText(projection.assistantMessage())) {
            return projection.assistantMessage();
        }
        if ("component_catalog".equals(category) || "platform_guidance".equals(category)) {
            String catalogMessage = componentCatalogFallbackMessage(componentCapabilities);
            if (StringUtils.hasText(catalogMessage)) {
                return catalogMessage;
            }
            return "Aqui você pode conversar comigo sobre a intenção da tela e eu ajudo a escolher componentes governados como tabela, formulário, gráfico, filtros, abas ou painel. Quando você pedir para criar, eu uso o catálogo de componentes e as fontes confirmadas antes de materializar uma prévia.";
        }
        if ("component_capability".equals(category)) {
            return "Consigo explicar as opções do componente em linguagem natural e separar o que é decisão de experiência do que é detalhe de configuração. Para aplicar qualquer mudança, eu valido o componente e gero uma prévia governada antes de salvar.";
        }
        return "";
    }

    private String guardedDomainAnswer(
            String userPrompt,
            String category,
            AgenticAuthoringConsultativeApiCatalogProjection projection,
            String answer) {
        if (!"domain_api".equals(category) || projection == null || !projection.hasResources()) {
            return answer;
        }
        String unsupportedDomainMessage = AgenticAuthoringConsultativeGroundingAlignment.unsupportedDomainMessage(
                userPrompt,
                projection.resources());
        return StringUtils.hasText(unsupportedDomainMessage) ? unsupportedDomainMessage : answer;
    }

    private boolean shouldUseGroundedProjectionAnswer(AgenticAuthoringConsultativeApiCatalogProjection projection) {
        if (projection == null || !projection.hasResources() || !StringUtils.hasText(projection.assistantMessage())) {
            return false;
        }
        return projection.warnings() != null
                && projection.warnings().contains("domain-api-consultative-compact-projection-used");
    }

    private String componentCatalogFallbackMessage(AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (componentCapabilities == null || componentCapabilities.catalogs() == null
                || componentCapabilities.catalogs().isEmpty()) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        message.append("Posso te orientar antes de criar qualquer coisa. Neste contexto, o catálogo governado expõe componentes como:\n\n");
        int count = 0;
        Set<String> emittedLabels = new LinkedHashSet<>();
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            if (catalog == null || !StringUtils.hasText(catalog.componentId()) || count >= 24) {
                continue;
            }
            String label = componentDisplayName(catalog.componentId());
            if (!emittedLabels.add(label)) {
                continue;
            }
            message.append("- ")
                    .append(label)
                    .append(": ")
                    .append(componentCapabilitySummary(catalog))
                    .append('\n');
            count++;
        }
        message.append("\nQuando você decidir criar ou alterar algo, eu cruzo a intenção com os dados confirmados do domínio e gero uma prévia governada para revisão.");
        return sanitizeUserFacingAnswer(message.toString());
    }

    private Optional<String> componentCapabilityFallbackMessage(
            String userPrompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        Optional<ComponentCapabilityMatch> match = bestComponentCapabilityMatch(userPrompt, componentCapabilities);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        String componentLabel = componentDisplayName(match.get().componentId());
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability = match.get().capability();
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example =
                nullToEmpty(capability.examples()).stream().filter(Objects::nonNull).findFirst().orElse(null);
        String intent = example == null ? "" : value(example.intent());
        List<String> hints = example == null ? List.of() : nullToEmpty(example.configHints()).stream()
                .filter(StringUtils::hasText)
                .map(this::humanizeConfigHint)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(5)
                .toList();
        StringBuilder message = new StringBuilder();
        message.append("Sim. No Praxis, isso é uma capacidade governada do componente ")
                .append(componentLabel)
                .append(".");
        if (!intent.isBlank()) {
            message.append(" Em termos de produto, a intenção é ")
                    .append(lowercaseFirst(stripTrailingSentencePunctuation(intent)))
                    .append(".");
        }
        if (!hints.isEmpty()) {
            message.append(" Para habilitar bem, a decisão precisa cobrir ")
                    .append(humanJoin(hints))
                    .append(".");
        }
        message.append(" Eu não criei nada agora; quando você pedir para aplicar, eu valido a tabela escolhida e preparo a prévia governada para revisão.");
        return Optional.of(sanitizeUserFacingAnswer(message.toString()));
    }

    private Optional<ComponentCapabilityMatch> bestComponentCapabilityMatch(
            String userPrompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        String normalizedPrompt = normalizeForIntentConstraint(userPrompt);
        if (normalizedPrompt.isBlank()
                || componentCapabilities == null
                || componentCapabilities.catalogs() == null) {
            return Optional.empty();
        }
        ComponentCapabilityMatch best = null;
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
                continue;
            }
            for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : nullToEmpty(catalog.capabilities())) {
                if (capability == null) {
                    continue;
                }
                int score = componentCapabilityScore(normalizedPrompt, catalog.componentId(), capability);
                if (score >= 3 && (best == null || score > best.score())) {
                    best = new ComponentCapabilityMatch(catalog.componentId(), capability, score);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private int componentCapabilityScore(
            String normalizedPrompt,
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability) {
        int score = 0;
        String componentLabel = normalizeForIntentConstraint(componentDisplayName(componentId));
        if (!componentLabel.isBlank() && normalizedPrompt.contains(componentLabel)) {
            score += 2;
        }
        for (String term : nullToEmpty(capability.triggerTerms())) {
            String normalizedTerm = normalizeForIntentConstraint(term);
            if (!normalizedTerm.isBlank() && normalizedPrompt.contains(normalizedTerm)) {
                score += normalizedTerm.contains(" ") ? 3 : 2;
            }
        }
        String examples = nullToEmpty(capability.examples()).stream()
                .filter(Objects::nonNull)
                .map(example -> value(example.prompt()) + " " + value(example.intent()))
                .reduce("", (left, right) -> left + " " + right);
        String normalizedEvidence = normalizeForIntentConstraint(value(capability.id()) + " "
                + value(capability.changeKind()) + " " + examples);
        for (String token : normalizedPrompt.split("\\s+")) {
            if (token.length() >= 5 && normalizedEvidence.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private String humanizeConfigHint(String hint) {
        String normalized = normalizeForIntentConstraint(hint);
        if (normalized.contains("selection enabled")) {
            return "seleção de linhas habilitada";
        }
        if (normalized.contains("toolbar") || normalized.contains("acoes em massa") || normalized.contains("actions")) {
            return "uma ação visível na barra da tabela ou nas ações em massa";
        }
        if (normalized.contains("export enabled")) {
            return "exportação habilitada";
        }
        if (normalized.contains("formats") || normalized.contains("csv") || normalized.contains("xlsx")) {
            return "formatos permitidos, como CSV ou XLSX";
        }
        if (normalized.contains("scope selected")) {
            return "escopo limitado às linhas selecionadas";
        }
        if (normalized.contains("headers")) {
            return "inclusão dos cabeçalhos no arquivo";
        }
        return "";
    }

    private String humanJoin(List<String> values) {
        List<String> safe = values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .toList();
        if (safe.isEmpty()) {
            return "";
        }
        if (safe.size() == 1) {
            return safe.get(0);
        }
        return String.join(", ", safe.subList(0, safe.size() - 1)) + " e " + safe.get(safe.size() - 1);
    }

    private String lowercaseFirst(String value) {
        String text = value(value);
        if (text.isBlank()) {
            return "";
        }
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    private String stripTrailingSentencePunctuation(String value) {
        return value(value).replaceAll("[.?!]+$", "").trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String candidate : values) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !node.isObject() || !StringUtils.hasText(fieldName)) {
            return "";
        }
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText().trim();
        }
        return value.isNumber() || value.isBoolean() ? value.asText() : "";
    }

    private String componentDisplayName(String componentId) {
        String normalized = value(componentId).toLowerCase(Locale.ROOT);
        if (normalized.contains("table")) {
            return "Tabela";
        }
        if (normalized.contains("chart")) {
            return "Gráfico";
        }
        if (normalized.contains("filter")) {
            return "Filtro";
        }
        if (normalized.contains("dynamic-form") || normalized.contains("manual-form")
                || normalized.contains("editorial-form") || normalized.contains("form")) {
            return "Formulário";
        }
        if (normalized.contains("field")) {
            return "Campos dinâmicos";
        }
        if (normalized.contains("expansion") || normalized.contains("tab")) {
            return "Seções expansíveis e abas";
        }
        String label = normalized.replace("praxis-", "").replace("pdx-", "").replace('-', ' ').trim();
        return label.isBlank() ? value(componentId) : capitalizeWords(label);
    }

    private String componentCapabilitySummary(
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog) {
        String componentId = catalog == null ? "" : value(catalog.componentId()).toLowerCase(Locale.ROOT);
        if (componentId.contains("table")) {
            return "serve para listas, consultas, comparação de registros, seleção de linhas, ações em massa e exportação.";
        }
        if (componentId.contains("chart")) {
            return "serve para visualizar métricas, distribuições, tendências e comparações quando houver campos confirmados para agrupar ou medir.";
        }
        if (componentId.contains("filter")) {
            return "serve para pesquisar e refinar dados antes de alimentar uma tabela, um gráfico ou uma área de detalhe.";
        }
        if (componentId.contains("dynamic-form") || componentId.contains("manual-form")
                || componentId.contains("editorial-form") || componentId.contains("form")) {
            return "serve para captura, edição ou revisão de informações, sempre respeitando campos e regras confirmadas.";
        }
        if (componentId.contains("field")) {
            return "serve para adaptar campos visíveis, rótulos e organização da experiência sem transformar isso em regra de negócio escondida.";
        }
        if (componentId.contains("expansion") || componentId.contains("tab")) {
            return "serve para dividir uma tela em áreas navegáveis, abas ou seções expansíveis quando o volume de informação pede organização.";
        }
        return "serve quando a intenção da tela combina com uma capacidade governada publicada para esse componente.";
    }

    private String capitalizeWords(String value) {
        String[] parts = value(value).split("\\s+");
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                label.append(part.substring(1));
            }
        }
        return label.toString();
    }

    private ParsedConsultativeAnswer parseConsultativeAnswer(String generated) {
        String text = value(generated);
        if (text.startsWith("```")) {
            text = text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        if (text.equalsIgnoreCase("NOT_CONSULTATIVE")) {
            return new ParsedConsultativeAnswer(false, "none", "");
        }
        String category = "";
        String answer = "";
        String[] lines = text.split("\\R", -1);
        boolean inAnswer = false;
        StringBuilder answerBuilder = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inAnswer && trimmed.toUpperCase(Locale.ROOT).startsWith("CONSULTATIVE_CATEGORY:")) {
                category = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                continue;
            }
            if (trimmed.equalsIgnoreCase("ANSWER:")) {
                inAnswer = true;
                continue;
            }
            if (inAnswer) {
                answerBuilder.append(line).append('\n');
            }
        }
        answer = answerBuilder.toString().trim();
        if (answer.isBlank() && !category.isBlank()) {
            answer = text;
        }
        return new ParsedConsultativeAnswer(!answer.isBlank(), category, answer);
    }

    private List<String> warnings(String category, AgenticAuthoringConsultativeApiCatalogProjection projection) {
        List<String> warnings = new ArrayList<>();
        warnings.add("consultative-fast-path-used");
        warnings.add("llm-consultative-intent-used");
        if (projection != null && projection.hasResources()) {
            warnings.add("domain-api-consultative-projection-used");
            warnings.addAll(projection.warnings() == null ? List.of() : projection.warnings());
        }
        if (!"domain_api".equals(category)) {
            warnings.add("component-capability-catalog-used");
        }
        return warnings.stream().distinct().toList();
    }

    private String changeKind(String category) {
        return switch (category) {
            case "domain_api" -> "answer_api_catalog_question";
            case "component_capability" -> "answer_component_capability_question";
            default -> "answer_component_catalog_question";
        };
    }

    private String category(String value) {
        String normalized = value(value).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "domain_api", "component_catalog", "component_capability", "platform_guidance" -> normalized;
            default -> "platform_guidance";
        };
    }

    private boolean explicitlyForbidsMaterialization(String prompt) {
        String text = " " + normalizeForIntentConstraint(prompt) + " ";
        return text.contains(" sem criar nada ")
                || text.contains(" sem criar qualquer coisa ")
                || text.contains(" sem montar nada ")
                || text.contains(" sem montar qualquer coisa ")
                || text.contains(" sem materializar nada ")
                || text.contains(" sem materializar qualquer coisa ")
                || text.contains(" sem pre visualizar ")
                || text.contains(" sem pre visualizacao ")
                || text.contains(" nao crie nada ")
                || text.contains(" nao crie qualquer coisa ")
                || text.contains(" nao cria nada ")
                || text.contains(" nao cria qualquer coisa ")
                || text.contains(" nao criar nada ")
                || text.contains(" nao criar qualquer coisa ")
                || text.contains(" nao monte nada ")
                || text.contains(" nao monte qualquer coisa ")
                || text.contains(" nao montar nada ")
                || text.contains(" nao montar qualquer coisa ")
                || text.contains(" nao materialize nada ")
                || text.contains(" nao materialize qualquer coisa ")
                || text.contains(" nao materializar nada ")
                || text.contains(" nao materializar qualquer coisa ")
                || text.contains(" nao gere previa ")
                || text.contains(" nao gerar previa ")
                || text.contains(" do not create anything ")
                || text.contains(" do not create anything yet ")
                || text.contains(" don't create anything ")
                || text.contains(" don't create anything yet ")
                || text.contains(" without creating anything ")
                || text.contains(" no preview ");
    }

    private boolean clearlyRequestsMaterialization(String prompt) {
        String text = normalizeForIntentConstraint(prompt);
        if (text.isBlank() || startsLikeConsultativeQuestion(text)) {
            return false;
        }
        return startsWithAny(text,
                "crie ",
                "criar ",
                "monte ",
                "montar ",
                "gere ",
                "gerar ",
                "adicione ",
                "adicionar ",
                "inclua ",
                "incluir ",
                "remova ",
                "remover ",
                "altere ",
                "alterar ",
                "configure ",
                "configurar ",
                "habilite ",
                "habilitar ",
                "create ",
                "build ",
                "generate ",
                "add ",
                "remove ",
                "update ",
                "configure ",
                "enable ")
                || isArtifactSpecification(text)
                || isImplicitMaterializationRequest(text);
    }

    private boolean isImplicitMaterializationRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean exploratory = text.contains("quero saber ")
                || text.contains("gostaria de saber ")
                || text.contains("preciso saber ")
                || text.contains("como criar ")
                || text.contains("como crio ")
                || text.contains("como montar ")
                || text.contains("como faco ")
                || text.contains("como fazer ")
                || text.contains("posso criar ")
                || text.contains("da para criar ")
                || text.contains("daria para criar ")
                || text.contains("quais dashboards ")
                || text.contains("quais paineis ")
                || text.contains("quais opcoes ")
                || text.contains("what can i create ")
                || text.contains("how to create ");
        if (exploratory) {
            return false;
        }
        boolean asksForOutcome = containsAnyToken(text,
                "quero",
                "preciso",
                "gostaria",
                "necessito",
                "acompanhar",
                "monitorar",
                "controlar",
                "visualizar",
                "want",
                "need");
        boolean dashboardLike = containsAnyToken(text,
                "dashboard",
                "painel",
                "overview",
                "kpi",
                "indicador",
                "indicadores",
                "resumo",
                "sumario")
                || text.contains("visao geral")
                || text.contains("visao 360");
        return asksForOutcome && dashboardLike;
    }

    private boolean isDomainAvailabilityQuestion(String prompt) {
        String text = " " + normalizeForIntentConstraint(prompt) + " ";
        boolean asksQuestion = text.contains(" ? ")
                || startsLikeConsultativeQuestion(text.trim())
                || text.contains(" da pra ")
                || text.contains(" da para ")
                || text.contains(" da p ")
                || text.contains(" tem ")
                || text.contains(" existe ")
                || text.contains(" existem ")
                || text.contains(" posso ");
        if (!asksQuestion) {
            return false;
        }
        boolean asksAvailability = text.contains(" tem esses dados ")
                || text.contains(" tem estes dados ")
                || text.contains(" tem esses dado ")
                || text.contains(" tem dados ")
                || text.contains(" esses dados ")
                || text.contains(" estes dados ")
                || text.contains(" nesse host ")
                || text.contains(" neste host ")
                || text.contains(" esse host ")
                || text.contains(" este host ")
                || text.contains(" da pra fazer ")
                || text.contains(" da para fazer ")
                || text.contains(" consigo fazer ")
                || text.contains(" posso fazer ")
                || text.contains(" posso criar ")
                || text.contains(" que dados tem ")
                || text.contains(" quais dados tem ")
                || text.contains(" que dados existem ")
                || text.contains(" quais dados existem ")
                || text.contains(" que apis existem ")
                || text.contains(" quais apis existem ")
                || text.contains(" que apis e dados existem ")
                || text.contains(" quais apis e dados existem ")
                || text.contains(" que dados e apis existem ")
                || text.contains(" quais dados e apis existem ")
                || text.contains(" que recursos existem ")
                || text.contains(" quais recursos existem ")
                || text.contains(" dados que existem ")
                || text.contains(" dados disponiveis ")
                || text.contains(" dados disponíveis ")
                || text.contains(" fontes de dados ")
                || text.contains(" existe api ")
                || text.contains(" existem api ")
                || text.contains(" existe dados ")
                || text.contains(" existem dados ");
        if (!asksAvailability && asksWhichGovernedDataCanFeedAuthoring(text)) {
            asksAvailability = true;
        }
        if (!asksAvailability) {
            return false;
        }
        return text.contains(" dados ")
                || text.contains(" dado ")
                || text.contains(" api ")
                || text.contains(" apis ")
                || text.contains(" dominio ")
                || text.contains(" domínio ")
                || text.contains(" recurso ")
                || text.contains(" recursos ")
                || mentionsAuthorableArtifact(text);
    }

    boolean shouldPreferGovernedCatalogAvailabilityAnswer(AgenticAuthoringTurnStreamRequest request) {
        if (request == null || clearlyRequestsMaterialization(request.userPrompt())) {
            return false;
        }
        return isDomainAvailabilityQuestion(request.userPrompt());
    }

    private boolean asksWhichGovernedDataCanFeedAuthoring(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean asksWhichData = text.contains(" quais dados ")
                || text.contains(" que dados ")
                || text.contains(" quais tipos de dados ")
                || text.contains(" que tipos de dados ")
                || text.contains(" com quais dados ")
                || text.contains(" usando quais dados ");
        if (!asksWhichData) {
            return false;
        }
        boolean asksUseOrFit = text.contains(" posso usar ")
                || text.contains(" posso utilizar ")
                || text.contains(" posso aproveitar ")
                || text.contains(" podem usar ")
                || text.contains(" podem utilizar ")
                || text.contains(" podem ter ")
                || text.contains(" pode ter ")
                || text.contains(" da para usar ")
                || text.contains(" da pra usar ")
                || text.contains(" consigo usar ")
                || text.contains(" existem para ")
                || text.contains(" tem para ");
        return asksUseOrFit && mentionsAuthorableArtifact(text);
    }

    private boolean isComponentCatalogQuestion(String prompt) {
        String text = " " + normalizeForIntentConstraint(prompt) + " ";
        boolean asksAboutComponents = text.contains(" componente ")
                || text.contains(" componentes ")
                || text.contains(" widget ")
                || text.contains(" widgets ")
                || text.contains(" catalogo ")
                || text.contains(" catálogo ");
        boolean asksWhatCanCreate = text.contains(" quais ")
                || text.contains(" que ")
                || text.contains(" o que ")
                || text.contains(" posso criar ")
                || text.contains(" podem ser criados ")
                || text.contains(" da pra criar ")
                || text.contains(" da para criar ");
        return asksAboutComponents && asksWhatCanCreate;
    }

    private boolean isArtifactSpecification(String text) {
        return mentionsAuthorableArtifact(text)
                && (mentionsDataBinding(text) || mentionsScopedArtifactConstraint(text));
    }

    private boolean mentionsAuthorableArtifact(String text) {
        return containsAnyToken(text,
                "grafico",
                "graficos",
                "chart",
                "charts",
                "tabela",
                "table",
                "formulario",
                "form",
                "dashboard",
                "painel",
                "kpi",
                "indicador",
                "aba",
                "abas",
                "tabs");
    }

    private boolean mentionsDataBinding(String text) {
        return containsAnyToken(text,
                "por",
                "campo",
                "fonte",
                "dados",
                "api",
                "metric",
                "metrica",
                "dimension",
                "dimensao",
                "field",
                "source");
    }

    private boolean mentionsScopedArtifactConstraint(String text) {
        return containsAnyToken(text,
                "apenas",
                "somente",
                "so",
                "sem",
                "without",
                "nao",
                "no");
    }

    private boolean containsAnyToken(String text, String... tokens) {
        String padded = " " + text + " ";
        for (String token : tokens) {
            if (padded.contains(" " + token + " ")) {
                return true;
            }
        }
        return false;
    }

    private boolean startsLikeConsultativeQuestion(String text) {
        return startsWithAny(text,
                "como ",
                "quais ",
                "qual ",
                "o que ",
                "que ",
                "posso ",
                "explique ",
                "me explique ",
                "antes de ",
                "sem criar ",
                "sem montar ",
                "without creating ",
                "what ",
                "which ",
                "how ",
                "can i ",
                "could i ");
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForIntentConstraint(String value) {
        String text = value(value).toLowerCase(Locale.ROOT);
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return text.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String safeAnswer(String generated, String fallback) {
        String message = value(generated);
        if (message.startsWith("```")) {
            message = message.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        if (message.isBlank()) {
            message = value(fallback);
        }
        message = sanitizeUserFacingAnswer(message);
        return message.length() <= 3200 ? message : message.substring(0, 3200).trim();
    }

    private String sanitizeUserFacingAnswer(String message) {
        String sanitized = value(message)
                .replaceAll("(?i)\\s*\\((?:proje[cç][aã]o|projection) compacta\\)", "")
                .replaceAll("(?i)(?:proje[cç][aã]o|projection) compacta", "informações resumidas")
                .replaceAll("(?i)\\bresourceKey\\b", "identificador técnico")
                .replaceAll("(?i)\\bsubmitUrl\\b", "endereço de envio")
                .replaceAll("(?i)\\bsourceRefs\\b", "referências")
                .replaceAll("(?i)\\bpraxis-table\\b", "tabela")
                .replaceAll("(?i)\\bpraxis-chart\\b", "gráfico")
                .replaceAll("(?i)\\bpraxis-filter\\b", "filtro")
                .replaceAll("(?i)\\bpraxis-dynamic-form\\b", "formulário")
                .replaceAll("(?i)\\bpraxis-manual-form\\b", "formulário manual")
                .replaceAll("(?i)\\bwarning codes?\\b", "avisos internos")
                .replaceAll("(?i)\\binternal warning codes?\\b", "avisos internos")
                .replaceAll("(?i)\\bschema\\b", "campos confirmados")
                .replaceAll("(?i)\\besquema\\b", "lista de campos")
                .replaceAll("(?i)\\bcat[aá]logo capturado\\b", "informações disponíveis")
                .replaceAll("(?i)\\bcat[aá]logo retornado\\b", "informações disponíveis")
                .replaceAll("(?i)\\bendpoints?\\b", "recursos técnicos")
                .replaceAll("(?i)\\bserver-side\\b", "no servidor")
                .replaceAll("(?i)\\bdownstream\\b", "posteriores")
                .replaceAll("(?i)\\b/api/[\\w/.-]+\\b", "recurso técnico");
        sanitized = sanitized
                .replaceAll("(?i)\\s*-?\\s*Aviso:\\s*[^.?!]*(?:informa[cç][oõ]es resumidas|avisos internos)[^.?!]*[.?!]", "")
                .replaceAll("(?i)\\buma informa[cç][oõ]es resumidas\\b", "informações resumidas")
                .replaceAll("(?i)\\busou uma informa[cç][oõ]es resumidas\\b", "trouxe informações resumidas")
                .replaceAll("(?i)\\bdomain-api-[a-z0-9-]+\\b", "")
                .replaceAll("(?i)\\bconsultative-[a-z0-9-]+\\b", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (sanitized.endsWith("?")) {
            int lastBreak = Math.max(sanitized.lastIndexOf(". "), Math.max(sanitized.lastIndexOf("\\n"), sanitized.lastIndexOf(" - ")));
            String lastSentence = lastBreak >= 0 ? sanitized.substring(lastBreak + 1).trim() : sanitized;
            String normalizedLastSentence = normalizeForIntentConstraint(lastSentence);
            if (normalizedLastSentence.startsWith("deseja ")
                    || normalizedLastSentence.startsWith("quer ")
                    || normalizedLastSentence.startsWith("posso ")
                    || normalizedLastSentence.startsWith("voce quer ")
                    || normalizedLastSentence.startsWith("would you ")) {
                sanitized = lastBreak >= 0 ? sanitized.substring(0, lastBreak + 1).trim() : "";
            }
            if (sanitized.isBlank()) {
                sanitized = "Próximo passo: confirmar os campos disponíveis e, depois disso, definir colunas, filtros e métricas antes de materializar a tela.";
            }
        }
        return sanitized;
    }

    private List<String> examplePrompts(
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample> examples,
            int limit) {
        if (examples == null || examples.isEmpty()) {
            return List.of();
        }
        return examples.stream()
                .filter(Objects::nonNull)
                .map(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample::prompt)
                .filter(StringUtils::hasText)
                .limit(limit)
                .toList();
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private List<String> limit(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .limit(limit)
                .toList();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String conversationTranscript(AgenticAuthoringTurnStreamRequest request) {
        if (request == null || request.conversationMessages() == null || request.conversationMessages().isEmpty()) {
            return "(none)";
        }
        String current = value(request.userPrompt());
        List<String> lines = request.conversationMessages().stream()
                .filter(Objects::nonNull)
                .filter(message -> StringUtils.hasText(message.text()))
                .map(message -> {
                    String role = value(message.role()).toLowerCase(Locale.ROOT);
                    if (!Set.of("user", "assistant").contains(role)) {
                        return "";
                    }
                    String text = truncateConversationReferenceText(value(message.text()));
                    if ("user".equals(role) && !current.isBlank() && current.equals(text)) {
                        return "";
                    }
                    return role + ": " + text;
                })
                .filter(StringUtils::hasText)
                .toList();
        if (lines.isEmpty()) {
            return "(none)";
        }
        int start = Math.max(0, lines.size() - MAX_CONVERSATION_REFERENCE_MESSAGES);
        return truncateConversationReferenceBlock(String.join("\n", lines.subList(start, lines.size())));
    }

    private String truncateConversationReferenceText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_CONVERSATION_REFERENCE_MESSAGE_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_CONVERSATION_REFERENCE_MESSAGE_CHARS).trim() + "...";
    }

    private String truncateConversationReferenceBlock(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_CONVERSATION_REFERENCE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CONVERSATION_REFERENCE_CHARS).trim() + "...";
    }

    private record ParsedConsultativeAnswer(boolean consultative, String category, String answer) {
    }

    private record ComponentCapabilityMatch(
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability,
            int score) {
    }

    private record RuntimeRelatedSurfaceReadAttempt(JsonNode read, ArrayNode reads, ObjectNode resolution, ObjectNode toolPlan) {
        RuntimeRelatedSurfaceReadAttempt(JsonNode read, ObjectNode resolution, ObjectNode toolPlan) {
            this(read, singletonReadArray(read), resolution, toolPlan);
        }

        boolean hasReads() {
            return reads != null && !reads.isEmpty();
        }

        private static ArrayNode singletonReadArray(JsonNode read) {
            ObjectMapper mapper = new ObjectMapper();
            ArrayNode array = mapper.createArrayNode();
            if (read != null && read.isObject()) {
                array.add(read.deepCopy());
            }
            return array;
        }
    }

    private record RuntimeRelatedSurfaceConsultativeIntent(
            String kind,
            String semanticDecisionRef,
            double confidence,
            List<String> reasons,
            boolean fallbackApplied,
            String comparisonDimensionFieldRef,
            String listTargetSurfaceRef,
            String summaryTargetSurfaceRef,
            String detailTargetSurfaceRef,
            String detailTargetCandidateRef,
            String detailTargetOptionRef,
            boolean requiresTemporalComparisonDimension,
            String targetResolutionMode,
            JsonNode targetCandidateResolutionDiagnostics,
            JsonNode targetRefinementDiagnostics) {
    }

    private record RuntimeRelatedSurfaceTargetCandidateResolution(
            RuntimeRelatedSurfaceConsultativeIntent intent,
            RuntimeRelatedSurfaceConsultativeIntent intentWithDiagnostics,
            ObjectNode diagnostics) {
    }

    private record RuntimeRelatedSurfaceTargetCatalogMatch(
            boolean accepted,
            String status,
            String surfaceRef,
            String candidateRef,
            String runtimeSurfaceInstanceRef,
            String matchedTermKind,
            String normalizedTerm,
            int score,
            String failureCode,
            ArrayNode evaluatedCandidates) {

        static RuntimeRelatedSurfaceTargetCatalogMatch accepted(
                String surfaceRef,
                String candidateRef,
                String runtimeSurfaceInstanceRef,
                String matchedTermKind,
                String normalizedTerm,
                int score) {
            return new RuntimeRelatedSurfaceTargetCatalogMatch(
                    true,
                    "accepted",
                    surfaceRef == null ? "" : surfaceRef,
                    candidateRef == null ? "" : candidateRef,
                    runtimeSurfaceInstanceRef == null ? "" : runtimeSurfaceInstanceRef,
                    matchedTermKind == null ? "" : matchedTermKind,
                    normalizedTerm == null ? "" : normalizedTerm,
                    score,
                    "",
                    null);
        }

        static RuntimeRelatedSurfaceTargetCatalogMatch rejected(String status, String failureCode) {
            return rejected(status, failureCode, null);
        }

        static RuntimeRelatedSurfaceTargetCatalogMatch rejected(
                String status,
                String failureCode,
                ArrayNode evaluatedCandidates) {
            return new RuntimeRelatedSurfaceTargetCatalogMatch(
                    false,
                    status == null ? "" : status,
                    "",
                    "",
                    "",
                    "",
                    "",
                    0,
                    failureCode == null ? "" : failureCode,
                    evaluatedCandidates);
        }
    }

    private record RuntimeRelatedSurfaceTargetRefinement(
            RuntimeRelatedSurfaceConsultativeIntent intent,
            ObjectNode diagnostics) {
    }

	    private record RuntimeRelatedSurfaceDisambiguationSelection(
            String surfaceRef,
            String candidateRef,
            String optionRef) {

        static RuntimeRelatedSurfaceDisambiguationSelection empty() {
            return new RuntimeRelatedSurfaceDisambiguationSelection("", "", "");
        }
    }

    private record RuntimeRelatedSurfaceTargetDecision(
            String kind,
            String targetSurfaceRef,
            double confidence) {
    }

    record RuntimeRelatedSurfaceIntentPolicy(
            String policyRef,
            String temporalComparisonFieldRef) {

        static RuntimeRelatedSurfaceIntentPolicy llm() {
            return new RuntimeRelatedSurfaceIntentPolicy(
                    "runtime-related-surface-intent-policy:llm",
                    "");
        }

        static RuntimeRelatedSurfaceIntentPolicy temporalCompareSmoke(String temporalComparisonFieldRef) {
            return new RuntimeRelatedSurfaceIntentPolicy(
                    "runtime-related-surface-intent-policy:temporal-compare-smoke",
                    safeConfiguredTemporalComparisonFieldRef(temporalComparisonFieldRef));
        }

        static RuntimeRelatedSurfaceIntentPolicy fromConfiguredPolicyRef(
                String policyRef,
                String temporalComparisonFieldRef) {
            if (!StringUtils.hasText(policyRef)) {
                return llm();
            }
            String normalized = policyRef.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "runtime-related-surface-intent-policy:temporal-compare-smoke",
                        "temporal-compare-smoke" -> temporalCompareSmoke(temporalComparisonFieldRef);
                case "runtime-related-surface-intent-policy:llm", "llm" -> llm();
                default -> llm();
            };
        }

        boolean temporalCompareSmokeEnabled() {
            return "runtime-related-surface-intent-policy:temporal-compare-smoke".equals(policyRef)
                    && StringUtils.hasText(temporalComparisonFieldRef);
        }

        private static String safeConfiguredTemporalComparisonFieldRef(String fieldRef) {
            String value = fieldRef == null ? "" : fieldRef.trim();
            if (!StringUtils.hasText(value)) {
                return "ocorridoEm";
            }
            return value.matches("[A-Za-z_][A-Za-z0-9_]*") ? value : "";
        }
    }

    record RuntimeToolPlannerPolicy(
            String policyRef,
            boolean multiToolExecutionEnabled,
            boolean maxToolCallsMayExceedOne,
            boolean multiToolAuthorized,
            String executionMode,
            int maxToolCalls,
            int maxRelatedSurfaceReads,
            int maxTotalRecordsReturned) {

        static RuntimeToolPlannerPolicy singleReadBeta() {
            return new RuntimeToolPlannerPolicy(
                    "runtime-tool-policy:single-read-beta",
                    false,
                    false,
                    false,
                    "single_read",
                    1,
                    1,
                    8);
        }

        static RuntimeToolPlannerPolicy dryRunMultiToolBeta() {
            return new RuntimeToolPlannerPolicy(
                    "runtime-tool-policy:multi-tool-dry-run-beta",
                    false,
                    false,
                    true,
                    "dry_run",
                    2,
                    2,
                    16);
        }

        static RuntimeToolPlannerPolicy readonlyMultiToolBetaSkeleton() {
            return new RuntimeToolPlannerPolicy(
                    "runtime-tool-policy:multi-tool-readonly-beta",
                    true,
                    true,
                    true,
                    "read_only",
                    2,
                    2,
                    16);
        }

        static RuntimeToolPlannerPolicy fromConfiguredPolicyRef(String policyRef) {
            if (!StringUtils.hasText(policyRef)) {
                return singleReadBeta();
            }
            String normalized = policyRef.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "runtime-tool-policy:multi-tool-dry-run-beta", "multi-tool-dry-run-beta" ->
                        dryRunMultiToolBeta();
                case "runtime-tool-policy:multi-tool-readonly-beta", "multi-tool-readonly-beta" ->
                        readonlyMultiToolBetaSkeleton();
                case "runtime-tool-policy:single-read-beta", "single-read-beta" -> singleReadBeta();
                default -> singleReadBeta();
            };
        }

        boolean dryRunMultiToolEnabled() {
            return multiToolAuthorized
                    && "dry_run".equals(executionMode);
        }

        boolean readonlyMultiToolSkeletonEnabled() {
            return multiToolAuthorized
                    && "planning_only".equals(executionMode);
        }

        boolean readonlyMultiToolExecutionEnabled() {
            return multiToolAuthorized
                    && multiToolExecutionEnabled
                    && maxToolCallsMayExceedOne
                    && "read_only".equals(executionMode);
        }
    }

    private record CandidateAssessment(
            String candidateRef,
            String surfaceRef,
            int score,
            boolean accepted,
            List<String> scoreReasons,
            List<String> failureCodes) {
    }
}
