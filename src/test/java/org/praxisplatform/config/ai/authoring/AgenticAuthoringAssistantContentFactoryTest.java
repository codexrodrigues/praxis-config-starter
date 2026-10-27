package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringAssistantContentFactory.ParagraphRecordReferenceInline;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringAssistantContentFactory.ParagraphTextInline;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringAssistantContentFactory.QuickViewContentRef;

class AgenticAuthoringAssistantContentFactoryTest {

    @Test
    void buildsParagraphWithValidatedRecordReferenceAndDegradesInvalidReferenceToPlainText() {
        ObjectNode block = AgenticAuthoringAssistantContentFactory.buildParagraphBlock(
                null,
                List.of(
                        new ParagraphTextInline("O colaborador "),
                        new ParagraphRecordReferenceInline(
                                "Ana Silva",
                                "human-resources.funcionarios",
                                "detail",
                                "funcionarioId",
                                new QuickViewContentRef("hr", "profile-summary", "1.0.0"),
                                42L),
                        new ParagraphTextInline(" reportou o caso "),
                        new ParagraphRecordReferenceInline(
                                "Referência sem identidade",
                                "human-resources.funcionarios",
                                "detail",
                                "funcionarioId",
                                null,
                                null)));

        assertThat(block).isNotNull();
        assertThat(block.path("type").asText()).isEqualTo("paragraph");
        assertThat(block.path("text").asText())
                .isEqualTo("O colaborador Ana Silva reportou o caso Referência sem identidade");

        JsonNode inlines = block.path("inlines");
        assertThat(inlines.isArray()).isTrue();
        assertThat(inlines).hasSize(4);

        JsonNode validRef = inlines.get(1);
        assertThat(validRef.path("type").asText()).isEqualTo("record-reference");
        assertThat(validRef.path("label").asText()).isEqualTo("Ana Silva");
        assertThat(validRef.path("quickView").path("recordOpen").path("sourceIdentityField").asText())
                .isEqualTo("funcionarioId");
        assertThat(validRef.path("quickView").path("recordOpen").path("target").path("resourceKey").asText())
                .isEqualTo("human-resources.funcionarios");
        assertThat(validRef.path("quickView").path("recordOpen").path("target").path("surfaceId").asText())
                .isEqualTo("detail");
        assertThat(validRef.path("quickView").path("contentRef").path("kind").asText())
                .isEqualTo("rich-block");
        assertThat(validRef.path("quickView").path("contentRef").path("namespace").asText())
                .isEqualTo("hr");
        assertThat(validRef.path("quickView").path("contentRef").path("presetId").asText())
                .isEqualTo("profile-summary");
        assertThat(validRef.path("quickView").path("contentRef").path("version").asText())
                .isEqualTo("1.0.0");
        assertThat(validRef.path("identityValues").path("funcionarioId").asLong()).isEqualTo(42L);

        JsonNode degradedRef = inlines.get(3);
        assertThat(degradedRef.path("type").asText()).isEqualTo("text");
        assertThat(degradedRef.path("text").asText()).isEqualTo("Referência sem identidade");
    }
}
