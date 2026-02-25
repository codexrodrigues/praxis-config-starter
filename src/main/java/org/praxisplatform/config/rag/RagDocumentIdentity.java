package org.praxisplatform.config.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class RagDocumentIdentity {

    private static final String DEFAULT_SCOPE = "global";
    private static final String DEFAULT_RELEASE = "v1";

    private RagDocumentIdentity() {
    }

    public static String resolveReleaseId(String releaseIdHint, String versionHint, String generatedAtHint) {
        String releaseId = firstNonBlank(releaseIdHint, versionHint, generatedAtHint, DEFAULT_RELEASE);
        return normalizeToken(releaseId, DEFAULT_RELEASE);
    }

    public static String buildDocumentId(
            String tenantId,
            String environment,
            String componentId,
            String releaseId,
            String docType,
            String contentHash,
            int chunkIndex) {
        String scopeTenant = normalizeToken(tenantId, DEFAULT_SCOPE);
        String scopeEnvironment = normalizeToken(environment, DEFAULT_SCOPE);
        String scopeComponent = normalizeToken(componentId, "unknown-component");
        String scopeRelease = normalizeToken(releaseId, DEFAULT_RELEASE);
        String scopeDocType = normalizeToken(docType, "unknown-doc");
        String scopeHash = normalizeToken(contentHash, "nohash");
        int normalizedChunk = Math.max(0, chunkIndex);
        return String.join(
                "/",
                scopeTenant,
                scopeEnvironment,
                scopeComponent,
                scopeRelease,
                scopeDocType,
                scopeHash,
                Integer.toString(normalizedChunk));
    }

    public static String buildDocumentId(
            String tenantId,
            String componentId,
            String releaseId,
            String docType,
            String contentHash,
            int chunkIndex) {
        return buildDocumentId(tenantId, null, componentId, releaseId, docType, contentHash, chunkIndex);
    }

    public static String normalizeToken(String rawValue, String fallback) {
        String normalized = normalize(rawValue);
        if (normalized == null) {
            normalized = normalize(fallback);
        }
        if (normalized == null) {
            return "unknown";
        }
        String safe = normalized.toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    public static String sha256(String rawValue) {
        String normalized = rawValue != null ? rawValue : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute SHA-256 hash.", ex);
        }
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }
}
