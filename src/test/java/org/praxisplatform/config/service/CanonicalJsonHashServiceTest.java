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
    void exactHashRejectsRoundedIntegersAndDecimalsWithoutChangingLegacyTokens() throws Exception {
        var lower = objectMapper.readTree("{\"value\":9007199254740992}");
        var adjacent = objectMapper.readTree("{\"value\":9007199254740993}");
        assertThat(service.sha256(lower)).isEqualTo(service.sha256(adjacent));
        assertThat(service.sha256Exact(lower)).isNotBlank();
        assertThatThrownBy(() -> service.sha256Exact(adjacent)).isInstanceOf(IllegalStateException.class);
        var precise = UiLayoutRevisionJsonInput.readDocument("{\"value\":0.10000000000000001}", () -> {});
        assertThat(precise.path("value").decimalValue()).isEqualByComparingTo("0.10000000000000001");
        assertThatThrownBy(() -> service.sha256Exact(precise)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.sha256Exact(
                UiLayoutRevisionJsonInput.readDocument("{\"value\":-9007199254740993}", () -> {})))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactPreciseTextAdmitsFaithfulLargeValuesAndEquivalentNotation() {
        for (String token : new String[] {"0.1", "1e20", "1e21", "1e-6", "1e-7", "5e-324", "-0.0"}) {
            var precise = UiLayoutRevisionJsonInput.readDocument("{\"value\":" + token + "}", () -> {});
            assertThat(service.sha256Exact(precise)).isNotBlank();
        }
        assertThat(service.sha256Exact(UiLayoutRevisionJsonInput.readDocument("{\"value\":1.0}", () -> {})))
                .isEqualTo(service.sha256Exact(UiLayoutRevisionJsonInput.readDocument("{\"value\":1}", () -> {})));
        for (String token : new String[] {"1e-400", "1e400", "4.9e-324"}) {
            var precise = UiLayoutRevisionJsonInput.readDocument("{\"value\":" + token + "}", () -> {});
            assertThatThrownBy(() -> service.sha256Exact(precise)).isInstanceOf(IllegalStateException.class);
        }
        // A materialized DoubleNode already denotes binary64, including the smallest subnormal.
        var materialized = objectMapper.createObjectNode().put("value", Double.MIN_VALUE);
        assertThat(service.sha256Exact(materialized)).isEqualTo(service.sha256Exact(
                UiLayoutRevisionJsonInput.readDocument("{\"value\":5e-324}", () -> {})));
    }

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
    void exactHashPreservesNestedNullWithoutChangingLegacyHash() throws Exception {
        var omitted = objectMapper.readTree("{\"bindings\":{},\"fields\":[{\"name\":\"email\"}]}");
        var explicit = objectMapper.readTree("{\"fields\":[{\"name\":\"email\",\"mask\":null}],\"bindings\":{\"emptyState\":null}}");
        var reordered = objectMapper.readTree("{\"bindings\":{\"emptyState\":null},\"fields\":[{\"mask\":null,\"name\":\"email\"}]}");
        assertThat(service.sha256(omitted)).isEqualTo(service.sha256(explicit));
        assertThat(service.sha256Exact(omitted)).isNotEqualTo(service.sha256Exact(explicit));
        assertThat(service.sha256Exact(explicit)).isEqualTo(service.sha256Exact(reordered));
    }

    @Test
    void exactHashPreservesArrayOrder() throws Exception {
        assertThat(service.sha256Exact(objectMapper.readTree("[\"email\",\"phone\"]")))
                .isNotEqualTo(service.sha256Exact(objectMapper.readTree("[\"phone\",\"email\"]")));
    }

    @Test
    void exactHashIgnoresCorporateNullAndEscapingDefaultsWithoutMutatingHost() throws Exception {
        var host = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.READ_NULL_PROPERTIES, false)
                .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.WRITE_NULL_PROPERTIES, false);
        host.getFactory().enable(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature());
        var corporate = new CanonicalJsonHashService(host);
        var explicit = objectMapper.readTree("{\"ação\":null,\"nested\":{\"label\":\"😀\",\"optional\":null},\"array\":[null,true]}");
        var omitted = objectMapper.readTree("{\"nested\":{\"label\":\"😀\"},\"array\":[null,true]}");
        assertThat(corporate.sha256Exact(explicit)).isEqualTo(service.sha256Exact(explicit));
        assertThat(corporate.sha256Exact(explicit)).isNotEqualTo(corporate.sha256Exact(omitted));
        assertThat(corporate.sha256Exact(java.util.Map.of("label", "ação")))
                .isEqualTo(service.sha256Exact(objectMapper.readTree("{\"label\":\"ação\"}")));
        assertThat(host.readTree("{\"optional\":null}").has("optional")).isFalse();
        assertThat(host.writeValueAsString(explicit)).contains("\\u").doesNotContain("optional");
        // Legacy hashing keeps its existing host conversion/escaping and null-omission behavior.
        assertThat(corporate.sha256(explicit)).isNotEqualTo(service.sha256(explicit));
    }

    @Test
    void exactHashIgnoresHostStringSerializer() throws Exception {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(String.class, new com.fasterxml.jackson.databind.JsonSerializer<String>() {
            @Override public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator generator,
                    com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                generator.writeString("host-redacted");
            }
        });
        var host = objectMapper.copy().registerModule(module);
        var value = objectMapper.readTree("{\"label\":\"ação\"}");
        assertThat(new CanonicalJsonHashService(host).sha256Exact(value)).isEqualTo(service.sha256Exact(value));
        assertThat(host.writeValueAsString("label")).isEqualTo("\"host-redacted\"");
    }

    @Test
    void exactHashRejectsForeignNodesAndNonFiniteValues() {
        for (var value : java.util.List.of(
                objectMapper.createObjectNode().putPOJO("foreign", new Object()),
                objectMapper.createObjectNode().put("binary", new byte[] {1}),
                objectMapper.createObjectNode().set("missing", com.fasterxml.jackson.databind.node.MissingNode.getInstance()),
                objectMapper.createObjectNode().put("number", Double.NaN))) {
            assertThatThrownBy(() -> service.sha256Exact(value)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertThatThrownBy(() -> service.sha256(Double.NaN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot calculate canonical JSON hash.")
                .hasRootCauseMessage("Canonical JSON does not support non-finite numbers.");
    }
}
