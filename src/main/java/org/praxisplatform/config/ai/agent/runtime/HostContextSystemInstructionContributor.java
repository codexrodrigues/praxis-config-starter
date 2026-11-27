package org.praxisplatform.config.ai.agent.runtime;

import java.util.Optional;
import java.util.regex.Pattern;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionContributor;
import org.praxisplatform.config.ai.agent.spi.AgentSystemInstructionSection;
import org.springframework.util.StringUtils;

/**
 * Contributes tenant/host UI focus metadata and fenced {@code <untrusted_context_data>} selection
 * hints at the very end ({@link AgentSystemInstructionSection.SectionPlacement#VOLATILE_TURN_CONTEXT})
 * to preserve prompt prefix caching across turns.
 */
public final class HostContextSystemInstructionContributor implements AgentSystemInstructionContributor {

    private static final int MAX_HINT_LENGTH = 768;
    private static final Pattern CLOSING_UNTRUSTED_TAG = Pattern.compile("(?i)</\\s*untrusted_context_data\\b[^>]*>");
    private static final Pattern OPENING_UNTRUSTED_TAG = Pattern.compile("(?i)<\\s*untrusted_context_data\\b[^>]*>");

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 100;
    }

    @Override
    public Optional<AgentSystemInstructionSection> contribute(
            AgentExecutionContext context,
            String resourceDiscoverySummary) {
        if (context == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(context.activeTopicKey())) {
            sb.append("Tópico em foco na tela ativa: ")
                    .append(sanitizePromptHint(context.activeTopicKey(), 120))
                    .append(".\n");
        }
        if (StringUtils.hasText(context.activeScreenResourcePath())) {
            sb.append("Contexto consultivo da tela aberta (apenas desempate, não bloqueia buscas em outros módulos): ")
                    .append(sanitizePromptHint(context.activeScreenResourcePath(), 240))
                    .append(".\n");
        }
        if (context.propagatedClaims() != null && !context.propagatedClaims().isEmpty()) {
            Object rawRoute = context.propagatedClaims()
                    .get(OperationalAgentTurnStreamService.CLAIM_AUTHORING_WORKSPACE_ROUTE);
            String authoringRoute = rawRoute instanceof String s
                    ? OperationalAgentTurnStreamService.sanitizeAuthoringWorkspaceRoute(s)
                    : null;
            if (authoringRoute != null) {
                Object rawLabel = context.propagatedClaims()
                        .get(OperationalAgentTurnStreamService.CLAIM_AUTHORING_WORKSPACE_LABEL);
                String authoringLabel = rawLabel instanceof String s
                        ? OperationalAgentTurnStreamService.sanitizeAuthoringWorkspaceLabel(
                                sanitizeUntrustedTagBreakouts(s))
                        : null;
                if (authoringLabel == null) {
                    authoringLabel = "Abrir no Construtor de Páginas";
                }
                sb.append("Workspace de autoria de páginas customizadas habilitado nesta sessão: rota ")
                        .append(authoringRoute)
                        .append(" (botão \"")
                        .append(authoringLabel)
                        .append("\"). Quando o usuário pedir para criar, montar ou editar uma página/tela customizada, chame obrigatoriamente 'handoff_to_page_authoring'.\n");
            }
        }
        if (context.activeSelectionHints() != null && !context.activeSelectionHints().isEmpty()) {
            Object scrubbedHints = OperationalAssistantBlockComposer.maskMod11InStructure(context.activeSelectionHints());
            String maskedText = TurnScopedPiiVault.maskRawEmails(
                    OperationalAssistantBlockComposer.maskMod11TaxIds(String.valueOf(scrubbedHints)));
            String sanitized = sanitizeUntrustedTagBreakouts(sanitizePromptHint(maskedText, MAX_HINT_LENGTH));
            sb.append("<untrusted_context_data type=\"active_selection_hints\">\n")
                    .append("Registro/escopo ativo em foco na conversa (somente leitura/referência; sanitizado): ")
                    .append(sanitized)
                    .append("\n</untrusted_context_data>");
        }
        String content = sb.toString().trim();
        if (content.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AgentSystemInstructionSection(
                "praxis.core.host-context",
                AgentSystemInstructionSection.SectionPlacement.VOLATILE_TURN_CONTEXT,
                content));
    }

    static String sanitizePromptHint(String raw, int maxLen) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String singleLine = raw.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return singleLine.length() > maxLen ? singleLine.substring(0, maxLen) : singleLine;
    }

    static String sanitizeUntrustedTagBreakouts(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String withoutClosing = CLOSING_UNTRUSTED_TAG.matcher(raw).replaceAll("[removed-closing-tag]");
        return OPENING_UNTRUSTED_TAG.matcher(withoutClosing).replaceAll("[removed-opening-tag]");
    }
}
