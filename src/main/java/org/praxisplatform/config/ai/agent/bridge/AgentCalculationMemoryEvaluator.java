package org.praxisplatform.config.ai.agent.bridge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.ai.agent.runtime.Mod11IdentifierHintExtractor;
import org.praxisplatform.config.ai.agent.runtime.Mod11IdentifierHintExtractor.TaxIdMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory.EvaluatedStep;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory.EvaluatedTrail;
import org.praxisplatform.config.ai.agent.spi.DualProjectedPayload;
import org.springframework.util.StringUtils;

/**
 * Avaliador deterministico em Java puro para metadados {@code @CalculationMemory}, produzindo
 * dupla projecao ({@code uiTrails} para exibicao na UI autenticada e {@code modelSafeTrails} para
 * consumo seguro pelo LLM).
 */
public final class AgentCalculationMemoryEvaluator {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DecimalFormatSymbols PT_BR_SYMBOLS = DecimalFormatSymbols.getInstance(PT_BR);
    private static final DateTimeFormatter PT_BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MAX_EXPRESSION_LENGTH = 2048;

    private AgentCalculationMemoryEvaluator() {
    }

    public static AgentEvaluatedCalculationMemory evaluate(
            AgentEntityDescriptor entity,
            String entityId,
            String recordTitle,
            Map<String, Object> calculationMemoryMeta,
            DualProjectedPayload projected) {
        if (calculationMemoryMeta == null || calculationMemoryMeta.isEmpty() || projected == null) {
            return null;
        }
        Object rawTrails = calculationMemoryMeta.get("trails");
        if (!(rawTrails instanceof List<?> trailList) || trailList.isEmpty()) {
            return null;
        }

        Set<String> hiddenSet = toLowerSet(projected.hiddenFields());
        Set<String> maskedSet = toLowerSet(projected.maskedForAiFields());

        Map<String, Object> uiContext = projected.uiRecord();
        Map<String, Object> safeContext = projected.modelSafeRecord();

        String titleExpr = asString(calculationMemoryMeta.get("titleExpr"));
        String titleFallback = asString(calculationMemoryMeta.get("title"));
        String subtitleExpr = asString(calculationMemoryMeta.get("subtitleExpr"));
        String subtitleFallback = asString(calculationMemoryMeta.get("subtitle"));
        String legalBasisExpr = asString(calculationMemoryMeta.get("legalBasisExpr"));
        String legalBasisFallback = asString(calculationMemoryMeta.get("legalBasis"));

        String title = evalTextWithFallback(
                titleExpr, titleFallback, uiContext, hiddenSet, maskedSet, false);
        String subtitle = evalTextWithFallback(
                subtitleExpr, subtitleFallback, uiContext, hiddenSet, maskedSet, false);
        String defaultLegalBasis = evalTextWithFallback(
                legalBasisExpr, legalBasisFallback, uiContext, hiddenSet, maskedSet, false);

        String modelSafeTitle = evalTextWithFallback(
                titleExpr, titleFallback, safeContext, hiddenSet, maskedSet, true);
        String modelSafeSubtitle = evalTextWithFallback(
                subtitleExpr, subtitleFallback, safeContext, hiddenSet, maskedSet, true);
        String modelSafeLegalBasis = evalTextWithFallback(
                legalBasisExpr, legalBasisFallback, safeContext, hiddenSet, maskedSet, true);

        String icon = firstNonBlank(asString(calculationMemoryMeta.get("icon")), "calculate");
        String emptyText = asString(calculationMemoryMeta.get("emptyText"));

        List<EvaluatedTrail> uiTrails = new ArrayList<>();
        List<EvaluatedTrail> modelSafeTrails = new ArrayList<>();

        for (Object item : trailList) {
            if (!(item instanceof Map<?, ?> rawTrail)) {
                continue;
            }
            Map<String, Object> trail = normalizeMap(rawTrail);
            String visibleWhen = asString(trail.get("visibleWhen"));
            if (StringUtils.hasText(visibleWhen)) {
                EvalOutcome vis = evaluateExpr(visibleWhen, uiContext);
                if (!isTruthy(vis.value())) {
                    continue;
                }
            }

            EvaluatedTrail uiTrail = evaluateTrail(trail, defaultLegalBasis, uiContext, hiddenSet, maskedSet, false);
            EvaluatedTrail safeTrail = evaluateTrail(trail, modelSafeLegalBasis, safeContext, hiddenSet, maskedSet, true);
            uiTrails.add(uiTrail);
            modelSafeTrails.add(safeTrail);
        }

        if (uiTrails.isEmpty() && !StringUtils.hasText(emptyText)) {
            return null;
        }

        String safeRecordTitle = sanitizeModelText(recordTitle);
        return new AgentEvaluatedCalculationMemory(
                entity != null ? entity.entityKey() : null,
                entity != null ? entity.resourceKey() : null,
                entity != null ? entity.resourcePath() : null,
                entityId,
                safeRecordTitle,
                title,
                subtitle,
                icon,
                defaultLegalBasis,
                emptyText,
                modelSafeTitle,
                modelSafeSubtitle,
                modelSafeLegalBasis,
                uiTrails,
                modelSafeTrails,
                projected.maskedForAiFields());
    }

    private static EvaluatedTrail evaluateTrail(
            Map<String, Object> trail,
            String defaultLegalBasis,
            Map<String, Object> context,
            Set<String> hiddenFields,
            Set<String> maskedFields,
            boolean strictModelMode) {

        String id = asString(trail.get("id"));
        String title = evalTextWithFallback(
                asString(trail.get("titleExpr")),
                asString(trail.get("title")),
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);
        String ruleCode = evalTextWithFallback(
                asString(trail.get("ruleCodeExpr")),
                asString(trail.get("ruleCode")),
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);
        String formula = evalTextWithFallback(
                asString(trail.get("formulaExpr")),
                asString(trail.get("formula")),
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);
        String legalBasis = evalTextWithFallback(
                asString(trail.get("legalBasisExpr")),
                firstNonBlank(asString(trail.get("legalBasis")), defaultLegalBasis),
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);
        String legalPurpose = evalTextWithFallback(
                asString(trail.get("legalPurposeExpr")),
                asString(trail.get("legalPurpose")),
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);

        List<EvaluatedStep> steps = evaluateSteps(trail, context, hiddenFields, maskedFields, strictModelMode);

        String resultLabel = firstNonBlank(asString(trail.get("resultLabel")), "Resultado");
        String resultExpr = asString(trail.get("resultExpr"));
        String resultFormat = firstNonBlank(asString(trail.get("resultFormat")), "currency:BRL");
        String resultTone = firstNonBlank(asString(trail.get("resultTone")), "primary");
        String resultHint = evalTextWithFallback(
                asString(trail.get("resultHintExpr")),
                asString(trail.get("resultHint")),
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);

        EvaluatedValue resultEval = evaluateValue(
                resultExpr,
                trail.get("resultValue"),
                resultFormat,
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);

        String resultFormatted = resultEval.formatted();
        if (!StringUtils.hasText(resultExpr) && trail.get("resultValue") == null && !steps.isEmpty()) {
            for (int i = steps.size() - 1; i >= 0; i--) {
                if ("result".equalsIgnoreCase(steps.get(i).role())) {
                    resultFormatted = steps.get(i).formattedValue();
                    break;
                }
            }
        }

        return new EvaluatedTrail(
                id,
                title,
                ruleCode,
                formula,
                legalBasis,
                legalPurpose,
                resultLabel,
                resultFormatted,
                resultTone,
                resultHint,
                steps);
    }

    private static List<EvaluatedStep> evaluateSteps(
            Map<String, Object> trail,
            Map<String, Object> context,
            Set<String> hiddenFields,
            Set<String> maskedFields,
            boolean strictModelMode) {

        Object rawSteps = trail.get("steps");
        if (rawSteps instanceof List<?> list && !list.isEmpty()) {
            List<EvaluatedStep> resolved = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Map<?, ?> rawStep)) {
                    continue;
                }
                Map<String, Object> step = normalizeMap(rawStep);
                String role = firstNonBlank(asString(step.get("role")), asString(step.get("kind")));
                role = role != null ? role.trim().toLowerCase(Locale.ROOT) : "custom";
                String defaultOp = i == 0 ? "" : switch (role) {
                    case "factor" -> "×";
                    case "cap" -> "≤";
                    case "target" -> "≥";
                    case "deduction" -> "−";
                    case "addition" -> "+";
                    case "result" -> "=";
                    default -> "→";
                };
                String expr = firstNonBlank(asString(step.get("valueExpr")), asString(step.get("expr")));
                String format = firstNonBlank(asString(step.get("format")), "currency:BRL");
                EvaluatedValue val = evaluateValue(
                        expr,
                        step.get("value"),
                        format,
                        context,
                        hiddenFields,
                        maskedFields,
                        strictModelMode);
                String hint = evalTextWithFallback(
                        asString(step.get("hintExpr")),
                        asString(step.get("hint")),
                        context,
                        hiddenFields,
                        maskedFields,
                        strictModelMode);
                resolved.add(new EvaluatedStep(
                        role,
                        firstNonBlank(asString(step.get("label")), role),
                        val.formatted(),
                        val.rawValue(),
                        format,
                        firstNonBlank(asString(step.get("operator")), defaultOp),
                        hint,
                        asString(step.get("legalBasis"))));
            }
            return resolved;
        }

        List<EvaluatedStep> steps = new ArrayList<>();
        appendFixedStep(steps, "base", "", "Base de Cálculo",
                asString(trail.get("baseLabel")),
                asString(trail.get("baseExpr")),
                trail.get("baseValue"),
                firstNonBlank(asString(trail.get("baseFormat")), "currency:BRL"),
                asString(trail.get("baseHint")),
                asString(trail.get("baseHintExpr")),
                context, hiddenFields, maskedFields, strictModelMode);

        appendFixedStep(steps, "addition", "+", "Adições / Acréscimos",
                asString(trail.get("additionLabel")),
                asString(trail.get("additionExpr")),
                trail.get("additionValue"),
                firstNonBlank(asString(trail.get("additionFormat")), "currency:BRL"),
                asString(trail.get("additionHint")),
                asString(trail.get("additionHintExpr")),
                context, hiddenFields, maskedFields, strictModelMode);

        appendFixedStep(steps, "deduction", "−", "Deduções Legais",
                asString(trail.get("deductionLabel")),
                asString(trail.get("deductionExpr")),
                trail.get("deductionValue"),
                firstNonBlank(asString(trail.get("deductionFormat")), "currency:BRL"),
                asString(trail.get("deductionHint")),
                asString(trail.get("deductionHintExpr")),
                context, hiddenFields, maskedFields, strictModelMode);

        appendFixedStep(steps, "factor", "×", "Fator / Índice",
                asString(trail.get("factorLabel")),
                asString(trail.get("factorExpr")),
                trail.get("factorValue"),
                firstNonBlank(asString(trail.get("factorFormat")), "decimal"),
                asString(trail.get("factorHint")),
                asString(trail.get("factorHintExpr")),
                context, hiddenFields, maskedFields, strictModelMode);

        appendFixedStep(steps, "cap", firstNonBlank(asString(trail.get("capOperator")), "≤"), "Teto / Limite",
                asString(trail.get("capLabel")),
                asString(trail.get("capExpr")),
                trail.get("capValue"),
                firstNonBlank(asString(trail.get("capFormat")), "currency:BRL"),
                asString(trail.get("capHint")),
                asString(trail.get("capHintExpr")),
                context, hiddenFields, maskedFields, strictModelMode);

        appendFixedStep(steps, "target", "≥", "Meta / Requisito",
                asString(trail.get("targetLabel")),
                asString(trail.get("targetExpr")),
                trail.get("targetValue"),
                firstNonBlank(asString(trail.get("targetFormat")), "integer"),
                asString(trail.get("targetHint")),
                asString(trail.get("targetHintExpr")),
                context, hiddenFields, maskedFields, strictModelMode);

        appendFixedStep(steps, "result", "=", "Resultado",
                asString(trail.get("resultLabel")),
                asString(trail.get("resultExpr")),
                trail.get("resultValue"),
                firstNonBlank(asString(trail.get("resultFormat")), "currency:BRL"),
                asString(trail.get("resultHint")),
                asString(trail.get("resultHintExpr")),
                context, hiddenFields, maskedFields, strictModelMode);

        return steps;
    }

    private static void appendFixedStep(
            List<EvaluatedStep> steps,
            String role,
            String operator,
            String defaultLabel,
            String label,
            String expr,
            Object staticValue,
            String format,
            String hint,
            String hintExpr,
            Map<String, Object> context,
            Set<String> hiddenFields,
            Set<String> maskedFields,
            boolean strictModelMode) {
        if (!StringUtils.hasText(expr) && staticValue == null && !StringUtils.hasText(label)) {
            return;
        }
        EvaluatedValue resolved = evaluateValue(
                expr,
                staticValue,
                format,
                context,
                hiddenFields,
                maskedFields,
                strictModelMode);
        String resolvedHint = evalTextWithFallback(hintExpr, hint, context, hiddenFields, maskedFields, strictModelMode);
        steps.add(new EvaluatedStep(
                role,
                firstNonBlank(label, defaultLabel),
                resolved.formatted(),
                resolved.rawValue(),
                format,
                operator,
                resolvedHint,
                null));
    }

    private static String evalTextWithFallback(
            String expr,
            String fallback,
            Map<String, Object> context,
            Set<String> hiddenFields,
            Set<String> maskedFields,
            boolean strictModelMode) {
        if (StringUtils.hasText(expr)) {
            EvalOutcome outcome = evaluateExpr(expr, context);
            if (strictModelMode) {
                if (touchesAny(outcome.accessedFields(), hiddenFields)) {
                    return "[OMITIDO POR GOVERNANCA]";
                }
                if (touchesAny(outcome.accessedFields(), maskedFields)) {
                    return "[PROTEGIDO]";
                }
            }
            if (outcome.value() != null && StringUtils.hasText(outcome.value().toString())) {
                String text = outcome.value().toString().trim();
                return strictModelMode ? sanitizeModelText(text) : text;
            }
        }
        return strictModelMode ? sanitizeModelText(fallback) : fallback;
    }

    private static EvaluatedValue evaluateValue(
            String expr,
            Object staticValue,
            String format,
            Map<String, Object> context,
            Set<String> hiddenFields,
            Set<String> maskedFields,
            boolean strictModelMode) {
        Object rawVal = staticValue;
        if (StringUtils.hasText(expr)) {
            EvalOutcome outcome = evaluateExpr(expr, context);
            if (strictModelMode) {
                if (touchesAny(outcome.accessedFields(), hiddenFields)) {
                    return new EvaluatedValue(null, "[OMITIDO POR GOVERNANCA]");
                }
                if (touchesAny(outcome.accessedFields(), maskedFields)) {
                    return new EvaluatedValue(null, "[PROTEGIDO]");
                }
            }
            if (outcome.evaluated()) {
                rawVal = outcome.value();
            }
        }
        if (strictModelMode && rawVal instanceof String str) {
            rawVal = sanitizeModelText(str);
        }
        String formatted = formatValue(rawVal, format);
        if (strictModelMode) {
            formatted = sanitizeModelText(formatted);
            if (rawVal instanceof Map<?, ?> || rawVal instanceof Collection<?>) {
                rawVal = null;
            }
        }
        return new EvaluatedValue(rawVal, formatted);
    }

    static String formatValue(Object rawVal, String format) {
        if (rawVal == null) {
            return "—";
        }
        if (rawVal instanceof String s && s.isBlank()) {
            return "—";
        }
        String fmt = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (fmt.isEmpty() || "text".equals(fmt)) {
            return String.valueOf(rawVal);
        }
        if ("boolean".equals(fmt)) {
            return isTruthy(rawVal) ? "Sim" : "Não";
        }
        if ("date".equals(fmt)) {
            LocalDate date = coerceLocalDate(rawVal);
            return date != null ? PT_BR_DATE.format(date) : String.valueOf(rawVal);
        }
        BigDecimal num = coerceNumber(rawVal);
        if (num == null) {
            return String.valueOf(rawVal);
        }
        if (fmt.startsWith("currency")) {
            String currencyCode = "BRL";
            int colon = fmt.indexOf(':');
            if (colon >= 0 && colon + 1 < fmt.length()) {
                currencyCode = fmt.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
            }
            NumberFormat nf = NumberFormat.getCurrencyInstance(PT_BR);
            try {
                nf.setCurrency(Currency.getInstance(currencyCode));
            } catch (IllegalArgumentException ignored) {
                nf.setCurrency(Currency.getInstance("BRL"));
            }
            return nf.format(num).replace('\u00A0', ' ');
        }
        if ("percent".equals(fmt) || "percentage".equals(fmt)) {
            BigDecimal stripped = num.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                DecimalFormat df = new DecimalFormat("#,##0", PT_BR_SYMBOLS);
                return df.format(stripped) + "%";
            }
            DecimalFormat df = new DecimalFormat("#,##0.##", PT_BR_SYMBOLS);
            return df.format(num) + "%";
        }
        if ("integer".equals(fmt)) {
            BigDecimal rounded = num.setScale(0, RoundingMode.HALF_UP);
            DecimalFormat df = new DecimalFormat("#,##0", PT_BR_SYMBOLS);
            return df.format(rounded);
        }
        if ("days".equals(fmt)) {
            long rounded = num.setScale(0, RoundingMode.HALF_UP).longValue();
            DecimalFormat df = new DecimalFormat("#,##0", PT_BR_SYMBOLS);
            return df.format(rounded) + (Math.abs(rounded) == 1 ? " dia" : " dias");
        }
        if ("decimal".equals(fmt) || fmt.startsWith("decimal:")) {
            int digits = parsePrecisionDigits(fmt, 2);
            DecimalFormat df = new DecimalFormat(decimalPattern(digits, true), PT_BR_SYMBOLS);
            return df.format(num);
        }
        if ("number".equals(fmt) || fmt.startsWith("number:")) {
            int digits = parsePrecisionDigits(fmt, 2);
            DecimalFormat df = new DecimalFormat(decimalPattern(digits, false), PT_BR_SYMBOLS);
            return df.format(num);
        }
        return String.valueOf(rawVal);
    }

    private static String decimalPattern(int digits, boolean fixed) {
        if (digits <= 0) {
            return "#,##0";
        }
        char ch = fixed ? '0' : '#';
        StringBuilder sb = new StringBuilder("#,##0.");
        for (int i = 0; i < digits; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    private static int parsePrecisionDigits(String fmt, int fallback) {
        int colon = fmt.indexOf(':');
        if (colon < 0) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(fmt.substring(colon + 1).trim());
            return Math.max(0, Math.min(parsed, 10));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static EvalOutcome evaluateExpr(String rawExpr, Map<String, Object> record) {
        if (!StringUtils.hasText(rawExpr)) {
            return new EvalOutcome(false, null, Set.of());
        }
        String normalized = rawExpr.trim();
        if (normalized.startsWith("=")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isEmpty() || normalized.length() > MAX_EXPRESSION_LENGTH) {
            return new EvalOutcome(false, null, Set.of());
        }
        try {
            Parser parser = new Parser(tokenize(normalized), record == null ? Map.of() : record);
            Object val = parser.parseExpression();
            return new EvalOutcome(true, val, parser.accessedFields());
        } catch (RuntimeException ex) {
            return new EvalOutcome(false, null, Set.of());
        }
    }

    private static boolean touchesAny(Set<String> accessed, Set<String> governed) {
        if (accessed.isEmpty() || governed.isEmpty()) {
            return false;
        }
        for (String field : accessed) {
            if (governed.contains(field.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeModelText(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        List<TaxIdMatch> matches = Mod11IdentifierHintExtractor.extractValidTaxIds(value);
        if (matches.isEmpty()) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int cursor = 0;
        for (TaxIdMatch match : matches) {
            out.append(value, cursor, match.start());
            out.append(match.cpf() ? "[CPF PROTEGIDO]" : "[CNPJ PROTEGIDO]");
            cursor = match.end();
        }
        out.append(value, cursor, value.length());
        return out.toString();
    }

    private static Set<String> toLowerSet(List<String> items) {
        if (items == null || items.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String item : items) {
            if (StringUtils.hasText(item)) {
                set.add(item.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(set);
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null) {
                copy.put(k.toString(), v);
            }
        });
        return copy;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : (StringUtils.hasText(fallback) ? fallback.trim() : null);
    }

    private static boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            if (n instanceof BigDecimal bd) {
                return bd.compareTo(BigDecimal.ZERO) != 0;
            }
            return n.doubleValue() != 0.0d;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            return !trimmed.isEmpty() && !"false".equalsIgnoreCase(trimmed);
        }
        return true;
    }

    private static BigDecimal coerceNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
                return BigDecimal.valueOf(n.longValue());
            }
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate coerceLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (value instanceof Instant inst) {
            return inst.atZone(ZoneOffset.UTC).toLocalDate();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
                return LocalDate.parse(text.substring(0, 10));
            }
            return LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record EvaluatedValue(Object rawValue, String formatted) {
    }

    record EvalOutcome(boolean evaluated, Object value, Set<String> accessedFields) {
    }

    private enum TokenType {
        NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, LBRACKET, RBRACKET, DOT, COMMA, QUESTION, COLON, EOF
    }

    private record Token(TokenType type, String text) {
    }

    private static List<Token> tokenize(String expr) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = expr.length();
        while (i < len) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                while (i < len && expr.charAt(i) != quote) {
                    char ch = expr.charAt(i);
                    if (ch == '\\' && i + 1 < len) {
                        char next = expr.charAt(i + 1);
                        switch (next) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            default -> sb.append(next);
                        }
                        i += 2;
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                if (i >= len) {
                    throw new IllegalArgumentException("Unterminated string literal");
                }
                i++;
                tokens.add(new Token(TokenType.STRING, sb.toString()));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < len && Character.isDigit(expr.charAt(i))) {
                    i++;
                }
                if (i < len && expr.charAt(i) == '.') {
                    i++;
                    while (i < len && Character.isDigit(expr.charAt(i))) {
                        i++;
                    }
                }
                tokens.add(new Token(TokenType.NUMBER, expr.substring(start, i)));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < len && (Character.isJavaIdentifierPart(expr.charAt(i)))) {
                    i++;
                }
                tokens.add(new Token(TokenType.IDENT, expr.substring(start, i)));
                continue;
            }
            String three = i + 3 <= len ? expr.substring(i, i + 3) : "";
            if ("===".equals(three) || "!==".equals(three)) {
                tokens.add(new Token(TokenType.OP, three));
                i += 3;
                continue;
            }
            String two = i + 2 <= len ? expr.substring(i, i + 2) : "";
            if ("==".equals(two) || "!=".equals(two) || "<=".equals(two) || ">=".equals(two)
                    || "&&".equals(two) || "||".equals(two)) {
                tokens.add(new Token(TokenType.OP, two));
                i += 2;
                continue;
            }
            switch (c) {
                case '(' -> tokens.add(new Token(TokenType.LPAREN, "("));
                case ')' -> tokens.add(new Token(TokenType.RPAREN, ")"));
                case '[' -> tokens.add(new Token(TokenType.LBRACKET, "["));
                case ']' -> tokens.add(new Token(TokenType.RBRACKET, "]"));
                case '.' -> tokens.add(new Token(TokenType.DOT, "."));
                case ',' -> tokens.add(new Token(TokenType.COMMA, ","));
                case '?' -> tokens.add(new Token(TokenType.QUESTION, "?"));
                case ':' -> tokens.add(new Token(TokenType.COLON, ":"));
                case '+', '-', '*', '/', '%', '!', '<', '>' -> tokens.add(new Token(TokenType.OP, String.valueOf(c)));
                default -> throw new IllegalArgumentException("Unexpected char in expression: " + c);
            }
            i++;
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Object> record;
        private final Set<String> accessedFields = new LinkedHashSet<>();
        private int pos = 0;
        private boolean suppressEvaluation = false;

        Parser(List<Token> tokens, Map<String, Object> record) {
            this.tokens = tokens;
            this.record = record;
        }

        Set<String> accessedFields() {
            return accessedFields;
        }

        Token peek() {
            return tokens.get(Math.min(pos, tokens.size() - 1));
        }

        Token next() {
            Token current = peek();
            if (pos < tokens.size() - 1) {
                pos++;
            }
            return current;
        }

        boolean matchOp(String op) {
            Token t = peek();
            if (t.type() == TokenType.OP && op.equals(t.text())) {
                next();
                return true;
            }
            return false;
        }

        boolean match(TokenType type) {
            if (peek().type() == type) {
                next();
                return true;
            }
            return false;
        }

        Object parseExpression() {
            return parseTernary();
        }

        private Object parseTernary() {
            Object cond = parseOr();
            if (match(TokenType.QUESTION)) {
                boolean takeTrue = isTruthy(cond);
                boolean prevSuppress = suppressEvaluation;
                Object whenTrue;
                try {
                    suppressEvaluation = prevSuppress || !takeTrue;
                    whenTrue = parseExpression();
                } finally {
                    suppressEvaluation = prevSuppress;
                }
                if (!match(TokenType.COLON)) {
                    throw new IllegalArgumentException("Expected ':' in ternary");
                }
                Object whenFalse;
                try {
                    suppressEvaluation = prevSuppress || takeTrue;
                    whenFalse = parseExpression();
                } finally {
                    suppressEvaluation = prevSuppress;
                }
                return takeTrue ? whenTrue : whenFalse;
            }
            return cond;
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (matchOp("||")) {
                boolean takeRight = !isTruthy(left);
                boolean prevSuppress = suppressEvaluation;
                Object right;
                try {
                    suppressEvaluation = prevSuppress || !takeRight;
                    right = parseAnd();
                } finally {
                    suppressEvaluation = prevSuppress;
                }
                left = takeRight ? right : left;
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseEquality();
            while (matchOp("&&")) {
                boolean takeRight = isTruthy(left);
                boolean prevSuppress = suppressEvaluation;
                Object right;
                try {
                    suppressEvaluation = prevSuppress || !takeRight;
                    right = parseEquality();
                } finally {
                    suppressEvaluation = prevSuppress;
                }
                left = takeRight ? right : left;
            }
            return left;
        }

        private Object parseEquality() {
            Object left = parseRelational();
            while (true) {
                if (matchOp("===") || matchOp("==")) {
                    Object right = parseRelational();
                    left = valuesEqual(left, right);
                } else if (matchOp("!==") || matchOp("!=")) {
                    Object right = parseRelational();
                    left = !valuesEqual(left, right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseRelational() {
            Object left = parseAdditive();
            while (true) {
                if (matchOp("<")) {
                    left = compare(left, parseAdditive()) < 0;
                } else if (matchOp("<=")) {
                    left = compare(left, parseAdditive()) <= 0;
                } else if (matchOp(">")) {
                    left = compare(left, parseAdditive()) > 0;
                } else if (matchOp(">=")) {
                    left = compare(left, parseAdditive()) >= 0;
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseAdditive() {
            Object left = parseMultiplicative();
            while (true) {
                if (matchOp("+")) {
                    Object right = parseMultiplicative();
                    if (left instanceof String || right instanceof String) {
                        left = (left == null ? "" : left.toString()) + (right == null ? "" : right.toString());
                    } else {
                        BigDecimal a = toZeroableDecimal(left);
                        BigDecimal b = toZeroableDecimal(right);
                        left = a.add(b);
                    }
                } else if (matchOp("-")) {
                    Object right = parseMultiplicative();
                    left = toZeroableDecimal(left).subtract(toZeroableDecimal(right));
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseMultiplicative() {
            Object left = parseUnary();
            while (true) {
                if (matchOp("*")) {
                    Object right = parseUnary();
                    left = toZeroableDecimal(left).multiply(toZeroableDecimal(right));
                } else if (matchOp("/")) {
                    BigDecimal b = coerceNumber(parseUnary());
                    if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
                        left = null;
                    } else {
                        left = toZeroableDecimal(left).divide(b, 8, RoundingMode.HALF_UP);
                    }
                } else if (matchOp("%")) {
                    BigDecimal b = coerceNumber(parseUnary());
                    if (b == null || b.compareTo(BigDecimal.ZERO) == 0) {
                        left = null;
                    } else {
                        left = toZeroableDecimal(left).remainder(b);
                    }
                } else {
                    break;
                }
            }
            return left;
        }

        private Object parseUnary() {
            if (matchOp("!")) {
                return !isTruthy(parseUnary());
            }
            if (matchOp("-")) {
                BigDecimal n = coerceNumber(parseUnary());
                return n == null ? null : n.negate();
            }
            if (matchOp("+")) {
                return coerceNumber(parseUnary());
            }
            return parsePostfix();
        }

        private Object parsePostfix() {
            Object target = parsePrimary();
            List<String> rootSegments = target instanceof RootPlaceholder rp ? rp.segments : null;
            Object current = target instanceof RootPlaceholder ? record : target;
            String pathPrefix = null;
            while (true) {
                if (match(TokenType.DOT)) {
                    Token prop = peek();
                    if (prop.type() != TokenType.IDENT) {
                        throw new IllegalArgumentException("Expected property identifier after '.'");
                    }
                    next();
                    if (rootSegments != null) {
                        if (rootSegments.size() == 1) {
                            String first = rootSegments.get(0);
                            if (isContextAlias(first)) {
                                pathPrefix = prop.text();
                                if (!suppressEvaluation) {
                                    accessedFields.add(pathPrefix);
                                    current = record.get(pathPrefix);
                                } else {
                                    current = null;
                                }
                            } else {
                                pathPrefix = first + "." + prop.text();
                                if (!suppressEvaluation) {
                                    accessedFields.add(first);
                                    accessedFields.add(prop.text());
                                    accessedFields.add(pathPrefix);
                                    Object base = record.get(first);
                                    current = readMember(base, prop.text());
                                } else {
                                    current = null;
                                }
                            }
                        } else {
                            pathPrefix = pathPrefix + "." + prop.text();
                            if (!suppressEvaluation) {
                                accessedFields.add(prop.text());
                                accessedFields.add(pathPrefix);
                                current = readMember(current, prop.text());
                            } else {
                                current = null;
                            }
                        }
                        rootSegments.add(prop.text());
                    } else {
                        current = suppressEvaluation ? null : readMember(current, prop.text());
                    }
                } else if (match(TokenType.LBRACKET)) {
                    Object indexVal = parseExpression();
                    if (!match(TokenType.RBRACKET)) {
                        throw new IllegalArgumentException("Expected ']'");
                    }
                    current = suppressEvaluation ? null : readIndex(current, indexVal);
                    rootSegments = null;
                    pathPrefix = null;
                } else {
                    break;
                }
            }
            if (target instanceof RootPlaceholder rp && rp.segments.size() == 1) {
                String name = rp.segments.get(0);
                if (!isContextAlias(name)) {
                    if (!suppressEvaluation) {
                        accessedFields.add(name);
                        return record.get(name);
                    }
                    return null;
                }
                return suppressEvaluation ? null : record;
            }
            return current;
        }

        private Object parsePrimary() {
            Token t = peek();
            if (match(TokenType.LPAREN)) {
                Object val = parseExpression();
                if (!match(TokenType.RPAREN)) {
                    throw new IllegalArgumentException("Expected ')'");
                }
                return val;
            }
            if (t.type() == TokenType.NUMBER) {
                next();
                return new BigDecimal(t.text());
            }
            if (t.type() == TokenType.STRING) {
                next();
                return t.text();
            }
            if (t.type() == TokenType.IDENT) {
                next();
                String ident = t.text();
                if ("true".equals(ident)) {
                    return Boolean.TRUE;
                }
                if ("false".equals(ident)) {
                    return Boolean.FALSE;
                }
                if ("null".equals(ident) || "undefined".equals(ident)) {
                    return null;
                }
                if (match(TokenType.LPAREN)) {
                    if ("if".equals(ident)) {
                        Object cond = peek().type() != TokenType.RPAREN ? parseExpression() : null;
                        boolean condTruthy = isTruthy(cond);
                        Object whenTrue = null;
                        Object whenFalse = null;
                        boolean prevSuppress = suppressEvaluation;
                        if (match(TokenType.COMMA)) {
                            try {
                                suppressEvaluation = prevSuppress || !condTruthy;
                                whenTrue = parseExpression();
                            } finally {
                                suppressEvaluation = prevSuppress;
                            }
                            if (match(TokenType.COMMA)) {
                                try {
                                    suppressEvaluation = prevSuppress || condTruthy;
                                    whenFalse = parseExpression();
                                } finally {
                                    suppressEvaluation = prevSuppress;
                                }
                                while (match(TokenType.COMMA)) {
                                    try {
                                        suppressEvaluation = true;
                                        parseExpression();
                                    } finally {
                                        suppressEvaluation = prevSuppress;
                                    }
                                }
                            }
                        }
                        if (!match(TokenType.RPAREN)) {
                            throw new IllegalArgumentException("Expected ')' after args");
                        }
                        return condTruthy ? whenTrue : whenFalse;
                    }
                    List<Object> args = new ArrayList<>();
                    if (peek().type() != TokenType.RPAREN) {
                        do {
                            args.add(parseExpression());
                        } while (match(TokenType.COMMA));
                    }
                    if (!match(TokenType.RPAREN)) {
                        throw new IllegalArgumentException("Expected ')' after args");
                    }
                    return suppressEvaluation ? null : invokeFunction(ident, args);
                }
                return new RootPlaceholder(ident);
            }
            throw new IllegalArgumentException("Unexpected token: " + t.text());
        }

        private Object invokeFunction(String name, List<Object> args) {
            return switch (name) {
                case "coalesce" -> {
                    for (Object arg : args) {
                        if (arg != null && (!(arg instanceof String s) || !s.isBlank())) {
                            yield arg;
                        }
                    }
                    yield null;
                }
                case "if" -> {
                    Object cond = !args.isEmpty() ? args.get(0) : null;
                    yield isTruthy(cond)
                            ? (args.size() > 1 ? args.get(1) : null)
                            : (args.size() > 2 ? args.get(2) : null);
                }
                case "abs" -> {
                    BigDecimal v = args.isEmpty() ? null : coerceNumber(args.get(0));
                    yield v == null ? null : v.abs();
                }
                case "min" -> {
                    BigDecimal best = null;
                    for (Object arg : args) {
                        BigDecimal d = coerceNumber(arg);
                        if (d != null && (best == null || d.compareTo(best) < 0)) {
                            best = d;
                        }
                    }
                    yield best;
                }
                case "max" -> {
                    BigDecimal best = null;
                    for (Object arg : args) {
                        BigDecimal d = coerceNumber(arg);
                        if (d != null && (best == null || d.compareTo(best) > 0)) {
                            best = d;
                        }
                    }
                    yield best;
                }
                case "round" -> {
                    BigDecimal v = args.isEmpty() ? null : coerceNumber(args.get(0));
                    int scale = args.size() > 1 && coerceNumber(args.get(1)) != null
                            ? Math.max(0, coerceNumber(args.get(1)).intValue())
                            : 0;
                    yield v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
                }
                case "ceil" -> {
                    BigDecimal v = args.isEmpty() ? null : coerceNumber(args.get(0));
                    yield v == null ? null : v.setScale(0, RoundingMode.CEILING);
                }
                case "floor", "trunc" -> {
                    BigDecimal v = args.isEmpty() ? null : coerceNumber(args.get(0));
                    yield v == null ? null : v.setScale(0, RoundingMode.FLOOR);
                }
                case "len" -> {
                    if (args.isEmpty() || args.get(0) == null) {
                        yield 0;
                    }
                    Object v = args.get(0);
                    if (v instanceof String s) {
                        yield s.length();
                    }
                    if (v instanceof Collection<?> c) {
                        yield c.size();
                    }
                    if (v instanceof Map<?, ?> m) {
                        yield m.size();
                    }
                    yield 0;
                }
                case "toNumber" -> args.isEmpty() ? null : coerceNumber(args.get(0));
                case "toString" -> args.isEmpty() || args.get(0) == null ? "" : args.get(0).toString();
                case "now" -> LocalDate.now();
                case "date" -> args.isEmpty() ? null : coerceLocalDate(args.get(0));
                case "yearsSince" -> {
                    LocalDate d = args.isEmpty() ? null : coerceLocalDate(args.get(0));
                    yield d == null ? null : BigDecimal.valueOf(Period.between(d, LocalDate.now()).getYears());
                }
                case "monthsSince" -> {
                    LocalDate d = args.isEmpty() ? null : coerceLocalDate(args.get(0));
                    yield d == null ? null : BigDecimal.valueOf(ChronoUnit.MONTHS.between(d, LocalDate.now()));
                }
                case "daysSince" -> {
                    LocalDate d = args.isEmpty() ? null : coerceLocalDate(args.get(0));
                    yield d == null ? null : BigDecimal.valueOf(ChronoUnit.DAYS.between(d, LocalDate.now()));
                }
                default -> throw new IllegalArgumentException("Unsupported function: " + name);
            };
        }

        private static Object readMember(Object target, String prop) {
            if (target == null) {
                return null;
            }
            if (target instanceof Map<?, ?> map) {
                return map.get(prop);
            }
            if ("length".equals(prop) || "size".equals(prop)) {
                if (target instanceof String s) {
                    return s.length();
                }
                if (target instanceof Collection<?> c) {
                    return c.size();
                }
            }
            return null;
        }

        private static Object readIndex(Object target, Object indexObj) {
            BigDecimal idxNum = coerceNumber(indexObj);
            if (target instanceof List<?> list && idxNum != null) {
                int idx = idxNum.intValue();
                return idx >= 0 && idx < list.size() ? list.get(idx) : null;
            }
            if (target instanceof Map<?, ?> map && indexObj != null) {
                return map.get(indexObj.toString());
            }
            return null;
        }

        private static boolean isContextAlias(String ident) {
            return "row".equals(ident) || "item".equals(ident) || "data".equals(ident) || "record".equals(ident);
        }

        private static BigDecimal toZeroableDecimal(Object value) {
            BigDecimal d = coerceNumber(value);
            return d != null ? d : BigDecimal.ZERO;
        }

        private static boolean valuesEqual(Object left, Object right) {
            if (left == null && right == null) {
                return true;
            }
            if (left == null || right == null) {
                return false;
            }
            BigDecimal lNum = coerceNumber(left);
            BigDecimal rNum = coerceNumber(right);
            if (lNum != null && rNum != null) {
                return lNum.compareTo(rNum) == 0;
            }
            return left.equals(right) || left.toString().equals(right.toString());
        }

        private static int compare(Object left, Object right) {
            BigDecimal lNum = coerceNumber(left);
            BigDecimal rNum = coerceNumber(right);
            if (lNum != null && rNum != null) {
                return lNum.compareTo(rNum);
            }
            if (left == null || right == null) {
                return left == null ? -1 : 1;
            }
            return left.toString().compareTo(right.toString());
        }

        private static final class RootPlaceholder {
            final List<String> segments = new ArrayList<>();

            RootPlaceholder(String first) {
                segments.add(first);
            }
        }
    }
}
