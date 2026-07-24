package org.praxisplatform.config.rag;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Identifies the embedding space used by the derived Praxis RAG index.
 *
 * <p>The profile is deliberately derived from runtime configuration rather than supplied by a
 * corpus producer. A vector can only be compared with vectors produced by the same provider,
 * model, dimensions and retrieval encoding.
 */
@Component
public class RagEmbeddingProfile {

    @Value("${spring.ai.embedding.provider:gemini}")
    private String provider;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-large}")
    private String openAiModel;

    @Value("${spring.ai.openai.embedding.options.dimensions:768}")
    private int openAiDimensions;

    @Value("${spring.ai.google.genai.embedding.text.options.model:gemini-embedding-2}")
    private String geminiModel;

    @Value("${spring.ai.google.genai.embedding.text.options.dimensions:768}")
    private int geminiDimensions;

    @Value("${praxis.ai.rag.embedding.retrieval-format-version:v1}")
    private String retrievalFormatVersion;

    public String id() {
        String resolvedProvider = normalized(provider, "gemini");
        boolean openAi = "openai".equals(resolvedProvider);
        String model = normalized(openAi ? openAiModel : geminiModel,
                openAi ? "text-embedding-3-large" : "gemini-embedding-2");
        int dimensions = openAi ? openAiDimensions : geminiDimensions;
        return "rag-" + normalized(retrievalFormatVersion, "v1")
                + "__" + resolvedProvider
                + "__" + model
                + "__" + Math.max(1, dimensions);
    }

    private String normalized(String value, String fallback) {
        return RagDocumentIdentity.normalizeToken(value, fallback);
    }
}
