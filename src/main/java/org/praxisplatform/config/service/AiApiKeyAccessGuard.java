package org.praxisplatform.config.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AiApiKeyAccessGuard {

    @Value("${praxis.ai.keys.admin-token:#{null}}")
    private String adminToken;

    @Value("${praxis.ai.keys.require-admin-token:true}")
    private boolean requireToken;

    public GuardResult authorize(String adminHeader, String authorizationHeader) {
        if (!requireToken) {
            return GuardResult.allow();
        }
        String configured = trimToNull(adminToken);
        if (configured == null) {
            log.warn("[AiApiKeyAccessGuard] Admin token required but not configured.");
            return GuardResult.deny("Admin token not configured.");
        }
        String candidate = resolveToken(adminHeader, authorizationHeader);
        if (candidate == null) {
            return GuardResult.deny("Missing admin token.");
        }
        if (!configured.equals(candidate)) {
            return GuardResult.deny("Invalid admin token.");
        }
        return GuardResult.allow();
    }

    private String resolveToken(String adminHeader, String authorizationHeader) {
        String direct = trimToNull(adminHeader);
        if (direct != null) {
            return direct;
        }
        String auth = trimToNull(authorizationHeader);
        if (auth == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (auth.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return trimToNull(auth.substring(prefix.length()));
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record GuardResult(boolean allowed, String message) {
        public static GuardResult allow() {
            return new GuardResult(true, null);
        }

        public static GuardResult deny(String message) {
            return new GuardResult(false, message);
        }
    }
}
