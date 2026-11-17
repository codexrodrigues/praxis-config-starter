package org.praxisplatform.config.ai.agent.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordance;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordanceContributor;
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntitySearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;
import org.praxisplatform.config.ai.agent.spi.AgentRelatedDataset;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentSurfaceSummary;
import org.praxisplatform.config.ai.agent.spi.AgentTopicContributor;
import org.praxisplatform.config.ai.agent.spi.AgentTopicDescriptor;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest;
import org.praxisplatform.config.ai.agent.spi.DualProjectedPayload;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.capability.CapabilityOperation;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.CapabilitySnapshot;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.surface.RelatedResourceSurface;
import org.praxisplatform.uischema.surface.SurfaceCatalogItem;
import org.praxisplatform.uischema.surface.SurfaceCatalogResponse;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Ponte em processo sobre os controladores e servicos {@code @ApiResource} do host usando
 * {@code praxis-metadata-starter}.
 *
 * <p>Executa consultas e dossies em transacoes curtas {@code readOnly} por ferramenta, aplicando
 * {@link AgentAuthorizationBridge} para dupla projecao ({@code uiRecord} e {@code modelSafeRecord}),
 * conversao de chaves compostas via {@link ConversionService} e avaliacao sequencial de candidatos.
 */
@Slf4j
public class MetadataStarterInProcessAgentBridge implements AgentResourceExecutionBridge {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int MAX_DIGEST_FILTERS = 20;
    private static final int MAX_DIGEST_RESPONSE_FIELDS = 25;
    private static final int MAX_RELATED_DATASETS = 4;
    private static final int MAX_RELATED_ROWS = 5;

    private record RegisteredResource(
            AgentEntityDescriptor descriptor,
            AbstractResourceQueryController<?, ?, ?> controller,
            BaseResourceQueryService<?, ?, ?> service,
            Class<?> responseDtoType,
            Class<?> idType,
            Class<?> filterDtoType,
            List<FilterFieldMeta> filterFields,
            List<ResponseFieldMeta> responseFields
    ) {
    }

    private record FilterFieldMeta(
            String name,
            Class<?> javaType,
            String label,
            String description,
            Filterable.FilterOperation operation,
            List<String> relations,
            FieldControlType controlType,
            UISchemaPreset preset
    ) {
    }

    private record ResponseFieldMeta(
            String name,
            String label,
            String description,
            FieldControlType controlType,
            String aiVisibility,
            boolean quickViewVisible
    ) {
    }

    private final List<AbstractResourceQueryController<?, ?, ?>> controllers;
    private final List<AgentEntityCatalogContributor> entityContributors;
    private final List<AgentTopicContributor> topicContributors;
    private final List<AgentProactiveAlertContributor> alertContributors;
    private final List<AgentActionAffordanceContributor> actionContributors;
    private final CapabilityService capabilityService;
    private final ActionCatalogService actionCatalogService;
    private final SurfaceCatalogService surfaceCatalogService;
    private final AgentAuthorizationBridge authorizationBridge;
    private final ConversionService conversionService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readOnlyTxTemplate;

    private volatile List<RegisteredResource> cachedCatalog;

    public MetadataStarterInProcessAgentBridge(
            List<AbstractResourceQueryController<?, ?, ?>> controllers,
            List<AgentEntityCatalogContributor> entityContributors,
            List<AgentTopicContributor> topicContributors,
            List<AgentProactiveAlertContributor> alertContributors,
            CapabilityService capabilityService,
            ActionCatalogService actionCatalogService,
            SurfaceCatalogService surfaceCatalogService,
            AgentAuthorizationBridge authorizationBridge,
            ConversionService conversionService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                controllers,
                entityContributors,
                topicContributors,
                alertContributors,
                capabilityService,
                actionCatalogService,
                surfaceCatalogService,
                authorizationBridge,
                conversionService,
                objectMapper,
                transactionManager,
                List.of());
    }

    public MetadataStarterInProcessAgentBridge(
            List<AbstractResourceQueryController<?, ?, ?>> controllers,
            List<AgentEntityCatalogContributor> entityContributors,
            List<AgentTopicContributor> topicContributors,
            List<AgentProactiveAlertContributor> alertContributors,
            CapabilityService capabilityService,
            ActionCatalogService actionCatalogService,
            SurfaceCatalogService surfaceCatalogService,
            AgentAuthorizationBridge authorizationBridge,
            ConversionService conversionService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            List<AgentActionAffordanceContributor> actionContributors) {
        this.controllers = controllers == null ? List.of() : List.copyOf(controllers);
        this.entityContributors = entityContributors == null ? List.of() : List.copyOf(entityContributors);
        this.topicContributors = topicContributors == null ? List.of() : List.copyOf(topicContributors);
        this.alertContributors = alertContributors == null ? List.of() : List.copyOf(alertContributors);
        this.actionContributors = actionContributors == null ? List.of() : List.copyOf(actionContributors);
        this.capabilityService = capabilityService;
        this.actionCatalogService = actionCatalogService;
        this.surfaceCatalogService = surfaceCatalogService;
        this.authorizationBridge = authorizationBridge != null
                ? authorizationBridge
                : new DefaultMetadataGovernanceAgentAuthorizationBridge();
        this.conversionService = conversionService != null
                ? conversionService
                : DefaultConversionService.getSharedInstance();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setReadOnly(true);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            this.readOnlyTxTemplate = template;
        } else {
            this.readOnlyTxTemplate = null;
        }
    }

    @Override
    public List<AgentTopicDescriptor> listTopics(AgentExecutionContext context) {
        List<AgentTopicDescriptor> contributed = new ArrayList<>();
        for (AgentTopicContributor contributor : topicContributors) {
            List<AgentTopicDescriptor> items = contributor.contributeTopics(context);
            if (items != null) {
                contributed.addAll(items);
            }
        }
        if (!contributed.isEmpty()) {
            return contributed.stream()
                    .sorted(Comparator.comparingInt(AgentTopicDescriptor::priority).reversed()
                            .thenComparing(AgentTopicDescriptor::title, Comparator.nullsLast(String::compareToIgnoreCase)))
                    .toList();
        }
        return buildDefaultTopics(context);
    }

    @Override
    public List<AgentEntityDescriptor> listEntities(AgentExecutionContext context, String topicOrQuery) {
        List<RegisteredResource> catalog = resolveCatalog(context);
        List<AgentEntityDescriptor> authorized = catalog.stream()
                .map(RegisteredResource::descriptor)
                .filter(desc -> authorizationBridge.canReadResource(context, desc.resourcePath()))
                .toList();
        if (!StringUtils.hasText(topicOrQuery)) {
            return authorized;
        }
        String q = topicOrQuery.trim().toLowerCase(Locale.ROOT);
        Set<String> topicResourcePaths = new LinkedHashSet<>();
        String topicPrimaryEntity = null;
        for (AgentTopicDescriptor topic : listTopics(context)) {
            boolean matchesTopic = containsIgnoreCase(topic.topicKey(), q)
                    || containsIgnoreCase(topic.title(), q)
                    || containsIgnoreCase(topic.description(), q)
                    || topic.keywords().stream().anyMatch(kw -> containsIgnoreCase(kw, q) || containsIgnoreCase(q, kw));
            if (matchesTopic) {
                topicResourcePaths.addAll(topic.resourcePaths());
                if (StringUtils.hasText(topic.primaryEntityKey())) {
                    topicPrimaryEntity = topic.primaryEntityKey();
                }
            }
        }
        final String primaryEntity = topicPrimaryEntity;
        List<AgentEntityDescriptor> filtered = authorized.stream()
                .filter(desc -> topicResourcePaths.contains(desc.resourcePath())
                        || (primaryEntity != null && primaryEntity.equalsIgnoreCase(desc.entityKey()))
                        || containsIgnoreCase(desc.entityKey(), q)
                        || containsIgnoreCase(desc.resourceKey(), q)
                        || containsIgnoreCase(desc.title(), q)
                        || containsIgnoreCase(desc.description(), q))
                .toList();
        return filtered.isEmpty() ? authorized : filtered;
    }

    @Override
    public Optional<CompactAgentSchemaDigest> describeEntitySchema(
            AgentExecutionContext context,
            String entityKeyOrResourcePath) {
        Optional<RegisteredResource> found = findResource(context, entityKeyOrResourcePath);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RegisteredResource res = found.get();
        if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
            return Optional.empty();
        }
        return Optional.of(executeReadOnly(() -> buildSchemaDigest(context, res)));
    }

    @Override
    public List<AgentEntityMatch> searchEntities(
            AgentExecutionContext context,
            AgentEntitySearchRequest request) {
        if (request == null || !StringUtils.hasText(request.rawQuery())) {
            return List.of();
        }
        return executeReadOnly(() -> searchEntitiesInternal(context, request));
    }

    @Override
    public AgentQueryResult queryRecords(
            AgentExecutionContext context,
            String entityKeyOrResourcePath,
            Map<String, Object> filters,
            int page,
            int size,
            String sort) {
        RegisteredResource res = findResource(context, entityKeyOrResourcePath)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown entity/resource: " + entityKeyOrResourcePath));
        if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
            throw new SecurityException("Access denied to resource: " + res.descriptor().resourcePath());
        }
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(Math.max(1, size), 20);
        return executeReadOnly(() -> queryRecordsInternal(context, res, filters, boundedPage, boundedSize, sort));
    }

    @Override
    public Optional<AgentEntityDossier> loadEntityDossier(
            AgentExecutionContext context,
            String entityKeyOrResourcePath,
            String entityId,
            List<String> includeRelatedSurfaces) {
        if (!StringUtils.hasText(entityId)) {
            return Optional.empty();
        }
        Optional<RegisteredResource> found = findResource(context, entityKeyOrResourcePath);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RegisteredResource res = found.get();
        if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
            throw new SecurityException("Access denied to resource: " + res.descriptor().resourcePath());
        }
        return executeReadOnly(() -> loadEntityDossierInternal(context, res, entityId.trim(), includeRelatedSurfaces));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<AgentEntityMatch> searchEntitiesInternal(
            AgentExecutionContext context,
            AgentEntitySearchRequest request) {
        List<RegisteredResource> candidates = selectCandidateResources(context, request);
        List<ScoredMatch> rawMatches = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        for (RegisteredResource res : candidates) {
            if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
                continue;
            }
            boolean activeScreen = matchesActiveScreen(context, res.descriptor());
            List<ScoredRow> rows = request.validTaxIdMod11()
                    ? searchByTaxId(res, request.resolvedTaxId(), request.maxResultsPerEntity())
                    : searchPolymorphic(res, request.rawQuery().trim(), request.maxResultsPerEntity());
            for (ScoredRow scored : rows) {
                Object dto = scored.dto();
                String id = extractIdString(res, dto);
                String dedupKey = res.descriptor().entityKey() + "::" + id;
                if (!StringUtils.hasText(id) || !seenKeys.add(dedupKey)) {
                    continue;
                }
                Map<String, Object> raw = toMap(res, dto);
                DualProjectedPayload projected = authorizationBridge.projectRecord(
                        context,
                        res.descriptor().resourcePath(),
                        res.responseDtoType(),
                        raw);
                String title = extractDisplayField(projected.uiRecord(), res.descriptor().titleField(), id);
                String subtitle = extractDisplayField(projected.uiRecord(), res.descriptor().subtitleField(), "");
                double boost = activeScreen ? 0.05d : 0.0d;
                AgentEntityMatch match = new AgentEntityMatch(
                        res.descriptor().entityKey(),
                        res.descriptor().resourceKey(),
                        res.descriptor().resourcePath(),
                        res.descriptor().title(),
                        id,
                        title,
                        subtitle,
                        scored.matchedBy(),
                        Math.min(1.0d, scored.confidence() + boost),
                        buildSummaryMap(res.descriptor(), projected.uiRecord(), id),
                        buildSummaryMap(res.descriptor(), projected.modelSafeRecord(), id),
                        activeScreen);
                rawMatches.add(new ScoredMatch(match, res.descriptor()));
            }
        }

        List<AgentEntityMatch> allMatches = suppressRedundantParentMatches(rawMatches, request).stream()
                .map(ScoredMatch::match)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        allMatches.sort(Comparator.comparingDouble(AgentEntityMatch::confidence).reversed()
                .thenComparing(AgentEntityMatch::fromActiveScreenScope, Comparator.reverseOrder())
                .thenComparing(AgentEntityMatch::title, Comparator.nullsLast(String::compareToIgnoreCase)));
        return allMatches;
    }

    private static List<ScoredMatch> suppressRedundantParentMatches(
            List<ScoredMatch> matches,
            AgentEntitySearchRequest request) {
        if (matches.size() <= 1) {
            return matches;
        }
        Set<String> explicitlyRequested = new LinkedHashSet<>();
        if (request != null && request.candidateEntityKeys() != null) {
            for (String key : request.candidateEntityKeys()) {
                if (StringUtils.hasText(key)) {
                    explicitlyRequested.add(key.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> supersededKeys = new LinkedHashSet<>();
        for (ScoredMatch item : matches) {
            String supersedes = item.descriptor().supersedesEntityKey();
            if (StringUtils.hasText(supersedes)) {
                String normalized = supersedes.trim().toLowerCase(Locale.ROOT);
                if (!explicitlyRequested.contains(normalized)) {
                    supersededKeys.add(normalized);
                }
            }
        }
        if (supersededKeys.isEmpty()) {
            return matches;
        }
        List<ScoredMatch> remaining = new ArrayList<>(matches.size());
        for (ScoredMatch item : matches) {
            AgentEntityMatch m = item.match();
            String entityKey = m.entityKey() != null ? m.entityKey().trim().toLowerCase(Locale.ROOT) : "";
            String resourceKey = m.resourceKey() != null ? m.resourceKey().trim().toLowerCase(Locale.ROOT) : "";
            if (!supersededKeys.contains(entityKey) && !supersededKeys.contains(resourceKey)) {
                remaining.add(item);
            }
        }
        return remaining.isEmpty() ? matches : remaining;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<ScoredRow> searchByTaxId(RegisteredResource res, String taxDigits, int maxResults) {
        String filterField = res.descriptor().taxIdFilterField();
        if (!StringUtils.hasText(filterField)) {
            return List.of();
        }
        BaseResourceQueryService service = res.service();
        List<ScoredRow> results = new ArrayList<>();
        Page page = filterSingleField(res, service, filterField, taxDigits, maxResults);
        if (page == null || page.isEmpty()) {
            String formatted = formatTaxId(taxDigits);
            if (!formatted.equals(taxDigits)) {
                page = filterSingleField(res, service, filterField, formatted, maxResults);
            }
        }
        if (page != null) {
            for (Object item : page.getContent()) {
                results.add(new ScoredRow(item, "taxId:" + filterField, 0.99d));
            }
        }
        return results;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<ScoredRow> searchPolymorphic(RegisteredResource res, String query, int maxResults) {
        BaseResourceQueryService service = res.service();
        List<ScoredRow> results = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        Object convertedId = tryConvertId(query, res.idType());
        if (convertedId != null) {
            try {
                ResourceRepresentationResult<?> found = service.findById(convertedId);
                if (found != null && found.body() != null) {
                    String id = extractIdString(res, found.body());
                    if (seenIds.add(id)) {
                        results.add(new ScoredRow(found.body(), "exactId", 0.96d));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        List<FilterFieldMeta> candidateFields = rankSearchFields(res);
        for (FilterFieldMeta field : candidateFields) {
            if (results.size() >= maxResults) {
                break;
            }
            Object typedValue = convertFilterFieldValue(query, field.javaType());
            if (typedValue == null) {
                continue;
            }
            Page page = filterSingleField(res, service, field.name(), typedValue, maxResults);
            if (page == null || page.isEmpty()) {
                continue;
            }
            double score = field.operation() == Filterable.FilterOperation.EQUAL ? 0.90d : 0.78d;
            for (Object item : page.getContent()) {
                String id = extractIdString(res, item);
                if (seenIds.add(id)) {
                    results.add(new ScoredRow(item, "filter:" + field.name(), score));
                    if (results.size() >= maxResults) {
                        break;
                    }
                }
            }
        }
        return results;
    }

    private List<FilterFieldMeta> rankSearchFields(RegisteredResource res) {
        List<FilterFieldMeta> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addNamedFilterField(res, res.descriptor().identifierFilterField(), ordered, seen);
        addNamedFilterField(res, res.descriptor().nameFilterField(), ordered, seen);
        for (FilterFieldMeta meta : res.filterFields()) {
            if (!seen.contains(meta.name()) && meta.relations() != null && !meta.relations().isEmpty()) {
                seen.add(meta.name());
                ordered.add(meta);
            }
        }
        for (FilterFieldMeta meta : res.filterFields()) {
            if (!seen.contains(meta.name()) && String.class.equals(meta.javaType())) {
                seen.add(meta.name());
                ordered.add(meta);
                if (ordered.size() >= 4) {
                    break;
                }
            }
        }
        return ordered;
    }

    private void addNamedFilterField(
            RegisteredResource res,
            String fieldName,
            List<FilterFieldMeta> target,
            Set<String> seen) {
        if (!StringUtils.hasText(fieldName) || seen.contains(fieldName)) {
            return;
        }
        res.filterFields().stream()
                .filter(meta -> meta.name().equals(fieldName))
                .findFirst()
                .ifPresent(meta -> {
                    seen.add(meta.name());
                    target.add(meta);
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Page filterSingleField(
            RegisteredResource res,
            BaseResourceQueryService service,
            String fieldName,
            Object value,
            int maxResults) {
        GenericFilterDTO filter = instantiateFilter(res.filterDtoType());
        if (filter == null) {
            return Page.empty();
        }
        try {
            BeanWrapperImpl wrapper = new BeanWrapperImpl(filter);
            if (!wrapper.isWritableProperty(fieldName)) {
                return Page.empty();
            }
            Object converted = convertFilterFieldValue(value, wrapper.getPropertyType(fieldName));
            if (converted == null) {
                return Page.empty();
            }
            wrapper.setPropertyValue(fieldName, converted);
            Sort defaultSort = service.getDefaultSort() != null ? service.getDefaultSort() : Sort.unsorted();
            return service.filter(filter, PageRequest.of(0, maxResults, defaultSort), null);
        } catch (Exception ex) {
            return Page.empty();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AgentQueryResult queryRecordsInternal(
            AgentExecutionContext context,
            RegisteredResource res,
            Map<String, Object> filters,
            int page,
            int size,
            String sort) {
        BaseResourceQueryService service = res.service();
        GenericFilterDTO filterDto = instantiateFilter(res.filterDtoType());
        if (filterDto != null && filters != null && !filters.isEmpty()) {
            BeanWrapperImpl wrapper = new BeanWrapperImpl(filterDto);
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                if (wrapper.isWritableProperty(entry.getKey())) {
                    Class<?> propType = wrapper.getPropertyType(entry.getKey());
                    Object converted = convertFilterFieldValue(entry.getValue(), propType);
                    if (converted != null) {
                        wrapper.setPropertyValue(entry.getKey(), converted);
                    }
                }
            }
        }
        Sort resolvedSort = parseSort(sort, service.getDefaultSort());
        Page<?> resultPage = service.filter(filterDto, PageRequest.of(page, size, resolvedSort), null);
        List<Map<String, Object>> uiRecords = new ArrayList<>();
        List<Map<String, Object>> modelSafeRecords = new ArrayList<>();
        for (Object dto : resultPage.getContent()) {
            DualProjectedPayload projected = authorizationBridge.projectRecord(
                    context,
                    res.descriptor().resourcePath(),
                    res.responseDtoType(),
                    toMap(res, dto));
            uiRecords.add(projected.uiRecord());
            modelSafeRecords.add(projected.modelSafeRecord());
        }
        List<AgentActionAffordance> collectionActions = resolveCollectionActions(context, res);
        return new AgentQueryResult(
                res.descriptor().entityKey(),
                res.descriptor().resourceKey(),
                res.descriptor().resourcePath(),
                res.descriptor().title(),
                resultPage.getTotalElements(),
                resultPage.getNumber(),
                resultPage.getSize(),
                uiRecords,
                modelSafeRecords,
                collectionActions);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Optional<AgentEntityDossier> loadEntityDossierInternal(
            AgentExecutionContext context,
            RegisteredResource res,
            String entityId,
            List<String> includeRelatedSurfaces) {
        Object convertedId = tryConvertId(entityId, res.idType());
        if (convertedId == null) {
            return Optional.empty();
        }
        BaseResourceQueryService service = res.service();
        ResourceRepresentationResult<?> representation;
        try {
            representation = service.findById(convertedId);
        } catch (Exception ex) {
            return Optional.empty();
        }
        if (representation == null || representation.body() == null) {
            return Optional.empty();
        }
        Object dto = representation.body();
        DualProjectedPayload projected = authorizationBridge.projectRecord(
                context,
                res.descriptor().resourcePath(),
                res.responseDtoType(),
                toMap(res, dto));
        String canonicalId = extractIdString(res, dto);
        if (!StringUtils.hasText(canonicalId)) {
            canonicalId = entityId;
        }
        String displayTitle = extractDisplayField(projected.uiRecord(), res.descriptor().titleField(), canonicalId);
        String displaySubtitle = extractDisplayField(projected.uiRecord(), res.descriptor().subtitleField(), "");

        List<AgentActionAffordance> actions = resolveItemActions(
                context, res, convertedId, canonicalId, projected.uiRecord());
        List<AgentSurfaceSummary> surfaces = resolveItemSurfaces(context, res, convertedId, canonicalId);
        List<AgentRelatedDataset> relatedDatasets = loadRelatedDatasets(
                context, res, canonicalId, surfaces, includeRelatedSurfaces);
        List<AgentProactiveAlert> alerts = evaluateAlerts(context, res.descriptor().entityKey(), canonicalId, projected.uiRecord());

        return Optional.of(new AgentEntityDossier(
                res.descriptor(),
                canonicalId,
                displayTitle,
                displaySubtitle,
                projected.uiRecord(),
                projected.modelSafeRecord(),
                projected.hiddenFields(),
                projected.maskedForAiFields(),
                relatedDatasets,
                alerts,
                actions,
                surfaces));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<AgentRelatedDataset> loadRelatedDatasets(
            AgentExecutionContext context,
            RegisteredResource parent,
            String parentEntityId,
            List<AgentSurfaceSummary> surfaces,
            List<String> includeRelatedSurfaces) {
        List<AgentRelatedDataset> datasets = new ArrayList<>();
        Set<String> requestedSurfaces = includeRelatedSurfaces == null
                ? Set.of()
                : includeRelatedSurfaces.stream()
                        .filter(StringUtils::hasText)
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (AgentSurfaceSummary surface : surfaces) {
            if (datasets.size() >= MAX_RELATED_DATASETS) {
                break;
            }
            if (!surface.available() || !StringUtils.hasText(surface.childResourcePath())) {
                continue;
            }
            if (!requestedSurfaces.isEmpty()
                    && !requestedSurfaces.contains(surface.surfaceId().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Optional<RegisteredResource> childOpt = findResource(context, surface.childResourcePath());
            if (childOpt.isEmpty() && StringUtils.hasText(surface.childResourceKey())) {
                childOpt = findResource(context, surface.childResourceKey());
            }
            if (childOpt.isEmpty()) {
                continue;
            }
            RegisteredResource child = childOpt.get();
            if (!authorizationBridge.canReadResource(context, child.descriptor().resourcePath())) {
                continue;
            }
            String parentField = surface.childParentField();
            if (!StringUtils.hasText(parentField)) {
                continue;
            }
            Page<?> childPage = filterSingleField(child, child.service(), parentField, parentEntityId, MAX_RELATED_ROWS);
            if (childPage == null) {
                continue;
            }
            List<Map<String, Object>> uiRows = new ArrayList<>();
            List<Map<String, Object>> modelSafeRows = new ArrayList<>();
            for (Object row : childPage.getContent()) {
                DualProjectedPayload projectedRow = authorizationBridge.projectRecord(
                        context,
                        child.descriptor().resourcePath(),
                        child.responseDtoType(),
                        toMap(child, row));
                uiRows.add(projectedRow.uiRecord());
                modelSafeRows.add(projectedRow.modelSafeRecord());
            }
            datasets.add(new AgentRelatedDataset(
                    surface.surfaceId(),
                    surface.title(),
                    child.descriptor().resourceKey(),
                    child.descriptor().resourcePath(),
                    parentField,
                    childPage.getTotalElements(),
                    uiRows,
                    modelSafeRows));
        }
        return datasets;
    }

    private List<AgentProactiveAlert> evaluateAlerts(
            AgentExecutionContext context,
            String entityKey,
            String entityId,
            Map<String, Object> authoritativeRecord) {
        List<AgentProactiveAlert> alerts = new ArrayList<>();
        for (AgentProactiveAlertContributor contributor : alertContributors) {
            if (contributor.supportsEntity(entityKey)) {
                List<AgentProactiveAlert> found = contributor.evaluateAlerts(
                        context, entityKey, entityId, authoritativeRecord);
                if (found != null) {
                    alerts.addAll(found);
                }
            }
        }
        return alerts;
    }

    private List<AgentActionAffordance> resolveItemActions(
            AgentExecutionContext context,
            RegisteredResource res,
            Object convertedId,
            String canonicalId,
            Map<String, Object> uiRecord) {
        List<AgentActionAffordance> actions = new ArrayList<>();
        String resourceKey = res.descriptor().resourceKey();
        String resourcePath = res.descriptor().resourcePath();
        if (actionCatalogService != null && StringUtils.hasText(resourceKey)) {
            ActionCatalogResponse response = tryMetadataLookup("findItemActions:" + resourceKey,
                    () -> actionCatalogService.findItemActions(resourceKey, convertedId));
            if (response != null && response.actions() != null) {
                ResourceStateSnapshot stateSnapshot = resolveItemStateSnapshot(res, convertedId);
                for (ActionCatalogItem item : response.actions()) {
                    if (isActionBlockedByStateOrRule(item, stateSnapshot)) {
                        continue;
                    }
                    boolean missingAuthority = item.availability() != null
                            && !item.availability().allowed()
                            && "missing-authority".equalsIgnoreCase(item.availability().reason());
                    boolean authorized = !missingAuthority
                            && authorizationBridge.canInvokeAction(
                                    context, resourcePath, item.id(), convertedId);
                    String submitPath = resolvePathId(item.path(), resourcePath, canonicalId);
                    boolean hasForm = StringUtils.hasText(item.requestSchemaUrl());
                    actions.add(new AgentActionAffordance(
                            item.id(),
                            firstNonBlank(item.title(), item.id()),
                            item.description(),
                            "bolt",
                            "primary",
                            resourceKey,
                            resourcePath,
                            canonicalId,
                            "workflow-action",
                            hasForm ? "dialog" : "confirm-only",
                            firstNonBlank(item.method(), "POST"),
                            submitPath,
                            item.requestSchemaUrl(),
                            null,
                            Map.of(),
                            Map.of(),
                            item.scope() == ActionScope.ITEM,
                            !authorized,
                            !authorized ? "Permissao insuficiente" : null,
                            item.order()));
                }
            }
        }
        if (capabilityService != null && StringUtils.hasText(resourceKey)) {
            CapabilitySnapshot cap = tryMetadataLookup("itemCapabilities:" + resourceKey,
                    () -> capabilityService.itemCapabilities(resourceKey, resourcePath, convertedId));
            if (cap != null && cap.operations() != null) {
                CapabilityOperation updateOp = cap.operations().get("update");
                if (updateOp != null && updateOp.supported()) {
                    boolean allowed = updateOp.availability() == null || updateOp.availability().allowed();
                    if (allowed && authorizationBridge.canInvokeAction(context, resourcePath, "update", convertedId)) {
                        actions.add(new AgentActionAffordance(
                                "edit",
                                "Editar registro",
                                "Abre o formulario de edicao governado deste registro",
                                "edit",
                                "neutral",
                                resourceKey,
                                resourcePath,
                                canonicalId,
                                "edit",
                                "drawer",
                                firstNonBlank(updateOp.preferredMethod(), "PUT"),
                                resourcePath + "/" + canonicalId,
                                "/schemas/filtered?path=" + resourcePath + "/" + canonicalId + "&schemaType=request",
                                null,
                                Map.of(),
                                Map.of(),
                                false,
                                false,
                                null,
                                900));
                    }
                }
            }
        }
        if (StringUtils.hasText(res.descriptor().routeTemplate())) {
            String resolvedRoute = res.descriptor().routeTemplate().replace("{id}", canonicalId);
            actions.add(new AgentActionAffordance(
                    "open-route",
                    "Abrir ficha completa",
                    "Navega para a tela completa deste registro",
                    "open_in_new",
                    "neutral",
                    resourceKey,
                    resourcePath,
                    canonicalId,
                    "route",
                    "route",
                    "GET",
                    null,
                    null,
                    resolvedRoute,
                    Map.of(),
                    Map.of(),
                    false,
                    false,
                    null,
                    950));
        }
        actions = applyActionContributors(context, res.descriptor(), convertedId, canonicalId, uiRecord, actions);
        actions.sort(Comparator.comparingInt(AgentActionAffordance::order)
                .thenComparing(AgentActionAffordance::label, Comparator.nullsLast(String::compareToIgnoreCase)));
        return actions;
    }

    private List<AgentActionAffordance> resolveCollectionActions(
            AgentExecutionContext context,
            RegisteredResource res) {
        List<AgentActionAffordance> actions = new ArrayList<>();
        String resourceKey = res.descriptor().resourceKey();
        String resourcePath = res.descriptor().resourcePath();
        if (actionCatalogService != null && StringUtils.hasText(resourceKey)) {
            ActionCatalogResponse response = tryMetadataLookup("findCollectionActions:" + resourceKey,
                    () -> actionCatalogService.findCollectionActions(resourceKey));
            if (response != null && response.actions() != null) {
                for (ActionCatalogItem item : response.actions()) {
                    if (isActionBlockedByStateOrRule(item, null)) {
                        continue;
                    }
                    boolean missingAuthority = item.availability() != null
                            && !item.availability().allowed()
                            && "missing-authority".equalsIgnoreCase(item.availability().reason());
                    boolean authorized = !missingAuthority
                            && authorizationBridge.canInvokeAction(context, resourcePath, item.id(), null);
                    actions.add(new AgentActionAffordance(
                            item.id(),
                            firstNonBlank(item.title(), item.id()),
                            item.description(),
                            "playlist_play",
                            "accent",
                            resourceKey,
                            resourcePath,
                            null,
                            "workflow-action",
                            StringUtils.hasText(item.requestSchemaUrl()) ? "dialog" : "confirm-only",
                            firstNonBlank(item.method(), "POST"),
                            item.path(),
                            item.requestSchemaUrl(),
                            null,
                            Map.of(),
                            Map.of(),
                            true,
                            !authorized,
                            !authorized ? "Permissao insuficiente" : null,
                            item.order()));
                }
            }
        }
        actions = applyActionContributors(context, res.descriptor(), null, null, Map.of(), actions);
        actions.sort(Comparator.comparingInt(AgentActionAffordance::order)
                .thenComparing(AgentActionAffordance::label, Comparator.nullsLast(String::compareToIgnoreCase)));
        return actions;
    }

    private List<AgentActionAffordance> applyActionContributors(
            AgentExecutionContext context,
            AgentEntityDescriptor descriptor,
            Object convertedId,
            String entityId,
            Map<String, Object> uiRecord,
            List<AgentActionAffordance> current) {
        List<AgentActionAffordance> working = new ArrayList<>(current);
        if (actionContributors.isEmpty()) {
            return working;
        }
        Map<String, Object> safeRecord = uiRecord != null ? uiRecord : Map.of();
        for (AgentActionAffordanceContributor contributor : actionContributors) {
            try {
                List<AgentActionAffordance> updated = contributor.contributeActions(
                        context, descriptor, convertedId, entityId, safeRecord, List.copyOf(working));
                if (updated != null) {
                    working = new ArrayList<>(updated);
                }
            } catch (Exception ex) {
                log.debug("Ignorando falha em AgentActionAffordanceContributor {}: {}",
                        contributor.getClass().getSimpleName(), ex.getMessage());
            }
        }
        return working;
    }

    private List<AgentSurfaceSummary> resolveItemSurfaces(
            AgentExecutionContext context,
            RegisteredResource res,
            Object convertedId,
            String canonicalId) {
        if (surfaceCatalogService == null || !StringUtils.hasText(res.descriptor().resourceKey())) {
            return List.of();
        }
        String resourceKey = res.descriptor().resourceKey();
        SurfaceCatalogResponse response = tryMetadataLookup("findItemSurfaces:" + resourceKey,
                () -> surfaceCatalogService.findItemSurfaces(resourceKey, convertedId));
        if (response == null || response.surfaces() == null) {
            return List.of();
        }
        List<AgentSurfaceSummary> summaries = new ArrayList<>();
        for (SurfaceCatalogItem item : response.surfaces()) {
            RelatedResourceSurface related = item.relatedResource();
            boolean available = item.availability() == null || item.availability().allowed();
            String contextualPath = item.contextualReadPath() != null
                    ? item.contextualReadPath().replace("{id}", canonicalId)
                    : null;
            summaries.add(new AgentSurfaceSummary(
                    item.id(),
                    firstNonBlank(item.title(), item.id()),
                    item.description(),
                    item.kind() != null ? item.kind().name() : null,
                    item.scope() != null ? item.scope().name() : null,
                    contextualPath,
                    related != null ? related.childResourceKey() : null,
                    related != null ? related.childResourcePath() : null,
                    related != null ? related.childParentField() : null,
                    available,
                    item.availability() != null ? item.availability().reason() : null,
                    item.order()));
        }
        summaries.sort(Comparator.comparingInt(AgentSurfaceSummary::order));
        return summaries;
    }

    private CompactAgentSchemaDigest buildSchemaDigest(AgentExecutionContext context, RegisteredResource res) {
        List<CompactAgentSchemaDigest.FilterFieldDigest> filters = res.filterFields().stream()
                .limit(MAX_DIGEST_FILTERS)
                .map(f -> new CompactAgentSchemaDigest.FilterFieldDigest(
                        f.name(), f.label(), f.description(), f.operation().name(), f.relations()))
                .toList();
        List<CompactAgentSchemaDigest.ResponseFieldDigest> fields = res.responseFields().stream()
                .limit(MAX_DIGEST_RESPONSE_FIELDS)
                .map(f -> new CompactAgentSchemaDigest.ResponseFieldDigest(
                        f.name(), f.label(), f.description(),
                        f.controlType() != null ? f.controlType().getValue() : null,
                        f.aiVisibility(),
                        f.quickViewVisible()))
                .toList();
        List<AgentActionAffordance> collectionActions = resolveCollectionActions(context, res);
        List<AgentSurfaceSummary> resourceSurfaces = List.of();
        if (surfaceCatalogService != null && StringUtils.hasText(res.descriptor().resourceKey())) {
            SurfaceCatalogResponse surf = tryMetadataLookup("findByResourceKey:" + res.descriptor().resourceKey(),
                    () -> surfaceCatalogService.findByResourceKey(res.descriptor().resourceKey()));
            if (surf != null && surf.surfaces() != null) {
                resourceSurfaces = surf.surfaces().stream()
                        .map(s -> new AgentSurfaceSummary(
                                s.id(),
                                firstNonBlank(s.title(), s.id()),
                                s.description(),
                                s.kind() != null ? s.kind().name() : null,
                                s.scope() != null ? s.scope().name() : null,
                                s.contextualReadPath(),
                                s.relatedResource() != null ? s.relatedResource().childResourceKey() : null,
                                s.relatedResource() != null ? s.relatedResource().childResourcePath() : null,
                                s.relatedResource() != null ? s.relatedResource().childParentField() : null,
                                s.availability() == null || s.availability().allowed(),
                                s.availability() != null ? s.availability().reason() : null,
                                s.order()))
                        .toList();
            }
        }
        AgentEntityDescriptor d = res.descriptor();
        return new CompactAgentSchemaDigest(
                d.entityKey(),
                d.resourceKey(),
                d.resourcePath(),
                d.title(),
                d.description(),
                d.idFieldName(),
                d.titleField(),
                d.subtitleField(),
                d.metadataFields(),
                filters,
                fields,
                collectionActions,
                resourceSurfaces);
    }

    private List<RegisteredResource> selectCandidateResources(
            AgentExecutionContext context,
            AgentEntitySearchRequest request) {
        List<RegisteredResource> catalog = resolveCatalog(context);
        List<RegisteredResource> filtered = catalog;
        if (!request.candidateEntityKeys().isEmpty()) {
            Set<String> requested = request.candidateEntityKeys().stream()
                    .filter(StringUtils::hasText)
                    .map(s -> s.trim().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<RegisteredResource> restricted = catalog.stream()
                    .filter(res -> requested.contains(res.descriptor().entityKey().toLowerCase(Locale.ROOT))
                            || requested.contains(res.descriptor().resourceKey().toLowerCase(Locale.ROOT))
                            || requested.contains(res.descriptor().resourcePath().toLowerCase(Locale.ROOT)))
                    .toList();
            if (!restricted.isEmpty()) {
                filtered = restricted;
            }
        }
        if (request.validTaxIdMod11()) {
            List<RegisteredResource> taxCapable = filtered.stream()
                    .filter(res -> StringUtils.hasText(res.descriptor().taxIdFilterField()))
                    .toList();
            if (!taxCapable.isEmpty()) {
                filtered = taxCapable;
            }
        } else if (request.candidateEntityKeys().isEmpty()) {
            List<RegisteredResource> globalScoped = filtered.stream()
                    .filter(res -> res.descriptor().includeInGlobalPolymorphicSearch()
                            || matchesActiveScreen(context, res.descriptor()))
                    .toList();
            if (!globalScoped.isEmpty()) {
                filtered = globalScoped;
            }
        }
        List<RegisteredResource> sorted = new ArrayList<>(filtered);
        sorted.sort(Comparator
                .comparing((RegisteredResource r) -> matchesActiveScreen(context, r.descriptor()), Comparator.reverseOrder())
                .thenComparingInt((RegisteredResource r) -> r.descriptor().matchPriority()).reversed());
        return sorted;
    }

    private Optional<RegisteredResource> findResource(
            AgentExecutionContext context,
            String entityKeyOrResourcePath) {
        if (!StringUtils.hasText(entityKeyOrResourcePath)) {
            return Optional.empty();
        }
        String key = entityKeyOrResourcePath.trim();
        List<RegisteredResource> catalog = resolveCatalog(context);
        for (RegisteredResource res : catalog) {
            AgentEntityDescriptor d = res.descriptor();
            if (key.equalsIgnoreCase(d.entityKey())
                    || key.equalsIgnoreCase(d.resourceKey())
                    || key.equalsIgnoreCase(d.resourcePath())) {
                return Optional.of(res);
            }
        }
        return Optional.empty();
    }

    private List<RegisteredResource> resolveCatalog(AgentExecutionContext context) {
        List<RegisteredResource> base = cachedCatalog;
        if (base == null) {
            base = introspectControllers();
            cachedCatalog = base;
        }
        if (entityContributors.isEmpty()) {
            return base;
        }
        Map<String, AgentEntityDescriptor> overridesByKey = new LinkedHashMap<>();
        for (AgentEntityCatalogContributor contributor : entityContributors) {
            List<AgentEntityDescriptor> items = contributor.contributeEntities(context);
            if (items != null) {
                for (AgentEntityDescriptor item : items) {
                    if (StringUtils.hasText(item.resourcePath())) {
                        overridesByKey.put(item.resourcePath().trim(), item);
                    }
                    if (StringUtils.hasText(item.entityKey())) {
                        overridesByKey.put(item.entityKey().trim().toLowerCase(Locale.ROOT), item);
                    }
                }
            }
        }
        if (overridesByKey.isEmpty()) {
            return base;
        }
        List<RegisteredResource> merged = new ArrayList<>(base.size());
        for (RegisteredResource res : base) {
            AgentEntityDescriptor override = overridesByKey.get(res.descriptor().resourcePath());
            if (override == null && StringUtils.hasText(res.descriptor().entityKey())) {
                override = overridesByKey.get(res.descriptor().entityKey().toLowerCase(Locale.ROOT));
            }
            if (override == null) {
                merged.add(new RegisteredResource(
                        withoutGlobalPolymorphicSearch(res.descriptor()),
                        res.controller(),
                        res.service(),
                        res.responseDtoType(),
                        res.idType(),
                        res.filterDtoType(),
                        res.filterFields(),
                        res.responseFields()));
            } else {
                merged.add(new RegisteredResource(
                        mergeDescriptors(res.descriptor(), override),
                        res.controller(),
                        res.service(),
                        res.responseDtoType(),
                        res.idType(),
                        res.filterDtoType(),
                        res.filterFields(),
                        res.responseFields()));
            }
        }
        return merged;
    }

    private List<RegisteredResource> introspectControllers() {
        List<RegisteredResource> list = new ArrayList<>();
        for (AbstractResourceQueryController<?, ?, ?> controller : controllers) {
            BaseResourceQueryService<?, ?, ?> service = invokeProtected(controller, "getService", BaseResourceQueryService.class);
            if (service == null) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(controller);
            ResolvableType resolved = ResolvableType.forClass(targetClass).as(AbstractResourceQueryController.class);
            Class<?> responseDtoType = resolved.getGeneric(0).resolve(Object.class);
            Class<?> idType = resolved.getGeneric(1).resolve(Object.class);
            CompositeIdSupport.registerCompositeIdType(idType);
            Class<?> filterDtoType = resolved.getGeneric(2).resolve(GenericFilterDTO.class);

            ApiResource apiResource = AnnotatedElementUtils.findMergedAnnotation(targetClass, ApiResource.class);
            RequestMapping reqMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);

            String basePath = invokeProtected(controller, "getBasePath", String.class);
            if (!StringUtils.hasText(basePath) && apiResource != null && apiResource.value().length > 0) {
                basePath = apiResource.value()[0];
            }
            if (!StringUtils.hasText(basePath) && apiResource != null && apiResource.path().length > 0) {
                basePath = apiResource.path()[0];
            }
            if (!StringUtils.hasText(basePath) && reqMapping != null && reqMapping.path().length > 0) {
                basePath = reqMapping.path()[0];
            }
            if (!StringUtils.hasText(basePath)) {
                continue;
            }
            String resourceKey = invokeProtected(controller, "getResourceKeyOrNull", String.class);
            if (!StringUtils.hasText(resourceKey) && apiResource != null && StringUtils.hasText(apiResource.resourceKey())) {
                resourceKey = apiResource.resourceKey().trim();
            }
            String entityKey = StringUtils.hasText(resourceKey)
                    ? resourceKey
                    : deriveEntityKey(basePath);
            String idFieldName = invokeProtected(controller, "getIdFieldName", String.class);
            if (!StringUtils.hasText(idFieldName)) {
                idFieldName = service.getIdFieldName();
            }
            ResourceIdentity identity = apiResource != null ? apiResource.identity() : null;
            if (identity == null || !StringUtils.hasText(identity.titleField())) {
                ResourceIdentity dtoIdentity = AnnotatedElementUtils.findMergedAnnotation(responseDtoType, ResourceIdentity.class);
                if (dtoIdentity != null) {
                    identity = dtoIdentity;
                }
            }
            String titleField = identity != null && StringUtils.hasText(identity.titleField())
                    ? identity.titleField()
                    : inferTitleField(responseDtoType);
            String subtitleField = identity != null ? trimToNull(identity.subtitleField()) : null;
            List<String> metadataFields = identity != null && identity.metadataFields() != null
                    ? Arrays.asList(identity.metadataFields())
                    : List.of();

            List<FilterFieldMeta> filterFields = introspectFilterFields(filterDtoType);
            List<ResponseFieldMeta> responseFields = introspectResponseFields(responseDtoType);

            String taxIdFilterField = inferTaxIdFilterField(filterFields);
            String nameFilterField = inferNameFilterField(filterFields);
            String identifierFilterField = inferIdentifierFilterField(filterFields);

            String title = apiResource != null && StringUtils.hasText(apiResource.title())
                    ? apiResource.title()
                    : targetClass.getSimpleName().replace("Controller", "");
            String description = apiResource != null ? apiResource.description() : "";
            String icon = apiResource != null && StringUtils.hasText(apiResource.icon())
                    ? apiResource.icon()
                    : "dataset";

            AgentEntityDescriptor descriptor = new AgentEntityDescriptor(
                    entityKey,
                    resourceKey,
                    basePath,
                    title,
                    description,
                    icon,
                    firstNonBlank(idFieldName, "id"),
                    titleField,
                    subtitleField,
                    metadataFields,
                    taxIdFilterField,
                    nameFilterField,
                    identifierFilterField,
                    null,
                    null,
                    10);
            list.add(new RegisteredResource(
                    descriptor,
                    controller,
                    service,
                    responseDtoType,
                    idType,
                    filterDtoType,
                    filterFields,
                    responseFields));
        }
        return List.copyOf(list);
    }

    private List<AgentTopicDescriptor> buildDefaultTopics(AgentExecutionContext context) {
        List<AgentEntityDescriptor> entities = resolveCatalog(context).stream()
                .map(RegisteredResource::descriptor)
                .toList();
        if (entities.isEmpty()) {
            return List.of();
        }
        List<AgentTopicDescriptor> topics = new ArrayList<>();
        for (AgentEntityDescriptor entity : entities) {
            topics.add(new AgentTopicDescriptor(
                    entity.entityKey(),
                    entity.title(),
                    entity.description(),
                    entity.entityKey(),
                    List.of(entity.resourcePath()),
                    List.of(entity.title(), entity.entityKey()),
                    null,
                    entity.matchPriority()));
        }
        return topics;
    }

    private static AgentEntityDescriptor mergeDescriptors(AgentEntityDescriptor base, AgentEntityDescriptor over) {
        return new AgentEntityDescriptor(
                firstNonBlank(over.entityKey(), base.entityKey()),
                firstNonBlank(over.resourceKey(), base.resourceKey()),
                firstNonBlank(over.resourcePath(), base.resourcePath()),
                firstNonBlank(over.title(), base.title()),
                firstNonBlank(over.description(), base.description()),
                firstNonBlank(over.icon(), base.icon()),
                firstNonBlank(over.idFieldName(), base.idFieldName()),
                firstNonBlank(over.titleField(), base.titleField()),
                firstNonBlank(over.subtitleField(), base.subtitleField()),
                over.metadataFields().isEmpty() ? base.metadataFields() : over.metadataFields(),
                firstNonBlank(over.taxIdFilterField(), base.taxIdFilterField()),
                firstNonBlank(over.nameFilterField(), base.nameFilterField()),
                firstNonBlank(over.identifierFilterField(), base.identifierFilterField()),
                firstNonBlank(over.routeTemplate(), base.routeTemplate()),
                firstNonBlank(over.primaryDomain(), base.primaryDomain()),
                over.matchPriority() != 0 ? over.matchPriority() : base.matchPriority(),
                firstNonBlank(over.supersedesEntityKey(), base.supersedesEntityKey()),
                over.includeInGlobalPolymorphicSearch());
    }

    private static AgentEntityDescriptor withoutGlobalPolymorphicSearch(AgentEntityDescriptor base) {
        if (base == null || !base.includeInGlobalPolymorphicSearch()) {
            return base;
        }
        return new AgentEntityDescriptor(
                base.entityKey(),
                base.resourceKey(),
                base.resourcePath(),
                base.title(),
                base.description(),
                base.icon(),
                base.idFieldName(),
                base.titleField(),
                base.subtitleField(),
                base.metadataFields(),
                base.taxIdFilterField(),
                base.nameFilterField(),
                base.identifierFilterField(),
                base.routeTemplate(),
                base.primaryDomain(),
                base.matchPriority(),
                base.supersedesEntityKey(),
                false);
    }

    private static List<FilterFieldMeta> introspectFilterFields(Class<?> filterType) {
        if (filterType == null || Object.class.equals(filterType) || filterType.isInterface()) {
            return List.of();
        }
        List<FilterFieldMeta> list = new ArrayList<>();
        Class<?> current = filterType;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                Filterable filterable = AnnotatedElementUtils.findMergedAnnotation(field, Filterable.class);
                UISchema ui = AnnotatedElementUtils.findMergedAnnotation(field, UISchema.class);
                if (filterable == null && ui == null) {
                    continue;
                }
                Filterable.FilterOperation op = filterable != null
                        ? filterable.operation()
                        : Filterable.FilterOperation.EQUAL;
                List<String> rels = filterable != null && filterable.relations() != null
                        ? Arrays.asList(filterable.relations())
                        : (filterable != null && StringUtils.hasText(filterable.relation())
                                ? List.of(filterable.relation())
                                : List.of());
                list.add(new FilterFieldMeta(
                        field.getName(),
                        field.getType(),
                        ui != null && StringUtils.hasText(ui.label()) ? ui.label() : field.getName(),
                        ui != null ? ui.description() : "",
                        op,
                        rels,
                        ui != null ? ui.controlType() : FieldControlType.AUTO,
                        ui != null ? ui.preset() : UISchemaPreset.NONE));
            }
            current = current.getSuperclass();
        }
        return list;
    }

    private static List<ResponseFieldMeta> introspectResponseFields(Class<?> dtoType) {
        if (dtoType == null || Object.class.equals(dtoType)) {
            return List.of();
        }
        List<ResponseFieldMeta> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (dtoType.isRecord()) {
            for (RecordComponent component : dtoType.getRecordComponents()) {
                UISchema ui = component.getAnnotation(UISchema.class);
                DomainGovernance gov = component.getAnnotation(DomainGovernance.class);
                seen.add(component.getName());
                list.add(toResponseFieldMeta(component.getName(), ui, gov));
            }
        }
        Class<?> current = dtoType;
        while (current != null && !Object.class.equals(current)) {
            for (Field field : current.getDeclaredFields()) {
                if (!seen.add(field.getName())) {
                    continue;
                }
                UISchema ui = AnnotatedElementUtils.findMergedAnnotation(field, UISchema.class);
                DomainGovernance gov = AnnotatedElementUtils.findMergedAnnotation(field, DomainGovernance.class);
                list.add(toResponseFieldMeta(field.getName(), ui, gov));
            }
            current = current.getSuperclass();
        }
        return list;
    }

    private static ResponseFieldMeta toResponseFieldMeta(
            String name, UISchema ui, DomainGovernance gov) {
        String label = ui != null && StringUtils.hasText(ui.label()) ? ui.label() : name;
        String desc = ui != null ? ui.description() : "";
        FieldControlType ctrl = ui != null ? ui.controlType() : FieldControlType.AUTO;
        String aiVis = gov != null && gov.aiUsage() != null
                ? gov.aiUsage().visibility().wireValue()
                : "allow";
        boolean quickViewVisible = ui != null && ui.quickViewVisible();
        return new ResponseFieldMeta(name, label, desc, ctrl, aiVis, quickViewVisible);
    }

    private static String inferTaxIdFilterField(List<FilterFieldMeta> filters) {
        for (FilterFieldMeta meta : filters) {
            if (meta.controlType() == FieldControlType.CPF_CNPJ_INPUT || meta.preset() == UISchemaPreset.CPF_CNPJ) {
                return meta.name();
            }
            String lower = meta.name().toLowerCase(Locale.ROOT);
            if (lower.equals("cpf") || lower.equals("cnpj") || lower.contains("taxid") || lower.contains("cpfcnpj")) {
                return meta.name();
            }
        }
        return null;
    }

    private static String inferNameFilterField(List<FilterFieldMeta> filters) {
        for (FilterFieldMeta meta : filters) {
            if (!meta.relations().isEmpty()) {
                return meta.name();
            }
        }
        for (FilterFieldMeta meta : filters) {
            String lower = meta.name().toLowerCase(Locale.ROOT);
            if (lower.contains("nome") || lower.contains("name") || lower.contains("titulo")
                    || lower.contains("title") || lower.contains("descricao") || lower.contains("description")) {
                return meta.name();
            }
        }
        return null;
    }

    private static String inferIdentifierFilterField(List<FilterFieldMeta> filters) {
        for (FilterFieldMeta meta : filters) {
            String lower = meta.name().toLowerCase(Locale.ROOT);
            if (lower.contains("matricula") || lower.contains("codigo") || lower.equals("code") || lower.contains("numero")) {
                return meta.name();
            }
        }
        return null;
    }

    private static String inferTitleField(Class<?> dtoType) {
        for (String candidate : List.of("nome", "name", "titulo", "title", "descricao", "description")) {
            if (BeanUtils.getPropertyDescriptor(dtoType, candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    private Object tryConvertId(String rawId, Class<?> idType) {
        if (!StringUtils.hasText(rawId) || idType == null) {
            return null;
        }
        if (String.class.equals(idType)) {
            return rawId;
        }
        if (CompositeIdSupport.isCompositeIdType(idType)) {
            try {
                return CompositeIdSupport.decode(rawId.trim(), idType);
            } catch (Exception ignored) {
            }
        }
        try {
            if (conversionService.canConvert(String.class, idType)) {
                return conversionService.convert(rawId, idType);
            }
        } catch (Exception ignored) {
            return null;
        }
        try {
            Method parseMethod = ReflectionUtils.findMethod(idType, "fromString", String.class);
            if (parseMethod == null) {
                parseMethod = ReflectionUtils.findMethod(idType, "valueOf", String.class);
            }
            if (parseMethod != null && java.lang.reflect.Modifier.isStatic(parseMethod.getModifiers())) {
                return parseMethod.invoke(null, rawId);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Object convertFilterFieldValue(Object value, Class<?> targetType) {
        if (value == null || targetType == null) {
            return value;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (String.class.equals(targetType)) {
            return value.toString();
        }
        if (value instanceof String str && CompositeIdSupport.isCompositeIdType(targetType)) {
            try {
                return CompositeIdSupport.decode(str.trim(), targetType);
            } catch (Exception ignored) {
            }
        }
        try {
            if (conversionService.canConvert(value.getClass(), targetType)) {
                return conversionService.convert(value, targetType);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static GenericFilterDTO instantiateFilter(Class<?> filterType) {
        if (filterType == null || filterType.isInterface()) {
            return null;
        }
        try {
            Object instance = BeanUtils.instantiateClass(filterType);
            return instance instanceof GenericFilterDTO dto ? dto : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private Object extractIdValue(RegisteredResource res, Object dto) {
        if (dto == null) {
            return null;
        }
        Object id = invokeProtected(res.controller(), "getResponseId", Object.class, dto);
        if (id == null && StringUtils.hasText(res.descriptor().idFieldName())) {
            PropertyDescriptor pd = BeanUtils.getPropertyDescriptor(dto.getClass(), res.descriptor().idFieldName());
            if (pd != null && pd.getReadMethod() != null) {
                try {
                    id = pd.getReadMethod().invoke(dto);
                } catch (Exception ignored) {
                }
            }
        }
        return id;
    }

    private String extractIdString(RegisteredResource res, Object dto) {
        Object id = extractIdValue(res, dto);
        if (id == null) {
            return null;
        }
        if (CompositeIdSupport.isCompositeIdValue(id)) {
            return CompositeIdSupport.encode(id);
        }
        return String.valueOf(id);
    }

    private Map<String, Object> toMap(RegisteredResource res, Object dto) {
        Map<String, Object> mapped = toMap(dto);
        if (mapped.isEmpty() || res == null || !StringUtils.hasText(res.descriptor().idFieldName())) {
            return mapped;
        }
        Object rawId = extractIdValue(res, dto);
        if (!CompositeIdSupport.isCompositeIdValue(rawId)) {
            return mapped;
        }
        String idField = res.descriptor().idFieldName();
        if (mapped.get(idField) instanceof Map<?, ?>) {
            Map<String, Object> normalized = new LinkedHashMap<>(mapped);
            normalized.put(idField, CompositeIdSupport.encode(rawId));
            return normalized;
        }
        return mapped;
    }

    private Map<String, Object> toMap(Object dto) {
        if (dto == null) {
            return Map.of();
        }
        return objectMapper.convertValue(dto, MAP_TYPE);
    }

    private static Map<String, Object> buildSummaryMap(
            AgentEntityDescriptor descriptor,
            Map<String, Object> source,
            String id) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (StringUtils.hasText(descriptor.idFieldName()) && source.containsKey(descriptor.idFieldName())) {
            summary.put(descriptor.idFieldName(), source.get(descriptor.idFieldName()));
        } else {
            summary.put("id", id);
        }
        if (StringUtils.hasText(descriptor.titleField()) && source.containsKey(descriptor.titleField())) {
            summary.put(descriptor.titleField(), source.get(descriptor.titleField()));
        }
        if (StringUtils.hasText(descriptor.subtitleField()) && source.containsKey(descriptor.subtitleField())) {
            summary.put(descriptor.subtitleField(), source.get(descriptor.subtitleField()));
        }
        for (String metaField : descriptor.metadataFields()) {
            if (source.containsKey(metaField)) {
                summary.put(metaField, source.get(metaField));
            }
        }
        return summary;
    }

    private static String extractDisplayField(Map<String, Object> record, String field, String fallback) {
        if (record != null && StringUtils.hasText(field)) {
            Object value = record.get(field);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString();
            }
        }
        return fallback;
    }

    private <T> T executeReadOnly(Supplier<T> supplier) {
        if (readOnlyTxTemplate == null) {
            return supplier.get();
        }
        return readOnlyTxTemplate.execute(status -> supplier.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResourceStateSnapshot resolveItemStateSnapshot(RegisteredResource res, Object convertedId) {
        if (res == null || res.service() == null || convertedId == null) {
            return null;
        }
        return tryMetadataLookup("findResourceStateSnapshot:" + res.descriptor().resourceKey(), () -> {
            Optional<ResourceStateSnapshot> snapshot =
                    ((BaseResourceQueryService) res.service()).findResourceStateSnapshot(convertedId);
            return snapshot != null ? snapshot.orElse(null) : null;
        });
    }

    private boolean isActionBlockedByStateOrRule(ActionCatalogItem item, ResourceStateSnapshot stateSnapshot) {
        if (item == null) {
            return true;
        }
        if (item.availability() != null && !item.availability().allowed()
                && !"missing-authority".equalsIgnoreCase(item.availability().reason())) {
            return true;
        }
        if (stateSnapshot != null) {
            if (item.allowedStates() != null && !item.allowedStates().isEmpty()
                    && StringUtils.hasText(stateSnapshot.state())
                    && !item.allowedStates().contains(stateSnapshot.state())) {
                return true;
            }
            if (stateSnapshot.metadata() != null
                    && Boolean.FALSE.equals(stateSnapshot.metadata().get("action." + item.id() + ".allowed"))) {
                return true;
            }
        }
        return false;
    }

    private <T> T tryMetadataLookup(String phase, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (SecurityException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.debug("[MetadataAgentBridge] Metadata enrichment '{}' unavailable in background scope: {}", phase, ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeProtected(Object target, String methodName, Class<T> returnType, Object... args) {
        if (target == null) {
            return null;
        }
        Method candidate = findCompatibleMethod(target.getClass(), methodName, args.length);
        if (candidate == null) {
            return null;
        }
        try {
            ReflectionUtils.makeAccessible(candidate);
            Object result = candidate.invoke(target, args);
            return returnType.isInstance(result) ? (T) result : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String methodName, int paramCount) {
        Class<?> current = type;
        while (current != null && !Object.class.equals(current)) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == paramCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Sort parseSort(String sort, Sort defaultSort) {
        if (!StringUtils.hasText(sort)) {
            return defaultSort != null ? defaultSort : Sort.unsorted();
        }
        String[] parts = sort.trim().split(",");
        String prop = parts[0].trim();
        if (prop.isEmpty()) {
            return defaultSort != null ? defaultSort : Sort.unsorted();
        }
        Sort.Direction dir = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(dir, prop);
    }

    private static String resolvePathId(String rawPath, String fallbackBase, String id) {
        if (StringUtils.hasText(rawPath)) {
            return rawPath.replace("{id}", id);
        }
        return fallbackBase;
    }

    private static boolean matchesActiveScreen(AgentExecutionContext context, AgentEntityDescriptor desc) {
        if (context == null || !StringUtils.hasText(context.activeScreenResourcePath()) || desc == null) {
            return false;
        }
        return context.activeScreenResourcePath().trim().equalsIgnoreCase(desc.resourcePath());
    }

    private static String formatTaxId(String digits) {
        if (digits != null && digits.length() == 11) {
            return digits.substring(0, 3) + "." + digits.substring(3, 6) + "."
                    + digits.substring(6, 9) + "-" + digits.substring(9, 11);
        }
        if (digits != null && digits.length() == 14) {
            return digits.substring(0, 2) + "." + digits.substring(2, 5) + "."
                    + digits.substring(5, 8) + "/" + digits.substring(8, 12) + "-" + digits.substring(12, 14);
        }
        return digits;
    }

    private static String deriveEntityKey(String basePath) {
        String clean = basePath.replaceAll("^/+|/+$", "");
        int lastSlash = clean.lastIndexOf('/');
        return lastSlash >= 0 ? clean.substring(lastSlash + 1) : clean;
    }

    private static boolean containsIgnoreCase(String source, String query) {
        return StringUtils.hasText(source) && StringUtils.hasText(query)
                && source.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        return StringUtils.hasText(second) ? second.trim() : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ScoredRow(Object dto, String matchedBy, double confidence) {
    }

    private record ScoredMatch(AgentEntityMatch match, AgentEntityDescriptor descriptor) {
    }
}
