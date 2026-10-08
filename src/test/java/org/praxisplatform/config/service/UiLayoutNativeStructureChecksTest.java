package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.UiLayoutLifecycleException.Code;

@Tag("unit")
class UiLayoutNativeStructureChecksTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UiLayoutTarget target = new UiLayoutTarget("praxis-table", "test");
    private final UiLayoutLifecycleInvocation invocation = new UiLayoutLifecycleInvocation("actor", "tenant", "org",
            "test", "ctx", new UiLayoutCompositionRegistration(target, List.of(target)));
    private final UiLayoutAuthoringDocumentDescriptor descriptor = new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "host.table", "1");
    private final UiLayoutPatchDocumentDescriptor patchDescriptor = new UiLayoutPatchDocumentDescriptor("host.patch", "praxis.ui-layout/v1");
    private UiLayoutValidationContext context() {
        return new UiLayoutValidationContext(UiLayoutValidationAttempt.start(invocation,
                UiLayoutLifecycleOperation.EDIT_DRAFT, Duration.ofSeconds(5)), UiLayoutValidationPurpose.AUTHORING);
    }
    private ObjectNode document() throws Exception {
        return (ObjectNode) json.readTree("{\"kind\":\"praxis.table.editor\",\"version\":1,\"config\":{\"columns\":[],\"columnProjection\":{\"source\":\"schema\"}}}");
    }
    private void reject(Runnable work, Code code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(UiLayoutLifecycleException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
    @Test void sharedChecksAcceptBothFiniteCorporaWithoutChangingAnyInput() throws Exception {
        for (String component : List.of("table", "form")) {
            var corpus = json.readTree(Files.readString(Path.of("docs/ai/contracts/" + component + "-native-revision-conformance.v1.json")));
            for (var entry : corpus.path("cases")) {
                var before = entry.deepCopy();
                var currentTarget = new UiLayoutTarget(component.equals("table") ? "praxis-table" : "praxis-dynamic-form", "test");
                String kind = component.equals("table") ? "praxis.table.editor" : "praxis.dynamic-form.editor";
                var currentDescriptor = new UiLayoutAuthoringDocumentDescriptor(kind, "host." + component, "1");
                var baseline = entry.path("baseline"); var candidate = entry.path("command").path("authoringDocument");
                UiLayoutNativeStructureChecks.requireBaseline(invocation, currentTarget, currentDescriptor, baseline, patchDescriptor, context());
                UiLayoutNativeStructureChecks.requireRevision(invocation, currentTarget, currentDescriptor, baseline,
                        currentDescriptor, candidate, patchDescriptor, entry.path("expectedPatch"), context());
                assertThat(entry).isEqualTo(before);
            }
        }
    }
    @Test void structureDoesNotAdmitUnmappedFormats() throws Exception {
        var doc = document();
        var unknown = new UiLayoutAuthoringDocumentDescriptor("unknown", "host", "1");
        reject(() -> UiLayoutNativeStructureChecks.requireBaseline(invocation, target, unknown, doc, patchDescriptor, context()), Code.SOURCE_UNAVAILABLE);
    }
    @Test void materializedTableIsNotConvertedIntoCompactAuthorship() throws Exception {
        var doc = document();
        ((ObjectNode) doc.get("config")).putArray("columns").addObject().put("field", "id");
        var before = doc.deepCopy();
        reject(() -> UiLayoutNativeStructureChecks.requireBaseline(invocation, target, descriptor, doc, patchDescriptor, context()), Code.VALIDATION_FAILED);
        assertThat(doc).isEqualTo(before);
    }
    @Test void baselineAndPatchBudgetsAreIndependent() throws Exception {
        var doc = document(); var oversized = doc.deepCopy();
        ((ObjectNode) oversized.get("config")).put("title", "x".repeat(262144));
        reject(() -> UiLayoutNativeStructureChecks.requireBaseline(invocation, target, descriptor, oversized, patchDescriptor, context()), Code.INVALID_STATE);
        var patch = json.createObjectNode().put("title", "x".repeat(262144));
        reject(() -> UiLayoutNativeStructureChecks.requireRevision(invocation, target, descriptor, doc, descriptor, doc, patchDescriptor, patch, context()), Code.INVALID_REQUEST);
    }
    @Test void aNonReproducingHistoricalPatchIsRejectedInsteadOfRecompiled() throws Exception {
        var doc = document();
        reject(() -> UiLayoutNativeStructureChecks.requireRevision(invocation, target, descriptor, doc, descriptor, doc,
                patchDescriptor, json.createObjectNode(), context()), Code.VALIDATION_FAILED);
    }
    @Test void descriptorChangesAreRejected() throws Exception {
        var doc = document(); var changed = new UiLayoutAuthoringDocumentDescriptor("praxis.table.editor", "other", "1");
        reject(() -> UiLayoutNativeStructureChecks.requireRevision(invocation, target, descriptor, doc, changed, doc,
                patchDescriptor, doc.get("config"), context()), Code.VALIDATION_FAILED);
    }
    @Test void closedOrDifferentInvocationCannotUseStructuralChecks() throws Exception {
        var doc = document(); var closed = context(); closed.attempt().close();
        reject(() -> UiLayoutNativeStructureChecks.requireBaseline(invocation, target, descriptor, doc, patchDescriptor, closed), Code.SOURCE_UNAVAILABLE);
        var other = new UiLayoutLifecycleInvocation("other", "tenant", "org", "test", "ctx", invocation.composition());
        reject(() -> UiLayoutNativeStructureChecks.requireBaseline(other, target, descriptor, doc, patchDescriptor, context()), Code.CONTEXT_STALE);
    }
    @Test void historicalReadPurposeCannotValidateCurrentNativeContent() throws Exception {
        var doc = document(); var history = context().forPurpose(UiLayoutValidationPurpose.HISTORICAL_EVIDENCE_READ);
        reject(() -> UiLayoutNativeStructureChecks.requireBaseline(invocation, target, descriptor, doc, patchDescriptor, history), Code.DENIED);
    }
}
