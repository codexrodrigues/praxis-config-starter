package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.praxisplatform.config.domain.UiUserConfig;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.UserConfigService;

public class AgenticAuthoringApplyService {

    private static final String DEFAULT_COMPONENT_TYPE = "praxis-dynamic-page";

    private final UserConfigService userConfigService;
    private final AiApiKeyProtectionService apiKeyProtectionService;
    private final ObjectMapper objectMapper;

    public AgenticAuthoringApplyService(
            UserConfigService userConfigService,
            AiApiKeyProtectionService apiKeyProtectionService,
            ObjectMapper objectMapper) {
        this.userConfigService = userConfigService;
        this.apiKeyProtectionService = apiKeyProtectionService;
        this.objectMapper = objectMapper;
    }

    public AgenticAuthoringApplyResult apply(
            AgenticAuthoringApplyRequest request,
            String tenantId,
            String userId,
            String environment,
            String updatedBy,
            String ifMatch) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("X-Tenant-ID is required");
        }

        String componentType = defaultIfBlank(request.componentType(), DEFAULT_COMPONENT_TYPE);
        String componentId = requireText(request.componentId(), "componentId is required");
        UserConfigService.Scope scope = resolveScope(request.scope(), userId);
        JsonNode payload = extractPagePayload(request.compiledFormPatch());
        JsonNode tags = buildTags(request.compiledFormPatch(), request.tags());

        UiUserConfig saved = userConfigService.upsert(
                scope,
                tenantId,
                userId,
                componentType,
                componentId,
                environment,
                payload,
                tags,
                normalizeConditionHeader(ifMatch),
                updatedBy);

        String etag = saved.getEtag() != null ? saved.getEtag().toString() : null;
        JsonNode savedPayload = apiKeyProtectionService.sanitizeForResponse(readJson(saved.getPayload()));
        JsonNode savedTags = readJson(saved.getTags());

        return new AgenticAuthoringApplyResult(
                true,
                componentType,
                componentId,
                environment,
                scope.name().toLowerCase(),
                saved.getVersion(),
                etag,
                savedPayload,
                savedTags,
                List.of("persisted-page-payload-from-compiled-form-patch"));
    }

    private JsonNode extractPagePayload(JsonNode compiledFormPatch) {
        if (compiledFormPatch == null || !compiledFormPatch.isObject()) {
            throw new IllegalArgumentException("compiledFormPatch is required");
        }
        JsonNode page = compiledFormPatch.path("patch").path("page");
        if (!page.isObject()) {
            throw new IllegalArgumentException("compiledFormPatch.patch.page must be an object");
        }
        JsonNode widgets = page.path("widgets");
        if (!widgets.isArray() || widgets.isEmpty()) {
            throw new IllegalArgumentException("compiledFormPatch.patch.page.widgets must not be empty");
        }
        return page;
    }

    private JsonNode buildTags(JsonNode compiledFormPatch, JsonNode requestTags) {
        ObjectNode tags = requestTags != null && requestTags.isObject()
                ? requestTags.deepCopy()
                : objectMapper.createObjectNode();
        tags.put("source", "agentic-authoring");
        copyText(compiledFormPatch, tags, "profileId");
        copyText(compiledFormPatch, tags, "catalogReleaseId");
        copyText(compiledFormPatch, tags, "builderVersion");
        return tags;
    }

    private void copyText(JsonNode source, ObjectNode target, String fieldName) {
        String value = source != null ? source.path(fieldName).asText(null) : null;
        if (value != null && !value.isBlank()) {
            target.put(fieldName, value);
        }
    }

    private UserConfigService.Scope resolveScope(String scopeParam, String userId) {
        if (scopeParam == null || scopeParam.isBlank()) {
            return userId != null && !userId.isBlank()
                    ? UserConfigService.Scope.USER
                    : UserConfigService.Scope.TENANT;
        }
        return switch (scopeParam.trim().toLowerCase()) {
            case "user" -> UserConfigService.Scope.USER;
            case "tenant" -> UserConfigService.Scope.TENANT;
            default -> throw new IllegalArgumentException("Invalid scope. Use user or tenant.");
        };
    }

    private String normalizeConditionHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String trimmed = headerValue.trim();
        if ("*".equals(trimmed)) {
            return trimmed;
        }
        if (trimmed.regionMatches(true, 0, "W/", 0, 2)) {
            trimmed = trimmed.substring(2).trim();
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }
}
