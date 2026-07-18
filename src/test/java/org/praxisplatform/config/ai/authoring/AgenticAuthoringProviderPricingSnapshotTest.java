package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringProviderPricingSnapshotTest {

    private static final String SNAPSHOT_FILE = "provider-pricing-snapshot.v1.json";
    private static final String SCHEMA_FILE = "provider-pricing-snapshot.v1.schema.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void snapshotConformsToVersionedInternalEvalSchema() throws Exception {
        JsonNode schema = objectMapper.readTree(AgenticAuthoringTestPaths.contract(SCHEMA_FILE).toFile());
        JsonNode snapshot = snapshot();

        Set<ValidationMessage> errors = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema)
                .validate(snapshot);

        assertThat(errors).isEmpty();
    }

    @Test
    void openAiModelsHaveUniqueNonOverlappingPricingIdentities() throws Exception {
        Set<String> identities = new HashSet<>();
        List<JsonNode> entries = new ArrayList<>();
        snapshot().path("entries").forEach(entries::add);
        for (JsonNode entry : entries) {
            String provider = entry.path("provider").asText();
            String model = entry.path("model").asText();
            assertThat(identities.add(provider + ":" + model)).as(model).isTrue();
            assertThat(decimal(entry, "cachedInputUsdPerMillion"))
                    .as(model)
                    .isLessThanOrEqualTo(decimal(entry, "inputUsdPerMillion"));
            assertThat(decimal(entry, "outputUsdPerMillion")).as(model).isPositive();
            for (JsonNode prefix : entry.path("modelPrefixes")) {
                assertThat(prefix.asText()).as(model).startsWith(model + "-");
            }
            List<String> coveredCandidates = new ArrayList<>();
            coveredCandidates.add(model);
            entry.path("modelPrefixes").forEach(prefix -> coveredCandidates.add(prefix.asText() + "sample"));
            for (String candidate : coveredCandidates) {
                long matchingEntries = entries.stream()
                        .filter(other -> provider.equals(other.path("provider").asText()))
                        .filter(other -> matches(other, candidate))
                        .count();
                assertThat(matchingEntries).as(provider + ":" + candidate).isEqualTo(1);
            }
        }
    }

    @Test
    void snapshotCoversTheDefaultOpenAiAssistantModel() throws Exception {
        Set<String> models = new HashSet<>();
        for (JsonNode entry : snapshot().path("entries")) {
            models.add(entry.path("model").asText());
        }
        assertThat(models).contains("gpt-5.4-mini");
    }

    @Test
    void snapshotCoversTheOpenAi56EvaluationFamily() throws Exception {
        Set<String> models = new HashSet<>();
        for (JsonNode entry : snapshot().path("entries")) {
            models.add(entry.path("model").asText());
        }
        assertThat(models).contains("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna");
    }

    private JsonNode snapshot() throws Exception {
        return objectMapper.readTree(AgenticAuthoringTestPaths.proof(SNAPSHOT_FILE).toFile());
    }

    private BigDecimal decimal(JsonNode entry, String field) {
        return entry.path(field).decimalValue();
    }

    private boolean matches(JsonNode entry, String model) {
        if (entry.path("model").asText().equals(model)) {
            return true;
        }
        for (JsonNode prefix : entry.path("modelPrefixes")) {
            if (model.startsWith(prefix.asText())) {
                return true;
            }
        }
        return false;
    }
}
