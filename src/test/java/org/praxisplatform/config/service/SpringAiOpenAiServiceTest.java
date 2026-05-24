package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void gpt5JsonCallRaisesCompletionBudgetForStructuredOutput() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = openAiServer("\"{\\\"value\\\":123}\"", "gpt-5.4-mini", capturedRequest);
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-5.4-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        ReflectionTestUtils.setField(service, "jsonMinCompletionTokens", 8192);

        JsonNode node = service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}"));

        assertNotNull(node);
        assertEquals(123, node.get("value").asInt());
        assertEquals(8192, capturedRequest.get().path("max_completion_tokens").asInt());
        assertTrue(capturedRequest.get().path("max_tokens").isMissingNode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gpt5JsonCallHonorsExplicitCompletionBudget() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = openAiServer("\"{\\\"value\\\":123}\"", "gpt-5.4-mini", capturedRequest);
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-5.4-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        ReflectionTestUtils.setField(service, "jsonMinCompletionTokens", 8192);

        JsonNode node = service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}"),
                AiCallConfig.builder().maxTokens(2200).build());

        assertNotNull(node);
        assertEquals(123, node.get("value").asInt());
        assertEquals(2200, capturedRequest.get().path("max_completion_tokens").asInt());
        assertTrue(capturedRequest.get().path("max_tokens").isMissingNode());
        assertTrue(capturedRequest.get().path("reasoning_effort").isMissingNode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gpt5JsonCallUsesLowReasoningForCompactExplicitBudget() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = openAiServer("\"{\\\"value\\\":123}\"", "gpt-5.4-mini", capturedRequest);
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-5.4-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        ReflectionTestUtils.setField(service, "jsonMinCompletionTokens", 8192);

        JsonNode node = service.generateJson(
                "prompt",
                AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}}}"),
                AiCallConfig.builder().maxTokens(1800).build());

        assertNotNull(node);
        assertEquals(123, node.get("value").asInt());
        assertEquals(1800, capturedRequest.get().path("max_completion_tokens").asInt());
        assertEquals("low", capturedRequest.get().path("reasoning_effort").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directCallRaisesNormalizedProviderExceptionForEmptyContent() throws Exception {
        HttpServer server = openAiServer("null");
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);

        AiProviderCallException ex = assertThrows(AiProviderCallException.class, () -> service.generateText("ping"));

        assertEquals("openai", ex.getProvider());
        assertEquals(AiProviderCallException.Kind.UNKNOWN, ex.getKind());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directCallRaisesNormalizedProviderExceptionForQuotaFailures() throws Exception {
        HttpServer server = errorServer(429, """
                {"error":{"type":"insufficient_quota","code":"insufficient_quota","message":"You exceeded your current quota. Check your plan and billing details. request req_secret_123"}}
                """);
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);

        AiProviderCallException ex = assertThrows(AiProviderCallException.class, () -> service.generateText("ping"));

        assertEquals("openai", ex.getProvider());
        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, ex.getKind());
        assertEquals(429, ex.getStatusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directCallKeepsRateLimitForTransientHttp429Failures() throws Exception {
        HttpServer server = errorServer(429, """
                {"error":{"message":"Rate limit reached for requests per minute."}}
                """);
        server.start();
        try {
        SpringAiOpenAiService service = new SpringAiOpenAiService(provider(chatClient), objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);

        AiProviderCallException ex = assertThrows(AiProviderCallException.class, () -> service.generateText("ping"));

        assertEquals("openai", ex.getProvider());
        assertEquals(AiProviderCallException.Kind.RATE_LIMIT, ex.getKind());
        assertEquals(429, ex.getStatusCode());
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

    private HttpServer errorServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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
