package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutDraftWorkspaceCodecTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final UiLayoutDraftWorkspaceCodec codec = new UiLayoutDraftWorkspaceCodec(mapper, hashes);
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "orders");
  private final UiLayoutCompositionRegistration registration = new UiLayoutCompositionRegistration(root, List.of(root));

  @Test
  void seedAndReceiptDocumentsAreDefensiveCopies() {
    var original = mapper.createObjectNode().put("density", "comfortable");
    var seed = new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,
        new UiLayoutAuthoringDocumentDescriptor("praxis.table.authoring-document", "urn:table", "authoring/v3"),
        new UiLayoutPatchDocumentDescriptor("urn:table-patch", "patch/v1"), "source:42", original, UiLayoutMetadataTestFixtures.metadata())));
    original.put("density", "mutated-after-seed");
    var invocation = new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx", registration);

    UiLayoutDraftWorkspaceDocument document = codec.fromSeed(invocation, seed);
    String encoded = codec.encode(document);
    ((com.fasterxml.jackson.databind.node.ObjectNode) seed.targets().getFirst().document()).put("density", "mutated-through-accessor");
    ((com.fasterxml.jackson.databind.node.ObjectNode) document.targets().getFirst().baseline().document()).put("density", "mutated-through-accessor");

    assertThat(codec.decode(encoded, registration).targets().getFirst().baseline().document().path("density").asText())
        .isEqualTo("comfortable");
  }

  @Test
  void rejectsLegacyOrUnknownWorkspaceShapesAndHashDrift() throws Exception {
    assertThatThrownBy(() -> codec.decode("{}", registration))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE);

    String hash = hashes.sha256Exact(mapper.readTree("{}"));
    String unknown = "{\"schemaVersion\":\"praxis.ui-layout-draft-workspace/v1\",\"targets\":[{"
        + "\"target\":{\"componentType\":\"praxis-table\",\"componentId\":\"orders\"},"
        + "\"authoring\":{\"documentType\":\"praxis.table.authoring-document\",\"schemaRef\":\"urn:table\",\"schemaVersion\":\"authoring/v3\"},"
        + "\"patch\":{\"schemaRef\":\"urn:patch\",\"schemaVersion\":\"patch/v1\"},"
        + "\"baseline\":{\"sourceRef\":\"source:42\",\"contentHash\":\"" + hash + "\",\"document\":{}},"
        + "\"working\":{\"contentHash\":\"" + hash + "\",\"document\":{}},\"unknown\":true}]}";
    assertThatThrownBy(() -> codec.decode(unknown, registration))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE);

    assertThatThrownBy(() -> codec.decode(unknown.replace(hash, "0".repeat(64)).replace(",\"unknown\":true", ""), registration))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_STATE);
  }
  @Test
  void historicalWorkspaceCannotRoundPreciseTextBackIntoAnEarlierAcceptedHash() throws Exception {
    var document = mapper.readTree("{\"kind\":\"praxis.dynamic-form.editor\",\"version\":1,\"precision\":0.1}");
    var seed = new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,
        new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "urn:form", "1"),
        new UiLayoutPatchDocumentDescriptor("urn:form-patch", "patch/v1"), "host:form:b0", document,
        UiLayoutMetadataTestFixtures.metadata())));
    var accepted = codec.fromSeed(new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx", registration), seed);
    String encoded = codec.encode(accepted);
    assertThat(codec.decodeHistorical(encoded).targets().getFirst().baseline().document().path("precision").decimalValue())
        .isEqualByComparingTo("0.1");
    String altered = encoded.replace("0.1", "0.10000000000000001");
    assertThat(altered).isNotEqualTo(encoded);
    assertThatThrownBy(() -> codec.decodeHistorical(altered)).isInstanceOf(UiLayoutLifecycleException.class);
  }

  private UiLayoutDraftWorkspaceDocument formWorkspace() throws Exception {
    var nativeDocument = mapper.readTree("{\"kind\":\"praxis.dynamic-form.editor\",\"version\":1,\"config\":{\"fieldMetadata\":[{\"name\":\"email\",\"placeholder\":\"Email B0\"}]},\"bindings\":{\"emptyState\":null}}");
    var seed = new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(root,
        new UiLayoutAuthoringDocumentDescriptor("praxis.dynamic-form.editor", "urn:form", "1"),
        new UiLayoutPatchDocumentDescriptor("urn:form-patch", "patch/v1"), "host:form:b0", nativeDocument, UiLayoutMetadataTestFixtures.metadata())));
    return codec.fromSeed(new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx", registration), seed);
  }

  @Test
  void corporateMapperSharedWithHashPreservesExactWorkspaceRoundtrip() throws Exception {
    var host = mapper.copy().configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.READ_NULL_PROPERTIES, false)
        .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.WRITE_NULL_PROPERTIES, false);
    var corporate = new UiLayoutDraftWorkspaceCodec(host, new CanonicalJsonHashService(host));
    var original = formWorkspace();
    var recovered = corporate.decodeHistorical(corporate.encode(original));
    assertThat(recovered.targets().getFirst().baseline().document())
        .isEqualTo(original.targets().getFirst().baseline().document());
    assertThat(recovered.targets().getFirst().working().contentHash())
        .isEqualTo(original.targets().getFirst().working().contentHash());
    assertThat(host.readTree("{\"optional\":null}").has("optional")).isFalse();
  }

  @Test
  void historicalHashFromNullDroppingHostIsNotSilentlyRepaired() throws Exception {
    var original = formWorkspace();
    var encoded = mapper.readTree(codec.encode(original));
    var document = (com.fasterxml.jackson.databind.node.ObjectNode) encoded.at("/targets/0/baseline/document");
    ((com.fasterxml.jackson.databind.node.ObjectNode) document.get("bindings")).remove("emptyState");
    assertThatThrownBy(() -> codec.decodeHistorical(encoded.toString()))
        .isInstanceOf(UiLayoutLifecycleException.class).hasMessage("Workspace baseline hash is invalid.");
  }

  @Test
  void closedEnvelopeRejectsTrailingJsonDespitePermissiveHost() throws Exception {
    var host = mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    var strict = new UiLayoutDraftWorkspaceCodec(host, new CanonicalJsonHashService(host));
    assertThatThrownBy(() -> strict.decodeHistorical(strict.encode(formWorkspace()) + " {}"))
        .isInstanceOf(UiLayoutLifecycleException.class);
  }

  @Test
  void preservesNativeNullAndAuthoredFieldValuesWithoutInferringIntent() throws Exception {
    var workspace = formWorkspace();
    var recovered = codec.decode(codec.encode(workspace), registration).targets().getFirst();
    assertThat(recovered.baseline().sourceRef()).isEqualTo("host:form:b0");
    assertThat(recovered.baseline().document().at("/bindings/emptyState").isNull()).isTrue();
    assertThat(recovered.working().document().at("/config/fieldMetadata/0/placeholder").asText()).isEqualTo("Email B0");
    assertThat(recovered.working().contentHash()).isEqualTo(recovered.baseline().contentHash());
  }

  @Test
  void rejectsWorkingDocumentDriftIndependentlyOfBaselineHash() throws Exception {
    var encoded = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(codec.encode(formWorkspace()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) encoded.at("/targets/0/working/document/bindings")).remove("emptyState");
    assertThatThrownBy(() -> codec.decode(encoded.toString(), registration))
        .isInstanceOf(UiLayoutLifecycleException.class).hasMessage("Workspace working hash is invalid.");
  }

  @Test
  void rejectsChangedCurrentTargetsWithoutRewritingHistoricalWorkspace() throws Exception {
    String encoded = codec.encode(formWorkspace());
    var replacement = new UiLayoutTarget("praxis-table", "other");
    var changed = new UiLayoutCompositionRegistration(replacement, List.of(replacement));
    assertThatThrownBy(() -> codec.decode(encoded, changed))
        .isInstanceOf(UiLayoutLifecycleException.class).hasMessage("Workspace targets differ from the registered composition.");
    assertThat(codec.decode(encoded, registration).targets().getFirst().target()).isEqualTo(root);
  }

  @Test
  void rejectsDuplicateJsonKeysAndRepeatedTargets() throws Exception {
    String encoded = codec.encode(formWorkspace());
    assertThatThrownBy(() -> codec.decode(encoded.replace("\"sourceRef\":", "\"sourceRef\":\"forged\",\"sourceRef\":"), registration))
        .isInstanceOf(UiLayoutLifecycleException.class);
    var repeated = new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION,
        List.of(formWorkspace().targets().getFirst(), formWorkspace().targets().getFirst()), null);
    assertThatThrownBy(() -> codec.encode(repeated))
        .isInstanceOf(UiLayoutLifecycleException.class).hasMessage("Workspace contains duplicate targets.");
  }

  @Test
  void rejectsSeedOutsideRegisteredComposition() throws Exception {
    var state = formWorkspace().targets().getFirst();
    var wrong = new UiLayoutTarget("praxis-dynamic-form", "unregistered");
    var seed = new UiLayoutDraftWorkspaceSeed(List.of(new UiLayoutDraftWorkspaceSeed.TargetSeed(wrong,
        state.authoring(), state.patch(), state.baseline().sourceRef(), state.baseline().document(), UiLayoutMetadataTestFixtures.metadata())));
    assertThatThrownBy(() -> codec.fromSeed(new UiLayoutLifecycleInvocation("author", "tenant", "unit", "lab", "ctx", registration), seed))
        .isInstanceOf(UiLayoutLifecycleException.class).hasMessage("Workspace source target order differs from the registered composition.");
  }

  @Test
  void historicalEvidenceSurvivesTargetRemovalWithoutAdmittingItIntoCurrentComposition() throws Exception {
    String encoded = codec.encode(formWorkspace());
    var replacement = new UiLayoutTarget("praxis-table", "replacement");
    var current = new UiLayoutCompositionRegistration(replacement, List.of(replacement));
    var recovered = codec.decodeHistorical(encoded).targets().getFirst();
    assertThat(recovered.target()).isEqualTo(root);
    assertThat(recovered.baseline().sourceRef()).isEqualTo("host:form:b0");
    assertThat(recovered.baseline().document().at("/bindings/emptyState").isNull()).isTrue();
    assertThatThrownBy(() -> codec.decode(encoded, current))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .hasMessage("Workspace targets differ from the registered composition.");
    assertThat(codec.encode(codec.decodeHistorical(encoded))).isEqualTo(encoded);
  }

  @Test
  void historicalDecodeRejectsCorruptedBaselineAndWorkingEvidence() throws Exception {
    for (String section : List.of("baseline", "working")) {
      var envelope = mapper.readTree(codec.encode(formWorkspace()));
      ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.at("/targets/0/" + section + "/document"))
          .put("forged", true);
      assertThatThrownBy(() -> codec.decodeHistorical(envelope.toString()))
          .isInstanceOf(UiLayoutLifecycleException.class)
          .hasMessage("Workspace " + section + " hash is invalid.");
    }
  }

  @Test
  void historicalDecodeKeepsClosedSchemaAndUniqueIdentities() throws Exception {
    String encoded = codec.encode(formWorkspace());
    assertThatThrownBy(() -> codec.decodeHistorical(encoded.replace("workspace/v1", "workspace/v99")))
        .isInstanceOf(UiLayoutLifecycleException.class);
    var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(encoded);
    unknown.put("historicalAuthorization", true);
    assertThatThrownBy(() -> codec.decodeHistorical(unknown.toString()))
        .isInstanceOf(UiLayoutLifecycleException.class);
    assertThatThrownBy(() -> codec.decodeHistorical(encoded.replace("\"sourceRef\":", "\"sourceRef\":\"forged\",\"sourceRef\":")))
        .isInstanceOf(UiLayoutLifecycleException.class);
    var repeated = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(encoded);
    ((com.fasterxml.jackson.databind.node.ArrayNode) repeated.get("targets")).add(repeated.at("/targets/0").deepCopy());
    assertThatThrownBy(() -> codec.decodeHistorical(repeated.toString()))
        .isInstanceOf(UiLayoutLifecycleException.class).hasMessage("Workspace contains duplicate targets.");
  }

  @Test
  void recoveredEvidenceIsDefensivelyCopiedAndRetainsSizeLimits() throws Exception {
    var recovered = codec.decodeHistorical(codec.encode(formWorkspace())).targets().getFirst();
    ((com.fasterxml.jackson.databind.node.ObjectNode) recovered.baseline().document()).put("forged", true);
    assertThat(recovered.baseline().document().has("forged")).isFalse();
    var envelope = mapper.readTree(codec.encode(formWorkspace()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.at("/targets/0/baseline/document"))
        .put("oversized", "x".repeat(UiLayoutDraftWorkspaceCodec.MAX_DOCUMENT_BYTES));
    assertThatThrownBy(() -> codec.decodeHistorical(envelope.toString()))
        .isInstanceOf(UiLayoutLifecycleException.class)
        .extracting(error -> ((UiLayoutLifecycleException) error).getCode())
        .isEqualTo(UiLayoutLifecycleException.Code.INVALID_REQUEST);
  }
}
