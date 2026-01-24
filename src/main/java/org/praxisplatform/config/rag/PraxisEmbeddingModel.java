package org.praxisplatform.config.rag;

import java.util.ArrayList;
import java.util.List;
import org.praxisplatform.config.service.EmbeddingService;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

public class PraxisEmbeddingModel implements EmbeddingModel {

    private final EmbeddingService embeddingService;
    private final int defaultDimensions;

    public PraxisEmbeddingModel(EmbeddingService embeddingService, int defaultDimensions) {
        this.embeddingService = embeddingService;
        this.defaultDimensions = defaultDimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (request == null || request.getInstructions() == null || request.getInstructions().isEmpty()) {
            return new EmbeddingResponse(List.of());
        }
        EmbeddingOptions options = request.getOptions();
        EmbeddingService.EmbeddingCallConfig config = buildConfig(options);
        List<Embedding> embeddings = new ArrayList<>();
        int index = 0;
        for (String input : request.getInstructions()) {
            List<Float> vector = embeddingService.embed(input, config);
            embeddings.add(new Embedding(toFloatArray(vector), index++));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        String text = resolveContent(document);
        List<Float> vector = embeddingService.embed(text);
        return toFloatArray(vector);
    }

    @Override
    public List<float[]> embed(
            List<Document> documents,
            EmbeddingOptions options,
            BatchingStrategy batchingStrategy) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        EmbeddingService.EmbeddingCallConfig config = buildConfig(options);
        List<float[]> embeddings = new ArrayList<>(documents.size());
        for (Document document : documents) {
            String text = resolveContent(document);
            List<Float> vector = embeddingService.embed(text, config);
            embeddings.add(toFloatArray(vector));
        }
        return embeddings;
    }

    @Override
    public int dimensions() {
        return defaultDimensions;
    }

    private EmbeddingService.EmbeddingCallConfig buildConfig(EmbeddingOptions options) {
        if (options == null) {
            return null;
        }
        String model = options.getModel();
        Integer dimensions = options.getDimensions();
        if ((model == null || model.isBlank()) && dimensions == null) {
            return null;
        }
        return new EmbeddingService.EmbeddingCallConfig(null, null, model, dimensions);
    }

    private String resolveContent(Document document) {
        if (document == null) {
            return "";
        }
        String text = document.getText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        return document.getFormattedContent(MetadataMode.NONE);
    }

    private float[] toFloatArray(List<Float> values) {
        if (values == null || values.isEmpty()) {
            return new float[0];
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Float value = values.get(i);
            out[i] = value != null ? value : 0.0f;
        }
        return out;
    }
}
