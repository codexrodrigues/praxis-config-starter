package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Relation tests plus finite TS-produced corpus; no backend Angular executor or effective schema is admitted. */
@Tag("unit")
class UiLayoutFormRevisionReproductionTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final UiLayoutTarget target = new UiLayoutTarget("praxis-dynamic-form", "test-form");
  private final UiLayoutAuthoringDocumentDescriptor descriptor = new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "test.form", "1");
  private final UiLayoutPatchDocumentDescriptor patchDescriptor = new UiLayoutPatchDocumentDescriptor("test.patch", "praxis.ui-layout/v1");
  private ObjectNode document(String config) throws Exception {
    ObjectNode document = mapper.createObjectNode().put("kind", "praxis.dynamic-form.editor").put("version", 1);
    document.putObject("bindings").put("mode", "view"); document.set("config", mapper.readTree(config)); return document;
  }
  private void require(JsonNode baseline, JsonNode candidate, JsonNode patch) {
    UiLayoutFormRevisionReproduction.require(target, descriptor, baseline, candidate, patchDescriptor, patch);
  }
  @Test void consumesFiniteCorpusGeneratedByTheNativeFormProducer() throws Exception {
    var corpus = mapper.readTree(Files.readString(Path.of("docs/ai/contracts/form-native-revision-conformance.v1.json")));
    assertThat(corpus.path("schemaVersion").asText()).isEqualTo("praxis.form-native-revision-conformance/v1");
    assertThat(corpus.path("evidenceClass").asText()).isEqualTo("finite-generated-test-fixtures");
    assertThat(corpus.path("sourceHashes").path("producer").asText()).matches("[a-f0-9]{64}");
    assertThat(corpus.path("cases")).hasSize(6);
    for (var item : corpus.path("cases")) {
      var before = item.deepCopy(); var command = item.path("command");
      assertThat(command.path("target").path("componentType").asText()).isEqualTo(target.componentType());
      require(item.path("baseline"), command.path("authoringDocument"), item.path("expectedPatch"));
      assertThat(item).isEqualTo(before);
      var omittedPin = (ObjectNode) item.path("expectedPatch").deepCopy(); omittedPin.remove("fieldMetadata");
      assertThatThrownBy(() -> require(item.path("baseline"), command.path("authoringDocument"), omittedPin))
          .as(item.path("id").asText()).isInstanceOf(UiLayoutLifecycleException.class);
    }
  }
  @Test void acceptsExplicitEqualPinsAndPreservesDocuments() throws Exception {
    var baseline = document("{\"title\":\"Original\",\"density\":\"compact\"}");
    var candidate = document("{\"title\":\"Authored\",\"density\":\"compact\"}");
    var patch = candidate.get("config").deepCopy(); var before = baseline.deepCopy();
    require(baseline, candidate, patch); assertThat(baseline).isEqualTo(before);
    assertThat(patch).isEqualTo(candidate.get("config"));
  }
  @Test void rejectsOmissionOfAnEqualPinEvenWhenMergeWouldReproduceCandidate() throws Exception {
    var baseline = document("{\"density\":\"compact\"}");
    assertThatThrownBy(() -> require(baseline, baseline, mapper.createObjectNode())).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void acceptsKnownResetAndRejectsPhantomReset() throws Exception {
    var baseline = document("{\"title\":\"Old\",\"density\":\"compact\"}"); var candidate = document("{\"density\":\"compact\"}");
    var patch = (ObjectNode) candidate.get("config").deepCopy(); patch.putNull("title"); require(baseline, candidate, patch);
    patch.putNull("unknown"); assertThatThrownBy(() -> require(baseline, candidate, patch)).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void preservesLiteralNullInsideAtomicArraysIncludingLayoutInheritance() throws Exception {
    var baseline = document("{\"sections\":[]}");
    var candidate = document("{\"sections\":[{\"id\":\"s\",\"rows\":[{\"id\":\"r\",\"columns\":[{\"id\":\"c\",\"span\":null}]}]}],\"values\":[null,{\"value\":null}]}");
    require(baseline, candidate, candidate.get("config"));
    assertThat(candidate.path("config").path("sections").get(0).path("rows").get(0).path("columns").get(0).has("span")).isTrue();
  }
  @Test void rejectsLiteralNullInMergedObjectsWithoutReinterpretingAsReset() throws Exception {
    var baseline = document("{\"layout\":{\"density\":\"compact\"}}"); var candidate = document("{\"layout\":{\"density\":null}}");
    assertThatThrownBy(() -> require(baseline, candidate, candidate.get("config")))
        .isInstanceOf(UiLayoutLifecycleException.class).hasMessageContaining("Literal null");
  }
  @Test void rejectsExtraPatchPropertiesAndArrayDifferences() throws Exception {
    var baseline = document("{\"sections\":[]}"); var candidate = document("{\"sections\":[{\"id\":\"authored\"}]}");
    var patch = (ObjectNode) candidate.get("config").deepCopy(); patch.put("unexpected", true);
    assertThatThrownBy(() -> require(baseline, candidate, patch)).isInstanceOf(UiLayoutLifecycleException.class);
    patch.remove("unexpected"); patch.putArray("sections");
    assertThatThrownBy(() -> require(baseline, candidate, patch)).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsChangedBindingsAndContextEnvelope() throws Exception {
    var baseline = document("{}"); var candidate = baseline.deepCopy(); candidate.put("contextSnapshot", "different");
    assertThatThrownBy(() -> require(baseline, candidate, mapper.createObjectNode())).isInstanceOf(UiLayoutLifecycleException.class);
    candidate.remove("contextSnapshot"); ((ObjectNode) candidate.get("bindings")).put("mode", "edit");
    assertThatThrownBy(() -> require(baseline, candidate, mapper.createObjectNode())).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsMalformedDocumentsAndUnsupportedDescriptor() throws Exception {
    var baseline = document("{}"); var invalid = baseline.deepCopy().put("version", 4294967297L);
    assertThatThrownBy(() -> require(baseline, invalid, mapper.createObjectNode())).isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(() -> UiLayoutFormRevisionReproduction.require(target,
        new UiLayoutAuthoringDocumentDescriptor(descriptor.documentType(), descriptor.schemaRef(), "2"), baseline, baseline, patchDescriptor, mapper.createObjectNode()))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class, error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE));
  }
}
