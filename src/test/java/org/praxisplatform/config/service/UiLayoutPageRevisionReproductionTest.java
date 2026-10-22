package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutPageRevisionReproductionTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final UiLayoutTarget PAGE = new UiLayoutTarget("praxis-dynamic-page", "11111111-1111-4111-8111-111111111111");
  static final UiLayoutAuthoringDocumentDescriptor DESCRIPTOR = new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-page.editor", "fixture:page", "1");
  static final UiLayoutPatchDocumentDescriptor PATCH = new UiLayoutPatchDocumentDescriptor("fixture:patch", "praxis.ui-layout/v1");
  static ObjectNode page() throws Exception {
    return (ObjectNode) JSON.readTree("""
      {"kind":"praxis.dynamic-page.editor","version":1,"config":{"definition":{"widgets":[
        {"key":"22222222-2222-4222-8222-222222222222","definition":{"id":"praxis-dynamic-form","inputs":{"config":{"title":"Original","fields":[]}}}},
        {"key":"33333333-3333-4333-8333-333333333333","definition":{"id":"praxis-table","inputs":{"config":{"columns":[],"columnProjection":{"source":"schema"}}}}}
      ]},"presentation":{"title":"Page","icon":"home"},"navigation":[]}}
      """);
  }
  static ObjectNode input(ObjectNode page, int index) { return (ObjectNode) page.at("/config/definition/widgets/" + index + "/definition/inputs"); }
  private void reproduce(JsonNode before, JsonNode after) {
    var projection = UiLayoutAuthoringProjection.project(PAGE, DESCRIPTOR, before, after, PATCH, () -> {});
    UiLayoutPageRevisionReproduction.require(PAGE, DESCRIPTOR, before, after, PATCH, projection.compilePatch());
  }
  @Test void runtimeSchemaIsExactlyTheNormativeP0Schema() throws Exception {
    try (var resource = getClass().getResourceAsStream("/org/praxisplatform/config/page-publication.schema.json")) {
      assertEquals(JSON.readTree(Path.of("docs/ai/contracts/page-publication-p0/contracts.schema.json").toFile()), JSON.readTree(resource));
    }
  }
  @Test void allPageSchemaCasesRunThroughNativeCodecWithoutNormalization() throws Exception {
    var corpus = JSON.readTree(Path.of("docs/ai/contracts/page-publication-p0/corpus.json").toFile());
    for (var entry : corpus.path("schemaCases")) {
      if (!entry.path("contract").asText().equals("pageDocument")) continue;
      var value = entry.get("input"); var before = value.deepCopy();
      if (entry.path("valid").asBoolean()) reproduce(value, value);
      else assertThrows(UiLayoutLifecycleException.class, () -> reproduce(value, value), entry.path("id").asText());
      assertEquals(before, value);
    }
  }
  @Test void fullConfigAndChildArraysRoundTripWhileNullOwnerRulesStillApply() throws Exception {
    var before = page(); var after = before.deepCopy();
    ((ObjectNode) input(after, 0).get("config")).remove("title");
    ((ObjectNode) input(after, 0).get("config")).putArray("fields").addObject().putNull("layout");
    reproduce(before, after);
    ((ObjectNode) input(after, 0).get("config")).putNull("layout");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, after));
    var tableNull = before.deepCopy();
    ((ObjectNode) input(tableNull, 1).get("config")).putArray("actions").addNull();
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, tableNull));
    var columns = before.deepCopy();
    ((ObjectNode) input(columns, 1).get("config")).putArray("columns").addObject().put("field", "id");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, columns));
  }
  @Test void reconstructsPresenceNullAndAllFiveOwnerModes() throws Exception {
    for (String mode : List.of("create", "edit", "view", "presentation", "dossier")) {
      var value = page(); input(value, 0).put("mode", mode).putNull("emptyState").putNull("presentationModeGlobal")
          .putNull("presentation").put("autoOpenSettingsOnOutdated", false).put("snoozeMs", 0);
      var child = UiLayoutPageRevisionReproduction.children(value).values().iterator().next();
      assertEquals(mode, child.at("/bindings/mode").asText());
      assertTrue(child.at("/bindings/emptyState").isNull());
      assertTrue(child.at("/contextSnapshot/presentation").isNull());
      assertEquals(0, child.at("/contextSnapshot/schemaPrefs/snoozeMs").asInt());
      assertFalse(child.at("/contextSnapshot/schemaPrefs").has("notifyIfOutdated"));
      reproduce(value, value);
    }
    var absent = UiLayoutPageRevisionReproduction.children(page()).values().iterator().next();
    assertFalse(absent.has("bindings")); assertFalse(absent.has("contextSnapshot"));
    var invalid = page(); input(invalid, 0).put("mode", "unknown");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(invalid, invalid));
  }
  @Test void fixedIdentityCannotChangeEnvelopeResourceOrComponent() throws Exception {
    var before = page();
    for (var field : List.of("mode", "emptyState", "backConfig", "presentationModeGlobal")) {
      var changed = before.deepCopy();
      switch(field) {
        case "mode" -> input(changed, 0).put(field, "edit");
        case "backConfig" -> input(changed, 0).putObject(field);
        default -> input(changed, 0).putNull(field);
      }
      assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, changed));
    }
    var resource = before.deepCopy(); input(resource, 1).put("resourcePath", "other");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, resource));
    var unsupported = before.deepCopy(); input(unsupported, 0).put("formId", "user-id");
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(before, unsupported));
  }
  @Test void duplicateKeysAndOrphanLinksFailDespiteAtomicWidgetArrays() throws Exception {
    var duplicate = page(); ((ObjectNode) duplicate.at("/config/definition/widgets/1")).put("key", duplicate.at("/config/definition/widgets/0/key").asText());
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(duplicate, duplicate));
    var orphan = page(); ((ObjectNode) orphan.at("/config/definition")).set("composition", JSON.readTree("""
      {"links":[{"id":"44444444-4444-4444-8444-444444444444","intent":"event-propagation",
        "from":{"kind":"component-port","ref":{"widget":"55555555-5555-4555-8555-555555555555","port":"saved","direction":"output"}},
        "to":{"kind":"component-port","ref":{"widget":"33333333-3333-4333-8333-333333333333","port":"refresh","direction":"input"}}}]}
      """));
    assertThrows(UiLayoutLifecycleException.class, () -> reproduce(orphan, orphan));
  }
}
