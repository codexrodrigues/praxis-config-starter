package org.praxisplatform.config.service;

import java.net.http.HttpRequest;

/** Aplica uma credencial opaca ao request sem permitir injecao de headers adicionais. */
final class GovernedPlatformRequestAuthorization {

    private static final int MAX_AUTHORIZATION_LENGTH = 8_192;

    private GovernedPlatformRequestAuthorization() {
    }

    static void apply(
            HttpRequest.Builder request,
            GovernedPlatformRequestAuthorizationProvider provider,
            GovernedPlatformRequest context) {
        if (provider == null) {
            return;
        }
        provider.authorizationHeader(context)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .ifPresent(value -> request.header("Authorization", validate(value)));
    }

    private static String validate(String value) {
        if (value.length() > MAX_AUTHORIZATION_LENGTH
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid governed Authorization header");
        }
        return value;
    }
}
