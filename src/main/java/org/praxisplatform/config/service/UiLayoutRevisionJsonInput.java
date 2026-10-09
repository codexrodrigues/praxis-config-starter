package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

/** Shared closed revision parser for Java entry and HTTP decoder, without host mapper inheritance. */
final class UiLayoutRevisionJsonInput {
  private static final ObjectMapper DOCUMENT = mapper(UiLayoutJsonBounds.MAX_DEPTH);
  private UiLayoutRevisionJsonInput() {}

  static ObjectMapper mapper(int depth) {
    var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    for (var feature : JsonReadFeature.values()) mapper.getFactory().disable(feature.mappedFeature());
    mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(depth)
        .maxStringLength(UiLayoutJsonBounds.MAX_INPUT_TEXT_BYTES).maxNumberLength(256).build());
    return mapper;
  }

  /** Closed JSON encoding preserves explicit nulls independently of host defaults. */
  static String writeDocument(JsonNode document, Runnable checkpoint) {
    UiLayoutJsonBounds.requireDocument(document, Code.INVALID_REQUEST, checkpoint);
    try {
      checkpoint.run();
      String encoded = DOCUMENT.writeValueAsString(document);
      checkpoint.run();
      return encoded;
    } catch (UiLayoutLifecycleException known) { throw known; }
    catch (Exception failure) {
      throw new UiLayoutLifecycleException(Code.INVALID_REQUEST, "Revision JSON document is invalid.");
    }
  }

  static ObjectNode readDocument(String text, Runnable checkpoint) {
    UiLayoutJsonBounds.requireInputText(text, Code.INVALID_REQUEST, checkpoint);
    try {
      checkpoint.run();
      var value = DOCUMENT.readTree(text);
      checkpoint.run();
      UiLayoutJsonBounds.requireDocument(value, Code.INVALID_REQUEST, checkpoint);
      return (ObjectNode) value;
    } catch (UiLayoutLifecycleException known) { throw known; }
    catch (Exception failure) {
      throw new UiLayoutLifecycleException(Code.INVALID_REQUEST, "Revision JSON document is invalid.");
    }
  }
}
