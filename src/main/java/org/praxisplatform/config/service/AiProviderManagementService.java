package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.AiProviderCatalogItem;
import org.praxisplatform.config.dto.AiProviderCatalogResponse;
import org.praxisplatform.config.dto.AiProviderModel;
import org.praxisplatform.config.dto.AiProviderModelsRequest;
import org.praxisplatform.config.dto.AiProviderModelsResponse;
import org.praxisplatform.config.dto.AiProviderTestRequest;
import org.praxisplatform.config.dto.AiProviderTestResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiProviderManagementService {

    private static final String TEST_PROMPT = "ping";
    private static final String GLOBAL_CONFIG_COMPONENT_TYPE = "praxis-global-config-editor";
    private static final String GLOBAL_CONFIG_KEY = "praxis:global-config";

    @Value("${praxis.ai.provider:gemini}")
    private String defaultProvider;

    private final ObjectMapper objectMapper;
    private final UserConfigService userConfigService;
    private final AiApiKeyCryptoService apiKeyCryptoService;
    private final AiProviderRouter providerRouter;
    private final GeminiAiService gemini;
    private final OpenAiService openai;
    private final XaiAiService xai;
    private final MockAiService mock;

    public AiProviderModelsResponse listModels(AiProviderModelsRequest request) {
        return listModels(request, null, null, null);
    }

    public AiProviderModelsResponse listModels(
            AiProviderModelsRequest request,
            String tenantId,
            String userId,
            String environment) {
        StoredAiConfig stored = resolveStoredConfig(tenantId, userId, environment);
        String provider = resolveProviderName(
                request != null ? request.getProvider() : null,
                stored != null ? stored.provider() : null);
        AiCallConfig config = AiCallConfig.builder()
                .provider(provider)
                .apiKey(resolveApiKey(request != null ? request.getApiKey() : null, stored))
                .build();
        try {
            List<AiProviderModel> models = listModelsByProvider(provider, config);
            return AiProviderModelsResponse.builder()
                    .provider(provider)
                    .success(true)
                    .models(models)
                    .build();
        } catch (Exception e) {
            log.warn("[AiProviderManagement] Model listing failed (provider={})", provider, e);
            return AiProviderModelsResponse.builder()
                    .provider(provider)
                    .success(false)
                    .message(safeErrorMessage(e))
                    .models(List.of())
                    .build();
        }
    }

    public AiProviderTestResponse testConnection(AiProviderTestRequest request) {
        return testConnection(request, null, null, null);
    }

    public AiProviderTestResponse testConnection(
            AiProviderTestRequest request,
            String tenantId,
            String userId,
            String environment) {
        StoredAiConfig stored = resolveStoredConfig(tenantId, userId, environment);
        String provider = resolveProviderName(
                request != null ? request.getProvider() : null,
                stored != null ? stored.provider() : null);
        AiCallConfig config = AiCallConfig.builder()
                .provider(provider)
                .apiKey(resolveApiKey(request != null ? request.getApiKey() : null, stored))
                .model(resolveModel(request != null ? request.getModel() : null, stored))
                .temperature(0.0)
                .maxTokens(8)
                .build();
        String model = config.getModel();
        try {
            providerRouter.generateText(TEST_PROMPT, config);
            return AiProviderTestResponse.builder()
                    .provider(provider)
                    .model(model)
                    .success(true)
                    .message("Connection OK")
                    .build();
        } catch (Exception e) {
            log.warn("[AiProviderManagement] Connection test failed (provider={})", provider, e);
            return AiProviderTestResponse.builder()
                    .provider(provider)
                    .model(model)
                    .success(false)
                    .message(safeErrorMessage(e))
                    .build();
        }
    }

    public AiProviderCatalogResponse listCatalog() {
        List<AiProviderCatalogItem> providers = new ArrayList<>();
        providers.add(AiProviderCatalogItem.builder()
                .id("gemini")
                .label("Google Gemini")
                .description("Modelos rápidos e multimodais do Google.")
                .defaultModel("gemini-2.0-flash")
                .requiresApiKey(true)
                .supportsModels(true)
                .supportsEmbeddings(true)
                .iconKey("gemini")
                .build());
        providers.add(AiProviderCatalogItem.builder()
                .id("openai")
                .label("OpenAI")
                .description("Modelos GPT para texto e chat.")
                .defaultModel("gpt-4o-mini")
                .requiresApiKey(true)
                .supportsModels(true)
                .supportsEmbeddings(false)
                .iconKey("openai")
                .build());
        providers.add(AiProviderCatalogItem.builder()
                .id("xai")
                .label("xAI (Grok)")
                .description("Modelos Grok focados em raciocínio.")
                .defaultModel("grok-2-latest")
                .requiresApiKey(true)
                .supportsModels(true)
                .supportsEmbeddings(false)
                .iconKey("xai")
                .build());
        providers.add(AiProviderCatalogItem.builder()
                .id("mock")
                .label("Mock (dev)")
                .description("Modo local para testes sem chave.")
                .defaultModel("mock-default")
                .requiresApiKey(false)
                .supportsModels(true)
                .supportsEmbeddings(true)
                .iconKey("mock")
                .build());

        return AiProviderCatalogResponse.builder()
                .providers(providers)
                .build();
    }

    private List<AiProviderModel> listModelsByProvider(String provider, AiCallConfig config) {
        String normalized = normalizeProvider(provider);
        if ("openai".equals(normalized) || "open-ai".equals(normalized)) {
            return openai.listModels(config);
        }
        if ("xai".equals(normalized) || "grok".equals(normalized) || "grok-ai".equals(normalized)) {
            return xai.listModels(config);
        }
        if ("mock".equals(normalized)) {
            return mock.listModels(config);
        }
        return gemini.listModels(config);
    }

    private String resolveProviderName(String requested, String stored) {
        String normalized = normalizeProvider(requested);
        if (normalized != null) {
            return normalized;
        }
        normalized = normalizeProvider(stored);
        if (normalized != null) {
            return normalized;
        }
        String fallback = normalizeProvider(defaultProvider);
        return fallback != null ? fallback : "gemini";
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

    private String resolveApiKey(String requested, StoredAiConfig stored) {
        String resolved = trimToNull(requested);
        if (resolved != null) {
            return resolved;
        }
        return stored != null ? trimToNull(stored.apiKey()) : null;
    }

    private String resolveModel(String requestedModel, StoredAiConfig stored) {
        String resolvedModel = trimToNull(requestedModel);
        if (resolvedModel != null) {
            return resolvedModel;
        }
        return stored != null ? trimToNull(stored.model()) : null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private String safeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Connection failed.";
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }

    private record StoredAiConfig(String provider, String model, String apiKey) {}
}
