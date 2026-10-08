package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;

@Tag("unit")
class UiLayoutResolutionServiceTest {
    private static final Path CORPUS = Path.of("docs", "ai", "contracts", "enterprise-ui-layout-resolution-corpus.v1.json");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJsonHashService hashService = new CanonicalJsonHashService(objectMapper);

    @Test
    void executesEveryVersionedResolutionCase() throws Exception {
        JsonNode corpus = readCorpus();
        assertThat(corpus.path("schemaVersion").asText())
                .isEqualTo("praxis.enterprise-ui-layout-resolution-corpus/v1");
        assertThat(corpus.path("cases")).hasSize(12);

        for (JsonNode testCase : corpus.path("cases")) {
            execute(testCase, corpus.path("target"));
        }
    }

    @Test
    void requiresSchemaPermissionForNullRemoval() throws Exception {
        UiLayoutTarget target = new UiLayoutTarget("praxis-table", "table-config:orders");
        EffectiveUiAudience audience = audience(objectMapper.readTree(
                "{\"tenant\":\"tenant-a\",\"environment\":\"lab\",\"user\":\"user-a\","
                        + "\"groups\":[],\"currentContextVersion\":\"context-1\"}"));
        UiLayoutResolutionCandidate candidate = candidate(
                objectMapper.readTree(
                        "{\"revision\":\"rev-1\",\"layer\":\"TENANT\",\"priority\":0,"
                                + "\"selector\":{\"tenant\":\"tenant-a\"},\"patch\":{\"density\":null}}"),
                target);

        StubSource denied = new StubSource(List.of(candidate), Set.of("/density"), Set.of());
        assertThatThrownBy(() -> service(audience, denied).resolve(() -> "principal", "context-1", target))
                .isInstanceOfSatisfying(UiLayoutResolutionException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(UiLayoutResolutionException.Code.PROTECTED_PRESENTATION_INVARIANT);
                    assertThat(exception.path()).isEqualTo("/density");
                });

        StubSource allowed = new StubSource(List.of(candidate), Set.of("/density"), Set.of("/density"));
        assertThat(service(audience, allowed).resolve(() -> "principal", "context-1", target))
                .get()
                .extracting(response -> response.document().has("density"))
                .isEqualTo(false);
    }

    @Test
    void redactsAudienceStringRepresentationAndValidatesHierarchy() {
        EffectiveUiAudience audience = new EffectiveUiAudience(
                "tenant-a", "lab", "user-a", "org-a", "sector-a", "manager", Set.of("private-group"), "ctx-1");
        assertThat(audience.toString()).isEqualTo("EffectiveUiAudience[redacted]");
        assertThatThrownBy(() -> new EffectiveUiAudience(
                        "tenant-a", "lab", "user-a", null, "sector-a", null, Set.of(), "ctx-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sector requires organization.");
    }

    @Test
    void rejectsFalseRevisionAttestationAndInconsistentLayerSelector() throws Exception {
        UiLayoutTarget target = new UiLayoutTarget("praxis-table", "table-config:orders");
        JsonNode patch = objectMapper.readTree("{\"density\":\"compact\"}");
        UiLayoutAudienceSelector tenantSelector = new UiLayoutAudienceSelector(
                "tenant-a", null, null, null, null, null);
        UiLayoutResolutionCandidate falseAttestation = new UiLayoutResolutionCandidate(
                target,
                "rev-1",
                "not-the-patch-hash",
                UiLayoutResolutionCandidate.LayerClass.TENANT,
                (short) 0,
                tenantSelector,
                patch,
                null);
        EffectiveUiAudience audience = new EffectiveUiAudience(
                "tenant-a", "lab", "user-a", null, null, null, Set.of(), "context-1");

        assertThatThrownBy(() -> service(
                                audience, new StubSource(List.of(falseAttestation), Set.of("/density"), Set.of()))
                        .resolve(principal(), "context-1", target))
                .isInstanceOfSatisfying(UiLayoutResolutionException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(UiLayoutResolutionException.Code.LAYOUT_REVISION_INTEGRITY));
        assertThatThrownBy(() -> new UiLayoutResolutionCandidate(
                        target,
                        "rev-2",
                        hashService.sha256Exact(patch),
                        UiLayoutResolutionCandidate.LayerClass.PROFILE,
                        (short) 0,
                        tenantSelector,
                        patch,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Layout layer class is incompatible with its audience selector.");
    }

    @Test
    void effectiveDocumentAndCandidatePatchAreDefensiveCopies() throws Exception {
        UiLayoutTarget target = new UiLayoutTarget("praxis-table", "table-config:orders");
        JsonNode original = objectMapper.readTree("{\"density\":\"compact\"}");
        UiLayoutResolutionCandidate candidate = new UiLayoutResolutionCandidate(
                target,
                "rev-1",
                hashService.sha256Exact(original),
                UiLayoutResolutionCandidate.LayerClass.TENANT,
                (short) 0,
                new UiLayoutAudienceSelector("tenant-a", null, null, null, null, null),
                original,
                null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) original).put("density", "mutated-before-resolution");
        EffectiveUiAudience audience = new EffectiveUiAudience(
                "tenant-a", "lab", "user-a", null, null, null, Set.of(), "context-1");
        var response = service(audience, new StubSource(List.of(candidate), Set.of("/density"), Set.of()))
                .resolve(principal(), "context-1", target)
                .orElseThrow();

        JsonNode exposed = response.document();
        ((com.fasterxml.jackson.databind.node.ObjectNode) exposed).put("density", "mutated-after-resolution");
        assertThat(response.document().path("density").asText()).isEqualTo("compact");
        assertThat(candidate.patch().path("density").asText()).isEqualTo("compact");
    }

    @Test
    void rejectsScalarAndDescendantWritesAtTheSamePrecedence() throws Exception {
        UiLayoutTarget target = new UiLayoutTarget("praxis-table", "table-config:orders");
        EffectiveUiAudience audience = new EffectiveUiAudience(
                "tenant-a", "lab", "user-a", null, null, null, Set.of(), "context-1");
        UiLayoutAudienceSelector selector = new UiLayoutAudienceSelector(
                "tenant-a", null, null, null, null, null);
        JsonNode scalar = objectMapper.readTree("{\"header\":\"compact\"}");
        JsonNode descendant = objectMapper.readTree("{\"header\":{\"title\":\"Orders\"}}");
        var candidates = List.of(
                new UiLayoutResolutionCandidate(target, "rev-1", hashService.sha256Exact(scalar),
                        UiLayoutResolutionCandidate.LayerClass.TENANT, (short) 0, selector, scalar, null),
                new UiLayoutResolutionCandidate(target, "rev-2", hashService.sha256Exact(descendant),
                        UiLayoutResolutionCandidate.LayerClass.TENANT, (short) 0, selector, descendant, null));

        assertThatThrownBy(() -> service(audience,
                new StubSource(candidates, Set.of("/header"), Set.of()))
                .resolve(principal(), "context-1", target))
                .isInstanceOfSatisfying(UiLayoutResolutionException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(UiLayoutResolutionException.Code.LAYOUT_RESOLUTION_AMBIGUOUS);
                    assertThat(exception.path()).isEqualTo("/header");
                });
    }

    @Test
    void permitsEquivalentObjectAndLeafWritesAtTheSamePrecedence() throws Exception {
        UiLayoutTarget target = new UiLayoutTarget("praxis-table", "table-config:orders");
        EffectiveUiAudience audience = new EffectiveUiAudience(
                "tenant-a", "lab", "user-a", null, null, null, Set.of(), "context-1");
        UiLayoutAudienceSelector selector = new UiLayoutAudienceSelector(
                "tenant-a", null, null, null, null, null);
        JsonNode first = objectMapper.readTree("{\"header\":{\"title\":\"Orders\"}}");
        JsonNode second = objectMapper.readTree("{\"header\":{\"title\":\"Orders\",\"visible\":true}}");
        var candidates = List.of(
                new UiLayoutResolutionCandidate(target, "rev-1", hashService.sha256Exact(first),
                        UiLayoutResolutionCandidate.LayerClass.TENANT, (short) 0, selector, first, null),
                new UiLayoutResolutionCandidate(target, "rev-2", hashService.sha256Exact(second),
                        UiLayoutResolutionCandidate.LayerClass.TENANT, (short) 0, selector, second, null));

        assertThat(service(audience, new StubSource(candidates, Set.of("/header"), Set.of()))
                .resolve(principal(), "context-1", target)).get()
                .extracting(response -> response.document().path("header"))
                .isEqualTo(second.path("header"));
    }

    private void execute(JsonNode testCase, JsonNode defaultTarget) {
        String id = testCase.path("id").asText();
        JsonNode request = testCase.path("request");
        UiLayoutTarget target = new UiLayoutTarget(
                defaultTarget.path("componentType").asText(),
                request.hasNonNull("componentId")
                        ? request.path("componentId").asText()
                        : defaultTarget.path("componentId").asText());
        String expectedContextVersion = request.path("expectedContextVersion").asText("context-1");
        EffectiveUiAudience audience = testCase.has("audience") ? audience(testCase.path("audience")) : null;
        List<UiLayoutResolutionCandidate> candidates = new ArrayList<>();
        testCase.path("candidates").forEach(node -> candidates.add(candidate(node, new UiLayoutTarget(
                defaultTarget.path("componentType").asText(),
                node.hasNonNull("componentId")
                        ? node.path("componentId").asText()
                        : defaultTarget.path("componentId").asText()))));
        Set<String> authorable = new HashSet<>(Set.of("/density", "/header", "/columns"));
        if (testCase.has("authorablePaths")) {
            authorable.clear();
            testCase.path("authorablePaths").forEach(path -> authorable.add(path.asText()));
        }
        StubSource source = new StubSource(candidates, Set.copyOf(authorable), Set.of());
        EffectiveUiAudienceProvider provider = "unavailable".equals(testCase.path("audienceProvider").asText())
                ? (principal, version) -> { throw new IllegalStateException("private provider failure"); }
                : (principal, version) -> audience;
        UiLayoutResolutionService service = new UiLayoutResolutionService(
                objectMapper, hashService, provider, source, CLOCK);

        String expectedStatus = testCase.path("expect").path("status").asText();
        if (Set.of("LAYOUT_RESOLUTION_AMBIGUOUS", "PROTECTED_PRESENTATION_INVARIANT", "CONTEXT_STALE",
                        "AUDIENCE_SOURCE_UNAVAILABLE")
                .contains(expectedStatus)) {
            assertThatThrownBy(() -> service.resolve(principal(), expectedContextVersion, target))
                    .as(id)
                    .isInstanceOfSatisfying(UiLayoutResolutionException.class, exception -> {
                        assertThat(exception.code().name()).isEqualTo(expectedStatus);
                        if (testCase.path("expect").hasNonNull("conflictPath")) {
                            assertThat(exception.path())
                                    .isEqualTo(testCase.path("expect").path("conflictPath").asText());
                        }
                    });
        } else {
            var result = service.resolve(principal(), expectedContextVersion, target);
            if ("NOT_FOUND".equals(expectedStatus)) {
                assertThat(result).as(id).isEmpty();
            } else {
                assertThat(result).as(id).isPresent();
                assertThat(result.orElseThrow().document())
                        .isEqualTo(testCase.path("expect").path("document"));
                assertThat(result.orElseThrow().appliedLayers())
                        .extracting(layer -> layer.revisionRef())
                        .containsExactlyElementsOf(strings(testCase.path("expect").path("layers")));
                assertThat(result.orElseThrow().contextVersion()).isEqualTo(audience.contextVersion());
                assertThat(result.orElseThrow().compositeEtag()).hasSize(64);
            }
        }
        if (testCase.path("expect").has("candidateSourceCalls")) {
            assertThat(source.calls.get()).as(id).isEqualTo(testCase.path("expect").path("candidateSourceCalls").asInt());
        }
    }

    @Test
    void nativeLiteralNullBecomesRemovalWhenUsedAsResolutionPatch() throws Exception {
        JsonNode resolved = resolveAuthorshipCounterexample(
                "{\"bindings\":{\"emptyState\":{\"title\":\"Choose\"}}}",
                "{\"bindings\":{\"emptyState\":null}}",
                Set.of("/bindings/emptyState/title", "/bindings/emptyState"),
                Set.of("/bindings/emptyState"));

        assertThat(resolved.path("bindings").has("emptyState")).isFalse();
        assertThat(resolved.path("bindings").path("emptyState").isNull()).isFalse();
    }

    @Test
    void nativeFieldArrayPatchReplacesOtherFieldsInsteadOfMergingByIdentity() throws Exception {
        JsonNode resolved = resolveAuthorshipCounterexample(
                "{\"fieldMetadata\":[{\"name\":\"a\",\"placeholder\":\"A\"},{\"name\":\"b\",\"placeholder\":\"B\"}]}",
                "{\"fieldMetadata\":[{\"name\":\"a\",\"placeholder\":\"Custom\"}]}",
                Set.of("/fieldMetadata"), Set.of());

        assertThat(resolved.path("fieldMetadata")).hasSize(1);
        assertThat(resolved.path("fieldMetadata").get(0).path("name").asText()).isEqualTo("a");
    }

    @Test
    void explicitPinSurvivesChangedLowerLayerButAnEmptyPatchInheritsIt() throws Exception {
        JsonNode pinned = resolveAuthorshipCounterexample(
                "{\"placeholder\":\"B1\"}", "{\"placeholder\":\"B0\"}",
                Set.of("/placeholder"), Set.of());
        JsonNode inherited = resolveAuthorshipCounterexample(
                "{\"placeholder\":\"B1\"}", "{}", Set.of("/placeholder"), Set.of());

        assertThat(pinned.path("placeholder").asText()).isEqualTo("B0");
        assertThat(inherited.path("placeholder").asText()).isEqualTo("B1");
    }

    private JsonNode resolveAuthorshipCounterexample(
            String baseline, String authoredPatch, Set<String> authorable, Set<String> removable) throws Exception {
        UiLayoutTarget target = new UiLayoutTarget("praxis-dynamic-form", "form:counterexample");
        EffectiveUiAudience audience = new EffectiveUiAudience(
                "tenant-a", "lab", "user-a", null, null, null, Set.of(), "context-1");
        UiLayoutResolutionCandidate lower = candidate(objectMapper.readTree(
                "{\"revision\":\"base\",\"layer\":\"TENANT\",\"priority\":0,"
                        + "\"selector\":{\"tenant\":\"tenant-a\"},\"patch\":" + baseline + "}"), target);
        UiLayoutResolutionCandidate upper = candidate(objectMapper.readTree(
                "{\"revision\":\"authored\",\"layer\":\"USER\",\"priority\":0,"
                        + "\"selector\":{\"tenant\":\"tenant-a\",\"user\":\"user-a\"},\"patch\":"
                        + authoredPatch + "}"), target);
        return service(audience, new StubSource(List.of(lower, upper), authorable, removable))
                .resolve(principal(), "context-1", target).orElseThrow().document();
    }

    private UiLayoutResolutionService service(EffectiveUiAudience audience, StubSource source) {
        return new UiLayoutResolutionService(objectMapper, hashService, (principal, version) -> audience, source, CLOCK);
    }

    private EffectiveUiAudience audience(JsonNode node) {
        return new EffectiveUiAudience(
                node.path("tenant").asText(null),
                node.path("environment").asText(null),
                node.path("user").asText(null),
                node.path("organization").asText(null),
                node.path("sector").asText(null),
                node.path("profile").asText(null),
                Set.copyOf(strings(node.path("groups"))),
                node.path("currentContextVersion").asText("context-1"));
    }

    private UiLayoutResolutionCandidate candidate(JsonNode node, UiLayoutTarget target) {
        JsonNode patch = node.path("patch");
        return new UiLayoutResolutionCandidate(
                target,
                node.path("revision").asText(),
                hashService.sha256Exact(patch),
                UiLayoutResolutionCandidate.LayerClass.valueOf(node.path("layer").asText()),
                (short) node.path("priority").asInt(),
                new UiLayoutAudienceSelector(
                        node.path("selector").path("tenant").asText(null),
                        node.path("selector").path("organization").asText(null),
                        node.path("selector").path("sector").asText(null),
                        node.path("selector").path("group").asText(null),
                        node.path("selector").path("profile").asText(null),
                        node.path("selector").path("user").asText(null)),
                patch,
                null);
    }

    private JsonNode readCorpus() throws Exception {
        try (InputStream input = Files.newInputStream(CORPUS)) {
            return objectMapper.readTree(input);
        }
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private Principal principal() {
        return () -> "authenticated-user";
    }

    private static final class StubSource implements UiLayoutCandidateSource {
        private final List<UiLayoutResolutionCandidate> candidates;
        private final Set<String> authorable;
        private final Set<String> removable;
        private final AtomicInteger calls = new AtomicInteger();

        private StubSource(
                List<UiLayoutResolutionCandidate> candidates, Set<String> authorable, Set<String> removable) {
            this.candidates = candidates;
            this.authorable = authorable;
            this.removable = removable;
        }

        @Override
        public List<UiLayoutResolutionCandidate> findActive(
                String tenant, String environment, UiLayoutTarget target) {
            calls.incrementAndGet();
            return candidates;
        }

        @Override
        public Set<String> authorablePaths(UiLayoutTarget target) {
            return authorable;
        }

        @Override
        public Set<String> removablePaths(UiLayoutTarget target) {
            return removable;
        }

        @Override
        public void validatePatch(UiLayoutTarget target, JsonNode patch) {}
    }
}
