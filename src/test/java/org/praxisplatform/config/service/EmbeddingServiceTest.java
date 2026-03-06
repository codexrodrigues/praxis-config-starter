package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        ReflectionTestUtils.setField(service, "geminiModel", "text-embedding-004");
        ReflectionTestUtils.setField(service, "openaiDimensions", 0);
        ReflectionTestUtils.setField(service, "geminiDimensions", 0);

        List<Float> vector = service.embed("hello");

        assertEquals(2, vector.size());
        assertEquals(0.5f, vector.get(0));
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
