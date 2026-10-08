package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The canonical resolution merge: null removes; objects merge; other values replace. */
final class UiLayoutMergePatch {
  private UiLayoutMergePatch() {}

  static void apply(ObjectNode target, ObjectNode patch) {
    patch.properties().forEach(entry -> {
      String field = entry.getKey();
      JsonNode value = entry.getValue();
      if (value.isNull()) {
        target.remove(field);
      } else if (value.isObject()) {
        JsonNode existing = target.get(field);
        ObjectNode child = existing != null && existing.isObject()
            ? (ObjectNode) existing : target.objectNode();
        apply(child, (ObjectNode) value);
        target.set(field, child);
      } else {
        target.set(field, value.deepCopy());
      }
    });
  }
}
