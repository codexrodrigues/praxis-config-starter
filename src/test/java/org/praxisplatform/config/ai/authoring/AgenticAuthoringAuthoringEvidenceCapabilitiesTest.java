package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.ContextRetrievalService;

class AgenticAuthoringAuthoringEvidenceCapabilitiesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesVectorOrderAndRejectsMalformedOrForeignCards() {
        AgenticAuthoringComponentCapabilitiesResult catalog = new AgenticAuthoringComponentCapabilitiesResult(
                "v1",
                List.of(new AgenticAuthoringComponentCapabilitiesResult.ComponentCapabilityCatalog(
                        "praxis-table", "v1", List.of(capability("column.add"), capability("toolbar.action.add")))),
                null);

        List<ContextRetrievalService.ComponentCorpusEvidence> evidence = List.of(
                evidence("{\"operationId\":\"toolbar.action.add\",\"componentId\":\"praxis-table\"}"),
                evidence("not-json"),
                foreignEvidence(),
                evidence("{\"operationId\":\"column.add\",\"componentId\":\"praxis-table\"}"));

        assertThat(AgenticAuthoringAuthoringEvidenceCapabilities.select(
                objectMapper, "praxis-table", evidence, catalog, 12))
                .extracting(AgenticAuthoringComponentCapabilitiesResult.ComponentCapability::id)
                .containsExactly("toolbar.action.add", "column.add");
    }

    private AgenticAuthoringComponentCapabilitiesResult.ComponentCapability capability(String operationId) {
        return new AgenticAuthoringComponentCapabilitiesResult.ComponentCapability(
                operationId, operationId, List.of(), List.of(), List.of());
    }

    private ContextRetrievalService.ComponentCorpusEvidence evidence(String content) {
        return new ContextRetrievalService.ComponentCorpusEvidence(
                "doc", "praxis-table", "component_definition", "authoring_manifest", "ref", "release", "tenant", "local",
                "allow", "hash", "v1", content, 0.9d);
    }

    private ContextRetrievalService.ComponentCorpusEvidence foreignEvidence() {
        return new ContextRetrievalService.ComponentCorpusEvidence(
                "foreign", "praxis-chart", "component_definition", "authoring_manifest", "ref", "release", "tenant", "local",
                "allow", "hash", "v1", "{\"operationId\":\"toolbar.action.add\",\"componentId\":\"praxis-chart\"}", 0.8d);
    }
}
