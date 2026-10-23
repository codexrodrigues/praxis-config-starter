package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;

/** Untrusted position only. No MAC, authority grant, private identity vector or server cursor state. */
record PageRuntimeCursor(String binding, String view, Key last) {
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static PageRuntimeCursor decode(String token) {
    if (token == null) return null;
    try {
      if (token.isEmpty() || token.length() > 4096 || !token.matches("[A-Za-z0-9_-]+")) throw invalid();
      JsonNode value = JSON.readTree(Base64.getUrlDecoder().decode(token));
      if (!value.isObject() || value.size() != 4 || !value.has("version") || !value.has("binding")
          || !value.has("view") || !value.has("last") || !value.path("version").isInt()
          || value.path("version").intValue() != 1) throw invalid();
      String binding = digest(value.get("binding"));
      String view = digest(value.get("view"));
      JsonNode last = value.get("last");
      if (!last.isArray() || last.size() != 3 || !last.get(0).isIntegralNumber()
          || !last.get(0).canConvertToInt() || !last.get(1).isTextual() || !last.get(2).isTextual()
          || last.get(1).textValue().length() > 255 || last.get(2).textValue().length() > 255) throw invalid();
      return new PageRuntimeCursor(binding, view,
          new Key(last.get(0).intValue(), last.get(1).textValue(), last.get(2).textValue()));
    } catch (RuntimeException | java.io.IOException failure) { throw invalid(); }
  }

  String encode() {
    try {
      var value = JSON.createObjectNode().put("version", 1).put("binding", binding).put("view", view);
      value.putArray("last").add(last.order()).add(last.id()).add(last.page());
      return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(value));
    } catch (java.io.IOException failure) { throw invalid(); }
  }

  void requireBinding(String expected) {
    if (!binding.equals(expected)) throw stale();
  }

  void requireView(String actual, boolean foundLast) {
    if (!view.equals(actual)) throw stale();
    if (!foundLast) throw invalid();
  }

  private static String digest(JsonNode value) {
    if (!value.isTextual() || !value.textValue().matches("[0-9a-f]{64}")) throw invalid();
    return value.textValue();
  }

  record Key(int order, String id, String page) implements Comparable<Key> {
    @Override public int compareTo(Key other) {
      int value = Integer.compare(order, other.order);
      if (value == 0) value = id.compareTo(other.id);
      return value == 0 ? page.compareTo(other.page) : value;
    }
  }

  static UiLayoutResolutionException invalid() {
    return new UiLayoutResolutionException(UiLayoutResolutionException.Code.INVALID_PUBLICATION_CURSOR,
        "The publication cursor is invalid.", null);
  }
  static UiLayoutResolutionException stale() {
    return new UiLayoutResolutionException(UiLayoutResolutionException.Code.PUBLICATION_CONTINUATION_STALE,
        "The publication continuation is obsolete.", null);
  }
}
