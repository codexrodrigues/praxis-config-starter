package org.praxisplatform.config.service;

/**
 * Falha de streaming normalizada no nivel do provider.
 *
 * <p>Encapsula tipo e status da falha para que o orquestrador tome decisoes deterministicas de
 * fallback, retry ou encerramento do stream sem depender de mensagens opacas do SDK.
 */
public final class AiProviderStreamException extends RuntimeException {

    /**
     * Categorias normalizadas de falha observadas no streaming de providers.
     */
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

    private AiProviderStreamException(
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

    public static AiProviderStreamException fromHttpStatus(String provider, int statusCode, String reason) {
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
        String message = provider + " stream HTTP " + statusCode + (reason == null || reason.isBlank()
                ? ""
                : ": " + reason);
        return new AiProviderStreamException(provider, kind, statusCode, message, null);
    }

    public static AiProviderStreamException timeout(String provider, Throwable cause) {
        return new AiProviderStreamException(provider, Kind.TIMEOUT, null, provider + " stream timed out", cause);
    }

    public static AiProviderStreamException transport(String provider, Throwable cause) {
        return new AiProviderStreamException(provider, Kind.TRANSPORT, null, provider + " stream transport failure", cause);
    }

    public static AiProviderStreamException unknown(String provider, Throwable cause) {
        return new AiProviderStreamException(provider, Kind.UNKNOWN, null, provider + " stream failed", cause);
    }
}
