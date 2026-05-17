package org.praxisplatform.config.service;

/**
 * Falha normalizada em chamadas sincronas de providers AI.
 *
 * <p>Evita que fluxos de authoring precisem inferir causa operacional a partir de mensagens
 * opacas do SDK ou do HTTP client.</p>
 */
public final class AiProviderCallException extends RuntimeException {

    public enum Kind {
        TRANSPORT,
        TIMEOUT,
        RATE_LIMIT,
        CAPACITY,
        AUTH,
        CLIENT_ERROR,
        SERVER_ERROR,
        UNKNOWN
    }

    private final String provider;
    private final Kind kind;
    private final Integer statusCode;

    private AiProviderCallException(
            String provider,
            Kind kind,
            Integer statusCode,
            String message,
            Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.kind = kind != null ? kind : Kind.UNKNOWN;
        this.statusCode = statusCode;
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

    public static AiProviderCallException fromHttpStatus(String provider, int statusCode, String reason) {
        Kind kind;
        if (statusCode == 408 || statusCode == 504) {
            kind = Kind.TIMEOUT;
        } else if (statusCode == 429) {
            kind = Kind.RATE_LIMIT;
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
        String message = provider + " HTTP " + statusCode + (reason == null || reason.isBlank()
                ? ""
                : ": " + reason);
        return new AiProviderCallException(provider, kind, statusCode, message, null);
    }

    public static AiProviderCallException timeout(String provider, Throwable cause) {
        return new AiProviderCallException(provider, Kind.TIMEOUT, null, provider + " call timed out", cause);
    }

    public static AiProviderCallException transport(String provider, Throwable cause) {
        return new AiProviderCallException(provider, Kind.TRANSPORT, null, provider + " call transport failure", cause);
    }

    public static AiProviderCallException unknown(String provider, Throwable cause) {
        return new AiProviderCallException(provider, Kind.UNKNOWN, null, provider + " call failed", cause);
    }
}
