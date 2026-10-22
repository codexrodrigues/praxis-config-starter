package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutNavigationGroupsRevisionReproductionTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UiLayoutTarget ROOT = new UiLayoutTarget("praxis-app-shell", "11111111-1111-4111-8111-111111111111");
  private static final UiLayoutAuthoringDocumentDescriptor DESCRIPTOR =
      new UiLayoutAuthoringDocumentDescriptor("praxis.navigation-groups.editor", "fixture:groups", "1");
  private static final UiLayoutPatchDocumentDescriptor PATCH =
      new UiLayoutPatchDocumentDescriptor("fixture:groups-patch", "praxis.ui-layout/v1");

  private static ObjectNode document() throws Exception {
    return (ObjectNode) JSON.readTree("""
        {"kind":"praxis.navigation-groups.editor","version":1,"config":{"groups":[
          {"id":"22222222-2222-4222-8222-222222222222","label":"Operações","icon":"work","order":2},
          {"id":"33333333-3333-4333-8333-333333333333","label":"Biblioteca","icon":"book","order":1}
        ]}}
        """);
  }

  private static ArrayNode groups(ObjectNode document) { return (ArrayNode) document.at("/config/groups"); }
  private static ObjectNode group(ObjectNode document) { return (ObjectNode) groups(document).get(0); }
  private static ObjectNode patch(ObjectNode baseline, ObjectNode candidate) {
    UiLayoutNavigationGroupsRevisionReproduction.requireNative(ROOT, DESCRIPTOR, baseline, candidate, PATCH);
    var result = UiLayoutAuthoredPatchCompiler.compile((ObjectNode) baseline.get("config"), (ObjectNode) candidate.get("config"));
    UiLayoutNavigationGroupsRevisionReproduction.require(ROOT, DESCRIPTOR, baseline, candidate, PATCH, result);
    return result;
  }
  private static void rejected(JsonNode document) {
    assertEquals(UiLayoutLifecycleException.Code.VALIDATION_FAILED,
        assertThrows(UiLayoutLifecycleException.class, () ->
            UiLayoutNavigationGroupsRevisionReproduction.requireNative(ROOT, DESCRIPTOR, document, document, PATCH)).getCode());
  }

  @Test void normativeGroupsCorpusRunsThroughNativeCodecWithoutNormalization() throws Exception {
    var corpus = JSON.readTree(Path.of("docs/ai/contracts/page-publication-p0/corpus.json").toFile());
    int selected = 0;
    for (var entry : corpus.path("schemaCases")) {
      if (!"groupsDocument".equals(entry.path("contract").asText())) continue;
      selected++;
      var input = entry.get("input"); var original = input.deepCopy();
      if (entry.path("valid").asBoolean()) patch((ObjectNode) input, (ObjectNode) input);
      else rejected(input);
      assertEquals(original, input, entry.path("id").asText());
    }
    assertEquals(3, selected);
  }

  @Test void completeArrayRepresentsAddRemoveRenameReorderAndEmptyWithoutMutatingSource() throws Exception {
    var baseline = document(); var original = baseline.deepCopy(); var candidate = baseline.deepCopy();
    groups(candidate).remove(0);
    ((ObjectNode) groups(candidate).get(0)).put("label", "Nova biblioteca").put("icon", "library_books").put("order", 0);
    groups(candidate).addObject().put("id", "44444444-4444-4444-8444-444444444444")
        .put("label", "Novo grupo").put("icon", "home").put("order", 0);
    var projected = patch(baseline, candidate);
    assertEquals(groups(candidate), projected.get("groups"));
    assertFalse(projected.toString().contains("22222222-2222-4222-8222-222222222222"));
    assertEquals(original, baseline);
    groups(candidate).removeAll();
    assertEquals(JSON.readTree("{\"groups\":[]}"), patch(baseline, candidate));
    // Deletion is omission of an entry, never a tombstone/null or another status machine.
    var tombstone = document(); groups(tombstone).set(0, JSON.nullNode()); rejected(tombstone);
  }

  @Test void duplicateIdsAreRejectedInBothBaselineAndCandidateDespiteAtomicArrayPatches() throws Exception {
    var valid = document(); var duplicate = document();
    ((ObjectNode) groups(duplicate).get(1)).put("id", group(duplicate).path("id").asText());
    rejected(duplicate);
    assertThrows(UiLayoutLifecycleException.class, () -> patch(duplicate, valid));
    assertThrows(UiLayoutLifecycleException.class, () -> patch(valid, duplicate));
    ((ObjectNode) groups(valid).get(1)).put("label", group(valid).path("label").asText()).put("order", 2);
    patch(valid, valid); // Labels and order ties do not establish identity.
  }

  @Test void profileRejectsPageBindingsGlobalPageListsHierarchyAndUnknownFields() throws Exception {
    for (String field : List.of("pages", "navigation", "children", "parentId", "status", "url", "tenantId")) {
      var invalid = document(); group(invalid).put(field, "untrusted"); rejected(invalid);
      invalid = document(); ((ObjectNode) invalid.get("config")).put(field, "untrusted"); rejected(invalid);
      invalid = document(); invalid.put(field, "untrusted"); rejected(invalid);
    }
  }

  @Test void requiredFieldsPresenceAndNullAreNotNormalized() throws Exception {
    for (String field : List.of("id", "label", "icon", "order")) {
      var missing = document(); group(missing).remove(field); rejected(missing);
      var explicitNull = document(); group(explicitNull).putNull(field); rejected(explicitNull);
    }
    for (String field : List.of("kind", "version", "config")) {
      var invalid = document(); invalid.remove(field); rejected(invalid);
      invalid = document(); invalid.putNull(field); rejected(invalid);
    }
    var absentGroups = document(); ((ObjectNode) absentGroups.get("config")).remove("groups"); rejected(absentGroups);
    var nullGroups = document(); ((ObjectNode) nullGroups.get("config")).putNull("groups"); rejected(nullGroups);
  }

  @Test void labelsUseCodepointBoundsAndRejectWhitespaceAndControls() throws Exception {
    var valid = document(); group(valid).put("label", "😀".repeat(160)); patch(valid, valid);
    for (String label : List.of("", " ", " Leading", "Trailing\u00a0", "line\nbreak", "x\u2028y", "😀".repeat(161))) {
      var invalid = document(); group(invalid).put("label", label); rejected(invalid);
    }
  }

  @Test void iconsAreOnlySyntacticallyCheckedAndNeverAttestCatalogAdmission() throws Exception {
    var syntacticallyValidButUnadmitted = document();
    group(syntacticallyValidButUnadmitted).put("icon", "host_has_not_admitted_this_icon");
    patch(syntacticallyValidButUnadmitted, syntacticallyValidButUnadmitted);
    for (String icon : List.of("", "Home", "home\n", "https://icons.invalid/x", "<svg/>", "a".repeat(81))) {
      var invalid = document(); group(invalid).put("icon", icon); rejected(invalid);
    }
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutLifecycleStructureValidator.denyAll().validateAuthoringBaseline(null, ROOT, DESCRIPTOR,
            syntacticallyValidButUnadmitted, null));
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutBaselineMetadataAdmission.denyAll().require(null, ROOT, DESCRIPTOR,
            "fixture:groups", syntacticallyValidButUnadmitted, null));
  }

  @Test void idsAndOrderAreStrictWithoutCoercion() throws Exception {
    for (String id : List.of("not-a-uuid", "22222222-2222-4222-8222-222222222222\n", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA")) {
      var invalid = document(); group(invalid).put("id", id); rejected(invalid);
    }
    for (JsonNode order : List.of(JSON.readTree("-1"), JSON.readTree("2147483648"), JSON.readTree("0.5"), JSON.readTree("\"0\""))) {
      var invalid = document(); group(invalid).set("order", order); rejected(invalid);
    }
    var valid = document(); group(valid).put("order", Integer.MAX_VALUE); patch(valid, valid);
  }

  @Test void unsupportedTargetAndDescriptorsFailClosedWithoutInventingRootIdentity() throws Exception {
    var nativeDocument = document();
    for (UiLayoutTarget target : List.of(new UiLayoutTarget("praxis-dynamic-page", ROOT.componentId()),
        new UiLayoutTarget("praxis-app-shell", "host-chosen-but-not-uuid"))) {
      assertEquals(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, assertThrows(UiLayoutLifecycleException.class, () ->
          UiLayoutNavigationGroupsRevisionReproduction.requireNative(target, DESCRIPTOR, nativeDocument, nativeDocument, PATCH)).getCode());
    }
    for (var descriptor : List.of(new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "fixture:groups", "1"),
        new UiLayoutAuthoringDocumentDescriptor(DESCRIPTOR.documentType(), "fixture:groups", "2"))) {
      assertThrows(UiLayoutLifecycleException.class, () ->
          UiLayoutNavigationGroupsRevisionReproduction.requireNative(ROOT, descriptor, nativeDocument, nativeDocument, PATCH));
    }
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutNavigationGroupsRevisionReproduction.requireNative(ROOT, DESCRIPTOR, nativeDocument, nativeDocument,
            new UiLayoutPatchDocumentDescriptor("fixture:patch", "unsupported")));
  }

  @Test void unrelatedNativeKindsAreNotCapturedByGroupsReproduction() {
    var form = new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "fixture:form", "1");
    assertDoesNotThrow(() -> UiLayoutNavigationGroupsRevisionReproduction.require(null, form, null, null, null, null));
  }

  @Test void patchMustExactlyReproduceCandidateAndPreserveAuthoredPresence() throws Exception {
    var baseline = document(); var candidate = document(); group(candidate).put("label", "Renamed");
    for (JsonNode invalidPatch : List.of(JSON.readTree("{}"), JSON.readTree("{\"groups\":null}"),
        JSON.readTree("{\"groups\":[]}"), JSON.readTree("[]"))) {
      assertThrows(UiLayoutLifecycleException.class, () ->
          UiLayoutNavigationGroupsRevisionReproduction.require(ROOT, DESCRIPTOR, baseline, candidate, PATCH, invalidPatch));
    }
    var valid = patch(baseline, candidate);
    valid.put("unknown", "injected");
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutNavigationGroupsRevisionReproduction.require(ROOT, DESCRIPTOR, baseline, candidate, PATCH, valid));
  }

  @Test void oversizedAndDeepDocumentsAreRejectedBeforeSchemaValidation() throws Exception {
    var oversized = document(); group(oversized).put("label", "x".repeat(UiLayoutJsonBounds.MAX_DOCUMENT_BYTES)); rejected(oversized);
    var deep = document(); var nested = deep.putObject("unknown");
    for (int depth = 0; depth < UiLayoutJsonBounds.MAX_DEPTH; depth++) nested = nested.putObject("child");
    rejected(deep);
    var baseline = document(); var hugePatch = JSON.createObjectNode().put("unknown", "x".repeat(UiLayoutJsonBounds.MAX_DOCUMENT_BYTES));
    assertThrows(UiLayoutLifecycleException.class, () ->
        UiLayoutNavigationGroupsRevisionReproduction.require(ROOT, DESCRIPTOR, baseline, baseline, PATCH, hugePatch));
  }
}
