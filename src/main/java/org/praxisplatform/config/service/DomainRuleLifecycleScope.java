package org.praxisplatform.config.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shared, domain-separated transaction lock identity for governed publication writes. */
public final class DomainRuleLifecycleScope {
    private DomainRuleLifecycleScope() {}

    public static long lockKey(DomainRuleGovernancePrincipal principal) {
        if (principal == null) throw new IllegalArgumentException("A server-resolved principal is required");
        String tenant = required(principal.tenantId(), "tenantId");
        String environment = required(principal.environment(), "environment");
        required(principal.actorRef(), "actorRef");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("praxis.config.rule-lifecycle/1".getBytes(StandardCharsets.UTF_8));
            for (String part : new String[] {tenant, environment}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            // A collision only adds serialization between scopes; it cannot weaken exclusion.
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
