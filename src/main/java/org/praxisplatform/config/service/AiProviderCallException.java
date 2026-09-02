package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Falha normalizada em chamadas sincronas de providers AI.
 *
 * <p>Evita que fluxos de authoring precisem inferir causa operacional a partir de mensagens
 * opacas do SDK ou do HTTP client.</p>
 */
public final class AiProviderCallException extends RuntimeException {

    private static final int MAX_PROVIDER_REQUEST_ID_LENGTH = 128;
    private static final Pattern SAFE_PROVIDER_REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

    public enum Kind {
        TRANSPORT,
        TIMEOUT,
        RATE_LIMIT,
        QUOTA_EXHAUSTED,
        CAPACITY,
        AUTH,
        CLIENT_ERROR,
        SERVER_ERROR,
        UNKNOWN
    }

    private final String provider;
    private final Kind kind;
    private final Integer statusCode;
    private final Instant retryAfter;
    private final String providerRequestId;

    private AiProviderCallException(
            String provider,
            Kind kind,
            Integer statusCode,
            Instant retryAfter,
            String providerRequestId,
            String message,
            Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.kind = kind != null ? kind : Kind.UNKNOWN;
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
        this.providerRequestId = sanitizeProviderRequestId(providerRequestId);
    }

    public String getProvider() {
        return provider;
    }

    public Kind getKind() {
        return kind;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Instant getRetryAfter() {
        return retryAfter;
    }

    /**
     * Returns the provider-owned request identifier when it is safe for operational correlation.
     * Raw provider headers are deliberately not retained.
     */
    public String getProviderRequestId() {
        return providerRequestId;
    }

    public static AiProviderCallException fromHttpStatus(String provider, int statusCode, String reason) {
        return fromHttpStatus(provider, statusCode, reason, null);
    }

    public static AiProviderCallException fromHttpStatus(
            String provider, int statusCode, String reason, Throwable cause) {
        return fromHttpStatus(provider, statusCode, reason, null, cause);
    }

    public static AiProviderCallException fromHttpStatus(
            String provider,
            int statusCode,
            String reason,
            Instant retryAfter,
            Throwable cause) {
        return fromHttpStatus(provider, statusCode, reason, retryAfter, null, cause, true);
    }

    public static AiProviderCallException fromHttpStatusSanitized(
            String provider,
            int statusCode,
            String classificationHint,
            Instant retryAfter,
            Throwable cause) {
        return fromHttpStatus(provider, statusCode, classificationHint, retryAfter, null, cause, false);
    }

    public static AiProviderCallException fromHttpStatusSanitized(
            String provider,
            int statusCode,
            String classificationHint,
            Instant retryAfter,
            String providerRequestId,
            Throwable cause) {
        return fromHttpStatus(
                provider,
                statusCode,
                classificationHint,
                retryAfter,
                providerRequestId,
                cause,
                false);
    }

    private static AiProviderCallException fromHttpStatus(
            String provider,
            int statusCode,
            String reason,
            Instant retryAfter,
            String providerRequestId,
            Throwable cause,
            boolean includeReason) {
        Kind kind;
        if (statusCode == 408 || statusCode == 504) {
            kind = Kind.TIMEOUT;
        } else if (statusCode == 429) {
            kind = isQuotaExhausted(reason) ? Kind.QUOTA_EXHAUSTED : Kind.RATE_LIMIT;
        } else if (statusCode == 503) {
            kind = Kind.CAPACITY;
        } else if (statusCode == 401 || statusCode == 403) {
            kind = Kind.AUTH;
        } else if (statusCode >= 400 && statusCode < 500) {
            kind = Kind.CLIENT_ERROR;
        } else if (statusCode >= 500) {
            kind = Kind.SERVER_ERROR;
        } else {
            kind = Kind.UNKNOWN;
        }
        String message = provider + " HTTP " + statusCode + " (" + kind.name().toLowerCase(Locale.ROOT) + ")";
        if (includeReason && reason != null && !reason.isBlank()) {
            message += ": " + reason;
        }
        return new AiProviderCallException(
                provider, kind, statusCode, retryAfter, providerRequestId, message, cause);
    }

    private static boolean isQuotaExhausted(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("insufficient_quota")
                || normalized.contains("quota_exhausted")
                || normalized.contains("quota exhausted")
                || normalized.contains("quota exceeded")
                || normalized.contains("exceeded your current quota")
                || normalized.contains("billing")
                || normalized.contains("check your plan");
    }

    public static AiProviderCallException timeout(String provider, Throwable cause) {
        return new AiProviderCallException(
                provider, Kind.TIMEOUT, null, null, null, provider + " call timed out", cause);
    }

    public static AiProviderCallException transport(String provider, Throwable cause) {
        return new AiProviderCallException(
                provider, Kind.TRANSPORT, null, null, null, provider + " call transport failure", cause);
    }

    public static AiProviderCallException unknown(String provider, Throwable cause) {
        return new AiProviderCallException(
                provider, Kind.UNKNOWN, null, null, null, provider + " call failed", cause);
    }

    private static String sanitizeProviderRequestId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.length() > MAX_PROVIDER_REQUEST_ID_LENGTH
                || !SAFE_PROVIDER_REQUEST_ID.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }
}
