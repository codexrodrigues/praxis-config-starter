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
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
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
