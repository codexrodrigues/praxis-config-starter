package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProvider;
import org.praxisplatform.config.service.SpringAiGeminiService;
import org.praxisplatform.config.service.SpringAiOpenAiService;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("external")
@Tag("e2e")
class AgenticAuthoringLlmShadowIntegrationTest {

    private static final String ENABLE_ENV = "PRAXIS_AGENTIC_AUTHORING_LLM_SHADOW";
    private static final String PROFILE_ID = "create-minimal-form";
    private static final String TARGET_APP = "praxis-helpdesk-ui";
    private static final String TARGET_COMPONENT = "praxis-dynamic-page-builder";
    private static final List<String> ALLOWED_FIELDS = List.of("titulo", "descricao");
    private static final List<String> BLOCKED_FIELDS = List.of(
            "organizacaoId",
            "solicitanteId",
            "statusAtualId",
            "itemCatalogoId",
            "prioridadeId",
            "grupoResponsavelId",
            "responsavelId",
            "dataLimite"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldGenerateMinimalFormPlanAndPassDeterministicDryRun() throws Exception {
        assumeTrue("true".equalsIgnoreCase(env(ENABLE_ENV)),
                "Set " + ENABLE_ENV + "=true to run this external LLM shadow test.");

        String providerName = normalize(envOrDefault("PRAXIS_AGENTIC_AUTHORING_SHADOW_PROVIDER", "openai"));
        AiProvider provider = createProvider(providerName);
        String schema = Files.readString(contractsDir().resolve("minimal-form-plan.v1.schema.json"));

        JsonNode plan = provider.generateJson(
                shadowPrompt(providerName),
                AiJsonSchema.ofSchema(schema),
                callConfig(providerName)
        );

        assertMinimalFormPlan(plan);

        AgenticAuthoringDryRunResult dryRun = new AgenticAuthoringDryRunService(objectMapper)
                .run(configuredArtifactSource());

        assertThat(dryRun.valid()).isTrue();
        assertThat(dryRun.failureCodes()).isEmpty();

        writeSanitizedReport(providerName, plan, dryRun);
    }

    private String shadowPrompt(String providerName) {
        return """
                You are generating an internal Praxis MinimalFormPlan for a deterministic shadow test.
                Return only one JSON object. Do not include Markdown.

                User request:
                Crie um formulario didatico so com os campos realmente necessarios para abrir chamados para notebooks com a tela quebrada.

                Hard constraints:
                - version must be "1.0.0".
                - profileId must be "create-minimal-form".
                - targetApp must be "praxis-helpdesk-ui".
                - targetComponentId must be "praxis-dynamic-page-builder".
                - apiUseCaseResolutionRef must be "proofs/helpdesk-create-ticket-discovery.md#api-use-case".
                - fieldSelectionPlanRef must be "proofs/helpdesk-create-ticket-discovery.md#field-selection".
                - submitActionRef must be "POST /api/helpdesk/chamados".
                - fields may include only titulo and descricao.
                - titulo is required because the create schema requires it.
                - descricao is allowed because the prompt describes the broken notebook screen.
                - Do not include organizacaoId, solicitanteId, statusAtualId, itemCatalogoId, prioridadeId,
                  grupoResponsavelId, responsavelId or dataLimite.
                - clarificationNeed.needed must be false and clarificationNeed.code must be "none".
                - sourceRefs must cite the discovery proof, page-create catalog and examples governance manifest.

                Provider under test: %s.
                """.formatted(providerName);
    }

    private AiProvider createProvider(String providerName) {
        if ("gemini".equals(providerName)) {
            return createGeminiProvider();
        }
        if ("openai".equals(providerName)) {
            return createOpenAiProvider();
        }
        throw new IllegalStateException("Unsupported shadow provider: " + providerName);
    }

    private SpringAiOpenAiService createOpenAiProvider() {
        String apiKey = requireEnv("PRAXIS_AI_OPENAI_API_KEY");
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiChatModel> chatProvider = mock(ObjectProvider.class);
        SpringAiOpenAiService service = new SpringAiOpenAiService(chatProvider, objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "baseUrl", envOrDefault("PRAXIS_AI_OPENAI_BASE_URL", "https://api.openai.com"));
        ReflectionTestUtils.setField(service, "model", envOrDefault("PRAXIS_AI_OPENAI_MODEL", "gpt-4o-mini"));
        ReflectionTestUtils.setField(service, "temperature", 0.0d);
        ReflectionTestUtils.setField(service, "maxTokens", intEnv("PRAXIS_AI_MAX_TOKENS", 2048));
        ReflectionTestUtils.setField(service, "timeoutSeconds", intEnv("PRAXIS_AI_TIMEOUT_SECONDS", 45));
        return service;
    }

    @SuppressWarnings("unchecked")
    private SpringAiGeminiService createGeminiProvider() {
        String apiKey = requireEnv("PRAXIS_AI_GEMINI_API_KEY");
        ObjectProvider<GoogleGenAiChatModel> chatProvider = mock(ObjectProvider.class);
        SpringAiGeminiService service = new SpringAiGeminiService(chatProvider, objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "model", envOrDefault("PRAXIS_AI_GEMINI_MODEL", "gemini-2.5-flash"));
        ReflectionTestUtils.setField(service, "fallbackModels", envOrDefault("PRAXIS_AI_GEMINI_FALLBACK_MODELS", "gemini-2.5-flash,gemini-2.0-flash"));
        ReflectionTestUtils.setField(service, "previewEnabled", Boolean.parseBoolean(envOrDefault("PRAXIS_AI_GEMINI_PREVIEW_ENABLED", "false")));
        ReflectionTestUtils.setField(service, "preferGenaiApi", Boolean.parseBoolean(envOrDefault("PRAXIS_AI_GEMINI_PREFER_GENAI_API", "true")));
        ReflectionTestUtils.setField(service, "retryMaxAttempts", intEnv("PRAXIS_AI_GEMINI_RETRY_MAX_ATTEMPTS", 2));
        ReflectionTestUtils.setField(service, "retryBaseDelayMs", (long) intEnv("PRAXIS_AI_GEMINI_RETRY_BASE_DELAY_MS", 300));
        ReflectionTestUtils.setField(service, "retryJitterPercent", intEnv("PRAXIS_AI_GEMINI_RETRY_JITTER_PERCENT", 30));
        ReflectionTestUtils.setField(service, "breakerThreshold", intEnv("PRAXIS_AI_GEMINI_BREAKER_THRESHOLD", 3));
        ReflectionTestUtils.setField(service, "breakerWindowMs", 30000L);
        ReflectionTestUtils.setField(service, "breakerOpenMs", 30000L);
        ReflectionTestUtils.setField(service, "temperature", 0.0d);
        ReflectionTestUtils.setField(service, "maxTokens", intEnv("PRAXIS_AI_MAX_TOKENS", 2048));
        ReflectionTestUtils.setField(service, "timeoutSeconds", intEnv("PRAXIS_AI_TIMEOUT_SECONDS", 45));
        return service;
    }

    private AiCallConfig callConfig(String providerName) {
        return AiCallConfig.builder()
                .provider(providerName)
                .model(modelFor(providerName))
                .temperature(0.0d)
                .maxTokens(intEnv("PRAXIS_AI_MAX_TOKENS", 2048))
                .build();
    }

    private String modelFor(String providerName) {
        if ("gemini".equals(providerName)) {
            return envOrDefault("PRAXIS_AI_GEMINI_MODEL", "gemini-2.5-flash");
        }
        return envOrDefault("PRAXIS_AI_OPENAI_MODEL", "gpt-4o-mini");
    }

    private void assertMinimalFormPlan(JsonNode plan) {
        assertThat(plan).isNotNull();
        assertThat(plan.path("version").asText()).isEqualTo("1.0.0");
        assertThat(plan.path("profileId").asText()).isEqualTo(PROFILE_ID);
        assertThat(plan.path("targetApp").asText()).isEqualTo(TARGET_APP);
        assertThat(plan.path("targetComponentId").asText()).isEqualTo(TARGET_COMPONENT);
        assertThat(plan.path("apiUseCaseResolutionRef").asText()).isNotBlank();
        assertThat(plan.path("fieldSelectionPlanRef").asText()).isNotBlank();
        assertThat(plan.path("submitActionRef").asText()).isNotBlank();
        assertThat(plan.path("sourceRefs")).isNotEmpty();

        JsonNode fields = plan.path("fields");
        assertThat(fields).isNotEmpty();
        assertThat(fields)
                .allSatisfy(field -> assertThat(ALLOWED_FIELDS).contains(field.path("name").asText()));
        assertThat(fields)
                .extracting(field -> field.path("name").asText())
                .contains("titulo", "descricao")
                .doesNotHaveDuplicates();
        for (String blockedField : BLOCKED_FIELDS) {
            assertThat(fields)
                    .extracting(field -> field.path("name").asText())
                    .doesNotContain(blockedField);
        }
        JsonNode title = findField(fields, "titulo");
        assertThat(title.path("required").asBoolean(false)).isTrue();
        JsonNode clarification = plan.path("clarificationNeed");
        assertThat(clarification.path("needed").asBoolean(true)).isFalse();
        assertThat(clarification.path("code").asText()).isEqualTo("none");
    }

    private JsonNode findField(JsonNode fields, String name) {
        for (JsonNode field : fields) {
            if (name.equals(field.path("name").asText())) {
                return field;
            }
        }
        throw new AssertionError("field not found: " + name);
    }

    private AgenticAuthoringArtifactSource configuredArtifactSource() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setArtifactsDir(proofsDir());
        return new AgenticAuthoringArtifactSource(properties);
    }

    private void writeSanitizedReport(String providerName, JsonNode plan, AgenticAuthoringDryRunResult dryRun) throws Exception {
        Path reportDir = Path.of("target", "agentic-authoring");
        Files.createDirectories(reportDir);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("provider", providerName);
        root.put("model", modelFor(providerName));
        root.set("minimalFormPlan", plan);
        ObjectNode dryRunNode = root.putObject("dryRun");
        dryRunNode.put("valid", dryRun.valid());
        dryRunNode.put("gateCount", dryRun.gates().size());
        dryRunNode.putPOJO("failureCodes", dryRun.failureCodes());
        dryRunNode.putPOJO("warnings", dryRun.warnings());

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportDir.resolve("llm-shadow-result." + providerName + ".json").toFile(), root);
    }

    private Path contractsDir() {
        Path fromModuleDir = Path.of("..", "docs", "ai", "agentic-authoring", "contracts");
        if (Files.exists(fromModuleDir)) {
            return fromModuleDir;
        }
        return Path.of("docs", "ai", "agentic-authoring", "contracts");
    }

    private Path proofsDir() {
        Path fromModuleDir = Path.of("..", "docs", "ai", "agentic-authoring", "proofs");
        if (Files.exists(fromModuleDir)) {
            return fromModuleDir;
        }
        return Path.of("docs", "ai", "agentic-authoring", "proofs");
    }

    private String requireEnv(String name) {
        String value = env(name);
        if (value == null || value.isBlank() || value.startsWith("PASTE_")) {
            throw new IllegalStateException(name + " must be configured for LLM shadow tests.");
        }
        return value;
    }

    private String envOrDefault(String name, String fallback) {
        String value = env(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int intEnv(String name, int fallback) {
        String value = env(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private String env(String name) {
        return System.getenv(name);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
