package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

@Tag("unit")
class SpringAiOpenAiCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void springAi118FlattensExtraBodyIntoTheNativeOpenAiRequest() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequest.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {
                      "id": "chatcmpl-compatibility",
                      "object": "chat.completion",
                      "created": 1,
                      "model": "gpt-5.4-mini",
                      "choices": [{
                        "index": 0,
                        "message": {"role": "assistant", "content": "pong"},
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey("test-key")
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model("gpt-5.4-mini")
                            .maxCompletionTokens(128)
                            .extraBody(Map.of("reasoning_effort", "none"))
                            .build())
                    .build();

            ChatResponse response = model.call(new Prompt("ping"));

            assertThat(response.getResult().getOutput().getText()).isEqualTo("pong");
            assertThat(capturedRequest.get().path("reasoning_effort").asText()).isEqualTo("none");
            assertThat(capturedRequest.get().has("extra_body")).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
