package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutPageChildSource;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutPageChildCapturesTest {
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(UiLayoutPageRevisionReproductionTest.JSON);
  private final UiLayoutPageChildCaptures captures = new UiLayoutPageChildCaptures(hashes);
  private final UiLayoutTarget page = UiLayoutPageRevisionReproductionTest.PAGE;
  private final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("actor", "tenant", "unit", "test", "context", new UiLayoutCompositionRegistration(page, List.of(page)));
  private final UUID draft = UUID.randomUUID();
  private final Map<UiLayoutPageChildSource, UiLayoutPageChildSeed> registered = new HashMap<>();
  private final List<UiLayoutPageChildSource> resolutions = new ArrayList<>();
  private boolean revoked;
  private final UiLayoutBaselineMetadataAdmission admission = (i,t,a,s,b,m) -> { if (revoked) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Revoked"); };
  private final UiLayoutDraftWorkspaceSource source = new UiLayoutDraftWorkspaceSource() {
    public UiLayoutDraftWorkspaceSeed capture(UiLayoutLifecycleInvocation i, UiLayoutValidationContext v) { throw new UnsupportedOperationException(); }
    public UiLayoutPageChildSeed capturePageChild(UiLayoutLifecycleInvocation i, UiLayoutTarget p, UiLayoutPageChildSource request, UiLayoutValidationContext v) {
      resolutions.add(request); return registered.get(request);
    }
    public void requirePageChild(UiLayoutLifecycleInvocation i, UiLayoutTarget p, UiLayoutPageChildSeed seed, UiLayoutValidationContext v) {
      if (revoked || !registered.containsKey(seed.request())) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "Unknown source");
    }
  };
  private UiLayoutValidationContext validation() {
    return new UiLayoutValidationContext(UiLayoutValidationAttempt.start(invocation, UiLayoutLifecycleOperation.EDIT_DRAFT, Duration.ofSeconds(30)), UiLayoutValidationPurpose.AUTHORING);
  }
  private UiLayoutBaselineMetadataSeed metadata(JsonNode document, String id) {
    return new UiLayoutBaselineMetadataSeed(new UiLayoutBaselineMetadataSeed.NativeDocumentSource("service", id, "r1", "producer:v1", "publication:r1"),
        new UiLayoutBaselineMetadataSeed.NativeIdentity(), new UiLayoutBaselineMetadataSeed.Observation("actor", "unit", "context", "policy", "1", Instant.parse("2026-10-22T00:00:00.123456789Z")), document.toString());
  }
  private UiLayoutPageChildSeed register(String key, JsonNode document) {
    var request = new UiLayoutPageChildSource(key, new UiLayoutPageChildSource.NativeSource("registered-native", "fixture:" + key, "r1"));
    var seed = new UiLayoutPageChildSeed(request, new UiLayoutAuthoringDocumentDescriptor(document.path("kind").asText(), "fixture:native", "1"),
        UiLayoutPageRevisionReproductionTest.PATCH, "fixture:" + key, document, metadata(document, request.source().sourceId()));
    registered.put(request, seed); return seed;
  }
  private List<UiLayoutPageChildEvidence> initial(ObjectNode baseline) {
    var children = UiLayoutPageRevisionReproduction.children(baseline).entrySet().stream().map(e -> register(e.getKey(), e.getValue())).toList();
    var seed = new UiLayoutDraftWorkspaceSeed.TargetSeed(page, UiLayoutPageRevisionReproductionTest.DESCRIPTOR,
        UiLayoutPageRevisionReproductionTest.PATCH, "fixture:page", baseline, metadata(baseline, "fixture:page"), children);
    return captures.initial(invocation, draft, seed, source, admission, validation());
  }
  private UiLayoutDraftWorkspaceDocument.TargetState state(JsonNode baseline, JsonNode working, List<UiLayoutPageChildEvidence> evidence) {
    return new UiLayoutDraftWorkspaceDocument.TargetState(page, UiLayoutPageRevisionReproductionTest.DESCRIPTOR, UiLayoutPageRevisionReproductionTest.PATCH,
        new UiLayoutDraftWorkspaceDocument.BaselineDocument("fixture:page", hashes.sha256Exact(baseline), baseline),
        new UiLayoutDraftWorkspaceDocument.WorkingDocument(hashes.sha256Exact(working), working), null, null, evidence);
  }
  @Test void capturesRoundTripInWorkspaceAndPreserveRemovedChildForRestoration() throws Exception {
    var baseline = UiLayoutPageRevisionReproductionTest.page(); var evidence = initial(baseline);
    var codec = new UiLayoutDraftWorkspaceCodec(UiLayoutPageRevisionReproductionTest.JSON, hashes);
    var workspace = new UiLayoutDraftWorkspaceDocument(UiLayoutDraftWorkspaceDocument.SCHEMA_VERSION, List.of(state(baseline, baseline, evidence)), null);
    var recovered = codec.decode(codec.encode(workspace), invocation.composition()).targets().getFirst();
    assertEquals(evidence, recovered.pageChildren());
    var removed = baseline.deepCopy(); ((ObjectNode) removed.at("/config/definition")).putArray("widgets");
    var retained = captures.revision(invocation, draft, recovered, removed, null, source, admission, validation());
    assertEquals(evidence, retained);
    var restored = captures.revision(invocation, draft, state(baseline, removed, retained), baseline, null, source, admission, validation());
    assertEquals(evidence, restored); assertTrue(resolutions.isEmpty());
    revoked = true;
    assertThrows(UiLayoutLifecycleException.class, () -> captures.revision(invocation, draft, state(baseline, removed, retained), baseline, null, source, admission, validation()));
  }
  @Test void newKeysRequireExactServerResolvedSourcesAndKeepB0ForLaterEdits() throws Exception {
    var baseline = UiLayoutPageRevisionReproductionTest.page(); var evidence = initial(baseline);
    var candidate = baseline.deepCopy();
    var widgets = (com.fasterxml.jackson.databind.node.ArrayNode) candidate.at("/config/definition/widgets");
    var added = ((ObjectNode) widgets.get(0)).deepCopy().put("key", "44444444-4444-4444-8444-444444444444"); widgets.add(added);
    var seed = register(added.path("key").asText(), UiLayoutPageRevisionReproduction.nativeChild(added));
    var state = state(baseline, baseline, evidence);
    assertThrows(UiLayoutLifecycleException.class, () -> captures.revision(invocation, draft, state, candidate, null, source, admission, validation()));
    assertThrows(UiLayoutLifecycleException.class, () -> captures.revision(invocation, draft, state, candidate, List.of(seed.request(),seed.request()), source, admission, validation()));
    var accepted = captures.revision(invocation, draft, state, candidate, List.of(seed.request()), source, admission, validation());
    assertEquals(3, accepted.size()); assertEquals(List.of(seed.request()), resolutions);
    var changed = candidate.deepCopy(); ((ObjectNode) changed.at("/config/definition/widgets/2/definition/inputs")).put("mode", "edit");
    assertThrows(UiLayoutLifecycleException.class, () -> captures.revision(invocation, draft, state(baseline,candidate,accepted), changed, null, source, admission, validation()));
    assertThrows(UiLayoutLifecycleException.class, () -> captures.revision(invocation, draft, state(baseline,candidate,accepted), candidate, List.of(seed.request()), source, admission, validation()));
  }
  @Test void missingInitialEvidenceAndWrongDraftOrHashAreRejected() throws Exception {
    var baseline = UiLayoutPageRevisionReproductionTest.page();
    var noEvidence = new UiLayoutDraftWorkspaceSeed.TargetSeed(page, UiLayoutPageRevisionReproductionTest.DESCRIPTOR,
        UiLayoutPageRevisionReproductionTest.PATCH, "fixture:page", baseline, metadata(baseline,"fixture:page"));
    assertThrows(UiLayoutLifecycleException.class, () -> captures.initial(invocation,draft,noEvidence,source,admission,validation()));
    var evidence = initial(baseline);
    assertThrows(UiLayoutLifecycleException.class, () -> captures.integrity(UUID.randomUUID(),page,baseline,baseline,evidence));
    var first = evidence.getFirst(); var corrupted = (ObjectNode) UiLayoutPageRevisionReproductionTest.JSON.readTree(first.captureText()); corrupted.put("rawInputHash", "0".repeat(64));
    var changed = new ArrayList<>(evidence); changed.set(0,new UiLayoutPageChildEvidence(first.request(),first.patch(),corrupted.toString()));
    assertThrows(UiLayoutLifecycleException.class, () -> captures.integrity(draft,page,baseline,baseline,changed));
  }
  @Test void clientAttestationAndUnknownPropertiesNeverEnterTheSourceSpi() throws Exception {
    var decoder = new UiLayoutLifecycleRequestDecoder(UiLayoutPageRevisionReproductionTest.JSON);
    ObjectNode command = UiLayoutPageRevisionReproductionTest.JSON.createObjectNode().put("commandRef",UUID.randomUUID().toString()).put("reason","test");
    command.set("target",UiLayoutPageRevisionReproductionTest.JSON.valueToTree(page)); command.set("authoringDocument",UiLayoutPageRevisionReproductionTest.page());
    var declared = command.putArray("childSources").addObject().put("widgetKey","44444444-4444-4444-8444-444444444444");
    declared.putObject("source").put("kind","registered-native").put("sourceId","fixture").put("revision","1");
    assertNotNull(decoder.decode(command.toString(),org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest.class));
    declared.put("attested",true);
    assertThrows(UiLayoutLifecycleException.class,()->decoder.decode(command.toString(),org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest.class));
    assertTrue(resolutions.isEmpty());
  }
  @Test void sourceReferencesUseP0UnicodeCodePointLimits() {
    assertNotNull(new UiLayoutPageChildSource.NativeSource("registered-native", "😀".repeat(200), "r1"));
    assertThrows(IllegalArgumentException.class, () -> new UiLayoutPageChildSource.NativeSource("registered-native", "😀".repeat(256), "r1"));
  }
}
