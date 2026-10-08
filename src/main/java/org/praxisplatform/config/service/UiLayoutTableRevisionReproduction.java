package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Native document correlation, additional to host policy; not schema runtime/provenance attestation. */
final class UiLayoutTableRevisionReproduction {
  private UiLayoutTableRevisionReproduction() {}

  static void require(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patch) {
    if (!"praxis.table.editor".equals(descriptor.documentType())) return;
    requireNative(target, descriptor, baseline, candidate, patchDescriptor);
    if (patch == null || !patch.isObject()) invalid();
    UiLayoutRevisionRelation.requireReproduction((ObjectNode) baseline.get("config"), (ObjectNode) candidate.get("config"), (ObjectNode) patch);
  }

  static void requireNative(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor) {
    if (!"praxis.table.editor".equals(descriptor.documentType())
        || !"praxis-table".equals(target.componentType()) || !"1".equals(descriptor.schemaVersion())
        || !"praxis.ui-layout/v1".equals(patchDescriptor.schemaVersion())) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Table native revision descriptor is unsupported.");
    }
    nativeDocument(baseline);
    nativeDocument(candidate);
    UiLayoutRevisionRelation.requireUnchangedEnvelope((ObjectNode) baseline, (ObjectNode) candidate);
    JsonNode config = candidate.get("config");
    if (!config.path("columns").isArray() || !config.path("columns").isEmpty()) invalid();
    if (containsNull(config)) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
          "Literal null authoring cannot be represented by the current resolution patch.");
    }
  }

  private static void nativeDocument(JsonNode document) {
    if (document == null || !document.isObject() || !"praxis.table.editor".equals(document.path("kind").asText())
        || !document.path("version").isIntegralNumber() || !document.path("version").canConvertToInt() || document.path("version").asInt() != 1
        || !document.path("config").isObject()) invalid();
    JsonNode config = document.get("config");
    columns(config.path("columns"));
    if (!config.path("columns").isEmpty()) invalid();
    JsonNode projection = config.path("columnProjection");
    if (!projection.isObject() || !"schema".equals(projection.path("source").asText())) invalid();
    for (String key : Set.of("include", "order")) {
      if (projection.has(key)) names(projection.get(key));
    }
    if (projection.has("overrides")) {
      JsonNode overrides = projection.get("overrides");
      if (!overrides.isObject()) invalid();
      overrides.properties().forEach(entry -> {
        name(entry.getKey());
        if (!entry.getValue().isObject() || entry.getValue().has("field")) invalid();
      });
    }
    if (projection.has("additions")) columns(projection.get("additions"));
  }

  private static void names(JsonNode array) {
    if (!array.isArray()) invalid();
    Set<String> identities = new HashSet<>();
    for (JsonNode value : array) {
      if (!value.isTextual()) invalid();
      name(value.asText());
      if (!identities.add(value.asText())) invalid();
    }
  }

  private static void columns(JsonNode array) {
    if (!array.isArray()) invalid();
    Set<String> identities = new HashSet<>();
    for (JsonNode column : array) {
      if (!column.isObject() || !column.path("field").isTextual()) invalid();
      String field = column.path("field").asText();
      name(field);
      if (!identities.add(field)) invalid();
    }
  }

  private static void name(String name) {
    if (name.isBlank() || !name.equals(name.trim())) invalid();
  }

  private static boolean containsNull(JsonNode node) {
    if (node.isNull()) return true;
    for (JsonNode child : node) if (containsNull(child)) return true;
    return false;
  }

  private static void invalid() {
    throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Table native revision does not preserve authored presence or reproduce its candidate.");
  }
}
