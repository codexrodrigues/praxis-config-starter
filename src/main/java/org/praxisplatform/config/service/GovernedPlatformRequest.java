package org.praxisplatform.config.service;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Contexto canonico para uma leitura HTTP que fundamenta uma decisao de authoring.
 *
 * <p>O contexto carrega apenas identidade operacional e destinos. Credenciais permanecem opacas
 * no {@link GovernedPlatformRequestAuthorizationProvider} e nunca entram em DTOs, eventos SSE ou
 * configuracao persistida.</p>
 */
public record GovernedPlatformRequest(
        Surface surface,
        URI requestBaseUri,
        URI targetUri,
        String tenantId,
        String userId,
        String environment) {

    public enum Surface {
        SCHEMA_FILTERED,
        RESOURCE_CAPABILITIES,
        RESOURCE_SURFACE_CATALOG,
        RESOURCE_ACTION_CATALOG,
        OPTION_SOURCE_VALUES
    }

    public GovernedPlatformRequest {
        surface = Objects.requireNonNull(surface, "surface is required");
        targetUri = requireHttpUri(targetUri, "targetUri");
        requestBaseUri = optionalHttpUri(requestBaseUri, "requestBaseUri");
        tenantId = normalize(tenantId);
        userId = normalize(userId);
        environment = normalize(environment);
    }

    /** Retorna true somente quando origem, host e porta efetiva coincidem. */
    public boolean isSameOrigin() {
        if (requestBaseUri == null) {
            return false;
        }
        return normalizedScheme(requestBaseUri).equals(normalizedScheme(targetUri))
                && normalizedHost(requestBaseUri).equals(normalizedHost(targetUri))
                && effectivePort(requestBaseUri) == effectivePort(targetUri);
    }

    public static URI parseOptionalBaseUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static URI requireHttpUri(URI uri, String field) {
        URI resolved = optionalHttpUri(uri, field);
        if (resolved == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return resolved;
    }

    private static URI optionalHttpUri(URI uri, String field) {
        if (uri == null) {
            return null;
        }
        String scheme = normalizedScheme(uri);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(field + " must be an HTTP(S) origin without user info");
        }
        return uri;
    }

    private static String normalizedScheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static String normalizedHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equals(normalizedScheme(uri)) ? 443 : 80;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
