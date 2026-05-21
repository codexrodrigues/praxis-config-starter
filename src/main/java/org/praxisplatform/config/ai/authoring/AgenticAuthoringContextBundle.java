package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

final class AgenticAuthoringContextBundle {

    private static final int MAX_DETAILED_COMPONENT_CATALOGS = 6;
    private static final int MAX_COMPACT_CAPABILITIES_PER_COMPONENT = 5;
    private static final int MAX_COMPACT_TRIGGER_TERMS = 5;
    private static final int MAX_COMPACT_FIELD_ALIASES = 6;
    private static final int MAX_COMPACT_ALIASES_PER_FIELD = 6;
    private static final int MAX_COMPACT_EXAMPLES = 1;
    private static final int MAX_COMPACT_CONFIG_HINTS = 3;

    private AgenticAuthoringContextBundle() {
    }

    static ObjectNode create(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            JsonNode currentPageSummary,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringCandidate> candidateOptions,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities,
            String governedDomainContext) {
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.put("schemaVersion", "praxis-agentic-authoring-context-bundle.v1");
        bundle.set("runtimeContext", runtimeContext(objectMapper, request, currentPageSummary, target));
        bundle.set("userIntent", userIntent(objectMapper, request, effectivePrompt));
        bundle.set("retrievalContext", retrievalContext(objectMapper, candidateOptions));
        bundle.set("governedDomainContext", governedDomainContext(objectMapper, request, governedDomainContext));
        bundle.set("componentContext", componentContext(objectMapper, request, effectivePrompt, target, componentCapabilities));
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

    private static ObjectNode governedDomainContext(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request,
            String promptBlock) {
        ObjectNode context = objectMapper.createObjectNode();
        String value = valueOrEmpty(promptBlock);
        context.put("schemaVersion", "praxis-agentic-authoring-governed-domain-context.v1");
        context.put("source", "domain-catalog/context");
        context.put("policyProfile", policyProfile(request));
        context.put("available", !value.isBlank());
        ObjectNode requested = requestedDomainCatalogContext(objectMapper, request);
        context.put("resolutionStatus", resolutionStatus(requested, value));
        context.set("requested", requested);
        context.put("usageRule", "Treat this block as governed semantic grounding for decision authoring; do not expose masked or denied source payloads, and do not use UI surfaces as the primary business rule source.");
        context.put("promptBlock", value);
        return context;
    }

    private static ObjectNode requestedDomainCatalogContext(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request) {
        ObjectNode requested = objectMapper.createObjectNode();
        JsonNode domainCatalog = objectNode(request != null && request.contextHints() != null
                ? request.contextHints().get("domainCatalog")
                : null);
        requested.put("present", domainCatalog != null);
        copyText(requested, "schemaVersion", domainCatalog, "schemaVersion");
        copyText(requested, "serviceKey", domainCatalog, "serviceKey");
        copyText(requested, "resourceKey", domainCatalog, "resourceKey");
        copyText(requested, "intent", domainCatalog, "intent");
        copyText(requested, "type", domainCatalog, "type");
        copyText(requested, "contextKey", domainCatalog, "contextKey");
        copyText(requested, "nodeType", domainCatalog, "nodeType");
        copyText(requested, "query", domainCatalog, "query");
        return requested;
    }

    private static String resolutionStatus(ObjectNode requested, String promptBlock) {
        if (StringUtils.hasText(promptBlock)) {
            return "resolved";
        }
        return requested != null && requested.path("present").asBoolean(false)
                ? "requested_but_unavailable"
                : "not_requested";
    }

    private static ObjectNode componentContext(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode component = objectMapper.createObjectNode();
        component.set("componentCapabilities", compactComponentCapabilities(
                objectMapper,
                request,
                effectivePrompt,
                target,
                componentCapabilities));
        component.set("authorableComponents", authorableComponents(objectMapper, componentCapabilities));
        component.set("platformGuide", platformGuide(objectMapper, componentCapabilities));
        component.put("formAuthoringPolicy", "Users can describe forms naturally, including fields and process goals. Praxis should ground final form materialization in governed domain resources, schemas, actions, and component capabilities; when grounding is incomplete, keep the form as a reviewable local/editorial draft instead of inventing business rules.");
        component.put("selectionRule", "Select visualizationDecision.primaryComponent from authorableComponents[].componentId when the user asks for a governed component. Do not invent component ids.");
        component.put("exampleRule", "Prefer examples[].prompt, examples[].intent, and examples[].configHints from the matching component capability when inferring UI configuration.");
        return component;
    }

    private static ObjectNode compactComponentCapabilities(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("version", componentCapabilities == null ? "" : valueOrEmpty(componentCapabilities.version()));
        int totalCatalogs = componentCapabilities == null || componentCapabilities.catalogs() == null
                ? 0
                : componentCapabilities.catalogs().size();
        compact.put("totalCatalogs", totalCatalogs);
        compact.put("detailPolicy", "Detailed capabilities are scoped to the current prompt and target to keep LLM latency low. Use authorableComponents and platformGuide for global discovery, then request or infer details for the selected component.");
        ArrayNode catalogs = compact.putArray("catalogs");
        if (componentCapabilities != null && componentCapabilities.catalogs() != null) {
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> selectedCatalogs =
                    promptRelevantCatalogs(request, effectivePrompt, target, componentCapabilities.catalogs());
            compact.put("includedCatalogs", selectedCatalogs.size());
            for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : selectedCatalogs) {
                if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
                    continue;
                }
                ObjectNode catalogNode = catalogs.addObject();
                catalogNode.put("componentId", catalog.componentId());
                catalogNode.put("version", valueOrEmpty(catalog.version()));
                ArrayNode capabilities = catalogNode.putArray("capabilities");
                if (catalog.capabilities() == null) {
                    continue;
                }
                int count = 0;
                for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : catalog.capabilities()) {
                    if (capability == null || count >= MAX_COMPACT_CAPABILITIES_PER_COMPONENT) {
                        continue;
                    }
                    ObjectNode capabilityNode = capabilities.addObject();
                    capabilityNode.put("id", valueOrEmpty(capability.id()));
                    capabilityNode.put("changeKind", valueOrEmpty(capability.changeKind()));
                    capabilityNode.set("triggerTerms", limitedStrings(
                            objectMapper,
                            capability.triggerTerms(),
                            MAX_COMPACT_TRIGGER_TERMS));
                    capabilityNode.set("fieldAliases", compactFieldAliases(objectMapper, capability.fieldAliases()));
                    capabilityNode.set("examples", compactExamples(objectMapper, capability.examples()));
                    count++;
                }
            }
        }
        return compact;
    }

    private static List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> promptRelevantCatalogs(
            AgenticAuthoringIntentResolutionRequest request,
            String effectivePrompt,
            AgenticAuthoringTarget target,
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog> catalogs) {
        if (catalogs == null || catalogs.isEmpty()) {
            return List.of();
        }
        List<ScoredCatalog> scored = new ArrayList<>();
        String prompt = normalizedSearchText(valueOrEmpty(effectivePrompt) + " " + valueOrEmpty(request == null ? null : request.userPrompt()));
        String targetComponentId = normalizedSearchText(request == null ? null : request.targetComponentId());
        String selectedComponentId = normalizedSearchText(target == null ? null : target.componentId());
        for (int index = 0; index < catalogs.size(); index++) {
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog = catalogs.get(index);
            if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
                continue;
            }
            int score = relevanceScore(catalog, prompt, targetComponentId, selectedComponentId);
            scored.add(new ScoredCatalog(catalog, score, index));
        }
        return scored.stream()
                .sorted(Comparator
                        .comparingInt(ScoredCatalog::score).reversed()
                        .thenComparingInt(ScoredCatalog::index))
                .limit(MAX_DETAILED_COMPONENT_CATALOGS)
                .map(ScoredCatalog::catalog)
                .toList();
    }

    private static int relevanceScore(
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog,
            String prompt,
            String targetComponentId,
            String selectedComponentId) {
        String componentId = normalizedSearchText(catalog.componentId());
        int score = 0;
        if (!componentId.isBlank() && (componentId.equals(targetComponentId) || componentId.equals(selectedComponentId))) {
            score += 100;
        }
        for (String token : searchTokens(componentId)) {
            if (containsToken(prompt, token)) {
                score += 24;
            }
        }
        String purpose = normalizedSearchText(componentPurpose(catalog) + " " + componentBestFor(catalog));
        for (String token : searchTokens(purpose)) {
            if (containsToken(prompt, token)) {
                score += 8;
            }
        }
        if (catalog.capabilities() != null) {
            for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : catalog.capabilities()) {
                if (capability == null) {
                    continue;
                }
                if (containsToken(prompt, normalizedSearchText(capability.changeKind()))) {
                    score += 10;
                }
                if (capability.triggerTerms() == null) {
                    continue;
                }
                for (String term : capability.triggerTerms()) {
                    String normalizedTerm = normalizedSearchText(term);
                    if (!normalizedTerm.isBlank() && prompt.contains(normalizedTerm)) {
                        score += 14;
                    }
                }
            }
        }
        return score;
    }

    private record ScoredCatalog(
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog,
            int score,
            int index) {
    }

    private static ArrayNode authorableComponents(
            ObjectMapper objectMapper,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ArrayNode components = objectMapper.createArrayNode();
        if (componentCapabilities == null || componentCapabilities.catalogs() == null) {
            return components;
        }
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
                continue;
            }
            ObjectNode item = components.addObject();
            item.put("componentId", catalog.componentId());
            item.put("version", valueOrEmpty(catalog.version()));
            item.put("purpose", componentPurpose(catalog));
            item.put("bestFor", componentBestFor(catalog));
            item.put("authoringBoundary", componentAuthoringBoundary(catalog));
            ArrayNode changeKinds = item.putArray("changeKinds");
            ArrayNode terms = item.putArray("semanticTerms");
            Set<String> seenChangeKinds = new LinkedHashSet<>();
            Set<String> seenTerms = new LinkedHashSet<>();
            if (catalog.capabilities() != null) {
                for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability : catalog.capabilities()) {
                    if (capability == null) {
                        continue;
                    }
                    addLimited(changeKinds, seenChangeKinds, capability.changeKind(), 16);
                    if (capability.triggerTerms() != null) {
                        for (String term : capability.triggerTerms()) {
                            addLimited(terms, seenTerms, term, 20);
                        }
                    }
                }
            }
        }
        return components;
    }

    private static ObjectNode platformGuide(
            ObjectMapper objectMapper,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ObjectNode guide = objectMapper.createObjectNode();
        guide.put("platformSummary", "Praxis is a governed AI authoring platform. The assistant should understand user intent, explain the current domain and available governed components, and materialize only decisions that pass catalog, schema, action, component, and review constraints.");
        guide.put("consultativeUse", "For questions such as what can be done here, which components can be created, how to build an admin panel, or how free-form forms work, answer as guidance first and do not create a preview until the user asks to create or confirms a concrete direction.");
        guide.set("componentFamilies", componentFamilies(objectMapper, componentCapabilities));
        return guide;
    }

    private static ArrayNode componentFamilies(
            ObjectMapper objectMapper,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        ArrayNode families = objectMapper.createArrayNode();
        if (componentCapabilities == null || componentCapabilities.catalogs() == null) {
            return families;
        }
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            if (catalog == null || !StringUtils.hasText(catalog.componentId())) {
                continue;
            }
            ObjectNode family = families.addObject();
            family.put("componentId", catalog.componentId());
            family.put("purpose", componentPurpose(catalog));
            family.put("bestFor", componentBestFor(catalog));
        }
        return families;
    }

    private static String componentPurpose(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog) {
        String componentId = valueOrEmpty(catalog == null ? null : catalog.componentId()).toLowerCase();
        if (componentId.contains("chart")) {
            return "Visualizar metricas, distribuicoes e recortes analiticos em graficos governados.";
        }
        if (componentId.contains("table") || componentId.contains("grid")) {
            return "Consultar, comparar e revisar registros em linhas e colunas.";
        }
        if (componentId.contains("form")) {
            return "Capturar, editar ou submeter informacoes por uma operacao governada.";
        }
        if (componentId.contains("tabs")) {
            return "Organizar uma experiencia em abas com secoes relacionadas.";
        }
        if (componentId.contains("expansion") || componentId.contains("accordion")) {
            return "Organizar uma experiencia em paineis expansiveis, accordion ou acordeon com conteudo e widgets governados.";
        }
        if (componentId.contains("stepper") || componentId.contains("wizard")) {
            return "Conduzir processos em etapas com revisao progressiva.";
        }
        if (componentId.contains("filter")) {
            return "Controlar filtros e recortes aplicados a outros componentes.";
        }
        if (componentId.contains("crud")) {
            return "Oferecer listagem e acoes de criar, editar, consultar ou remover quando governado.";
        }
        return "Componente governado disponivel no catalogo de authoring.";
    }

    private static String componentBestFor(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog) {
        String componentId = valueOrEmpty(catalog == null ? null : catalog.componentId()).toLowerCase();
        if (componentId.contains("chart")) {
            return "Dashboards, paineis administrativos e perguntas de acompanhamento por categoria, status, periodo ou responsavel.";
        }
        if (componentId.contains("table") || componentId.contains("grid")) {
            return "Telas operacionais, detalhes conectados, listas auditaveis e comparacao de registros.";
        }
        if (componentId.contains("form")) {
            return "Cadastros, solicitacoes, edicoes guiadas e fluxos de captura de dados.";
        }
        if (componentId.contains("tabs")) {
            return "Paginas com multiplas visoes do mesmo assunto, como resumo, detalhes, historico e acoes.";
        }
        if (componentId.contains("expansion") || componentId.contains("accordion")) {
            return "Paginas compactas com secoes recolhiveis, accordion ou acordeon, como dados gerais, detalhes, historico e acoes.";
        }
        if (componentId.contains("stepper") || componentId.contains("wizard")) {
            return "Fluxos com varias etapas, validacao gradual e revisao antes de concluir.";
        }
        if (componentId.contains("filter")) {
            return "Exploracao de dados em dashboards, tabelas e paineis com recortes reutilizaveis.";
        }
        if (componentId.contains("crud")) {
            return "Administracao de entidades quando as acoes governadas existirem no dominio.";
        }
        return semanticFallback(catalog);
    }

    private static String componentAuthoringBoundary(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog) {
        String componentId = valueOrEmpty(catalog == null ? null : catalog.componentId()).toLowerCase();
        if (componentId.contains("form")) {
            return "Forms can be described freely by the user, but final fields/actions must be grounded in governed schema/action metadata or kept as a reviewable local/editorial draft.";
        }
        if (componentId.contains("crud")) {
            return "CRUD surfaces require governed create/read/update/delete actions before being treated as operational business behavior.";
        }
        return "Use only governed component capabilities and domain/catalog evidence; do not invent fields, resources, or business behavior.";
    }

    private static String semanticFallback(AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog) {
        if (catalog == null || catalog.capabilities() == null) {
            return "Authoring scenarios covered by this component capability catalog.";
        }
        return catalog.capabilities().stream()
                .filter(capability -> capability != null && capability.triggerTerms() != null)
                .flatMap(capability -> capability.triggerTerms().stream())
                .filter(StringUtils::hasText)
                .limit(6)
                .reduce((left, right) -> left + ", " + right)
                .map(terms -> "Pedidos relacionados a: " + terms + ".")
                .orElse("Authoring scenarios covered by this component capability catalog.");
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
        ObjectNode getComponentAuthoringContext = tools.putObject("getComponentAuthoringContext");
        getComponentAuthoringContext.put("method", "INTERNAL_TOOL");
        getComponentAuthoringContext.put("endpoint", "praxis-config-starter:vector_store/component-corpus");
        getComponentAuthoringContext.put("purpose", "Retrieve granular read-only component corpus evidence before preview planning.");
        ArrayNode componentInputs = getComponentAuthoringContext.putArray("inputs");
        componentInputs.add("query");
        componentInputs.add("componentId");
        componentInputs.add("releaseId");
        componentInputs.add("limit");
        getComponentAuthoringContext.put("result", "authoringEvidence.evidence[] with sourceRef, releaseId, chunkKind, contentHash, corpusVersion, and bounded content.");
        getComponentAuthoringContext.put("whenToUse", "Use for component capabilities, examples, manifests, recipes, or configuration docs; evidence is grounding only and does not replace validate-plan or compile-patch.");
        ObjectNode getManifestSlice = tools.putObject("getManifestSlice");
        getManifestSlice.put("method", "INTERNAL_TOOL");
        getManifestSlice.put("endpoint", "praxis-config-starter:ai_registry/authoringManifest");
        getManifestSlice.put("purpose", "Retrieve a bounded backend manifest slice for a component operation.");
        getManifestSlice.put("result", "A read-only manifest slice such as operations, editableTargets, validators, or one operationId.");
        ObjectNode searchSchemaFields = tools.putObject("searchSchemaFields");
        searchSchemaFields.put("method", "INTERNAL_TOOL");
        searchSchemaFields.put("endpoint", "praxis-config-starter:/schemas/filtered");
        searchSchemaFields.put("purpose", "Retrieve governed schema evidence for fields and operations without applying patches.");
        searchSchemaFields.put("result", "Read-only schema evidence with sourceRef.");
        return tools;
    }

    private static ArrayNode rules(ObjectMapper objectMapper) {
        ArrayNode rules = objectMapper.createArrayNode();
        rules.add("You are helping inside an Angular application that uses Praxis UI Page Builder metadata-driven components.");
        rules.add("Use the backend tool catalog as the menu of available retrieval operations; do not invent resources, endpoints, schemas, fields, or component capabilities.");
        rules.add("Use component capability examples to infer likely configuration choices before asking the user for low-level technical details.");
        rules.add("When more backend data is needed, return actionable quickReplies with contextHints.tool instead of a generic clarification.");
        rules.add("assistantMessage must read like a natural chat reply in the user's language. Avoid diagnostics, API terms, and terse labels such as 'alimentar tela'.");
        return rules;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizedSearchText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase();
        return normalized.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Set<String> searchTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        if (!StringUtils.hasText(value)) {
            return tokens;
        }
        for (String token : value.split("\\s+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static boolean containsToken(String text, String token) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(token)
                && (" " + text + " ").contains(" " + token + " ");
    }

    private static void addLimited(ArrayNode node, Set<String> seen, String value, int limit) {
        if (node.size() >= limit || !StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.trim();
        if (seen.add(normalized)) {
            node.add(normalized);
        }
    }

    private static ArrayNode limitedStrings(ObjectMapper objectMapper, List<String> values, int limit) {
        ArrayNode node = objectMapper.createArrayNode();
        if (values == null) {
            return node;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            addLimited(node, seen, value, limit);
        }
        return node;
    }

    private static ArrayNode compactFieldAliases(
            ObjectMapper objectMapper,
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias> aliases) {
        ArrayNode node = objectMapper.createArrayNode();
        if (aliases == null) {
            return node;
        }
        int count = 0;
        Set<String> seenFields = new LinkedHashSet<>();
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentFieldAlias alias : aliases) {
            if (alias == null || count >= MAX_COMPACT_FIELD_ALIASES) {
                continue;
            }
            String field = valueOrEmpty(alias.field());
            if (field.isBlank() || !seenFields.add(field)) {
                continue;
            }
            ObjectNode item = node.addObject();
            item.put("field", field);
            item.set("aliases", limitedStrings(objectMapper, alias.aliases(), MAX_COMPACT_ALIASES_PER_FIELD));
            count++;
        }
        return node;
    }

    private static ArrayNode compactExamples(
            ObjectMapper objectMapper,
            List<AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample> examples) {
        ArrayNode node = objectMapper.createArrayNode();
        if (examples == null) {
            return node;
        }
        int count = 0;
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example : examples) {
            if (example == null || count >= MAX_COMPACT_EXAMPLES) {
                continue;
            }
            ObjectNode item = node.addObject();
            item.put("prompt", valueOrEmpty(example.prompt()));
            item.put("intent", valueOrEmpty(example.intent()));
            item.set("configHints", limitedStrings(objectMapper, example.configHints(), MAX_COMPACT_CONFIG_HINTS));
            count++;
        }
        return node;
    }

    private static JsonNode objectNode(JsonNode node) {
        return node != null && node.isObject() ? node : null;
    }

    private static void copyText(ObjectNode target, String targetFieldName, JsonNode source, String sourceFieldName) {
        String value = text(source, sourceFieldName);
        if (StringUtils.hasText(value)) {
            target.put(targetFieldName, value);
        }
    }

    private static String policyProfile(AgenticAuthoringIntentResolutionRequest request) {
        JsonNode domainCatalog = objectNode(request != null && request.contextHints() != null
                ? request.contextHints().get("domainCatalog")
                : null);
        String policyProfile = text(domainCatalog, "policyProfile");
        return StringUtils.hasText(policyProfile) ? policyProfile : "authoring";
    }

    private static String text(JsonNode node, String fieldName) {
        if (node == null || !node.isObject() || !node.has(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        return value != null && value.isTextual() && StringUtils.hasText(value.asText())
                ? value.asText()
                : null;
    }
}
