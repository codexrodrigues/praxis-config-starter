package org.praxisplatform.config.service;

import java.util.Set;

/**
 * Host-validated audience facts used only inside layout resolution. This value must never be
 * serialized into an HTTP response or log message.
 */
public record EffectiveUiAudience(
        String tenant,
        String environment,
        String user,
        String organization,
        String sector,
        String profile,
        Set<String> groups,
        String contextVersion) {

    public EffectiveUiAudience {
        tenant = required(tenant, "tenant", 255);
        environment = optional(environment, 64);
        user = required(user, "user", 255);
        organization = optional(organization, 255);
        sector = optional(sector, 255);
        profile = optional(profile, 255);
        contextVersion = required(contextVersion, "contextVersion", 255);
        groups = groups == null ? Set.of() : groups.stream()
                .map(value -> required(value, "group", 255))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (sector != null && organization == null) {
            throw new IllegalArgumentException("sector requires organization.");
        }
    }

    @Override
    public String toString() {
        return "EffectiveUiAudience[redacted]";
    }

    private static String required(String value, String name, int maximumLength) {
        String normalized = optional(value, maximumLength);
        if (normalized == null) throw new IllegalArgumentException(name + " is required.");
        return normalized;
    }

    private static String optional(String value, int maximumLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Audience identity must contain at most " + maximumLength + " characters.");
        }
        return normalized;
    }
}
