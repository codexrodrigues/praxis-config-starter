package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringAssistantConsistencyCorpusTest {

    private static final String CORPUS_FILE = "assistant-consistency-corpus.v1.json";
    private static final String SCHEMA_FILE = "assistant-consistency-corpus.v1.schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void corpusConformsToVersionedInternalEvalSchema() throws Exception {
        JsonNode schema = objectMapper.readTree(AgenticAuthoringTestPaths.contract(SCHEMA_FILE).toFile());
        JsonNode corpus = corpus();

        Set<ValidationMessage> errors = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema)
                .validate(corpus);

        assertThat(errors).isEmpty();
    }

    @Test
    void corpusKeepsIdsUniqueAndEveryContextReferenceResolvable() throws Exception {
        JsonNode corpus = corpus();
        Set<String> ids = new HashSet<>();

        for (JsonNode testCase : corpus.path("cases")) {
            String id = testCase.path("id").asText();
            String contextRef = testCase.path("contextRef").asText();
            assertThat(ids.add(id)).as("duplicated corpus case id %s", id).isTrue();
            assertThat(corpus.path("contexts").has(contextRef))
                    .as("missing context %s referenced by %s", contextRef, id)
                    .isTrue();
        }
        for (JsonNode journey : corpus.path("journeys")) {
            String id = journey.path("id").asText();
            String contextRef = journey.path("contextRef").asText();
            assertThat(ids.add(id)).as("duplicated corpus unit id %s", id).isTrue();
            assertThat(corpus.path("contexts").has(contextRef))
                    .as("missing context %s referenced by %s", contextRef, id)
                    .isTrue();

            Set<String> turnIds = new HashSet<>();
            int index = 0;
            for (JsonNode turn : journey.path("turns")) {
                String turnId = turn.path("id").asText();
                assertThat(turnIds.add(turnId)).as("duplicated turn id %s in %s", turnId, id).isTrue();
                if (index == 0) {
                    assertThat(turn.path("currentPageSource").asText()).isEqualTo("context");
                    assertThat(turn.has("lineage")).isFalse();
                } else {
                    assertThat(turn.path("currentPageSource").asText()).isEqualTo("previous-preview");
                    assertThat(turn.path("lineage").path("sameThread").asBoolean()).isTrue();
                    assertThat(turn.path("lineage").path("distinctTurn").asBoolean()).isTrue();
                    assertThat(turn.path("lineage").path("activeDecisionFromPreviousTurn").asBoolean()).isTrue();
                }
                index++;
            }
        }
    }

    @Test
    void mustPassBaselineCoversDiscoveryAndBasicCreation() throws Exception {
        JsonNode cases = corpus().path("cases");

        long mustPass = count(cases, "status", "must-pass");
        long platformDiscovery = countMustPassFamily(cases, "platform-discovery");
        long openCreation = countMustPassFamily(cases, "open-creation");
        long explicitComponent = countMustPassFamily(cases, "explicit-component");

        assertThat(mustPass).isGreaterThanOrEqualTo(6);
        assertThat(platformDiscovery).isGreaterThanOrEqualTo(3);
        assertThat(openCreation).isGreaterThanOrEqualTo(1);
        assertThat(explicitComponent).isGreaterThanOrEqualTo(2);
    }

    @Test
    void extendedProfileCoversLanguageHumanErrorAndExistingPageRefinement() throws Exception {
        JsonNode cases = corpus().path("cases");

        assertThat(countExtendedFamily(cases, "platform-discovery")).isGreaterThanOrEqualTo(1);
        assertThat(countExtendedFamily(cases, "human-error")).isGreaterThanOrEqualTo(1);
        assertThat(countExtendedFamily(cases, "refinement")
                        + countExtendedFamily(corpus().path("journeys"), "refinement"))
                .isGreaterThanOrEqualTo(1);
        assertThat(count(cases, "locale", "en-US")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void guidanceIsNonMutatingAndEveryMutationRequiresPreview() throws Exception {
        for (JsonNode testCase : corpus().path("cases")) {
            assertSafeExpectation(testCase.path("id").asText(), testCase.path("family").asText(), testCase.path("expected"));
        }
        for (JsonNode journey : corpus().path("journeys")) {
            for (JsonNode turn : journey.path("turns")) {
                assertSafeExpectation(
                        journey.path("id").asText() + "#" + turn.path("id").asText(),
                        journey.path("family").asText(),
                        turn.path("expected"));
            }
        }
    }

    @Test
    void employeeFormRequiresTheCanonicalWriteEndpoint() throws Exception {
        JsonNode employeeForm = null;
        for (JsonNode testCase : corpus().path("cases")) {
            if ("employee-form-create-pt".equals(testCase.path("id").asText())) {
                employeeForm = testCase;
                break;
            }
        }

        assertThat(employeeForm).isNotNull();
        JsonNode submitUrls = employeeForm.path("expected").path("intent").path("submitUrls");
        assertThat(submitUrls.isArray()).isTrue();
        assertThat(submitUrls.size()).isEqualTo(1);
        assertThat(submitUrls.get(0).asText()).isEqualTo("/api/human-resources/funcionarios");

        JsonNode persistence = employeeForm.path("expected").path("persistence");
        assertThat(persistence.path("apply").asText()).isEqualTo("required");
        assertThat(persistence.path("readback").asText()).isEqualTo("exact-page");
        assertThat(persistence.path("conditionalReplay").asText()).isEqualTo("same-state");
        assertThat(persistence.path("staleRetry").asText()).isEqualTo("precondition-failed");
        assertThat(persistence.path("cleanup").asText()).isEqualTo("required");
    }

    private JsonNode corpus() throws Exception {
        return objectMapper.readTree(AgenticAuthoringTestPaths.proof(CORPUS_FILE).toFile());
    }

    private void assertSafeExpectation(String id, String family, JsonNode expected) {
        JsonNode terminal = expected.path("terminal");
        if ("platform-discovery".equals(family)) {
            assertThat(terminal.path("canApply").asBoolean()).as(id).isFalse();
            assertThat(terminal.path("preview").asText()).as(id).isEqualTo("forbidden");
            assertThat(terminal.path("minimumQuickReplies").asInt()).as(id).isPositive();
        }
        if (terminal.path("canApply").asBoolean()) {
            assertThat(terminal.path("preview").asText()).as(id).isEqualTo("required");
            assertThat(expected.path("safety").path("mutationRequiresPreview").asBoolean()).as(id).isTrue();
        }
    }

    private long count(JsonNode cases, String field, String value) {
        long total = 0;
        for (JsonNode testCase : cases) {
            if (value.equals(testCase.path(field).asText())) {
                total++;
            }
        }
        return total;
    }

    private long countMustPassFamily(JsonNode cases, String family) {
        long total = 0;
        for (JsonNode testCase : cases) {
            if ("must-pass".equals(testCase.path("status").asText())
                    && family.equals(testCase.path("family").asText())) {
                total++;
            }
        }
        return total;
    }

    private long countExtendedFamily(JsonNode cases, String family) {
        long total = 0;
        for (JsonNode testCase : cases) {
            if ("extended".equals(testCase.path("status").asText())
                    && family.equals(testCase.path("family").asText())) {
                total++;
            }
        }
        return total;
    }
}
