package org.praxisplatform.config.ai.agent.bridge;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeDocument;
import org.praxisplatform.config.ai.agent.spi.AgentKnowledgeSearchContributor;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Contribuidor padrao que consulta {@link DomainRuleDefinitionRepository} em transacao isolada
 * somente leitura para citar regras governadas pelo Control Plane no RAG operacional.
 */
@Slf4j
public class ConfigDomainRuleKnowledgeContributor implements AgentKnowledgeSearchContributor {

    private final DomainRuleDefinitionRepository repository;
    private final TransactionTemplate isolatedReadOnlyTxTemplate;

    public ConfigDomainRuleKnowledgeContributor(
            DomainRuleDefinitionRepository repository,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setReadOnly(true);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.isolatedReadOnlyTxTemplate = template;
        } else {
            this.isolatedReadOnlyTxTemplate = null;
        }
    }

    @Override
    public List<AgentKnowledgeDocument> searchKnowledge(
            AgentExecutionContext context,
            String query,
            String topicKey,
            String sourceFilter,
            int limit) {
        if (repository == null || !StringUtils.hasText(query)) {
            return List.of();
        }
        if (StringUtils.hasText(sourceFilter) && !"DOMAIN_RULE".equalsIgnoreCase(sourceFilter.trim())) {
            return List.of();
        }
        String tenantId = context != null && StringUtils.hasText(context.tenantId()) ? context.tenantId().trim() : "default";
        String environment = context != null && StringUtils.hasText(context.environment()) ? context.environment().trim() : "prod";
        int boundedLimit = Math.min(Math.max(1, limit), 10);

        try {
            List<AgentKnowledgeDocument> docs = isolatedReadOnlyTxTemplate != null
                    ? isolatedReadOnlyTxTemplate.execute(status -> queryAndRankCandidates(tenantId, environment, query.trim(), boundedLimit))
                    : queryAndRankCandidates(tenantId, environment, query.trim(), boundedLimit);
            return docs == null ? List.of() : docs;
        } catch (RuntimeException ex) {
            log.debug("[ConfigKnowledgeContributor] Consulta em domain_rule_definition ignorada: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<AgentKnowledgeDocument> queryAndRankCandidates(
            String tenantId,
            String environment,
            String query,
            int boundedLimit) {
        Page<DomainRuleDefinition> page = queryCandidates(tenantId, environment, query, boundedLimit);
        if (page != null && !page.isEmpty()) {
            List<AgentKnowledgeDocument> docs = new ArrayList<>();
            for (DomainRuleDefinition def : page.getContent()) {
                docs.add(toKnowledgeDocument(def, query));
            }
            return docs;
        }
        Set<String> queryTokens = tokenizeQuery(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        Page<DomainRuleDefinition> fallbackPage = queryCandidates(tenantId, environment, "", 50);
        if (fallbackPage == null || fallbackPage.isEmpty()) {
            return List.of();
        }
        List<AgentKnowledgeDocument> scoredDocs = new ArrayList<>();
        for (DomainRuleDefinition def : fallbackPage.getContent()) {
            String searchable = normalizeText(String.join(" ",
                    safe(def.getRuleKey()),
                    safe(def.getContextKey()),
                    safe(def.getResourceKey()),
                    safe(def.getServiceKey()),
                    safe(def.getSemanticOwner()),
                    safe(def.getRuleType())));
            if (searchable.isEmpty()) {
                continue;
            }
            int matched = 0;
            for (String token : queryTokens) {
                if (searchable.contains(token)) {
                    matched++;
                }
            }
            if (matched <= 0) {
                continue;
            }
            double statusBoost = ("PUBLISHED".equalsIgnoreCase(def.getStatus())
                    || "ACTIVE".equalsIgnoreCase(def.getStatus())) ? 0.08d : 0.0d;
            double score = 0.45d + 0.18d * ((double) matched / (double) queryTokens.size()) + statusBoost;
            scoredDocs.add(toKnowledgeDocument(def, query, score));
        }
        scoredDocs.sort(Comparator.comparingDouble(AgentKnowledgeDocument::score).reversed()
                .thenComparing(AgentKnowledgeDocument::title, Comparator.nullsLast(String::compareToIgnoreCase)));
        return scoredDocs.size() > boundedLimit ? scoredDocs.subList(0, boundedLimit) : scoredDocs;
    }

    private Page<DomainRuleDefinition> queryCandidates(String tenantId, String environment, String query, int limit) {
        return repository.searchCatalogCandidates(
                tenantId,
                environment,
                query.trim(),
                null,
                null,
                null,
                PageRequest.of(0, limit));
    }

    private static AgentKnowledgeDocument toKnowledgeDocument(DomainRuleDefinition def, String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        double score = 0.72d;
        if (def.getRuleKey() != null && def.getRuleKey().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            score += 0.15d;
        }
        if ("PUBLISHED".equalsIgnoreCase(def.getStatus()) || "ACTIVE".equalsIgnoreCase(def.getStatus())) {
            score += 0.08d;
        }
        return toKnowledgeDocument(def, query, score);
    }

    private static AgentKnowledgeDocument toKnowledgeDocument(DomainRuleDefinition def, String query, double score) {
        String excerpt = buildExcerpt(def);
        List<String> tags = new ArrayList<>();
        if (StringUtils.hasText(def.getRuleType())) {
            tags.add(def.getRuleType());
        }
        if (StringUtils.hasText(def.getStatus())) {
            tags.add(def.getStatus());
        }
        if (StringUtils.hasText(def.getResourceKey())) {
            tags.add(def.getResourceKey());
        }
        return new AgentKnowledgeDocument(
                "config-rule:" + def.getRuleKey() + ":v" + def.getVersion(),
                "DOMAIN_RULE",
                def.getRuleKey(),
                firstNonBlank(def.getContextKey(), "Control Plane v" + def.getVersion()),
                firstNonBlank(def.getSemanticOwner(), def.getTenantId()),
                excerpt,
                tags,
                score,
                null,
                Map.of(
                        "ruleKey", def.getRuleKey(),
                        "version", def.getVersion() != null ? def.getVersion() : 1,
                        "status", def.getStatus() != null ? def.getStatus() : ""));
    }

    private static String buildExcerpt(DomainRuleDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append("Regra governada ").append(def.getRuleKey())
                .append(" (v").append(def.getVersion()).append(", ").append(def.getStatus()).append(")");
        if (StringUtils.hasText(def.getResourceKey())) {
            sb.append(" vinculada ao recurso ").append(def.getResourceKey());
        }
        if (StringUtils.hasText(def.getContextKey())) {
            sb.append(" no contexto ").append(def.getContextKey());
        }
        sb.append('.');
        return sb.toString();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static Set<String> tokenizeQuery(String query) {
        String normalized = normalizeText(query);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : normalized.split("[^a-z0-9]+")) {
            if (part.length() >= 3) {
                tokens.add(part);
            }
        }
        return Set.copyOf(tokens);
    }
}
