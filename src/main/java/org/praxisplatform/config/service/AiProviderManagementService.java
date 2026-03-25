package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Serviço de catálogo, descoberta de modelos e teste operacional de provedores AI.
 *
 * <p>
 * Esta camada centraliza resolução de provider, sobreposição de credenciais temporárias e leitura
 * de configuração persistida do host antes de consultar um {@link AiProvider} concreto.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiProviderManagementService {

    private static final String TEST_PROMPT = "ping";
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

    private final ObjectMapper objectMapper;
    private final UserConfigService userConfigService;
    private final AiApiKeyCryptoService apiKeyCryptoService;
    private final List<AiProvider> providers;
    private final Map<String, AiProvider> providerRegistry = new LinkedHashMap<>();

    @PostConstruct
    void initProviderRegistry() {
        for (AiProvider provider : providers) {
            if (provider instanceof AiProviderRouter) {
                continue;
            }
            String name = normalizeProvider(provider.getProviderName());
            if (name == null) {
                continue;
            }
            if (providerRegistry.containsKey(name)) {
                log.warn("[AiProviderManagement] Duplicate AiProvider name '{}', keeping first.", name);
                continue;
            }
            providerRegistry.put(name, provider);
        }
    }

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
            String preferredModel = resolvePreferredModel(provider, stored);
            models = sortModelsByRelevance(models, provider, preferredModel);
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
            AiProvider selectedProvider = resolveProvider(provider);
            if (selectedProvider == null) {
                throw new IllegalStateException("No providers available");
            }
            selectedProvider.generateText(TEST_PROMPT, config);
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
        ProviderCapabilities geminiCapabilities = resolveProviderCapabilities("gemini");
        providers.add(AiProviderCatalogItem.builder()
                .id("gemini")
                .label("Google Gemini")
                .description("Modelos rápidos e multimodais do Google.")
                .defaultModel("gemini-2.0-flash")
                .requiresApiKey(true)
                .supportsModels(true)
                .supportsEmbeddings(true)
                .supportsTextStreaming(geminiCapabilities.supportsTextStreaming())
                .supportsTurnCancellation(geminiCapabilities.supportsTurnCancellation())
                .iconKey("gemini")
                .build());
        ProviderCapabilities openAiCapabilities = resolveProviderCapabilities("openai");
        providers.add(AiProviderCatalogItem.builder()
                .id("openai")
                .label("OpenAI")
                .description("Modelos GPT para texto e chat.")
                .defaultModel("gpt-4o-mini")
                .requiresApiKey(true)
                .supportsModels(true)
                .supportsEmbeddings(true)
                .supportsTextStreaming(openAiCapabilities.supportsTextStreaming())
                .supportsTurnCancellation(openAiCapabilities.supportsTurnCancellation())
                .iconKey("openai")
                .build());
        ProviderCapabilities xaiCapabilities = resolveProviderCapabilities("xai");
        providers.add(AiProviderCatalogItem.builder()
                .id("xai")
                .label("xAI (Grok)")
                .description("Modelos Grok focados em raciocínio.")
                .defaultModel("grok-2-latest")
                .requiresApiKey(true)
                .supportsModels(true)
                .supportsEmbeddings(false)
                .supportsTextStreaming(xaiCapabilities.supportsTextStreaming())
                .supportsTurnCancellation(xaiCapabilities.supportsTurnCancellation())
                .iconKey("xai")
                .build());
        ProviderCapabilities mockCapabilities = resolveProviderCapabilities("mock");
        providers.add(AiProviderCatalogItem.builder()
                .id("mock")
                .label("Mock (dev)")
                .description("Modo local para testes sem chave.")
                .defaultModel("mock-default")
                .requiresApiKey(false)
                .supportsModels(true)
                .supportsEmbeddings(true)
                .supportsTextStreaming(mockCapabilities.supportsTextStreaming())
                .supportsTurnCancellation(mockCapabilities.supportsTurnCancellation())
                .iconKey("mock")
                .build());

        return AiProviderCatalogResponse.builder()
                .providers(providers)
                .build();
    }

    private List<AiProviderModel> listModelsByProvider(String provider, AiCallConfig config) {
        AiProvider selected = resolveProvider(provider);
        if (selected == null) {
            return List.of();
        }
        return selected.listModels(config);
    }

    private AiProvider resolveProvider(String provider) {
        String normalized = normalizeProvider(provider);
        String canonical = normalizeAlias(normalized != null ? normalized : "gemini");
        AiProvider selected = providerRegistry.get(canonical);
        if (selected != null) {
            return selected;
        }
        if (!"gemini".equals(canonical)) {
            log.warn("[AiProviderManagement] Unknown provider '{}', defaulting to gemini.", provider);
        }
        selected = providerRegistry.get("gemini");
        if (selected != null) {
            return selected;
        }
        return providerRegistry.values().stream().findFirst().orElse(null);
    }

    private ProviderCapabilities resolveProviderCapabilities(String providerName) {
        AiProvider selected = findProvider(providerName);
        if (selected == null) {
            return new ProviderCapabilities(false, false);
        }
        AiCallConfig capabilityConfig = AiCallConfig.builder()
                .provider(providerName)
                .build();
        return new ProviderCapabilities(
                safeSupportsTextStreaming(selected, capabilityConfig),
                safeSupportsTurnCancellation(selected, capabilityConfig));
    }

    private boolean safeSupportsTextStreaming(AiProvider provider, AiCallConfig config) {
        try {
            return provider.supportsTextStreaming(config);
        } catch (Exception ex) {
            log.debug("[AiProviderManagement] Failed to resolve text streaming capability: {}", ex.getMessage());
            return false;
        }
    }

    private AiProvider findProvider(String provider) {
        String normalized = normalizeProvider(provider);
        String canonical = normalizeAlias(normalized);
        if (canonical == null) {
            return null;
        }
        return providerRegistry.get(canonical);
    }

    private boolean safeSupportsTurnCancellation(AiProvider provider, AiCallConfig config) {
        try {
            return provider.supportsTurnCancellation(config);
        } catch (Exception ex) {
            log.debug("[AiProviderManagement] Failed to resolve cancellation capability: {}", ex.getMessage());
            return false;
        }
    }

    private List<AiProviderModel> sortModelsByRelevance(
            List<AiProviderModel> models,
            String provider,
            String preferredModel) {
        if (models == null || models.isEmpty()) {
            return models;
        }
        String normalized = normalizeProvider(provider);
        if (preferredModel == null || preferredModel.isBlank()) {
            return models;
        }
        if (!"openai".equals(normalized) && !"open-ai".equals(normalized)) {
            return sortWithPreferred(models, preferredModel);
        }
        return sortOpenAiModels(models, preferredModel);
    }

    private List<AiProviderModel> sortWithPreferred(
            List<AiProviderModel> models,
            String preferredModel) {
        String preferred = preferredModel.trim().toLowerCase();
        List<AiProviderModel> copy = new ArrayList<>(models);
        for (int i = 0; i < copy.size(); i++) {
            copy.get(i).setVersion(String.valueOf(i));
        }
        copy.sort((a, b) -> {
            int rankA = preferredRank(a.getName(), preferred);
            int rankB = preferredRank(b.getName(), preferred);
            if (rankA != rankB) {
                return Integer.compare(rankA, rankB);
            }
            return Integer.compare(originalIndex(a), originalIndex(b));
        });
        clearSortMetadata(copy);
        return copy;
    }

    private List<AiProviderModel> sortOpenAiModels(
            List<AiProviderModel> models,
            String preferredModel) {
        String preferred = preferredModel.trim().toLowerCase();
        List<AiProviderModel> copy = new ArrayList<>(models);
        for (int i = 0; i < copy.size(); i++) {
            copy.get(i).setVersion(String.valueOf(i));
        }
        copy.sort((a, b) -> {
            int prefA = preferredRank(a.getName(), preferred);
            int prefB = preferredRank(b.getName(), preferred);
            if (prefA != prefB) {
                return Integer.compare(prefA, prefB);
            }
            int famA = openAiFamilyRank(a.getName());
            int famB = openAiFamilyRank(b.getName());
            if (famA != famB) {
                return Integer.compare(famA, famB);
            }
            return Integer.compare(originalIndex(a), originalIndex(b));
        });
        clearSortMetadata(copy);
        return copy;
    }

    private int preferredRank(String name, String preferred) {
        if (name == null || name.isBlank()) {
            return 3;
        }
        String normalized = name.trim().toLowerCase();
        if (normalized.equals(preferred)) {
            return 0;
        }
        if (normalized.endsWith("/" + preferred)) {
            return 1;
        }
        return 2;
    }

    private int openAiFamilyRank(String name) {
        if (name == null || name.isBlank()) {
            return 99;
        }
        String normalized = name.trim().toLowerCase();
        String[] order = {
                "gpt-5",
                "o1",
                "o3",
                "gpt-4o",
                "gpt-4.1",
                "gpt-4",
                "gpt-3.5"
        };
        for (int i = 0; i < order.length; i++) {
            if (normalized.startsWith(order[i])) {
                return i;
            }
        }
        return 98;
    }

    private int originalIndex(AiProviderModel model) {
        try {
            return Integer.parseInt(model.getVersion());
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void clearSortMetadata(List<AiProviderModel> models) {
        for (AiProviderModel model : models) {
            model.setVersion(null);
        }
    }

    private String resolvePreferredModel(String provider, StoredAiConfig stored) {
        String storedModel = stored != null ? trimToNull(stored.model()) : null;
        if (storedModel != null) {
            return storedModel;
        }
        String normalized = normalizeProvider(provider);
        if ("openai".equals(normalized) || "open-ai".equals(normalized)) {
            return trimToNull(openaiModel);
        }
        if ("xai".equals(normalized) || "grok".equals(normalized) || "grok-ai".equals(normalized)) {
            return trimToNull(xaiModel);
        }
        if ("mock".equals(normalized)) {
            return "mock-default";
        }
        return trimToNull(geminiModel);
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

    private String normalizeAlias(String normalized) {
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "open-ai" -> "openai";
            case "grok", "grok-ai" -> "xai";
            default -> normalized;
        };
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

    private record ProviderCapabilities(
            boolean supportsTextStreaming,
            boolean supportsTurnCancellation) {}
}
