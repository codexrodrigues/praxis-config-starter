package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutAssignmentCommandRequest;
import org.praxisplatform.config.dto.UiLayoutFreezeReleaseRequest;
import org.praxisplatform.config.dto.UiLayoutHeadReleaseRequest;
import org.praxisplatform.config.dto.UiLayoutReasonRequest;
import org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest;

/** Per-endpoint strict JSON decoder; it does not alter global Jackson behavior. */
public class UiLayoutLifecycleRequestDecoder {
  /** Actual revision wire/text quota; native documents each retain their independent 256 KiB limit. */
  public static final int MAX_REVISION_BODY_BYTES = UiLayoutJsonBounds.MAX_INPUT_TEXT_BYTES;
  private final ObjectMapper strictMapper;
  private final ObjectMapper revisionMapper = UiLayoutRevisionJsonInput.mapper(UiLayoutJsonBounds.MAX_DEPTH + 1);
  public UiLayoutLifecycleRequestDecoder(ObjectMapper objectMapper) {
    // The command boundary must not inherit host lexical leniency or custom DTO deserializers.
    strictMapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
    for (JsonReadFeature feature : JsonReadFeature.values()) {
      strictMapper.getFactory().disable(feature.mappedFeature());
    }
  }
  public <T> T decode(String body, Class<T> type) {
    try {
      boolean revision = type == UiLayoutRevisionCommandRequest.class;
      if (revision) UiLayoutJsonBounds.requireInputText(body, UiLayoutLifecycleException.Code.INVALID_REQUEST, () -> {});
      ObjectMapper mapper = revision ? revisionMapper : strictMapper;
      JsonNode tree = mapper.readTree(body);
      if (tree == null || !tree.isObject()) throw invalid();
      if (revision) UiLayoutJsonBounds.requireTree(tree, MAX_REVISION_BODY_BYTES, UiLayoutJsonBounds.MAX_DEPTH + 1,
          UiLayoutLifecycleException.Code.INVALID_REQUEST, () -> {});
      validate((ObjectNode) tree, type);
      T request = mapper.treeToValue(tree, type);
      if (request == null) throw invalid();
      return request;
    } catch (UiLayoutLifecycleException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid();
    }
  }

  private void validate(ObjectNode body, Class<?> type) {
    if (type == UiLayoutRevisionCommandRequest.class) {
      uuid(body, "commandRef");
      target(body, "target");
      UiLayoutJsonBounds.requireDocument(object(body, "authoringDocument"), UiLayoutLifecycleException.Code.INVALID_REQUEST, () -> {});
      text(body, "reason");
    } else if (type == UiLayoutAssignmentCommandRequest.class) {
      uuid(body, "commandRef");
      target(body, "target");
      text(body, "layerClass");
      selector(body, "selector");
      shortInteger(body, "priority");
      text(body, "contributionKey");
    } else if (type == UiLayoutFreezeReleaseRequest.class) {
      uuid(body, "commandRef");
    } else if (type == UiLayoutHeadReleaseRequest.class) {
      uuid(body, "releaseId");
      text(body, "reason");
    } else if (type == UiLayoutReasonRequest.class) {
      text(body, "reason");
    }
  }

  private void target(ObjectNode body, String name) {
    ObjectNode target = object(body, name);
    text(target, "componentType");
    text(target, "componentId");
  }

  private void selector(ObjectNode body, String name) {
    ObjectNode selector = object(body, name);
    text(selector, "tenant");
    for (String field : new String[] {"organization", "sector", "group", "profile", "user"}) {
      JsonNode value = selector.get(field);
      if (value != null && !value.isNull() && (!value.isTextual() || value.textValue().isBlank())) throw invalid();
    }
  }

  private ObjectNode object(ObjectNode body, String name) {
    JsonNode value = required(body, name);
    if (!value.isObject()) throw invalid();
    return (ObjectNode) value;
  }

  private void text(ObjectNode body, String name) {
    JsonNode value = required(body, name);
    if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
  }

  private void uuid(ObjectNode body, String name) {
    JsonNode value = required(body, name);
    if (!value.isTextual()) throw invalid();
    try {
      UUID.fromString(value.textValue());
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private void shortInteger(ObjectNode body, String name) {
    JsonNode value = required(body, name);
    if (!value.isIntegralNumber() || !value.canConvertToInt()
        || value.intValue() < Short.MIN_VALUE || value.intValue() > Short.MAX_VALUE) throw invalid();
  }

  private JsonNode required(ObjectNode body, String name) {
    JsonNode value = body.get(name);
    if (value == null || value.isNull()) throw invalid();
    return value;
  }

  private UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_REQUEST,
        "Lifecycle command body is invalid.");
  }
}
