package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("external")
class SpringAiOpenAiServiceTest {

    @Mock
    private OpenAiChatModel chatClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateTextReturnsContent() throws Exception {
        HttpServer server = openAiServer("\"pong\"");
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);

        String result = service.generateText("ping");

        assertEquals("pong", result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateJsonUsesSchemaPrompt() throws Exception {
        HttpServer server = openAiServer("\"{\\\"value\\\":123}\"");
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);

        JsonNode node = service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}"));

        assertNotNull(node);
        assertEquals(123, node.get("value").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gpt5DirectCallUsesMaxCompletionTokens() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = openAiServer("\"pong\"", "gpt-5.4-mini", capturedRequest);
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-5.4-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);

        String result = service.generateText("ping");

        assertEquals("pong", result);
        assertEquals(128, capturedRequest.get().path("max_completion_tokens").asInt());
        assertTrue(capturedRequest.get().path("max_tokens").isMissingNode());
        assertTrue(capturedRequest.get().path("temperature").isMissingNode());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer openAiServer(String contentJsonLiteral) throws Exception {
        return openAiServer(contentJsonLiteral, "gpt-4o-mini", new AtomicReference<>());
    }

    private HttpServer openAiServer(
            String contentJsonLiteral,
            String expectedModel,
            AtomicReference<JsonNode> capturedRequest) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            JsonNode request = objectMapper.readTree(requestBytes);
            capturedRequest.set(request);
            if (!expectedModel.equals(request.path("model").asText())) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            String body = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": %s
                          }
                        }
                      ]
                    }
                    """.formatted(contentJsonLiteral);
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private static ObjectProvider<OpenAiChatModel> provider(OpenAiChatModel client) {
        return new ObjectProvider<>() {
            @Override
            public OpenAiChatModel getObject(Object... args) {
                return client;
            }

            @Override
            public OpenAiChatModel getIfAvailable() {
                return client;
            }

            @Override
            public OpenAiChatModel getIfUnique() {
                return client;
            }

            @Override
            public java.util.Iterator<OpenAiChatModel> iterator() {
                return List.of(client).iterator();
            }
        };
    }
}
