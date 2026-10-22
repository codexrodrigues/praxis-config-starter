package org.praxisplatform.config.service;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.dto.UiLayoutPageChildSource;
import org.praxisplatform.config.dto.UiLayoutTarget;

/** Extends the existing capture contract for internal page children, without changing external targets. */
final class UiLayoutPageChildCaptures {
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .addMixIn(UiLayoutBaselineMetadataSeed.Source.class, SourceKinds.class)
      .addMixIn(UiLayoutBaselineMetadataSeed.Reproduction.class, ReproductionKinds.class);
  static ObjectMapper evidenceMapper() { return JSON.copy(); }
  static UiLayoutPageChildSeed capturedSeed(UiLayoutPageChildEvidence entry) {
    var codec = new UiLayoutPageChildCaptures(new CanonicalJsonHashService(JSON));
    return codec.seed(entry, codec.read(entry));
  }
  private final CanonicalJsonHashService hashes;
  private final UiLayoutMetadataCaptureCodec codec;

  UiLayoutPageChildCaptures(CanonicalJsonHashService hashes) { this.hashes = hashes; this.codec = new UiLayoutMetadataCaptureCodec(JSON, hashes); }

  List<UiLayoutPageChildEvidence> initial(UiLayoutLifecycleInvocation invocation, UUID draftId,
      UiLayoutDraftWorkspaceSeed.TargetSeed target, UiLayoutDraftWorkspaceSource source,
      UiLayoutBaselineMetadataAdmission admission, UiLayoutValidationContext validation) {
    if (!UiLayoutPageRevisionReproduction.TYPE.equals(target.target().componentType())) {
      if (!target.pageChildren().isEmpty()) throw invalid();
      return List.of();
    }
    UiLayoutPageRevisionReproduction.requireNative(target.target(), target.authoring(), target.document(), target.document(), target.patch());
    var children = UiLayoutPageRevisionReproduction.children(target.document());
    var entries = new ArrayList<UiLayoutPageChildEvidence>();
    var keys = new HashSet<String>();
    for (var seed : target.pageChildren()) {
      var key = seed.request().widgetKey();
      if (!children.containsKey(key) || !keys.add(key)) throw invalid();
      if (!hashes.sha256Exact(children.get(key)).equals(hashes.sha256Exact(seed.document()))) throw invalid();
      entries.add(seal(invocation, draftId, target.target(), seed, source, admission, validation));
    }
    if (!keys.equals(children.keySet())) throw invalid();
    budget(entries);
    return List.copyOf(entries);
  }

  List<UiLayoutPageChildEvidence> revision(UiLayoutLifecycleInvocation invocation, UUID draftId,
      UiLayoutDraftWorkspaceDocument.TargetState state, JsonNode candidate, List<UiLayoutPageChildSource> requests,
      UiLayoutDraftWorkspaceSource source, UiLayoutBaselineMetadataAdmission admission, UiLayoutValidationContext validation) {
    if (!UiLayoutPageRevisionReproduction.TYPE.equals(state.target().componentType())) {
      if (requests != null || !state.pageChildren().isEmpty()) throw invalid();
      return List.of();
    }
    var children = UiLayoutPageRevisionReproduction.children(candidate);
    var evidence = new LinkedHashMap<String, UiLayoutPageChildEvidence>();
    for (var entry : state.pageChildren()) if (evidence.put(entry.request().widgetKey(), entry) != null) throw invalid();
    var declared = new LinkedHashMap<String, UiLayoutPageChildSource>();
    for (var request : requests == null ? List.<UiLayoutPageChildSource>of() : requests) {
      if (request == null || !children.containsKey(request.widgetKey()) || evidence.containsKey(request.widgetKey())
          || declared.put(request.widgetKey(), request) != null) throw invalid();
    }
    var unknown = new HashSet<>(children.keySet()); unknown.removeAll(evidence.keySet());
    if (!unknown.equals(declared.keySet())) throw invalid();
    // Existing evidence is preserved even while its child is removed. It cannot be replaced by a new declaration.
    for (var request : declared.values()) {
      UiLayoutPageChildSeed seed;
      try { seed = validation.call(invocation, () -> source.capturePageChild(invocation, state.target(), request, validation)); }
      catch (RuntimeException exception) { throw UiLayoutMetadataAdmissionFailure.sanitized(exception, "Page child source is unavailable."); }
      if (seed == null || !request.equals(seed.request())) throw invalid();
      evidence.put(request.widgetKey(), seal(invocation, draftId, state.target(), seed, source, admission, validation));
    }
    var result = List.copyOf(evidence.values());
    verify(invocation, draftId, state.target(), state.baseline().document(), candidate, result, source, admission, validation);
    return result;
  }

  void verify(UiLayoutLifecycleInvocation invocation, UUID draftId, UiLayoutTarget page, JsonNode baseline, JsonNode candidate,
      List<UiLayoutPageChildEvidence> entries, UiLayoutDraftWorkspaceSource source,
      UiLayoutBaselineMetadataAdmission admission, UiLayoutValidationContext validation) {
    if (!UiLayoutPageRevisionReproduction.TYPE.equals(page.componentType())) {
      if (!entries.isEmpty()) throw invalid();
      return;
    }
    integrity(draftId, page, baseline, candidate, entries);
    var children = UiLayoutPageRevisionReproduction.children(candidate);
    for (var entry : entries) {
      var capture = read(entry);
      if (!invocation.tenantId().equals(capture.binding().scope().tenantId())
          || !invocation.environment().equals(capture.binding().scope().environment())) throw invalid();
      if (!children.containsKey(entry.request().widgetKey())) continue;
      var seed = seed(entry, capture);
      requireAdmission(invocation, page, seed, source, admission, validation);
      codec.verifyAndRead(invocation, binding(invocation, draftId, page, seed), capture,
          (current, ignored) -> requireAdmission(current, page, seed, source, admission, validation));
    }
    validation.require(invocation);
  }

  /** Persisted corruption is state failure; this boundary performs no current-source admission. */
  void historicalIntegrity(UUID draftId, UiLayoutTarget page, JsonNode baseline, JsonNode candidate,
      List<UiLayoutPageChildEvidence> entries) {
    try { integrity(draftId, page, baseline, candidate, entries); }
    catch (RuntimeException exception) {
      throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE,
          "Historical page child evidence is unavailable or inconsistent.");
    }
  }

  /** Historical syntax/reproduction only; current host admission is still mandatory on mutations. */
  void integrity(UUID draftId, UiLayoutTarget page, JsonNode baseline, JsonNode candidate, List<UiLayoutPageChildEvidence> entries) {
    if (!UiLayoutPageRevisionReproduction.TYPE.equals(page.componentType())) { if (!entries.isEmpty()) throw invalid(); return; }
    var original = UiLayoutPageRevisionReproduction.children(baseline);
    var children = UiLayoutPageRevisionReproduction.children(candidate);
    var keys = new HashSet<String>();
    for (var entry : entries) {
      String key = entry.request().widgetKey();
      if (!keys.add(key)) throw invalid();
      var capture = read(entry);
      var binding = capture.binding();
      if ((draftId != null && !draftId.equals(binding.sourceDraftRef())) || !page.equals(binding.scope().root())
          || !key.equals(binding.scope().target().componentId())) throw invalid();
      var seed = seed(entry, capture);
      requireDescriptor(seed);
      if (!childTarget(seed).equals(binding.scope().target())) throw invalid();
      var resealed = codec.seal(capture.captureRef(), binding, capture.baselineDocument(), capture.source(), capture.reproduction(), capture.observation(), capture.rawInputText());
      if (!capture.equals(resealed)) throw invalid();
      if (original.containsKey(key) && !hashes.sha256Exact(original.get(key)).equals(binding.baselineContentHash())) throw invalid();
      if (children.containsKey(key)) UiLayoutPageRevisionReproduction.requireChild(capture.baselineDocument(), children.get(key), key);
    }
    if (!keys.containsAll(original.keySet()) || !keys.containsAll(children.keySet())) throw invalid();
    budget(entries);
  }

  private UiLayoutPageChildEvidence seal(UiLayoutLifecycleInvocation invocation, UUID draftId, UiLayoutTarget page,
      UiLayoutPageChildSeed seed, UiLayoutDraftWorkspaceSource source, UiLayoutBaselineMetadataAdmission admission,
      UiLayoutValidationContext validation) {
    requireDescriptor(seed);
    codec.requireSeedSyntax(seed.metadata(), seed.document());
    var observation = seed.metadata().observation();
    if (!invocation.actorRef().equals(observation.actorRef()) || !invocation.administrativeUnit().equals(observation.administrativeUnit())
        || !invocation.contextVersion().equals(observation.contextVersion())) throw invalid();
    requireAdmission(invocation, page, seed, source, admission, validation);
    var capture = codec.seal(UUID.randomUUID(), binding(invocation, draftId, page, seed), seed.document(),
        seed.metadata().source(), seed.metadata().reproduction(), observation, seed.metadata().rawInputText());
    try { return new UiLayoutPageChildEvidence(seed.request(), seed.patch(), JSON.writeValueAsString(capture)); }
    catch (Exception exception) { throw invalid(); }
  }

  private void requireDescriptor(UiLayoutPageChildSeed seed) {
    var nativeKind = seed.document().path("kind").asText();
    if (!nativeKind.equals(seed.authoring().documentType())) throw invalid();
    var target = childTarget(seed);
    UiLayoutAuthoringProjection.project(target, seed.authoring(), seed.document(), seed.document(), seed.patch(), () -> {});
  }

  private void requireAdmission(UiLayoutLifecycleInvocation invocation, UiLayoutTarget page, UiLayoutPageChildSeed seed,
      UiLayoutDraftWorkspaceSource source, UiLayoutBaselineMetadataAdmission admission, UiLayoutValidationContext validation) {
    try {
      validation.run(invocation, () -> source.requirePageChild(invocation, page, seed, validation));
      validation.run(invocation, () -> admission.require(invocation, childTarget(seed), seed.authoring(), seed.sourceRef(), seed.document(), seed.metadata()));
    } catch (RuntimeException exception) { throw UiLayoutMetadataAdmissionFailure.sanitized(exception, "Page child admission is unavailable."); }
  }

  private UiLayoutMetadataCapture.Binding binding(UiLayoutLifecycleInvocation invocation, UUID draftId, UiLayoutTarget page, UiLayoutPageChildSeed seed) {
    return new UiLayoutMetadataCapture.Binding(draftId,
        new UiLayoutMetadataCapture.Scope(invocation.tenantId(), invocation.environment(), page, childTarget(seed)),
        seed.sourceRef(), seed.authoring(), hashes.sha256Exact(seed.document()));
  }
  private UiLayoutTarget childTarget(UiLayoutPageChildSeed seed) {
    return new UiLayoutTarget("praxis.table.editor".equals(seed.authoring().documentType()) ? "praxis-table" : "praxis-dynamic-form", seed.request().widgetKey());
  }
  private UiLayoutPageChildSeed seed(UiLayoutPageChildEvidence entry, UiLayoutMetadataCapture capture) {
    return new UiLayoutPageChildSeed(entry.request(), capture.binding().authoring(), entry.patch(), capture.binding().baselineSourceRef(), capture.baselineDocument(),
        new UiLayoutBaselineMetadataSeed(capture.source(), capture.reproduction(), capture.observation(), capture.rawInputText()));
  }
  private UiLayoutMetadataCapture read(UiLayoutPageChildEvidence entry) {
    try { return JSON.readValue(entry.captureText(), UiLayoutMetadataCapture.class); }
    catch (Exception exception) { throw invalid(); }
  }
  static long bodyBytes(List<UiLayoutPageChildEvidence> entries) {
    return new UiLayoutPageChildCaptures(new CanonicalJsonHashService(JSON)).measure(entries);
  }
  private long measure(List<UiLayoutPageChildEvidence> entries) {
    // Captures across edits can have different observations; each remains exact. The aggregate byte quota is unchanged.
    long bytes = 0;
    for (var entry : entries) {
      var capture = read(entry);
      bytes += capture.rawInputText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (capture.reproduction() instanceof UiLayoutBaselineMetadataSeed.SchemaProjection projection)
        bytes += projection.assembly().inputText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    return bytes;
  }
  private void budget(List<UiLayoutPageChildEvidence> entries) {
    if (measure(entries) > UiLayoutMetadataCaptureCodec.MAX_COMPOSITION_BODY_BYTES) throw invalid();
  }
  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "Page child capture is missing, inconsistent or outside its admitted profile.");
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = UiLayoutBaselineMetadataSeed.OperationSource.class, name = "operation"),
      @JsonSubTypes.Type(value = UiLayoutBaselineMetadataSeed.NativeDocumentSource.class, name = "native-document")})
  private abstract static class SourceKinds {}
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  @JsonSubTypes({@JsonSubTypes.Type(value = UiLayoutBaselineMetadataSeed.SchemaProjection.class, name = "schema-projection"),
      @JsonSubTypes.Type(value = UiLayoutBaselineMetadataSeed.NativeIdentity.class, name = "native-identity")})
  private abstract static class ReproductionKinds {}
}
