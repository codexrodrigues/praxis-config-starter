package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiContextDTO;
import org.praxisplatform.config.dto.AiIntentClassification;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.IntentPlan;

class AiOrchestratorServiceCompletenessLoopTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiContextService contextService;
    private AiRagContextService ragContextService;

    @BeforeEach
    void setUp() {
        contextService = mock(AiContextService.class);
        ragContextService = mock(AiRagContextService.class);
    }

    @Test
    void shouldRetryWhenCompletenessIsMissing() {
        ObjectNode currentState = objectMapper.createObjectNode();
        currentState.put("title", "Old");
        currentState.putObject("config").put("enabled", false);

        ObjectNode componentDefinition = objectMapper.createObjectNode();
        ArrayNode capabilities = componentDefinition.putArray("capabilities");
        capabilities.addObject().put("path", "title").put("category", "appearance");
        capabilities.addObject().put("path", "config.enabled").put("category", "appearance");
        ObjectNode contextPack = componentDefinition.putObject("componentContext");
        contextPack.putObject("optionsByPath")
                .putObject("config.enabled")
                .putArray("options")
                .add("true")
                .add("false");

        AiContextDTO context = AiContextDTO.builder()
                .componentId("praxis-card")
                .componentType("praxis-card")
                .aiMode("runtime-configurable")
                .requireSchema(false)
                .currentState(currentState)
                .componentDefinition(componentDefinition)
                .build();

        when(contextService.buildContext(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenReturn(context);
        when(ragContextService.buildRagHints(any(), any(), any(), any())).thenReturn("");

        AiProvider aiProvider = new StubAiProvider(objectMapper);
        AiOrchestratorService service = new AiOrchestratorService(
                contextService,
                aiProvider,
                mock(AiInteractionLogger.class),
                mock(ContextRetrievalService.class),
                mock(SchemaRetrievalService.class),
                mock(AiRegistryTemplateService.class),
                ragContextService,
                mock(UserConfigService.class),
                objectMapper,
                mock(AiApiKeyCryptoService.class));

        AiOrchestratorRequest request = AiOrchestratorRequest.builder()
                .componentId("praxis-card")
                .componentType("praxis-card")
                .userPrompt("Atualize o titulo e habilite o config.enabled.")
                .currentState(currentState)
                .build();

        AiOrchestratorResponse response = service.generatePatch(
                request,
                null,
                null,
                null,
                null);

        assertThat(response.getType())
                .as("message: %s", response.getMessage())
                .isEqualTo("patch");
        assertThat(response.getPatch().path("title").asText()).isEqualTo("Novo");
        assertThat(response.getPatch().path("config").path("enabled").asBoolean()).isTrue();
        assertThat(response.getDiff()).isNotNull();
        assertThat(response.getDiff().stream().anyMatch(d -> "config.enabled".equals(d.getPath()))).isTrue();
    }

    private static final class StubAiProvider implements AiProvider {

        private final ObjectMapper mapper;
        private int patchCalls = 0;

        private StubAiProvider(ObjectMapper mapper) {
            this.mapper = mapper;
        }

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
            if (schema != null && AiIntentClassification.class.equals(schema.targetClass())) {
                return buildIntentClassification();
            }
            if (schema != null && IntentPlan.class.equals(schema.targetClass())) {
                return buildIntentPlan();
            }
            patchCalls++;
            if (patchCalls == 1) {
                return buildPatch("title", "Novo");
            }
            return buildPatch("config.enabled", true);
        }

        @Override
        public String generateText(String prompt) {
            return "";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

        private JsonNode buildIntentClassification() {
            ObjectNode node = mapper.createObjectNode();
            node.put("intent", "toggle_feature");
            node.putNull("targetField");
            node.put("category", "appearance");
            node.put("scope", "config");
            node.put("needsClarification", false);
            node.putArray("missingContext");
            node.putArray("options");
            return node;
        }

        private JsonNode buildIntentPlan() {
            ObjectNode node = mapper.createObjectNode();
            node.put("intent", "update");
            ArrayNode actions = node.putArray("actions");
            ObjectNode titleAction = actions.addObject();
            titleAction.put("id", "update-title");
            ArrayNode titleChecks = titleAction.putArray("checks");
            ObjectNode titleCheck = titleChecks.addObject();
            titleCheck.put("type", "pathEquals");
            titleCheck.put("path", "title");
            titleCheck.put("value", "Novo");

            ObjectNode flagAction = actions.addObject();
            flagAction.put("id", "enable-config");
            ArrayNode flagChecks = flagAction.putArray("checks");
            ObjectNode flagCheck = flagChecks.addObject();
            flagCheck.put("type", "pathChanged");
            flagCheck.put("path", "config.enabled");

            node.putArray("questions");
            return node;
        }

        private JsonNode buildPatch(String path, Object value) {
            ObjectNode node = mapper.createObjectNode();
            ObjectNode patch = node.putObject("patch");
            if ("title".equals(path)) {
                patch.put("title", value.toString());
            } else if ("config.enabled".equals(path)) {
                patch.putObject("config").put("enabled", (Boolean) value);
            }
            node.put("explanation", "ok");
            return node;
        }
    }
}
