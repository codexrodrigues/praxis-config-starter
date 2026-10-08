package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class UiLayoutRevisionRelationTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ObjectNode object(String json) throws Exception { return (ObjectNode) mapper.readTree(json); }

  @Test
  void preservesEqualPinsAndIgnoresObjectKeyOrderWithoutMutatingInputs() throws Exception {
    var baseline = object("{\"density\":\"compact\",\"title\":\"old\"}");
    var candidate = object("{\"title\":\"new\",\"density\":\"compact\"}");
    var patch = object("{\"density\":\"compact\",\"title\":\"new\"}");
    var beforeBaseline = baseline.deepCopy(); var beforeCandidate = candidate.deepCopy(); var beforePatch = patch.deepCopy();
    UiLayoutRevisionRelation.requireReproduction(baseline, candidate, patch);
    assertThat(baseline).isEqualTo(beforeBaseline);
    assertThat(candidate).isEqualTo(beforeCandidate);
    assertThat(patch).isEqualTo(beforePatch);
  }

  @Test
  void rejectsAnOmittedPinEvenWhenApplyingThePatchWouldProduceTheSameDocument() throws Exception {
    var baseline = object("{\"density\":\"compact\"}");
    assertThatThrownBy(() -> UiLayoutRevisionRelation.requireReproduction(baseline, baseline.deepCopy(), mapper.createObjectNode()))
        .isInstanceOfSatisfying(UiLayoutLifecycleException.class, error ->
            assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
  }

  @Test
  void acceptsNestedKnownResetsButRejectsPhantomRemoval() throws Exception {
    var baseline = object("{\"layout\":{\"title\":\"old\",\"density\":\"compact\"}}");
    var candidate = object("{\"layout\":{\"density\":\"compact\"}}");
    var patch = object("{\"layout\":{\"title\":null,\"density\":\"compact\"}}");
    UiLayoutRevisionRelation.requireReproduction(baseline, candidate, patch);
    ((ObjectNode) patch.get("layout")).putNull("absent");
    assertThatThrownBy(() -> UiLayoutRevisionRelation.requireReproduction(baseline, candidate, patch))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void replacesAtomicArraysIncludingNullButRejectsReorderedPatchValues() throws Exception {
    var baseline = object("{\"items\":[\"old\"]}");
    var candidate = object("{\"items\":[null,{\"value\":null},\"new\"]}");
    UiLayoutRevisionRelation.requireReproduction(baseline, candidate, candidate.deepCopy());
    var wrong = object("{\"items\":[\"new\",{\"value\":null},null]}");
    assertThatThrownBy(() -> UiLayoutRevisionRelation.requireReproduction(baseline, candidate, wrong))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void rejectsAdditionalPatchValuesEvenWhenAllAuthoredValuesArePresent() throws Exception {
    var candidate = object("{\"title\":\"new\"}");
    var patch = object("{\"title\":\"new\",\"unexpected\":true}");
    assertThatThrownBy(() -> UiLayoutRevisionRelation.requireReproduction(mapper.createObjectNode(), candidate, patch))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void protectsEnvelopeAndDistinguishesNullFromAbsence() throws Exception {
    var baseline = object("{\"kind\":\"native\",\"version\":1,\"bindings\":{\"mode\":\"view\"},\"config\":{}}");
    var candidate = baseline.deepCopy(); candidate.putObject("config").put("title", "edited");
    UiLayoutRevisionRelation.requireUnchangedEnvelope(baseline, candidate);
    candidate.putNull("contextSnapshot");
    assertThatThrownBy(() -> UiLayoutRevisionRelation.requireUnchangedEnvelope(baseline, candidate))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertThat(baseline.has("contextSnapshot")).isFalse();
  }
}
