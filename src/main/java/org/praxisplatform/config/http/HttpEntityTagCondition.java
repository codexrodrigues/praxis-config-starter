package org.praxisplatform.config.http;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser canonico para headers condicionais baseados em entity-tag publicados por
 * {@code /api/praxis/config/ui}.
 */
public final class HttpEntityTagCondition {

  private final boolean wildcard;
  private final List<EntityTag> validators;

  private HttpEntityTagCondition(boolean wildcard, List<EntityTag> validators) {
    this.wildcard = wildcard;
    this.validators = List.copyOf(validators);
  }

  public static HttpEntityTagCondition parse(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return new HttpEntityTagCondition(false, List.of());
    }

    String trimmed = headerValue.trim();
    if ("*".equals(trimmed)) {
      return new HttpEntityTagCondition(true, List.of());
    }

    List<String> parts = splitValidators(trimmed);
    if (parts.isEmpty()) {
      throw invalidHeader();
    }

    List<EntityTag> validators = new ArrayList<>();
    for (String part : parts) {
      String token = part.trim();
      if (token.isEmpty() || "*".equals(token)) {
        throw invalidHeader();
      }

      boolean weak = false;
      if (token.regionMatches(true, 0, "W/", 0, 2)) {
        weak = true;
        token = token.substring(2).trim();
      }

      if (token.length() < 2 || token.charAt(0) != '"' || token.charAt(token.length() - 1) != '"') {
        throw invalidHeader();
      }

      String value = token.substring(1, token.length() - 1);
      if (value.indexOf('"') >= 0) {
        throw invalidHeader();
      }
      validators.add(new EntityTag(value, weak));
    }

    return new HttpEntityTagCondition(false, validators);
  }

  public boolean matchesWeak(String currentEtag) {
    if (currentEtag == null) {
      return false;
    }
    if (wildcard) {
      return true;
    }
    return validators.stream().anyMatch(validator -> validator.value().equals(currentEtag));
  }

  public boolean matchesStrong(String currentEtag) {
    if (currentEtag == null) {
      return false;
    }
    if (wildcard) {
      return true;
    }
    return validators.stream()
        .filter(validator -> !validator.weak())
        .anyMatch(validator -> validator.value().equals(currentEtag));
  }

  public boolean isEmpty() {
    return !wildcard && validators.isEmpty();
  }

  public boolean wildcard() {
    return wildcard;
  }

  public List<EntityTag> validators() {
    return validators;
  }

  private static List<String> splitValidators(String headerValue) {
    List<String> parts = new ArrayList<>();
    boolean inQuotes = false;
    int start = 0;

    for (int i = 0; i < headerValue.length(); i++) {
      char ch = headerValue.charAt(i);
      if (ch == '"') {
        inQuotes = !inQuotes;
      } else if (ch == ',' && !inQuotes) {
        parts.add(headerValue.substring(start, i));
        start = i + 1;
      }
    }

    if (inQuotes) {
      throw invalidHeader();
    }

    parts.add(headerValue.substring(start));
    return parts;
  }

  private static IllegalArgumentException invalidHeader() {
    return new IllegalArgumentException(
        "Invalid ETag condition header. Use *, quoted entity tags, or comma-separated quoted entity tags.");
  }

  public record EntityTag(String value, boolean weak) {}
}
