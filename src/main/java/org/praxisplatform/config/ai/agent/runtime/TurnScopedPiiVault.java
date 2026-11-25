package org.praxisplatform.config.ai.agent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.praxisplatform.config.ai.agent.runtime.Mod11IdentifierHintExtractor.TaxIdMatch;
import org.springframework.util.StringUtils;

/**
 * Cofre efemero com escopo exclusivo do turno para pseudonimizacao de documentos Mod-11
 * (CPF/CNPJ) e enderecos de e-mail antes da persistencia em banco e antes do envio ao LLM.
 *
 * <p>Permite reidratar {@code [TAX_ID_n]} e {@code [EMAIL_n]} estritamente em memoria durante a
 * execucao da ferramenta Java local e impede que valores brutos retornem ao modelo.
 */
public final class TurnScopedPiiVault {

    private static final Pattern RAW_EMAIL_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]{1,64})@((?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,})(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])");

    private final String sanitizedPrompt;
    private final Map<String, String> tokenToDigits;
    private final Map<String, String> digitsToToken;
    private final Map<String, Boolean> tokenIsCpf;
    private int nextSequence;
    private final Map<String, String> tokenToEmail;
    private final Map<String, String> emailToToken;
    private int nextEmailSequence;

    private TurnScopedPiiVault(
            String sanitizedPrompt,
            Map<String, String> tokenToDigits,
            Map<String, String> digitsToToken,
            Map<String, Boolean> tokenIsCpf,
            int nextSequence,
            Map<String, String> tokenToEmail,
            Map<String, String> emailToToken,
            int nextEmailSequence) {
        this.sanitizedPrompt = sanitizedPrompt;
        this.tokenToDigits = new LinkedHashMap<>(tokenToDigits);
        this.digitsToToken = new LinkedHashMap<>(digitsToToken);
        this.tokenIsCpf = new LinkedHashMap<>(tokenIsCpf);
        this.nextSequence = nextSequence;
        this.tokenToEmail = new LinkedHashMap<>(tokenToEmail);
        this.emailToToken = new LinkedHashMap<>(emailToToken);
        this.nextEmailSequence = nextEmailSequence;
    }

    public static TurnScopedPiiVault tokenize(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return new TurnScopedPiiVault(
                    rawText, Map.of(), Map.of(), Map.of(), 1, Map.of(), Map.of(), 1);
        }
        TurnScopedPiiVault vault = new TurnScopedPiiVault(
                "", Map.of(), Map.of(), Map.of(), 1, Map.of(), Map.of(), 1);
        String sanitized = vault.registerAndSanitizeEmails(vault.registerAndSanitize(rawText));
        return new TurnScopedPiiVault(
                sanitized,
                vault.tokenToDigits,
                vault.digitsToToken,
                vault.tokenIsCpf,
                vault.nextSequence,
                vault.tokenToEmail,
                vault.emailToToken,
                vault.nextEmailSequence);
    }

    /**
     * Tokeniza textos adicionais dentro do mesmo turno (por exemplo, mensagens de historico),
     * reutilizando tokens existentes para os mesmos digitos e e-mails e atribuindo indices
     * monotonicamente crescentes sem colisao.
     */
    public synchronized String tokenizeAdditional(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return rawText;
        }
        return scrubEmails(registerAndSanitizeEmails(registerAndSanitize(rawText)));
    }

    private String registerAndSanitize(String rawText) {
        List<TaxIdMatch> matches = Mod11IdentifierHintExtractor.extractValidTaxIds(rawText);
        if (matches.isEmpty()) {
            return rawText;
        }
        StringBuilder out = new StringBuilder(rawText.length());
        int cursor = 0;
        for (TaxIdMatch match : matches) {
            out.append(rawText, cursor, match.start());
            String token = digitsToToken.get(match.digits());
            if (token == null) {
                token = "[TAX_ID_" + nextSequence++ + "]";
                digitsToToken.put(match.digits(), token);
                tokenToDigits.put(token, match.digits());
                tokenIsCpf.put(token, match.cpf());
            }
            out.append(token);
            cursor = match.end();
        }
        out.append(rawText, cursor, rawText.length());
        return out.toString();
    }

    private String registerAndSanitizeEmails(String rawText) {
        if (!StringUtils.hasText(rawText) || rawText.indexOf('@') < 0) {
            return rawText;
        }
        Matcher matcher = RAW_EMAIL_PATTERN.matcher(rawText);
        StringBuilder out = new StringBuilder(rawText.length());
        while (matcher.find()) {
            String fullEmail = matcher.group(0);
            String local = matcher.group(1);
            if (local.contains("***")) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(fullEmail));
                continue;
            }
            String normalized = fullEmail.toLowerCase(Locale.ROOT);
            String token = emailToToken.get(normalized);
            if (token == null) {
                token = "[EMAIL_" + nextEmailSequence++ + "]";
                emailToToken.put(normalized, token);
                tokenToEmail.put(token, fullEmail);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public String sanitizedPrompt() {
        return sanitizedPrompt;
    }

    public synchronized boolean hasTaxIds() {
        return !tokenToDigits.isEmpty();
    }

    public synchronized Optional<String> resolveDigits(String value) {
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

    public synchronized String rehydrateText(String input) {
        if (!StringUtils.hasText(input) || (tokenToDigits.isEmpty() && tokenToEmail.isEmpty())) {
            return input;
        }
        String result = input;
        for (Map.Entry<String, String> entry : tokenToDigits.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : tokenToEmail.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public synchronized Map<String, Object> rehydrateArguments(Map<String, Object> args) {
        if (args == null || args.isEmpty() || (tokenToDigits.isEmpty() && tokenToEmail.isEmpty())) {
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
        if (value instanceof java.util.Collection<?> collection) {
            java.util.Collection<Object> out = value instanceof java.util.Set<?>
                    ? new java.util.LinkedHashSet<>(collection.size())
                    : new ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(rehydrateValue(item));
            }
            return out;
        }
        if (value instanceof String[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = rehydrateText(arr[i]);
            }
            return out;
        }
        if (value instanceof Object[] arr) {
            Object[] out = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = rehydrateValue(arr[i]);
            }
            return out;
        }
        return value;
    }

    /**
     * Garante que nenhum documento em claro (presente neste cofre ou retornado por registros/alertas)
     * nem endereco de e-mail em claro reapareca em payloads devolvidos ao LLM.
     */
    public synchronized Map<String, Object> scrubOutboundForModel(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload == null ? Map.of() : payload;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey(), scrubValue(entry.getValue()));
            }
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
        if (value instanceof java.util.Collection<?> collection) {
            java.util.Collection<Object> out = value instanceof java.util.Set<?>
                    ? new java.util.LinkedHashSet<>(collection.size())
                    : new ArrayList<>(collection.size());
            for (Object item : collection) {
                out.add(scrubValue(item));
            }
            return out;
        }
        if (value instanceof String[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = scrubString(arr[i]);
            }
            return out;
        }
        if (value instanceof Object[] arr) {
            Object[] out = new Object[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = scrubValue(arr[i]);
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
        scrubbed = OperationalAssistantBlockComposer.maskMod11TaxIds(scrubbed);
        return scrubEmailsWithVault(scrubbed);
    }

    private String scrubEmailsWithVault(String text) {
        if (!StringUtils.hasText(text) || text.indexOf('@') < 0) {
            return text;
        }
        Matcher matcher = RAW_EMAIL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            String fullEmail = matcher.group(0);
            String local = matcher.group(1);
            String domain = matcher.group(2);
            String replacement;
            if (local.contains("***")) {
                replacement = fullEmail;
            } else {
                String token = emailToToken.get(fullEmail.toLowerCase(Locale.ROOT));
                replacement = token != null ? token : local.charAt(0) + "***@" + domain;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static String maskRawEmails(String text) {
        return scrubEmails(text);
    }

    private static String scrubEmails(String text) {
        if (!StringUtils.hasText(text) || text.indexOf('@') < 0) {
            return text;
        }
        Matcher matcher = RAW_EMAIL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer(text.length());
        while (matcher.find()) {
            String local = matcher.group(1);
            String domain = matcher.group(2);
            String replacement = local.contains("***")
                    ? matcher.group(0)
                    : local.charAt(0) + "***@" + domain;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public synchronized List<String> tokens() {
        return List.copyOf(tokenToDigits.keySet());
    }

    public synchronized List<String> emailTokens() {
        return List.copyOf(tokenToEmail.keySet());
    }

    public synchronized boolean isCpfToken(String token) {
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
