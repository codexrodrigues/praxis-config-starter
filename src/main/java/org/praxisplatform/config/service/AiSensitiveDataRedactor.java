package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Remove ou mascara dados sensiveis de textos e payloads JSON antes de logging, auditoria ou
 * persistencia de eventos.
 *
 * <p>O redactor aplica regras para secrets, tokens, credenciais e campos livres do fluxo AI,
 * reduzindo o risco de vazamento acidental em trilhas operacionais e historicos de stream.
 */
@Component
public class AiSensitiveDataRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile(
            "\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "((?:api[-_ ]?key|token|secret|senha|password)\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "([?&](?:api[-_]?key|token|secret|password|senha)=)[^&\\s]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9]{20,}\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{12,}\\b");
    private static final Set<String> EVENT_FORBIDDEN_FIELDS = Set.of(
            "currentstate",
            "messages",
            "dataprofile",
            "schemafields",
            "runtimestate",
            "suggestedpatch",
            "contexthints",
            "summary");
    private static final Set<String> EVENT_FREE_TEXT_FIELDS = Set.of(
            "message",
            "explanation",
            "reason",
            "details",
            "error",
            "effectiveprompt",
            "raw",
            "prompt",
            "sourceprompt",
            "userprompt",
            "content");
    private static final Set<String> QUICK_REPLY_CONTEXT_HINT_FIELDS = Set.of(
            "artifactkind",
            "operation",
            "resourcepath",
            "retrievalquery",
            "schemaurl",
            "submitmethod",
            "submiturl",
            "tool");

    public String redactText(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String redacted = value;
        redacted = EMAIL.matcher(redacted).replaceAll(REDACTED);
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = QUERY_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        redacted = OPENAI_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = LONG_NUMBER.matcher(redacted).replaceAll(REDACTED);
        return redacted;
    }

    public JsonNode redactJson(JsonNode source) {
        if (source == null || source.isNull()) {
            return source;
        }
        if (source.isTextual()) {
            return JsonNodeFactory.instance.textNode(redactText(source.asText()));
        }
        if (source.isArray()) {
            ArrayNode redacted = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : source) {
                redacted.add(redactJson(child));
            }
            return redacted;
        }
        if (source.isObject()) {
            ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitiveField(field.getKey())) {
                    objectNode.put(field.getKey(), REDACTED);
                    continue;
                }
                objectNode.set(field.getKey(), redactJson(field.getValue()));
            }
            return objectNode;
        }
        return source;
    }

    public JsonNode sanitizeEventPayload(JsonNode source) {
        return sanitizeEventPayload(source, null);
    }

    private JsonNode sanitizeEventPayload(JsonNode source, String fieldName) {
        if (source == null || source.isNull()) {
            return source;
        }
        if (source.isObject()) {
            ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalizedKey = normalizeFieldName(field.getKey());
                if (EVENT_FORBIDDEN_FIELDS.contains(normalizedKey)) {
                    if ("contexthints".equals(normalizedKey) && isQuickReplyContainer(fieldName)) {
                        objectNode.set(field.getKey(), sanitizeQuickReplyContextHints(field.getValue()));
                        continue;
                    }
                    objectNode.put(field.getKey(), REDACTED);
                    continue;
                }
                if (isSecretField(normalizedKey)) {
                    objectNode.put(field.getKey(), REDACTED);
                    continue;
                }
                objectNode.set(field.getKey(), sanitizeEventPayload(field.getValue(), field.getKey()));
            }
            return objectNode;
        }
        if (source.isArray()) {
            ArrayNode redacted = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : source) {
                redacted.add(sanitizeEventPayload(child, fieldName));
            }
            return redacted;
        }
        if (source.isTextual()) {
            String normalizedField = normalizeFieldName(fieldName);
            if (isSecretField(normalizedField)) {
                return JsonNodeFactory.instance.textNode(REDACTED);
            }
            if (isEventFreeTextField(normalizedField)) {
                return JsonNodeFactory.instance.textNode(redactText(source.asText()));
            }
        }
        return source;
    }

    private boolean isQuickReplyContainer(String fieldName) {
        String normalized = normalizeFieldName(fieldName);
        return "quickreplies".equals(normalized) || "quickreply".equals(normalized);
    }

    private JsonNode sanitizeQuickReplyContextHints(JsonNode source) {
        if (source == null || source.isNull()) {
            return source;
        }
        if (!source.isObject()) {
            return JsonNodeFactory.instance.textNode(REDACTED);
        }
        ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String normalizedKey = normalizeFieldName(field.getKey());
            if (!QUICK_REPLY_CONTEXT_HINT_FIELDS.contains(normalizedKey) || isSecretField(normalizedKey)) {
                objectNode.put(field.getKey(), REDACTED);
                continue;
            }
            objectNode.set(field.getKey(), sanitizeEventPayload(field.getValue(), field.getKey()));
        }
        return objectNode;
    }

    private String normalizeFieldName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return "";
        }
        return fieldName.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private boolean isSecretField(String normalizedField) {
        if (normalizedField == null || normalizedField.isBlank()) {
            return false;
        }
        return normalizedField.contains("password")
                || normalizedField.contains("secret")
                || normalizedField.contains("token")
                || normalizedField.contains("apikey")
                || normalizedField.contains("authorization")
                || normalizedField.contains("senha");
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String key = fieldName.toLowerCase();
        return key.contains("password")
                || key.contains("secret")
                || key.contains("token")
                || key.contains("apikey")
                || key.contains("api_key")
                || key.contains("authorization")
                || key.contains("prompt")
                || key.contains("currentstate");
    }

    private boolean isEventFreeTextField(String normalizedField) {
        if (normalizedField == null || normalizedField.isBlank()) {
            return false;
        }
        return EVENT_FREE_TEXT_FIELDS.contains(normalizedField);
    }
}
