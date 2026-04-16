package org.praxisplatform.config.ai.authoring;

final class AgenticAuthoringConversationPrompt {

    static final String CONFIRMATION_PREFIX = "Confirmed: ";

    private AgenticAuthoringConversationPrompt() {
    }

    static String appendConfirmation(String sourcePrompt, String answer) {
        return trim(sourcePrompt) + "\n\n" + CONFIRMATION_PREFIX + trim(answer);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
