package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Closed P0 page profile. Port/icon policy and provenance are separately admitted by the host. */
final class UiLayoutPageRevisionReproduction {
  static final String KIND = "praxis.dynamic-page.editor";
  static final String TYPE = "praxis-dynamic-page";
  static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchema SCHEMA = schema();
  private UiLayoutPageRevisionReproduction() {}

  private static JsonSchema schema() {
    try (var input = UiLayoutPageRevisionReproduction.class.getResourceAsStream("/org/praxisplatform/config/page-publication.schema.json")) {
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(input));
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }

  static void requireNative(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor) {
    if (!KIND.equals(descriptor.documentType()) || !TYPE.equals(target.componentType())
        || !target.componentId().matches(UUID_PATTERN) || !"1".equals(descriptor.schemaVersion())
        || !"praxis.ui-layout/v1".equals(patchDescriptor.schemaVersion())) throw invalid();
    var before = children(baseline);
    var after = children(candidate);
    UiLayoutRevisionRelation.requireUnchangedEnvelope((ObjectNode) baseline, (ObjectNode) candidate);
    for (var entry : after.entrySet()) {
      var child = entry.getValue();
      var original = before.get(entry.getKey());
      requireChild(original == null ? child : original, child, entry.getKey());
    }
  }

  static void require(UiLayoutTarget target, UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline, JsonNode candidate, UiLayoutPatchDocumentDescriptor patchDescriptor, JsonNode patch) {
    if (!KIND.equals(descriptor.documentType())) return;
    requireNative(target, descriptor, baseline, candidate, patchDescriptor);
    if (patch == null || !patch.isObject()) throw invalid();
    UiLayoutRevisionRelation.requireReproduction((ObjectNode) baseline.get("config"), (ObjectNode) candidate.get("config"), (ObjectNode) patch);
  }

  /** Native documents reconstructed without defaults. Values, absence and null remain distinct. */
  static Map<String, ObjectNode> children(JsonNode page) {
    UiLayoutJsonBounds.requireDocument(page, UiLayoutLifecycleException.Code.VALIDATION_FAILED, () -> {});
    if (!SCHEMA.validate(page).isEmpty()) throw invalid();
    var result = new LinkedHashMap<String, ObjectNode>();
    for (var widget : page.at("/config/definition/widgets")) {
      String key = widget.path("key").asText();
      ObjectNode nativeDocument = nativeChild(widget);
      if (result.putIfAbsent(key, nativeDocument) != null) throw invalid();
      requireChild(nativeDocument, nativeDocument, key);
    }
    unique(page.at("/config/navigation"));
    JsonNode links = page.at("/config/definition/composition/links");
    unique(links);
    for (var link : links) {
      if (!result.containsKey(link.at("/from/ref/widget").asText())
          || !result.containsKey(link.at("/to/ref/widget").asText())) throw invalid();
    }
    return result;
  }

  static ObjectNode nativeChild(JsonNode widget) {
    boolean form = "praxis-dynamic-form".equals(widget.at("/definition/id").asText());
    JsonNode inputs = widget.at("/definition/inputs");
    ObjectNode nativeDocument = JSON.createObjectNode().put("kind", form ? "praxis.dynamic-form.editor" : "praxis.table.editor").put("version", 1);
    nativeDocument.set("config", inputs.get("config").deepCopy());
    var bindings = JSON.createObjectNode();
    for (var field : form ? new String[]{"mode", "emptyState", "presentationModeGlobal"} : new String[]{"resourcePath", "horizontalScroll"}) copy(inputs, bindings, field);
    if (!bindings.isEmpty()) nativeDocument.set("bindings", bindings);
    if (form) {
      var context = JSON.createObjectNode();
      for (var field : new String[]{"backConfig", "presentation"}) copy(inputs, context, field);
      var preferences = JSON.createObjectNode();
      for (var field : new String[]{"notifyIfOutdated", "snoozeMs", "autoOpenSettingsOnOutdated"}) copy(inputs, preferences, field);
      if (!preferences.isEmpty()) context.set("schemaPrefs", preferences);
      if (!context.isEmpty()) nativeDocument.set("contextSnapshot", context);
    }
    return nativeDocument;
  }

  static void requireChild(JsonNode baseline, JsonNode candidate, String key) {
    var descriptor = new UiLayoutAuthoringDocumentDescriptor(candidate.path("kind").asText(), "praxis:page-child-native", "1");
    var patch = new UiLayoutPatchDocumentDescriptor("praxis:page-child-patch", "praxis.ui-layout/v1");
    var target = new UiLayoutTarget("praxis.table.editor".equals(descriptor.documentType()) ? "praxis-table" : "praxis-dynamic-form", key);
    var projection = UiLayoutAuthoringProjection.project(target, descriptor, baseline, candidate, patch, () -> {});
    UiLayoutRevisionRelation.requireReproduction(projection.baselineConfig(), projection.candidateConfig(), projection.compilePatch());
  }
  private static void copy(JsonNode input, ObjectNode output, String field) { if (input.has(field)) output.set(field, input.get(field).deepCopy()); }
  private static void unique(JsonNode array) {
    Set<String> keys = new HashSet<>();
    for (var value : array) if (!keys.add(value.path("id").asText())) throw invalid();
  }
  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "Page native document is outside the admitted profile or changes a child envelope.");
  }
}
