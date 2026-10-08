package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutAuthoredPatchCompilerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode object(String json) throws Exception { return (ObjectNode) mapper.readTree(json); }

  private ObjectNode compiled(String baseline, String candidate, String expected) throws Exception {
    var b0 = object(baseline); var c0 = object(candidate);
    var beforeB0 = b0.deepCopy(); var beforeC0 = c0.deepCopy();
    var patch = UiLayoutAuthoredPatchCompiler.compile(b0, c0);
    assertThat(patch).isEqualTo(object(expected));
    UiLayoutRevisionRelation.requireReproduction(b0, c0, patch);
    assertThat(b0).isEqualTo(beforeB0); assertThat(c0).isEqualTo(beforeC0);
    return patch;
  }

  @Test void retainsEqualPinsInsteadOfOnlyChangedValues() throws Exception {
    compiled("{\"title\":\"A\",\"width\":10}", "{\"title\":\"A\"}", "{\"title\":\"A\",\"width\":null}");
  }

  @Test void emitsOnlyKnownNestedResetsAndPreservesAnEmptyObject() throws Exception {
    compiled("{\"layout\":{\"density\":\"compact\",\"title\":\"old\"},\"unused\":1}",
        "{\"layout\":{}}", "{\"layout\":{\"density\":null,\"title\":null},\"unused\":null}");
  }

  @Test void handlesEmptyRootAndNewEmptyContainers() throws Exception {
    compiled("{\"old\":{\"value\":1}}", "{}", "{\"old\":null}");
    compiled("{}", "{\"object\":{},\"array\":[]}", "{\"object\":{},\"array\":[]}");
    compiled("{}", "{}", "{}");
  }

  @Test void replacesArraysAtomicallyPreservingOrderAndNestedLiteralNull() throws Exception {
    compiled("{\"items\":[\"old\",\"keep\"]}", "{\"items\":[null,{\"value\":null},[null],\"new\"]}",
        "{\"items\":[null,{\"value\":null},[null],\"new\"]}");
    compiled("{\"items\":[1,2]}", "{\"items\":[]}", "{\"items\":[]}");
  }

  @Test void reproducesTransitionsBetweenObjectsScalarsArraysAndBaselineNull() throws Exception {
    for (String previous : new String[] {"{}", "{\"old\":1}", "10", "[]", "null"}) {
      for (String next : new String[] {"{}", "{\"new\":2}", "false", "[null]"}) {
        var baseline = object("{\"value\":" + previous + "}");
        var candidate = object("{\"value\":" + next + "}");
        var patch = UiLayoutAuthoredPatchCompiler.compile(baseline, candidate);
        var reproduced = baseline.deepCopy(); UiLayoutMergePatch.apply(reproduced, patch);
        assertThat(reproduced).as("%s -> %s", previous, next).isEqualTo(candidate);
        assertThat(patch.has("value")).isTrue();
      }
    }
  }

  @Test void resetsAnExistingBaselineNullWithoutInventingALiteralAssignment() throws Exception {
    compiled("{\"old\":null}", "{}", "{\"old\":null}");
  }

  @Test void rejectsLiteralNullInMergedObjectsAndPreservesInputsOnFailure() throws Exception {
    for (String candidateJson : new String[] {"{\"secret\":null}", "{\"layout\":{\"secret\":null}}"}) {
      var baseline = object("{\"secret\":\"private\"}"); var candidate = object(candidateJson);
      var beforeB0 = baseline.deepCopy(); var beforeC0 = candidate.deepCopy();
      assertThatThrownBy(() -> UiLayoutAuthoredPatchCompiler.compile(baseline, candidate))
          .isInstanceOfSatisfying(UiLayoutLifecycleException.class, error ->
              assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED))
          .hasMessageNotContaining("private");
      assertThat(baseline).isEqualTo(beforeB0); assertThat(candidate).isEqualTo(beforeC0);
    }
  }

  @Test void rejectsMissingConfigReferencesWithTheLifecycleErrorVocabulary() throws Exception {
    var config = object("{}");
    assertThatThrownBy(() -> UiLayoutAuthoredPatchCompiler.compile(null, config)).isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(() -> UiLayoutAuthoredPatchCompiler.compile(config, null)).isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test void preservesUnicodeNumbersFalsyValuesAndObjectKeyIdentity() throws Exception {
    var baseline = object("{\"zero\":0,\"flag\":false,\"empty\":\"\",\"text\":\"ação 🧩\",\"big\":9007199254740993,\"decimal\":1.25}");
    var candidate = object("{\"decimal\":1.25,\"big\":9007199254740993,\"text\":\"ação 🧩\",\"empty\":\"\",\"flag\":false,\"zero\":0}");
    assertThat(UiLayoutAuthoredPatchCompiler.compile(baseline, candidate)).isEqualTo(candidate);
  }

  @Test void copiesMutableSubtreesIndependentlyInBothDirections() throws Exception {
    var baseline = object("{\"layout\":{\"old\":1}}");
    var candidate = object("{\"layout\":{\"title\":\"A\"},\"items\":[{\"value\":1}]}");
    var beforeB0 = baseline.deepCopy(); var beforeC0 = candidate.deepCopy();
    var patch = UiLayoutAuthoredPatchCompiler.compile(baseline, candidate);
    ((ObjectNode) patch.get("layout")).put("title", "patch edited");
    ((ObjectNode) patch.get("items").get(0)).put("value", 2);
    assertThat(baseline).isEqualTo(beforeB0); assertThat(candidate).isEqualTo(beforeC0);
    patch = UiLayoutAuthoredPatchCompiler.compile(baseline, candidate);
    var beforePatch = patch.deepCopy();
    ((ObjectNode) candidate.get("layout")).put("title", "candidate edited");
    ((ObjectNode) candidate.get("items").get(0)).put("value", 3);
    ((ObjectNode) baseline.get("layout")).put("old", 2);
    assertThat(patch).isEqualTo(beforePatch);
  }

  @Test void compilesEachRevisionAgainstPinnedBaselineNotPreviousWorking() throws Exception {
    var baseline = object("{\"title\":\"A\",\"width\":10}");
    UiLayoutAuthoredPatchCompiler.compile(baseline, object("{\"title\":\"B\"}"));
    var secondCandidate = object("{\"title\":\"C\"}");
    var secondPatch = UiLayoutAuthoredPatchCompiler.compile(baseline, secondCandidate);
    assertThat(secondPatch).isEqualTo(object("{\"title\":\"C\",\"width\":null}"));
    var wrongBasePatch = UiLayoutAuthoredPatchCompiler.compile(object("{\"title\":\"B\"}"), secondCandidate);
    assertThat(wrongBasePatch.has("width")).isFalse();
    assertThatThrownBy(() -> UiLayoutRevisionRelation.requireReproduction(baseline, secondCandidate, wrongBasePatch))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test void matchesFiniteNativeTableCorpusAndItsAdapter() throws Exception { corpus("table", 4); }

  @Test void matchesFiniteNativeFormCorpusAndItsAdapter() throws Exception { corpus("form", 6); }

  private void corpus(String component, int cases) throws Exception {
    JsonNode corpus = mapper.readTree(Files.readString(Path.of("docs/ai/contracts/" + component + "-native-revision-conformance.v1.json")));
    assertThat(corpus.path("evidenceClass").asText()).isEqualTo("finite-generated-test-fixtures");
    assertThat(corpus.path("cases")).hasSize(cases);
    for (var item : corpus.path("cases")) {
      var before = item.deepCopy(); var baseline = item.path("baseline"); var command = item.path("command");
      var candidate = command.path("authoringDocument");
      var patch = UiLayoutAuthoredPatchCompiler.compile((ObjectNode) baseline.path("config"), (ObjectNode) candidate.path("config"));
      assertThat(patch).as(item.path("id").asText()).isEqualTo(item.path("expectedPatch"));
      var target = mapper.treeToValue(command.path("target"), UiLayoutTarget.class);
      var descriptor = new UiLayoutAuthoringDocumentDescriptor(candidate.path("kind").asText(), "fixture.authoring", "1");
      var patchDescriptor = new UiLayoutPatchDocumentDescriptor("fixture.patch", "praxis.ui-layout/v1");
      if (component.equals("table")) UiLayoutTableRevisionReproduction.require(target, descriptor, baseline, candidate, patchDescriptor, patch);
      else UiLayoutFormRevisionReproduction.require(target, descriptor, baseline, candidate, patchDescriptor, patch);
      assertThat(item).isEqualTo(before);
    }
  }
}
