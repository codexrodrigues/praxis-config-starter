package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.dto.AiApiKeyClearRequest;
import org.praxisplatform.config.dto.AiApiKeyMaintenanceResponse;
import org.praxisplatform.config.dto.AiApiKeyRotateRequest;
import org.praxisplatform.config.repository.UiUserConfigRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiApiKeyMaintenanceService {

    private static final String AI_NODE = "ai";
    private static final String API_KEY = "apiKey";
    private static final String API_KEY_ENCRYPTED = "apiKeyEncrypted";
    private static final String API_KEY_LAST4 = "apiKeyLast4";
    private static final String API_KEY_PRESENT = "hasApiKey";

    private final UiUserConfigRepository repository;
    private final UserConfigService userConfigService;
    private final ObjectMapper objectMapper;
    private final AiApiKeyCryptoService cryptoService;

    public AiApiKeyMaintenanceResponse clearApiKey(
            AiApiKeyClearRequest request,
            String tenantId,
            String userId,
            String updatedBy) {
        ScopeContext scope = resolveScope(request != null ? request.getScope() : null, userId);
        String componentType = trimToNull(request != null ? request.getComponentType() : null);
        String componentId = trimToNull(request != null ? request.getComponentId() : null);
        String environment = trimToNull(request != null ? request.getEnvironment() : null);

        if (componentType == null || componentId == null) {
            return response(false, "invalid_request", "componentType/componentId required", componentType, componentId, scope, environment);
        }
        if (scope.scope() == UserConfigService.Scope.USER && (scope.userId() == null || scope.userId().isBlank())) {
            return response(false, "invalid_request", "User scope requires X-User-ID header", componentType, componentId, scope, environment);
        }

        Optional<UserConfigService.ResolvedConfig> resolved =
                userConfigService.getByScope(scope.scope(), tenantId, scope.userId(), componentType, componentId, environment);
        if (resolved.isEmpty()) {
            return response(false, "not_found", "Configuration not found", componentType, componentId, scope, environment);
        }

        UiUserConfig cfg = resolved.get().config();
        ObjectNode payload = parsePayload(cfg.getPayload());
        ObjectNode aiNode = extractAiNode(payload);
        if (aiNode == null) {
            return response(true, "no_key", "No apiKey to clear", componentType, componentId, scope, environment);
        }
        aiNode.remove(API_KEY);
        aiNode.remove(API_KEY_ENCRYPTED);
        aiNode.remove(API_KEY_LAST4);
        aiNode.remove(API_KEY_PRESENT);
        if (aiNode.isEmpty()) {
            payload.remove(AI_NODE);
        }
        savePayload(cfg, payload, updatedBy);
        return response(true, "cleared", "apiKey cleared", componentType, componentId, scope, environment);
    }

    public AiApiKeyMaintenanceResponse rotateApiKey(
            AiApiKeyRotateRequest request,
            String tenantId,
            String userId,
            String updatedBy) {
        ScopeContext scope = resolveScope(request != null ? request.getScope() : null, userId);
        String componentType = trimToNull(request != null ? request.getComponentType() : null);
        String componentId = trimToNull(request != null ? request.getComponentId() : null);
        String environment = trimToNull(request != null ? request.getEnvironment() : null);

        if (componentType == null || componentId == null) {
            return response(false, "invalid_request", "componentType/componentId required", componentType, componentId, scope, environment);
        }
        if (scope.scope() == UserConfigService.Scope.USER && (scope.userId() == null || scope.userId().isBlank())) {
            return response(false, "invalid_request", "User scope requires X-User-ID header", componentType, componentId, scope, environment);
        }

        Optional<UserConfigService.ResolvedConfig> resolved =
                userConfigService.getByScope(scope.scope(), tenantId, scope.userId(), componentType, componentId, environment);
        if (resolved.isEmpty()) {
            return response(false, "not_found", "Configuration not found", componentType, componentId, scope, environment);
        }

        UiUserConfig cfg = resolved.get().config();
        ObjectNode payload = parsePayload(cfg.getPayload());
        ObjectNode aiNode = extractAiNode(payload);
        if (aiNode == null) {
            return response(false, "no_key", "No apiKey to rotate", componentType, componentId, scope, environment);
        }

        String encrypted = textOrNull(aiNode.get(API_KEY_ENCRYPTED));
        String plaintext = textOrNull(aiNode.get(API_KEY));
        if (plaintext == null && encrypted != null) {
            String prevKey = trimToNull(request != null ? request.getPreviousEncryptionKey() : null);
            plaintext = prevKey != null
                    ? cryptoService.decryptWithKey(encrypted, prevKey)
                    : cryptoService.decrypt(encrypted);
        }
        if (plaintext == null || plaintext.isBlank()) {
            return response(false, "no_key", "Unable to resolve apiKey for rotation", componentType, componentId, scope, environment);
        }

        String newKey = trimToNull(request != null ? request.getNewEncryptionKey() : null);
        String encryptedNew = newKey != null
                ? cryptoService.encryptWithKey(plaintext, newKey)
                : cryptoService.encrypt(plaintext);

        aiNode.put(API_KEY_ENCRYPTED, encryptedNew);
        aiNode.put(API_KEY_LAST4, last4(plaintext));
        aiNode.remove(API_KEY);
        aiNode.remove(API_KEY_PRESENT);
        savePayload(cfg, payload, updatedBy);
        return response(true, "rotated", "apiKey rotated", componentType, componentId, scope, environment)
                .toBuilder()
                .hasApiKey(true)
                .apiKeyLast4(last4(plaintext))
                .build();
    }

    private void savePayload(UiUserConfig cfg, ObjectNode payload, String updatedBy) {
        try {
            cfg.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to serialize JSON", e);
        }
        cfg.setVersion(cfg.getVersion() + 1);
        cfg.setEtag(UUID.randomUUID());
        cfg.setUpdatedBy(updatedBy);
        repository.save(cfg);
    }

    private ObjectNode parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
        } catch (Exception ex) {
            log.warn("[AiApiKeyMaintenance] Failed to parse payload", ex);
        }
        return objectMapper.createObjectNode();
    }

    private ObjectNode extractAiNode(ObjectNode payload) {
        if (payload == null) return null;
        JsonNode aiNode = payload.get(AI_NODE);
        if (aiNode != null && aiNode.isObject()) {
            return (ObjectNode) aiNode;
        }
        return null;
    }

    private ScopeContext resolveScope(String scopeParam, String userId) {
        if (scopeParam == null || scopeParam.isBlank()) {
            UserConfigService.Scope scope =
                    (userId != null && !userId.isBlank())
                            ? UserConfigService.Scope.USER
                            : UserConfigService.Scope.TENANT;
            return new ScopeContext(scope, scope == UserConfigService.Scope.USER ? userId : null);
        }
        String normalized = scopeParam.trim().toLowerCase();
        return switch (normalized) {
            case "user" -> new ScopeContext(UserConfigService.Scope.USER, userId);
            case "tenant" -> new ScopeContext(UserConfigService.Scope.TENANT, null);
            default -> throw new IllegalArgumentException("Invalid scope. Use user or tenant.");
        };
    }

    private AiApiKeyMaintenanceResponse response(
            boolean success,
            String status,
            String message,
            String componentType,
            String componentId,
            ScopeContext scope,
            String environment) {
        return AiApiKeyMaintenanceResponse.builder()
                .success(success)
                .status(status)
                .message(message)
                .componentType(componentType)
                .componentId(componentId)
                .scope(scope.scope().name().toLowerCase())
                .environment(environment)
                .hasApiKey(Boolean.FALSE)
                .build();
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return (text == null || text.isBlank()) ? null : text;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String last4(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() <= 4) return trimmed;
        return trimmed.substring(trimmed.length() - 4);
    }

    private record ScopeContext(UserConfigService.Scope scope, String userId) {}
}
