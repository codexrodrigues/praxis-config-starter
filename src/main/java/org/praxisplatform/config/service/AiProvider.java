package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.praxisplatform.config.dto.AiProviderModel;

/**
 * Contrato canônico de um provedor AI conectável ao config-starter.
 *
 * <p>
 * Implementações concretas encapsulam geração de texto/JSON, descoberta de modelos, suporte a
 * streaming e cancelamento cooperativo. O restante da plataforma deve depender desta interface,
 * não de SDKs específicos de fornecedor.
 * </p>
 */
public interface AiProvider {
    JsonNode generateJson(String prompt);

    default JsonNode generateJson(String prompt, AiJsonSchema schema) {
        return generateJson(prompt);
    }

    default JsonNode generateJson(String prompt, AiJsonSchema schema, AiCallConfig config) {
        return generateJson(prompt, schema);
    }

    String generateText(String prompt);

    default String generateText(String prompt, AiCallConfig config) {
        return generateText(prompt);
    }

    default boolean supportsTextStreaming(AiCallConfig config) {
        return false;
    }

    default boolean supportsTurnCancellation(AiCallConfig config) {
        return false;
    }

    default String generateTextStream(
            String prompt,
            AiCallConfig config,
            Consumer<String> onChunk,
            Supplier<Boolean> cancellationRequested) {
        if (cancellationRequested != null && Boolean.TRUE.equals(cancellationRequested.get())) {
            throw new java.util.concurrent.CancellationException("AI provider call cancelled before start.");
        }
        String text = generateText(prompt, config);
        if (onChunk != null && text != null && !text.isBlank()) {
            onChunk.accept(text);
        }
        if (cancellationRequested != null && Boolean.TRUE.equals(cancellationRequested.get())) {
            throw new java.util.concurrent.CancellationException("AI provider call cancelled.");
        }
        return text;
    }

    default void cancelTurn(UUID threadId, UUID turnId) {
        // Optional by provider; default no-op.
    }

    List<AiProviderModel> listModels(AiCallConfig config);

    String getProviderName();
}
