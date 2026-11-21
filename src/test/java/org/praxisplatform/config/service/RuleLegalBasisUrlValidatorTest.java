package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class RuleLegalBasisUrlValidatorTest {

    @Test
    void returnsNullForNullOrBlankOptionalUrls() {
        assertThat(RuleLegalBasisUrlValidator.validateOptionalHttpUrl(null, "url")).isNull();
        assertThat(RuleLegalBasisUrlValidator.validateOptionalHttpUrl("", "url")).isNull();
        assertThat(RuleLegalBasisUrlValidator.validateOptionalHttpUrl("   \t\n\r ", "url")).isNull();
        assertThat(RuleLegalBasisUrlValidator.sanitizeOptionalHttpUrl(null)).isNull();
        assertThat(RuleLegalBasisUrlValidator.sanitizeOptionalHttpUrl("   ")).isNull();
    }

    @ParameterizedTest
    @MethodSource("validUrls")
    void acceptsAndTrimsValidHttpAndHttpsUrls(String rawUrl, String expected) {
        assertThat(RuleLegalBasisUrlValidator.validateOptionalHttpUrl(rawUrl, "legalBasis.url"))
                .isEqualTo(expected);
        assertThat(RuleLegalBasisUrlValidator.sanitizeOptionalHttpUrl(rawUrl)).isEqualTo(expected);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> validUrls() {
        String longValid = "https://example.org/statutes/" + "a".repeat(1024 - "https://example.org/statutes/".length());
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "https://example.org/statutes/123", "https://example.org/statutes/123"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "http://example.org/statutes/123", "http://example.org/statutes/123"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "HTTPS://Example.ORG/Path?a=1&b=2#art-5",
                        "HTTPS://Example.ORG/Path?a=1&b=2#art-5"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "  https://example.org/statutes/123  ", "https://example.org/statutes/123"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "https://[::1]:8443/doc?x=1#s2", "https://[::1]:8443/doc?x=1#s2"),
                org.junit.jupiter.params.provider.Arguments.of(longValid, longValid));
    }

    @ParameterizedTest
    @MethodSource("invalidUrls")
    void rejectsUnsafeOrMalformedUrlsFailClosedAndSanitizesToNull(String rawUrl) {
        assertThatThrownBy(
                        () -> RuleLegalBasisUrlValidator.validateOptionalHttpUrl(rawUrl, "documentation.legalBasis.url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentation.legalBasis.url");
        assertThat(RuleLegalBasisUrlValidator.sanitizeOptionalHttpUrl(rawUrl)).isNull();
    }

    static Stream<String> invalidUrls() {
        String overLimit = "https://example.org/statutes/" + "a".repeat(1025 - "https://example.org/statutes/".length());
        return Stream.of(
                "javascript:alert(1)",
                "JAVASCRIPT:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "vbscript:msgbox(1)",
                "file:///etc/passwd",
                "ftp://example.org/statute.pdf",
                "mailto:legal@example.org",
                "//evil.example/path",
                "/relative/path",
                "https://user:pass@example.com/path",
                "https://user@example.com/path",
                "https://@example.com/path",
                "https:example.com",
                "https:///path",
                "https://:8080/path",
                "https://foo_bar/path",
                "https://example.org:0/path",
                "https://example.org:65536/path",
                "https://example.org/with space",
                "https://example.org/\u00A0path",
                "https://example.org/with\rcarriage",
                "https://example.org/with\nnewline",
                "https://example.org/with\ttab",
                "https://example.org/trailing\n",
                "\nhttps://example.org/leading",
                "\thttps://example.org/leading-tab",
                "\u0085",
                "https://example.org/\u0085nel",
                "https://example.org/\u2028line-sep",
                "\u2029https://example.org/",
                "https://example.org/\u0000nul",
                "https://example.org/\u007Fdel",
                "https://[broken-ipv6",
                overLimit);
    }
}
