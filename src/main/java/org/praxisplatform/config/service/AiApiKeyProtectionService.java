package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiApiKeyProtectionService {

    private static final String AI_NODE = "ai";
    private static final String API_KEY = "apiKey";
    private static final String API_KEY_ENCRYPTED = "apiKeyEncrypted";
    private static final String API_KEY_LAST4 = "apiKeyLast4";
    private static final String API_KEY_PRESENT = "hasApiKey";

    private final ObjectMapper objectMapper;
    private final AiApiKeyCryptoService cryptoService;

    public JsonNode sanitizeForStorage(JsonNode payload, JsonNode existingPayload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        ObjectNode root = payload.deepCopy();
        ObjectNode aiNode = extractAiNode(root);
        ObjectNode existingAi = extractAiNode(existingPayload);

        String rawApiKey = aiNode != null ? textOrNull(aiNode.get(API_KEY)) : null;
        boolean apiKeyProvided = rawApiKey != null;
        if (aiNode != null && !apiKeyProvided) {
            aiNode.remove(API_KEY);
            aiNode.remove(API_KEY_ENCRYPTED);
            aiNode.remove(API_KEY_LAST4);
        }
        String existingEncrypted = existingAi != null ? textOrNull(existingAi.get(API_KEY_ENCRYPTED)) : null;
        String existingPlain = existingAi != null ? textOrNull(existingAi.get(API_KEY)) : null;
        String existingLast4 = existingAi != null ? textOrNull(existingAi.get(API_KEY_LAST4)) : null;

        if (apiKeyProvided) {
            aiNode.remove(API_KEY);
            aiNode.remove(API_KEY_ENCRYPTED);
            aiNode.remove(API_KEY_LAST4);
            if (!cryptoService.isEnabled()) {
                throw new IllegalArgumentException("Encryption key not configured for apiKey storage.");
            }
            String encrypted = cryptoService.encrypt(rawApiKey);
            aiNode.put(API_KEY_ENCRYPTED, encrypted);
            aiNode.put(API_KEY_LAST4, last4(rawApiKey));
            return root;
        }

        // No apiKey provided: keep existing secret if present
        String migratedEncrypted = existingEncrypted;
        String migratedLast4 = existingLast4;
        if (migratedEncrypted == null && existingPlain != null && cryptoService.isEnabled()) {
            try {
                migratedEncrypted = cryptoService.encrypt(existingPlain);
                migratedLast4 = last4(existingPlain);
            } catch (Exception ex) {
                log.warn("[AiApiKeyProtection] Failed to migrate plaintext apiKey", ex);
            }
        }

        if (migratedEncrypted == null && existingPlain != null && !cryptoService.isEnabled()) {
            // Preserve legacy plaintext when encryption is unavailable.
            if (aiNode == null) {
                aiNode = objectMapper.createObjectNode();
                root.set(AI_NODE, aiNode);
            }
            aiNode.put(API_KEY, existingPlain);
            if (migratedLast4 != null) {
                aiNode.put(API_KEY_LAST4, migratedLast4);
            }
            return root;
        }

        if (migratedEncrypted != null) {
            if (aiNode == null) {
                aiNode = objectMapper.createObjectNode();
                root.set(AI_NODE, aiNode);
            }
            aiNode.put(API_KEY_ENCRYPTED, migratedEncrypted);
            if (migratedLast4 != null) {
                aiNode.put(API_KEY_LAST4, migratedLast4);
            }
        } else if (aiNode != null) {
            aiNode.remove(API_KEY);
            aiNode.remove(API_KEY_ENCRYPTED);
            aiNode.remove(API_KEY_LAST4);
        }

        return root;
    }

    public JsonNode sanitizeForResponse(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        ObjectNode root = payload.deepCopy();
        ObjectNode aiNode = extractAiNode(root);
        if (aiNode == null) {
            return root;
        }
        boolean hasEncrypted = aiNode.has(API_KEY_ENCRYPTED);
        boolean hasPlain = aiNode.has(API_KEY);
        boolean hasLast4 = aiNode.has(API_KEY_LAST4);
        aiNode.remove(API_KEY);
        aiNode.remove(API_KEY_ENCRYPTED);
        if ((hasEncrypted || hasPlain || hasLast4) && !aiNode.has(API_KEY_PRESENT)) {
            aiNode.put(API_KEY_PRESENT, true);
        }
        return root;
    }

    private ObjectNode extractAiNode(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        JsonNode aiNode = payload.get(AI_NODE);
        if (aiNode != null && aiNode.isObject()) {
            return (ObjectNode) aiNode;
        }
        return null;
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

    private String last4(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - 4);
    }
}
