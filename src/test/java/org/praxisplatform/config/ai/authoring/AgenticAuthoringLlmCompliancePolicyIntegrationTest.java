package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
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
class AgenticAuthoringLlmCompliancePolicyIntegrationTest {

    private static final String ENABLE_ENV = "PRAXIS_AGENTIC_AUTHORING_LLM_COMPLIANCE_POLICY";
    private static final String EXPECTED_PROFILE = "compliance_review";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldHonorFederatedCompliancePolicyContext() throws Exception {
        assumeTrue("true".equalsIgnoreCase(env(ENABLE_ENV)),
                "Set " + ENABLE_ENV + "=true to run this external LLM compliance policy test.");

        String providerName = normalize(envOrDefault("PRAXIS_AGENTIC_AUTHORING_SHADOW_PROVIDER", "openai"));
        AiProvider provider = createProvider(providerName);

        JsonNode result = provider.generateJson(
                compliancePrompt(providerName),
                AiJsonSchema.ofSchema(complianceResultSchema()),
                callConfig(providerName));

        assertComplianceResult(result);
        writeSanitizedReport(providerName, result);
    }

    private String compliancePrompt(String providerName) {
        return """
                You are validating a Praxis agentic-authoring compliance context.
                Return only one JSON object. Do not include Markdown.

                User request:
                Crie um formulario LGPD para funcionarios com CPF e salario.

                DOMAIN_CATALOG_CONTEXT
                schemaVersion: praxis.domain-catalog-context/v0.1
                query: cpf lgpd
                itemType: node
                contextKey: human-resources
                nodeType: field
                guidance:
                - Use this context as governed vocabulary.
                items:
                - [node/field] CPF (human-resources.funcionarios.field.cpf) | field=cpf | type=string | complianceTags=LGPD,GDPR | visibility=mask | trainingUse=deny | ruleAuthoring=review_required

                DOMAIN_CATALOG_RELATIONSHIPS
                federated: true
                relationshipType: references
                items:
                - [edge/references] edge:hr.funcionario.references.security.usuario (edge:hr.funcionario.references.security.usuario) | sourceNodeKey=human-resources.funcionarios.field.usuarioId | targetNodeKey=security.usuarios.id

                DOMAIN_FEDERATION_POLICY
                policyProfile: compliance_review
                minConfidence: 0.9
                includeDenied: false
                includeLowConfidence: false
                decisions:
                - Excluded human-resources.funcionarios.field.salario because confidence is below minConfidence.

                Task:
                Confirm which policy profile was used, whether denied content may be used, and list the allowed
                authoring guidance. Do not invent denied or low-confidence fields.

                Hard constraints:
                - policyProfile must be "compliance_review".
                - mayUseDeniedContent must be false.
                - allowedGuidance must mention CPF governance, masking, LGPD/GDPR or review_required.
                - excludedSignals must mention that salario was excluded because confidence is below minConfidence.
                - Do not list salario as allowed guidance.

                Provider under test: %s.
                """.formatted(providerName);
    }

    private String complianceResultSchema() {
        return """
                {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["policyProfile", "mayUseDeniedContent", "allowedGuidance", "excludedSignals"],
                  "properties": {
                    "policyProfile": {
                      "type": "string",
                      "enum": ["compliance_review"]
                    },
                    "mayUseDeniedContent": {
                      "type": "boolean",
                      "const": false
                    },
                    "allowedGuidance": {
                      "type": "array",
                      "minItems": 1,
                      "items": {
                        "type": "string"
                      }
                    },
                    "excludedSignals": {
                      "type": "array",
                      "minItems": 1,
                      "items": {
                        "type": "string"
                      }
                    }
                  }
                }
                """;
    }

    private void assertComplianceResult(JsonNode result) {
        assertThat(result).isNotNull();
        assertThat(result.path("policyProfile").asText()).isEqualTo(EXPECTED_PROFILE);
        assertThat(result.path("mayUseDeniedContent").asBoolean(true)).isFalse();
        assertThat(result.path("allowedGuidance")).isNotEmpty();
        assertThat(result.path("excludedSignals")).isNotEmpty();

        String allowed = result.path("allowedGuidance").toString().toLowerCase();
        assertThat(allowed)
                .containsAnyOf("cpf", "lgpd", "gdpr", "mask", "review_required");
        assertThat(allowed).doesNotContain("salario");

        String excluded = result.path("excludedSignals").toString().toLowerCase();
        assertThat(excluded).contains("salario");
        assertThat(excluded).containsAnyOf("confidence", "minconfidence", "baixa confianca", "exclu");
    }

    private AiProvider createProvider(String providerName) {
        if ("gemini".equals(providerName)) {
            return createGeminiProvider();
        }
        if ("openai".equals(providerName)) {
            return createOpenAiProvider();
        }
        throw new IllegalStateException("Unsupported compliance policy provider: " + providerName);
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

    private void writeSanitizedReport(String providerName, JsonNode result) throws Exception {
        Path reportDir = Path.of("target", "agentic-authoring");
        Files.createDirectories(reportDir);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("provider", providerName);
        root.put("model", modelFor(providerName));
        root.put("schemaVersion", "praxis.agentic-authoring.llm-compliance-policy-result/v0.1");
        root.set("result", result);

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportDir.resolve("llm-compliance-policy-result." + providerName + ".json").toFile(), root);
    }

    private String requireEnv(String name) {
        String value = env(name);
        if (value == null || value.isBlank() || value.startsWith("PASTE_")) {
            throw new IllegalStateException(name + " must be configured for LLM compliance policy tests.");
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
