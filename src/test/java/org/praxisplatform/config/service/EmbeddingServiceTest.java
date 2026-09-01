package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.ClientException;
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("unit")
class EmbeddingServiceTest {

    @Test
    void embedUsesOpenAiClient() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new Embedding(new float[] {1.0f, 2.0f, 3.0f}, 0)));
        when(client.call(any(EmbeddingRequest.class))).thenReturn(response);

        EmbeddingService service = new EmbeddingService(provider(client), emptyGoogleGenAiProvider(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "openaiApiKey", "key");
        ReflectionTestUtils.setField(service, "openaiBaseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(service, "openaiModel", "text-embedding-3-large");
        ReflectionTestUtils.setField(service, "openaiDimensions", 0);
        ReflectionTestUtils.setField(service, "geminiDimensions", 0);

        List<Float> vector = service.embed("hello");

        assertEquals(3, vector.size());
        assertEquals(1.0f, vector.get(0));
    }

    @Test
    void embedUsesGoogleGenAiClient() {
        GoogleGenAiTextEmbeddingModel client = Mockito.mock(GoogleGenAiTextEmbeddingModel.class);
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new Embedding(new float[] {0.5f, 0.25f}, 0)));
        when(client.call(any(EmbeddingRequest.class))).thenReturn(response);

        EmbeddingService service = new EmbeddingService(emptyOpenAiProvider(), provider(client), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "gemini");
        ReflectionTestUtils.setField(service, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(service, "geminiModel", "gemini-embedding-2");
        ReflectionTestUtils.setField(service, "openaiDimensions", 0);
        ReflectionTestUtils.setField(service, "geminiDimensions", 0);

        List<Float> vector = service.embed("hello");

        assertEquals(2, vector.size());
        assertEquals(0.5f, vector.get(0));
    }

    @Test
    void embedAllPreservesGeminiQuotaFailureAsCanonicalProviderException() {
        GoogleGenAiTextEmbeddingModel client = Mockito.mock(GoogleGenAiTextEmbeddingModel.class);
        when(client.call(any(EmbeddingRequest.class))).thenThrow(new ClientException(
                429,
                "RESOURCE_EXHAUSTED",
                "You exceeded your current quota for embed content requests."));

        EmbeddingService service = new EmbeddingService(emptyOpenAiProvider(), provider(client), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "gemini");
        ReflectionTestUtils.setField(service, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(service, "geminiModel", "gemini-embedding-2");
        ReflectionTestUtils.setField(service, "geminiDimensions", 768);

        AiProviderCallException failure = assertThrows(
                AiProviderCallException.class,
                () -> service.embedAll(List.of("first", "second")));

        assertEquals("gemini", failure.getProvider());
        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, failure.getKind());
        assertEquals(429, failure.getStatusCode());
    }

    @Test
    void embedPreservesGeminiQuotaFailureAsCanonicalProviderException() {
        GoogleGenAiTextEmbeddingModel client = Mockito.mock(GoogleGenAiTextEmbeddingModel.class);
        when(client.call(any(EmbeddingRequest.class))).thenThrow(new ClientException(
                429,
                "RESOURCE_EXHAUSTED",
                "You exceeded your current quota for embed content requests."));

        EmbeddingService service = new EmbeddingService(emptyOpenAiProvider(), provider(client), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "gemini");
        ReflectionTestUtils.setField(service, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(service, "geminiModel", "gemini-embedding-2");
        ReflectionTestUtils.setField(service, "geminiDimensions", 768);

        AiProviderCallException failure = assertThrows(
                AiProviderCallException.class,
                () -> service.embed("only document"));

        assertEquals("gemini", failure.getProvider());
        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, failure.getKind());
        assertEquals("gemini HTTP 429 (quota_exhausted)", failure.getMessage());
    }

    @Test
    void embedPreservesOpenAiRetryAfterWithoutExposingProviderMessage() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        OpenAIServiceException providerFailure = Mockito.mock(OpenAIServiceException.class);
        when(providerFailure.statusCode()).thenReturn(429);
        when(providerFailure.code()).thenReturn(Optional.of("rate_limit_exceeded"));
        when(providerFailure.type()).thenReturn(Optional.of("requests"));
        when(providerFailure.headers()).thenReturn(Headers.builder().put("retry-after-ms", "1500").build());
        when(client.call(any(EmbeddingRequest.class))).thenThrow(providerFailure);

        EmbeddingService service = new EmbeddingService(provider(client), emptyGoogleGenAiProvider(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "openaiApiKey", "key");
        ReflectionTestUtils.setField(service, "openaiModel", "text-embedding-3-large");
        ReflectionTestUtils.setField(service, "openaiDimensions", 768);
        Instant before = Instant.now();

        AiProviderCallException failure = assertThrows(
                AiProviderCallException.class,
                () -> service.embed("only document"));

        assertEquals(AiProviderCallException.Kind.RATE_LIMIT, failure.getKind());
        assertEquals("openai HTTP 429 (rate_limit)", failure.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                !failure.getRetryAfter().isBefore(before.plusMillis(1500)));
    }

    @Test
    void embedRetriesOneTransientOpenAiFailureWithinTheCanonicalBudget() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        OpenAIServiceException providerFailure = Mockito.mock(OpenAIServiceException.class);
        when(providerFailure.statusCode()).thenReturn(503);
        when(providerFailure.code()).thenReturn(Optional.of("service_unavailable"));
        when(providerFailure.type()).thenReturn(Optional.of("server_error"));
        when(providerFailure.headers()).thenReturn(Headers.builder().build());
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new Embedding(new float[] {1.0f, 2.0f}, 0)));
        when(client.call(any(EmbeddingRequest.class)))
                .thenThrow(providerFailure)
                .thenReturn(response);

        EmbeddingService service = openAiService(client);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "retryInitialDelayMs", 0L);
        ReflectionTestUtils.setField(service, "retryMaxDelayMs", 0L);

        List<Float> vector = service.embed("retryable document");

        assertEquals(List.of(1.0f, 2.0f), vector);
        verify(client, times(2)).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedRetriesSpringAiWrappedOpenAiRateLimitWithinTheCanonicalBudget() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        NonTransientAiException providerFailure = new NonTransientAiException(
                "HTTP 429 - {\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new Embedding(new float[] {1.0f, 2.0f}, 0)));
        when(client.call(any(EmbeddingRequest.class)))
                .thenThrow(providerFailure)
                .thenReturn(response);

        EmbeddingService service = openAiService(client);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "retryInitialDelayMs", 0L);
        ReflectionTestUtils.setField(service, "retryMaxDelayMs", 0L);

        List<Float> vector = service.embed("rate limited document");

        assertEquals(List.of(1.0f, 2.0f), vector);
        verify(client, times(2)).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedRetriesSpringAiWrappedOpenAiServerFailureWithinTheCanonicalBudget() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        TransientAiException providerFailure = new TransientAiException(
                "HTTP 503 - {\"error\":{\"code\":\"service_unavailable\"}}");
        EmbeddingResponse response = new EmbeddingResponse(
                List.of(new Embedding(new float[] {1.0f, 2.0f}, 0)));
        when(client.call(any(EmbeddingRequest.class)))
                .thenThrow(providerFailure)
                .thenReturn(response);

        EmbeddingService service = openAiService(client);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "retryInitialDelayMs", 0L);
        ReflectionTestUtils.setField(service, "retryMaxDelayMs", 0L);

        List<Float> vector = service.embed("temporarily unavailable document");

        assertEquals(List.of(1.0f, 2.0f), vector);
        verify(client, times(2)).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedDoesNotRetrySpringAiWrappedOpenAiQuotaExhaustion() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        NonTransientAiException providerFailure = new NonTransientAiException(
                "HTTP 429 - {\"error\":{\"code\":\"insufficient_quota\"}}");
        when(client.call(any(EmbeddingRequest.class))).thenThrow(providerFailure);

        EmbeddingService service = openAiService(client);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "retryInitialDelayMs", 0L);
        ReflectionTestUtils.setField(service, "retryMaxDelayMs", 0L);

        AiProviderCallException failure = assertThrows(
                AiProviderCallException.class,
                () -> service.embed("quota exhausted document"));

        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, failure.getKind());
        assertEquals("openai HTTP 429 (quota_exhausted)", failure.getMessage());
        verify(client, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    void embedDoesNotRetryWhenProviderDelayExceedsTheCanonicalBudget() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        OpenAIServiceException providerFailure = Mockito.mock(OpenAIServiceException.class);
        when(providerFailure.statusCode()).thenReturn(429);
        when(providerFailure.code()).thenReturn(Optional.of("rate_limit_exceeded"));
        when(providerFailure.type()).thenReturn(Optional.of("requests"));
        when(providerFailure.headers()).thenReturn(Headers.builder().put("retry-after-ms", "10000").build());
        when(client.call(any(EmbeddingRequest.class))).thenThrow(providerFailure);

        EmbeddingService service = openAiService(client);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "retryInitialDelayMs", 100L);
        ReflectionTestUtils.setField(service, "retryMaxDelayMs", 2_000L);

        AiProviderCallException failure = assertThrows(
                AiProviderCallException.class,
                () -> service.embed("rate limited document"));

        assertEquals(AiProviderCallException.Kind.RATE_LIMIT, failure.getKind());
        verify(client, times(1)).call(any(EmbeddingRequest.class));
    }

    @Test
    void googleRestFailureUsesStructuredQuotaAndRetryInfoWithoutRetainingBody() {
        EmbeddingService service = new EmbeddingService(
                emptyOpenAiProvider(), emptyGoogleGenAiProvider(), new ObjectMapper());
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        String body = """
                {
                  "error": {
                    "status": "RESOURCE_EXHAUSTED",
                    "message": "sensitive provider diagnostic",
                    "details": [
                      {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[]},
                      {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"2.5s"}
                    ]
                  }
                }
                """;
        HttpHeaders headers = HttpHeaders.of(
                Map.of("Retry-After", List.of("1")), (name, value) -> true);

        AiProviderCallException failure = service.classifyGoogleGenAiRestFailure(429, body, headers, now);

        assertEquals(AiProviderCallException.Kind.QUOTA_EXHAUSTED, failure.getKind());
        assertEquals(now.plusMillis(2500), failure.getRetryAfter());
        assertEquals("gemini HTTP 429 (quota_exhausted)", failure.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("sensitive"));
    }

    @Test
    void embedRagQueryUsesGeminiEmbedding2RetrievalInstruction() {
        GoogleGenAiTextEmbeddingModel client = Mockito.mock(GoogleGenAiTextEmbeddingModel.class);
        when(client.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(
                List.of(new Embedding(new float[] {0.5f}, 0))));
        EmbeddingService service = new EmbeddingService(emptyOpenAiProvider(), provider(client), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "gemini");
        ReflectionTestUtils.setField(service, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(service, "geminiModel", "gemini-embedding-2");
        ReflectionTestUtils.setField(service, "geminiDimensions", 0);
        ReflectionTestUtils.setField(service, "geminiEmbedding2RetrievalInstructionsEnabled", true);

        service.embedRagQuery("coloque o status inativo em vermelho");

        ArgumentCaptor<EmbeddingRequest> requestCaptor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(client).call(requestCaptor.capture());
        assertEquals(
                "task: search result | query: coloque o status inativo em vermelho",
                requestCaptor.getValue().getInstructions().getFirst());
    }

    @Test
    void googleGenAiRestPayloadPinsConfiguredVectorDimensions() {
        EmbeddingService service = new EmbeddingService(
                emptyOpenAiProvider(),
                emptyGoogleGenAiProvider(),
                new ObjectMapper());

        JsonNode payload = ReflectionTestUtils.invokeMethod(
                service,
                "googleGenAiEmbeddingPayload",
                "semantic authoring context",
                768);

        assertEquals("semantic authoring context", payload.path("content").path("parts").path(0).path("text").asText());
        assertEquals(768, payload.path("outputDimensionality").asInt());
    }

    @Test
    void embedAllUsesOneOpenAiRequestAndPreservesProviderIndexes() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        EmbeddingResponse response = new EmbeddingResponse(List.of(
                new Embedding(new float[] {3.0f, 4.0f}, 1),
                new Embedding(new float[] {1.0f, 2.0f}, 0)));
        when(client.call(any(EmbeddingRequest.class))).thenReturn(response);

        EmbeddingService service = new EmbeddingService(provider(client), emptyGoogleGenAiProvider(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "openaiApiKey", "key");
        ReflectionTestUtils.setField(service, "openaiBaseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(service, "openaiModel", "text-embedding-3-large");
        ReflectionTestUtils.setField(service, "openaiDimensions", 0);
        ReflectionTestUtils.setField(service, "geminiDimensions", 0);

        List<List<Float>> vectors = service.embedAll(List.of("first", "second"));

        verify(client, times(1)).call(any(EmbeddingRequest.class));
        assertEquals(List.of(1.0f, 2.0f), vectors.get(0));
        assertEquals(List.of(3.0f, 4.0f), vectors.get(1));
    }

    @Test
    void embedAllPartitionsLargeOpenAiCorporaWithoutLosingOrder() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        when(client.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            List<Embedding> results = new java.util.ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) {
                results.add(new Embedding(
                        new float[] {(float) request.getInstructions().get(index).length()},
                        index));
            }
            return new EmbeddingResponse(results);
        });

        EmbeddingService service = new EmbeddingService(provider(client), emptyGoogleGenAiProvider(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "openaiApiKey", "key");
        ReflectionTestUtils.setField(service, "openaiBaseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(service, "openaiModel", "text-embedding-3-large");
        ReflectionTestUtils.setField(service, "openaiDimensions", 0);
        ReflectionTestUtils.setField(service, "geminiDimensions", 0);
        List<String> inputs = List.of(
                "a".repeat(125_000),
                "b".repeat(125_000),
                "final");

        List<List<Float>> vectors = service.embedAll(inputs);

        verify(client, times(2)).call(any(EmbeddingRequest.class));
        assertEquals(125_000f, vectors.get(0).get(0));
        assertEquals(125_000f, vectors.get(1).get(0));
        assertEquals(5f, vectors.get(2).get(0));
    }

    @Test
    void embedAllRetriesOnlyTheFailedOpenAiPartition() {
        OpenAiEmbeddingModel client = Mockito.mock(OpenAiEmbeddingModel.class);
        OpenAIServiceException providerFailure = Mockito.mock(OpenAIServiceException.class);
        when(providerFailure.statusCode()).thenReturn(503);
        when(providerFailure.code()).thenReturn(Optional.of("service_unavailable"));
        when(providerFailure.type()).thenReturn(Optional.of("server_error"));
        when(providerFailure.headers()).thenReturn(Headers.builder().build());
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw providerFailure;
            }
            EmbeddingRequest request = invocation.getArgument(0);
            List<Embedding> results = new java.util.ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) {
                results.add(new Embedding(
                        new float[] {(float) request.getInstructions().get(index).length()},
                        index));
            }
            return new EmbeddingResponse(results);
        });

        EmbeddingService service = openAiService(client);
        ReflectionTestUtils.setField(service, "retryMaxAttempts", 2);
        ReflectionTestUtils.setField(service, "retryInitialDelayMs", 0L);
        ReflectionTestUtils.setField(service, "retryMaxDelayMs", 0L);
        List<String> inputs = List.of(
                "a".repeat(125_000),
                "b".repeat(125_000),
                "final");

        List<List<Float>> vectors = service.embedAll(inputs);

        verify(client, times(3)).call(any(EmbeddingRequest.class));
        assertEquals(125_000f, vectors.get(0).get(0));
        assertEquals(125_000f, vectors.get(1).get(0));
        assertEquals(5f, vectors.get(2).get(0));
    }

    private EmbeddingService openAiService(OpenAiEmbeddingModel client) {
        EmbeddingService service = new EmbeddingService(provider(client), emptyGoogleGenAiProvider(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "openaiApiKey", "key");
        ReflectionTestUtils.setField(service, "openaiBaseUrl", "https://api.openai.com");
        ReflectionTestUtils.setField(service, "openaiModel", "text-embedding-3-large");
        ReflectionTestUtils.setField(service, "openaiDimensions", 0);
        ReflectionTestUtils.setField(service, "geminiDimensions", 0);
        return service;
    }

    private static ObjectProvider<OpenAiEmbeddingModel> provider(OpenAiEmbeddingModel client) {
        return new ObjectProvider<>() {
            @Override
            public OpenAiEmbeddingModel getObject(Object... args) {
                return client;
            }

            @Override
            public OpenAiEmbeddingModel getIfAvailable() {
                return client;
            }

            @Override
            public OpenAiEmbeddingModel getIfUnique() {
                return client;
            }

            @Override
            public Iterator<OpenAiEmbeddingModel> iterator() {
                return List.of(client).iterator();
            }
        };
    }

    private static ObjectProvider<GoogleGenAiTextEmbeddingModel> provider(GoogleGenAiTextEmbeddingModel client) {
        return new ObjectProvider<>() {
            @Override
            public GoogleGenAiTextEmbeddingModel getObject(Object... args) {
                return client;
            }

            @Override
            public GoogleGenAiTextEmbeddingModel getIfAvailable() {
                return client;
            }

            @Override
            public GoogleGenAiTextEmbeddingModel getIfUnique() {
                return client;
            }

            @Override
            public Iterator<GoogleGenAiTextEmbeddingModel> iterator() {
                return List.of(client).iterator();
            }
        };
    }

    private static ObjectProvider<OpenAiEmbeddingModel> emptyOpenAiProvider() {
        return new ObjectProvider<>() {
            @Override
            public OpenAiEmbeddingModel getObject(Object... args) {
                return null;
            }

            @Override
            public OpenAiEmbeddingModel getIfAvailable() {
                return null;
            }

            @Override
            public OpenAiEmbeddingModel getIfUnique() {
                return null;
            }

            @Override
            public Iterator<OpenAiEmbeddingModel> iterator() {
                return List.<OpenAiEmbeddingModel>of().iterator();
            }
        };
    }

    private static ObjectProvider<GoogleGenAiTextEmbeddingModel> emptyGoogleGenAiProvider() {
        return new ObjectProvider<>() {
            @Override
            public GoogleGenAiTextEmbeddingModel getObject(Object... args) {
                return null;
            }

            @Override
            public GoogleGenAiTextEmbeddingModel getIfAvailable() {
                return null;
            }

            @Override
            public GoogleGenAiTextEmbeddingModel getIfUnique() {
                return null;
            }

            @Override
            public Iterator<GoogleGenAiTextEmbeddingModel> iterator() {
                return List.<GoogleGenAiTextEmbeddingModel>of().iterator();
            }
        };
    }
}
