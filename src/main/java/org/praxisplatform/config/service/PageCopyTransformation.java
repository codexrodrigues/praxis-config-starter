package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure P0 transformation; source authority and durable evidence belong to the creation transaction.
 */
final class PageCopyTransformation {
  static final String TRANSFORM = "praxis.page-copy/1";
  private final CanonicalJsonHashService hashes;

  PageCopyTransformation(CanonicalJsonHashService hashes) {
    this.hashes = hashes;
  }

  Result allocate(JsonNode source, UUID sourcePageId, UUID pageId, String title) {
    UiLayoutPageRevisionReproduction.children(source);
    Set<String> occupied = sourceIds(source, sourcePageId.toString());
    if (!occupied.add(pageId.toString())) throw invalid();
    var map = new ArrayList<Identity>();
    for (var widget : source.at("/config/definition/widgets"))
      map.add(new Identity("widget", widget.path("key").asText(), next(occupied)));
    for (var link : source.at("/config/definition/composition/links"))
      map.add(new Identity("link", link.path("id").asText(), next(occupied)));
    return reproduce(source, sourcePageId.toString(), pageId.toString(), title, map);
  }

  Result reproduce(
      JsonNode source,
      String sourcePageId,
      String pageId,
      String title,
      List<Identity> identities) {
    UiLayoutPageRevisionReproduction.children(source);
    if (!uuid(sourcePageId) || !uuid(pageId) || identities == null) throw invalid();
    Set<String> occupied = sourceIds(source, sourcePageId);
    if (!occupied.add(pageId)) throw invalid();
    var expected = new HashSet<Key>();
    for (var widget : source.at("/config/definition/widgets"))
      expected.add(new Key("widget", widget.path("key").asText()));
    for (var link : source.at("/config/definition/composition/links"))
      expected.add(new Key("link", link.path("id").asText()));
    Map<Key, String> mapped = new LinkedHashMap<>();
    for (var entry : identities) {
      if (entry == null) throw invalid();
      Key key = new Key(entry.kind(), entry.from());
      if (!expected.contains(key)
          || !uuid(entry.to())
          || !occupied.add(entry.to())
          || mapped.putIfAbsent(key, entry.to()) != null) throw invalid();
    }
    if (!mapped.keySet().equals(expected)) throw invalid();
    ObjectNode output = source.deepCopy();
    for (var widget : output.at("/config/definition/widgets"))
      ((ObjectNode) widget).put("key", mapped.get(new Key("widget", widget.path("key").asText())));
    for (var link : output.at("/config/definition/composition/links")) {
      ((ObjectNode) link).put("id", mapped.get(new Key("link", link.path("id").asText())));
      for (String end : List.of("from", "to")) {
        var ref = (ObjectNode) link.path(end).path("ref");
        ref.put("widget", mapped.get(new Key("widget", ref.path("widget").asText())));
      }
    }
    ((ObjectNode) output.at("/config/presentation")).put("title", title);
    ((ObjectNode) output.path("config")).putArray("navigation");
    UiLayoutPageRevisionReproduction.children(output);
    return new Result(
        output, hashes.sha256Exact(source), hashes.sha256Exact(output), List.copyOf(identities));
  }

  private static Set<String> sourceIds(JsonNode source, String pageId) {
    var result = new HashSet<String>();
    result.add(pageId);
    for (var widget : source.at("/config/definition/widgets"))
      result.add(widget.path("key").asText());
    for (var link : source.at("/config/definition/composition/links"))
      result.add(link.path("id").asText());
    return result;
  }

  private static String next(Set<String> occupied) {
    String value;
    do {
      value = UUID.randomUUID().toString();
    } while (!occupied.add(value));
    return value;
  }

  private static boolean uuid(String value) {
    return value != null && value.matches(UiLayoutPageRevisionReproduction.UUID_PATTERN);
  }

  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Page copy identity or document is invalid.");
  }

  record Identity(String kind, String from, String to) {}

  private record Key(String kind, String id) {}

  record Result(
      JsonNode document, String sourceHash, String outputHash, List<Identity> identityMap) {
    Result {
      document = document.deepCopy();
      identityMap = List.copyOf(identityMap);
    }

    @Override
    public JsonNode document() {
      return document.deepCopy();
    }

    @Override
    public String toString() {
      return "PageCopyResult[content redacted]";
    }
  }
}
