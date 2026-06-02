package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.praxisplatform.config.dto.Domain360CatalogResponse;
import org.praxisplatform.config.dto.Domain360CatalogResponse.Domain360Coverage;
import org.praxisplatform.config.dto.Domain360CatalogResponse.Domain360Diagnostic;
import org.praxisplatform.config.dto.Domain360CatalogResponse.Domain360Entry;
import org.praxisplatform.config.dto.Domain360CatalogResponse.Domain360Route;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainFederationContextQueryResponse;
import org.praxisplatform.config.dto.DomainFederationRetrievalPolicyOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnBean(DomainFederationQueryService.class)
public class Domain360CatalogService {

    private static final String SCHEMA_VERSION = "praxis.domain-360-catalog/v0.1";

    private final DomainFederationQueryService domainFederationQueryService;

    public Domain360CatalogService(DomainFederationQueryService domainFederationQueryService) {
        this.domainFederationQueryService = domainFederationQueryService;
    }

    public Domain360CatalogResponse catalog(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String contextKey,
            String query,
            int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 200);
        List<DomainFederationContextQueryResponse> federatedContexts = federatedContexts(
                serviceKey,
                resourceKey,
                tenantId,
                environment,
                contextKey,
                query,
                effectiveLimit);
        DomainFederationContextQueryResponse federated = federatedContexts.get(0);

        DomainCatalogContextResponse context = federated.context();
        List<DomainCatalogItemResponse> contextItems = new ArrayList<>();
        List<DomainCatalogItemResponse> relationships = new ArrayList<>();
        List<DomainCatalogItemResponse> contracts = new ArrayList<>();
        List<DomainCatalogItemResponse> resolutions = new ArrayList<>();
        for (DomainFederationContextQueryResponse response : federatedContexts) {
            DomainCatalogContextResponse responseContext = response.context();
            if (responseContext != null) {
                contextItems.addAll(safe(responseContext.items()));
            }
            relationships.addAll(safe(response.relationships()));
            contracts.addAll(safe(response.contracts()));
            resolutions.addAll(safe(response.resolutions()));
        }

        List<Domain360Entry> resources = new ArrayList<>();
        List<Domain360Entry> fields = new ArrayList<>();
        List<Domain360Entry> capabilities = new ArrayList<>();
        List<Domain360Entry> surfaces = new ArrayList<>();
        List<Domain360Entry> actions = new ArrayList<>();
        List<Domain360Entry> workflows = new ArrayList<>();
        List<Domain360Entry> stats = new ArrayList<>();
        List<Domain360Entry> optionSources = new ArrayList<>();

        for (DomainCatalogItemResponse item : contextItems) {
            Domain360Entry entry = toEntry(item);
            Classification classification = classify(item);
            switch (classification) {
                case FIELD -> fields.add(entry);
                case CAPABILITY -> capabilities.add(entry);
                case SURFACE -> surfaces.add(entry);
                case ACTION -> actions.add(entry);
                case WORKFLOW -> workflows.add(entry);
                case STATS -> stats.add(entry);
                case OPTION_SOURCE -> optionSources.add(entry);
                case RESOURCE -> resources.add(entry);
                case UNKNOWN -> {
                    if (isResourceLike(item)) {
                        resources.add(entry);
                    }
                }
            }
        }

        for (DomainCatalogItemResponse contract : contracts) {
            Domain360Entry entry = toEntry(contract);
            Classification classification = classify(contract);
            switch (classification) {
                case CAPABILITY -> capabilities.add(entry);
                case SURFACE -> surfaces.add(entry);
                case ACTION -> actions.add(entry);
                case WORKFLOW -> workflows.add(entry);
                case STATS -> stats.add(entry);
                case OPTION_SOURCE -> optionSources.add(entry);
                default -> {
                }
            }
        }

        List<Domain360Entry> relationshipEntries = relationships.stream().map(this::toEntry).toList();
        List<Domain360Entry> contractEntries = contracts.stream().map(this::toEntry).toList();
        List<Domain360Entry> resolutionEntries = resolutions.stream().map(this::toEntry).toList();
        List<Domain360Entry> dedupedResources = dedupe(resources);
        if (dedupedResources.isEmpty() && StringUtils.hasText(normalize(resourceKey))) {
            dedupedResources = List.of(fallbackResource(resourceKey, contextKey));
        }
        List<Domain360Entry> dedupedFields = dedupe(fields);
        List<Domain360Entry> dedupedCapabilities = dedupe(capabilities);
        List<Domain360Entry> dedupedSurfaces = dedupe(surfaces);
        List<Domain360Entry> dedupedActions = dedupe(actions);
        List<Domain360Entry> dedupedWorkflows = dedupe(workflows);
        List<Domain360Entry> dedupedStats = dedupe(stats);
        List<Domain360Entry> dedupedOptionSources = dedupe(optionSources);
        List<Domain360Entry> dedupedRelationships = dedupe(relationshipEntries);
        List<Domain360Entry> dedupedContracts = dedupe(contractEntries);
        List<Domain360Entry> dedupedResolutions = dedupe(resolutionEntries);
        List<Domain360Diagnostic> diagnostics = diagnostics(
                contextItems,
                dedupedResources,
                dedupedFields,
                dedupedSurfaces,
                dedupedStats,
                dedupedOptionSources,
                dedupedRelationships);
        List<Domain360Route> routes = recommendedRoutes(
                dedupedFields,
                dedupedCapabilities,
                dedupedSurfaces,
                dedupedActions,
                dedupedWorkflows,
                dedupedStats,
                dedupedOptionSources);

        return new Domain360CatalogResponse(
                SCHEMA_VERSION,
                federated.tenantId(),
                federated.environment(),
                federated.serviceKey(),
                federated.resourceKey(),
                federated.query(),
                federated.sourceMode(),
                context == null ? null : context.release(),
                federated.retrievalGuidance(),
                new Domain360Coverage(
                        dedupedResources.size(),
                        dedupedFields.size(),
                        dedupedCapabilities.size(),
                        dedupedSurfaces.size(),
                        dedupedActions.size(),
                        dedupedWorkflows.size(),
                        dedupedStats.size(),
                        dedupedOptionSources.size(),
                        dedupedRelationships.size(),
                        dedupedContracts.size(),
                        dedupedResolutions.size()),
                dedupedResources,
                dedupedFields,
                dedupedCapabilities,
                dedupedSurfaces,
                dedupedActions,
                dedupedWorkflows,
                dedupedStats,
                dedupedOptionSources,
                dedupedRelationships,
                dedupedContracts,
                dedupedResolutions,
                routes,
                diagnostics);
    }

    private List<DomainFederationContextQueryResponse> federatedContexts(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String contextKey,
            String query,
            int effectiveLimit) {
        List<String> queries = new ArrayList<>();
        queries.add(normalize(query));
        queries.addAll(List.of("surface", "option", "stats", "kpi", "action", "workflow"));
        List<DomainFederationContextQueryResponse> responses = new ArrayList<>();
        List<String> distinctQueries = new ArrayList<>();
        for (String candidateQuery : queries) {
            if (!distinctQueries.contains(candidateQuery)) {
                distinctQueries.add(candidateQuery);
            }
        }
        for (String candidateQuery : distinctQueries) {
            responses.add(domainFederationQueryService.context(
                    normalize(serviceKey),
                    normalize(resourceKey),
                    normalize(tenantId),
                    normalize(environment),
                    null,
                    normalize(contextKey),
                    null,
                    null,
                    candidateQuery,
                    effectiveLimit,
                    new DomainFederationRetrievalPolicyOptions("authoring", null, null, null)));
        }
        if (responses.isEmpty()) {
            responses.add(domainFederationQueryService.context(
                    normalize(serviceKey),
                    normalize(resourceKey),
                    normalize(tenantId),
                    normalize(environment),
                    null,
                    normalize(contextKey),
                    null,
                    null,
                    null,
                    effectiveLimit,
                    new DomainFederationRetrievalPolicyOptions("authoring", null, null, null)));
        }
        return List.copyOf(responses);
    }

    private List<Domain360Diagnostic> diagnostics(
            List<DomainCatalogItemResponse> contextItems,
            List<Domain360Entry> resources,
            List<Domain360Entry> fields,
            List<Domain360Entry> surfaces,
            List<Domain360Entry> stats,
            List<Domain360Entry> optionSources,
            List<Domain360Entry> relationships) {
        List<Domain360Diagnostic> diagnostics = new ArrayList<>();
        if (contextItems.isEmpty()) {
            diagnostics.add(new Domain360Diagnostic(
                    "warning",
                    "domain360.empty-context",
                    "Nenhum item de dominio foi encontrado para o escopo informado.",
                    "context"));
        }
        if (resources.isEmpty()) {
            diagnostics.add(new Domain360Diagnostic(
                    "info",
                    "domain360.resource-not-explicit",
                    "O catalogo nao trouxe um recurso principal explicito; a UI deve usar o resourceKey solicitado como fallback.",
                    "resources"));
        }
        if (fields.isEmpty()) {
            diagnostics.add(new Domain360Diagnostic(
                    "warning",
                    "domain360.no-fields",
                    "Nenhum campo canonico foi encontrado; dashboards e formularios guiados podem ficar limitados.",
                    "fields"));
        }
        if (stats.isEmpty()) {
            diagnostics.add(new Domain360Diagnostic(
                    "info",
                    "domain360.no-stats",
                    "Nenhuma capability analitica/stats foi encontrada; o roteiro de dashboard deve evitar graficos inventados.",
                    "stats"));
        }
        if (optionSources.isEmpty()) {
            diagnostics.add(new Domain360Diagnostic(
                    "info",
                    "domain360.no-option-sources",
                    "Nenhuma option source foi encontrada; filtros ricos podem precisar usar campos simples.",
                    "optionSources"));
        }
        if (surfaces.isEmpty()) {
            diagnostics.add(new Domain360Diagnostic(
                    "info",
                    "domain360.no-surfaces",
                    "Nenhuma surface foi encontrada; interacoes de detalhe/modal devem ser omitidas ou solicitadas explicitamente.",
                    "surfaces"));
        }
        if (relationships.isEmpty()) {
            diagnostics.add(new Domain360Diagnostic(
                    "info",
                    "domain360.no-relationships",
                    "Nenhum relacionamento explicito foi encontrado; o 360 ficara restrito ao recurso principal.",
                    "relationships"));
        }
        return List.copyOf(diagnostics);
    }

    private List<Domain360Route> recommendedRoutes(
            List<Domain360Entry> fields,
            List<Domain360Entry> capabilities,
            List<Domain360Entry> surfaces,
            List<Domain360Entry> actions,
            List<Domain360Entry> workflows,
            List<Domain360Entry> stats,
            List<Domain360Entry> optionSources) {
        List<Domain360Route> routes = new ArrayList<>();
        if (!fields.isEmpty() || !stats.isEmpty() || !capabilities.isEmpty()) {
            List<String> interactions = new ArrayList<>(List.of("filter.queryContext", "chart.crossFilter"));
            if (!surfaces.isEmpty()) {
                interactions.add("surface.open");
            }
            routes.add(new Domain360Route(
                    "dashboard-overview",
                    "Dashboard 360",
                    "dashboard",
                    "analytical-dashboard",
                    List.of("praxis-filter", "praxis-rich-content", "praxis-chart", "praxis-list", "praxis-table"),
                    List.copyOf(interactions),
                    List.of("filter", "stats", "list", "detail"),
                    "Criar painel 360 com filtros canonicos, KPIs, graficos, lista rica, tabela e detalhes quando houver surface.",
                    routeConfidence(fields, stats, optionSources)));
        }
        if (!fields.isEmpty()) {
            routes.add(new Domain360Route(
                    "rich-list",
                    "Lista rica operacional",
                    "list",
                    "rich-resource-list",
                    List.of("praxis-filter", "praxis-list"),
                    surfaces.isEmpty() ? List.of("filter.queryContext") : List.of("filter.queryContext", "surface.open"),
                    List.of("filter", "list"),
                    "Criar lista rica com os campos mais importantes do recurso e filtros disponiveis.",
                    "high"));
            routes.add(new Domain360Route(
                    "guided-form",
                    "Formulario guiado",
                    "form",
                    "metadata-form",
                    List.of("praxis-dynamic-form", "praxis-rich-content"),
                    List.of("form.submit"),
                    List.of("create", "update"),
                    "Criar formulario guiado usando apenas campos e validacoes expostos pelo dominio.",
                    actions.isEmpty() ? "medium" : "high"));
        }
        if (!surfaces.isEmpty()) {
            routes.add(new Domain360Route(
                    "detail-surface",
                    "Detalhe em surface",
                    "surface",
                    "surface-detail",
                    List.of("praxis-list", "praxis-table", "praxis-dynamic-form", "praxis-rich-content"),
                    List.of("surface.open"),
                    List.of("detail"),
                    "Abrir a surface de detalhe a partir de lista, tabela ou grafico com contexto de selecao.",
                    "high"));
        }
        if (!actions.isEmpty() || !workflows.isEmpty()) {
            routes.add(new Domain360Route(
                    "operational-workspace",
                    "Workspace operacional",
                    "workspace",
                    "operational-workspace",
                    List.of("praxis-filter", "praxis-list", "praxis-table", "praxis-dynamic-form", "praxis-rich-content"),
                    List.of("selection.context", "surface.open", "workflow.start"),
                    List.of("list", "detail", "action", "workflow"),
                    "Criar workspace operacional com selecao, detalhe, actions e workflows disponiveis.",
                    "high"));
        }
        return List.copyOf(routes);
    }

    private String routeConfidence(
            List<Domain360Entry> fields,
            List<Domain360Entry> stats,
            List<Domain360Entry> optionSources) {
        if (!fields.isEmpty() && !stats.isEmpty() && !optionSources.isEmpty()) {
            return "high";
        }
        if (!fields.isEmpty() && (!stats.isEmpty() || !optionSources.isEmpty())) {
            return "medium";
        }
        return "low";
    }

    private Domain360Entry toEntry(DomainCatalogItemResponse item) {
        JsonNode payload = item.payload();
        return new Domain360Entry(
                firstText(
                        text(payload, "nodeKey"),
                        text(payload, "surfaceKey"),
                        text(payload, "actionKey"),
                        text(payload, "workflowKey"),
                        text(payload, "optionSourceKey"),
                        text(payload, "capabilityKey"),
                        text(payload.path("contract"), "contractKey"),
                        text(payload.path("resolution"), "resolutionKey"),
                        item.itemKey()),
                firstText(
                        text(payload, "label"),
                        text(payload, "displayName"),
                        text(payload, "title"),
                        text(payload, "name"),
                        text(payload.path("contract"), "operationKey"),
                        item.itemKey()),
                entryKind(item),
                item.itemType(),
                firstText(item.contextKey(), text(payload, "contextKey"), text(payload.path("contract"), "providerContextKey")),
                firstText(text(payload, "sourceMode"), text(payload, "sourceKey"), item.releaseKey()),
                firstText(
                        text(payload, "description"),
                        text(payload, "summary"),
                        text(payload.path("businessGlossary"), "pt-BR"),
                        text(payload.path("contract"), "contractType"),
                        text(payload.path("resolution"), "resolutionType")),
                payload);
    }

    private String entryKind(DomainCatalogItemResponse item) {
        if (isResourceLike(item)) {
            return "resource";
        }
        JsonNode payload = item.payload();
        return firstText(
                text(payload, "nodeType"),
                text(payload, "contextType"),
                text(payload.path("contract"), "contractType"),
                item.nodeType(),
                item.bindingType(),
                item.edgeType(),
                item.itemType());
    }

    private Domain360Entry fallbackResource(String resourceKey, String contextKey) {
        return new Domain360Entry(
                normalize(resourceKey),
                labelFromKey(normalize(resourceKey)),
                "resource",
                "synthetic",
                normalize(contextKey),
                "domain-360.request",
                "Recurso principal sintetizado a partir do resourceKey solicitado.",
                null);
    }

    private Classification classify(DomainCatalogItemResponse item) {
        String haystack = normalizeText(String.join(" ",
                value(item.itemType()),
                value(item.itemKey()),
                value(item.contextKey()),
                value(item.nodeType()),
                value(item.bindingType()),
                value(item.edgeType()),
                text(item.payload(), "nodeType"),
                text(item.payload(), "contextType"),
                text(item.payload(), "capabilityKey"),
                text(item.payload(), "surfaceKey"),
                text(item.payload(), "actionKey"),
                text(item.payload(), "workflowKey"),
                text(item.payload(), "optionSourceKey"),
                text(item.payload(), "source"),
                item.payload().path("tags").toString(),
                item.payload().path("metadata").path("tags").toString(),
                text(item.payload().path("contract"), "contractType"),
                text(item.payload().path("contract"), "operationKey"),
                text(item.payload().path("contract"), "schemaRef"),
                text(item.payload().path("metadata"), "fieldName")));

        if (containsAny(haystack, "option source", "options", "lookup option", "lookup_option", "select multiple")) {
            return Classification.OPTION_SOURCE;
        }
        if (containsAny(haystack, "surface", "modal", "drawer", "detail page")) {
            return Classification.SURFACE;
        }
        if (containsAny(haystack, "workflow", "flow", "process")) {
            return Classification.WORKFLOW;
        }
        if (containsAny(haystack, "action", "command")) {
            return Classification.ACTION;
        }
        if (containsAny(haystack, "stats", "stat", "metric", "kpi", "analytics", "group by", "group-by", "measure")) {
            return Classification.STATS;
        }
        if (containsAny(haystack, "capability", "capabilities")) {
            return Classification.CAPABILITY;
        }
        if (containsAny(haystack, "api resource")) {
            return Classification.RESOURCE;
        }
        if (containsAny(haystack, "field", "property", "attribute") || StringUtils.hasText(text(item.payload().path("metadata"), "fieldName"))) {
            return Classification.FIELD;
        }
        if (isResourceLike(item)) {
            return Classification.RESOURCE;
        }
        return Classification.UNKNOWN;
    }

    private boolean isResourceLike(DomainCatalogItemResponse item) {
        String nodeType = normalizeText(firstText(item.nodeType(), text(item.payload(), "nodeType"), text(item.payload(), "contextType")));
        String source = normalizeText(text(item.payload(), "source"));
        return containsAny(nodeType, "resource", "entity", "aggregate", "context", "domain")
                || "api resource".equals(source);
    }

    private List<Domain360Entry> dedupe(List<Domain360Entry> entries) {
        Map<String, Domain360Entry> byKey = new LinkedHashMap<>();
        for (Domain360Entry entry : entries) {
            String key = firstText(entry.key(), entry.label(), entry.kind());
            byKey.putIfAbsent(key, entry);
        }
        return List.copyOf(byKey.values());
    }

    private List<DomainCatalogItemResponse> safe(List<DomainCatalogItemResponse> items) {
        return items == null ? List.of() : items;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.isContainerNode()) {
            return "";
        }
        return value.asText("");
    }

    private String firstText(String... values) {
        for (String candidate : values) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeText(String value) {
        return value(value)
                .replace('_', ' ')
                .replace('.', ' ')
                .replace('/', ' ')
                .replace('-', ' ')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String labelFromKey(String key) {
        String value = value(key);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) {
            value = value.substring(dot + 1);
        }
        String normalized = value.replace('-', ' ').replace('_', ' ').trim();
        if (normalized.isBlank()) {
            return "";
        }
        String[] parts = normalized.split("\\s+");
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                label.append(part.substring(1));
            }
        }
        return label.toString();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private enum Classification {
        RESOURCE,
        FIELD,
        CAPABILITY,
        SURFACE,
        ACTION,
        WORKFLOW,
        STATS,
        OPTION_SOURCE,
        UNKNOWN
    }
}
