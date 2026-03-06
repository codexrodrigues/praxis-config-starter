package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class SpringAiXaiServiceTest {

    @Mock
    private OpenAiChatModel chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateTextReturnsContent() {
        SpringAiXaiService service = new SpringAiXaiService(chatClient, objectMapper);
        ReflectionTestUtils.setField(service, "model", "grok-2-latest");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        when(chatClient.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("pong")))));

        String result = service.generateText("ping");

        assertEquals("pong", result);
    }

    @Test
    void generateJsonParsesSchema() {
        SpringAiXaiService service = new SpringAiXaiService(chatClient, objectMapper);
        ReflectionTestUtils.setField(service, "model", "grok-2-latest");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        when(chatClient.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"value\":999}")))));

        JsonNode node = service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}"));

        assertNotNull(node);
        assertEquals(999, node.get("value").asInt());
    }
}
