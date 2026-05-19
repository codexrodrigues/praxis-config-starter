package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AiOrchestratorServiceTableKeywordRoutingHygieneTest {

    @Test
    void tableGeneratePatchFlowMustNotRouteThroughLegacyKeywordFallbacks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/praxisplatform/config/service/AiOrchestratorService.java"));
        String generatePatchBody = source.substring(
                source.indexOf("public AiOrchestratorResponse generatePatch"),
                source.indexOf("private AiActionPlan extractTableActionPlan"));

        assertThat(generatePatchBody)
                .doesNotContain("tryResolveTableDeterministicDirectFallback(")
                .doesNotContain("deriveFallbackTableManifestActionPlan(")
                .doesNotContain("deriveFallbackTableActions(")
                .doesNotContain("keyword-fallback-table-actions-used");
        assertThat(generatePatchBody).contains("extractTableActionPlan(");
    }
}
