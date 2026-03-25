package org.praxisplatform.config.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Emite e valida tokens de acesso para o canal de streaming AI quando a plataforma opera em modo
 * de URL assinada.
 *
 * <p>O servico encapsula assinatura, cifragem, TTL e verificacao de escopo do token para garantir
 * que um stream SSE so seja consumido pelo tenant/usuario autorizado e dentro da janela valida.
 */
@Service
public class AiStreamAccessTokenService {

    private static final String AUTH_MODE_COOKIE = "cookie";
    private static final String AUTH_MODE_SIGNED_URL = "signed-url-token";
    private static final String ENCRYPTED_TOKEN_PREFIX = "enc1";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${praxis.ai.stream.auth.mode:cookie}")
    private String authMode;

    @Value("${praxis.ai.stream.auth.token-secret:}")
    private String tokenSecret;

    @Value("${praxis.ai.stream.auth.token-ttl-seconds:900}")
    private long tokenTtlSeconds;

    @Value("${praxis.ai.stream.auth.allow-legacy-signed-token:false}")
    private boolean allowLegacySignedToken;

    @PostConstruct
    void validateConfiguration() {
        if (!isSignedUrlMode()) {
            return;
        }
        if (normalize(tokenSecret) == null) {
            throw new IllegalStateException(
                    "praxis.ai.stream.auth.token-secret is required when praxis.ai.stream.auth.mode=signed-url-token");
        }
        if (tokenTtlSeconds <= 0) {
            throw new IllegalStateException(
                    "praxis.ai.stream.auth.token-ttl-seconds must be positive when signed-url-token mode is enabled");
        }
    }

    public String resolveAuthMode() {
        return isSignedUrlMode() ? "signed_url_token" : AUTH_MODE_COOKIE;
    }

    public boolean isSignedUrlTokenMode() {
        return isSignedUrlMode();
    }

    public String issueToken(UUID streamId, AiPrincipalContext principalContext, Instant streamExpiresAt) {
        if (!isSignedUrlMode()) {
            return null;
        }
        requireSecret();
        if (streamId == null || principalContext == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing stream token context.");
        }
        String tenantId = normalize(principalContext.tenantId());
        String userId = normalize(principalContext.userId());
        if (tenantId == null || userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identity context is required.");
        }
        long nowEpoch = Instant.now().getEpochSecond();
        long configuredExpiry = nowEpoch + Math.max(60L, tokenTtlSeconds);
        long streamExpiry = streamExpiresAt != null ? streamExpiresAt.getEpochSecond() : configuredExpiry;
        long effectiveExpiry = Math.max(nowEpoch + 60L, Math.min(configuredExpiry, streamExpiry));
        String environment = normalize(principalContext.environment());
        String payload = String.join(
                "|",
                streamId.toString(),
                tenantId,
                userId,
                environment != null ? environment : "",
                Long.toString(effectiveExpiry));
        return ENCRYPTED_TOKEN_PREFIX + "." + encryptPayload(payload);
    }

    public AiPrincipalContext resolvePrincipalContext(
            UUID streamId,
            String token,
            AiPrincipalContext principalContext) {
        if (!isSignedUrlMode()) {
            return requirePrincipal(principalContext);
        }
        requireSecret();
        TokenClaims claims = parseAndValidateToken(streamId, token);
        if (principalContext == null
                || normalize(principalContext.tenantId()) == null
                || normalize(principalContext.userId()) == null) {
            return new AiPrincipalContext(
                    claims.tenantId(),
                    claims.userId(),
                    claims.environment(),
                    true);
        }
        if (!safeEquals(claims.tenantId(), principalContext.tenantId())
                || !safeEquals(claims.userId(), principalContext.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Stream access token is outside identity scope.");
        }
        String principalEnvironment = normalize(principalContext.environment());
        if (claims.environment() != null
                && principalEnvironment != null
                && !claims.environment().equals(principalEnvironment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Stream access token environment mismatch.");
        }
        return new AiPrincipalContext(
                claims.tenantId(),
                claims.userId(),
                principalEnvironment != null ? principalEnvironment : claims.environment(),
                principalContext.resolvedFromServerPrincipal());
    }

    public void validateToken(UUID streamId, String token, AiPrincipalContext principalContext) {
        resolvePrincipalContext(streamId, token, principalContext);
    }

    private AiPrincipalContext requirePrincipal(AiPrincipalContext principalContext) {
        if (principalContext == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        if (normalize(principalContext.tenantId()) == null
                || normalize(principalContext.userId()) == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Identity context is required.");
        }
        return principalContext;
    }

    private TokenClaims parseAndValidateToken(UUID streamId, String token) {
        String normalizedToken = normalize(token);
        if (normalizedToken == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Stream access token is required.");
        }
        String decodedPayload = decodeTokenPayload(normalizedToken);
        String[] parts = decodedPayload.split("\\|", -1);
        if (parts.length != 5) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token payload.");
        }
        UUID tokenStreamId;
        try {
            tokenStreamId = UUID.fromString(parts[0]);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token stream scope.");
        }
        if (streamId == null || !streamId.equals(tokenStreamId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Stream access token is outside stream scope.");
        }
        String tokenTenant = normalize(parts[1]);
        String tokenUser = normalize(parts[2]);
        String tokenEnvironment = normalize(parts[3]);
        long tokenExpiry;
        try {
            tokenExpiry = Long.parseLong(parts[4]);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token expiry.");
        }
        if (Instant.now().getEpochSecond() > tokenExpiry) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Stream access token expired.");
        }
        if (tokenTenant == null || tokenUser == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token identity scope.");
        }
        return new TokenClaims(tokenTenant, tokenUser, tokenEnvironment);
    }

    private String decodeTokenPayload(String token) {
        if (token.startsWith(ENCRYPTED_TOKEN_PREFIX + ".")) {
            String encryptedPart = token.substring((ENCRYPTED_TOKEN_PREFIX + ".").length());
            return decryptPayload(encryptedPart);
        }
        if (!allowLegacySignedToken) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Legacy stream access token format is disabled.");
        }
        return decodeLegacySignedPayload(token);
    }

    private String decodeLegacySignedPayload(String token) {
        int delimiter = token.lastIndexOf('.');
        if (delimiter <= 0 || delimiter >= token.length() - 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token.");
        }
        String payloadPart = token.substring(0, delimiter);
        String signaturePart = token.substring(delimiter + 1);
        String expectedSignature = sign(payloadPart);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signaturePart.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token signature.");
        }
        try {
            return new String(URL_DECODER.decode(payloadPart), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token payload.");
        }
    }

    private boolean isSignedUrlMode() {
        String normalized = normalize(authMode);
        if (normalized == null) {
            return false;
        }
        return AUTH_MODE_SIGNED_URL.equalsIgnoreCase(normalized)
                || "signed_url_token".equalsIgnoreCase(normalized);
    }

    private void requireSecret() {
        if (normalize(tokenSecret) == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "praxis.ai.stream.auth.token-secret is required for signed-url-token mode.");
        }
    }

    private String encryptPayload(String payload) {
        try {
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return URL_ENCODER.encodeToString(packed);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to encrypt stream token.", ex);
        }
    }

    private String decryptPayload(String encryptedPayload) {
        byte[] packed;
        try {
            packed = URL_DECODER.decode(encryptedPayload);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token payload.");
        }
        if (packed.length <= GCM_NONCE_BYTES) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token payload.");
        }
        byte[] nonce = Arrays.copyOfRange(packed, 0, GCM_NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(packed, GCM_NONCE_BYTES, packed.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid stream access token payload.");
        }
    }

    private SecretKeySpec encryptionKey() {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(tokenSecret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to initialize stream token key.", ex);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.encodeToString(signature);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to sign stream token.", ex);
        }
    }

    private boolean safeEquals(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft == null) {
            return normalizedRight == null;
        }
        return normalizedLeft.equals(normalizedRight);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record TokenClaims(String tenantId, String userId, String environment) {
    }
}
