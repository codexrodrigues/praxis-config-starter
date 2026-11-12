package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Native document correlation and envelope guards for governed declarative page widgets
 * ({@code PLAT-EVO-11}: chart, list, rich-content, tabs, stepper, expansion).
 */
final class UiLayoutDeclarativeWidgetRevisionReproduction {

  enum Spec {
    CHART(
        "praxis-chart",
        "praxis.chart.editor",
        false,
        List.of("config", "chartDocument"),
        List.of("config", "chartDocument"),
        List.of("resourceKey", "resourcePath", "projectionKey", "queryContext")),
    LIST(
        "praxis-list",
        "praxis.list.editor",
        true,
        List.of("config"),
        List.of("config"),
        List.of("resourcePath", "queryContext")),
    RICH_CONTENT(
        "praxis-rich-content",
        "praxis.rich-content.editor",
        false,
        List.of("config", "document"),
        List.of("config", "document"),
        List.of("presetId", "context")),
    TABS(
        "praxis-tabs",
        "praxis.tabs.editor",
        false,
        List.of("config", "tabs", "selectedIndex", "mode"),
        List.of("config", "tabs"),
        List.of()),
    STEPPER(
        "praxis-stepper",
        "praxis.stepper.editor",
        false,
        List.of("config", "steps", "linear", "orientation"),
        List.of("config", "steps"),
        List.of()),
    EXPANSION(
        "praxis-expansion",
        "praxis.expansion.editor",
        false,
        List.of("config", "panels", "multi"),
        List.of("config", "panels"),
        List.of());

    final String componentType;
    final String documentType;
    final boolean directConfigObject;
    final List<String> authoredFields;
    final List<String> requiredAnyOfFields;
    final List<String> bindingFields;

    Spec(
        String componentType,
        String documentType,
        boolean directConfigObject,
        List<String> authoredFields,
        List<String> requiredAnyOfFields,
        List<String> bindingFields) {
      this.componentType = componentType;
      this.documentType = documentType;
      this.directConfigObject = directConfigObject;
      this.authoredFields = authoredFields;
      this.requiredAnyOfFields = requiredAnyOfFields;
      this.bindingFields = bindingFields;
    }
  }

  private static final Map<String, Spec> BY_COMPONENT_TYPE =
      Map.of(
          Spec.CHART.componentType, Spec.CHART,
          Spec.LIST.componentType, Spec.LIST,
          Spec.RICH_CONTENT.componentType, Spec.RICH_CONTENT,
          Spec.TABS.componentType, Spec.TABS,
          Spec.STEPPER.componentType, Spec.STEPPER,
          Spec.EXPANSION.componentType, Spec.EXPANSION);

  private static final Map<String, Spec> BY_DOCUMENT_TYPE =
      Map.of(
          Spec.CHART.documentType, Spec.CHART,
          Spec.LIST.documentType, Spec.LIST,
          Spec.RICH_CONTENT.documentType, Spec.RICH_CONTENT,
          Spec.TABS.documentType, Spec.TABS,
          Spec.STEPPER.documentType, Spec.STEPPER,
          Spec.EXPANSION.documentType, Spec.EXPANSION);

  private UiLayoutDeclarativeWidgetRevisionReproduction() {}

  static Optional<Spec> findByComponentType(String componentType) {
    return Optional.ofNullable(BY_COMPONENT_TYPE.get(componentType));
  }

  static Optional<Spec> findByDocumentType(String documentType) {
    return Optional.ofNullable(BY_DOCUMENT_TYPE.get(documentType));
  }

  static String resolveComponentTypeForDocumentType(String documentType) {
    if ("praxis.dynamic-form.editor".equals(documentType)) {
      return "praxis-dynamic-form";
    }
    if ("praxis.table.editor".equals(documentType)) {
      return "praxis-table";
    }
    Spec spec = BY_DOCUMENT_TYPE.get(documentType);
    if (spec != null) {
      return spec.componentType;
    }
    throw new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
        "Unsupported page child authoring document type: " + documentType);
  }

  static ObjectNode buildNativeChild(ObjectMapper mapper, Spec spec, JsonNode inputs) {
    ObjectNode nativeDocument =
        mapper.createObjectNode().put("kind", spec.documentType).put("version", 1);
    if (spec.directConfigObject) {
      JsonNode cfg = inputs.get("config");
      if (cfg == null || !cfg.isObject()) {
        throw invalid();
      }
      nativeDocument.set("config", cfg.deepCopy());
    } else {
      ObjectNode authored = mapper.createObjectNode();
      for (String field : spec.authoredFields) {
        if (inputs.has(field)) {
          authored.set(field, inputs.get(field).deepCopy());
        }
      }
      nativeDocument.set("config", authored);
    }
    ObjectNode bindings = mapper.createObjectNode();
    for (String field : spec.bindingFields) {
      if (inputs.has(field)) {
        bindings.set(field, inputs.get(field).deepCopy());
      }
    }
    if (!bindings.isEmpty()) {
      nativeDocument.set("bindings", bindings);
    }
    return nativeDocument;
  }

  static void requireNative(
      UiLayoutTarget target,
      UiLayoutAuthoringDocumentDescriptor descriptor,
      JsonNode baseline,
      JsonNode candidate,
      UiLayoutPatchDocumentDescriptor patchDescriptor) {
    Spec spec = BY_DOCUMENT_TYPE.get(descriptor.documentType());
    if (spec == null
        || !spec.componentType.equals(target.componentType())
        || !"1".equals(descriptor.schemaVersion())
        || !"praxis.ui-layout/v1".equals(patchDescriptor.schemaVersion())) {
      throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE,
          "Declarative widget native revision descriptor is unsupported.");
    }
    validateDocument(spec, baseline);
    validateDocument(spec, candidate);
    UiLayoutRevisionRelation.requireUnchangedEnvelope((ObjectNode) baseline, (ObjectNode) candidate);
    if (hasMergedObjectNull(candidate.get("config"))) {
      throw new UiLayoutLifecycleException(
          UiLayoutLifecycleException.Code.VALIDATION_FAILED,
          "Literal null in a merged declarative widget object cannot be represented by the current resolution patch.");
    }
  }

  private static void validateDocument(Spec spec, JsonNode document) {
    if (document == null
        || !document.isObject()
        || !spec.documentType.equals(document.path("kind").asText())
        || !document.path("version").isIntegralNumber()
        || !document.path("version").canConvertToInt()
        || document.path("version").asInt() != 1
        || !document.path("config").isObject()) {
      throw invalid();
    }
    JsonNode config = document.get("config");
    if (!spec.directConfigObject) {
      boolean hasRequiredPayload =
          spec.requiredAnyOfFields.stream()
              .anyMatch(
                  field -> {
                    JsonNode entry = config.get(field);
                    return entry != null && (entry.isObject() || entry.isArray());
                  });
      if (!hasRequiredPayload) {
        throw invalid();
      }
    }
  }

  private static boolean hasMergedObjectNull(JsonNode node) {
    if (!node.isObject()) {
      return false;
    }
    for (JsonNode child : node) {
      if (child.isNull() || hasMergedObjectNull(child)) {
        return true;
      }
    }
    return false;
  }

  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(
        UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        "Declarative widget native revision does not preserve its envelope or reproduce its candidate.");
  }
}
