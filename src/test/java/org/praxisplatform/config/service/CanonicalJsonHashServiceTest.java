package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CanonicalJsonHashServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJsonHashService service = new CanonicalJsonHashService(objectMapper);

    @Test
    void matchesTheBrowserCanonicalNumberVector() throws Exception {
        var value = objectMapper.readTree("""
                {
                  "small": 1e-7,
                  "thresholdSmall": 1e-6,
                  "large": 1e21,
                  "minSubnormal": 4.9e-324,
                  "thresholdLarge": 1e20,
                  "decimal": 1.0,
                  "negativeZero": -0.0,
                  "fraction": 1234.5678901234567
                }
                """);

        assertThat(service.sha256(value))
                .isEqualTo("8b683131259f82811741c1dede49b1bcc75d256f011d1da81e935c34d168a0ac");
    }

    @Test
    void matchesTheBrowserUtf8AndNestedVectors() throws Exception {
        var text = objectMapper.readTree("""
                {
                  "z": "ação\\ncontrole",
                  "a": "😀",
                  "omitted": null,
                  "array": [null, "é", true, false]
                }
                """);
        var nested = objectMapper.readTree("""
                {"b":[{"y":2,"x":1}],"a":{"d":4,"c":3}}
                """);

        assertThat(service.sha256(text))
                .isEqualTo("6e7f0245a88ea9ecc8074defbbd936124bbfab28ef8b3078a1310eb2da1325df");
        assertThat(service.sha256(nested))
                .isEqualTo("be7c4247ec8669c74f18acccfe25972754e64977ed6562dace8c61960205b2c3");
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertThatThrownBy(() -> service.sha256(Double.NaN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot calculate canonical JSON hash.")
                .hasRootCauseMessage("Canonical JSON does not support non-finite numbers.");
    }
}
