package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CanonicalJsonHashService {
    private final ObjectMapper objectMapper;

    public String sha256(Object value) {
        try {
            JsonNode canonical = canonicalize(objectMapper.valueToTree(value));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(writeCanonicalJson(canonical).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot calculate canonical JSON hash.", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.stream()
                    .filter(name -> node.get(name) != null && !node.get(name).isNull())
                    .forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node;
    }

    /**
     * Writes the canonical tree using ECMAScript-compatible finite-number
     * formatting so browser and server attest the same JSON value.
     */
    private String writeCanonicalJson(JsonNode node) throws Exception {
        if (node.isObject()) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!first) result.append(',');
                result.append(objectMapper.writeValueAsString(field.getKey()))
                        .append(':')
                        .append(writeCanonicalJson(field.getValue()));
                first = false;
            }
            return result.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) result.append(',');
                result.append(writeCanonicalJson(node.get(index)));
            }
            return result.append(']').toString();
        }
        if (node.isNumber()) {
            return writeCanonicalNumber(node.doubleValue());
        }
        return objectMapper.writeValueAsString(node);
    }

    private String writeCanonicalNumber(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Canonical JSON does not support non-finite numbers.");
        }
        if (value == 0d) return "0";

        BigDecimal decimal = shortestRoundTripDecimal(value);
        int exponent = decimal.precision() - decimal.scale() - 1;
        if (exponent >= 21 || exponent <= -7) {
            String digits = decimal.unscaledValue().abs().toString();
            StringBuilder result = new StringBuilder();
            if (value < 0d) result.append('-');
            result.append(digits.charAt(0));
            if (digits.length() > 1) {
                result.append('.').append(digits, 1, digits.length());
            }
            result.append('e');
            if (exponent >= 0) result.append('+');
            return result.append(exponent).toString();
        }
        return decimal.toPlainString();
    }

    private BigDecimal shortestRoundTripDecimal(double value) {
        BigDecimal exact = new BigDecimal(value);
        long expectedBits = Double.doubleToLongBits(value);
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal candidate = exact
                    .round(new MathContext(precision, RoundingMode.HALF_EVEN))
                    .stripTrailingZeros();
            if (Double.doubleToLongBits(candidate.doubleValue()) == expectedBits) {
                return candidate;
            }
        }
        return BigDecimal.valueOf(value).stripTrailingZeros();
    }
}
