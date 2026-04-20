package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AgenticAuthoringConversationTurnOrchestratorTest {

    private final AgenticAuthoringConversationTurnOrchestrator orchestrator =
            new AgenticAuthoringConversationTurnOrchestrator();

    @Test
    void appendsShortClarificationAnswerToPendingSourcePrompt() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "folha de pagamento",
                List.of(),
                pendingClarification("Crie um dashboard", "Qual recurso de negocio deve alimentar esta tela?"));

        assertThat(turn.effectivePrompt()).isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(turn.answeredPendingClarification()).isTrue();
    }

    @Test
    void preservesAccumulatedClarificationContextAcrossShortAnswers() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "por departamento",
                List.of(),
                pendingClarification(
                        "Crie um dashboard\n\nConfirmed: folha de pagamento",
                        "Qual recorte do dashboard?"));

        assertThat(turn.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: por departamento");
        assertThat(turn.answeredPendingClarification()).isTrue();
    }

    @Test
    void infersPendingClarificationFromConversationHistoryWhenExplicitStateIsMissing() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "folha de pagamento",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual tema do dashboard?", null)),
                null);

        assertThat(turn.effectivePrompt()).isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(turn.sourcePrompt()).isEqualTo("Crie um dashboard");
        assertThat(turn.assistantMessage()).isEqualTo("Qual tema do dashboard?");
    }

    @Test
    void infersPendingClarificationFromAssistantNextStepWithoutQuestionMark() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "pode fazer agora",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Encontrei a melhor fonte de dados para o dashboard. Escolha a API principal.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m3",
                                "user",
                                "Usar Dashboard analitico (/api/human-resources/vw-analytics-folha-pagamento) como fonte de dados.",
                                null),
                        new AgenticAuthoringConversationMessage(
                                "m4",
                                "assistant",
                                "Perfeito. Proximo passo: posso montar a previa usando essa fonte de dados.",
                                null)),
                null);

        assertThat(turn.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: Usar Dashboard analitico (/api/human-resources/vw-analytics-folha-pagamento) como fonte de dados.\n\nConfirmed: pode fazer agora");
        assertThat(turn.sourcePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: Usar Dashboard analitico (/api/human-resources/vw-analytics-folha-pagamento) como fonte de dados.");
        assertThat(turn.answeredPendingClarification()).isTrue();
    }

    @Test
    void rebuildsAccumulatedClarificationContextFromConversationHistory() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "por departamento",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual tema do dashboard?", null),
                        new AgenticAuthoringConversationMessage("m3", "user", "folha de pagamento", null),
                        new AgenticAuthoringConversationMessage("m4", "assistant", "Qual recorte do dashboard?", null)),
                null);

        assertThat(turn.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: por departamento");
        assertThat(turn.sourcePrompt()).isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento");
        assertThat(turn.assistantMessage()).isEqualTo("Qual recorte do dashboard?");
    }

    @Test
    void doesNotAppendAnswerAlreadyPresentInSourcePrompt() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "folha de pagamento",
                List.of(),
                pendingClarification("Crie um dashboard de folha de pagamento", "Qual tema do dashboard?"));

        assertThat(turn.effectivePrompt()).isEqualTo("folha de pagamento");
        assertThat(turn.answeredPendingClarification()).isFalse();
    }

    @Test
    void keepsContextWhenAlternativeAnswerRepeatsAcrossBreakdownClarifications() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "outro",
                List.of(),
                pendingClarification(
                        "Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: outro",
                        "Qual recorte do dashboard de folha de pagamento voce quer usar: por departamento, competencia, status ou outro?"));

        assertThat(turn.effectivePrompt())
                .isEqualTo("Crie um dashboard\n\nConfirmed: folha de pagamento\n\nConfirmed: outro\n\nConfirmed: outro");
        assertThat(turn.answeredPendingClarification()).isTrue();
    }

    @Test
    void treatsConsultativeQuestionAsNewPromptWhenHistoryHasSoftClarification() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "Como visualizar a folha?",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Me ajude a escolher um dashboard para folha de pagamento", null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Voce pode seguir com essa visao e depois ajustar os graficos e metricas.",
                                null)),
                null);

        assertThat(turn.effectivePrompt()).isEqualTo("Como visualizar a folha?");
        assertThat(turn.answeredPendingClarification()).isFalse();
    }

    @Test
    void treatsConsultativeInstructionAsNewPromptWhenHistoryHasSoftClarification() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "Me ajude a escolher um dashboard para folha de pagamento",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Como visualizar informacoes da folha de pagamento por departamento?", null),
                        new AgenticAuthoringConversationMessage(
                                "m2",
                                "assistant",
                                "Posso seguir com essa direcao e ajudar a configurar os graficos e metricas mais adequados.",
                                null)),
                null);

        assertThat(turn.effectivePrompt()).isEqualTo("Me ajude a escolher um dashboard para folha de pagamento");
        assertThat(turn.answeredPendingClarification()).isFalse();
    }

    @Test
    void treatsStandaloneInstructionAsNewPrompt() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "Crie uma tabela operacional de folhas de pagamento",
                List.of(
                        new AgenticAuthoringConversationMessage("m1", "user", "Crie um dashboard", null),
                        new AgenticAuthoringConversationMessage("m2", "assistant", "Qual tema do dashboard?", null)),
                null);

        assertThat(turn.effectivePrompt()).isEqualTo("Crie uma tabela operacional de folhas de pagamento");
        assertThat(turn.answeredPendingClarification()).isFalse();
    }

    @Test
    void preservesContextForLongClarificationThatSelectsSuggestedResource() {
        String answer = "Quero seguir com a primeira opcao, usando /api/human-resources/vw-ranking-reputacao "
                + "como recurso principal. Mantenha o objetivo original de criar um dashboard e gere a pre-visualizacao.";

        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                answer,
                List.of(),
                pendingClarification("Crie um dashboard", "Qual recurso de negocio deve alimentar esta tela?"));

        assertThat(turn.effectivePrompt()).isEqualTo("Crie um dashboard\n\nConfirmed: " + answer);
        assertThat(turn.answeredPendingClarification()).isTrue();
    }

    @Test
    void returnsTrimmedPromptWhenThereIsNoPendingClarification() {
        AgenticAuthoringConversationTurn turn = orchestrator.resolve(
                "  Crie um dashboard  ",
                List.of(),
                null);

        assertThat(turn.effectivePrompt()).isEqualTo("Crie um dashboard");
        assertThat(turn.answeredPendingClarification()).isFalse();
    }

    private AgenticAuthoringPendingClarification pendingClarification(String sourcePrompt, String question) {
        return new AgenticAuthoringPendingClarification(
                sourcePrompt,
                List.of(question),
                question,
                "turn-1",
                null);
    }
}
