package org.praxisplatform.config.ai.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeDocument;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeSearchContributor;
import org.springframework.util.StringUtils;

/**
 * Ferramenta de busca normativa, em regras declarativas e em catalogos de validacao/erros
 * alimentada por {@link AgentKnowledgeSearchContributor} registrados no host e no starter.
 */
public class SearchDomainKnowledgeAndRulesTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "search_domain_knowledge_and_rules";

    private final List<AgentKnowledgeSearchContributor> contributors;

    public SearchDomainKnowledgeAndRulesTool(List<AgentKnowledgeSearchContributor> contributors) {
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Termo, lei/estatuto, codigo de regra, motivo de validacao ou regra de negocio a consultar."));
        properties.put("topicKey", Map.of(
                "type", "string",
                "description", "Opcional: chave de topico para priorizar normas e regras do assunto."));
        properties.put("sourceFilter", Map.of(
                "type", "string",
                "description", "Opcional: filtrar fonte (STATUTE, DOMAIN_RULE, RUNTIME_RULE ou ERROR_CATALOG)."));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "Opcional: quantidade maxima de citacoes (1 a 8, padrao 5)."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Busca normas legais, estatutos por ente/tenant, regras declarativas e codigos de erro de negocio para fundamentar respostas e citacoes na UI.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("query"));
    }

    @Override
    public Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments) {
        Map<String, Object> args = rawArguments == null ? Map.of() : rawArguments;
        String query = args.get("query") == null ? "" : args.get("query").toString().trim();
        String topicKey = args.get("topicKey") == null ? null : args.get("topicKey").toString().trim();
        if (!StringUtils.hasText(topicKey) && context != null && StringUtils.hasText(context.activeTopicKey())) {
            topicKey = context.activeTopicKey();
        }
        String sourceFilter = args.get("sourceFilter") == null ? null : args.get("sourceFilter").toString().trim();
        int limit = Math.min(Math.max(1, asInt(args.get("limit"), 5)), 8);

        Map<String, AgentKnowledgeDocument> mergedById = new LinkedHashMap<>();
        for (AgentKnowledgeSearchContributor contributor : contributors) {
            List<AgentKnowledgeDocument> found = contributor.searchKnowledge(context, query, topicKey, sourceFilter, limit);
            if (found == null) {
                continue;
            }
            for (AgentKnowledgeDocument doc : found) {
                if (doc == null || !StringUtils.hasText(doc.docId())) {
                    continue;
                }
                AgentKnowledgeDocument existing = mergedById.get(doc.docId());
                if (existing == null || doc.score() > existing.score()) {
                    mergedById.put(doc.docId(), doc);
                }
            }
        }

        List<AgentKnowledgeDocument> ranked = new ArrayList<>(mergedById.values());
        ranked.sort(Comparator.comparingDouble(AgentKnowledgeDocument::score).reversed()
                .thenComparing(AgentKnowledgeDocument::title, Comparator.nullsLast(String::compareToIgnoreCase)));
        if (ranked.size() > limit) {
            ranked = ranked.subList(0, limit);
        }

        artifacts.recordKnowledgeCitations(ranked);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("found", !ranked.isEmpty());
        output.put("count", ranked.size());
        output.put("query", query);
        output.put("items", ranked.stream()
                .map(doc -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("docId", nonNull(doc.docId()));
                    item.put("sourceKind", nonNull(doc.sourceKind()));
                    item.put("title", nonNull(doc.title()));
                    item.put("normRef", nonNull(doc.normRef()));
                    item.put("scopeLabel", nonNull(doc.scopeLabel()));
                    item.put("excerpt", nonNull(doc.excerpt()));
                    item.put("tags", doc.tags());
                    if (StringUtils.hasText(doc.targetRoute())) {
                        item.put("targetRoute", doc.targetRoute());
                    }
                    return item;
                })
                .toList());
        return piiVault != null ? piiVault.scrubOutboundForModel(output) : output;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
