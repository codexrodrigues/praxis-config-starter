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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
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
    void generateJsonParsesSchema() {
        SpringAiGeminiService service = new SpringAiGeminiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "model", "gemini-2.0-flash");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        when(chatClient.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"value\":321}")))));

        JsonNode node = service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}"));

        assertNotNull(node);
        assertEquals(321, node.get("value").asInt());
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
