package org.praxisplatform.config.ai.agent.security;

import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.tools.OperationalAgentTool.AgentToolSafetyClass;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default deterministic 4-stage {@link AgentSecurityGuard}.
 *
 * <p>
 * Designed for high precision with zero false positives on legitimate Portuguese corporate/legal text
 * (e.g., administrative orders, penalties, rules, or statutory overrides) while isolating meta-prompt
 * hijackers and enforcing a taint + target-grounding gate on {@link AgentToolSafetyClass#PROPOSAL_MUTATION}.
 * </p>
 */
public final class DefaultDeterministicAgentSecurityGuard implements AgentSecurityGuard {

    private static final Pattern ROLE_OR_DELIMITER_INJECTION = Pattern.compile(
            "(?is)(</?untrusted_context_data\\b[^>]*>|<\\|im_(start|end)\\|>|<\\|endoftext\\|>|\\[/?INST\\]|^\\s*#{2,}\\s*(system|developer|instruction)\\b|<system>\\s*|[\\r\\n]+\\s*system\\s*:\\s*(you must|ignore|override|call ))"
    );

    private static final Pattern META_INSTRUCTION_OVERRIDE = Pattern.compile(
            "(?is)(\\bignore\\s+(all\\s+|any\\s+)?(previous|prior|above|system)\\s+(instructions?|rules?|policies|guidelines)\\b"
                    + "|\\bdisregard\\s+(all\\s+|any\\s+)?(previous|prior|system)\\s+(instructions?|rules?)\\b"
                    + "|\\b(override|bypass)\\s+(the\\s+)?(system\\s+prompt|governance|security\\s+rules?|authorization)\\b"
                    + "|\\b(reveal|print|dump)\\s+(the\\s+)?(system\\s+prompt|hidden\\s+instructions?)\\b"
                    + "|\\bignore\\s+(todas?\\s+as\\s+|as\\s+)?(instrucoes|regras)\\s+(anteriores|do\\s+sistema)\\b"
                    + "|\\bdesconsidere\\s+(todas?\\s+as\\s+|as\\s+)?(instrucoes|regras)\\s+(anteriores|do\\s+sistema|de\\s+governanca)\\b"
                    + "|\\bcontorne\\s+(a\\s+)?(governanca|seguranca|autorizacao)\\b"
                    + "|\\bregras\\s+anteriores\\s+nao\\s+valem\\b"
                    + "|\\b(chame|execute|call|invoke)\\s+(imediatamente\\s+|now\\s+|without\\s+asking\\s+)?(prepare_action_or_mutation|prepare_bulk_operation)\\b)"
    );

    private static final Set<String> GROUNDABLE_ENTITY_TOOLS = Set.of(
            "query_resource_records",
            "search_and_resolve_entities",
            "get_entity_360_dossier"
    );

    private static final Set<String> TARGET_ID_KEYS = Set.of(
            "recordId", "entityId", "id", "targetId", "targetIds", "selectedIds"
    );

    private static final Set<String> RECORD_ID_FIELDS = Set.of(
            "id", "recordId", "entityId", "targetId", "itemId", "businessKey"
    );

    private static final Set<String> FREE_TEXT_CONTEXT_FIELDS = Set.of(
            "observacao", "observacoes", "justificativa", "descricao", "notes", "comment",
            "comments", "memo", "detalhe", "detalhes", "motivo", "parecer", "historico"
    );

    @Override
    public InboundCheckResult guardInboundUserInput(
            String userMessage,
            AgentExecutionContext context,
            TurnSecurityState state
    ) {
        if (state != null) {
            state.groundFromUserMessage(userMessage);
        }
        AgentExecutionContext sanitizedContext = context;
        boolean contextQuarantined = false;

        if (context != null && context.activeSelectionHints() != null && !context.activeSelectionHints().isEmpty()) {
            Map<String, Object> cleanedHints = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : context.activeSelectionHints().entrySet()) {
                String key = entry.getKey();
                Object rawVal = entry.getValue();
                if (rawVal != null && containsSuspiciousHint(rawVal)) {
                    contextQuarantined = true;
                    AgentSecurityFinding finding = new AgentSecurityFinding(
                            "inbound_hint",
                            "prompt-injection-in-selection-hint",
                            AgentSecurityVerdict.Decision.QUARANTINE,
                            "activeSelectionHints." + key,
                            "Suspicious instruction pattern in selection hint was isolated"
                    );
                    if (state != null) {
                        state.markTainted(finding);
                    }
                    if (isEnforce(state)) {
                        cleanedHints.put(key, "[isolated-untrusted-hint]");
                    } else {
                        cleanedHints.put(key, rawVal);
                    }
                } else {
                    cleanedHints.put(key, rawVal);
                    if (state != null && rawVal != null) {
                        groundFromCleanSelectionHint(key, rawVal, state);
                    }
                }
            }
            if (isEnforce(state) && contextQuarantined) {
                sanitizedContext = new AgentExecutionContext(
                        context.threadId(),
                        context.turnId(),
                        context.streamId(),
                        context.tenantId(),
                        context.userId(),
                        context.environment(),
                        context.principalName(),
                        context.authorities(),
                        context.activeScreenResourcePath(),
                        context.activeTopicKey(),
                        cleanedHints,
                        context.propagatedClaims()
                );
            }
        }

        if (userMessage != null && isSuspicious(userMessage)) {
            AgentSecurityFinding finding = new AgentSecurityFinding(
                    "inbound_user",
                    "prompt-injection-user-message",
                    AgentSecurityVerdict.Decision.BLOCK,
                    "userMessage",
                    "Prompt injection or meta-instruction override detected in user input"
            );
            if (state != null) {
                state.markTainted(finding);
            }
            if (isEnforce(state)) {
                return new InboundCheckResult(
                        AgentSecurityVerdict.block(
                                "prompt-injection-user-message",
                                "Solicitacao bloqueada pela politica de seguranca: tentativa de sobrescrever instrucoes do sistema."
                        ),
                        userMessage,
                        sanitizedContext
                );
            }
            return new InboundCheckResult(
                    AgentSecurityVerdict.warn("prompt-injection-user-message", finding.detail()),
                    userMessage,
                    sanitizedContext
            );
        }

        if (contextQuarantined) {
            return new InboundCheckResult(
                    AgentSecurityVerdict.quarantine(
                            "prompt-injection-in-selection-hint",
                            "Dicas de selecao suspeitas foram isoladas antes de compor o contexto."
                    ),
                    userMessage,
                    sanitizedContext
            );
        }
        return new InboundCheckResult(AgentSecurityVerdict.allow(), userMessage, sanitizedContext);
    }

    @Override
    public HistoryCheckResult guardHistoryEntry(String role, String text, TurnSecurityState state) {
        if (text == null || text.isBlank()) {
            return new HistoryCheckResult(AgentSecurityVerdict.allow(), text);
        }
        if (isSuspicious(text)) {
            AgentSecurityFinding finding = new AgentSecurityFinding(
                    "inbound_history",
                    "prompt-injection-in-history",
                    AgentSecurityVerdict.Decision.QUARANTINE,
                    "history." + (role != null ? role : "unknown"),
                    "Prompt injection or meta-instruction override detected in conversation history"
            );
            if (state != null) {
                state.markTainted(finding);
            }
            if (isEnforce(state)) {
                return new HistoryCheckResult(
                        AgentSecurityVerdict.quarantine(
                                "prompt-injection-in-history",
                                "Entrada suspeita no historico da conversa foi isolada."
                        ),
                        "[mensagem de historico suspeita isolada por seguranca]"
                );
            }
            return new HistoryCheckResult(
                    AgentSecurityVerdict.warn("prompt-injection-in-history", finding.detail()),
                    text
            );
        }
        if (state != null) {
            state.groundFromTrustedHistoryText(text);
        }
        return new HistoryCheckResult(AgentSecurityVerdict.allow(), text);
    }

    @Override
    public AgentSecurityVerdict guardBeforeToolExecution(
            String toolName,
            AgentToolSafetyClass safetyClass,
            Map<String, Object> arguments,
            TurnSecurityState state
    ) {
        if (safetyClass != AgentToolSafetyClass.PROPOSAL_MUTATION) {
            return AgentSecurityVerdict.allow();
        }
        if (state != null && state.isTainted()) {
            AgentSecurityFinding finding = new AgentSecurityFinding(
                    "pre_tool_mutation",
                    "tainted-turn-mutation-blocked",
                    AgentSecurityVerdict.Decision.BLOCK,
                    toolName,
                    "Mutation tool blocked because earlier untrusted data triggered prompt-injection taint"
            );
            state.addFinding(finding);
            if (isEnforce(state)) {
                return AgentSecurityVerdict.block(
                        "tainted-turn-mutation-blocked",
                        "Operacao de mutacao/proposta bloqueada: dados nao confiaveis lidos neste turno continham instrucoes suspeitas. Confirme explicitamente em um novo turno limpo."
                );
            }
            return AgentSecurityVerdict.warn("tainted-turn-mutation-blocked", finding.detail());
        }

        if (state != null && arguments != null && !arguments.isEmpty()) {
            List<String> targetIds = extractTargetIds(arguments);
            for (String targetId : targetIds) {
                if (!state.isTargetGrounded(targetId)) {
                    AgentSecurityFinding finding = new AgentSecurityFinding(
                            "pre_tool_mutation",
                            "ungrounded-mutation-target",
                            AgentSecurityVerdict.Decision.BLOCK,
                            toolName + ".targetId",
                            "Target ID '" + targetId + "' was not verified by read tools or user request in this turn"
                    );
                    state.addFinding(finding);
                    if (isEnforce(state)) {
                        return AgentSecurityVerdict.block(
                                "ungrounded-mutation-target",
                                "Mutacao bloqueada: o identificador alvo '" + targetId
                                        + "' nao foi confirmado via consulta previa nem citado explicitamente pelo usuario neste turno. Consulte o registro primeiro."
                        );
                    }
                    return AgentSecurityVerdict.warn("ungrounded-mutation-target", finding.detail());
                }
            }
        }

        if ("prepare_bulk_operation".equals(toolName) && arguments != null) {
            Object rawSelectionMode = arguments.get("selectionMode");
            if (rawSelectionMode != null && "QUERY".equalsIgnoreCase(String.valueOf(rawSelectionMode).trim())) {
                List<String> excludedIds = new ArrayList<>();
                collectTargetIdValue(arguments.get("excludedIds"), excludedIds);
                for (String excludedId : excludedIds) {
                    if (state != null && !state.isTargetGrounded(excludedId)) {
                        AgentSecurityFinding finding = new AgentSecurityFinding(
                                "pre_tool_mutation",
                                "ungrounded-mutation-target",
                                AgentSecurityVerdict.Decision.BLOCK,
                                toolName + ".excludedIds",
                                "Excluded ID '" + excludedId + "' was not verified by read tools or user request in this turn"
                        );
                        state.addFinding(finding);
                        if (isEnforce(state)) {
                            return AgentSecurityVerdict.block(
                                    "ungrounded-mutation-target",
                                    "Mutacao em lote bloqueada: o identificador em excludedIds '" + excludedId
                                            + "' nao foi confirmado via consulta previa nem citado pelo usuario neste turno."
                            );
                        }
                        return AgentSecurityVerdict.warn("ungrounded-mutation-target", finding.detail());
                    }
                }
                Object rawFilters = arguments.get("filters");
                boolean hasNonEmptyFilters = rawFilters instanceof Map<?, ?> filterMap && !filterMap.isEmpty();
                boolean hasReadGrounding = state != null && state.hasReadGrounding();
                if (!hasNonEmptyFilters && !hasReadGrounding) {
                    AgentSecurityFinding finding = new AgentSecurityFinding(
                            "pre_tool_mutation",
                            "ungrounded-bulk-query-scope",
                            AgentSecurityVerdict.Decision.BLOCK,
                            toolName + ".filters",
                            "QUERY-mode bulk operation requires explicit non-empty filters or prior read grounding in this turn"
                    );
                    if (state != null) {
                        state.addFinding(finding);
                    }
                    if (isEnforce(state)) {
                        return AgentSecurityVerdict.block(
                                "ungrounded-bulk-query-scope",
                                "Mutacao em lote no modo QUERY bloqueada: informe filtros explicitos nao vazios ou consulte os registros antes de preparar alteracoes coletivas."
                        );
                    }
                    return AgentSecurityVerdict.warn("ungrounded-bulk-query-scope", finding.detail());
                }
            }
        }
        return AgentSecurityVerdict.allow();
    }

    @Override
    public ToolOutputCheckResult guardToolOutput(
            String toolName,
            AgentToolSafetyClass safetyClass,
            Map<String, Object> payload,
            TurnSecurityState state
    ) {
        if (payload == null || payload.isEmpty()) {
            return new ToolOutputCheckResult(AgentSecurityVerdict.allow(), payload);
        }
        boolean groundableTool = GROUNDABLE_ENTITY_TOOLS.contains(toolName);
        if (state != null && groundableTool) {
            state.markReadGrounding();
            collectGroundedIds(payload, state);
        }

        boolean enforce = isEnforce(state);
        boolean[] quarantinedAny = { false };
        Map<String, Object> sanitized = sanitizeMap(
                payload, "$", toolName, state, enforce, groundableTool, quarantinedAny);

        if (quarantinedAny[0]) {
            return new ToolOutputCheckResult(
                    AgentSecurityVerdict.quarantine(
                            "indirect-prompt-injection-in-tool-output",
                            "Campo textual suspeito retornado pela ferramenta '" + toolName + "' foi isolado e o turno foi marcado como tainted."
                    ),
                    enforce ? sanitized : payload
            );
        }
        return new ToolOutputCheckResult(AgentSecurityVerdict.allow(), enforce ? sanitized : payload);
    }

    @Override
    public OutboundCheckResult guardOutboundResponse(String narrative, TurnSecurityState state) {
        if (narrative == null || narrative.isBlank()) {
            return new OutboundCheckResult(AgentSecurityVerdict.allow(), narrative);
        }
        if (isEnforce(state) && (narrative.contains("<untrusted_context_data")
                || narrative.contains("</untrusted_context_data>"))) {
            String cleaned = narrative
                    .replace("</untrusted_context_data>", "")
                    .replaceAll("<untrusted_context_data[^>]*>", "")
                    .trim();
            if (state != null) {
                state.addFinding(new AgentSecurityFinding(
                        "outbound",
                        "fencing-tag-stripped",
                        AgentSecurityVerdict.Decision.WARN,
                        "narrative",
                        "Removed internal context fencing tag from outbound narrative"
                ));
            }
            return new OutboundCheckResult(
                    AgentSecurityVerdict.warn("fencing-tag-stripped", "Internal delimiter stripped"),
                    cleaned
            );
        }
        return new OutboundCheckResult(AgentSecurityVerdict.allow(), narrative);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMap(
            Map<String, Object> source,
            String path,
            String toolName,
            TurnSecurityState state,
            boolean enforce,
            boolean fenceFreeText,
            boolean[] quarantinedAny
    ) {
        Map<String, Object> copy = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String childPath = path + "." + key;
            Object val = entry.getValue();
            copy.put(key, sanitizeValue(val, key, childPath, toolName, state, enforce, fenceFreeText, quarantinedAny));
        }
        return copy;
    }

    private Object sanitizeValue(
            Object val,
            String fieldName,
            String path,
            String toolName,
            TurnSecurityState state,
            boolean enforce,
            boolean fenceFreeText,
            boolean[] quarantinedAny
    ) {
        if (val instanceof String str) {
            if (isSuspicious(str)) {
                quarantinedAny[0] = true;
                AgentSecurityFinding finding = new AgentSecurityFinding(
                        "tool_output",
                        "indirect-prompt-injection-in-tool-output",
                        AgentSecurityVerdict.Decision.QUARANTINE,
                        toolName + ":" + path,
                        "Suspicious imperative instruction found in untrusted field '" + fieldName + "'"
                );
                if (state != null) {
                    state.markTainted(finding);
                }
                if (enforce) {
                    return "[conteudo suspeito isolado por seguranca — campo='" + fieldName + "']";
                }
            } else if (enforce && fenceFreeText && isFenceableFreeTextField(fieldName)
                    && str.length() >= 20 && containsWhitespace(str)
                    && !str.trim().startsWith("<untrusted_context_data")) {
                String safeField = fieldName == null ? "text" : fieldName.replaceAll("[^A-Za-z0-9._-]", "_");
                return "<untrusted_context_data field=\"" + safeField + "\">"
                        + str
                        + "</untrusted_context_data>";
            }
            return str;
        }
        if (val instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((k, v) -> typed.put(String.valueOf(k), v));
            return sanitizeMap(typed, path, toolName, state, enforce, fenceFreeText, quarantinedAny);
        }
        if (val instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                copy.add(sanitizeValue(
                        list.get(i), fieldName, path + "[" + i + "]", toolName, state, enforce, fenceFreeText, quarantinedAny));
            }
            return copy;
        }
        return val;
    }

    private static boolean isFenceableFreeTextField(String fieldName) {
        return fieldName != null
                && FREE_TEXT_CONTEXT_FIELDS.contains(fieldName.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void collectGroundedIds(Object node, TurnSecurityState state) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (RECORD_ID_FIELDS.contains(key) && value != null
                        && (value instanceof Number || value instanceof String)) {
                    String str = String.valueOf(value).trim();
                    if (!str.isBlank() && !isSuspicious(str)) {
                        state.recordGroundedEntityId(str);
                    }
                } else if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
                    collectGroundedIds(value, state);
                }
            }
        } else if (node instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectGroundedIds(item, state);
            }
        }
    }

    private static List<String> extractTargetIds(Map<String, Object> arguments) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (TARGET_ID_KEYS.contains(key)) {
                collectTargetIdValue(value, ids);
            } else if ("items".equals(key) && value instanceof Collection<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> itemMap) {
                        for (Map.Entry<?, ?> itemEntry : itemMap.entrySet()) {
                            String itemKey = itemEntry.getKey() == null ? "" : String.valueOf(itemEntry.getKey());
                            if (RECORD_ID_FIELDS.contains(itemKey)) {
                                collectTargetIdValue(itemEntry.getValue(), ids);
                            }
                        }
                    }
                }
            }
        }
        return ids;
    }

    private static void collectTargetIdValue(Object value, List<String> ids) {
        if (value instanceof Collection<?> col) {
            for (Object item : col) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    ids.add(String.valueOf(item).trim());
                }
            }
        } else if (value != null && !String.valueOf(value).isBlank()) {
            ids.add(String.valueOf(value).trim());
        }
    }

    private static boolean containsSuspiciousHint(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsSuspiciousHint(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Collection<?> col) {
            for (Object item : col) {
                if (containsSuspiciousHint(item)) {
                    return true;
                }
            }
            return false;
        }
        return isSuspicious(String.valueOf(value));
    }

    private static void groundFromCleanSelectionHint(String key, Object value, TurnSecurityState state) {
        if (key != null && RECORD_ID_FIELDS.contains(key)
                && (value instanceof Number || value instanceof String)) {
            String str = String.valueOf(value).trim();
            if (!str.isBlank() && !isSuspicious(str)) {
                state.recordGroundedEntityId(str);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                if (entry.getValue() != null) {
                    groundFromCleanSelectionHint(childKey, entry.getValue(), state);
                }
            }
        } else if (value instanceof Collection<?> col && key != null && TARGET_ID_KEYS.contains(key)) {
            for (Object item : col) {
                if (item instanceof Number || item instanceof String) {
                    String str = String.valueOf(item).trim();
                    if (!str.isBlank() && !isSuspicious(str)) {
                        state.recordGroundedEntityId(str);
                    }
                }
            }
        }
    }

    public static boolean isSuspicious(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return ROLE_OR_DELIMITER_INJECTION.matcher(normalized).find()
                || META_INSTRUCTION_OVERRIDE.matcher(normalized).find();
    }

    private static boolean isEnforce(TurnSecurityState state) {
        return state == null || state.mode() != AgentSecurityMode.AUDIT_ONLY;
    }
}
