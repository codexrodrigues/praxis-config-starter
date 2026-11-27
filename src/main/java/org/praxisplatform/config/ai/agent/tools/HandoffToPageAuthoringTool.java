package org.praxisplatform.config.ai.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.OperationalAgentTurnStreamService;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordance;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.springframework.util.StringUtils;

/**
 * Ferramenta fail-closed para preparar redirecionamento governado do assistente operacional
 * para o workspace de autoria e edição de páginas customizadas anunciado no contexto do turno.
 */
public class HandoffToPageAuthoringTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "handoff_to_page_authoring";
    private static final String DEFAULT_ACTION_LABEL = "Abrir no Construtor de Páginas";
    private static final int MAX_PROMPT_LENGTH = 1200;
    private static final int MAX_TITLE_LENGTH = 120;

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("authoringPrompt", Map.of(
                "type", "string",
                "description", "Especificação clara e completa em linguagem natural da página customizada desejada (widgets como tabela, filtros, gráficos, KPIs ou ficha de detalhes, recurso de domínio e métricas quando citados)."));
        properties.put("pageTitle", Map.of(
                "type", "string",
                "description", "Opcional: título curto sugerido para a página customizada."));
        properties.put("resourcePath", Map.of(
                "type", "string",
                "description", "Opcional: caminho canônico do recurso principal quando já conhecido (ex.: /api/workforce/assignments)."));
        properties.put("autoSubmit", Map.of(
                "type", "boolean",
                "description", "Quando true (padrão), aciona automaticamente a geração da prévia estrutural ao abrir o workspace de autoria."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Prepara uma ação de navegação para abrir o workspace de autoria de páginas customizadas com o pedido do usuário preenchido, quando esse workspace estiver habilitado no contexto da sessão.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("authoringPrompt"));
    }

    @Override
    public Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments) {
        Map<String, Object> args = rawArguments == null ? Map.of() : rawArguments;
        Map<String, Object> claims = context != null && context.propagatedClaims() != null
                ? context.propagatedClaims()
                : Map.of();

        String rawRoute = claims.get(OperationalAgentTurnStreamService.CLAIM_AUTHORING_WORKSPACE_ROUTE) instanceof String s
                ? s
                : null;
        String safeRoute = OperationalAgentTurnStreamService.sanitizeAuthoringWorkspaceRoute(rawRoute);
        if (safeRoute == null) {
            return Map.of(
                    "handoffReady", false,
                    "reason", "authoring-workspace-unavailable",
                    "message", "Nenhum workspace de autoria de páginas customizadas está habilitado para este usuário ou sessão.");
        }

        String authoringPrompt = sanitizePrompt(args.get("authoringPrompt"));
        if (authoringPrompt == null) {
            return Map.of(
                    "handoffReady", false,
                    "reason", "missing-authoring-prompt",
                    "message", "Informe a descrição da página customizada desejada para preparar o redirecionamento.");
        }

        String rawClaimLabel = claims.get(OperationalAgentTurnStreamService.CLAIM_AUTHORING_WORKSPACE_LABEL) instanceof String s
                ? s
                : null;
        String safeLabel = OperationalAgentTurnStreamService.sanitizeAuthoringWorkspaceLabel(rawClaimLabel);
        if (safeLabel == null) {
            safeLabel = DEFAULT_ACTION_LABEL;
        }

        String pageTitle = sanitizeTitle(args.get("pageTitle"));
        String resourcePath = sanitizeResourcePath(args.get("resourcePath"));
        boolean autoSubmit = !Boolean.FALSE.equals(parseBoolean(args.get("autoSubmit")));

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("aiPrompt", authoringPrompt);
        if (pageTitle != null) {
            queryParams.put("aiTitle", pageTitle);
        }
        queryParams.put("aiAutoSubmit", Boolean.toString(autoSubmit));

        AgentActionAffordance affordance = new AgentActionAffordance(
                "handoff-page-authoring",
                safeLabel,
                "Abre o workspace de autoria de páginas customizadas com este pedido já preenchido.",
                "auto_awesome",
                "primary",
                null,
                resourcePath,
                null,
                "route",
                null,
                "GET",
                null,
                null,
                safeRoute,
                queryParams,
                Map.of(),
                false,
                false,
                null,
                0);
        if (artifacts != null) {
            artifacts.recordHandoffAction(affordance);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("handoffReady", true);
        output.put("route", safeRoute);
        output.put("label", safeLabel);
        output.put("autoSubmit", autoSubmit);
        output.put("authoringPrompt", authoringPrompt);
        if (pageTitle != null) {
            output.put("pageTitle", pageTitle);
        }
        if (resourcePath != null) {
            output.put("resourcePath", resourcePath);
        }
        return piiVault != null ? piiVault.scrubOutboundForModel(output) : output;
    }

    private static String sanitizePrompt(Object raw) {
        if (!(raw instanceof String text) || !StringUtils.hasText(text)) {
            return null;
        }
        String cleaned = text
                .replaceAll("[\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_PROMPT_LENGTH
                ? cleaned.substring(0, MAX_PROMPT_LENGTH).trim()
                : cleaned;
    }

    private static String sanitizeTitle(Object raw) {
        if (!(raw instanceof String text) || !StringUtils.hasText(text)) {
            return null;
        }
        String cleaned = text
                .replaceAll("[\\p{Cntrl}<>]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_TITLE_LENGTH
                ? cleaned.substring(0, MAX_TITLE_LENGTH).trim()
                : cleaned;
    }

    private static String sanitizeResourcePath(Object raw) {
        if (!(raw instanceof String text) || !StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("/")
                || trimmed.startsWith("//")
                || trimmed.contains("..")
                || trimmed.length() > 160
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return trimmed;
    }

    private static Boolean parseBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s && StringUtils.hasText(s)) {
            String normalized = s.trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
