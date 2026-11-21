package org.praxisplatform.config.service;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Pure-JDK fail-closed validator and defensive sanitizer for optional {@code legalBasis[].url}
 * references in governed domain-rule documentation.
 */
public final class RuleLegalBasisUrlValidator {

    public static final int MAX_URL_LENGTH = 1024;

    private RuleLegalBasisUrlValidator() {}

    /**
     * Validates an optional HTTP/HTTPS URL, returning {@code null} when omitted or blank, or the
     * trimmed URL when valid.
     *
     * @throws IllegalArgumentException if the URL contains control/whitespace characters, exceeds
     *     {@value #MAX_URL_LENGTH} characters, is opaque/protocol-relative, uses a scheme other than
     *     {@code http}/{@code https}, embeds userinfo credentials, lacks a valid host, or has an
     *     invalid port
     */
    public static String validateOptionalHttpUrl(String rawUrl, String fieldLabel) {
        String label = (fieldLabel == null || fieldLabel.isBlank()) ? "url" : fieldLabel.trim();
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        if (rawUrl.codePoints().anyMatch(RuleLegalBasisUrlValidator::isForbiddenPreTrimControl)) {
            throw new IllegalArgumentException(label + " must not contain control characters");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(label + " exceeds " + MAX_URL_LENGTH + " characters");
        }
        if (trimmed.codePoints()
                .anyMatch(
                        cp ->
                                Character.isWhitespace(cp)
                                        || Character.isISOControl(cp)
                                        || Character.isSpaceChar(cp))) {
            throw new IllegalArgumentException(
                    label + " must not contain whitespace or control characters");
        }
        if (trimmed.startsWith("//")) {
            throw new IllegalArgumentException(label + " must use http or https scheme");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(label + " must be a valid http or https URL", exception);
        }
        if (uri.isOpaque()) {
            throw new IllegalArgumentException(label + " must use an explicit http or https URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(label + " must use http or https scheme");
        }
        if (uri.getRawUserInfo() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(label + " must not contain userinfo credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(label + " must include a valid host");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException(label + " port must be between 1 and 65535");
        }
        return trimmed;
    }

    /**
     * Defensively sanitizes an optional HTTP/HTTPS URL, returning {@code null} when the input is
     * {@code null}, blank, or fails {@link #validateOptionalHttpUrl(String, String)}.
     */
    public static String sanitizeOptionalHttpUrl(String rawUrl) {
        try {
            return validateOptionalHttpUrl(rawUrl, "url");
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isForbiddenPreTrimControl(int cp) {
        return Character.isISOControl(cp) || cp == '\u2028' || cp == '\u2029';
    }
}
