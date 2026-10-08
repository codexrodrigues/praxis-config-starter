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

@Tag("unit")
class UiLayoutTableRevisionReproductionTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final UiLayoutTarget target = new UiLayoutTarget("praxis-table", "purchase-orders");
  private final UiLayoutAuthoringDocumentDescriptor descriptor = new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "fixture.table", "1");
  private final UiLayoutPatchDocumentDescriptor patchDescriptor = new UiLayoutPatchDocumentDescriptor("fixture.patch", "praxis.ui-layout/v1");
  private JsonNode fixture() throws Exception {
    return mapper.readTree(Files.readString(Path.of("docs/ai/contracts/table-native-revision-conformance.v1.json"))).path("cases").get(0);
  }
  private void require(JsonNode b, JsonNode c, JsonNode p) { UiLayoutTableRevisionReproduction.require(target, descriptor, b, c, patchDescriptor, p); }
  @Test void acceptsAllCommandsFromActualTypeScriptProducerWithoutMutatingTheirDocuments() throws Exception {
    JsonNode corpus = mapper.readTree(Files.readString(Path.of("docs/ai/contracts/table-native-revision-conformance.v1.json")));
    assertThat(corpus.path("cases")).hasSize(4);
    for (JsonNode item : corpus.path("cases")) {
      JsonNode before = item.deepCopy();
      require(item.path("baseline"), item.path("command").path("authoringDocument"), item.path("expectedPatch"));
      assertThat(item).isEqualTo(before);
    }
  }
  @Test void rejectsMissingEqualPinEvenIfMergeWouldStillEqualCandidate() throws Exception {
    JsonNode item=fixture(); ObjectNode p=item.path("expectedPatch").deepCopy();
    ((ObjectNode)p.path("columnProjection").path("overrides").path("a")).remove("header");
    assertThatThrownBy(() -> require(item.path("baseline"),item.path("command").path("authoringDocument"),p)).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsCandidateThatPatchDoesNotReproduce() throws Exception {
    JsonNode item=fixture(); ObjectNode p=item.path("expectedPatch").deepCopy(); p.put("unexpected",true);
    assertThatThrownBy(() -> require(item.path("baseline"),item.path("command").path("authoringDocument"),p)).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsPhantomReset() throws Exception {
    JsonNode item=fixture(); ObjectNode p=item.path("expectedPatch").deepCopy(); p.putNull("unknown");
    assertThatThrownBy(() -> require(item.path("baseline"),item.path("command").path("authoringDocument"),p)).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsLiteralNullRatherThanSilentlyReinterpretingItAsReset() throws Exception {
    JsonNode item=fixture(); ObjectNode c=item.path("command").path("authoringDocument").deepCopy(); ((ObjectNode)c.path("config")).putNull("title");
    assertThatThrownBy(() -> require(item.path("baseline"),c,item.path("expectedPatch"))).isInstanceOf(UiLayoutLifecycleException.class).hasMessageContaining("Literal null");
  }
  @Test void rejectsBindingChanges() throws Exception {
    JsonNode item=fixture(); ObjectNode c=item.path("command").path("authoringDocument").deepCopy(); ((ObjectNode)c.path("bindings")).put("resourcePath","/other");
    assertThatThrownBy(() -> require(item.path("baseline"),c,item.path("expectedPatch"))).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsMaterializedBaselineAsCompactAuthorship() throws Exception {
    JsonNode item=fixture(); ObjectNode b=item.path("baseline").deepCopy();
    ((ObjectNode)b.path("config")).set("columns",mapper.readTree("[{\"field\":\"a\"}]"));
    assertThatThrownBy(() -> require(b,item.path("command").path("authoringDocument"),item.path("expectedPatch"))).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsLossyColumnSnapshotsAndDuplicateIdentities() throws Exception {
    JsonNode item=fixture(); ObjectNode c=item.path("command").path("authoringDocument").deepCopy();
    ((ObjectNode)c.path("config")).set("columns",mapper.readTree("[{\"field\":\"a\"}]"));
    assertThatThrownBy(() -> require(item.path("baseline"),c,item.path("expectedPatch"))).isInstanceOf(UiLayoutLifecycleException.class);
    ((ObjectNode)c.path("config")).putArray("columns");
    ((ObjectNode)c.path("config").path("columnProjection")).set("order",mapper.readTree("[\"a\",\"a\"]"));
    assertThatThrownBy(() -> require(item.path("baseline"),c,item.path("expectedPatch"))).isInstanceOf(UiLayoutLifecycleException.class);
  }
  @Test void rejectsUnsupportedNativeDescriptorVersion() throws Exception {
    JsonNode item=fixture();
    assertThatThrownBy(() -> UiLayoutTableRevisionReproduction.require(target,new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor","fixture.table","2"),item.path("baseline"),item.path("command").path("authoringDocument"),patchDescriptor,item.path("expectedPatch"))).isInstanceOf(UiLayoutLifecycleException.class);
  }
}
