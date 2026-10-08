package org.praxisplatform.config.service;

public record UiLayoutAudienceSelector(
        String tenant, String organization, String sector, String group, String profile, String user) {

    public UiLayoutAudienceSelector {
        tenant = required(tenant, "selector tenant");
        organization = optional(organization);
        sector = optional(sector);
        group = optional(group);
        profile = optional(profile);
        user = optional(user);
        if (sector != null && organization == null) {
            throw new IllegalArgumentException("A sector selector requires organization.");
        }
    }

    boolean matches(EffectiveUiAudience audience) {
        return tenant.equals(audience.tenant())
                && matches(organization, audience.organization())
                && matches(sector, audience.sector())
                && (group == null || audience.groups().contains(group))
                && matches(profile, audience.profile())
                && matches(user, audience.user());
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private static String required(String value, String name) {
        String normalized = optional(value);
        if (normalized == null) throw new IllegalArgumentException(name + " is required.");
        return normalized;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
