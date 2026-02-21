package org.praxisplatform.config;

import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary; // Correctly placed import
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.praxisplatform.config.service.EmbeddingService;
import org.praxisplatform.config.service.SpringAiGeminiService;
import org.praxisplatform.config.service.SpringAiOpenAiService;
import org.praxisplatform.config.rag.RagChatAdvisorProperties;
import org.springframework.ai.vectorstore.VectorStore; // Added

import static org.mockito.Mockito.mock;

@Profile("test")
@TestConfiguration
public class TestAiConfig {

    @Bean
    public GoogleGenAiTextEmbeddingModel googleGenAiTextEmbeddingModel() {
        return mock(GoogleGenAiTextEmbeddingModel.class);
    }

    @Bean
    public GoogleGenAiChatModel googleGenAiChatModel() {
        return mock(GoogleGenAiChatModel.class);
    }

    @Bean
    public OpenAiChatModel openAiChatModel() {
        return mock(OpenAiChatModel.class);
    }

    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel() {
        return mock(OpenAiEmbeddingModel.class);
    }

    @Bean
    public SpringAiGeminiService springAiGeminiService() {
        return mock(SpringAiGeminiService.class);
    }

    @Bean
    public SpringAiOpenAiService springAiOpenAiService() {
        return mock(SpringAiOpenAiService.class);
    }

    @Bean
    public EmbeddingService embeddingService() {
        return mock(EmbeddingService.class);
    }

    @Bean
    @Primary
    public RagChatAdvisorProperties ragChatAdvisorProperties() {
        return mock(RagChatAdvisorProperties.class);
    }

    @Bean
    public VectorStore vectorStore() { // Added
        return mock(VectorStore.class);
    }

}
