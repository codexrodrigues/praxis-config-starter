package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class GovernedColorPaletteContractValidatorTest {

    private ObjectMapper objectMapper;
    private GovernedColorPaletteContractValidator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validator = new GovernedColorPaletteContractValidator(objectMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"#12345", "#1234567", "#12", "rgb(nope)", "rgb(1,2,3,)",
            "rgb(1%,2,3)", "rgba(0,0,0,NaN)", "rgb(1e999,0,0)", "hsl(0,foo,50%)",
            "hsl(0,50,50)", "rgb(1 2 3 / )", "rgb(1,2,3 / .5)", "#-00000"})
    void rejectsInvalidFallbackColors(String color) {
        var payload = candidate(color);
        assertThat(validator.validate("test", payload).validation().valid()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#123", "#1234", "#123456", "#12345678", "transparent",
            "rgb(1,2,3)", "rgba(1,2,3,0.5)", "rgb(10% 20% 30% / 50%)",
            "hsl(120,100%,50%)", "hsl(.5turn 100% 50% / .5)", "hsl(200grad 20% 50%)",
            "hsl(3.14rad 20% 50%)", "hsl(-60deg 20% 50%)"})
    void acceptsAndParsesSupportedCssColors(String color) {
        assertThat(validator.validate("test", candidate(color)).validation().valid()).isTrue();
    }

    @Test
    void rejectsAmbiguousFallbackInsteadOfCertifyingADifferentRuntimeColor() {
        var payload = candidate("#000000");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("entries").get(0))
                .put("fallbackTokenId", "surface");
        var result = validator.validate("test", payload);
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().errors())
                .contains("entries[0] must specify only one of fallback or fallbackTokenId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hsl(0 0% 0%)", "rgb(0% 0% 0%)", "#000"})
    void usesTheSameColorParserForValidationAndContrast(String color) {
        var payload = candidate(color);
        var evidence = payload.putArray("contrastEvidence").addObject();
        evidence.put("foregroundTokenId", "color").put("backgroundTokenId", "surface")
                .put("requiredLevel", "AAA");
        var result = validator.validate("test", payload);
        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contrastEvidence().getFirst().ratio()).isEqualTo(21d);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode candidate(String color) {
        var payload = objectMapper.createObjectNode();
        payload.put("paletteKey", "test").put("displayName", "Test").put("familyKey", "test-family");
        payload.putObject("variant").put("key", "light").put("displayName", "Light");
        var entries = payload.putArray("entries");
        var colorEntry = entries.addObject().put("tokenId", "color").put("displayName", "Color")
                .put("semanticRole", "fill").put("cssReference", "var(--color)").put("fallback", color);
        colorEntry.putArray("aliases").add("Accent");
        colorEntry.putArray("purposes").add("fill");
        var surfaceEntry = entries.addObject().put("tokenId", "surface").put("displayName", "Surface")
                .put("semanticRole", "surface").put("cssReference", "var(--surface)").put("fallback", "#ffffff");
        surfaceEntry.putArray("aliases");
        surfaceEntry.putArray("purposes").add("surface");
        return payload;
    }

    @Test
    void recalculatesContrastInsteadOfTrustingTheCallerRatio() throws Exception {
        var result = validator.validate("corporate-main", objectMapper.readTree("""
                {
                  "paletteKey":"corporate-main",
                  "displayName":"Corporate",
                  "familyKey":"corporate",
                  "variant":{"key":"light","displayName":"Light","dimensions":{"colorScheme":"light"}},
                  "entries":[
                    {"tokenId":"text","displayName":"Text","aliases":[],"semanticRole":"text","cssReference":"var(--text)","fallback":"#000000","purposes":["text"]},
                    {"tokenId":"surface","displayName":"Surface","aliases":[],"semanticRole":"surface","cssReference":"var(--surface)","fallback":"#ffffff","purposes":["surface"]}
                  ],
                  "contrastEvidence":[
                    {"foregroundTokenId":"text","backgroundTokenId":"surface","ratio":1.01,"requiredLevel":"AAA"}
                  ]
                }
                """));

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contrastEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.ratio()).isEqualTo(21d);
            assertThat(evidence.passes()).isTrue();
        });
        assertThat(result.validation().warnings())
                .contains("contrastEvidence[0].ratio was recalculated from resolved fallback colors");
    }

    @Test
    void makesARealContrastFailureBlocking() throws Exception {
        var result = validator.validate("corporate-main", objectMapper.readTree("""
                {
                  "paletteKey":"corporate-main",
                  "displayName":"Corporate",
                  "familyKey":"corporate",
                  "variant":{"key":"light","displayName":"Light"},
                  "entries":[
                    {"tokenId":"text","displayName":"Text","aliases":[],"semanticRole":"text","cssReference":"var(--text)","fallback":"#777777","purposes":["text"]},
                    {"tokenId":"surface","displayName":"Surface","aliases":[],"semanticRole":"surface","cssReference":"var(--surface)","fallback":"#888888","purposes":["surface"]}
                  ],
                  "contrastEvidence":[
                    {"foregroundTokenId":"text","backgroundTokenId":"surface","ratio":9,"requiredLevel":"AA"}
                  ]
                }
                """));

        assertThat(result.validation().valid()).isFalse();
        assertThat(result.contrastEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.ratio()).isLessThan(1.3d);
            assertThat(evidence.passes()).isFalse();
        });
        assertThat(result.validation().errors()).contains("contrastEvidence[0] does not meet AA");
    }

    @Test
    void resolvesFallbackTokenChainsBeforeCalculatingContrast() throws Exception {
        var result = validator.validate("corporate-main", objectMapper.readTree("""
                {
                  "paletteKey":"corporate-main",
                  "displayName":"Corporate",
                  "familyKey":"corporate",
                  "variant":{"key":"light","displayName":"Light"},
                  "entries":[
                    {"tokenId":"text","displayName":"Text","aliases":[],"semanticRole":"text","cssReference":"var(--text)","fallbackTokenId":"ink","purposes":["text"]},
                    {"tokenId":"ink","displayName":"Ink","aliases":[],"semanticRole":"ink","cssReference":"var(--ink)","fallback":"rgb(0, 0, 0)","purposes":["fill"]},
                    {"tokenId":"surface","displayName":"Surface","aliases":[],"semanticRole":"surface","cssReference":"var(--surface)","fallback":"rgb(255, 255, 255)","purposes":["surface"]}
                  ],
                  "contrastEvidence":[
                    {"foregroundTokenId":"text","backgroundTokenId":"surface","requiredLevel":"AAA"}
                  ]
                }
                """));

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.contrastEvidence().getFirst().ratio()).isEqualTo(21d);
    }

    @Test
    void requiresContrastEvidenceForTextTokens() throws Exception {
        var result = validator.validate("corporate-main", objectMapper.readTree("""
                {
                  "paletteKey":"corporate-main",
                  "displayName":"Corporate",
                  "familyKey":"corporate",
                  "variant":{"key":"light","displayName":"Light"},
                  "entries":[
                    {"tokenId":"text","displayName":"Text","aliases":[],"semanticRole":"text","cssReference":"var(--text)","fallback":"#000","purposes":["text"]}
                  ]
                }
                """));

        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().errors())
                .contains("contrastEvidence is required when the palette contains text tokens");
    }

    @Test
    void requiresCanonicalFamilyVariantAndTokenPresentationIdentity() {
        var payload = candidate("#000000");
        payload.remove("familyKey");
        payload.remove("variant");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("entries").get(0))
                .remove("displayName");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("entries").get(0))
                .remove("aliases");

        assertThat(validator.validate("test", payload).validation().errors()).contains(
                "familyKey is required",
                "variant is required",
                "variant.key is required",
                "variant.displayName is required",
                "entries[0].displayName is required",
                "entries[0].aliases must be an array");
    }

    @Test
    void rejectsAmbiguousAliasesWithinTheResolvedToken() {
        var payload = candidate("#000000");
        var aliases = ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("entries").get(0))
                .putArray("aliases");
        aliases.add("Accent").add("accent").add("color").add(" ").add(42);
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("variant"))
                .putArray("dimensions").add("invalid");

        assertThat(validator.validate("test", payload).validation().errors()).contains(
                "entries[0].aliases contains duplicate value accent",
                "entries[0].aliases must not contain tokenId",
                "entries[0].aliases must contain non-blank strings",
                "variant.dimensions must be an object");
    }
}
