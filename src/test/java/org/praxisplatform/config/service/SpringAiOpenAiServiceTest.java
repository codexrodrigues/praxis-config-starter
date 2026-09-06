package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class SpringAiOpenAiServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateTextUsesResponsesApiAndCapturesSanitizedTelemetry() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("pong"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-5.4-mini");
        server.start();
        try {
            AiProviderInvocationTrace trace =
                    new AiProviderInvocationTrace("intent_full", 1, "openai", "gpt-5.4-mini");

            String result = service.generateText(
                    "ping", AiCallConfig.builder()
                            .environment("ci")
                            .executionProfile(AiExecutionProfile.AGENTIC_AUTHORING)
                            .invocationTrace(trace)
                            .build());
            AiProviderInvocationTelemetry telemetry = trace.snapshot();

            assertEquals("pong", result);
            assertEquals("ping", capturedRequest.get().path("input").asText());
            assertEquals(128, capturedRequest.get().path("max_output_tokens").asInt());
            assertFalse(capturedRequest.get().path("store").asBoolean(true));
            assertTrue(capturedRequest.get().path("temperature").isMissingNode());
            assertEquals("none", capturedRequest.get().path("reasoning").path("effort").asText());
            JsonNode metadata = capturedRequest.get().path("metadata");
            assertEquals("ci-live-gate", metadata.path("praxis_origin_class").asText());
            assertEquals("ci", metadata.path("praxis_environment").asText());
            assertEquals("agentic_authoring", metadata.path("praxis_execution_profile").asText());
            assertEquals("intent_full", metadata.path("praxis_call_phase").asText());
            assertEquals("1", metadata.path("praxis_call_attempt").asText());
            assertEquals("text", metadata.path("praxis_response_mode").asText());
            assertEquals("openai-responses-sdk", telemetry.transport());
            assertEquals("gpt-5.4-mini-2026-06-01", telemetry.model());
            assertEquals("resp-safe-123", telemetry.responseId());
            assertEquals("completed", telemetry.finishReason());
            assertEquals(120, telemetry.inputTokens());
            assertEquals(18, telemetry.outputTokens());
            assertEquals(80, telemetry.cacheReadInputTokens());
            assertEquals(138, telemetry.totalTokens());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void generateTextClampsOutputTokensToTheOpenAiMinimum() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("pong"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-5-mini");
        server.start();
        try {
            String result = service.generateText(
                    "ping", AiCallConfig.builder().maxTokens(8).build());

            assertEquals("pong", result);
            assertEquals(16, capturedRequest.get().path("max_output_tokens").asInt());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void connectionProbeAuthenticatesThroughModelDiscoveryWithoutInference() throws Exception {
        AtomicInteger modelRequests = new AtomicInteger();
        AtomicInteger inferenceRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            modelRequests.incrementAndGet();
            writeJson(exchange, 200, "{\"data\":[{\"id\":\"gpt-5-mini\",\"owned_by\":\"openai\"}]}");
        });
        server.createContext("/v1/responses", exchange -> {
            inferenceRequests.incrementAndGet();
            writeJson(exchange, 500, "{\"error\":{\"message\":\"inference must not be called\"}}");
        });
        SpringAiOpenAiService service = service(server, "gpt-5-mini");
        server.start();
        try {
            service.testConnection(AiCallConfig.builder().maxTokens(32).build());

            assertEquals(1, modelRequests.get());
            assertEquals(0, inferenceRequests.get());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void transcribeAudioUsesTheOfficialSdkMultipartEndpointExactlyOnce() throws Exception {
        AtomicInteger transcriptionRequests = new AtomicInteger();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedAuthorization = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            transcriptionRequests.incrementAndGet();
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            writeJson(exchange, 200, "{\"text\":\"  Criar uma página de missões  \"}");
        });
        SpringAiOpenAiService service = service(server, "gpt-5.4-mini");
        server.start();
        try {
            String result = service.transcribeAudio(
                    new AiAudioTranscriptionRequest(
                            new byte[] {1, 2, 3, 4},
                            "voice.webm",
                            "audio/webm",
                            "pt-BR"),
                    AiCallConfig.builder()
                            .model("gpt-4o-mini-transcribe")
                            .build());

            assertEquals("Criar uma página de missões", result);
            assertEquals(1, transcriptionRequests.get());
            assertTrue(capturedContentType.get().startsWith("multipart/form-data; boundary="));
            assertEquals("Bearer test-key", capturedAuthorization.get());
            assertTrue(capturedBody.get().contains("voice.webm"));
            assertTrue(capturedBody.get().contains("audio/webm"));
            assertTrue(capturedBody.get().contains("name=\"model\""));
            assertTrue(capturedBody.get().contains("\r\n\r\ngpt-4o-mini-transcribe\r\n"));
            assertTrue(capturedBody.get().contains("name=\"language\""));
            assertTrue(capturedBody.get().contains("\r\n\r\npt\r\n"));
            assertFalse(capturedBody.get().contains("test-key"));
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void transcribeAudioRejectsAnEmptyPayloadBeforeCallingTheSdk() throws Exception {
        AtomicInteger transcriptionRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            transcriptionRequests.incrementAndGet();
            writeJson(exchange, 500, "{\"error\":{\"message\":\"must not be called\"}}");
        });
        SpringAiOpenAiService service = service(server, "gpt-5.4-mini");
        server.start();
        try {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.transcribeAudio(
                            new AiAudioTranscriptionRequest(new byte[0], "voice.webm", "audio/webm", "pt-BR"),
                            AiCallConfig.builder().model("gpt-4o-mini-transcribe").build()));

            assertEquals("Audio payload is required.", error.getMessage());
            assertEquals(0, transcriptionRequests.get());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void economicalGpt56ModelsUseTheLightReasoningProfile() throws Exception {
        List<JsonNode> capturedRequests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            capturedRequests.add(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            writeJson(exchange, 200, completedResponse("pong"));
        });
        SpringAiOpenAiService service = service(server, "gpt-5.6-luna");
        server.start();
        try {
            service.generateText("luna", AiCallConfig.builder().maxTokens(2_048).build());
            service.generateText("terra", AiCallConfig.builder()
                    .model("gpt-5.6-terra")
                    .maxTokens(2_048)
                    .build());

            assertEquals("low", capturedRequests.get(0).path("reasoning").path("effort").asText());
            assertEquals("low", capturedRequests.get(1).path("reasoning").path("effort").asText());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void versionedEconomicalGpt56ModelsKeepTheLightReasoningProfile() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("pong"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-5.6-terra-2026-07-15");
        server.start();
        try {
            service.generateText("terra");

            assertEquals("low", capturedRequest.get().path("reasoning").path("effort").asText());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void agenticAuthoringProfileAttachesOnlyConfiguredHostedSkills() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("pong"), capturedRequest);
        OpenAiHostedSkillProperties properties = new OpenAiHostedSkillProperties();
        OpenAiHostedSkillProperties.Reference coordinator = new OpenAiHostedSkillProperties.Reference();
        coordinator.setId("skill-coordinator");
        coordinator.setVersion("2");
        OpenAiHostedSkillProperties.Reference grounding = new OpenAiHostedSkillProperties.Reference();
        grounding.setId("skill-grounding");
        OpenAiHostedSkillProperties.Reference artifactUnderstanding = new OpenAiHostedSkillProperties.Reference();
        artifactUnderstanding.setId("skill-artifact-understanding");
        artifactUnderstanding.setVersion("1");
        OpenAiHostedSkillProperties.Reference multiTurnRefinement = new OpenAiHostedSkillProperties.Reference();
        multiTurnRefinement.setId("skill-multi-turn-refinement");
        multiTurnRefinement.setVersion("1");
        properties.setAgenticAuthoring(List.of(
                coordinator,
                grounding,
                artifactUnderstanding,
                multiTurnRefinement));
        SpringAiOpenAiService service = service(server, "gpt-5.6-luna", properties);
        server.start();
        try {
            service.generateText("author", AiCallConfig.agenticAuthoringBuilder().build());

            JsonNode tool = capturedRequest.get().path("tools").path(0);
            assertEquals("shell", tool.path("type").asText());
            assertEquals("container_auto", tool.path("environment").path("type").asText());
            assertEquals("skill-coordinator", tool.path("environment").path("skills").path(0).path("skill_id").asText());
            assertEquals("2", tool.path("environment").path("skills").path(0).path("version").asText());
            assertEquals("skill-grounding", tool.path("environment").path("skills").path(1).path("skill_id").asText());
            assertEquals("latest", tool.path("environment").path("skills").path(1).path("version").asText());
            assertEquals("skill-artifact-understanding", tool.path("environment").path("skills").path(2).path("skill_id").asText());
            assertEquals("1", tool.path("environment").path("skills").path(2).path("version").asText());
            assertEquals("skill-multi-turn-refinement", tool.path("environment").path("skills").path(3).path("skill_id").asText());
            assertEquals("1", tool.path("environment").path("skills").path(3).path("version").asText());

            service.generateText("plain", AiCallConfig.builder().build());
            assertTrue(capturedRequest.get().path("tools").isMissingNode());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void generateTextIgnoresUnconsumedEvolvingOutputVariants() throws Exception {
        String response = completedResponse("pong").replace(
                "\"output\":[{",
                "\"output\":[{\"id\":\"search-safe-123\",\"type\":\"web_search_call\",\"status\":\"completed\"},{");
        HttpServer server = responseServer(response, new AtomicReference<>());
        SpringAiOpenAiService service = service(server, "gpt-5.4-mini");
        server.start();
        try {
            assertEquals("pong", service.generateText("ping"));
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void generateJsonUsesStrictNativeSchemaWithoutPromptInjection() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("{\"value\":123}"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            JsonNode node = service.generateJson(
                    "author this decision",
                    AiJsonSchema.ofSchema("""
                            {"type":"object","properties":{"value":{"type":"number"}},"required":["value"],"additionalProperties":false}
                            """));

            JsonNode format = capturedRequest.get().path("text").path("format");
            assertNotNull(node);
            assertEquals(123, node.path("value").asInt());
            assertEquals("author this decision", capturedRequest.get().path("input").asText());
            assertEquals("json_schema", format.path("type").asText());
            assertEquals("praxis_response", format.path("name").asText());
            assertTrue(format.path("strict").asBoolean());
            assertEquals("object", format.path("schema").path("type").asText());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void generatedTypedSchemaIsStrictCompatibleAndConvertsTheResult() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("{\"value\":123}"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            JsonNode node = service.generateJson(
                    "author this typed decision",
                    AiJsonSchema.ofClass(TypedValue.class));

            assertEquals(123, node.path("value").asInt());
            JsonNode schema = capturedRequest.get().path("text").path("format").path("schema");
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertEquals("value", schema.path("required").path(0).asText());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void generateJsonWithoutSchemaUsesNativeJsonObjectFormat() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("{\"value\":123}"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            JsonNode node = service.generateJson("return an object");

            assertEquals(123, node.path("value").asInt());
            assertEquals(
                    "json_object",
                    capturedRequest.get().path("text").path("format").path("type").asText());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void gpt5StructuredOutputRaisesDefaultBudgetButHonorsExplicitBudget() throws Exception {
        List<JsonNode> capturedRequests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            capturedRequests.add(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            writeJson(exchange, 200, completedResponse("{\"value\":123}"));
        });
        SpringAiOpenAiService service = service(server, "gpt-5.4-mini");
        server.start();
        try {
            AiJsonSchema schema = AiJsonSchema.ofSchema(
                    "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"number\"}},\"required\":[\"value\"],\"additionalProperties\":false}");

            service.generateJson("first", schema);
            service.generateJson("second", schema, AiCallConfig.builder().maxTokens(1800).build());

            assertEquals(8192, capturedRequests.get(0).path("max_output_tokens").asInt());
            assertTrue(capturedRequests.get(0).path("reasoning").isMissingNode());
            assertEquals(1800, capturedRequests.get(1).path("max_output_tokens").asInt());
            assertEquals("none", capturedRequests.get(1).path("reasoning").path("effort").asText());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void agenticReasoningPolicyDoesNotDisappearWhenOutputBudgetGrows() throws Exception {
        List<JsonNode> captured = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            captured.add(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            writeJson(exchange, 200, completedResponse("{}"));
        });
        SpringAiOpenAiService service = service(server, "gpt-5-mini");
        server.start();
        try {
            for (int budget : List.of(640, 1800, 4096)) {
                service.generateJson("classify governed intent", AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}"),
                        AiCallConfig.agenticAuthoringBuilder().maxTokens(budget).build());
            }
            assertEquals(3, captured.size());
            for (JsonNode request : captured) {
                assertEquals("gpt-5-mini", request.path("model").asText());
                assertEquals("low", request.path("reasoning").path("effort").asText());
            }
            assertEquals(4096, captured.get(2).path("max_output_tokens").asInt());
        } finally { service.closeDefaultClient(); server.stop(0); }
    }

    @Test
    void cancelledStructuredCallReleasesWorkerWithoutWaitingForItsDeadline() throws Exception {
        var received = new java.util.concurrent.CountDownLatch(1);
        var releaseResponse = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new AtomicBoolean(false);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var streamId = java.util.UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            received.countDown();
            try { releaseResponse.await(8, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        SpringAiOpenAiService service = service(server, "gpt-5-mini");
        server.start();
        try {
            var worker = executor.submit(() -> AiStreamExecutionContextHolder.execute(streamId, null, null,
                    cancelled::get, () -> service.generateJson("structured authoring transport",
                            AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}"),
                            AiCallConfig.agenticAuthoringBuilder().timeoutSeconds(5).build())));
            assertTrue(received.await(2, java.util.concurrent.TimeUnit.SECONDS));
            cancelled.set(true);
            AiStreamExecutionContextHolder.abortStream(streamId);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> worker.get(1, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.util.concurrent.CancellationException);
            assertEquals(1, calls.get());
        } finally {
            releaseResponse.countDown(); executor.shutdownNow(); service.closeDefaultClient(); server.stop(0);
        }
    }

    @Test
    void cancelledTurnDoesNotAdmitAnotherStructuredProviderCall() throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("{}"), captured);
        SpringAiOpenAiService service = service(server, "gpt-5-mini");
        server.start();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> AiStreamExecutionContextHolder.execute(java.util.UUID.randomUUID(), null, null,
                            () -> true, () -> service.generateJson("cancelled authoring",
                                    AiJsonSchema.ofSchema("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}"),
                                    AiCallConfig.agenticAuthoringBuilder().build())));
            assertNull(captured.get(), "A cancelled turn must stop before issuing HTTP");
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void completedResponseWithNoTextIsNormalizedAsProviderFailure() throws Exception {
        HttpServer server = responseServer(completedResponse(""), new AtomicReference<>());
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            AiProviderCallException exception =
                    assertThrows(AiProviderCallException.class, () -> service.generateText("ping"));

            assertEquals("openai", exception.getProvider());
            assertEquals(AiProviderCallException.Kind.UNKNOWN, exception.getKind());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void incompleteResponseIsNotAcceptedAsSuccessfulGeneration() throws Exception {
        String response = completedResponse("partial").replace("\"status\":\"completed\"", "\"status\":\"incomplete\"");
        HttpServer server = responseServer(response, new AtomicReference<>());
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            AiProviderInvocationTrace trace =
                    new AiProviderInvocationTrace("intent_full", 1, "openai", "gpt-4o-mini");
            AiProviderCallException exception =
                    assertThrows(AiProviderCallException.class, () -> service.generateText("ping", AiCallConfig.builder()
                            .invocationTrace(trace)
                            .build()));

            assertEquals(AiProviderCallException.Kind.UNKNOWN, exception.getKind());
            AiProviderInvocationTelemetry telemetry = trace.snapshot();
            assertEquals("incomplete", telemetry.finishReason());
            assertEquals("resp-safe-123", telemetry.responseId());
            assertEquals("gpt-5.4-mini-2026-06-01", telemetry.model());
            assertEquals(120, telemetry.inputTokens());
            assertEquals(18, telemetry.outputTokens());
            assertEquals(80, telemetry.cacheReadInputTokens());
            assertEquals(138, telemetry.totalTokens());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void explicitRefusalIsNotAcceptedAsEmptySuccess() throws Exception {
        String response = completedResponse("").replace(
                "{\"type\":\"output_text\",\"text\":\"\",\"annotations\":[]}",
                "{\"type\":\"refusal\",\"refusal\":\"policy restriction\"}");
        HttpServer server = responseServer(response, new AtomicReference<>());
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            AiProviderCallException exception =
                    assertThrows(AiProviderCallException.class, () -> service.generateText("ping"));

            assertEquals(AiProviderCallException.Kind.UNKNOWN, exception.getKind());
            assertTrue(exception.getCause().getMessage().contains("refused"));
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void invalidStructuredSchemaFailsAsClientErrorBeforeCallingProvider() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("{}"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            AiProviderCallException exception = assertThrows(
                    AiProviderCallException.class,
                    () -> service.generateJson("ping", AiJsonSchema.ofSchema("[\"not-an-object\"]")));

            assertEquals(AiProviderCallException.Kind.CLIENT_ERROR, exception.getKind());
            assertNull(capturedRequest.get());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void nonStrictNestedObjectSchemaFailsBeforeCallingProvider() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = responseServer(completedResponse("{}"), capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            AiProviderCallException exception = assertThrows(
                    AiProviderCallException.class,
                    () -> service.generateJson("ping", AiJsonSchema.ofSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "decision": {
                                  "type": "object",
                                  "properties": {"value": {"type": "string"}},
                                  "required": [],
                                  "additionalProperties": false
                                }
                              },
                              "required": ["decision"],
                              "additionalProperties": false
                            }
                            """)));

            assertEquals(AiProviderCallException.Kind.CLIENT_ERROR, exception.getKind());
            assertTrue(exception.getMessage().contains("required must contain every declared property"));
            assertNull(capturedRequest.get());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void quotaAndTransientRateLimitRemainDistinct() throws Exception {
        HttpServer quotaServer = errorServer(429, """
                {"error":{"type":"insufficient_quota","code":"insufficient_quota","message":"You exceeded your current quota."}}
                """);
        SpringAiOpenAiService quotaService = service(quotaServer, "gpt-4o-mini");
        quotaServer.start();
        try {
            AiProviderCallException quota =
                    assertThrows(AiProviderCallException.class, () -> quotaService.generateText("ping"));
            assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, quota.getKind());
            assertEquals(429, quota.getStatusCode());
            assertEquals("req_responses_safe_123", quota.getProviderRequestId());
            assertEquals("openai HTTP 429 (quota_exhausted)", quota.getMessage());
        } finally {
            quotaService.closeDefaultClient();
            quotaServer.stop(0);
        }

        HttpServer rateServer = errorServer(429, """
                {"error":{"type":"rate_limit_error","message":"Rate limit reached for requests per minute."}}
                """);
        SpringAiOpenAiService rateService = service(rateServer, "gpt-4o-mini");
        rateServer.start();
        try {
            AiProviderCallException rate =
                    assertThrows(AiProviderCallException.class, () -> rateService.generateText("ping"));
            assertEquals(AiProviderCallException.Kind.RATE_LIMIT, rate.getKind());
            assertEquals(429, rate.getStatusCode());
        } finally {
            rateService.closeDefaultClient();
            rateServer.stop(0);
        }
    }

    @Test
    void streamConsumesTypedDeltasAndCapturesTerminalTelemetry() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = streamServer(capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-5.4-mini");
        server.start();
        try {
            List<String> chunks = new ArrayList<>();
            AiProviderInvocationTrace trace =
                    new AiProviderInvocationTrace("turn_stream", 1, "openai", "gpt-5.4-mini");

            String result = service.generateTextStream(
                    "ping",
                    AiCallConfig.builder().invocationTrace(trace).build(),
                    chunks::add,
                    () -> false);

            assertEquals("pong", result);
            assertEquals(List.of("po", "ng"), chunks);
            assertTrue(capturedRequest.get().path("stream").asBoolean());
            assertFalse(capturedRequest.get().path("store").asBoolean(true));
            assertEquals("openai-responses-sdk", trace.snapshot().transport());
            assertEquals("resp-safe-123", trace.snapshot().responseId());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void cancellationBeforeStreamStartDoesNotCallProvider() throws Exception {
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        HttpServer server = streamServer(capturedRequest);
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> service.generateTextStream("ping", null, ignored -> {}, () -> true));

            assertNull(capturedRequest.get());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void missingApiKeyIsNormalizedAsStreamAuthenticationFailure() throws Exception {
        HttpServer server = streamServer(new AtomicReference<>());
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        ReflectionTestUtils.setField(service, "apiKey", null);
        try {
            AiProviderStreamException exception = assertThrows(
                    AiProviderStreamException.class,
                    () -> service.generateTextStream("ping", null, ignored -> {}, () -> false));

            assertEquals(AiProviderStreamException.Kind.AUTH, exception.getKind());
            assertEquals(401, exception.getStatusCode());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void emptyTerminalStreamIsNotAcceptedAsSuccess() throws Exception {
        HttpServer server = terminalOnlyStreamServer("");
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            AiProviderInvocationTrace trace =
                    new AiProviderInvocationTrace("turn_stream", 1, "openai", "gpt-4o-mini");
            AiProviderStreamException exception = assertThrows(
                    AiProviderStreamException.class,
                    () -> service.generateTextStream("ping", AiCallConfig.builder()
                            .invocationTrace(trace)
                            .build(), ignored -> {}, () -> false));

            assertEquals(AiProviderStreamException.Kind.UNKNOWN, exception.getKind());
            AiProviderInvocationTelemetry telemetry = trace.snapshot();
            assertEquals("completed", telemetry.finishReason());
            assertEquals("resp-safe-123", telemetry.responseId());
            assertEquals("gpt-5.4-mini-2026-06-01", telemetry.model());
            assertEquals(120, telemetry.inputTokens());
            assertEquals(18, telemetry.outputTokens());
            assertEquals(80, telemetry.cacheReadInputTokens());
            assertEquals(138, telemetry.totalTokens());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void cancellationAfterFirstChunkStopsTheSdkStream() throws Exception {
        HttpServer server = streamServer(new AtomicReference<>());
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<String> chunks = new ArrayList<>();
        server.start();
        try {
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> service.generateTextStream(
                            "ping",
                            null,
                            chunk -> {
                                chunks.add(chunk);
                                cancelled.set(true);
                            },
                            cancelled::get));

            assertEquals(List.of("po"), chunks);
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void timeoutIsNormalizedAndSdkDoesNotRetryGeneration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1500);
                writeJson(exchange, 200, completedResponse("late"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (IOException exception) {
                exchange.close();
            }
        });
        SpringAiOpenAiService service = service(server, "gpt-5-mini");
        AiProviderInvocationTrace trace = new AiProviderInvocationTrace("intent_full", 1, "openai", null);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1);
        server.start();
        try {
            AiProviderCallException exception =
                    assertThrows(AiProviderCallException.class, () -> service.generateText("ping",
                            AiCallConfig.agenticAuthoringBuilder().invocationTrace(trace).build()));

            assertEquals("gpt-5-mini", trace.snapshot().model());
            assertNull(trace.snapshot().inputTokens());
            assertNull(trace.snapshot().responseId());
            assertEquals(AiProviderCallException.Kind.TIMEOUT, exception.getKind());
            assertEquals(1, calls.get());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    @Test
    void sdkRetriesRemainDisabledSoOrchestratorOwnsRetryPolicy() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            writeJson(exchange, 500, "{\"error\":{\"message\":\"temporary server failure\"}}");
        });
        SpringAiOpenAiService service = service(server, "gpt-4o-mini");
        server.start();
        try {
            AiProviderCallException exception =
                    assertThrows(AiProviderCallException.class, () -> service.generateText("ping"));

            assertEquals(AiProviderCallException.Kind.SERVER_ERROR, exception.getKind());
            assertEquals(1, calls.get());
        } finally {
            service.closeDefaultClient();
            server.stop(0);
        }
    }

    private record TypedValue(int value) {
    }

    private SpringAiOpenAiService service(HttpServer server, String model) {
        return service(server, model, new OpenAiHostedSkillProperties());
    }

    private SpringAiOpenAiService service(
            HttpServer server,
            String model,
            OpenAiHostedSkillProperties hostedSkillProperties) {
        SpringAiOpenAiService service = new SpringAiOpenAiService(objectMapper, hostedSkillProperties);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "model", model);
        ReflectionTestUtils.setField(service, "temperature", 0.1d);
        ReflectionTestUtils.setField(service, "maxTokens", 128);
        ReflectionTestUtils.setField(service, "jsonMinCompletionTokens", 8192);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "usageOriginClass", "ci-live-gate");
        return service;
    }

    private HttpServer responseServer(String response, AtomicReference<JsonNode> capturedRequest) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            capturedRequest.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            writeJson(exchange, 200, response);
        });
        return server;
    }

    private HttpServer errorServer(int status, String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("x-request-id", "req_responses_safe_123");
            writeJson(exchange, status, response);
        });
        return server;
    }

    private HttpServer streamServer(AtomicReference<JsonNode> capturedRequest) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            capturedRequest.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            String response = objectMapper.writeValueAsString(objectMapper.readTree(completedResponse("pong")));
            String body = """
                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg-safe-123","output_index":0,"content_index":0,"delta":"po","logprobs":[]}

                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg-safe-123","output_index":0,"content_index":0,"delta":"ng","logprobs":[]}

                    event: response.completed
                    data: {"type":"response.completed","sequence_number":3,"response":%s}

                    """.formatted(response);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private HttpServer terminalOnlyStreamServer(String text) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String response = objectMapper.writeValueAsString(objectMapper.readTree(completedResponse(text)));
            String body = """
                    event: response.completed
                    data: {"type":"response.completed","sequence_number":1,"response":%s}

                    """.formatted(response);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private String completedResponse(String text) throws IOException {
        String escapedText = objectMapper.writeValueAsString(text);
        return """
                {
                  "id":"resp-safe-123",
                  "object":"response",
                  "created_at":1,
                  "status":"completed",
                  "error":null,
                  "incomplete_details":null,
                  "instructions":null,
                  "max_output_tokens":128,
                  "model":"gpt-5.4-mini-2026-06-01",
                  "output":[{
                    "id":"msg-safe-123",
                    "type":"message",
                    "status":"completed",
                    "role":"assistant",
                    "content":[{"type":"output_text","text":%s,"annotations":[]}]
                  }],
                  "parallel_tool_calls":true,
                  "tool_choice":"auto",
                  "tools":[],
                  "usage":{
                    "input_tokens":120,
                    "input_tokens_details":{"cached_tokens":80},
                    "output_tokens":18,
                    "output_tokens_details":{"reasoning_tokens":0},
                    "total_tokens":138
                  }
                }
                """.formatted(escapedText);
    }

    private void writeJson(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
