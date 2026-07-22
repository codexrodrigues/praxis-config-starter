package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.OpenAiHostedSkillProperties;
import org.praxisplatform.config.service.SpringAiOpenAiService;
import org.springframework.test.util.ReflectionTestUtils;

/** Live, opt-in proof that OpenAI accepts the exact complex table schemas in strict mode. */
@Tag("integration")
@Tag("live")
class AgenticAuthoringProviderSchemaCompilerOpenAiIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsRendererAndConditionalRendererSchemasWithoutHttp400() throws Exception {
        String apiKey = System.getenv("PRAXIS_AI_OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"PASTE_OPENAI_API_KEY_HERE".equals(apiKey));
        JsonNode manifest = tableManifest();
        AgenticAuthoringProviderSchemaCompiler compiler = new AgenticAuthoringProviderSchemaCompiler(objectMapper);
        SpringAiOpenAiService service = service(apiKey);
        for (String operationId : List.of("column.renderer.set", "column.conditionalRenderer.add")) {
            JsonNode schema = compiler.compileEditPlanSchema(
                    AgenticAuthoringComponentEditPlanService.PLAN_SCHEMA_VERSION,
                    "praxis-table",
                    List.of(operation(manifest, operationId)));
            JsonNode response = service.generateJson(
                    "Return the smallest valid plan for the declared operation. Use null for optional values.",
                    AiJsonSchema.ofSchema(objectMapper.writeValueAsString(schema)),
                    AiCallConfig.agenticAuthoringBuilder().maxTokens(1800).timeoutSeconds(90).build());
            assertThat(response).as(operationId).isNotNull();
        }
    }

    private SpringAiOpenAiService service(String apiKey) {
        SpringAiOpenAiService service = new SpringAiOpenAiService(objectMapper, new OpenAiHostedSkillProperties());
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "baseUrl", System.getenv().getOrDefault("PRAXIS_AI_OPENAI_BASE_URL", "https://api.openai.com"));
        ReflectionTestUtils.setField(service, "model", System.getenv().getOrDefault("PRAXIS_AI_OPENAI_MODEL", "gpt-4o-mini"));
        ReflectionTestUtils.setField(service, "temperature", 0.0d);
        ReflectionTestUtils.setField(service, "maxTokens", 1800);
        ReflectionTestUtils.setField(service, "jsonMinCompletionTokens", 1800);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 90);
        return service;
    }

    private JsonNode tableManifest() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/ai-registry/registry-snapshot.json")) {
            assertThat(input).isNotNull();
            return objectMapper.readTree(input).path("components").path("praxis-table").path("authoringManifest");
        }
    }

    private JsonNode operation(JsonNode manifest, String id) {
        for (JsonNode operation : manifest.path("operations")) {
            if (id.equals(operation.path("operationId").asText())) return operation;
        }
        throw new AssertionError("Missing operation " + id);
    }
}
