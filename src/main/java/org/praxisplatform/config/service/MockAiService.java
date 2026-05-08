package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiProviderModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementacao mock do provider AI usada para desenvolvimento, testes e fallback controlado.
 *
 * <p>Ela reconhece alguns prompts canônicos do orquestrador e devolve respostas deterministicas,
 * permitindo exercitar classificacao de intencao, patches e catalogos sem depender de um provedor
 * externo.
 */
@Service
@ConditionalOnProperty(name = "praxis.ai.mock.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MockAiService implements AiProvider {

    private static final Pattern USER_INPUT_PATTERN =
            Pattern.compile("(ENTRADA|PEDIDO|PERGUNTA) DO USU\\p{L}+:\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    @Override
    public JsonNode generateJson(String prompt) {
        return generateJson(prompt, null, null);
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema) {
        return generateJson(prompt, schema, null);
    }

    @Override
    public JsonNode generateJson(String prompt, AiJsonSchema schema, AiCallConfig config) {
        String userInput = extractUserInput(prompt);
        String normalized = normalize(userInput);

        if (isIntentPrompt(prompt)) {
            return buildIntentClassification(normalized);
        }
        if (isTableActionPlanPrompt(prompt)) {
            return buildTableActionPlan(normalized);
        }
        if (isComponentActionPlanPrompt(prompt)) {
            return buildComponentActionPlan(prompt, normalized);
        }
        if (isPatchPrompt(prompt)) {
            return buildPatchResponse(normalized);
        }

        log.debug("[MockAiService] Unrecognized prompt, returning empty object.");
        return objectMapper.createObjectNode();
    }

    @Override
    public String generateText(String prompt) {
        return "mock-response";
    }

    @Override
    public String getProviderName() {
        return "mock";
    }

    @Override
    public List<AiProviderModel> listModels(AiCallConfig config) {
        return List.of(
                AiProviderModel.builder()
                        .name("mock-default")
                        .displayName("Mock Default")
                        .description("Mock provider model")
                        .supportedGenerationMethods(List.of("generateContent"))
                        .build());
    }

    private boolean isIntentPrompt(String prompt) {
        return prompt != null && prompt.contains("SCHEMA DE RESPOSTA (JSON)")
                && prompt.contains("\"intent\"");
    }

    private boolean isTableActionPlanPrompt(String prompt) {
        return prompt != null && prompt.contains("CATALOGO DE ACOES")
                && (prompt.contains("COLUNAS DISPONIVEIS") || prompt.contains("COLUMNS DISPONIVEIS"));
    }

    private boolean isComponentActionPlanPrompt(String prompt) {
        return prompt != null && prompt.contains("CATALOGO DE ACOES")
                && (prompt.contains("CANDIDATOS DE ALVO")
                || prompt.contains("tab.add")
                || prompt.contains("tab.label.set")
                || prompt.contains("field.local.add"));
    }

    private boolean isPatchPrompt(String prompt) {
        return prompt != null && prompt.contains("CAPABILITIES PERMITIDAS")
                && prompt.contains("\"patch\"");
    }

    private String extractUserInput(String prompt) {
        if (prompt == null) {
            return "";
        }
        Matcher matcher = USER_INPUT_PATTERN.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return "";
    }

    private String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT).trim();
    }

    private JsonNode buildIntentClassification(String input) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("intent", "update_column");
        root.put("category", "columns");
        root.put("scope", "config");
        root.put("needsClarification", false);
        root.putArray("missingContext");
        root.putArray("options");

        if (isDynamicFormComponentAuthoring(input)) {
            root.put("intent", "update_component");
            root.put("category", "fields");
            root.put("scope", "component");
            root.putNull("targetField");
            return root;
        }
        if (input.contains("densidade") || input.contains("density")) {
            root.put("category", "appearance");
            root.putNull("targetField");
            return root;
        }
        if (input.contains("export")) {
            root.put("category", "export");
            root.putNull("targetField");
            return root;
        }
        if (input.contains("aba") || input.contains("tab")) {
            root.put("intent", "toggle_feature");
            root.put("category", "tabs");
            root.putNull("targetField");
            return root;
        }
        if (input.contains("endpoint") || input.contains("api")) {
            root.putNull("targetField");
            return root;
        }
        if (input.contains("status") && input.contains("createdat")) {
            root.putNull("targetField");
            return root;
        }
        if (input.contains("status")) {
            root.put("targetField", "status");
            return root;
        }
        if (input.contains("createdat")) {
            root.put("targetField", "createdAt");
            return root;
        }
        root.putNull("targetField");
        return root;
    }

    private boolean isDynamicFormComponentAuthoring(String input) {
        boolean mentionsFormField = input.contains("campo")
                || input.contains("formulario")
                || input.contains("formulário")
                || input.contains("preencher")
                || input.contains("informar")
                || input.contains("guardar")
                || input.contains("registrar");
        boolean mentionsAddOrEdit = input.contains("mais")
                || input.contains("extra")
                || input.contains("adicion")
                || input.contains("inclu")
                || input.contains("alternativo")
                || input.contains("emergenc")
                || input.contains("obrigatori")
                || input.contains("label")
                || input.contains("rotulo")
                || input.contains("rótulo");
        boolean mentionsContactLikeField = input.contains("telefone")
                || input.contains("whatsapp")
                || input.contains("recado")
                || input.contains("contato");
        return mentionsFormField && (mentionsAddOrEdit || mentionsContactLikeField);
    }

    private JsonNode buildTableActionPlan(String input) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");

        if (input.contains("ocultar") && input.contains("status")) {
            actions.add(buildAction("HIDE_COLUMN", "status", null));
        }
        if (input.contains("desabilitar") && input.contains("createdat")) {
            actions.add(buildAction("DISABLE_SORT", "createdAt", null));
        }

        root.putArray("ambiguities");
        return root;
    }

    private ObjectNode buildAction(String type, String target, String value) {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", type);
        action.put("target", target);
        if (value != null) {
            action.put("value", value);
        } else {
            action.putNull("value");
        }
        return action;
    }

    private JsonNode buildEmptyActionPlan() {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("actions");
        root.putArray("ambiguities");
        return root;
    }

    private JsonNode buildComponentActionPlan(String prompt, String input) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode actions = root.putArray("actions");
        root.putArray("ambiguities");

        if (prompt != null
                && prompt.contains("tab.label.set")
                && prompt.contains("tab.add")
                && isTabsAddIntent(input)) {
            actions.add(buildTabsAddAction(input));
            return root;
        }

        if (prompt != null
                && prompt.contains("tab.label.set")
                && (input.contains("aba") || input.contains("tab"))
                && (input.contains("nome") || input.contains("clara") || input.contains("label")
                || input.contains("titulo"))) {
            actions.add(buildAction(
                    "tab.label.set",
                    firstTargetCandidateId(prompt),
                    inferTabLabel(input)));
        }

        if (prompt != null
                && prompt.contains("field.local.add")
                && (input.contains("campo") || input.contains("informar") || input.contains("preencher")
                || input.contains("guardar") || input.contains("registrar") || input.contains("telefone"))
                && (input.contains("mais") || input.contains("extra") || input.contains("adicion")
                || input.contains("inclu") || input.contains("alternativo") || input.contains("emergenc")
                || input.contains("recado") || input.contains("whatsapp"))) {
            actions.add(buildDynamicFormLocalFieldAddAction(input));
            return root;
        }

        return root;
    }

    private ObjectNode buildDynamicFormLocalFieldAddAction(String input) {
        ObjectNode action = buildAction("field.local.add", null, null);
        ObjectNode params = action.putObject("params");
        boolean asksContact = input.contains("contato") || input.contains("telefone") || input.contains("whatsapp")
                || input.contains("recado");
        if (input.contains("whatsapp")) {
            params.put("name", "whatsapp");
            params.put("label", input.contains("recado") ? "WhatsApp ou telefone de recado" : "WhatsApp");
        } else if (input.contains("telefone") || input.contains("recado")) {
            params.put("name", "telefoneRecado");
            params.put("label", "Telefone de recado");
        } else {
            params.put("name", "campoAuxiliar");
            params.put("label", "Campo auxiliar");
        }
        params.put("controlType", "input");
        params.put("source", "local");
        params.put("submitPolicy", "omit");
        if (asksContact) {
            params.put("sectionLabel", "Contato");
            params.put("placeholder", "Informe um telefone alternativo para contato");
        }
        return action;
    }

    private boolean isTabsAddIntent(String input) {
        boolean explicitTabLanguage = input.contains("aba nova")
                || input.contains("nova aba")
                || input.contains("adicionar aba")
                || input.contains("inclua uma aba")
                || input.contains("crie uma aba");
        boolean asksSeparatedArea = input.contains("parte separada")
                || input.contains("area separada")
                || input.contains("área separada")
                || input.contains("secao separada")
                || input.contains("seção separada")
                || input.contains("separado para")
                || input.contains("separada para");
        boolean asksNewWorkArea = (input.contains("parte") || input.contains("area") || input.contains("área")
                || input.contains("secao") || input.contains("seção"))
                && (input.contains("acompanhar") || input.contains("organizar") || input.contains("separar"));
        return explicitTabLanguage || asksSeparatedArea || asksNewWorkArea;
    }

    private ObjectNode buildTabsAddAction(String input) {
        ObjectNode action = buildAction("tab.add", null, null);
        ObjectNode params = action.putObject("params");
        String id = inferTabId(input);
        String label = inferTabLabelForAdd(input);
        params.put("id", id);
        params.put("textLabel", label);
        if (input.contains("formulario") || input.contains("formulário")) {
            ArrayNode content = params.putArray("content");
            ObjectNode field = content.addObject();
            field.put("name", "observacoes");
            field.put("label", "Observacoes");
            field.put("controlType", "textarea");
        }
        return action;
    }

    private String inferTabId(String input) {
        if (input.contains("formulario") || input.contains("formulário")) {
            return "detalhes-formulario";
        }
        if (input.contains("document") && (input.contains("observa") || input.contains("anot"))) {
            return "documentos-observacoes";
        }
        if (input.contains("document")) {
            return "documentos";
        }
        if (input.contains("observa") || input.contains("anot")) {
            return "observacoes";
        }
        return "nova-aba";
    }

    private String inferTabLabelForAdd(String input) {
        if (input.contains("formulario") || input.contains("formulário") || input.contains("detalhe")) {
            return "Detalhes da pessoa";
        }
        if (input.contains("document") && (input.contains("observa") || input.contains("anot"))) {
            return "Documentos e observacoes";
        }
        if (input.contains("document")) {
            return "Documentos";
        }
        if (input.contains("observa") || input.contains("anot")) {
            return "Observacoes";
        }
        return "Nova aba";
    }

    private JsonNode buildPatchResponse(String input) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode patch = root.putObject("patch");

        if (input.contains("ocultar") && input.contains("status") && input.contains("createdat")) {
            ArrayNode columns = patch.putArray("columns");
            columns.add(buildColumnPatch("status").put("visible", false));
            root.put("explanation", "mock: hide status (multi-action)");
            return root;
        }
        if (input.contains("ocultar") && input.contains("status")) {
            ArrayNode columns = patch.putArray("columns");
            columns.add(buildColumnPatch("status").put("visible", false));
            root.put("explanation", "mock: hide status");
            return root;
        }
        if (input.contains("desabilitar") && input.contains("createdat")) {
            ArrayNode columns = patch.putArray("columns");
            columns.add(buildColumnPatch("createdAt").put("sortable", false));
            root.put("explanation", "mock: disable createdAt sorting");
            return root;
        }
        if (input.contains("endpoint") || input.contains("api")) {
            patch.put("resourcePath", "https://malicioso");
            root.put("explanation", "mock: attempt forbidden resource path");
            return root;
        }
        if (input.contains("densidade") || input.contains("density")) {
            ObjectNode appearance = patch.putObject("appearance");
            appearance.put("density", "ULTRA_COMPACT");
            root.put("explanation", "mock: invalid density value");
            return root;
        }
        if (input.contains("export")) {
            ObjectNode exportNode = patch.putObject("export");
            ArrayNode formats = exportNode.putArray("formats");
            formats.add("xml");
            root.put("explanation", "mock: invalid export format");
            return root;
        }

        patch.putObject("meta").put("name", "mock-default");
        root.put("explanation", "mock: default response");
        return root;
    }

    private ObjectNode buildColumnPatch(String field) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("field", field);
        return node;
    }

    private String firstTargetCandidateId(String prompt) {
        if (prompt == null) {
            return "search";
        }
        int idx = prompt.indexOf("CANDIDATOS DE ALVO");
        String targetBlock = idx >= 0 ? prompt.substring(idx) : prompt;
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(targetBlock);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "search";
    }

    private String inferTabLabel(String input) {
        if (input != null && (input.contains("consulta") || input.contains("buscar") || input.contains("busca"))) {
            return "Consulta e busca";
        }
        if (input != null && (input.contains("detalhe") || input.contains("detalhes"))) {
            return "Detalhes";
        }
        if (input != null && (input.contains("edicao") || input.contains("editar"))) {
            return "Edicao";
        }
        return "Aba principal";
    }
}
