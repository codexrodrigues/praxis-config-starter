package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;

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

    String getProviderName();
}
