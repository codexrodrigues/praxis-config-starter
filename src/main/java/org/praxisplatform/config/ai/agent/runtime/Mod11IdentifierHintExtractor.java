package org.praxisplatform.config.ai.agent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrator e validador deterministico Mod-11 para CPF (11 digitos) e CNPJ (14 digitos).
 *
 * <p>Sequencias com todos os digitos repetidos (ex.: {@code 11111111111}) ou com digitos
 * verificadores invalidos sao explicitamente rejeitadas e tratadas pelo fluxo apenas como
 * identificadores ou matriculas comuns.
 */
public final class Mod11IdentifierHintExtractor {

    private static final Pattern CANDIDATE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}|\\d{2}\\.?\\d{3}\\.?\\d{3}/?\\d{4}-?\\d{2})(?!\\d)");

    public record TaxIdMatch(
            int start,
            int end,
            String rawText,
            String digits,
            boolean cpf
    ) {
    }

    private Mod11IdentifierHintExtractor() {
    }

    public static List<TaxIdMatch> extractValidTaxIds(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TaxIdMatch> matches = new ArrayList<>();
        Matcher matcher = CANDIDATE_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String digits = raw.replaceAll("\\D", "");
            if (digits.length() == 11 && isValidCpf(digits)) {
                matches.add(new TaxIdMatch(matcher.start(), matcher.end(), raw, digits, true));
            } else if (digits.length() == 14 && isValidCnpj(digits)) {
                matches.add(new TaxIdMatch(matcher.start(), matcher.end(), raw, digits, false));
            }
        }
        return List.copyOf(matches);
    }

    public static boolean isValidCpf(String digitsOnly) {
        if (digitsOnly == null || digitsOnly.length() != 11 || !digitsOnly.chars().allMatch(Character::isDigit)) {
            return false;
        }
        if (allSameDigit(digitsOnly)) {
            return false;
        }
        int d1 = computeCpfDigit(digitsOnly, 9, 10);
        int d2 = computeCpfDigit(digitsOnly, 10, 11);
        return d1 == (digitsOnly.charAt(9) - '0') && d2 == (digitsOnly.charAt(10) - '0');
    }

    public static boolean isValidCnpj(String digitsOnly) {
        if (digitsOnly == null || digitsOnly.length() != 14 || !digitsOnly.chars().allMatch(Character::isDigit)) {
            return false;
        }
        if (allSameDigit(digitsOnly)) {
            return false;
        }
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int d1 = computeCnpjDigit(digitsOnly, w1);
        int d2 = computeCnpjDigit(digitsOnly, w2);
        return d1 == (digitsOnly.charAt(12) - '0') && d2 == (digitsOnly.charAt(13) - '0');
    }

    private static int computeCpfDigit(String digits, int len, int startWeight) {
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += (digits.charAt(i) - '0') * (startWeight - i);
        }
        int rem = 11 - (sum % 11);
        return rem >= 10 ? 0 : rem;
    }

    private static int computeCnpjDigit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int mod = sum % 11;
        return mod < 2 ? 0 : 11 - mod;
    }

    private static boolean allSameDigit(String value) {
        char first = value.charAt(0);
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }
}
