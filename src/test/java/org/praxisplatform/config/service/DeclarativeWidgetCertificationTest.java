package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class DeclarativeWidgetCertificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UiLayoutPatchDocumentDescriptor PATCH_DESC =
            new UiLayoutPatchDocumentDescriptor("praxis:test:patch", "praxis.ui-layout/v1");

    @Test
    void allDeclaredPraxisUiWidgetsAreCertifiedForNativeCaptureAuthoringAndPageCopy() {
        List<String> newlyPromotedWidgetTypes =
                List.of(
                        "praxis-crud",
                        "praxis-filter",
                        "praxis-analytics-presentation",
                        "praxis-files-upload",
                        "praxis-related-resource-outlet",
                        "pdx-cron-builder",
                        "praxis-manual-form",
                        "praxis-editorial-form-runtime",
                        "praxis-visual-builder",
                        "praxis-table-rule-builder",
                        "praxis-micro-visualization",
                        "widget:hero-banner",
                        "widget:footer-links",
                        "widget:legal-notice",
                        "widget:rich-text-block",
                        "form:user-context-summary",
                        "praxis-kpi-strip",
                        "praxis-kpi-card");

        Set<UiLayoutDeclarativeWidgetRevisionReproduction.Spec> coveredSpecs =
                EnumSet.noneOf(UiLayoutDeclarativeWidgetRevisionReproduction.Spec.class);

        for (String componentType : newlyPromotedWidgetTypes) {
            var specOpt =
                    UiLayoutDeclarativeWidgetRevisionReproduction.findByComponentType(componentType);
            assertThat(specOpt)
                    .as("Expected %s to be registered in declarative widget catalog", componentType)
                    .isPresent();

            UiLayoutDeclarativeWidgetRevisionReproduction.Spec spec = specOpt.get();
            coveredSpecs.add(spec);
            ObjectNode widgetConfig = sampleWidgetConfig(spec);
            ObjectNode nativeChild =
                    UiLayoutDeclarativeWidgetRevisionReproduction.buildNativeChild(
                            MAPPER, spec, widgetConfig);
            assertThat(nativeChild.get("kind").asText()).isEqualTo(spec.documentType);

            UiLayoutTarget target = new UiLayoutTarget(componentType, "widget-" + componentType);
            UiLayoutAuthoringDocumentDescriptor descriptor =
                    new UiLayoutAuthoringDocumentDescriptor(
                            spec.documentType, "praxis:widget:" + componentType, "1");

            ObjectNode candidateConfig = widgetConfig.deepCopy();
            candidateConfig.put("title", "Updated " + componentType);
            if (!candidateConfig.has("label")) {
                candidateConfig.put("label", "Updated " + componentType);
            }
            if (spec == UiLayoutDeclarativeWidgetRevisionReproduction.Spec.KPI_STRIP) {
                candidateConfig.withObject("config").put("title", "Updated KPI Strip");
            } else if (spec == UiLayoutDeclarativeWidgetRevisionReproduction.Spec.KPI_CARD) {
                candidateConfig
                        .withObject("config")
                        .withObject("presentation")
                        .put("label", "Updated KPI Card");
            }
            ObjectNode candidateDoc =
                    UiLayoutDeclarativeWidgetRevisionReproduction.buildNativeChild(
                            MAPPER, spec, candidateConfig);

            assertThatCode(
                            () ->
                                    UiLayoutDeclarativeWidgetRevisionReproduction.requireNative(
                                            target,
                                            descriptor,
                                            nativeChild,
                                            candidateDoc,
                                            PATCH_DESC))
                    .doesNotThrowAnyException();

            UiLayoutAuthoringProjection projection =
                    UiLayoutAuthoringProjection.project(
                            target,
                            descriptor,
                            nativeChild,
                            candidateDoc,
                            PATCH_DESC,
                            () -> {});
            assertThat(projection.compilePatch()).isNotNull();
        }

        assertThat(coveredSpecs).hasSize(newlyPromotedWidgetTypes.size());
        assertThat(UiLayoutDeclarativeWidgetRevisionReproduction.Spec.values()).hasSize(26);
    }

    @Test
    void pageCopyTransformationPreservesAndRemapsNewlyCertifiedWidgetComponents() {
        UUID sourcePageId = UUID.randomUUID();
        UUID copiedPageId = UUID.randomUUID();
        UUID crudWidgetKey = UUID.randomUUID();
        UUID heroWidgetKey = UUID.randomUUID();

        ObjectNode pageDoc = MAPPER.createObjectNode();
        pageDoc.put("kind", "praxis.dynamic-page.editor");
        pageDoc.put("version", 1);
        ObjectNode config = pageDoc.putObject("config");
        ObjectNode definition = config.putObject("definition");
        ArrayNode widgets = definition.putArray("widgets");

        ObjectNode crudWidget = widgets.addObject();
        crudWidget.put("key", crudWidgetKey.toString());
        ObjectNode crudDef = crudWidget.putObject("definition");
        crudDef.put("id", "praxis-crud");
        ObjectNode crudInputs = crudDef.putObject("inputs");
        crudInputs.put("crudId", "leave-requests-crud");
        crudInputs.putObject("metadata").put("resourcePath", "/api/workforce/leave-requests");

        ObjectNode heroWidget = widgets.addObject();
        heroWidget.put("key", heroWidgetKey.toString());
        ObjectNode heroDef = heroWidget.putObject("definition");
        heroDef.put("id", "widget:hero-banner");
        heroDef.putObject("inputs").put("title", "Workforce Portal");

        ObjectNode layout = definition.putObject("layout");
        layout.put("orientation", "columns");
        layout.put("columns", 1);
        layout.put("gap", "16px");
        layout.putObject("breakpoints").put("sm", 1);

        ObjectNode presentation = config.putObject("presentation");
        presentation.put("title", "Operational Hub");
        presentation.put("icon", "web");
        ArrayNode navigation = config.putArray("navigation");
        navigation
                .addObject()
                .put("id", UUID.randomUUID().toString())
                .put("groupId", UUID.randomUUID().toString())
                .put("label", "Operational Hub")
                .put("icon", "web")
                .put("order", 0);

        PageCopyTransformation transformation =
                new PageCopyTransformation(new CanonicalJsonHashService(MAPPER));
        PageCopyTransformation.Result allocated =
                transformation.allocate(
                        pageDoc, sourcePageId, copiedPageId, "Copied Operational Hub");
        JsonNode copiedDocument = allocated.document();
        JsonNode copiedWidgets = copiedDocument.at("/config/definition/widgets");
        assertThat(copiedWidgets.get(0).at("/definition/id").asText()).isEqualTo("praxis-crud");
        assertThat(copiedWidgets.get(1).at("/definition/id").asText())
                .isEqualTo("widget:hero-banner");
        assertThat(copiedWidgets.get(0).get("key").asText())
                .isNotEqualTo(crudWidgetKey.toString());
        assertThat(copiedDocument.at("/config/presentation/title").asText())
                .isEqualTo("Copied Operational Hub");
    }

    private static ObjectNode sampleWidgetConfig(UiLayoutDeclarativeWidgetRevisionReproduction.Spec spec) {
        if (spec == UiLayoutDeclarativeWidgetRevisionReproduction.Spec.KPI_STRIP
                || spec == UiLayoutDeclarativeWidgetRevisionReproduction.Spec.KPI_CARD) {
            ObjectNode card = MAPPER.createObjectNode();
            card.put("schemaVersion", 1);
            card.put("id", "11111111-1111-4111-8111-111111111111");
            ObjectNode presentation = card.putObject("presentation");
            presentation.put("label", "Active Headcount Share");
            presentation.put("icon", "groups");
            presentation.put("tone", "primary");
            presentation.put("format", "percent");
            ObjectNode source = card.putObject("source");
            source.put("kind", "stats-query");
            source.put("resourcePath", "workforce/assignments");
            source.put("operation", "group-by");
            source.putObject("request").put("field", "status");
            ObjectNode derivation = card.putObject("derivation");
            derivation.put("kind", "ratio");
            derivation.putArray("numeratorKeys").add("ACTIVE");
            derivation.put("denominatorMode", "population-total");

            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.put("density", "comfortable");
            if (spec == UiLayoutDeclarativeWidgetRevisionReproduction.Spec.KPI_CARD) {
                wrapper.set("config", card);
            } else {
                ObjectNode strip = wrapper.putObject("config");
                strip.put("schemaVersion", 1);
                strip.put("title", "Executive Strip");
                strip.putArray("cards").add(card);
            }
            return wrapper;
        }
        ObjectNode config = MAPPER.createObjectNode();
        if (spec.bindingFields.contains("resourcePath")) {
            config.put("resourcePath", "/api/workforce/employees");
        }
        if (spec.bindingFields.contains("dataPath")) {
            config.put("dataPath", "workforce.employees");
        }
        if (spec.bindingFields.contains("schemaPath")) {
            config.put("schemaPath", "/schemas/filtered");
        }
        for (String field : spec.authoredFields) {
            switch (field) {
                case "items", "tabs", "steps", "panels", "sections", "links", "rules", "conditions", "columns" ->
                        config.putArray(field).addObject().put("id", "item-1").put("label", "Default");
                case "groups", "datasets", "series", "widgets" ->
                        config.putArray(field).addObject().put("id", "grp-1");
                case "enabled", "linear", "multi", "readOnly", "collapsible", "compact" ->
                        config.put(field, true);
                case "defaultPageSize", "maxFiles", "maxSizeMb", "order", "selectedIndex" ->
                        config.put(field, 10);
                default -> {
                    if (!config.has(field)) {
                        config.put(field, "sample-" + field);
                    }
                }
            }
        }
        for (String required : spec.requiredAnyOfFields) {
            if (!config.has(required)) {
                config.put(required, "sample-" + required);
            }
        }
        return config;
    }
}
