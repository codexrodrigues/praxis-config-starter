package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Package-private support encapsulating consultative API catalog guidance, platform capability
 * explanations, component capability scoring, and clarification/assistant message synthesis for
 * {@link AgenticAuthoringIntentResolverService}.
 */
final class AgenticAuthoringApiCatalogGuidanceSupport {

    private final ObjectMapper objectMapper;
    private final AgenticAuthoringIntentQuickReplySupport quickReplySupport;

    AgenticAuthoringApiCatalogGuidanceSupport(
            ObjectMapper objectMapper,
            AgenticAuthoringIntentQuickReplySupport quickReplySupport) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.quickReplySupport = Objects.requireNonNull(quickReplySupport, "quickReplySupport must not be null");
    }

    List<String> clarificationQuestions(
            AgenticAuthoringGateResult gate,
            String operationKind,
            String artifactKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<String> questions = new ArrayList<>();
        for (String message : gate.messages()) {
            if ("resource-candidate-required".equals(message)) {
                questions.add("Qual recurso de negocio deve alimentar esta tela?");
            } else if ("resource-candidate-ambiguous".equals(message)) {
                String options = quickReplySupport.formatCandidateOptions(candidates);
                if (options.isBlank()) {
                    questions.add("Qual recurso candidato deve ser usado?");
                } else {
                    questions.add("Encontrei recursos proximos: " + options + ". Qual deles voce quer usar?");
                }
            } else if ("target-widget-required".equals(message)) {
                questions.add("Qual componente existente deve ser alterado?");
            } else if ("intent-operation-unknown".equals(message)) {
                questions.add("O que voce quer fazer com esse tema: visualizar, criar, alterar ou abrir um detalhe?");
            } else if ("intent-artifact-unknown".equals(message)) {
                questions.add("Voce quer criar ou alterar formulario, tabela, dashboard, stepper ou outro componente?");
            } else if ("analytics-breakdown-required".equals(message)) {
                questions.add("Qual recorte, metrica ou dimensao governada deve orientar este dashboard?");
            } else if ("intent-confirmation-required".equals(message)) {
                questions.add(confirmationQuestion(operationKind, artifactKind, selectedCandidate));
            }
        }
        return List.copyOf(questions);
    }

    String confirmationQuestion(String operationKind, String artifactKind, AgenticAuthoringCandidate selectedCandidate) {
        if ("table".equals(artifactKind)) {
            return "Posso criar uma tabela usando o recurso de negocio selecionado?";
        }
        if ("form".equals(artifactKind)) {
            return "Posso criar um formulario usando o recurso de negocio selecionado?";
        }
        if ("dashboard".equals(artifactKind)) {
            return "Posso criar um dashboard usando o recurso de negocio selecionado?";
        }
        return "Posso aplicar esta alteracao usando o recurso de negocio selecionado?";
    }

    boolean isConsultativeCatalogQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean asksQuestion = containsAny(normalized,
                "quais", "qual", "que outras", "outras tabelas", "outras fontes",
                "o que posso", "o que da para", "o que dá para", "sugira", "sugerir",
                "compare", "comparar", "compara", "mostre", "mostrar", "liste", "listar");
        boolean materializationIntent = containsAny(normalized,
                "crie", "criar tela", "criar uma tela", "organize", "organizar",
                "editar", "edite", "formulario guiado", "materializar", "preview");
        if (materializationIntent) {
            return false;
        }
        boolean asksCatalog = containsAny(normalized,
                "tabela", "tabelas", "tela", "telas", "pagina", "paginas",
                "api", "apis", "fonte", "fontes", "recurso", "recursos",
                "outras fontes", "outra fonte", "outros recursos", "outro recurso",
                "dados", "dado",
                "campo", "campos", "schema", "schemas");
        boolean asksFieldCatalog = containsAny(normalized, "campo", "campos", "schema", "schemas");
        boolean asksRelationship = containsAny(normalized,
                "referencia", "referencias", "relacao", "relacionamento", "relacionadas", "relacionados");
        boolean asksCreation = containsAny(normalized,
                "podem ser criadas", "posso criar", "daria para criar", "da para criar", "dá para criar",
                "recomenda criar", "recomende criar", "recomendar criar");
        return asksQuestion && ((asksCatalog && (asksRelationship || asksCreation)) || asksFieldCatalog);
    }

    boolean isConsultativeDomainQuestion(String prompt) {
        return isConsultativeCatalogQuestion(prompt)
                || isConsultativeGovernanceQuestion(prompt);
    }

    boolean isDomainDataCatalogQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "dados", "fonte", "fontes", "recurso", "recursos",
                "api", "apis", "schema", "schemas", "campo", "campos",
                "tabela de dados", "catalogo de dados", "catálogo de dados",
                "dominio", "domínio", "entidade", "entidades");
    }

    boolean isConsultativeFormPolicyQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "formulario", "formulário", "livre", "livremente",
                "predefinido", "predefinidos", "pre definido", "pre definidos",
                "governado", "governada");
    }

    boolean isConsultativePlatformCapabilityQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean question = containsAny(normalized,
                "o que posso", "o que consigo", "o que da para", "o que dá para",
                "o que voce pode", "o que você pode", "como faco", "como faço",
                "como habilito", "como configurar", "como configuro", "como funciona",
                "como criar", "como montar", "quais componentes", "que componentes",
                "quais widgets", "que widgets", "quais telas", "que telas",
                "quais paginas", "que paginas", "posso criar", "daria para criar",
                "da para criar", "dá para criar", "posso montar", "posso fazer");
        boolean platformSubject = containsAny(normalized,
                "aqui", "praxis", "page builder", "builder", "assistente",
                "componente", "componentes", "widget", "widgets",
                "tela", "telas", "pagina", "paginas", "dashboard", "painel",
                "formulario", "formulário", "tabela", "grafico", "gráfico",
                "botao", "botão", "exportar", "selecionada", "selecionadas",
                "aba", "abas", "tabs", "stepper", "administrativo", "admin",
                "livremente", "predefinido", "predefinidos", "pre definido", "pre definidos");
        boolean directMaterializationCommand = containsAny(normalized,
                "crie ", "criar agora", "monte ", "montar agora", "gere ", "gerar agora",
                "faca ", "faça ", "materialize", "materializar", "preview", "pre visualizacao");
        boolean dataCatalogSubject = containsAny(normalized,
                "dados", "fonte", "fontes", "api", "apis", "schema", "schemas",
                "campo", "campos", "recurso", "recursos", "entidade", "entidades");
        boolean formPolicySubject = containsAny(normalized,
                "formulario", "formulário", "livre", "livremente",
                "predefinido", "predefinidos", "pre definido", "pre definidos",
                "governado", "governada");
        return question && platformSubject && !directMaterializationCommand && (!dataCatalogSubject || formPolicySubject);
    }

    String platformCapabilityChangeKind(String prompt) {
        String normalized = normalize(prompt);
        if (containsAny(normalized,
                "habilito", "configurar", "configuro", "botao", "botão",
                "exportar", "selecionada", "selecionadas", "linhas selecionadas")) {
            return "answer_component_capability_question";
        }
        return "answer_component_catalog_question";
    }

    boolean isConsultativeGovernanceQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean question = containsAny(normalized,
                "quais", "qual", "que campos", "quais campos", "onde", "como",
                "liste", "listar", "mostre", "mostrar", "consultar", "consulta",
                "what", "which", "where", "how", "list", "show");
        boolean governanceSubject = containsAny(normalized,
                "lgpd", "gdpr",
                "governanca", "governance",
                "mascar", "mask",
                "privacidade", "privacy",
                "dados sensiveis", "dado sensivel", "sensitive data",
                "personal data", "dados pessoais");
        boolean authoringAction = containsAny(normalized,
                "crie", "criar", "create",
                "aplique", "aplicar", "apply",
                "implemente", "implementar", "implement",
                "bloqueie", "bloquear", "block",
                "mascare", "mascarar",
                "exigir", "exija", "require",
                "valide", "validar", "validate",
                "publique", "publicar", "publish",
                "simule", "simular", "simulate",
                "aprove", "aprovar", "approve");
        return question && governanceSubject && !authoringAction;
    }

    String assistantMessage(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates,
            AgenticAuthoringGateResult gate,
            boolean answeredBareDomainClarification,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (gate != null && gate.messages().contains("shared-rule-authoring-required")) {
            return sharedRuleAuthoringAssistantMessage(selectedCandidate);
        }
        if ("api_catalog".equals(artifactKind) && isConsultativePlatformCapabilityQuestion(prompt)) {
            return platformCapabilityAssistantMessage(prompt, componentCapabilities);
        }
        if ("component".equals(artifactKind) && isConsultativePlatformCapabilityQuestion(prompt)) {
            return platformCapabilityAssistantMessage(prompt, componentCapabilities);
        }
        if ("explore".equals(operationKind)
                && "api_catalog".equals(artifactKind)
                && isConsultativeDomainQuestion(prompt)) {
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (gate != null && gate.messages().contains("resource-candidate-required")) {
            return quickReplySupport.missingResourceAssistantMessage(artifactKind);
        }
        if (gate != null
                && gate.messages().contains("resource-candidate-ambiguous")
                && selectedCandidate == null) {
            return quickReplySupport.ambiguousResourceAssistantMessage(artifactKind, candidates);
        }
        if ("unknown".equals(valueOrUnknown(operationKind))
                || "unknown".equals(valueOrUnknown(artifactKind))) {
            return unresolvedIntentAssistantMessage();
        }
        if (!"explore".equals(operationKind)) {
            return authoringAssistantMessage(operationKind, artifactKind, changeKind, selectedCandidate);
        }
        if ("api_catalog".equals(artifactKind)) {
            return apiCatalogAssistantMessage(prompt, selectedCandidate, candidates);
        }
        if (answeredBareDomainClarification) {
            return null;
        }
        if ("dashboard".equals(artifactKind)) {
            return "Entendi que voce quer um painel analitico. Posso preparar uma previa com a fonte de negocio mais aderente, ou ajustar antes quais metricas e recortes devem aparecer.";
        }
        if ("table".equals(artifactKind)) {
            return "Consigo montar essa tabela. Antes de criar, preciso apenas confirmar a fonte de negocio e quais colunas devem aparecer como principais.";
        }
        return "Posso ajudar a transformar esse pedido em uma tela. Se voce me disser se prefere dashboard, tabela, formulario ou uma pagina mais completa, eu sigo pelo caminho certo.";
    }

    boolean shouldUsePlatformGuidanceMessage(String prompt, String assistantMessage) {
        String normalizedPrompt = normalize(prompt);
        String normalizedMessage = normalize(assistantMessage);
        if (normalizedMessage.isBlank()) {
            return true;
        }
        boolean asksForms = containsAny(normalizedPrompt, "formulario", "formulário", "livre", "predefinido", "pre definido");
        boolean asksComponents = containsAny(normalizedPrompt,
                "componentes", "widgets", "telas", "paginas", "páginas", "o que posso",
                "administrativo", "admin", "painel", "dashboard");
        boolean asksComponentCapability = containsAny(normalizedPrompt,
                "habilito", "habilitar", "configurar", "configuro",
                "botao", "botão", "exportar", "selecionada", "selecionadas", "tabela");
        boolean explainsPlatform = containsAny(normalizedMessage,
                "praxis", "componente", "componentes", "governado", "governada",
                "formulario", "formulário", "tabela", "dashboard", "grafico", "gráfico");
        boolean dataCatalogAnswer = containsAny(normalizedMessage,
                "encontrei dados governados em", "pelos campos confirmados", "campos confirmados",
                "fonte de dados", "fontes de dados");
        boolean technicalLeak = containsAny(normalizedMessage,
                "/api/", "/schemas/", "endpoint", "endpoints", "schema", "schemas", "json", "post", "get",
                ".enabled", ".scope", ".formats", "selection.enabled", "export.general", "toolbar.actions",
                "api", "apis", "backend", "schemas", "esquemas", "actions", "acoes", "ações");
        boolean plainLanguageRequested = containsAny(normalizedPrompt,
                "sem termos tecnicos", "sem termo tecnico", "linguagem natural", "conversa natural");
        return (asksForms || asksComponents || asksComponentCapability)
                && (!explainsPlatform || dataCatalogAnswer || technicalLeak || plainLanguageRequested);
    }

    boolean shouldUseApiCatalogGuidanceMessage(String assistantMessage) {
        String rawMessage = valueOrDefault(assistantMessage, "").toLowerCase(Locale.ROOT);
        if (rawMessage.contains("/api") || rawMessage.contains("/schemas") || rawMessage.contains("url")) {
            return true;
        }
        if (rawMessage.indexOf("fonte confirmada") >= 0
                && rawMessage.indexOf("fonte confirmada") != rawMessage.lastIndexOf("fonte confirmada")) {
            return true;
        }
        String normalizedMessage = normalize(assistantMessage);
        return containsAny(normalizedMessage,
                "vou consultar",
                "vou investigar",
                "consultar o catalogo",
                "consultar as fontes",
                "investigar as capacidades",
                "nao temos dados",
                "não temos dados",
                "nao encontrei dados",
                "não encontrei dados",
                "nao temos informacoes",
                "não temos informações",
                "informacoes especificas",
                "informações específicas",
                "dados especificos disponiveis",
                "dados específicos disponíveis",
                "dados governados",
                "/api/",
                "/schemas/",
                "endpoint",
                "endpoints",
                "schema",
                "schemas",
                "esquema",
                "esquemas",
                "json",
                "post",
                "get",
                "actions");
    }

    String sanitizeApiCatalogConsultativeLanguage(
            String assistantMessage,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        String sanitized = quickReplySupport.sanitizePresentationText(assistantMessage, selectedCandidate, candidates);
        if (sanitized == null || sanitized.isBlank()) {
            return sanitized;
        }
        return sanitized
                .replaceAll("(?i)\\bendpoints?\\b", "fontes")
                .replaceAll("(?i)\\bschemas?\\b", "campos confirmados")
                .replaceAll("(?i)\\bjson\\b", "estrutura")
                .replaceAll("(?i)\\burls? de submiss[aã]o\\b", "formas de uso")
                .replaceAll("(?i)\\burls?\\b", "referencias")
                .replaceAll("(?i)\\bPOST\\b", "consulta ou envio")
                .replaceAll("(?i)\\bGET\\b", "consulta")
                .replaceAll("(?i)\\bPUT\\b", "atualizacao")
                .replaceAll("(?i)\\bPATCH\\b", "ajuste")
                .replaceAll("(?i)\\bDELETE\\b", "remocao")
                .replaceAll("(?i)\\bactions?\\b", "acoes disponiveis")
                .replace("cada fontes", "cada fonte");
    }

    String platformCapabilityAssistantMessage(
            String prompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        String normalized = normalize(prompt);
        Optional<ComponentCapabilityMatch> capabilityMatch = bestComponentCapabilityMatch(normalized, componentCapabilities);
        if (capabilityMatch.isPresent()
                && capabilityMatch.get().score() >= 3
                && containsAny(normalized,
                "habilito", "habilitar", "configurar", "configuro", "como",
                "botao", "botão", "exportar", "selecionada", "selecionadas")) {
            return componentCapabilityAssistantMessage(capabilityMatch.get());
        }
        List<String> families = componentFamilyLabels(componentCapabilities);
        String componentText = families.isEmpty()
                ? "tabelas, formularios, dashboards, graficos, filtros, abas e fluxos em etapas"
                : humanJoin(families);
        if (containsAny(normalized, "formulario", "formulário", "livre", "predefinido", "pre definido")) {
            return "Voce pode descrever o formulario em linguagem natural, mas no Praxis a versao pronta para uso deve ficar ligada a uma decisao governada: dados, campos, acoes e regras confirmados pelo backend. "
                    + "Tambem posso ajudar com um rascunho mais livre para explorar a ideia, mas antes de publicar ou materializar eu valido o que existe no dominio. "
                    + "Na pratica, eu posso transformar seu pedido em formulario de cadastro, edicao ou solicitacao quando houver uma fonte e uma acao governada para sustentar isso.";
        }
        if (containsAny(normalized, "administrativo", "admin", "painel")) {
            return "Para montar um painel administrativo, voce pode me dizer o objetivo em linguagem natural: acompanhar indicadores, revisar registros, operar cadastros ou organizar etapas de um processo. "
                    + "Eu descubro os componentes governados disponiveis, combino isso com o dominio e proponho uma tela com tabelas, graficos, filtros, formularios ou abas conforme fizer sentido. "
                    + "Nada precisa nascer como JSON manual: a decisao vem primeiro, e a tela e materializada a partir dela.";
        }
        if (containsAny(normalized,
                "exportar", "selecionada", "selecionadas", "linhas selecionadas", "botao", "botão", "tabela")) {
            return "Em uma tabela do Praxis, a exportacao de linhas selecionadas deve ser tratada como capacidade governada do componente: a tabela precisa permitir selecao, expor uma acao de toolbar ou acao em massa e executar a exportacao usando apenas as linhas marcadas. "
                    + "Eu posso te orientar nessa configuracao ou, quando houver uma tabela selecionada, preparar a alteracao para revisao.";
        }
        return "Aqui voce pode conversar comigo em linguagem natural sobre a intencao da tela antes de criar qualquer coisa. "
                + "O Praxis usa componentes governados como " + componentText + ", escolhidos de acordo com o objetivo, o dominio disponivel e as regras de materializacao. "
                + "Voce pode pedir algo como um dashboard para acompanhar indicadores, uma tabela operacional, um formulario governado, uma pagina com abas ou um fluxo em etapas. "
                + "Quando o pedido ficar concreto, eu valido dados, campos e acoes antes de gerar a previa.";
    }

    private Optional<ComponentCapabilityMatch> bestComponentCapabilityMatch(
            String normalizedPrompt,
            AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (normalizedPrompt == null
                || normalizedPrompt.isBlank()
                || componentCapabilities == null
                || componentCapabilities.catalogs() == null) {
            return Optional.empty();
        }
        return componentCapabilities.catalogs().stream()
                .filter(Objects::nonNull)
                .flatMap(catalog -> nullToEmpty(catalog.capabilities()).stream()
                        .filter(Objects::nonNull)
                        .map(capability -> new ComponentCapabilityMatch(
                                catalog.componentId(),
                                capability,
                                componentCapabilityScore(normalizedPrompt, catalog.componentId(), capability))))
                .filter(match -> match.score() > 0)
                .max(Comparator
                        .comparingInt(ComponentCapabilityMatch::score)
                        .thenComparing(match -> valueOrDefault(match.componentId(), "")));
    }

    private int componentCapabilityScore(
            String normalizedPrompt,
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability) {
        int score = 0;
        String normalizedComponent = normalize(componentId);
        if (!normalizedComponent.isBlank() && normalizedPrompt.contains(normalizedComponent.replace('-', ' '))) {
            score += 2;
        }
        String normalizedChangeKind = normalize(capability.changeKind()).replace('_', ' ');
        if (!normalizedChangeKind.isBlank() && normalizedPrompt.contains(normalizedChangeKind)) {
            score += 2;
        }
        for (String term : nullToEmpty(capability.triggerTerms())) {
            String normalizedTerm = normalize(term);
            if (!normalizedTerm.isBlank() && normalizedPrompt.contains(normalizedTerm)) {
                score += normalizedTerm.contains(" ") ? 2 : 1;
            }
        }
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example : nullToEmpty(capability.examples())) {
            String exampleText = normalize(valueOrDefault(example.prompt(), "") + " " + valueOrDefault(example.intent(), ""));
            for (String token : normalizedPrompt.split("\\s+")) {
                if (token.length() >= 5 && exampleText.contains(token)) {
                    score++;
                }
            }
        }
        return score;
    }

    private String componentCapabilityAssistantMessage(ComponentCapabilityMatch match) {
        String componentLabel = componentLabel(match.componentId());
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability = match.capability();
        AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityExample example =
                nullToEmpty(capability.examples()).stream().findFirst().orElse(null);
        String intent = example == null ? "" : valueOrDefault(example.intent(), "");
        List<String> hints = example == null ? List.of() : nullToEmpty(example.configHints());
        StringBuilder message = new StringBuilder();
        message.append("Sim. No Praxis, isso e uma capacidade governada do componente ")
                .append(componentLabel)
                .append(", nao um atalho solto da tela.");
        if (!intent.isBlank()) {
            message.append(" A intencao e ").append(stripTrailingSentencePunctuation(lowercaseFirst(intent))).append(".");
        }
        if (!hints.isEmpty()) {
            message.append(" Para funcionar bem, a decisao precisa cobrir ")
                    .append(humanJoin(hints.stream()
                            .map(this::humanizeConfigHint)
                            .filter(hint -> !hint.isBlank())
                            .distinct()
                            .limit(5)
                            .toList()))
                    .append(".");
        }
        message.append(" Se ja houver uma tabela selecionada na pagina, eu posso preparar essa alteracao para revisao usando a configuracao governada do componente.");
        return message.toString();
    }

    private String humanizeConfigHint(String hint) {
        String normalized = normalize(hint);
        if (normalized.contains("selection.enabled")) {
            return "selecao de linhas habilitada";
        }
        if (normalized.contains("toolbar") || normalized.contains("acao em massa") || normalized.contains("action")) {
            return "uma acao de exportar na barra da tabela ou nas acoes em massa";
        }
        if (normalized.contains("export.enabled")) {
            return "exportacao habilitada";
        }
        if (normalized.contains("export.formats")) {
            return "formatos permitidos, como CSV ou Excel";
        }
        if (normalized.contains("scope=selected") || normalized.contains("scope selected")) {
            return "escopo limitado aos registros selecionados";
        }
        if (normalized.contains("includeheaders")) {
            return "cabecalhos no arquivo exportado quando fizer sentido";
        }
        return hint
                .replace("=", " ")
                .replace(".", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String componentLabel(String componentId) {
        String normalized = valueOrDefault(componentId, "").trim();
        if (normalized.isBlank()) {
            return "governado";
        }
        return Stream.of(normalized.split("[^A-Za-z0-9]+"))
                .filter(part -> !part.isBlank())
                .map(part -> part.equalsIgnoreCase("praxis")
                        ? "Praxis"
                        : part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    private String lowercaseFirst(String value) {
        String text = valueOrDefault(value, "").trim();
        if (text.isBlank()) {
            return text;
        }
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    private String stripTrailingSentencePunctuation(String value) {
        return valueOrDefault(value, "").replaceAll("[.!?]+$", "").trim();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ComponentCapabilityMatch(
            String componentId,
            AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability,
            int score) {
    }

    private List<String> componentFamilyLabels(AgenticAuthoringComponentCapabilitiesResult componentCapabilities) {
        if (componentCapabilities == null || componentCapabilities.catalogs() == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog catalog : componentCapabilities.catalogs()) {
            String componentId = valueOrDefault(catalog == null ? null : catalog.componentId(), "");
            String normalized = normalize(componentId);
            if (normalized.contains("chart")) {
                labels.add("graficos");
            } else if (normalized.contains("table") || normalized.contains("grid")) {
                labels.add("tabelas");
            } else if (normalized.contains("form")) {
                labels.add("formularios");
            } else if (normalized.contains("tabs")) {
                labels.add("abas");
            } else if (normalized.contains("stepper") || normalized.contains("wizard")) {
                labels.add("fluxos em etapas");
            } else if (normalized.contains("filter")) {
                labels.add("filtros");
            } else if (normalized.contains("crud")) {
                labels.add("CRUDs governados");
            }
        }
        return labels.stream().distinct().limit(8).toList();
    }

    String unresolvedIntentAssistantMessage() {
        return "Ainda nao consegui entender isso com seguranca. Nao vou criar nem alterar nada agora. "
                + "Voce pode me pedir para explorar os dados disponiveis, comparar fontes de negocio ou gerar uma tela especifica.";
    }

    String authoringAssistantMessage(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate) {
        String resourceSummary = selectedCandidate == null || valueOrDefault(selectedCandidate.reason(), "").isBlank()
                ? "a fonte de negocio mais aderente"
                : "a fonte de negocio selecionada";
        if ("set_chart_type".equals(changeKind)) {
            return "Atualizei o grafico selecionado mantendo os dados, a dimensao e a metrica atuais.";
        }
        if ("chart".equals(artifactKind) && "modify".equals(operationKind)) {
            return "Atualizei o grafico mantendo os dados confirmados e o contexto atual.";
        }
        if ("chart".equals(artifactKind)) {
            return "Entendi. Vou preparar o grafico usando dados confirmados e manter a decisao pronta para revisao.";
        }
        if ("dashboard".equals(artifactKind) && "create_chart_drilldown".equals(changeKind)) {
            return "Atualizei a composicao para mostrar detalhes a partir da selecao do grafico, mantendo a fonte e os campos confirmados.";
        }
        if ("dashboard".equals(artifactKind) && "create_chart".equals(changeKind)) {
            return "Entendi. Vou preparar somente o grafico pedido, usando " + resourceSummary
                    + ", sem adicionar tabela, filtros ou KPIs.";
        }
        if ("dashboard".equals(artifactKind)) {
            return "Entendi o painel que voce quer montar. Vou usar " + resourceSummary
                    + " para preparar uma previa com metricas e recortes coerentes.";
        }
        if ("table".equals(artifactKind)) {
            return "Entendi a tabela que voce quer montar. Vou usar " + resourceSummary
                    + " para preparar as colunas principais e manter a decisao pronta para revisao.";
        }
        if ("form".equals(artifactKind)) {
            return "Entendi o formulario que voce quer criar. Vou usar " + resourceSummary
                    + " para sugerir os campos certos e manter a decisao pronta para revisao.";
        }
        return "Entendi o que voce quer criar. Vou usar " + resourceSummary
                + " para preparar uma previa e manter a decisao pronta para revisao.";
    }

    String sharedRuleAuthoringAssistantMessage(AgenticAuthoringCandidate selectedCandidate) {
        String resourcePath = selectedCandidate == null ? "" : valueOrDefault(selectedCandidate.resourcePath(), "").trim();
        if (!resourcePath.isBlank()) {
            return "Esse pedido parece ser uma regra compartilhada do dominio, nao apenas uma mudanca visual da tela. Use /api/praxis/config/domain-rules/intake para registrar a intencao, /api/praxis/config/domain-rules/simulations para simulacao de impacto e cobertura, e siga por revisao e publicacao antes de aplicar qualquer materializacao.";
        }
        return "Esse pedido parece ser uma regra compartilhada do dominio, nao apenas uma mudanca visual da tela. Primeiro confirme o recurso de negocio e entao use /api/praxis/config/domain-rules/intake e /api/praxis/config/domain-rules/simulations para seguir por simulacao, revisao e publicacao com governanca.";
    }

    JsonNode apiCatalogAnswer(
            String prompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        if (!quickReplySupport.isApiCatalogQuestion(operationKind, artifactKind, changeKind)
                || candidates == null || candidates.isEmpty()) {
            return null;
        }
        return objectMapper.valueToTree(Map.of(
                "schemaVersion", "praxis-agentic-authoring-api-catalog-grounding.v1",
                "questionType", "api_catalog_grounding",
                "candidateApis", candidates));
    }

    String apiCatalogAssistantMessage(
            String prompt,
            AgenticAuthoringCandidate selectedCandidate,
            List<AgenticAuthoringCandidate> candidates) {
        List<AgenticAuthoringCandidate> usableCandidates = candidates == null ? List.of() : candidates;
        if (isConsultativePlatformCapabilityQuestion(prompt)) {
            return platformCapabilityAssistantMessage(prompt, usableCandidates);
        }
        if (usableCandidates.isEmpty()) {
            return "Ainda nao encontrei uma fonte de negocio confiavel para esse tema. Posso procurar melhor no catalogo ou voce pode me dizer qual dominio devo usar.";
        }
        if (isConsultativeDomainQuestion(prompt)) {
            if (isApiCatalogResourceListPrompt(prompt)) {
                return apiCatalogResourceListAssistantMessage(usableCandidates);
            }
            if (!isApiOperationCatalogQuestion(prompt)) {
                return "Encontrei algumas fontes de dados relacionadas ao que voce perguntou. Posso comparar para que serve cada uma, explicar os campos disponiveis e recomendar telas sem criar tela nem alterar regra agora.";
            }
        }
        if (containsAny(prompt, "schema", "schemas", "campo", "campos")) {
            return "Encontrei a fonte de negocio para essa pergunta. Posso te responder sobre os campos e cuidados de governanca sem criar tela nem alterar regra agora.";
        }
        if (isApiOperationCatalogQuestion(prompt)) {
            return "Encontrei a fonte de negocio e posso verificar quais operacoes fazem sentido para ela. Vou manter isso como consulta, sem alterar a aplicacao.";
        }
        if (containsAny(prompt, "filtro", "filtros", "filtrar", "filter")) {
            return "Encontrei a fonte de negocio e posso olhar os filtros e recortes disponiveis para ela. Nao vou criar nada ainda; primeiro respondo a consulta.";
        }
        if (isConsultativeDomainQuestion(prompt)) {
            return "Encontrei algumas fontes de dados que podem responder isso. Posso comparar as opcoes em linguagem simples antes de criar qualquer tela ou regra.";
        }

        String endpoints = usableCandidates.stream()
                .limit(4)
                .map(candidate -> {
                    String reason = valueOrDefault(candidate.reason(), "");
                    return isHumanCatalogReason(reason) ? reason : quickReplySupport.candidateLabel(candidate);
                })
                .map(this::humanizeCatalogCandidate)
                .filter(label -> !label.isBlank())
                .reduce((left, right) -> left + "; " + right)
                .orElse("fonte de negocio do catalogo");
        String message = "Encontrei algumas fontes de negocio candidatas: " + endpoints;
        if (containsAny(prompt, "devo usar", "melhor", "recomenda", "recomende", "dashboard", "tabela")) {
            message += ". Posso te ajudar a escolher a que melhor representa o objetivo antes de criar a tela.";
        }
        return message;
    }

    String apiCatalogResourceListAssistantMessage(List<AgenticAuthoringCandidate> candidates) {
        List<String> labels = (candidates == null ? List.<AgenticAuthoringCandidate>of() : candidates).stream()
                .limit(4)
                .map(candidate -> {
                    String reason = valueOrDefault(candidate.reason(), "");
                    return isHumanCatalogReason(reason) ? reason : quickReplySupport.candidateLabel(candidate);
                })
                .map(this::humanizeCatalogCandidate)
                .filter(label -> !label.isBlank())
                .distinct()
                .toList();
        if (labels.isEmpty()) {
            return "Encontrei fontes de negocio candidatas para esse tema, mas ainda preciso abrir os detalhes governados antes de recomendar uma delas.";
        }
        return "Encontrei " + labels.size() + " fonte" + (labels.size() == 1 ? "" : "s")
                + " de dados relacionada" + (labels.size() == 1 ? "" : "s")
                + ": " + humanJoin(labels) + ". Posso comparar para que serve cada uma, explicar os campos disponiveis ou usar a mais aderente para montar uma tela. Vou manter isso como consulta ate voce pedir para criar algo.";
    }

    boolean isApiOperationCatalogQuestion(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        boolean asksOperation = containsAny(normalized,
                "action", "actions", "acao", "acoes", "ação", "ações",
                "operacao", "operacoes", "operação", "operações",
                "permite", "permitidas", "permitidos",
                "criar registro", "editar registro", "alterar registro", "excluir registro",
                "salvar", "atualizar", "remover", "submeter");
        boolean asksRecommendedScreen = containsAny(normalized,
                "tela", "telas", "pagina", "paginas", "página", "páginas",
                "dashboard", "painel", "recomenda criar", "recomende criar", "que telas");
        boolean asksDataCatalog = containsAny(normalized,
                "quais dados", "que dados", "dados existem", "fontes", "recursos",
                "o que posso consultar", "posso consultar", "dados disponiveis", "dados disponíveis");
        return asksOperation && !asksRecommendedScreen && !asksDataCatalog;
    }

    boolean isApiCatalogResourceListPrompt(String prompt) {
        String normalized = normalize(prompt);
        return containsAny(normalized,
                "quais dados",
                "que dados",
                "dados existem",
                "dados que existem",
                "dados existentes",
                "dados disponiveis",
                "dados disponíveis",
                "o que posso consultar",
                "posso consultar",
                "que telas recomenda",
                "quais telas recomenda",
                "telas recomenda criar",
                "quais apis",
                "quais api",
                "apis de",
                "api de",
                "quais recursos",
                "recursos disponiveis",
                "recursos disponíveis",
                "fontes candidatas");
    }

    private boolean isHumanCatalogReason(String reason) {
        String normalized = normalize(reason);
        return !normalized.isBlank()
                && !reason.contains("/")
                && !containsAny(normalized,
                "fonte confirmada",
                "fonte governada",
                "fonte de negocio",
                "fonte de negócio",
                "evidence",
                "fallback",
                "weak lexical",
                "api metadata",
                "api_metadata",
                "broad artifact",
                "artifact discovery",
                "discovery",
                "semantic retrieval",
                "semantic_retrieval",
                "retrieval",
                "domain catalog",
                "domain_catalog",
                "grounded",
                "resource selection",
                "provenance",
                "listar",
                "filter",
                "operation");
    }

    private String humanizeCatalogCandidate(String value) {
        String text = valueOrDefault(value, "")
                .replace("/api/", "")
                .replace("vw-", "")
                .replace("-", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.isBlank()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    String platformCapabilityAssistantMessage(String prompt, List<AgenticAuthoringCandidate> candidates) {
        StringBuilder message = new StringBuilder(
                "Aqui voce pode conversar comigo em linguagem natural para explorar o dominio, escolher componentes governados e criar paginas, dashboards, tabelas, graficos, formularios ou composicoes como abas e fluxos por etapas.");
        if (containsAny(prompt, "formulario", "formulário", "livremente", "predefinido", "predefinidos")) {
            message.append(" Formularios nao precisam ser escolhidos como um template fixo: voce descreve o processo, mas os campos e a acao final precisam ser confirmados por dados, acoes e governanca antes de salvar.");
        } else if (containsAny(prompt, "painel", "dashboard", "administrativo", "admin")) {
            message.append(" Para um painel administrativo, normalmente eu ajudo a escolher a fonte certa, sugerir indicadores, graficos, filtros e detalhes, e so materializo quando houver uma direcao confirmada.");
        }
        if (candidates != null && !candidates.isEmpty()) {
            message.append(" Tambem posso usar as fontes de negocio encontradas para recomendar caminhos mais concretos antes de criar qualquer coisa.");
        }
        return message.toString();
    }

    private String humanJoin(List<String> values) {
        return AgenticAuthoringTextNormalizationSupport.humanJoin(values);
    }

    private boolean containsAny(String value, String... tokens) {
        return AgenticAuthoringTextNormalizationSupport.containsAny(value, tokens);
    }

    private String normalize(String value) {
        return AgenticAuthoringTextNormalizationSupport.normalize(value);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return AgenticAuthoringTextNormalizationSupport.valueOrDefault(value, defaultValue);
    }

    private String valueOrUnknown(String value) {
        return AgenticAuthoringTextNormalizationSupport.valueOrUnknown(value);
    }
}
