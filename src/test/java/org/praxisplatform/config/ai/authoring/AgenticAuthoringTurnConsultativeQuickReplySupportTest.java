package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgenticAuthoringTurnConsultativeQuickReplySupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringTurnConsultativeQuickReplySupport support =
            new AgenticAuthoringTurnConsultativeQuickReplySupport(objectMapper);

    @Test
    void serverIssuedContinuationsAreBoundToTheDecisionPersistedByTheIssuingTurn() {
        AgenticAuthoringSemanticDecision issuingDecision = decision(
                "explore", "api_catalog", "answer_api_catalog_question", null, "turn-1", "governed-domain-discovery");
        // Authored before the turn reconciled its own decision: parented to the pre-turn state (none).
        AgenticAuthoringSemanticDecision continuation = decision(
                "create", "table", "create_artifact", null, "turn-1:governed-domain:create-table",
                "server-issued-quick-reply");
        AgenticAuthoringQuickReply issued = quickReply("governed-domain:create-table", continuation);
        AgenticAuthoringQuickReply plain = new AgenticAuthoringQuickReply("revise", "revise", "Revisar", "Revisar");

        List<AgenticAuthoringQuickReply> bound = support.withIssuingDecision(List.of(issued, plain), issuingDecision);

        AgenticAuthoringSemanticDecision boundDecision = objectMapper.convertValue(
                bound.get(0).semanticDecision(), AgenticAuthoringSemanticDecision.class);
        assertThat(boundDecision.previousDecisionId()).isEqualTo(issuingDecision.decisionId());
        assertThat(boundDecision.refinementOf()).isEqualTo(issuingDecision.decisionId());
        assertThat(boundDecision.decisionId()).isEqualTo(continuation.decisionId());
        assertThat(boundDecision.constraints().path("quickReplyId").asText())
                .isEqualTo("governed-domain:create-table");
        assertThat(bound.get(0).prompt()).isEqualTo(issued.prompt());
        assertThat(bound.get(1)).isSameAs(plain);
    }

    @Test
    void nonServerIssuedDecisionsAndMissingIssuingDecisionAreLeftUntouched() {
        AgenticAuthoringSemanticDecision issuingDecision = decision(
                "explore", "api_catalog", "answer_api_catalog_question", null, "turn-1", "governed-domain-discovery");
        AgenticAuthoringSemanticDecision clientDecision = decision(
                "create", "table", "create_artifact", null, "turn-1:other", "client");
        AgenticAuthoringQuickReply reply = quickReply("other", clientDecision);

        assertThat(support.withIssuingDecision(List.of(reply), issuingDecision).get(0)).isSameAs(reply);
        assertThat(support.withIssuingDecision(List.of(reply), null)).containsExactly(reply);
    }

    private AgenticAuthoringSemanticDecision decision(
            String operationKind,
            String artifactKind,
            String changeKind,
            AgenticAuthoringSemanticDecision active,
            String turnId,
            String source) {
        ObjectNode constraints = objectMapper.createObjectNode();
        constraints.put("source", source);
        if ("server-issued-quick-reply".equals(source)) {
            constraints.put("quickReplyId", turnId.substring(turnId.indexOf(':') + 1));
        }
        return AgenticAuthoringSemanticDecision.from(
                        operationKind,
                        artifactKind,
                        changeKind,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        active,
                        "session-1",
                        turnId,
                        "objetivo",
                        "objetivo",
                        "rationale")
                .withConstraints(constraints);
    }

    private AgenticAuthoringQuickReply quickReply(String id, AgenticAuthoringSemanticDecision decision) {
        JsonNode node = objectMapper.valueToTree(decision);
        return new AgenticAuthoringQuickReply(
                id, "suggestion", "Criar uma tabela", "Proponha uma tabela.", null, null, null,
                objectMapper.createObjectNode(), node, null);
    }
}
