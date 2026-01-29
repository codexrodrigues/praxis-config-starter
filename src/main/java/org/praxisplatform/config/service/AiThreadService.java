package org.praxisplatform.config.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.domain.AiThreadStatus;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiUiContextRef;
import org.praxisplatform.config.repository.AiThreadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiThreadService {

    private static final Map<String, String> COMPONENT_TYPE_ALIASES = buildAliases();

    private final AiThreadRepository threadRepository;

    public AiThread resolveThread(
            AiOrchestratorRequest request,
            String tenantId,
            String userId,
            String environment,
            String resolvedUserPrompt) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request is required");
        }
        String normalizedComponentType = normalizeComponentType(request.getComponentType());
        request.setComponentType(normalizedComponentType);

        AiUiContextRef uiContextRef = request.getUiContextRef();
        String routeKey = normalizeRouteKey(uiContextRef != null ? uiContextRef.getRouteKey() : null);

        boolean createNew = shouldCreateNewThread(request);
        if (createNew) {
            AiThread created = AiThread.builder()
                    .threadId(UUID.randomUUID())
                    .tenantId(tenantId != null ? tenantId : "default")
                    .environment(environment)
                    .userId(userId)
                    .componentType(normalizedComponentType)
                    .componentId(request.getComponentId())
                    .routeKey(routeKey)
                    .title(buildTitle(resolvedUserPrompt))
                    .status(AiThreadStatus.ACTIVE)
                    .summary("")
                    .schemaHash(uiContextRef != null ? uiContextRef.getSchemaHash() : null)
                    .variantId(request.getVariantId() != null ? request.getVariantId()
                            : uiContextRef != null ? uiContextRef.getVariantId() : null)
                    .build();
            AiThread saved = threadRepository.save(created);
            request.setSessionId(saved.getThreadId());
            return saved;
        }

        UUID sessionId = request.getSessionId();
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session id is required");
        }
        Optional<AiThread> loaded = threadRepository.findById(sessionId);
        AiThread thread = loaded.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Thread not found"));
        if (!isAuthorized(thread, tenantId, environment, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Thread access denied");
        }
        thread.setLastUsedAt(java.time.Instant.now());
        AiThread saved = threadRepository.save(thread);
        request.setSessionId(saved.getThreadId());
        return saved;
    }

    private boolean shouldCreateNewThread(AiOrchestratorRequest request) {
        if (request.getSessionId() == null) {
            return true;
        }
        String mode = request.getMode();
        return mode != null && "new".equalsIgnoreCase(mode.trim());
    }

    private boolean isAuthorized(AiThread thread, String tenantId, String environment, String userId) {
        if (thread == null) {
            return false;
        }
        if (tenantId == null && thread.getTenantId() != null
                && !"default".equals(thread.getTenantId())) {
            return false;
        }
        if (tenantId != null && thread.getTenantId() != null
                && !tenantId.equals(thread.getTenantId())) {
            return false;
        }
        if (environment == null && thread.getEnvironment() != null) {
            return false;
        }
        if (environment != null && thread.getEnvironment() != null
                && !environment.equals(thread.getEnvironment())) {
            return false;
        }
        if (thread.getUserId() == null) {
            return true;
        }
        return userId != null && userId.equals(thread.getUserId());
    }

    private String buildTitle(String prompt) {
        String cleaned = prompt != null ? prompt.replace("\n", " ").trim() : "";
        if (cleaned.isEmpty()) {
            return "Nova sessao";
        }
        if (cleaned.length() <= 60) {
            return cleaned;
        }
        return cleaned.substring(0, 60);
    }

    private String normalizeComponentType(String componentType) {
        if (componentType == null) {
            return null;
        }
        String key = componentType.trim().toLowerCase(Locale.ROOT);
        return COMPONENT_TYPE_ALIASES.getOrDefault(key, componentType);
    }

    private String normalizeRouteKey(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return null;
        }
        String base = routeKey;
        int queryIdx = base.indexOf('?');
        if (queryIdx >= 0) {
            base = base.substring(0, queryIdx);
        }
        int fragmentIdx = base.indexOf('#');
        if (fragmentIdx >= 0) {
            base = base.substring(0, fragmentIdx);
        }
        String[] segments = base.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            normalized.append('/');
            normalized.append(isLikelyId(segment) ? ":id" : segment);
        }
        String normalizedValue = normalized.length() == 0 ? "/" : normalized.toString();
        return normalizedValue.endsWith("/") && normalizedValue.length() > 1
                ? normalizedValue.substring(0, normalizedValue.length() - 1)
                : normalizedValue;
    }

    private boolean isLikelyId(String segment) {
        if (segment == null) return false;
        if (segment.matches("\\d+")) return true;
        if (segment.matches("[0-9a-fA-F\\-]{32,36}")) return true;
        if (segment.length() > 12 && segment.matches(".*\\d.*")) return true;
        return false;
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("table", "praxis-table");
        aliases.put("praxis-table", "praxis-table");
        aliases.put("form", "praxis-dynamic-form");
        aliases.put("praxis-dynamic-form", "praxis-dynamic-form");
        return aliases;
    }
}
