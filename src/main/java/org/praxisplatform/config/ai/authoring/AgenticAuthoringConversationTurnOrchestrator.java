package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

final class AgenticAuthoringConversationTurnOrchestrator {

    AgenticAuthoringConversationTurn resolve(
            String userPrompt,
            List<AgenticAuthoringConversationMessage> conversationMessages,
            AgenticAuthoringPendingClarification pendingClarification) {
        String prompt = trim(userPrompt);
        AgenticAuthoringPendingClarification pending = usablePending(pendingClarification);
        if (pending == null) {
            pending = inferPendingClarification(conversationMessages, conversationMessages == null ? 0 : conversationMessages.size());
        }
        String effectivePrompt = effectivePrompt(prompt, pending);
        boolean answeredPendingClarification = pending != null
                && (!effectivePrompt.equals(prompt) || carriesPendingConfirmation(prompt, pending));
        return new AgenticAuthoringConversationTurn(
                prompt,
                effectivePrompt,
                pending == null ? "" : trim(pending.sourcePrompt()),
                pending == null ? "" : trim(pending.assistantMessage()),
                pending == null || pending.questions() == null ? List.of() : List.copyOf(pending.questions()),
                answeredPendingClarification);
    }

    private String effectivePrompt(String prompt, AgenticAuthoringPendingClarification pendingClarification) {
        if (prompt.isBlank() || pendingClarification == null) {
            return prompt;
        }
        String sourcePrompt = trim(pendingClarification.sourcePrompt());
        if (sourcePrompt.isBlank()
                || (normalize(sourcePrompt).contains(normalize(prompt))
                && !isAlternativeClarificationAnswer(prompt, pendingClarification))
                || isStandaloneQuestion(prompt)
                || isStandaloneConsultativePrompt(prompt)
                || isStandaloneInstruction(prompt, pendingClarification)) {
            return prompt;
        }
        return AgenticAuthoringConversationPrompt.appendConfirmation(sourcePrompt, prompt);
    }

    private boolean isAlternativeClarificationAnswer(
            String prompt,
            AgenticAuthoringPendingClarification pendingClarification) {
        String normalized = normalize(prompt);
        if (!normalized.matches("\\b(outro|outra|outros|outras)\\b")) {
            return false;
        }
        String assistantMessage = normalize(pendingClarification == null ? null : pendingClarification.assistantMessage());
        boolean assistantAskedBreakdown = assistantMessage.contains("recorte")
                || assistantMessage.contains("por departamento")
                || assistantMessage.contains("competencia");
        if (assistantAskedBreakdown) {
            return true;
        }
        List<String> questions = pendingClarification == null || pendingClarification.questions() == null
                ? List.of()
                : pendingClarification.questions();
        return questions.stream()
                .map(AgenticAuthoringConversationTurnOrchestrator::normalize)
                .anyMatch(question -> question.contains("recorte")
                        || question.contains("por departamento")
                        || question.contains("competencia"));
    }

    private AgenticAuthoringPendingClarification inferPendingClarification(
            List<AgenticAuthoringConversationMessage> messages,
            int exclusiveEnd) {
        if (messages == null || messages.isEmpty() || exclusiveEnd <= 0) {
            return null;
        }
        int end = Math.min(exclusiveEnd, messages.size());
        for (int index = end - 1; index >= 0; index--) {
            AgenticAuthoringConversationMessage message = messages.get(index);
            if (!isAssistantClarification(message)) {
                continue;
            }
            int sourceUserIndex = previousUserIndex(messages, index);
            if (sourceUserIndex < 0) {
                return null;
            }
            String sourcePrompt = effectivePromptForMessage(messages, sourceUserIndex);
            if (sourcePrompt.isBlank()) {
                return null;
            }
            String assistantMessage = trim(message.text());
            return new AgenticAuthoringPendingClarification(
                    sourcePrompt,
                    List.of(assistantMessage),
                    assistantMessage,
                    message.id(),
                    null);
        }
        return null;
    }

    private String effectivePromptForMessage(List<AgenticAuthoringConversationMessage> messages, int userIndex) {
        if (userIndex < 0 || userIndex >= messages.size()) {
            return "";
        }
        AgenticAuthoringConversationMessage userMessage = messages.get(userIndex);
        if (!isUser(userMessage)) {
            return "";
        }
        AgenticAuthoringPendingClarification pending = inferPendingClarification(messages, userIndex);
        return effectivePrompt(trim(userMessage.text()), pending);
    }

    private AgenticAuthoringPendingClarification usablePending(AgenticAuthoringPendingClarification pending) {
        if (pending == null || trim(pending.sourcePrompt()).isBlank()) {
            return null;
        }
        List<String> questions = pending.questions() == null ? List.of() : pending.questions().stream()
                .map(AgenticAuthoringConversationTurnOrchestrator::trim)
                .filter(question -> !question.isBlank())
                .toList();
        return new AgenticAuthoringPendingClarification(
                trim(pending.sourcePrompt()),
                questions,
                trim(pending.assistantMessage()),
                pending.clientTurnId(),
                pending.diagnostics());
    }

    private int previousUserIndex(List<AgenticAuthoringConversationMessage> messages, int beforeIndex) {
        for (int index = beforeIndex - 1; index >= 0; index--) {
            if (isUser(messages.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean isAssistantClarification(AgenticAuthoringConversationMessage message) {
        String text = trim(message == null ? null : message.text());
        if (!isAssistant(message) || text.isBlank()) {
            return false;
        }
        if (text.contains("?")) {
            return true;
        }
        String normalized = normalize(text);
        return normalized.matches(".*\\b(escolha|selecione|confirme|defina|informe|posso seguir|posso criar|posso montar|proximo passo|falta definir|para avancar|para seguir|se quiser)\\b.*");
    }

    private boolean isAssistant(AgenticAuthoringConversationMessage message) {
        return message != null && "assistant".equals(message.role());
    }

    private boolean isUser(AgenticAuthoringConversationMessage message) {
        return message != null && "user".equals(message.role());
    }

    private boolean isStandaloneQuestion(String prompt) {
        String normalized = normalize(prompt);
        return normalized.endsWith("?")
                || normalized.matches("^(como|qual|quais|que|o que|onde|quando|porque|por que)\\b.*");
    }

    private boolean isStandaloneConsultativePrompt(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.matches(".*\\b(escolho|confirmo|confirmar|usar|use|usando|primeira|segunda|terceira)\\b.*")) {
            return false;
        }
        return normalized.matches(".*\\b(me ajude|ajude|escolher|melhor forma|antes de criar|quais outras|compare|comparar|orientar|oriente|me oriente|faz mais sentido|devo usar)\\b.*");
    }

    private boolean isStandaloneInstruction(String prompt, AgenticAuthoringPendingClarification pendingClarification) {
        if (isContinuationClarificationAnswer(prompt, pendingClarification)) {
            return false;
        }
        String normalized = normalize(prompt);
        return normalized.matches(".*\\b(crie|criar|adicione|adicionar|altere|alterar|remova|remover|gere|gerar|monte|montar|create|add|change|remove|generate|build)\\b.*");
    }

    private boolean isContinuationClarificationAnswer(
            String prompt,
            AgenticAuthoringPendingClarification pendingClarification) {
        if (pendingClarification == null) {
            return false;
        }
        String normalized = normalize(prompt);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("/api/")
                || normalized.matches(".*\\b(confirmed|confirmado|confirmada|confirmo|confirmar|sim|escolho|escolher|seguir|fazer|use|usar|usando|mantenha|preserve|preservar|opcao|opcoes|primeira|segunda|terceira|com base|exatamente|gerar previa|gerar pre visualizacao)\\b.*");
    }

    private boolean carriesPendingConfirmation(
            String prompt,
            AgenticAuthoringPendingClarification pendingClarification) {
        String sourcePrompt = normalize(pendingClarification == null ? null : pendingClarification.sourcePrompt());
        String normalizedPrompt = normalize(prompt);
        return !sourcePrompt.isBlank()
                && normalizedPrompt.contains(sourcePrompt)
                && (normalizedPrompt.contains("confirmed:")
                || isContinuationClarificationAnswer(prompt, pendingClarification));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
