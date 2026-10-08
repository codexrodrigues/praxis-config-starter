package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Internal syntax/integrity boundary. Not a schema validator, writer, provenance attestation or renderer. */
final class UiLayoutMetadataCaptureCodec {
  static final int MAX_BODY_BYTES = 256 * 1024;
  static final int MAX_COMPOSITION_BODY_BYTES = 1024 * 1024;
  static final int MAX_NESTING_DEPTH = 64;
  private final ObjectMapper strictMapper;
  private final CanonicalJsonHashService hashes;

  UiLayoutMetadataCaptureCodec(ObjectMapper mapper, CanonicalJsonHashService hashes) {
    this.strictMapper = strictMapper(mapper);
    this.hashes = hashes;
  }

  /** Capture syntax and tree identity must not depend on host JSON leniency or serialization defaults. */
  static ObjectMapper strictMapper(ObjectMapper mapper) {
    // This closed JSON boundary must not inherit host serializers or newer Jackson feature flags.
    var strict = new ObjectMapper()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    for (var feature : JsonReadFeature.values()) strict.getFactory().disable(feature.mappedFeature());
    strict.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(MAX_NESTING_DEPTH).maxStringLength(MAX_BODY_BYTES).maxNumberLength(256).build());
    return strict;
  }

  /** Seals an internal value; this does not authorize the source or persist a capture. */
  UiLayoutMetadataCapture seal(UUID captureRef, UiLayoutMetadataCapture.Binding binding, JsonNode baseline,
      UiLayoutBaselineMetadataSeed.Source source, UiLayoutBaselineMetadataSeed.Reproduction reproduction,
      UiLayoutBaselineMetadataSeed.Observation observation, String rawInputText) {
    try {
      byte[] raw = validateRaw(rawInputText);
      validateBaseline(binding, baseline);
      validateOrigin(source, reproduction, observation, rawInputText, baseline);
      return new UiLayoutMetadataCapture(captureRef, binding, baseline, source, reproduction, observation,
          rawInputText, digest(raw), assemblyHash(reproduction));
    } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED); }
  }

  /** Bounds/rejects foreign nodes before workspace serialization or host content inspection. */
  void requireSeedSyntax(UiLayoutBaselineMetadataSeed metadata, JsonNode baseline) {
    try {
      if (metadata == null) throw new IllegalArgumentException();
      validateRaw(metadata.rawInputText());
      validateBaselineDocument(baseline);
      validateOrigin(metadata.source(), metadata.reproduction(), metadata.observation(), metadata.rawInputText(), baseline);
    } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED); }
  }

  /** Requires exact expected binding and current scope; denial or final revocation returns no capture. */
  UiLayoutMetadataCapture verifyAndRead(UiLayoutLifecycleInvocation invocation,
      UiLayoutMetadataCapture.Binding expected, UiLayoutMetadataCapture stored, UiLayoutMetadataCaptureAccess access) {
    if (invocation == null || expected == null) throw failure(UiLayoutLifecycleException.Code.DENIED);
    var scope = expected.scope();
    if (!scope.tenantId().equals(invocation.tenantId()) || !scope.environment().equals(invocation.environment())
        || !scope.root().equals(invocation.rootTarget())) throw failure(UiLayoutLifecycleException.Code.DENIED);
    if (stored == null || !expected.equals(stored.binding())) throw failure(UiLayoutLifecycleException.Code.INVALID_STATE);
    var visibility = access == null ? UiLayoutMetadataCaptureAccess.denyAll() : access;
    requireAccess(visibility, invocation, stored);
    try {
      if (!digest(validateRaw(stored.rawInputText())).equals(stored.rawInputHash())) throw new IllegalArgumentException();
      if (!java.util.Objects.equals(assemblyHash(stored.reproduction()), stored.assemblyInputHash())) throw new IllegalArgumentException();
      validateBaseline(expected, stored.baselineDocument());
      validateOrigin(stored.source(), stored.reproduction(), stored.observation(), stored.rawInputText(), stored.baselineDocument());
    } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.INVALID_STATE); }
    requireAccess(visibility, invocation, stored);
    return stored;
  }

  /** Local byte quota and identity checks; the caller must still prove every registered target is present. */
  void requireCompositionBudget(List<UiLayoutMetadataCapture> captures) {
    try {
      if (captures == null || captures.isEmpty()) throw new IllegalArgumentException();
      var first = captures.getFirst().binding();
      var context = captures.getFirst().observation();
      var targets = new HashSet<org.praxisplatform.config.dto.UiLayoutTarget>();
      var captureRefs = new HashSet<UUID>();
      long total = 0;
      for (var capture : captures) {
        var binding = capture.binding();
        var scope = binding.scope();
        if (!first.sourceDraftRef().equals(binding.sourceDraftRef())
            || !first.scope().tenantId().equals(scope.tenantId())
            || !first.scope().environment().equals(scope.environment())
            || !first.scope().root().equals(scope.root()) || !targets.add(scope.target())
            || !captureRefs.add(capture.captureRef())
            || !context.actorRef().equals(capture.observation().actorRef())
            || !context.administrativeUnit().equals(capture.observation().administrativeUnit())
            || !context.contextVersion().equals(capture.observation().contextVersion())) throw new IllegalArgumentException();
        byte[] raw = validateRaw(capture.rawInputText());
        if (!digest(raw).equals(capture.rawInputHash())) throw new IllegalArgumentException();
        if (!java.util.Objects.equals(assemblyHash(capture.reproduction()), capture.assemblyInputHash())) throw new IllegalArgumentException();
        validateBaseline(binding, capture.baselineDocument());
        validateOrigin(capture.source(), capture.reproduction(), capture.observation(), capture.rawInputText(), capture.baselineDocument());
        total += raw.length;
        if (capture.reproduction() instanceof UiLayoutBaselineMetadataSeed.SchemaProjection projection) {
          total += validateRaw(projection.assembly().inputText()).length;
        }
        if (total > MAX_COMPOSITION_BODY_BYTES) throw new IllegalArgumentException();
      }
    } catch (Exception exception) { throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED); }
  }

  private void validateOrigin(UiLayoutBaselineMetadataSeed.Source source,
      UiLayoutBaselineMetadataSeed.Reproduction reproduction, UiLayoutBaselineMetadataSeed.Observation observation,
      String raw, JsonNode baseline) throws Exception {
    new UiLayoutBaselineMetadataSeed(source, reproduction, observation, raw);
    assemblyHash(reproduction);
    if (source instanceof UiLayoutBaselineMetadataSeed.NativeDocumentSource) {
      // Neither canonical hash nor host-configured sorted serialization establishes input order.
      if (!sameOrderedInput(strictMapper.readTree(raw), baseline)) {
        throw new IllegalArgumentException();
      }
    }
  }

  private String assemblyHash(UiLayoutBaselineMetadataSeed.Reproduction reproduction) throws Exception {
    return reproduction instanceof UiLayoutBaselineMetadataSeed.SchemaProjection projection
        ? digest(validateRaw(projection.assembly().inputText())) : null;
  }

  private static boolean sameOrderedInput(JsonNode input, JsonNode baseline) {
    if (input.getNodeType() != baseline.getNodeType()) return false;
    if (input.isObject()) {
      if (input.size() != baseline.size()) return false;
      var left = input.properties().iterator();
      var right = baseline.properties().iterator();
      while (left.hasNext()) {
        var inputField = left.next(); var baselineField = right.next();
        if (!inputField.getKey().equals(baselineField.getKey())
            || !sameOrderedInput(inputField.getValue(), baselineField.getValue())) return false;
      }
      return true;
    }
    if (input.isArray()) {
      if (input.size() != baseline.size()) return false;
      for (int index = 0; index < input.size(); index++) {
        if (!sameOrderedInput(input.get(index), baseline.get(index))) return false;
      }
      return true;
    }
    return input.equals(baseline);
  }

  private byte[] validateRaw(String text) throws Exception {
    byte[] bytes = utf8(text);
    if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException();
    JsonNode body = strictMapper.readTree(text);
    if (body == null || !body.isObject()) throw new IllegalArgumentException();
    validateUnicode(body, 1);
    return bytes;
  }

  private void validateBaseline(UiLayoutMetadataCapture.Binding binding, JsonNode baseline) throws Exception {
    if (binding == null) throw new IllegalArgumentException();
    validateBaselineDocument(baseline);
    if (!hashes.sha256Exact(baseline).equals(binding.baselineContentHash())) throw new IllegalArgumentException();
  }

  private void validateBaselineDocument(JsonNode baseline) throws Exception {
    if (baseline == null || !baseline.isObject()) throw new IllegalArgumentException();
    validateUnicode(baseline, 1);
    if (strictMapper.writeValueAsBytes(baseline).length > UiLayoutDraftWorkspaceCodec.MAX_DOCUMENT_BYTES) throw new IllegalArgumentException();
  }

  private void validateUnicode(JsonNode node, int depth) throws Exception {
    if (depth > MAX_NESTING_DEPTH) throw new IllegalArgumentException();
    // Reject foreign objects before any serializer can execute their getters.
    if (node.isPojo() || node.isBinary() || node.isMissingNode()) throw new IllegalArgumentException();
    if (node.isTextual()) utf8(node.textValue());
    if (node.isObject()) {
      for (var field : node.properties()) {
        utf8(field.getKey());
        validateUnicode(field.getValue(), field.getValue().isContainerNode() ? depth + 1 : depth);
      }
    } else if (node.isArray()) {
      for (var value : node) validateUnicode(value, value.isContainerNode() ? depth + 1 : depth);
    }
  }

  private static byte[] utf8(String text) throws Exception {
    if (text == null || text.length() > MAX_BODY_BYTES) throw new IllegalArgumentException();
    var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }

  private static String digest(byte[] raw) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
  }

  private static void requireAccess(UiLayoutMetadataCaptureAccess access, UiLayoutLifecycleInvocation invocation,
      UiLayoutMetadataCapture capture) {
    try { access.require(invocation, capture); }
    catch (Exception exception) {
      throw UiLayoutMetadataAdmissionFailure.sanitized(exception, "Metadata capture is unavailable.");
    }
  }

  private static UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code) {
    return new UiLayoutLifecycleException(code, "Metadata capture is unavailable.");
  }
}
