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
class AgenticAuthoringSemanticGroundingCorpusTest {

    private static final String CORPUS_FILE = "semantic-grounding-generative-ui-corpus.v0.1.json";
    private static final String SCHEMA_FILE = "semantic-grounding-generative-ui-corpus.v0.1.schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void corpusConformsToTheVersionedEvalSchema() throws Exception {
        JsonNode schema = objectMapper.readTree(AgenticAuthoringTestPaths.contract(SCHEMA_FILE).toFile());
        Set<ValidationMessage> errors = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema)
                .validate(corpus());

        assertThat(errors).isEmpty();
    }

    @Test
    void caseIdsAreUniqueAndContextReferencesResolve() throws Exception {
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
    }

    @Test
    void macroProfilesForbidApiSchemaAndComponentManifestReads() throws Exception {
        for (JsonNode testCase : corpus().path("cases")) {
            String profile = testCase.at("/expected/retrievalProfile").asText();
            if (!Set.of("platform", "global-domain").contains(profile)) {
                continue;
            }

            JsonNode budget = testCase.at("/expected/technicalReadBudget");
            assertThat(budget.path("maxResources").asInt()).as(testCase.path("id").asText()).isZero();
            assertThat(budget.path("maxOpenApiDocuments").asInt()).as(testCase.path("id").asText()).isZero();
            assertThat(budget.path("maxSchemas").asInt()).as(testCase.path("id").asText()).isZero();
            assertThat(budget.path("maxComponentManifests").asInt()).as(testCase.path("id").asText()).isZero();
        }
    }

    @Test
    void everyMaterializationRequiresPreviewAndBoundedTechnicalReads() throws Exception {
        for (JsonNode testCase : corpus().path("cases")) {
            JsonNode expected = testCase.path("expected");
            if (!"materialize".equals(expected.path("taskMode").asText())) {
                continue;
            }

            assertThat(expected.at("/safety/mutationRequiresPreview").asBoolean())
                    .as(testCase.path("id").asText())
                    .isTrue();
            if (expected.path("canApply").asBoolean()) {
                assertThat(expected.path("terminalAuthority").asText())
                        .as(testCase.path("id").asText())
                        .isEqualTo("preview");
            }

            JsonNode budget = expected.path("technicalReadBudget");
            assertThat(budget.path("maxResources").asInt()).isLessThanOrEqualTo(3);
            assertThat(budget.path("maxSchemas").asInt()).isLessThanOrEqualTo(2);
            assertThat(budget.path("maxComponentManifests").asInt()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void everyMaterialSelectionRequiresCanonicalScopedProvenance() throws Exception {
        for (JsonNode testCase : corpus().path("cases")) {
            JsonNode expected = testCase.path("expected");
            assertThat(expected.at("/provenance/materialSelectionCoverage").asDouble())
                    .as(testCase.path("id").asText())
                    .isEqualTo(1d);
            assertThat(expected.at("/provenance/requireCanonicalRelease").asBoolean()).isTrue();
            assertThat(expected.at("/provenance/requireEvidenceRefs").asBoolean()).isTrue();
            assertThat(expected.at("/safety/forbidUnapprovedInference").asBoolean()).isTrue();
            assertThat(expected.at("/safety/requireTenantEnvironmentMatch").asBoolean()).isTrue();
        }
    }

    @Test
    void corpusCoversTheCriticalMachineFirstProfiles() throws Exception {
        Set<String> profiles = new HashSet<>();
        Set<String> authorities = new HashSet<>();

        for (JsonNode testCase : corpus().path("cases")) {
            if ("must-pass".equals(testCase.path("status").asText())) {
                profiles.add(testCase.at("/expected/retrievalProfile").asText());
                authorities.add(testCase.at("/expected/terminalAuthority").asText());
            }
        }

        assertThat(profiles).contains(
                "platform",
                "global-domain",
                "context",
                "resource",
                "execution",
                "ui-materialization");
        assertThat(authorities).contains("answer", "clarify", "preview", "blocked");
    }

    private JsonNode corpus() throws Exception {
        return objectMapper.readTree(AgenticAuthoringTestPaths.proof(CORPUS_FILE).toFile());
    }
}
