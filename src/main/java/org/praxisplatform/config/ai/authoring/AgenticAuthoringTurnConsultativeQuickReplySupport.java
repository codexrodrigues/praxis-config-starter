package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * Package-private collaborator for {@link AgenticAuthoringTurnEngine} that encapsulates
 * consultative quick-reply generation, domain discovery and resource discovery candidate
 * presentation, and grounded clarification messaging.
 */
@Slf4j
final class AgenticAuthoringTurnConsultativeQuickReplySupport {

    private final ObjectMapper objectMapper;

    AgenticAuthoringTurnConsultativeQuickReplySupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    List<AgenticAuthoringQuickReply> terminalClarificationQuickReplies(
            AgenticAuthoringIntentResolutionResult resolution) {
        if (resolution != null && resolution.quickReplies() != null && !resolution.quickReplies().isEmpty()) {
            return resolution.quickReplies();
        }
        ObjectNode retryHints = objectMapper.createObjectNode();
        retryHints.put("source", "authoring-turn-terminal-clarification");
        retryHints.put("canonicalAction", "retry_semantic_resolution");
        return List.of(
                new AgenticAuthoringQuickReply(
                        "retry-semantic-resolution",
                        "retry",
                        "Tentar novamente",
                        "Tente resolver novamente a mesma solicitação com o contexto governado disponível.",
                        "Repete a resolução sem assumir uma intenção localmente.",
                        "refresh",
                        "primary",
                        retryHints),
                new AgenticAuthoringQuickReply(
                        "revise",
                        "revise",
                        "Revisar solicitação",
                        "Quero revisar a solicitação antes de tentar novamente."));
    }

    AgenticAuthoringIntentResolutionResult reconcileGovernedDomainConsultativeDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            AgenticAuthoringConsultativeAnswer answer) {
        if (answer == null
                || !"domain_knowledge".equals(answer.category())
                || intentResolution == null) {
            return intentResolution;
        }
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", "governed-domain-discovery");
        JsonNode entries = answer.evidenceBundle() == null
                ? null
                : answer.evidenceBundle().path("entries");
        ArrayNode conceptKeys = constraints.putArray("conceptKeys");
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                String conceptKey = entry.path("conceptKey").asText("").trim();
                if (!conceptKey.isBlank()) {
                    conceptKeys.add(conceptKey);
                }
            }
        }
        List<String> warnings = new ArrayList<>(intentResolution.warnings() == null
                ? List.of()
                : intentResolution.warnings());
        warnings.add("governed-domain-discovery-cleared-ungrounded-resource-selection");
        AgenticAuthoringSemanticDecision semanticDecision = AgenticAuthoringSemanticDecision.from(
                        "explore",
                        "api_catalog",
                        "answer_api_catalog_question",
                        null,
                        List.of(),
                        null,
                        warnings,
                        intentResolution.llmDiagnostics(),
                        null,
                        request.activeSemanticDecision(),
                        request.sessionId(),
                        request.clientTurnId(),
                        request.userPrompt(),
                        request.userPrompt(),
                        "Governed domain discovery is grounded by the canonical domain catalog, not by an unrelated resource candidate.")
                .withConstraints(constraints);
        return new AgenticAuthoringIntentResolutionResult(
                true,
                "explore",
                "api_catalog",
                "answer_api_catalog_question",
                "api-catalog-qa",
                intentResolution.targetApp(),
                intentResolution.targetComponentId(),
                intentResolution.target(),
                null,
                List.of(),
                intentResolution.gate(),
                intentResolution.effectivePrompt(),
                intentResolution.assistantMessage(),
                intentResolution.assistantContent(),
                intentResolution.apiCatalogAnswer(),
                intentResolution.quickReplies(),
                intentResolution.pendingClarification(),
                intentResolution.clarificationQuestions(),
                warnings.stream().distinct().toList(),
                intentResolution.failureCodes(),
                intentResolution.currentPageSummary(),
                intentResolution.llmDiagnostics(),
                null,
                semanticDecision);
    }

    List<JsonNode> domainDiscoveryResources(JsonNode domainDiscovery, int limit) {
        List<JsonNode> resources = new ArrayList<>();
        if (domainDiscovery == null || !domainDiscovery.isArray()) {
            return resources;
        }
        for (JsonNode resource : domainDiscovery) {
            if (resources.size() >= limit || resource == null || !resource.isObject()) {
                break;
            }
            if (StringUtils.hasText(domainDiscoveryLabel(resource))) {
                resources.add(resource);
            }
        }
        return resources;
    }

    List<AgenticAuthoringQuickReply> domainDiscoveryQuickReplies(List<JsonNode> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        for (JsonNode resource : resources) {
            if (replies.size() >= 4 || resource == null || !resource.isObject()) {
                break;
            }
            String label = domainDiscoveryLabel(resource);
            ObjectNode contextHints = objectMapper.createObjectNode();
            contextHints.put("schemaVersion", "praxis-agentic-authoring-domain-discovery-choice.v1");
            contextHints.put("source", "domainDiscovery");
            putText(contextHints, "resourceKey", resource.path("resourceKey").asText(""));
            putText(contextHints, "resourceLabel", label);
            if (resource.path("fields").isArray()) {
                contextHints.set("fields", resource.path("fields").deepCopy());
            }
            if (resource.path("surfaces").isArray()) {
                contextHints.set("surfaces", resource.path("surfaces").deepCopy());
            }
            ObjectNode value = objectMapper.createObjectNode();
            putText(value, "resourceKey", resource.path("resourceKey").asText(""));
            putText(value, "label", label);
            replies.add(new AgenticAuthoringQuickReply(
                    "domain-discovery-confirm:" + replies.size(),
                    "resource",
                    label,
                    "Investigue " + label + " como fonte governada para a tela.",
                    "Usa o contexto governado já disponível antes de materializar qualquer configuração.",
                    "dataset",
                    "resource",
                    contextHints,
                    null,
                    value));
        }
        return replies;
    }

    String domainDiscoveryLabel(JsonNode resource) {
        if (resource == null || !resource.isObject()) {
            return "";
        }
        String label = firstNonBlank(
                resource.path("title").asText(""),
                resource.path("label").asText(""),
                resource.path("resourceLabel").asText(""),
                resource.path("resourceKey").asText(""));
        return StringUtils.hasText(label)
                ? AgenticAuthoringPresentationText.display(label)
                : "";
    }

    String unconfirmedAiAuthoredResourceFocusMessage() {
        return "Os candidatos recuperados ainda não possuem vínculo semântico e operacional aprovado com o recurso e as ações solicitados. "
                + "Por segurança, não vou materializar a tela nem oferecer uma dessas fontes como confirmação. "
                + "Verifique a projeção e a aprovação do binding canônico no catálogo governado antes de continuar.";
    }

    ArrayNode intentResolutionCandidateNodes(AgenticAuthoringIntentResolutionResult intentResolution) {
        ArrayNode nodes = objectMapper.createArrayNode();
        if (intentResolution == null || intentResolution.candidates() == null || intentResolution.candidates().isEmpty()) {
            return nodes;
        }
        for (AgenticAuthoringCandidate candidate : intentResolution.candidates()) {
            if (candidate != null) {
                nodes.add(candidateContext(candidate));
            }
        }
        return nodes;
    }

    AgenticAuthoringConsultativeApiCatalogProjection resourceDiscoveryProjection(JsonNode resourceDiscovery) {
        JsonNode projection = resourceDiscovery == null
                ? objectMapper.missingNode()
                : resourceDiscovery.path("consultativeProjection");
        if (!projection.isObject()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(projection, AgenticAuthoringConsultativeApiCatalogProjection.class);
        } catch (Exception ex) {
            log.warn("[AgenticAuthoring] Unable to read resource discovery consultative projection.", ex);
            return null;
        }
    }

    String groundedResourceDiscoveryClarificationMessage(
            AgenticAuthoringTurnStreamRequest request,
            JsonNode presentableCandidates,
            AgenticAuthoringConsultativeApiCatalogProjection projection) {
        if (presentableCandidates == null || !presentableCandidates.isArray() || presentableCandidates.isEmpty()) {
            String domainContext = domainDiscoveryContextSentence(request);
            return "A busca governada retornou candidatos preliminares, mas ainda sem evidência forte suficiente para eu destacar uma fonte. "
                    + domainContext
                    + "Como a confirmação semântica da intenção falhou nesta tentativa, não vou materializar a tela automaticamente. "
                    + "Confirme a fonte de dados desejada ou peça para tentar novamente a busca governada.";
        }
        List<String> labels = projection != null && projection.resources() != null
                ? projection.resources().stream()
                        .filter(Objects::nonNull)
                        .map(this::resourceLabel)
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(3)
                        .toList()
                : resourceDiscoveryCandidateLabels(presentableCandidates, 3);
        String candidateText = labels.isEmpty()
                ? "fontes governadas compatíveis"
                : String.join(", ", labels);
        return "Encontrei candidatos governados para continuar: " + candidateText + "."
                + " Como a confirmação semântica da intenção falhou nesta tentativa, não vou materializar a tela automaticamente. "
                + "Confirme uma fonte ou peça os campos confirmados para eu seguir com segurança.";
    }

    String domainDiscoveryContextSentence(AgenticAuthoringTurnStreamRequest request) {
        JsonNode contextHints = request == null || request.contextHints() == null
                ? objectMapper.missingNode()
                : request.contextHints();
        JsonNode domainDiscovery = contextHints.path("domainDiscovery");
        List<String> labels = domainDiscoveryResources(domainDiscovery, 3).stream()
                .map(this::domainDiscoveryLabel)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return labels.isEmpty()
                ? ""
                : "O contexto governado disponível inclui: " + String.join(", ", labels) + ". ";
    }

    boolean onlyWeakResourceDiscoveryCandidates(JsonNode candidates) {
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return false;
        }
        for (JsonNode candidate : candidates) {
            if (!isWeakResourceDiscoveryCandidate(candidate)) {
                return false;
            }
        }
        return true;
    }

    boolean isWeakResourceDiscoveryCandidate(JsonNode candidate) {
        JsonNode evidence = candidate == null ? objectMapper.missingNode() : candidate.path("evidence");
        if (!evidence.isArray()) {
            return false;
        }
        boolean lexicalFallback = false;
        boolean weakEvidence = false;
        boolean broadArtifactDiscovery = false;
        for (JsonNode item : evidence) {
            String value = safeText(item.asText(""));
            lexicalFallback = lexicalFallback || "lexical-fallback".equals(value);
            weakEvidence = weakEvidence || "weak-evidence".equals(value);
            broadArtifactDiscovery = broadArtifactDiscovery || "broad-artifact-discovery".equals(value);
        }
        return lexicalFallback || weakEvidence || broadArtifactDiscovery;
    }

    ArrayNode presentableResourceDiscoveryCandidates(JsonNode candidates) {
        ArrayNode presentable = objectMapper.createArrayNode();
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            return presentable;
        }
        for (JsonNode candidate : candidates) {
            if (candidate != null && candidate.isObject() && !isWeakResourceDiscoveryCandidate(candidate)) {
                presentable.add(candidate);
            }
        }
        return presentable;
    }

    List<String> resourceDiscoveryCandidateLabels(JsonNode candidates, int limit) {
        List<String> labels = new ArrayList<>();
        if (candidates == null || !candidates.isArray()) {
            return labels;
        }
        for (JsonNode candidate : candidates) {
            if (labels.size() >= limit || candidate == null || !candidate.isObject()) {
                break;
            }
            String label = resourceDiscoveryCandidateLabel(candidate);
            if (StringUtils.hasText(label)) {
                labels.add(AgenticAuthoringPresentationText.display(label));
            }
        }
        return labels;
    }

    List<AgenticAuthoringQuickReply> resourceDiscoveryCandidateQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            JsonNode candidates) {
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        if (candidates == null || !candidates.isArray()) {
            return replies;
        }
        for (JsonNode candidate : candidates) {
            if (replies.size() >= 4 || candidate == null || !candidate.isObject()) {
                break;
            }
            String resourcePath = safeText(candidate.path("resourcePath").asText(""));
            if (!StringUtils.hasText(resourcePath)) {
                continue;
            }
            String label = AgenticAuthoringPresentationText.display(resourceDiscoveryCandidateLabel(candidate));
            ObjectNode contextHints = objectMapper.createObjectNode();
            contextHints.put("schemaVersion", "praxis-agentic-authoring-resource-discovery-choice.v1");
            contextHints.put("source", "resourceDiscovery");
            contextHints.put("resourcePath", resourcePath);
            putText(contextHints, "operation", candidate.path("operation").asText(""));
            putText(contextHints, "schemaUrl", candidate.path("schemaUrl").asText(""));
            putText(contextHints, "submitUrl", candidate.path("submitUrl").asText(""));
            putText(contextHints, "submitMethod", candidate.path("submitMethod").asText(""));
            ObjectNode value = objectMapper.createObjectNode();
            value.put("resourcePath", resourcePath);
            putText(value, "operation", candidate.path("operation").asText(""));
            putCandidateScore(candidate, value);
            String quickReplyId = resourceDiscoveryQuickReplyId(candidate);
            replies.add(new AgenticAuthoringQuickReply(
                    quickReplyId,
                    "resource",
                    label,
                    "Use " + label + " como fonte governada para a tela.",
                    "Confirma este candidato governado antes de materializar a configuração.",
                    "dataset",
                    "resource",
                    contextHints,
                    resourceDiscoverySemanticDecision(
                            request,
                            intentResolution,
                            quickReplyId,
                            label,
                            candidate),
                    value));
        }
        return replies;
    }

    String resourceDiscoveryQuickReplyId(JsonNode candidate) {
        String identity = firstNonBlank(
                candidate.path("resourcePath").asText(""),
                candidate.path("resourceKey").asText(""),
                resourceDiscoveryCandidateLabel(candidate));
        String operation = candidate.path("operation").asText("");
        String slug = (identity + "-" + operation)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return "resource-discovery-confirm:" + (slug.isBlank() ? "candidate" : slug);
    }

    JsonNode resourceDiscoverySemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringIntentResolutionResult intentResolution,
            String quickReplyId,
            String label,
            JsonNode candidateNode) {
        AgenticAuthoringSemanticDecision activeDecision = intentResolution != null
                        && intentResolution.semanticDecision() != null
                ? intentResolution.semanticDecision()
                : request == null ? null : request.activeSemanticDecision();
        String operationKind = firstNonBlank(
                intentResolution == null ? "" : intentResolution.operationKind(),
                activeDecision == null ? "" : activeDecision.operationKind(),
                "create");
        String artifactKind = firstNonBlank(
                intentResolution == null ? "" : intentResolution.artifactKind(),
                activeDecision == null ? "" : activeDecision.artifactKind(),
                "page");
        String changeKind = firstNonBlank(
                intentResolution == null ? "" : intentResolution.changeKind(),
                activeDecision == null ? "" : activeDecision.changeKind(),
                "create_artifact");
        List<String> evidence = new ArrayList<>();
        JsonNode evidenceNode = candidateNode.path("evidence");
        if (evidenceNode.isArray()) {
            evidenceNode.forEach(item -> {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    evidence.add(value);
                }
            });
        }
        if (evidence.isEmpty()) {
            evidence.add("semantic-retrieval");
        }
        AgenticAuthoringCandidate selectedCandidate = new AgenticAuthoringCandidate(
                candidateNode.path("resourcePath").asText(""),
                candidateNode.path("operation").asText(""),
                candidateNode.path("schemaUrl").asText(""),
                candidateNode.path("submitUrl").asText(""),
                candidateNode.path("submitMethod").asText(""),
                candidateNode.path("score").asDouble(0.0d),
                candidateNode.path("reason").asText("Governed resource discovery candidate."),
                List.copyOf(evidence));
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", quickReplyId);
        constraints.put("continuationOf", "resource_discovery");
        constraints.putArray("conceptKeys");
        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                        operationKind,
                        artifactKind,
                        changeKind,
                        selectedCandidate,
                        List.of(selectedCandidate),
                        activeDecision == null ? null : activeDecision.visualizationDecision(),
                        List.of(),
                        null,
                        null,
                        activeDecision,
                        request == null ? "" : request.sessionId(),
                        (request == null ? "" : request.clientTurnId()) + ":" + quickReplyId,
                        "Use " + label + " como fonte governada para a tela.",
                        activeDecision == null
                                ? "Use " + label + " como fonte governada para a tela."
                                : activeDecision.activeObjective(),
                        "The user may select this governed resource discovered for the active authoring decision.")
                .withConstraints(constraints);
        return objectMapper.valueToTree(decision);
    }

    String resourceDiscoveryCandidateLabel(JsonNode candidate) {
        if (candidate == null || !candidate.isObject()) {
            return "";
        }
        String explicit = firstNonBlank(
                candidate.path("resourceLabel").asText(""),
                candidate.path("resourceKey").asText(""));
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String resourcePath = safeText(candidate.path("resourcePath").asText(""));
        if (StringUtils.hasText(resourcePath)) {
            String lastSegment = resourcePath;
            int slash = lastSegment.lastIndexOf('/');
            if (slash >= 0 && slash < lastSegment.length() - 1) {
                lastSegment = lastSegment.substring(slash + 1);
            }
            return lastSegment.replace('-', ' ').replace('_', ' ');
        }
        return safeText(candidate.path("reason").asText(""));
    }

    private void putCandidateScore(JsonNode candidate, ObjectNode value) {
        if (candidate != null && candidate.path("score").isNumber()) {
            value.put("score", candidate.path("score").asDouble());
        }
    }

    List<AgenticAuthoringQuickReply> consultativeQuickReplies(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringConsultativeAnswer answer,
            AgenticAuthoringIntentResolutionResult intentResolution) {
        if (answer == null) {
            return List.of();
        }
        if (answer.quickReplies() != null && !answer.quickReplies().isEmpty()) {
            return answer.quickReplies();
        }
        AgenticAuthoringConsultativeApiCatalogProjection projection = answer.apiCatalogProjection();
        if (projection == null || !projection.hasResources()) {
            return intentResolution != null && intentResolution.quickReplies() != null
                    ? intentResolution.quickReplies()
                    : List.of();
        }
        List<AgenticAuthoringQuickReply> replies = new ArrayList<>();
        AgenticAuthoringConsultativeApiCatalogProjection.Resource primary = firstResource(projection.resources());
        if (primary != null) {
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-show-fields:" + safeResourceId(primary),
                    "Ver campos",
                    "Quais campos confirmados existem em " + resourceLabel(primary) + "?",
                    "Mostra os campos confirmados antes de escolher tabela, formulário ou gráfico.",
                    "view_list",
                    "resource",
                    "explore",
                    "api_catalog",
                    "answer_api_catalog_question",
                    primary,
                    quickReplyPresentation(
                            "Boa para entender se essa fonte cobre os dados que você precisa.",
                            "Retorna campos confirmados e pistas de uso para colunas, filtros, formulários ou gráficos.",
                            "Clique para explorar os campos dessa fonte.")));
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-create-table:" + safeResourceId(primary),
                    "Criar tabela",
                    "Crie uma tabela filtrável usando " + resourceLabel(primary) + ".",
                    "Transforma a fonte confirmada em uma primeira tabela governada para revisão.",
                    "table_chart",
                    "primary",
                    "create",
                    "table",
                    "create_artifact",
                    primary,
                    quickReplyPresentation(
                            "Boa para listas operacionais, auditoria e navegação por registros.",
                            "Retorna uma prévia de tabela com colunas, paginação, filtros e formatação governada.",
                            "Clique para pedir uma tabela inicial com essa fonte.")));
        }
        AgenticAuthoringConsultativeApiCatalogProjection.Resource analytical =
                firstAnalyticalResource(projection.resources());
        if (analytical != null) {
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-create-chart:" + safeResourceId(analytical),
                    "Criar gráfico",
                    "Crie uma visão com gráficos usando " + resourceLabel(analytical) + ".",
                    "Usa uma fonte analítica confirmada para propor indicadores e visualizações.",
                    "query_stats",
                    "analytics",
                    "create",
                    "chart",
                    "create_artifact",
                    analytical,
                    quickReplyPresentation(
                            "Boa para indicadores, tendências, comparação e visão executiva.",
                            "Retorna uma prévia com gráficos compatíveis com os campos confirmados.",
                            "Clique para pedir uma composição analítica inicial.")));
        }
        AgenticAuthoringConsultativeApiCatalogProjection.Resource writable =
                firstWritableResource(projection.resources());
        if (writable != null) {
            replies.add(consultativeResourceQuickReply(
                    request,
                    intentResolution == null ? null : intentResolution.semanticDecision(),
                    "consultative-create-form:" + safeResourceId(writable),
                    "Criar formulário",
                    "Crie um formulário governado usando " + resourceLabel(writable) + ".",
                    "Usa uma operação confirmada de escrita para capturar ou atualizar dados com governança.",
                    "dynamic_form",
                    "resource",
                    "create",
                    "form",
                    "create_artifact",
                    writable,
                    quickReplyPresentation(
                            "Boa quando a fonte confirma uma ação de cadastro ou atualização.",
                            "Retorna uma prévia de formulário conectada à operação permitida pelo catálogo.",
                            "Clique para pedir um formulário inicial com essa fonte.")));
        }
        return replies.stream()
                .filter(Objects::nonNull)
                .limit(4)
                .toList();
    }

    private AgenticAuthoringQuickReply consultativeResourceQuickReply(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision parentDecision,
            String id,
            String label,
            String prompt,
            String description,
            String icon,
            String tone,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource,
            ObjectNode presentation) {
        ObjectNode contextHints = objectMapper.createObjectNode();
        contextHints.put("schemaVersion", "praxis-agentic-authoring-consultative-resource-choice.v1");
        contextHints.put("source", "consultative-api-catalog-projection");
        putText(contextHints, "resourceKey", resource.resourceKey());
        putText(contextHints, "resourcePath", resource.resourcePath());
        putText(contextHints, "resourceLabel", resourceLabel(resource));
        putText(contextHints, "resourceRole", resource.role());
        putText(contextHints, "operationKind", operationKind);
        putText(contextHints, "artifactKind", artifactKind);
        putText(contextHints, "changeKind", changeKind);
        if (presentation != null) {
            contextHints.set("presentation", presentation);
        }
        return new AgenticAuthoringQuickReply(
                id,
                "resource",
                label,
                prompt,
                description,
                icon,
                tone,
                contextHints,
                consultativeResourceSemanticDecision(
                        request,
                        parentDecision,
                        id,
                        operationKind,
                        artifactKind,
                        changeKind,
                        prompt,
                        resource),
                consultativeResourceChoiceValue(resource));
    }

    private JsonNode consultativeResourceSemanticDecision(
            AgenticAuthoringTurnStreamRequest request,
            AgenticAuthoringSemanticDecision parentDecision,
            String id,
            String operationKind,
            String artifactKind,
            String changeKind,
            String prompt,
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", "server-issued-quick-reply");
        constraints.put("quickReplyId", id);
        constraints.put("continuationOf", "api_catalog_projection");
        putText(constraints, "resourcePath", resource.resourcePath());
        putText(constraints, "resourceKey", resource.resourceKey());
        constraints.putArray("conceptKeys");
        AgenticAuthoringSemanticDecision decision = AgenticAuthoringSemanticDecision.from(
                        operationKind,
                        artifactKind,
                        changeKind,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        parentDecision,
                        request == null ? "" : request.sessionId(),
                        (request == null ? "" : request.clientTurnId()) + ":" + id,
                        prompt,
                        prompt,
                        "The user may select this governed resource action after catalog discovery.")
                .withConstraints(constraints);
        return objectMapper.valueToTree(decision);
    }

    private ObjectNode consultativeResourceChoiceValue(
            AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        ObjectNode value = objectMapper.createObjectNode();
        putText(value, "resourcePath", resource.resourcePath());
        putText(value, "resourceKey", resource.resourceKey());
        putText(value, "label", resourceLabel(resource));
        putText(value, "role", resource.role());
        return value;
    }

    private ObjectNode quickReplyPresentation(String bestFor, String returns, String nextStep) {
        ObjectNode presentation = objectMapper.createObjectNode();
        presentation.put("bestFor", bestFor);
        presentation.put("returns", returns);
        presentation.put("nextStep", nextStep);
        return presentation;
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource firstResource(
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources == null) {
            return null;
        }
        return resources.stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource firstAnalyticalResource(
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources == null) {
            return null;
        }
        return resources.stream()
                .filter(Objects::nonNull)
                .filter(resource -> {
                    String role = safeText(resource.role()).toLowerCase(Locale.ROOT);
                    return role.contains("analytic") || role.contains("indicator") || role.contains("metric");
                })
                .findFirst()
                .orElse(null);
    }

    private AgenticAuthoringConsultativeApiCatalogProjection.Resource firstWritableResource(
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        if (resources == null) {
            return null;
        }
        return resources.stream()
                .filter(Objects::nonNull)
                .filter(resource -> hasWriteAffordance(resource.actions()) || hasWriteEndpoint(resource.endpoints()))
                .findFirst()
                .orElse(null);
    }

    private boolean hasWriteAffordance(List<AgenticAuthoringConsultativeApiCatalogProjection.Action> actions) {
        if (actions == null) {
            return false;
        }
        return actions.stream()
                .filter(Objects::nonNull)
                .map(action -> safeText(action.name()) + " " + safeText(action.label()))
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("create")
                        || value.contains("update")
                        || value.contains("save")
                        || value.contains("cadastro")
                        || value.contains("atualizar"));
    }

    private boolean hasWriteEndpoint(List<AgenticAuthoringConsultativeApiCatalogProjection.Endpoint> endpoints) {
        if (endpoints == null) {
            return false;
        }
        return endpoints.stream()
                .filter(Objects::nonNull)
                .map(endpoint -> safeText(endpoint.method()).toUpperCase(Locale.ROOT))
                .anyMatch(method -> method.equals("POST") || method.equals("PUT") || method.equals("PATCH"));
    }

    String resourceLabel(AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        return AgenticAuthoringPresentationText.display(
                firstNonBlank(resource.label(), resource.resourceKey(), resource.resourcePath(), "fonte confirmada"));
    }

    String safeResourceId(AgenticAuthoringConsultativeApiCatalogProjection.Resource resource) {
        String value = firstNonBlank(resource.resourceKey(), resource.label(), resource.resourcePath(), "resource");
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return normalized.isBlank() ? "resource" : normalized;
    }

    JsonNode candidateContext(AgenticAuthoringCandidate candidate) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourcePath", safeText(candidate.resourcePath()));
        node.put("operation", safeText(candidate.operation()));
        node.put("schemaUrl", safeText(candidate.schemaUrl()));
        node.put("submitUrl", safeText(candidate.submitUrl()));
        node.put("submitMethod", safeText(candidate.submitMethod()));
        node.put("score", candidate.score());
        node.put("reason", safeText(candidate.reason()));
        ArrayNode evidence = node.putArray("evidence");
        if (candidate.evidence() != null) {
            candidate.evidence().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(evidence::add);
        }
        if (candidate.evidenceBundle() != null) {
            node.set("evidenceBundle", objectMapper.valueToTree(candidate.evidenceBundle()));
        }
        return node;
    }

    void putText(ObjectNode node, String field, String value) {
        if (node != null && StringUtils.hasText(value)) {
            node.put(field, value.trim());
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safeText(String value) {
        return value != null ? value : "";
    }
}
