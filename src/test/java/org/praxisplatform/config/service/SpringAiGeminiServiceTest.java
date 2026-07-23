package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
