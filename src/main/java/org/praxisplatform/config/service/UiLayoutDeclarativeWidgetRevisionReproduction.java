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
        List.of()),
    ORGANOGRAM(
        "praxis-organogram",
        "praxis.organogram.editor",
        false,
        List.of(
            "config",
            "nodes",
            "topology",
            "hierarchyType",
            "includeExtinct",
            "selectedSubtreeRoot",
            "rootScopeLabel",
            "synthesizePolymorphicNodes"),
        List.of("config", "nodes", "resourcePath"),
        List.of("resourcePath", "queryContext")),
    PROGRESSION_LADDER(
        "praxis-progression-ladder",
        "praxis.progression-ladder.editor",
        false,
        List.of(
            "document",
            "variant",
            "showVariantSwitcher",
            "showFinancialDetails",
            "showIntersticeRunway",
            "showBridgeBanner",
            "selectedStepId"),
        List.of("document"),
        List.of()),
    CRUD(
        "praxis-crud",
        "praxis.crud.editor",
        false,
        List.of(
            "metadata",
            "showResourceIdentityKey",
            "filterTeleport",
            "disableAutoDrawer",
            "hostManagedChrome"),
        List.of("metadata"),
        List.of(
            "crudId",
            "componentInstanceId",
            "tableConfigPersistenceStrategy",
            "context",
            "filterCriteria",
            "authoringCapability")),
    FILTER(
        "praxis-filter",
        "praxis.filter.editor",
        false,
        List.of(
            "presentation",
            "fieldMetadata",
            "value",
            "alwaysVisibleFields",
            "selectedFieldIds",
            "changeDebounceMs",
            "tags",
            "allowSaveTags",
            "placeBooleansInActions",
            "advancedOpenMode",
            "showFilterSettings",
            "showAdvancedButton",
            "showAddButton",
            "showClearButton",
            "showSearchButton",
            "disablePersistence"),
        List.of("presentation", "fieldMetadata", "resourcePath"),
        List.of(
            "resourcePath",
            "endpointKey",
            "apiUrlEntry",
            "filterId",
            "formId",
            "componentInstanceId",
            "persistenceKey")),
    ANALYTICS_PRESENTATION(
        "praxis-analytics-presentation",
        "praxis.analytics-presentation.editor",
        false,
        List.of(
            "analytics",
            "availableFamilies",
            "preferredFamily",
            "title",
            "subtitle",
            "height",
            "motion",
            "surfaceMode",
            "controlsPlacement",
            "controlsDisplay",
            "chartDocumentOverrides",
            "coerceCrossFilters"),
        List.of("analytics", "resourcePath"),
        List.of("projectionId", "resourcePath", "filterOperation", "schemaType", "queryContext")),
    FILES_UPLOAD(
        "praxis-files-upload",
        "praxis.files-upload.editor",
        false,
        List.of("config", "displayMode"),
        List.of("config"),
        List.of("filesUploadId", "componentInstanceId", "baseUrl", "context")),
    RELATED_RESOURCE_OUTLET(
        "praxis-related-resource-outlet",
        "praxis.related-resource-outlet.editor",
        false,
        List.of(
            "presentation",
            "title",
            "subtitle",
            "icon",
            "tableConfig",
            "emptyState",
            "mode",
            "compact",
            "strictValidation"),
        List.of("surfaceId", "surface", "tableConfig", "parentResourcePath"),
        List.of(
            "surfaceId",
            "surface",
            "parentRecord",
            "parentResourceId",
            "parentIdentity",
            "parentResourcePath",
            "apiEndpointKey",
            "apiUrlEntry",
            "queryContext",
            "tableId",
            "authoringCapability",
            "ownerWidgetKey")),
    CRON_BUILDER(
        "pdx-cron-builder",
        "praxis.cron-builder.editor",
        false,
        List.of("metadata", "label", "hint", "presets", "enableSeconds"),
        List.of("metadata", "presets"),
        List.of()),
    MANUAL_FORM(
        "praxis-manual-form",
        "praxis.manual-form.editor",
        false,
        List.of(
            "formTitle",
            "formDescription",
            "showHeader",
            "showActions",
            "enableAutoSave",
            "autoSaveDebounceMs",
            "showAiAssistant",
            "usePathNames"),
        List.of("formId", "persistenceOptions"),
        List.of("formId", "persistenceOptions")),
    EDITORIAL_FORM_RUNTIME(
        "praxis-editorial-form-runtime",
        "praxis.editorial-form-runtime.editor",
        false,
        List.of("solution", "instance", "hostConfig"),
        List.of("solution", "instance"),
        List.of("runtimeContext")),
    VISUAL_BUILDER(
        "praxis-visual-builder",
        "praxis.visual-builder.editor",
        false,
        List.of("config", "initialRules"),
        List.of("config", "initialRules"),
        List.of()),
    TABLE_RULE_BUILDER(
        "praxis-table-rule-builder",
        "praxis.table-rule-builder.editor",
        false,
        List.of("scope", "value", "labels"),
        List.of("scope", "value"),
        List.of()),
    MICRO_VISUALIZATION(
        "praxis-micro-visualization",
        "praxis.micro-visualization.editor",
        false,
        List.of("visualization", "fallbackText", "interactiveContainer"),
        List.of("visualization"),
        List.of()),
    HERO_BANNER(
        "widget:hero-banner",
        "praxis.hero-banner.editor",
        false,
        List.of(
            "ariaLabel",
            "brandText",
            "title",
            "titleAccent",
            "subtitle",
            "description",
            "imageUrl",
            "imageAlt",
            "badges",
            "metaItems",
            "visualSummary",
            "variant",
            "appearance"),
        List.of("title", "brandText", "visualSummary"),
        List.of("instanceId", "analyticsId")),
    FOOTER_LINKS(
        "widget:footer-links",
        "praxis.footer-links.editor",
        false,
        List.of("ariaLabel", "brandText", "secondaryText", "links", "layout", "appearance"),
        List.of("links", "brandText", "secondaryText"),
        List.of("instanceId", "analyticsId")),
    LEGAL_NOTICE(
        "widget:legal-notice",
        "praxis.legal-notice.editor",
        false,
        List.of(
            "ariaLabel",
            "title",
            "subtitle",
            "contentFormat",
            "content",
            "severity",
            "appearance",
            "links"),
        List.of("content", "title"),
        List.of("instanceId", "analyticsId")),
    RICH_TEXT_BLOCK(
        "widget:rich-text-block",
        "praxis.rich-text-block.editor",
        false,
        List.of(
            "ariaLabel",
            "title",
            "subtitle",
            "icon",
            "variant",
            "appearance",
            "contentFormat",
            "content"),
        List.of("content", "title"),
        List.of("instanceId", "analyticsId")),
    USER_CONTEXT_SUMMARY(
        "form:user-context-summary",
        "praxis.user-context-summary.editor",
        false,
        List.of("ariaLabel", "title", "subtitle", "appearance", "fields", "actionLabel"),
        List.of("fields", "title"),
        List.of("instanceId", "analyticsId", "source", "context", "actionId"));

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

  private static final Map<String, Spec> BY_COMPONENT_TYPE;
  private static final Map<String, Spec> BY_DOCUMENT_TYPE;

  static {
    Map<String, Spec> byComponent = new java.util.LinkedHashMap<>();
    Map<String, Spec> byDocument = new java.util.LinkedHashMap<>();
    for (Spec spec : Spec.values()) {
      byComponent.put(spec.componentType, spec);
      byDocument.put(spec.documentType, spec);
    }
    BY_COMPONENT_TYPE = Map.copyOf(byComponent);
    BY_DOCUMENT_TYPE = Map.copyOf(byDocument);
  }

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
      JsonNode bindings = document.path("bindings");
      boolean hasRequiredPayload =
          spec.requiredAnyOfFields.stream()
              .anyMatch(
                  field -> {
                    JsonNode entry = config.get(field);
                    if (hasRequiredValue(entry)) {
                      return true;
                    }
                    if (spec.bindingFields.contains(field)) {
                      return hasRequiredValue(bindings.get(field));
                    }
                    return false;
                  });
      if (!hasRequiredPayload) {
        throw invalid();
      }
    }
  }

  private static boolean hasRequiredValue(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return false;
    }
    if (node.isObject() || node.isArray()) {
      return true;
    }
    return node.isTextual() && !node.asText().isBlank();
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
