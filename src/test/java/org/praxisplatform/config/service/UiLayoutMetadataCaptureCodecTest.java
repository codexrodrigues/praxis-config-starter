package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutMetadataCaptureCodecTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final CanonicalJsonHashService hashes = new CanonicalJsonHashService(mapper);
  private final UiLayoutMetadataCaptureCodec codec = new UiLayoutMetadataCaptureCodec(mapper, hashes);
  private final UiLayoutTarget root = new UiLayoutTarget("praxis-table", "orders");
  private final UUID draft = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private final UiLayoutAuthoringDocumentDescriptor descriptor =
      new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "urn:table", "1");

  private JsonNode baseline() { return mapper.createObjectNode().put("kind", "praxis.table.editor").put("version", 1); }
  private UiLayoutMetadataCapture.Binding binding() { return binding(draft, "tenant", "lab", root, root, "source:b0", descriptor); }
  private UiLayoutMetadataCapture.Binding binding(UUID draftRef, String tenant, String environment,
      UiLayoutTarget rootTarget, UiLayoutTarget target, String source, UiLayoutAuthoringDocumentDescriptor authoring) {
    return new UiLayoutMetadataCapture.Binding(draftRef,
        new UiLayoutMetadataCapture.Scope(tenant, environment, rootTarget, target), source, authoring, hashes.sha256Exact(baseline()));
  }
  private UiLayoutBaselineMetadataSeed.Source source() {
    return new UiLayoutBaselineMetadataSeed.OperationSource("orders-service", "orders", "/orders/filter", "POST", "response",
        "urn:schema", "producer:trusted-fixture", "publication:fixture-only");
  }
  private UiLayoutBaselineMetadataSeed.Reproduction reproduction() {
    return new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer:fixture", "projector:fixture", UiLayoutMetadataTestFixtures.assembly());
  }
  private UiLayoutBaselineMetadataSeed.Observation observation() {
    return new UiLayoutBaselineMetadataSeed.Observation("actor", "unit", "old-context", "private-policy", "revision-1",
        Instant.parse("2026-10-02T12:00:00Z"));
  }
  private UiLayoutMetadataCapture seal(String raw) {
    return codec.seal(UUID.randomUUID(), binding(), baseline(), source(), reproduction(), observation(), raw);
  }
  private UiLayoutLifecycleInvocation invocation(String tenant, String environment, UiLayoutTarget rootTarget) {
    return new UiLayoutLifecycleInvocation("reader", tenant, "unit", environment, "current-context",
        new UiLayoutCompositionRegistration(rootTarget, List.of(rootTarget)));
  }
  private UiLayoutLifecycleInvocation invocation() { return invocation("tenant", "lab", root); }
  private UiLayoutMetadataCapture copy(UiLayoutMetadataCapture original, JsonNode baseline, String raw, String digest) {
    return new UiLayoutMetadataCapture(original.captureRef(), original.binding(), baseline, original.source(),
        original.reproduction(), original.observation(), raw, digest, original.assemblyInputHash());
  }

  @Test void nativeOriginIdentityPreservesInputWithoutInventedPipeline() {
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(baseline(), "actor", "unit", "ctx");
    var stored = codec.seal(UUID.randomUUID(), binding(), baseline(), metadata.source(), metadata.reproduction(),
        metadata.observation(), " \n" + metadata.rawInputText() + " \t");
    assertThat(codec.verifyAndRead(invocation(), binding(), stored, (current, capture) -> {})).isEqualTo(stored);
    assertThat(stored.source()).isInstanceOf(UiLayoutBaselineMetadataSeed.NativeDocumentSource.class);
    assertThat(stored.reproduction()).isEqualTo(new UiLayoutBaselineMetadataSeed.NativeIdentity());
  }

  @ParameterizedTest @ValueSource(strings = {"{\"version\":1,\"kind\":\"praxis.table.editor\"}", "{\"kind\":\"different\",\"version\":1}"})
  void rejectsNativeInputReorderedOrDifferentDespiteCanonicalHash(String raw) {
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(baseline(), "actor", "unit", "ctx");
    assertThatThrownBy(() -> codec.seal(UUID.randomUUID(), binding(), baseline(), metadata.source(), metadata.reproduction(),
        metadata.observation(), raw)).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
  }

  @Test void rejectsNativeReorderingEvenWhenHostSerializationSortsProperties() throws Exception {
    var sortingMapper = mapper.copy().configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.WRITE_PROPERTIES_SORTED, true);
    var sortedCodec = new UiLayoutMetadataCaptureCodec(sortingMapper, hashes);
    String reversed = "{\"version\":1,\"kind\":\"praxis.table.editor\"}";
    assertThat(sortingMapper.writeValueAsString(sortingMapper.readTree(reversed)))
        .isEqualTo(sortingMapper.writeValueAsString(baseline()));
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(baseline(), "actor", "unit", "ctx");
    assertThatThrownBy(() -> sortedCodec.seal(UUID.randomUUID(), binding(), baseline(), metadata.source(), metadata.reproduction(),
        metadata.observation(), reversed)).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
            error -> assertThat(error.getCode()).isEqualTo(UiLayoutLifecycleException.Code.VALIDATION_FAILED));
  }

  @Test void rejectsCrossOriginReproductionModes() {
    var nativeMetadata = UiLayoutMetadataTestFixtures.nativeMetadata(baseline(), "actor", "unit", "ctx");
    assertThatThrownBy(() -> new UiLayoutBaselineMetadataSeed(source(), new UiLayoutBaselineMetadataSeed.NativeIdentity(), observation(), "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UiLayoutBaselineMetadataSeed(nativeMetadata.source(), reproduction(), observation(), "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void preservesRawWhitespaceUnicodeAndPropertyOrderWithTwoReadChecks() {
    String raw = " \n{\"properties\":{\"b\":{\"title\":\"Ação 😀\"},\"a\":{}}} \t";
    var capture = seal(raw);
    var checks = new AtomicInteger();
    var read = codec.verifyAndRead(invocation(), binding(), capture, (current, value) -> checks.incrementAndGet());
    assertThat(read.rawInputText()).isEqualTo(raw);
    assertThat(read.observation().contextVersion()).isEqualTo("old-context");
    assertThat(checks.get()).isEqualTo(2);
  }

  @Test void separatesRawHashFromCanonicalHash() throws Exception {
    String left = "{\"properties\":{\"b\":{},\"a\":{}}}";
    String right = "{\"properties\":{\"a\":{},\"b\":{}}}";
    assertThat(hashes.sha256Exact(mapper.readTree(left))).isEqualTo(hashes.sha256Exact(mapper.readTree(right)));
    assertThat(seal(left).rawInputHash()).isNotEqualTo(seal(right).rawInputHash());
  }

  @Test void defensivelyCopiesBaselineAndRedactsAllRepresentations() {
    ObjectNode baseline = (ObjectNode) baseline();
    var capture = codec.seal(UUID.randomUUID(), binding(), baseline, source(), reproduction(), observation(), "{\"private-field\":{}}");
    baseline.put("kind", "tampered");
    ((ObjectNode) capture.baselineDocument()).put("kind", "tampered-again");
    assertThat(capture.baselineDocument().get("kind").asText()).isEqualTo("praxis.table.editor");
    assertThat(List.of(capture.toString(), capture.binding().toString(), capture.binding().scope().toString(),
        capture.source().toString(), capture.reproduction().toString(), capture.observation().toString()))
        .allSatisfy(text -> assertThat(text).doesNotContain("private-field", "private-policy", "orders-service"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "[]", "null", "42", "{} {}", "{\"a\":1,\"a\":2}",
      "{\"a\":{\"b\":1,\"b\":2}}", "{\"a\":1,\"\\u0061\":2}", "{\"private-field\":", "{\"a\":NaN}"})
  void rejectsInvalidJsonWithoutEchoingContent(String raw) {
    assertFailure(() -> seal(raw), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"a\":\"\\uD800\"}", "{\"\\uDC00\":1}"})
  void rejectsEscapedUnpairedSurrogates(String raw) {
    assertFailure(() -> seal(raw), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void rejectsLiteralUnpairedSurrogatesWithoutUtf8Replacement() {
    assertFailure(() -> seal("{\"a\":\"" + (char) 0xd800 + "\"}"), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void acceptsExactByteLimitAndRejectsAdditionalOrMultibyteOverflow() {
    String exact = "{\"a\":\"" + "a".repeat(UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES - 8) + "\"}";
    assertThat(seal(exact).rawInputText()).isEqualTo(exact);
    assertFailure(() -> seal(exact + " "), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    String multibyte = "{\"a\":\"" + "é".repeat(UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES / 2) + "\"}";
    assertThat(multibyte.length()).isLessThan(UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES);
    assertFailure(() -> seal(multibyte), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void limitsNestingLocallyWithoutChangingTheSharedMapper() throws Exception {
    String tooDeep = "{\"a\":".repeat(UiLayoutMetadataCaptureCodec.MAX_NESTING_DEPTH) + "{}"
        + "}".repeat(UiLayoutMetadataCaptureCodec.MAX_NESTING_DEPTH);
    assertThat(mapper.readTree(tooDeep).isObject()).isTrue();
    assertFailure(() -> seal(tooDeep), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    assertThat(mapper.readTree("{\"a\":1,\"a\":2}").get("a").asInt()).isEqualTo(2);
  }

  @Test void validatesBaselineHashBeforeSealing() {
    ObjectNode changed = (ObjectNode) baseline(); changed.put("kind", "private-value");
    assertFailure(() -> codec.seal(UUID.randomUUID(), binding(), changed, source(), reproduction(), observation(), "{}"),
        UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void rejectsMissingAccessAndFinalRevocation() {
    var capture = seal("{\"private-field\":{}}");
    assertFailure(() -> codec.verifyAndRead(invocation(), binding(), capture, null), UiLayoutLifecycleException.Code.DENIED);
    var calls = new AtomicInteger();
    assertFailure(() -> codec.verifyAndRead(invocation(), binding(), capture, (current, value) -> {
      if (calls.incrementAndGet() == 2) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.DENIED, "private-field revoked");
    }), UiLayoutLifecycleException.Code.DENIED);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test void rejectsAllExpectedBindingDivergences() {
    var capture = seal("{}");
    var otherTarget = new UiLayoutTarget("praxis-table", "other");
    var otherContract = new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "urn:other", "1");
    var mismatches = List.of(
        binding(UUID.randomUUID(), "tenant", "lab", root, root, "source:b0", descriptor),
        binding(draft, "other-tenant", "lab", root, root, "source:b0", descriptor),
        binding(draft, "tenant", "other-env", root, root, "source:b0", descriptor),
        binding(draft, "tenant", "lab", otherTarget, root, "source:b0", descriptor),
        binding(draft, "tenant", "lab", root, otherTarget, "source:b0", descriptor),
        binding(draft, "tenant", "lab", root, root, "source:other", descriptor),
        binding(draft, "tenant", "lab", root, root, "source:b0", otherContract));
    for (var mismatch : mismatches) {
      var current = invocation(mismatch.scope().tenantId(), mismatch.scope().environment(), mismatch.scope().root());
      assertFailure(() -> codec.verifyAndRead(current, mismatch, capture, (scope, value) -> {}),
          UiLayoutLifecycleException.Code.INVALID_STATE);
    }
  }

  @Test void deniesInvocationScopeMismatchBeforeCallingAccess() {
    var calls = new AtomicInteger(); var capture = seal("{}");
    assertFailure(() -> codec.verifyAndRead(invocation("other", "lab", root), binding(), capture,
        (current, value) -> calls.incrementAndGet()), UiLayoutLifecycleException.Code.DENIED);
    assertThat(calls.get()).isZero();
  }

  @Test void rejectsStoredRawAndBaselineCorruptionAfterInitialAccess() {
    var capture = seal("{}");
    assertFailure(() -> codec.verifyAndRead(invocation(), binding(), copy(capture, baseline(), "{} ", capture.rawInputHash()),
        (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
    ObjectNode changed = (ObjectNode) baseline(); changed.put("private-field", true);
    assertFailure(() -> codec.verifyAndRead(invocation(), binding(), copy(capture, changed, "{}", capture.rawInputHash()),
        (current, value) -> {}), UiLayoutLifecycleException.Code.INVALID_STATE);
  }

  @Test void enforcesAggregateQuotaAndUniqueTargets() {
    var captures = new ArrayList<UiLayoutMetadataCapture>();
    int assemblyBytes = UiLayoutMetadataTestFixtures.assembly().inputText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    String exact = "{\"a\":\"" + "a".repeat(UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES - 8 - assemblyBytes) + "\"}";
    for (int i = 0; i < 5; i++) {
      var target = new UiLayoutTarget("praxis-table", "orders-" + i);
      captures.add(codec.seal(UUID.randomUUID(), binding(draft, "tenant", "lab", root, target, "source:b0", descriptor),
          baseline(), source(), reproduction(), observation(), exact));
    }
    codec.requireCompositionBudget(captures.subList(0, 4));
    var overByOne = new ArrayList<>(captures.subList(0, 4));
    var first = captures.getFirst();
    overByOne.set(0, codec.seal(first.captureRef(), first.binding(), baseline(), source(), reproduction(), observation(), exact + " "));
    assertFailure(() -> codec.requireCompositionBudget(overByOne), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    assertFailure(() -> codec.requireCompositionBudget(captures), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    assertFailure(() -> codec.requireCompositionBudget(List.of(captures.getFirst(), captures.getFirst())),
        UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void rejectsCompositionScopeMismatchAndCorruption() {
    var capture = seal("{}");
    var other = codec.seal(UUID.randomUUID(), binding(UUID.randomUUID(), "tenant", "lab", root,
        new UiLayoutTarget("praxis-table", "second"), "source:b0", descriptor), baseline(), source(), reproduction(), observation(), "{}");
    assertFailure(() -> codec.requireCompositionBudget(List.of(capture, other)), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    assertFailure(() -> codec.requireCompositionBudget(List.of(copy(capture, baseline(), "{} ", capture.rawInputHash()))),
        UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void preservesDeclarativeExecutableTextWithoutEvaluatingIt() {
    String raw = "{\"properties\":{\"code\":{\"x-ui\":{\"conditionalValidation\":\"() => { throw 'private'; }\"}}}}";
    assertThat(seal(raw).rawInputText()).isEqualTo(raw);
  }

  @Test void rejectsInvalidIdentifierUnicodeAndPaddingWithoutNormalization() {
    String invalid = "private" + (char) 0xd800;
    assertThatThrownBy(() -> new UiLayoutMetadataCapture.Scope(invalid, "lab", root, root))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Metadata capture identity is invalid.");
    assertThatThrownBy(() -> new UiLayoutMetadataCapture.Scope(" tenant", "lab", root, root))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Metadata capture identity is invalid.");
  }

  @Test void rejectsForeignBaselineObjectsBeforeExecutingTheirSerializer() {
    var executions = new AtomicInteger();
    Object secret = new Object() {
      public String getValue() { executions.incrementAndGet(); return "private-value"; }
    };
    ObjectNode foreign = (ObjectNode) baseline(); foreign.putPOJO("foreign", secret);
    assertFailure(() -> codec.seal(UUID.randomUUID(), binding(), foreign, source(), reproduction(), observation(), "{}"),
        UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    assertThat(executions.get()).isZero();
  }

  @Test void rejectsMixedObservationContextsAndDuplicateCaptureIdentities() {
    var first = seal("{}");
    var secondBinding = binding(draft, "tenant", "lab", root, new UiLayoutTarget("praxis-table", "second"), "source:b0", descriptor);
    var changedContext = new UiLayoutBaselineMetadataSeed.Observation("actor", "unit", "other-context", "private-policy", "revision-1", Instant.now());
    var second = codec.seal(UUID.randomUUID(), secondBinding, baseline(), source(), reproduction(), changedContext, "{}");
    assertFailure(() -> codec.requireCompositionBudget(List.of(first, second)), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    var duplicateIdentity = codec.seal(first.captureRef(), secondBinding, baseline(), source(), reproduction(), observation(), "{}");
    assertFailure(() -> codec.requireCompositionBudget(List.of(first, duplicateIdentity)), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    // Policy evidence may differ by resource; the shared actor/unit/context must not.
    var otherPolicy = new UiLayoutBaselineMetadataSeed.Observation("actor", "unit", "old-context", "other-policy", "revision-2", Instant.now());
    codec.requireCompositionBudget(List.of(first, codec.seal(UUID.randomUUID(), secondBinding, baseline(), source(), reproduction(), otherPolicy, "{}")));
  }

  @Test void capturesExactAssemblyInputsSeparatelyFromSchemaAndRedactsContent() {
    String text = " \n{\"config\":{\"private\":\"Ação 😀\"},\"bindings\":{\"resourcePath\":\"/orders\"}} \t";
    var assembly = new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, "assembler:full-native-v1", text);
    var projection = new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer", "projector", assembly);
    var stored = codec.seal(UUID.randomUUID(), binding(), baseline(), source(), projection, observation(), "{}");
    assertThat(((UiLayoutBaselineMetadataSeed.SchemaProjection) stored.reproduction()).assembly()).isEqualTo(assembly);
    assertThat(stored.assemblyInputHash()).hasSize(64).isNotEqualTo(stored.rawInputHash());
    assertThat(stored.rawInputText()).isEqualTo("{}");
    assertThat(assembly.toString()).isEqualTo("DocumentAssembly[content redacted]");
    assertThat(codec.verifyAndRead(invocation(), binding(), stored, (current, value) -> {})).isEqualTo(stored);
  }

  @ParameterizedTest @ValueSource(strings = {"[]", "null", "{\"a\":1,\"a\":2}", "{} {}", "{\"a\":NaN}"})
  void rejectsInvalidAssemblyJsonBeforeCapture(String text) {
    var projection = new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer", "projector",
        new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, "assembler", text));
    assertFailure(() -> codec.seal(UUID.randomUUID(), binding(), baseline(), source(), projection, observation(), "{}"),
        UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void rejectsUnsupportedOrMissingAssemblyEvidence() {
    assertThatThrownBy(() -> new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer", "projector", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UiLayoutBaselineMetadataSeed.DocumentAssembly(2, "assembler", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, " assembler", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void boundsAssemblyBytesDepthAndMalformedUnicode() {
    for (String text : List.of("{\"a\":\"" + "é".repeat(140000) + "\"}",
        "{\"a\":".repeat(65) + "{}" + "}".repeat(65), "{\"a\":\"" + (char) 0xd800 + "\"}")) {
      var projection = new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer", "projector",
          new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, "assembler", text));
      assertFailure(() -> codec.seal(UUID.randomUUID(), binding(), baseline(), source(), projection, observation(), "{}"),
          UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    }
  }

  @Test void rejectsTamperedAssemblyAfterInitialAdmission() {
    var original = seal("{}");
    var projection = new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer", "projector",
        new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, "assembler", "{\"changed\":true}"));
    var changed = new UiLayoutMetadataCapture(original.captureRef(), original.binding(), original.baselineDocument(), original.source(),
        projection, original.observation(), original.rawInputText(), original.rawInputHash(), original.assemblyInputHash());
    var admissions = new AtomicInteger();
    assertFailure(() -> codec.verifyAndRead(invocation(), binding(), changed, (current, value) -> admissions.incrementAndGet()),
        UiLayoutLifecycleException.Code.INVALID_STATE);
    assertThat(admissions.get()).isEqualTo(1);
    assertFailure(() -> codec.requireCompositionBudget(List.of(changed)), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void strictCaptureDoesNotInheritPermissiveJsonFromCorporateMapper() throws Exception {
    var permissive = mapper.copy();
    for (var feature : com.fasterxml.jackson.core.json.JsonReadFeature.values()) permissive.getFactory().enable(feature.mappedFeature());
    var strict = new UiLayoutMetadataCaptureCodec(permissive, hashes);
    for (String invalid : List.of("{'private':true}", "{/*comment*/\"a\":1}", "{unquoted:1}", "{\"a\":1,}", "{\"a\":NaN}")) {
      assertFailure(() -> strict.seal(UUID.randomUUID(), binding(), baseline(), source(), reproduction(), observation(), invalid),
          UiLayoutLifecycleException.Code.VALIDATION_FAILED);
      var projection = new UiLayoutBaselineMetadataSeed.SchemaProjection("normalizer", "projector",
          new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, "assembler", invalid));
      assertFailure(() -> strict.seal(UUID.randomUUID(), binding(), baseline(), source(), projection, observation(), "{}"),
          UiLayoutLifecycleException.Code.VALIDATION_FAILED);
    }
    assertThat(permissive.readTree("{'still':'permissive'}").path("still").asText()).isEqualTo("permissive");
  }

  @Test void nativeIdentityCannotDropNullPropertiesFromInput() {
    var host = mapper.copy().configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.READ_NULL_PROPERTIES, false);
    var strict = new UiLayoutMetadataCaptureCodec(host, hashes);
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(baseline(), "actor", "unit", "ctx");
    String input = "{\"kind\":\"praxis.table.editor\",\"version\":1,\"extra\":null}";
    assertFailure(() -> strict.seal(UUID.randomUUID(), binding(), baseline(), metadata.source(), metadata.reproduction(),
        metadata.observation(), input), UiLayoutLifecycleException.Code.VALIDATION_FAILED);
  }

  @Test void corporateMapperIsSharedByHashAndCaptureWithoutLosingNullOrUnicodeIdentity() throws Exception {
    var host = mapper.copy().configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.READ_NULL_PROPERTIES, false)
        .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.WRITE_NULL_PROPERTIES, false);
    host.getFactory().enable(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature());
    var corporateHashes = new CanonicalJsonHashService(host);
    var strict = new UiLayoutMetadataCaptureCodec(host, corporateHashes);
    var document = mapper.readTree("{\"kind\":\"praxis.table.editor\",\"ação\":null,\"label\":\"😀\"}");
    var expected = new UiLayoutMetadataCapture.Binding(draft,
        new UiLayoutMetadataCapture.Scope("tenant", "lab", root, root), "source:b0", descriptor,
        corporateHashes.sha256Exact(document));
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(document, "actor", "unit", "ctx");
    var stored = strict.seal(UUID.randomUUID(), expected, document, metadata.source(), metadata.reproduction(),
        metadata.observation(), metadata.rawInputText());
    assertThat(strict.verifyAndRead(invocation(), expected, stored, (current, capture) -> {}).baselineDocument())
        .isEqualTo(document);
    assertThat(expected.baselineContentHash()).isEqualTo(hashes.sha256Exact(document));
    assertThat(host.readTree("{\"optional\":null}").has("optional")).isFalse();
  }

  @Test void privateClosedJsonMapperNeverExecutesHostTreeSerializers() {
    var module = new com.fasterxml.jackson.databind.module.SimpleModule();
    var serializerCalls = new AtomicInteger();
    module.addSerializer(JsonNode.class, new com.fasterxml.jackson.databind.JsonSerializer<JsonNode>() {
      @Override public void serialize(JsonNode value, com.fasterxml.jackson.core.JsonGenerator output,
          com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
        serializerCalls.incrementAndGet(); throw new java.io.IOException("Host serializer must not execute");
      }
    });
    var host = new ObjectMapper().registerModule(module);
    var strict = new UiLayoutMetadataCaptureCodec(host, new CanonicalJsonHashService(host));
    var metadata = UiLayoutMetadataTestFixtures.nativeMetadata(baseline(), "actor", "unit", "ctx");
    var sealed = strict.seal(UUID.randomUUID(), binding(), baseline(), metadata.source(), metadata.reproduction(),
        metadata.observation(), metadata.rawInputText());
    assertThat(strict.verifyAndRead(invocation(), binding(), sealed, (current, value) -> {}).baselineDocument()).isEqualTo(baseline());
    assertThat(serializerCalls.get()).isZero();
  }

  @Test void technicalAccessFailuresRemainUnavailableBeforeAndAfterVerification() {
    for (int failedCheck : new int[] {1, 2}) {
      var calls = new AtomicInteger();
      assertFailure(() -> codec.verifyAndRead(invocation(), binding(), seal("{}"), (current, value) -> {
        if (calls.incrementAndGet() == failedCheck) throw new IllegalStateException("private credential");
      }), UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE);
      assertThat(calls.get()).isEqualTo(failedCheck);
    }
  }

  @Test void declaredAdmissionCodesArePreservedButUnrelatedCodesAreUnavailable() {
    for (var code : UiLayoutLifecycleException.Code.values()) {
      var expected = switch (code) {
        case DENIED, CONTEXT_STALE, SOURCE_UNAVAILABLE, VALIDATION_FAILED -> code;
        default -> UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE;
      };
      assertFailure(() -> codec.verifyAndRead(invocation(), binding(), seal("{}"), (current, value) -> {
        throw new UiLayoutLifecycleException(code, "private policy details");
      }), expected);
    }
  }

  private static void assertFailure(Runnable work, UiLayoutLifecycleException.Code code) {
    assertThatThrownBy(work::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class, failure -> {
      assertThat(failure.getCode()).isEqualTo(code);
      assertThat(failure.getMessage()).isEqualTo("Metadata capture is unavailable.");
      assertThat(failure.getCause()).isNull();
    });
  }
}
