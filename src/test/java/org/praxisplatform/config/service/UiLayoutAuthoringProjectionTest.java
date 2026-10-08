package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

@Tag("unit")
class UiLayoutAuthoringProjectionTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final UiLayoutTarget table = new UiLayoutTarget("praxis-table", "test");
  private final UiLayoutTarget form = new UiLayoutTarget("praxis-dynamic-form", "test");
  private final UiLayoutAuthoringDocumentDescriptor tableDescriptor = new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "host.table", "1");
  private final UiLayoutAuthoringDocumentDescriptor formDescriptor = new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "host.form", "1");
  private final UiLayoutPatchDocumentDescriptor patchDescriptor = new UiLayoutPatchDocumentDescriptor("host.patch", "praxis.ui-layout/v1");
  private final Runnable active = () -> {};

  private ObjectNode document(boolean isTable) throws Exception {
    return (ObjectNode) mapper.readTree(isTable
        ? "{\"kind\":\"praxis.table.editor\",\"version\":1,\"config\":{\"columns\":[],\"columnProjection\":{\"source\":\"schema\"}}}"
        : "{\"kind\":\"praxis.dynamic-form.editor\",\"version\":1,\"config\":{}}");
  }
  private UiLayoutAuthoringProjection project(boolean isTable, JsonNode baseline, JsonNode candidate) {
    return UiLayoutAuthoringProjection.project(isTable ? table : form, isTable ? tableDescriptor : formDescriptor,
        baseline, candidate, patchDescriptor, active);
  }
  private void invalid(Runnable action, Code code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
        failure -> assertThat(failure.getCode()).isEqualTo(code));
  }

  @Test void matchesFiniteTableAndFormCorporaUsingTheCommonCompiler() throws Exception {
    for (String component : new String[] {"table", "form"}) {
      var corpus = mapper.readTree(Files.readString(Path.of("docs/ai/contracts/" + component + "-native-revision-conformance.v1.json")));
      assertThat(corpus.path("cases")).hasSize(component.equals("table") ? 4 : 6);
      for (var item : corpus.path("cases")) {
        var before = item.deepCopy(); var command = item.path("command");
        assertThat(project(component.equals("table"), item.path("baseline"), command.path("authoringDocument")).compilePatch())
            .as(item.path("id").asText()).isEqualTo(item.path("expectedPatch"));
        assertThat(item).isEqualTo(before);
      }
    }
  }

  @Test void isolatesOriginalInputsAndReturnedProjectionCopies() throws Exception {
    var baseline = document(false); ((ObjectNode) baseline.get("config")).put("old", 1);
    var candidate = document(false); ((ObjectNode) candidate.get("config")).put("title", "A");
    var projection = project(false, baseline, candidate);
    ((ObjectNode) baseline.get("config")).put("extra", 2);
    ((ObjectNode) candidate.get("config")).put("title", "changed");
    projection.baselineConfig().put("corrupted", true);
    projection.candidateConfig().put("title", "corrupted");
    assertThat(projection.compilePatch()).isEqualTo(mapper.readTree("{\"title\":\"A\",\"old\":null}"));
  }

  @Test void rejectsChangedEnvelopeAndMalformedNativeRoot() throws Exception {
    var baseline = document(false); var candidate = baseline.deepCopy().put("binding", "changed");
    invalid(() -> project(false, baseline, candidate), Code.VALIDATION_FAILED);
    candidate.remove("binding"); candidate.put("config", "wrong");
    invalid(() -> project(false, baseline, candidate), Code.VALIDATION_FAILED);
  }

  @Test void rejectsTargetAndDescriptorVersionMismatchWithoutFallback() throws Exception {
    var doc = document(true);
    invalid(() -> UiLayoutAuthoringProjection.project(form, tableDescriptor, doc, doc, patchDescriptor, active), Code.SOURCE_UNAVAILABLE);
    invalid(() -> UiLayoutAuthoringProjection.project(table,
        new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "host.table", "2"), doc, doc, patchDescriptor, active), Code.SOURCE_UNAVAILABLE);
    invalid(() -> UiLayoutAuthoringProjection.project(table, tableDescriptor, doc, doc,
        new UiLayoutPatchDocumentDescriptor("host.patch", "other"), active), Code.SOURCE_UNAVAILABLE);
    for (String kind : new String[] {"praxis.table.authoring-document", "praxis.page.editor", "native"}) {
      invalid(() -> UiLayoutAuthoringProjection.project(table, new UiLayoutAuthoringDocumentDescriptor(kind, "host", "1"),
          doc, doc, patchDescriptor, active), Code.SOURCE_UNAVAILABLE);
    }
  }

  @Test void rejectsOverflowingFractionalAndWrongNativeVersionsInProjectionAndGuard() throws Exception {
    var baseline = document(true);
    for (String version : new String[] {"4294967297", "1.5", "2"}) {
      var candidate = baseline.deepCopy(); candidate.set("version", mapper.readTree(version));
      invalid(() -> project(true, baseline, candidate), Code.VALIDATION_FAILED);
      invalid(() -> UiLayoutTableRevisionReproduction.require(table, tableDescriptor, baseline, candidate, patchDescriptor,
          candidate.path("config")), Code.VALIDATION_FAILED);
    }
  }

  @Test void retainsTableAndFormDifferentNullPolicies() throws Exception {
    var baseline = document(true); var candidate = baseline.deepCopy(); ((ObjectNode) candidate.get("config")).putArray("values").addNull();
    invalid(() -> project(true, baseline, candidate), Code.VALIDATION_FAILED);
    var formBaseline = document(false); var formCandidate = formBaseline.deepCopy();
    ((ObjectNode) formCandidate.get("config")).putArray("values").addObject().putNull("value");
    assertThat(project(false, formBaseline, formCandidate).compilePatch()).isEqualTo(formCandidate.get("config"));
    ((ObjectNode) formCandidate.get("config")).putNull("literal");
    invalid(() -> project(false, formBaseline, formCandidate), Code.VALIDATION_FAILED);
  }

  @Test void rejectsMaterializedTableInsteadOfInferringCompactAuthorship() throws Exception {
    var baseline = document(true); var config = (ObjectNode) baseline.get("config");
    config.remove("columnProjection"); config.putArray("columns").addObject().put("field", "id");
    invalid(() -> project(true, baseline, baseline), Code.VALIDATION_FAILED);
  }

  @Test void distinguishesCorruptBaselineFromOversizedCandidate() throws Exception {
    var baseline = document(false); var candidate = baseline.deepCopy();
    ((ObjectNode) candidate.get("config")).put("large", "x".repeat(262144));
    invalid(() -> project(false, baseline, candidate), Code.INVALID_REQUEST);
    invalid(() -> project(false, candidate, baseline), Code.INVALID_STATE);
  }

  @Test void rejectsOversizedResetPatchBeforeBuildingIt() throws Exception {
    var baseline = document(false); var config = (ObjectNode) baseline.get("config");
    for (int index = 0; index < 20000; index++) config.put("k" + String.format("%05d", index), 0);
    var candidate = document(false);
    var projection = project(false, baseline, candidate);
    invalid(projection::compilePatch, Code.INVALID_REQUEST);
    assertThat(config.size()).isEqualTo(20000); assertThat(candidate.path("config")).isEmpty();
  }

  @Test void boundsPatchKeysWithEscapesAndPreservesExpiryFromTheTraversal() throws Exception {
    var baseline = document(false); ((ObjectNode) baseline.get("config")).put("quoted\"\n", 1);
    var candidate = document(false);
    assertThat(project(false, baseline, candidate).compilePatch()).isEqualTo(mapper.readTree("{\"quoted\\\"\\n\":null}"));
    var checks = new AtomicInteger();
    invalid(() -> UiLayoutAuthoringProjection.project(form, formDescriptor, baseline, candidate, patchDescriptor,
        () -> { if (checks.incrementAndGet() > 4) throw new UiLayoutLifecycleException(Code.SOURCE_UNAVAILABLE, "expired"); }),
        Code.SOURCE_UNAVAILABLE);
  }
}
