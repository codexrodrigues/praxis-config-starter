package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Extracts provider retry guidance from structured response metadata without retaining raw bodies. */
final class AiProviderRetryAfter {

    private static final String RETRY_INFO_TYPE = "type.googleapis.com/google.rpc.RetryInfo";
    private static final String QUOTA_FAILURE_TYPE = "type.googleapis.com/google.rpc.QuotaFailure";

    private AiProviderRetryAfter() {
    }

    static Instant fromHeaders(
            List<String> retryAfterMillisValues,
            List<String> retryAfterValues,
            Instant now) {
        Instant reference = now != null ? now : Instant.now();
        Instant milliseconds = firstMillis(retryAfterMillisValues, reference);
        Instant standard = firstRetryAfter(retryAfterValues, reference);
        return latest(milliseconds, standard);
    }

    static GoogleFailureMetadata fromGoogleErrorBody(
            ObjectMapper objectMapper,
            String responseBody,
            Instant now) {
        Instant reference = now != null ? now : Instant.now();
        if (objectMapper == null || responseBody == null || responseBody.isBlank()) {
            return new GoogleFailureMetadata("unknown", null);
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            boolean quotaFailure = false;
            Instant retryAfter = null;
            JsonNode details = error.path("details");
            if (details.isArray()) {
                for (JsonNode detail : details) {
                    String type = detail.path("@type").asText("");
                    if (QUOTA_FAILURE_TYPE.equals(type)) {
                        quotaFailure = true;
                    }
                    if (RETRY_INFO_TYPE.equals(type)) {
                        retryAfter = latest(retryAfter, durationAfter(detail.path("retryDelay"), reference));
                    }
                }
            }
            String status = safeToken(error.path("status").asText("unknown"));
            return new GoogleFailureMetadata(quotaFailure ? "quota_exhausted" : status, retryAfter);
        } catch (Exception ignored) {
            return new GoogleFailureMetadata("unknown", null);
        }
    }

    private static Instant firstMillis(List<String> values, Instant now) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            try {
                BigDecimal milliseconds = new BigDecimal(value.trim());
                if (milliseconds.signum() >= 0) {
                    return plusMillis(now, milliseconds.setScale(0, RoundingMode.CEILING).longValueExact());
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Try the next structured header value.
            }
        }
        return null;
    }

    private static Instant firstRetryAfter(List<String> values, Instant now) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = value != null ? value.trim() : "";
            if (normalized.isEmpty()) {
                continue;
            }
            try {
                BigDecimal seconds = new BigDecimal(normalized);
                if (seconds.signum() >= 0) {
                    long milliseconds = seconds.movePointRight(3)
                            .setScale(0, RoundingMode.CEILING)
                            .longValueExact();
                    return plusMillis(now, milliseconds);
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                try {
                    return ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                } catch (DateTimeParseException ignoredDate) {
                    // Try the next structured header value.
                }
            }
        }
        return null;
    }

    private static Instant durationAfter(JsonNode duration, Instant now) {
        if (duration == null || duration.isMissingNode() || duration.isNull()) {
            return null;
        }
        try {
            BigDecimal seconds;
            if (duration.isTextual()) {
                String value = duration.asText().trim();
                if (!value.endsWith("s") || value.length() == 1) {
                    return null;
                }
                seconds = new BigDecimal(value.substring(0, value.length() - 1));
            } else if (duration.isObject()) {
                seconds = new BigDecimal(duration.path("seconds").asText("0"))
                        .add(new BigDecimal(duration.path("nanos").asText("0")).movePointLeft(9));
            } else {
                return null;
            }
            if (seconds.signum() < 0) {
                return null;
            }
            long milliseconds = seconds.movePointRight(3)
                    .setScale(0, RoundingMode.CEILING)
                    .longValueExact();
            return plusMillis(now, milliseconds);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant plusMillis(Instant now, long milliseconds) {
        try {
            return now.plusMillis(milliseconds);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static Instant latest(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String token = value.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase(Locale.ROOT);
        return token.length() <= 80 ? token : token.substring(0, 80);
    }

    record GoogleFailureMetadata(String reason, Instant retryAfter) {
    }
}
