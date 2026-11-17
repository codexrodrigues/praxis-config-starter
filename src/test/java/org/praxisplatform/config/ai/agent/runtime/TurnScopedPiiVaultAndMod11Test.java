package org.praxisplatform.config.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TurnScopedPiiVaultAndMod11Test {

    @Test
    void tokenizesValidMod11CpfAndCnpjAndRejectsFakeSequences() {
        String raw = "Consultar servidor CPF 529.982.247-25, CNPJ 11.222.333/0001-81, "
                + "matricula 11111111111 e codigo 12345678900.";
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize(raw);

        assertThat(vault.hasTaxIds()).isTrue();
        assertThat(vault.tokens()).containsExactly("[TAX_ID_1]", "[TAX_ID_2]");
        assertThat(vault.sanitizedPrompt())
                .contains("[TAX_ID_1]")
                .contains("[TAX_ID_2]")
                .contains("11111111111")
                .contains("12345678900")
                .doesNotContain("52998224725")
                .doesNotContain("529.982.247-25")
                .doesNotContain("11222333000181")
                .doesNotContain("11.222.333/0001-81");

        assertThat(vault.resolveDigits("[TAX_ID_1]")).contains("52998224725");
        assertThat(vault.resolveDigits("[TAX_ID_2]")).contains("11222333000181");
        assertThat(Mod11IdentifierHintExtractor.extractValidTaxIds("11111111111")).isEmpty();
        assertThat(Mod11IdentifierHintExtractor.extractValidTaxIds("12345678900")).isEmpty();
    }

    @Test
    void rehydratesInMemoryAndScrubsOutboundModelPayloads() {
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("52998224725");

        Map<String, Object> rehydrated = vault.rehydrateArguments(Map.of(
                "query", "[TAX_ID_1]",
                "filters", Map.of("taxId", "[TAX_ID_1]"),
                "items", List.of("[TAX_ID_1]")));
        assertThat(rehydrated.get("query")).isEqualTo("52998224725");
        assertThat(((Map<?, ?>) rehydrated.get("filters")).get("taxId")).isEqualTo("52998224725");

        Map<String, Object> scrubbed = vault.scrubOutboundForModel(Map.of(
                "rawText", "Documento 529.982.247-25 encontrado",
                "nested", Map.of("digits", "52998224725")));
        assertThat(scrubbed.get("rawText").toString())
                .contains("[TAX_ID_1]")
                .doesNotContain("529.982.247-25");
        assertThat(((Map<?, ?>) scrubbed.get("nested")).get("digits")).isEqualTo("[TAX_ID_1]");
    }
}
