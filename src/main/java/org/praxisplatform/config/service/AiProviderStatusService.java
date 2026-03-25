package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.dto.AiProviderStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolve o status efetivo do provider AI visto pelo runtime.
 *
 * <p>O servico combina configuracao persistida e variaveis de ambiente para informar provider,
 * modelo, origem da configuracao e presenca de API key, sem expor o segredo em si.
 */
@Service
@RequiredArgsConstructor
public class AiProviderStatusService {

    private static final String GLOBAL_CONFIG_COMPONENT_TYPE = "praxis-global-config-editor";
    private static final String GLOBAL_CONFIG_KEY = "praxis:global-config";

    @Value("${praxis.ai.provider:gemini}")
    private String defaultProvider;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String openaiModel;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.0-flash}")
    private String geminiModel;

    @Value("${spring.ai.openai.chat.options.model:grok-2-latest}")
    private String xaiModel;

    @Value("${spring.ai.openai.api-key:#{null}}")
    private String openaiApiKey;

    @Value("${spring.ai.google.genai.api-key:#{null}}")
    private String geminiApiKey;

    @Value("${spring.ai.openai.api-key:#{null}}")
    private String xaiApiKey;

    private final ObjectMapper objectMapper;
    private final UserConfigService userConfigService;
    private final AiApiKeyCryptoService apiKeyCryptoService;

    public AiProviderStatusResponse getStatus(String tenantId, String userId, String environment) {
        StoredAiConfig stored = resolveStoredConfig(tenantId, userId, environment);
        String source = stored != null ? "stored" : "env";
        boolean hasStored = stored != null;
        String provider = resolveProviderName(hasStored ? stored.provider() : null);
        String model = resolveModel(provider, hasStored ? stored.model() : null);
        boolean hasApiKey = resolveHasApiKey(provider, hasStored ? stored.apiKey() : null, hasStored);

        return AiProviderStatusResponse.builder()
                .provider(provider)
                .model(model)
                .hasApiKey(hasApiKey)
                .source(source)
                .success(true)
                .build();
    }

    private boolean resolveHasApiKey(String provider, String storedApiKey, boolean hasStoredConfig) {
        if (hasStoredConfig) {
            String normalized = normalizeProvider(provider);
            if ("mock".equals(normalized)) {
                return false;
            }
            if (storedApiKey != null && !storedApiKey.isBlank()) {
                return true;
            }
            if ("openai".equals(normalized) || "open-ai".equals(normalized)) {
                return openaiApiKey != null && !openaiApiKey.isBlank();
            }
            if ("xai".equals(normalized) || "grok".equals(normalized) || "grok-ai".equals(normalized)) {
                return xaiApiKey != null && !xaiApiKey.isBlank();
            }
            return geminiApiKey != null && !geminiApiKey.isBlank();
        }
        if (storedApiKey != null && !storedApiKey.isBlank()) {
            return true;
        }
        String normalized = normalizeProvider(provider);
        if ("openai".equals(normalized) || "open-ai".equals(normalized)) {
            return openaiApiKey != null && !openaiApiKey.isBlank();
        }
        if ("xai".equals(normalized) || "grok".equals(normalized) || "grok-ai".equals(normalized)) {
            return xaiApiKey != null && !xaiApiKey.isBlank();
        }
        if ("mock".equals(normalized)) {
            return false;
        }
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    private String resolveModel(String provider, String storedModel) {
        String resolved = trimToNull(storedModel);
        if (resolved != null) {
            return resolved;
        }
        String normalized = normalizeProvider(provider);
        if ("openai".equals(normalized) || "open-ai".equals(normalized)) {
            return openaiModel;
        }
        if ("xai".equals(normalized) || "grok".equals(normalized) || "grok-ai".equals(normalized)) {
            return xaiModel;
        }
        if ("mock".equals(normalized)) {
            return "mock-default";
        }
        return geminiModel;
    }

    private String resolveProviderName(String storedProvider) {
        String normalized = normalizeProvider(storedProvider);
        if (normalized != null) {
            return normalized;
        }
        normalized = normalizeProvider(defaultProvider);
        return normalized != null ? normalized : "gemini";
    }

    private String normalizeProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase();
    }

    private StoredAiConfig resolveStoredConfig(String tenantId, String userId, String environment) {
        String resolvedTenant = trimToNull(tenantId);
        if (resolvedTenant == null || userConfigService == null) {
            return null;
        }
        String resolvedEnv = trimToNull(environment);
        for (String componentId : buildGlobalConfigIds(resolvedTenant)) {
            Optional<UserConfigService.ResolvedConfig> resolved =
                    userConfigService.getResolved(
                            resolvedTenant,
                            userId,
                            GLOBAL_CONFIG_COMPONENT_TYPE,
                            componentId,
                            resolvedEnv);
            if (resolved.isEmpty()) {
                continue;
            }
            JsonNode payload = parsePayload(resolved.get().config().getPayload());
            JsonNode aiNode = payload.get("ai");
            if (aiNode == null || !aiNode.isObject()) {
                continue;
            }
            String provider = trimToNull(textOrNull(aiNode.get("provider")));
            String model = trimToNull(textOrNull(aiNode.get("model")));
            String apiKey = trimToNull(textOrNull(aiNode.get("apiKey")));
            if (apiKey == null) {
                String encrypted = trimToNull(textOrNull(aiNode.get("apiKeyEncrypted")));
                apiKey = trimToNull(apiKeyCryptoService.decrypt(encrypted));
            }
            if (provider != null || model != null || apiKey != null) {
                return new StoredAiConfig(provider, model, apiKey);
            }
        }
        return null;
    }

    private List<String> buildGlobalConfigIds(String tenantId) {
        List<String> ids = new ArrayList<>();
        if (tenantId != null && !tenantId.isBlank()) {
            ids.add(GLOBAL_CONFIG_KEY + ":" + tenantId.trim());
        }
        ids.add(GLOBAL_CONFIG_KEY);
        return ids;
    }

    private JsonNode parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record StoredAiConfig(String provider, String model, String apiKey) {}
}
