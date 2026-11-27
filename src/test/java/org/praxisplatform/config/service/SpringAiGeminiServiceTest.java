package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class SpringAiGeminiServiceTest {

    @Mock
    private GoogleGenAiChatModel chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateTextReturnsContent() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "model", "gemini-2.0-flash");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        when(chatClient.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("pong")))));

        String result = service.generateText("ping");

        assertEquals("pong", result);
    }

    @Test
    void sdkCallPreservesChatResponseUsageMetadata() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "model", "gemini-2.5-flash");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        ReflectionTestUtils.setField(service, "preferGenaiApi", false);
        when(chatClient.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("pong"))),
                ChatResponseMetadata.builder()
                        .id("gemini-safe-123")
                        .model("gemini-2.5-flash-001")
                        .usage(new DefaultUsage(90, 12, 102))
                        .build()));
        AiProviderInvocationTrace trace = new AiProviderInvocationTrace(
                "intent_fast", 1, "gemini", "gemini-2.5-flash");

        String result = service.generateText(
                "ping",
                AiCallConfig.builder().model("gemini-2.5-flash").invocationTrace(trace).build());
        AiProviderInvocationTelemetry telemetry = trace.snapshot();

        assertEquals("pong", result);
        assertEquals("spring-ai-google-genai", telemetry.transport());
        assertEquals("gemini-2.5-flash-001", telemetry.model());
        assertEquals("gemini-safe-123", telemetry.responseId());
        assertEquals(90, telemetry.inputTokens());
        assertEquals(12, telemetry.outputTokens());
        assertEquals(102, telemetry.totalTokens());
    }

    @Test
    void directMetadataPreservesGeminiCacheUsage() throws Exception {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        AiProviderInvocationTrace trace = new AiProviderInvocationTrace(
                "intent_full", 1, "gemini", "gemini-2.5-flash");
        JsonNode response = objectMapper.readTree("""
                {
                  "responseId": "gemini-safe-456",
                  "modelVersion": "gemini-2.5-flash-002",
                  "candidates": [{"finishReason": "STOP"}],
                  "usageMetadata": {
                    "promptTokenCount": 140,
                    "candidatesTokenCount": 22,
                    "cachedContentTokenCount": 100,
                    "totalTokenCount": 162
                  }
                }
                """);

        ReflectionTestUtils.invokeMethod(
                service,
                "captureDirectInvocationMetadata",
                AiCallConfig.builder().invocationTrace(trace).build(),
                response,
                "gemini-2.5-flash");
        AiProviderInvocationTelemetry telemetry = trace.snapshot();

        assertEquals("google-genai-http", telemetry.transport());
        assertEquals("gemini-2.5-flash-002", telemetry.model());
        assertEquals("gemini-safe-456", telemetry.responseId());
        assertEquals("STOP", telemetry.finishReason());
        assertEquals(140, telemetry.inputTokens());
        assertEquals(22, telemetry.outputTokens());
        assertEquals(100, telemetry.cacheReadInputTokens());
        assertEquals(162, telemetry.totalTokens());
    }

    @Test
    void generateJsonParsesSchema() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "model", "gemini-2.0-flash");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        ReflectionTestUtils.setField(service, "jsonMinOutputTokens", 8192);
        when(chatClient.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"value\":321}")))));

        JsonNode node = service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}"));

        assertNotNull(node);
        assertEquals(321, node.get("value").asInt());
    }

    @Test
    void jsonModeHonorsProviderMinimumWhenCallerDeclaresCompactLogicalBudget() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 2048);
        ReflectionTestUtils.setField(service, "jsonMinOutputTokens", 8192);
        AiCallConfig compactAuthoringCall = AiCallConfig.builder()
                .maxTokens(640)
                .build();

        GoogleGenAiChatOptions jsonOptions = ReflectionTestUtils.invokeMethod(
                service,
                "buildOptions",
                compactAuthoringCall,
                true,
                "gemini-2.5-flash");
        GoogleGenAiChatOptions textOptions = ReflectionTestUtils.invokeMethod(
                service,
                "buildOptions",
                compactAuthoringCall,
                false,
                "gemini-2.5-flash");

        assertNotNull(jsonOptions);
        assertNotNull(textOptions);
        assertEquals(8192, jsonOptions.getMaxOutputTokens());
        assertEquals("application/json", jsonOptions.getResponseMimeType());
        assertEquals(640, textOptions.getMaxOutputTokens());
    }

    @Test
    void supportsStreamingAsTechnicalCapabilityWithoutApiKey() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);

        assertTrue(service.supportsTextStreaming(AiCallConfig.builder().build()));
        assertTrue(service.supportsTurnCancellation(AiCallConfig.builder().build()));
    }

    @Test
    void connectionProbeUsesMinimalGenerationWhenVertexHasNoApiKeyDiscoveryEndpoint() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "model", "gemini-2.0-flash");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        ReflectionTestUtils.setField(service, "preferGenaiApi", false);
        when(chatClient.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("pong")))));

        service.testConnection(AiCallConfig.builder().maxTokens(32).build());

        verify(chatClient).call(any(Prompt.class));
    }

    @Test
    void restPayloadUsesGeminiContentsPartsShape() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ObjectNode payload = objectMapper.createObjectNode();

        ReflectionTestUtils.invokeMethod(service, "addTextContent", payload, "ping");

        JsonNode firstContent = payload.path("contents").path(0);
        assertTrue(firstContent.isObject());
        assertEquals("ping", firstContent.path("parts").path(0).path("text").asText());
    }

    @Test
    void quotaExhaustionDoesNotSpendRetriesOrFallbackModels() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        AiProviderCallException quota = AiProviderCallException.fromHttpStatus(
                "gemini",
                429,
                "You exceeded your current quota; check your plan and billing details.");

        Boolean capacity = ReflectionTestUtils.invokeMethod(service, "isCapacityExhausted", quota);
        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryable", quota);

        assertEquals(false, capacity);
        assertEquals(false, retryable);
    }

    @Test
    void transientPerMinuteRateLimitWithRetryInfoAllowsRetryAndFallback() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        String body = """
                {
                  "error": {
                    "code": 429,
                    "message": "You exceeded your current quota, please check your plan and billing details. * Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 5, model: gemini-2.5-flash Please retry in 24.3s.",
                    "status": "RESOURCE_EXHAUSTED",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.rpc.RetryInfo",
                        "retryDelay": "24s"
                      }
                    ]
                  }
                }
                """;
        String summary = ReflectionTestUtils.invokeMethod(service, "summarizeErrorBodyForClassification", body);
        Object retryAfter = ReflectionTestUtils.invokeMethod(service, "extractRetryAfter", body);
        AiProviderCallException rateLimit = AiProviderCallException.fromHttpStatus(
                "gemini",
                429,
                summary,
                (java.time.Instant) retryAfter,
                null);

        Boolean capacity = ReflectionTestUtils.invokeMethod(service, "isCapacityExhausted", rateLimit);
        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryable", rateLimit);

        assertNotNull(retryAfter);
        assertEquals(AiProviderCallException.Kind.RATE_LIMIT, rateLimit.getKind());
        assertEquals(true, capacity);
        assertEquals(true, retryable);
    }

    @Test
    void zeroLimitQuotaExhaustionRemainsNonRetryable() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        String body = """
                {
                  "error": {
                    "code": 429,
                    "message": "You exceeded your current quota, please check your plan and billing details. Quota exceeded for limit: 0, model: gemini-2.5-pro. Please retry in 10s.",
                    "status": "RESOURCE_EXHAUSTED",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.rpc.RetryInfo",
                        "retryDelay": "10s"
                      }
                    ]
                  }
                }
                """;
        String summary = ReflectionTestUtils.invokeMethod(service, "summarizeErrorBodyForClassification", body);
        Object retryAfter = ReflectionTestUtils.invokeMethod(service, "extractRetryAfter", body);
        AiProviderCallException quota = AiProviderCallException.fromHttpStatus(
                "gemini",
                429,
                summary,
                (java.time.Instant) retryAfter,
                null);

        Boolean capacity = ReflectionTestUtils.invokeMethod(service, "isCapacityExhausted", quota);
        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryable", quota);

        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, quota.getKind());
        assertEquals(false, capacity);
        assertEquals(false, retryable);
    }

    @Test
    void dailyFreeTierQuotaExhaustionDoesNotRetryAsRateLimit() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        String body = """
                {
                  "error": {
                    "code": 429,
                    "message": "You exceeded your current quota, please check your plan and billing details. * Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-2.5-flash-lite, description: Generate content requests per day limit exceeded on Free Tier. Please retry in 10h36m48.537126298s.",
                    "status": "RESOURCE_EXHAUSTED",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.rpc.RetryInfo",
                        "retryDelay": "38208s"
                      }
                    ]
                  }
                }
                """;
        String summary = ReflectionTestUtils.invokeMethod(service, "summarizeErrorBodyForClassification", body);
        Object retryAfter = ReflectionTestUtils.invokeMethod(service, "extractRetryAfter", body);
        AiProviderCallException quota = AiProviderCallException.fromHttpStatus(
                "gemini",
                429,
                summary,
                (java.time.Instant) retryAfter,
                null);

        Boolean capacity = ReflectionTestUtils.invokeMethod(service, "isCapacityExhausted", quota);
        Boolean retryable = ReflectionTestUtils.invokeMethod(service, "isRetryable", quota);

        assertTrue(retryAfter == null);
        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, quota.getKind());
        assertEquals(false, capacity);
        assertEquals(false, retryable);
    }

    @Test
    void agenticAuthoringOnGemini3AppliesLowThinkingLevelAndHonorsCallTimeout() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 2048);
        ReflectionTestUtils.setField(service, "jsonMinOutputTokens", 8192);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 75);
        ReflectionTestUtils.setField(service, "authoringThinkingLevel", "LOW");

        AiCallConfig authoringCall = AiCallConfig.agenticAuthoringBuilder()
                .timeoutSeconds(60)
                .build();

        ObjectNode gemini38Payload = service.buildDirectRequestPayload("prompt", authoringCall, true, "gemini-3.8-flash");
        assertEquals(
                "LOW",
                gemini38Payload.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());

        ObjectNode gemini35LitePayload = service.buildDirectRequestPayload(
                "prompt", authoringCall, true, "gemini-3.5-flash-lite");
        assertTrue(gemini35LitePayload.path("generationConfig").path("thinkingConfig").isMissingNode());

        ObjectNode gemini25Payload = service.buildDirectRequestPayload("prompt", authoringCall, true, "gemini-2.5-flash");
        assertTrue(gemini25Payload.path("generationConfig").path("thinkingConfig").isMissingNode());

        assertEquals(60, service.resolveTimeoutSeconds(authoringCall));
        assertEquals(75, service.resolveTimeoutSeconds(AiCallConfig.builder().build()));
    }

    @Test
    void explicitThinkingProfileMapsGemini3ThinkingLevelsSafely() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 2048);
        ReflectionTestUtils.setField(service, "jsonMinOutputTokens", 8192);

        ObjectNode offOn38 = service.buildDirectRequestPayload(
                "prompt",
                AiCallConfig.builder().thinkingProfile(AiThinkingProfile.OFF).build(),
                true,
                "gemini-3.8-flash");
        assertEquals("LOW", offOn38.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());

        ObjectNode offOnLite = service.buildDirectRequestPayload(
                "prompt",
                AiCallConfig.builder().thinkingProfile(AiThinkingProfile.OFF).build(),
                true,
                "gemini-3.5-flash-lite");
        assertEquals("MINIMAL", offOnLite.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());

        ObjectNode deepOn38 = service.buildDirectRequestPayload(
                "prompt",
                AiCallConfig.builder().thinkingProfile(AiThinkingProfile.DEEP_ANALYSIS).build(),
                true,
                "gemini-3.8-flash");
        assertEquals("HIGH", deepOn38.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());
    }

    @Test
    void extractContentFromGeminiSkipsThoughtParts() throws Exception {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        JsonNode root = objectMapper.readTree("""
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          { "thought": true, "text": "chain of thought" },
                          { "text": "{\\"status\\":\\"ok\\"}" }
                        ]
                      }
                    }
                  ]
                }
                """);

        String extracted = ReflectionTestUtils.invokeMethod(service, "extractContentFromGemini", root);
        assertEquals("{\"status\":\"ok\"}", extracted);
    }

    private static ObjectProvider<GoogleGenAiChatModel> provider(GoogleGenAiChatModel client) {
        return new ObjectProvider<>() {
            @Override
            public GoogleGenAiChatModel getObject(Object... args) {
                return client;
            }

            @Override
            public GoogleGenAiChatModel getIfAvailable() {
                return client;
            }

            @Override
            public GoogleGenAiChatModel getIfUnique() {
                return client;
            }

            @Override
            public java.util.Iterator<GoogleGenAiChatModel> iterator() {
                return List.of(client).iterator();
            }
        };
    }
}
