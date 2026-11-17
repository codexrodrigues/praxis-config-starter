package org.praxisplatform.config.ai.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.praxisplatform.config.ai.agent.runtime.Mod11IdentifierHintExtractor.TaxIdMatch;
import org.springframework.util.StringUtils;

/**
 * Cofre efemero com escopo exclusivo do turno para pseudonimizacao de documentos Mod-11
 * (CPF/CNPJ) antes da persistencia em banco e antes do envio ao LLM.
 *
 * <p>Permite reidratar {@code [TAX_ID_n]} estritamente em memoria durante a execucao da
 * ferramenta Java local e impede que o valor bruto retorne ao modelo.
 */
public final class TurnScopedPiiVault {

    private final String sanitizedPrompt;
    private final Map<String, String> tokenToDigits;
    private final Map<String, String> digitsToToken;
    private final Map<String, Boolean> tokenIsCpf;

    private TurnScopedPiiVault(
            String sanitizedPrompt,
            Map<String, String> tokenToDigits,
            Map<String, String> digitsToToken,
            Map<String, Boolean> tokenIsCpf) {
        this.sanitizedPrompt = sanitizedPrompt;
        this.tokenToDigits = Collections.unmodifiableMap(new LinkedHashMap<>(tokenToDigits));
        this.digitsToToken = Collections.unmodifiableMap(new LinkedHashMap<>(digitsToToken));
        this.tokenIsCpf = Collections.unmodifiableMap(new LinkedHashMap<>(tokenIsCpf));
    }

    public static TurnScopedPiiVault tokenize(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return new TurnScopedPiiVault(rawText, Map.of(), Map.of(), Map.of());
        }
        List<TaxIdMatch> matches = Mod11IdentifierHintExtractor.extractValidTaxIds(rawText);
        if (matches.isEmpty()) {
            return new TurnScopedPiiVault(rawText, Map.of(), Map.of(), Map.of());
        }

        Map<String, String> tokenToDigits = new LinkedHashMap<>();
        Map<String, String> digitsToToken = new LinkedHashMap<>();
        Map<String, Boolean> tokenIsCpf = new LinkedHashMap<>();

        StringBuilder out = new StringBuilder(rawText.length());
        int cursor = 0;
        int seq = 1;
        for (TaxIdMatch match : matches) {
            out.append(rawText, cursor, match.start());
            String token = digitsToToken.get(match.digits());
            if (token == null) {
                token = "[TAX_ID_" + seq++ + "]";
                digitsToToken.put(match.digits(), token);
                tokenToDigits.put(token, match.digits());
                tokenIsCpf.put(token, match.cpf());
            }
            out.append(token);
            cursor = match.end();
        }
        out.append(rawText, cursor, rawText.length());
        return new TurnScopedPiiVault(out.toString(), tokenToDigits, digitsToToken, tokenIsCpf);
    }

    public String sanitizedPrompt() {
        return sanitizedPrompt;
    }

    public boolean hasTaxIds() {
        return !tokenToDigits.isEmpty();
    }

    public Optional<String> resolveDigits(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (tokenToDigits.containsKey(trimmed)) {
            return Optional.of(tokenToDigits.get(trimmed));
        }
        List<TaxIdMatch> direct = Mod11IdentifierHintExtractor.extractValidTaxIds(trimmed);
        if (!direct.isEmpty()) {
            return Optional.of(direct.get(0).digits());
        }
        return Optional.empty();
    }

    public String rehydrateText(String input) {
        if (!StringUtils.hasText(input) || tokenToDigits.isEmpty()) {
            return input;
        }
        String result = input;
        for (Map.Entry<String, String> entry : tokenToDigits.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public Map<String, Object> rehydrateArguments(Map<String, Object> args) {
        if (args == null || args.isEmpty() || tokenToDigits.isEmpty()) {
            return args == null ? Map.of() : args;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            copy.put(entry.getKey(), rehydrateValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object rehydrateValue(Object value) {
        if (value instanceof String str) {
            return rehydrateText(str);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), rehydrateValue(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(rehydrateValue(item));
            }
            return out;
        }
        return value;
    }

    /**
     * Garante que nenhum documento em claro presente neste cofre reapareca em payloads devolvidos
     * ao LLM apos a execucao das ferramentas.
     */
    public Map<String, Object> scrubOutboundForModel(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty() || digitsToToken.isEmpty()) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            out.put(entry.getKey(), scrubValue(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object scrubValue(Object value) {
        if (value instanceof String str) {
            return scrubString(str);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(entry.getKey().toString(), scrubValue(entry.getValue()));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(scrubValue(item));
            }
            return out;
        }
        return value;
    }

    private String scrubString(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String scrubbed = value;
        for (Map.Entry<String, String> entry : digitsToToken.entrySet()) {
            String digits = entry.getKey();
            String token = entry.getValue();
            scrubbed = scrubbed.replace(digits, token);
            String formatted = formatDigits(digits);
            if (!formatted.equals(digits)) {
                scrubbed = scrubbed.replace(formatted, token);
            }
        }
        return scrubbed;
    }

    public List<String> tokens() {
        return List.copyOf(tokenToDigits.keySet());
    }

    public boolean isCpfToken(String token) {
        return Boolean.TRUE.equals(tokenIsCpf.get(token));
    }

    private static String formatDigits(String digits) {
        if (digits.length() == 11) {
            return digits.substring(0, 3) + "." + digits.substring(3, 6) + "."
                    + digits.substring(6, 9) + "-" + digits.substring(9, 11);
        }
        if (digits.length() == 14) {
            return digits.substring(0, 2) + "." + digits.substring(2, 5) + "."
                    + digits.substring(5, 8) + "/" + digits.substring(8, 12) + "-" + digits.substring(12, 14);
        }
        return digits;
    }
}
