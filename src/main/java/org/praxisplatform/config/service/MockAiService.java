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
import org.springframework.stereotype.Service;

@Service
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
            return buildEmptyActionPlan();
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
                && prompt.contains("CANDIDATOS DE ALVO");
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
        root.put("intent", "update_column_rules");
        root.put("category", "columns");
        root.put("scope", "config");
        root.put("needsClarification", false);
        root.putArray("missingContext");
        root.putArray("options");

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
}
