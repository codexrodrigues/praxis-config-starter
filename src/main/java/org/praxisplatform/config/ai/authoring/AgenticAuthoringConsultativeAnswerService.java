package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.springframework.util.StringUtils;

@Slf4j
public class AgenticAuthoringConsultativeAnswerService {

    private final AiProviderManagementService providerManagementService;
    private final ObjectMapper objectMapper;
    private final AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService;

    public AgenticAuthoringConsultativeAnswerService(
            AiProviderManagementService providerManagementService,
            ObjectMapper objectMapper,
            AgenticAuthoringConsultativeApiCatalogProjectionService apiCatalogProjectionService) {
        this.providerManagementService = Objects.requireNonNull(
                providerManagementService, "providerManagementService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.apiCatalogProjectionService = apiCatalogProjectionService;
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
        if (!domainAvailabilityQuestion && clearlyRequestsMaterialization(request.userPrompt())) {
            return Optional.empty();
        }
        boolean explicitNoMaterialization = explicitlyForbidsMaterialization(request.userPrompt());
        AgenticAuthoringConsultativeApiCatalogProjection projection = null;
        try {
            if (domainAvailabilityQuestion) {
                projection = apiCatalogProjection(
                        request.userPrompt(),
                        tenantId,
                        environment);
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
                                request.userPrompt(),
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
                        directAnswerPrompt(request.userPrompt(), evidenceBundle(request, "auto", componentCapabilities, null)),
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
                    if (projection != null && projection.hasResources()) {
                        generated = providerManagementService.generateText(
                                directAnswerPrompt(
                                        request.userPrompt(),
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
                    answerPrompt(request.userPrompt(), evidenceBundle(request, "auto", componentCapabilities, null)),
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
                if (projection != null && projection.hasResources()) {
                    generated = providerManagementService.generateText(
                            answerPrompt(
                                    request.userPrompt(),
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

    private String directAnswerPrompt(String userPrompt, JsonNode evidence) {
        return """
                You are Praxis, a governed AI authoring assistant.
                The user explicitly asked not to create, preview, apply, save or materialize anything yet.
                Answer as a consultative conversation using the grounded evidence below.

                Return:

                CONSULTATIVE_CATEGORY: domain_api|component_catalog|component_capability|platform_guidance
                ANSWER:
                <final user-facing answer>

                Style:
                - Same language as the user; default to pt-BR.
                - Answer the question now; do not ask whether the user wants a brief or detailed answer.
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

                Grounded evidence:
                %s
                """.formatted(value(userPrompt), evidence == null ? "{}" : evidence.toPrettyString());
    }

    private String answerPrompt(String userPrompt, JsonNode evidence) {
        return """
                You are Praxis, a governed AI authoring assistant.
                Decide from the user's full intent whether this should be answered conversationally without creating,
                changing, previewing, applying or saving UI. Use the grounded evidence below for the answer.

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
                  Recommend screen types at a product level and state that columns, filters and metrics depend on confirmed fields.
                - End with a complete, concrete next step. Do not leave a dangling sentence or end with a question.
                - When discussing component capability, explain the user-facing options first; put technical config names only if truly necessary and label them as implementation details.
                - For platform or component questions, stay on platform/component concepts. Do not introduce business-domain examples
                  from project knowledge or domain resources unless the user explicitly asks about data, APIs, domain, resources or business entities.

                User question:
                %s

                Grounded evidence:
                %s
                """.formatted(value(userPrompt), evidence == null ? "{}" : evidence.toPrettyString());
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
        if ("domain_api".equals(category)
                && request != null
                && request.contextHints() != null
                && request.contextHints().path("projectKnowledge").isObject()) {
            root.set("projectKnowledge", request.contextHints().path("projectKnowledge"));
        }
        return root;
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
                || isArtifactSpecification(text);
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
                || text.contains(" existe api ")
                || text.contains(" existem api ")
                || text.contains(" existe dados ")
                || text.contains(" existem dados ");
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

    private record ParsedConsultativeAnswer(boolean consultative, String category, String answer) {
    }

    private record ComponentCapabilityMatch(
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability,
            int score) {
    }
}
