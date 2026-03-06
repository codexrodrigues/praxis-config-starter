package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class AiStreamAccessTokenServiceTest {

    private AiStreamAccessTokenService service;

    @BeforeEach
    void setUp() {
        service = new AiStreamAccessTokenService();
        ReflectionTestUtils.setField(service, "authMode", "signed-url-token");
        ReflectionTestUtils.setField(service, "tokenSecret", "super-secret-for-tests");
        ReflectionTestUtils.setField(service, "tokenTtlSeconds", 900L);
        ReflectionTestUtils.setField(service, "allowLegacySignedToken", false);
    }

    @Test
    void shouldIssueAndValidateSignedToken() {
        UUID streamId = UUID.randomUUID();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        String token = service.issueToken(streamId, principal, Instant.now().plusSeconds(600));

        assertThat(token).isNotBlank();
        assertThat(token).startsWith("enc1.");
        assertThat(token).doesNotContain("tenant-a");
        assertThat(token).doesNotContain("user-a");
        service.validateToken(streamId, token, principal);
    }

    @Test
    void shouldResolvePrincipalFromSignedTokenWhenPrincipalIsMissing() {
        UUID streamId = UUID.randomUUID();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        String token = service.issueToken(streamId, principal, Instant.now().plusSeconds(600));

        AiPrincipalContext resolved = service.resolvePrincipalContext(streamId, token, null);

        assertThat(resolved).isNotNull();
        assertThat(resolved.tenantId()).isEqualTo("tenant-a");
        assertThat(resolved.userId()).isEqualTo("user-a");
        assertThat(resolved.environment()).isEqualTo("prod");
    }

    @Test
    void shouldRejectTokenForDifferentStream() {
        UUID streamId = UUID.randomUUID();
        UUID otherStreamId = UUID.randomUUID();
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
        String token = service.issueToken(streamId, principal, Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> service.validateToken(otherStreamId, token, principal))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void shouldRequirePrincipalWhenCookieAuthModeIsActive() {
        ReflectionTestUtils.setField(service, "authMode", "cookie");

        assertThatThrownBy(() -> service.resolvePrincipalContext(UUID.randomUUID(), null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void shouldRejectLegacyTokenByDefaultInSignedUrlMode() {
        UUID streamId = UUID.randomUUID();
        String legacyToken = createLegacyToken(streamId, "tenant-a", "user-a", "prod", Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> service.resolvePrincipalContext(streamId, legacyToken, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(status.getReason()).contains("Legacy stream access token format is disabled.");
                });
    }

    @Test
    void shouldAcceptLegacyTokenWhenMigrationFlagIsEnabled() {
        UUID streamId = UUID.randomUUID();
        ReflectionTestUtils.setField(service, "allowLegacySignedToken", true);
        String legacyToken = createLegacyToken(streamId, "tenant-a", "user-a", "prod", Instant.now().plusSeconds(600));

        AiPrincipalContext resolved = service.resolvePrincipalContext(streamId, legacyToken, null);

        assertThat(resolved.tenantId()).isEqualTo("tenant-a");
        assertThat(resolved.userId()).isEqualTo("user-a");
        assertThat(resolved.environment()).isEqualTo("prod");
    }

    @Test
    void shouldFailFastWhenSignedModeIsMissingSecret() {
        ReflectionTestUtils.setField(service, "tokenSecret", "");

        assertThatThrownBy(() -> service.validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token-secret");
    }

    private String createLegacyToken(UUID streamId, String tenantId, String userId, String environment, Instant expiresAt) {
        String payload = String.join(
                "|",
                streamId.toString(),
                tenantId,
                userId,
                environment,
                Long.toString(expiresAt.getEpochSecond()));
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return encodedPayload + "." + signature;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("super-secret-for-tests".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
