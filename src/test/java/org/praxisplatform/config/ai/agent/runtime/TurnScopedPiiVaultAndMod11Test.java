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

    @Test
    void tokenizesRehydratesAndScrubsUnmaskedEmailsWithoutAffectingTaxIdTokens() {
        String raw = "Consultar CPF 529.982.247-25 e email maria.clara@servidor.gov.br (copia m***@servidor.gov.br).";
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize(raw);

        assertThat(vault.tokens()).containsExactly("[TAX_ID_1]");
        assertThat(vault.emailTokens()).containsExactly("[EMAIL_1]");
        assertThat(vault.sanitizedPrompt())
                .contains("[TAX_ID_1]")
                .contains("[EMAIL_1]")
                .contains("m***@servidor.gov.br")
                .doesNotContain("529.982.247-25")
                .doesNotContain("52998224725")
                .doesNotContain("maria.clara@servidor.gov.br");

        assertThat(vault.rehydrateText("buscar [EMAIL_1] para [TAX_ID_1]"))
                .isEqualTo("buscar maria.clara@servidor.gov.br para 52998224725");
        Map<String, Object> rehydratedArgs = vault.rehydrateArguments(Map.of(
                "query", "[EMAIL_1]",
                "filters", Map.of("email", "[EMAIL_1]")));
        assertThat(rehydratedArgs.get("query")).isEqualTo("maria.clara@servidor.gov.br");
        assertThat(((Map<?, ?>) rehydratedArgs.get("filters")).get("email"))
                .isEqualTo("maria.clara@servidor.gov.br");

        Map<String, Object> scrubbed = vault.scrubOutboundForModel(Map.of(
                "matched", "Contato maria.clara@servidor.gov.br",
                "other", "copia carlos.silva@servidor.gov.br",
                "alreadyMasked", "m***@servidor.gov.br"));
        assertThat(scrubbed.get("matched").toString())
                .contains("[EMAIL_1]")
                .doesNotContain("maria.clara@servidor.gov.br");
        assertThat(scrubbed.get("other").toString())
                .contains("c***@servidor.gov.br")
                .doesNotContain("carlos.silva@servidor.gov.br");
        assertThat(scrubbed.get("alreadyMasked")).isEqualTo("m***@servidor.gov.br");

        String trailingPeriod = TurnScopedPiiVault.maskRawEmails(
                "Relatorio emitido por ops.auditor@workforce.org. Fim.");
        assertThat(trailingPeriod)
                .isEqualTo("Relatorio emitido por o***@workforce.org. Fim.")
                .doesNotContain("ops.auditor@workforce.org");
    }

    @Test
    void handlesSetsAndArraysDuringRehydrationScrubbingAndComposerMasking() {
        TurnScopedPiiVault vault = TurnScopedPiiVault.tokenize("CPF 52998224725 e maria.clara@servidor.gov.br");

        Map<String, Object> rehydrated = vault.rehydrateArguments(Map.of(
                "setVals", java.util.Set.of("[TAX_ID_1]", "[EMAIL_1]"),
                "strArray", new String[]{"[TAX_ID_1]", "ok"},
                "objArray", new Object[]{"[EMAIL_1]", 42}));
        assertThat(((java.util.Set<?>) rehydrated.get("setVals")).stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("52998224725", "maria.clara@servidor.gov.br");
        assertThat((String[]) rehydrated.get("strArray"))
                .containsExactly("52998224725", "ok");
        assertThat((Object[]) rehydrated.get("objArray"))
                .containsExactly("maria.clara@servidor.gov.br", 42);

        Map<String, Object> scrubbed = vault.scrubOutboundForModel(Map.of(
                "setVals", java.util.Set.of("CPF 529.982.247-25"),
                "strArray", new String[]{"maria.clara@servidor.gov.br"},
                "objArray", new Object[]{"11.222.333/0001-81"}));
        assertThat(((java.util.Set<?>) scrubbed.get("setVals")).stream().map(Object::toString).toList())
                .containsExactly("CPF [TAX_ID_1]");
        assertThat((String[]) scrubbed.get("strArray"))
                .containsExactly("[EMAIL_1]");
        assertThat(((Object[]) scrubbed.get("objArray"))[0].toString())
                .doesNotContain("11.222.333/0001-81")
                .contains("*");

        Object masked = OperationalAssistantBlockComposer.maskPiiValue(Map.of(
                "emails", java.util.Set.of("ops.auditor@workforce.org"),
                "taxArray", new String[]{"CPF 529.982.247-25"}));
        assertThat(masked).isInstanceOf(Map.class);
        Map<?, ?> maskedMap = (Map<?, ?>) masked;
        assertThat(((java.util.Set<?>) maskedMap.get("emails")).stream().map(Object::toString).toList())
                .containsExactly("o***@workforce.org");
        assertThat(((String[]) maskedMap.get("taxArray"))[0]).doesNotContain("529.982.247-25");
    }
}
