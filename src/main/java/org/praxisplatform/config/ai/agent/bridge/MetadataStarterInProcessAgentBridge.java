package org.praxisplatform.config.ai.agent.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.ai.agent.bridge.BridgeActionReflectionSupport.DeterminationEndpoint;
import org.praxisplatform.config.ai.agent.bridge.BridgeActionReflectionSupport.MutatingActionEndpoint;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordance;
import org.praxisplatform.config.ai.agent.spi.AgentActionAffordanceContributor;
import org.praxisplatform.config.ai.agent.spi.AgentActionExecutionOutcome;
import org.praxisplatform.config.ai.agent.spi.AgentActionInputField;
import org.praxisplatform.config.ai.agent.spi.AgentActionPreparationContributor;
import org.praxisplatform.config.ai.agent.spi.AgentActionPreparationContributor.ActionGuardAssessment;
import org.praxisplatform.config.ai.agent.spi.AgentAuthorizationBridge;
import org.praxisplatform.config.ai.agent.spi.AgentDeterminationRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEntityCatalogContributor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentEntityDossier;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntitySearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentEvaluatedCalculationMemory;
import org.praxisplatform.config.ai.agent.spi.AgentExecuteActionRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentFieldDescriptor;
import org.praxisplatform.config.ai.agent.spi.AgentPrepareActionRequest;
import org.praxisplatform.config.ai.agent.spi.AgentPreparedAction;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlert;
import org.praxisplatform.config.ai.agent.spi.AgentProactiveAlertContributor;
import org.praxisplatform.config.ai.agent.spi.AgentQueryResult;
import org.praxisplatform.config.ai.agent.spi.AgentRelatedDataset;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.praxisplatform.config.ai.agent.spi.AgentSimulationPreview;
import org.praxisplatform.config.ai.agent.spi.AgentStatsAggregationRequest;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult;
import org.praxisplatform.config.ai.agent.spi.AgentStatsResult.AgentStatsBucket;
import org.praxisplatform.config.ai.agent.spi.AgentSurfaceSummary;
import org.praxisplatform.config.ai.agent.spi.AgentTopicContributor;
import org.praxisplatform.config.ai.agent.spi.AgentTopicDescriptor;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.DeterminationSummary;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.QuickFilterSummary;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.ResourceFactSummary;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.StatsCapabilityDigest;
import org.praxisplatform.config.ai.agent.spi.CompactAgentSchemaDigest.StatsFieldDigest;
import org.praxisplatform.config.ai.agent.spi.DualProjectedPayload;
import org.praxisplatform.rules.violation.PraxisRuleViolationException;
import org.praxisplatform.rules.violation.SlotViolationDetail;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.action.ActionCatalogItem;
import org.praxisplatform.uischema.action.ActionCatalogResponse;
import org.praxisplatform.uischema.action.ActionCatalogService;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.annotation.ApiResource;
import org.praxisplatform.uischema.annotation.DomainGovernance;
import org.praxisplatform.uischema.annotation.ResourceIdentity;
import org.praxisplatform.uischema.annotation.UiOption;
import org.praxisplatform.uischema.capability.CapabilityOperation;
import org.praxisplatform.uischema.capability.CapabilityService;
import org.praxisplatform.uischema.capability.CapabilitySnapshot;
import org.praxisplatform.uischema.capability.DefaultResourceAvailabilityService;
import org.praxisplatform.uischema.capability.ResourceStateSnapshot;
import org.praxisplatform.uischema.concurrency.ResourceRepresentationResult;
import org.praxisplatform.uischema.concurrency.ResourceVersionEtagService;
import org.praxisplatform.uischema.concurrency.ResourceVersionPreconditionException;
import org.praxisplatform.uischema.concurrency.ResourceVersionScope;
import org.praxisplatform.uischema.concurrency.ResourceVersionScopeProvider;
import org.praxisplatform.uischema.concurrency.ResourceVersionUpdatePrecondition;
import org.praxisplatform.uischema.controller.base.AbstractCreateUpdateResourceController;
import org.praxisplatform.uischema.controller.base.AbstractResourceQueryController;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
import org.praxisplatform.uischema.fact.ResourceFact;
import org.praxisplatform.uischema.fact.ResourceFactCatalogProvider;
import org.praxisplatform.uischema.fact.ResourceFactDescriptor;
import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.id.CompositeIdSupport;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.praxisplatform.uischema.schema.ApiResourceQuickFilterResolver;
import org.praxisplatform.uischema.schema.CalculationMemoryMetadataResolver;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceCommandService;
import org.praxisplatform.uischema.service.base.BaseCreateUpdateResourceService;
import org.praxisplatform.uischema.service.base.BaseResourceCommandService;
import org.praxisplatform.uischema.service.base.BaseResourceQueryService;
import org.praxisplatform.uischema.service.base.VersionedCreateUpdateResourceService;
import org.praxisplatform.uischema.stats.DistributionMode;
import org.praxisplatform.uischema.stats.StatsBucketOrder;
import org.praxisplatform.uischema.stats.StatsFieldDescriptor;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsMetric;
import org.praxisplatform.uischema.stats.StatsSupportMode;
import org.praxisplatform.uischema.stats.TimeSeriesGranularity;
import org.praxisplatform.uischema.stats.dto.DistributionBucket;
import org.praxisplatform.uischema.stats.dto.DistributionStatsRequest;
import org.praxisplatform.uischema.stats.dto.DistributionStatsResponse;
import org.praxisplatform.uischema.stats.dto.GroupByBucket;
import org.praxisplatform.uischema.stats.dto.GroupByStatsRequest;
import org.praxisplatform.uischema.stats.dto.GroupByStatsResponse;
import org.praxisplatform.uischema.stats.dto.StatsMetricRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesPoint;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsRequest;
import org.praxisplatform.uischema.stats.dto.TimeSeriesStatsResponse;
import org.praxisplatform.uischema.surface.RelatedResourceSurface;
import org.praxisplatform.uischema.surface.SurfaceCatalogItem;
import org.praxisplatform.uischema.surface.SurfaceCatalogResponse;
import org.praxisplatform.uischema.surface.SurfaceCatalogService;
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
import org.praxisplatform.uischema.surface.SurfaceScope;
import org.springframework.web.context.request.RequestContextHolder;
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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

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
            List<ResponseFieldMeta> responseFields,
            List<DeterminationEndpoint> determinations,
            List<MutatingActionEndpoint> mutatingActions
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
            boolean quickViewVisible,
            int order,
            int quickViewOrder,
            boolean hidden,
            boolean tableHidden,
            String labelTrue,
            String labelFalse,
            Map<String, String> optionLabels
    ) {
    }

    private final List<AbstractResourceQueryController<?, ?, ?>> controllers;
    private final List<AgentEntityCatalogContributor> entityContributors;
    private final List<AgentTopicContributor> topicContributors;
    private final List<AgentProactiveAlertContributor> alertContributors;
    private final List<AgentActionAffordanceContributor> actionContributors;
    private final List<ResourceFactCatalogProvider> factCatalogProviders;
    private final List<AgentActionPreparationContributor> preparationContributors;
    private final CapabilityService capabilityService;
    private final ActionCatalogService actionCatalogService;
    private final ActionDefinitionRegistry actionDefinitionRegistry;
    private final SurfaceCatalogService surfaceCatalogService;
    private final SurfaceDefinitionRegistry surfaceDefinitionRegistry;
    private final AgentAuthorizationBridge authorizationBridge;
    private final ConversionService conversionService;
    private final ObjectMapper objectMapper;
    private final ResourceVersionEtagService resourceVersionEtagService;
    private final ResourceVersionScopeProvider resourceVersionScopeProvider;
    private final Validator validator;
    private final TransactionTemplate readOnlyTxTemplate;
    private final TransactionTemplate isolatedReadOnlyTxTemplate;
    private final TransactionTemplate rollbackOnlyReadTemplate;

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
                List.of(),
                null,
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
                actionContributors,
                null,
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
            List<AgentActionAffordanceContributor> actionContributors,
            SurfaceDefinitionRegistry surfaceDefinitionRegistry) {
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
                actionContributors,
                surfaceDefinitionRegistry,
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
            List<AgentActionAffordanceContributor> actionContributors,
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            List<ResourceFactCatalogProvider> factCatalogProviders) {
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
                actionContributors,
                surfaceDefinitionRegistry,
                factCatalogProviders,
                null,
                null,
                null,
                null,
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
            List<AgentActionAffordanceContributor> actionContributors,
            SurfaceDefinitionRegistry surfaceDefinitionRegistry,
            List<ResourceFactCatalogProvider> factCatalogProviders,
            ActionDefinitionRegistry actionDefinitionRegistry,
            ResourceVersionEtagService resourceVersionEtagService,
            ResourceVersionScopeProvider resourceVersionScopeProvider,
            Validator validator,
            List<AgentActionPreparationContributor> preparationContributors) {
        this.controllers = controllers == null ? List.of() : List.copyOf(controllers);
        this.entityContributors = entityContributors == null ? List.of() : List.copyOf(entityContributors);
        this.topicContributors = topicContributors == null ? List.of() : List.copyOf(topicContributors);
        this.alertContributors = alertContributors == null ? List.of() : List.copyOf(alertContributors);
        this.actionContributors = actionContributors == null ? List.of() : List.copyOf(actionContributors);
        this.factCatalogProviders = factCatalogProviders == null ? List.of() : List.copyOf(factCatalogProviders);
        this.preparationContributors = preparationContributors == null ? List.of() : List.copyOf(preparationContributors);
        this.capabilityService = capabilityService;
        this.actionCatalogService = actionCatalogService;
        this.actionDefinitionRegistry = actionDefinitionRegistry;
        this.surfaceCatalogService = surfaceCatalogService;
        this.surfaceDefinitionRegistry = surfaceDefinitionRegistry;
        this.authorizationBridge = authorizationBridge != null
                ? authorizationBridge
                : new DefaultMetadataGovernanceAgentAuthorizationBridge(this.controllers);
        this.conversionService = conversionService != null
                ? conversionService
                : DefaultConversionService.getSharedInstance();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
        this.resourceVersionEtagService = resourceVersionEtagService;
        this.resourceVersionScopeProvider = resourceVersionScopeProvider;
        this.validator = validator;
        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setReadOnly(true);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            this.readOnlyTxTemplate = template;

            TransactionTemplate isolatedTemplate = new TransactionTemplate(transactionManager);
            isolatedTemplate.setReadOnly(true);
            isolatedTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.isolatedReadOnlyTxTemplate = isolatedTemplate;

            TransactionTemplate rollbackTemplate = new TransactionTemplate(transactionManager);
            rollbackTemplate.setReadOnly(false);
            rollbackTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.rollbackOnlyReadTemplate = rollbackTemplate;
        } else {
            this.readOnlyTxTemplate = null;
            this.isolatedReadOnlyTxTemplate = null;
            this.rollbackOnlyReadTemplate = null;
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
        return searchEntitiesInternal(context, request);
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
        return loadEntityDossierInternal(context, res, entityId.trim(), includeRelatedSurfaces);
    }

    @Override
    public AgentStatsResult aggregateStats(
            AgentExecutionContext context,
            AgentStatsAggregationRequest request) {
        if (request == null || !StringUtils.hasText(request.entityKeyOrResourcePath())) {
            throw new IllegalArgumentException("entityKeyOrResourcePath is required");
        }
        RegisteredResource res = findResource(context, request.entityKeyOrResourcePath())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown entity/resource: " + request.entityKeyOrResourcePath()));
        if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
            throw new SecurityException("Access denied to resource: " + res.descriptor().resourcePath());
        }
        return executeReadOnly(() -> aggregateStatsInternal(context, res, request));
    }

    @Override
    public List<AgentEvaluatedCalculationMemory> evaluateCalculationMemory(
            AgentExecutionContext context,
            String entityKeyOrResourcePath,
            String entityId,
            Map<String, Object> filters,
            int maxRecords) {
        if (!StringUtils.hasText(entityKeyOrResourcePath)) {
            return List.of();
        }
        Optional<RegisteredResource> found = findResource(context, entityKeyOrResourcePath);
        if (found.isEmpty()) {
            return List.of();
        }
        RegisteredResource res = found.get();
        if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
            throw new SecurityException("Access denied to resource: " + res.descriptor().resourcePath());
        }
        return executeReadOnly(() -> evaluateCalculationMemoryInternal(
                context, res, trimToNull(entityId), filters, Math.min(Math.max(1, maxRecords), 5)));
    }

    @Override
    public AgentSimulationPreview simulateDetermination(
            AgentExecutionContext context,
            AgentDeterminationRequest request) {
        if (request == null || !StringUtils.hasText(request.entityKeyOrResourcePath())) {
            throw new IllegalArgumentException("entityKeyOrResourcePath is required");
        }
        RegisteredResource res = findResource(context, request.entityKeyOrResourcePath())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown entity/resource: " + request.entityKeyOrResourcePath()));
        if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
            throw new SecurityException("Access denied to resource: " + res.descriptor().resourcePath());
        }
        DeterminationEndpoint endpoint = resolveDeterminationEndpoint(res, request.determinationId());
        Map<String, Object> submittedDraft = request.draftFields() != null ? request.draftFields() : Map.of();

        Object convertedDraft = null;
        if (endpoint.requestDtoType() != null && endpoint.requestBodyIndex() >= 0) {
            try {
                convertedDraft = objectMapper.convertValue(submittedDraft, endpoint.requestDtoType());
            } catch (IllegalArgumentException ex) {
                List<AgentFieldDescriptor> previewFields = toAgentFieldDescriptors(
                        introspectResponseFields(endpoint.responseDtoType()));
                return new AgentSimulationPreview(
                        res.descriptor().entityKey(),
                        res.descriptor().resourceKey(),
                        res.descriptor().resourcePath(),
                        endpoint.determinationId(),
                        endpoint.title(),
                        endpoint.description(),
                        submittedDraft,
                        Map.of(),
                        Map.of(),
                        previewFields,
                        List.of(Map.of(
                                "field", "_draft",
                                "issueNote", "Formato inválido nos campos do rascunho para simulação.")));
            }
            Map<String, String> fieldErrors = BridgeActionReflectionSupport.validateDto(validator, convertedDraft);
            if (!fieldErrors.isEmpty()) {
                List<Map<String, String>> issues = fieldErrors.entrySet().stream()
                        .map(e -> Map.of("field", e.getKey(), "issueNote", e.getValue()))
                        .toList();
                List<AgentFieldDescriptor> previewFields = toAgentFieldDescriptors(
                        introspectResponseFields(endpoint.responseDtoType()));
                return new AgentSimulationPreview(
                        res.descriptor().entityKey(),
                        res.descriptor().resourceKey(),
                        res.descriptor().resourcePath(),
                        endpoint.determinationId(),
                        endpoint.title(),
                        endpoint.description(),
                        submittedDraft,
                        Map.of(),
                        Map.of(),
                        previewFields,
                        issues);
            }
        }

        final Object finalDraft = convertedDraft;
        Object rawOutcome = executeRollbackOnlyRead(() -> invokeDeterminationEndpoint(res, endpoint, finalDraft));
        Object unwrapped = unwrapControllerResult(rawOutcome);
        Map<String, Object> rawPreview = toMap(unwrapped);
        DualProjectedPayload projected = authorizationBridge.projectRecord(
                context,
                res.descriptor().resourcePath(),
                endpoint.responseDtoType(),
                rawPreview);
        List<AgentFieldDescriptor> previewFields = toAgentFieldDescriptors(
                introspectResponseFields(endpoint.responseDtoType()));
        return new AgentSimulationPreview(
                res.descriptor().entityKey(),
                res.descriptor().resourceKey(),
                res.descriptor().resourcePath(),
                endpoint.determinationId(),
                endpoint.title(),
                endpoint.description(),
                submittedDraft,
                projected.uiRecord(),
                projected.modelSafeRecord(),
                previewFields,
                List.of());
    }

    @Override
    public AgentPreparedAction prepareAction(
            AgentExecutionContext context,
            AgentPrepareActionRequest request) {
        if (request == null || !StringUtils.hasText(request.entityKeyOrResourcePath()) || !StringUtils.hasText(request.actionId())) {
            throw new IllegalArgumentException("entityKeyOrResourcePath e actionId são obrigatórios");
        }
        RegisteredResource res = findResource(context, request.entityKeyOrResourcePath())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Recurso desconhecido: " + request.entityKeyOrResourcePath()));
        if (!authorizationBridge.canReadResource(context, res.descriptor().resourcePath())) {
            throw new SecurityException("Access denied to resource: " + res.descriptor().resourcePath());
        }
        MutatingActionEndpoint endpoint = resolveMutatingEndpoint(res, request.actionId());
        boolean isCollectionScope = endpoint.scope() == ActionScope.COLLECTION;
        if (!isCollectionScope && !StringUtils.hasText(request.entityId())) {
            throw new IllegalArgumentException("entityId é obrigatório para preparar a ação " + endpoint.actionId());
        }

        Object convertedId = isCollectionScope ? null : tryConvertId(request.entityId().trim(), res.idType());
        if (!isCollectionScope && convertedId == null) {
            throw new IllegalArgumentException("Identificador inválido para " + res.descriptor().entityKey() + ": " + request.entityId());
        }

        return executeIsolatedReadOnly(() -> prepareActionInternal(context, res, endpoint, convertedId,
                request.entityId() != null ? request.entityId().trim() : null,
                request.proposedInputs() != null ? request.proposedInputs() : Map.of()));
    }

    @Override
    public AgentActionExecutionOutcome executeConfirmedAction(
            AgentExecutionContext context,
            AgentExecuteActionRequest request) {
        if (request == null || !StringUtils.hasText(request.entityKeyOrResourcePath()) || !StringUtils.hasText(request.actionId())) {
            throw new IllegalArgumentException("entityKeyOrResourcePath e actionId são obrigatórios");
        }
        RegisteredResource res = findResource(context, request.entityKeyOrResourcePath())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Recurso desconhecido: " + request.entityKeyOrResourcePath()));
        MutatingActionEndpoint endpoint = resolveMutatingEndpoint(res, request.actionId());
        boolean isCollectionScope = endpoint.scope() == ActionScope.COLLECTION;
        Object convertedId = isCollectionScope ? null : tryConvertId(request.entityId(), res.idType());
        if (!isCollectionScope && convertedId == null) {
            throw new IllegalArgumentException("Identificador inválido para " + res.descriptor().entityKey() + ": " + request.entityId());
        }
        String canonicalId = convertedId != null ? stringifyEntityId(convertedId, request.entityId()) : null;

        if (!authorizationBridge.canInvokeAction(context, res.descriptor().resourcePath(), endpoint.actionId(), convertedId)
                || ("edit".equalsIgnoreCase(endpoint.actionId())
                        && !authorizationBridge.canInvokeAction(context, res.descriptor().resourcePath(), "update", convertedId))) {
            return failureOutcome(403, "ACCESS_DENIED", "Autoridade insuficiente para executar a ação " + endpoint.actionId() + ".");
        }

        if (!preparationContributors.isEmpty()) {
            try {
                Map<String, Object> authoritativeRaw = isCollectionScope
                        ? Map.of()
                        : loadRawRecordIsolated(res, convertedId);
                if (!isCollectionScope && authoritativeRaw == null) {
                    return failureOutcome(404, "RESOURCE_NOT_FOUND", "Registro não encontrado para execução da ação.");
                }
                ActionGuardAssessment guard = executeIsolatedReadOnly(() -> evaluatePreparationContributors(
                        context, res.descriptor(), canonicalId, endpoint.actionId(), authoritativeRaw, request.confirmedInputs()));
                if (guard != null && !guard.allowed()) {
                    return failureOutcome(
                            409,
                            firstNonBlank(guard.blockedCode(), "ACTION_BLOCKED"),
                            firstNonBlank(guard.impedimentNote(), "Execução bloqueada pelas regras de escopo do host."));
                }
                if (StringUtils.hasText(request.organizationId())
                        && guard != null
                        && StringUtils.hasText(guard.organizationId())
                        && !request.organizationId().trim().equalsIgnoreCase(guard.organizationId().trim())) {
                    return failureOutcome(
                            409,
                            "ORGANIZATION_SCOPE_CHANGED",
                            "O escopo organizacional ativo diverge da organização vinculada à proposta.");
                }
            } catch (Throwable ex) {
                return mapExecutionException(context, res, convertedId, canonicalId, ex);
            }
        }

        Object requestDto = null;
        if (endpoint.requestDtoType() != null) {
            Map<String, Object> payloadMap = request.confirmedInputs() != null ? request.confirmedInputs() : Map.of();
            if (!payloadMap.isEmpty()
                    && !authorizationBridge.canModifyFields(
                            context,
                            res.descriptor().resourcePath(),
                            endpoint.requestDtoType(),
                            payloadMap.keySet())) {
                return failureOutcome(
                        403,
                        "FIELD_EDIT_DENIED",
                        "Sem permissão para alterar um ou mais campos informados.");
            }
            try {
                requestDto = objectMapper.convertValue(payloadMap, endpoint.requestDtoType());
            } catch (IllegalArgumentException ex) {
                return new AgentActionExecutionOutcome(
                        422,
                        false,
                        "INVALID_INPUT_PAYLOAD",
                        "Os parâmetros informados não puderam ser convertidos para o contrato da ação.",
                        null,
                        null,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of("_payload", "Formato de dados incompatível com o comando."),
                        Map.of());
            }
            Map<String, String> fieldErrors = BridgeActionReflectionSupport.validateDto(validator, requestDto);
            if (!fieldErrors.isEmpty()) {
                return new AgentActionExecutionOutcome(
                        422,
                        false,
                        "VALIDATION_FAILED",
                        "Preencha os campos obrigatórios antes de confirmar a execução.",
                        null,
                        null,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        fieldErrors,
                        Map.of());
            }
        }

        return invokeMutatingEndpoint(context, res, endpoint, convertedId, canonicalId, request, requestDto);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AgentPreparedAction prepareActionInternal(
            AgentExecutionContext context,
            RegisteredResource res,
            MutatingActionEndpoint endpoint,
            Object convertedId,
            String rawEntityId,
            Map<String, Object> proposedInputs) {
        String resourceKey = res.descriptor().resourceKey();
        String resourcePath = res.descriptor().resourcePath();
        boolean isCollectionScope = endpoint.scope() == ActionScope.COLLECTION;

        Map<String, Object> rawRecord = Map.of();
        Map<String, Object> uiRecord = Map.of();
        String canonicalId = rawEntityId;
        String recordTitle = res.descriptor().title();
        String recordSubtitle = null;
        String expectedVersionTag = null;
        ResourceStateSnapshot stateSnapshot = null;

        if (!isCollectionScope) {
            ResourceRepresentationResult<?> repr = ((BaseResourceQueryService) res.service()).findById(convertedId);
            if (repr == null || repr.body() == null) {
                throw new IllegalArgumentException("Registro não encontrado para " + res.descriptor().entityKey() + " id=" + rawEntityId);
            }
            Object dto = repr.body();
            rawRecord = toMap(res, dto);
            DualProjectedPayload projected = authorizationBridge.projectRecord(
                    context, resourcePath, res.responseDtoType(), rawRecord);
            uiRecord = projected.uiRecord();
            canonicalId = firstNonBlank(extractIdString(res, dto), rawEntityId);
            recordTitle = extractDisplayField(uiRecord, res.descriptor().titleField(), canonicalId);
            recordSubtitle = extractDisplayField(uiRecord, res.descriptor().subtitleField(), "");
            stateSnapshot = resolveItemStateSnapshot(res, convertedId);
            OptionalLong persistedVer = repr.persistedVersion();
            if (persistedVer.isPresent() && resourceVersionEtagService != null && StringUtils.hasText(resourceKey)) {
                expectedVersionTag = resourceVersionEtagService.create(
                        currentResourceVersionScope(), resourceKey, convertedId, persistedVer.getAsLong());
            }
        }

        String lifecycleState = BridgeActionReflectionSupport.extractLifecycleState(rawRecord, stateSnapshot);
        boolean allowed = true;
        String blockedCode = null;
        String impedimentNote = null;

        boolean authorityOk = authorizationBridge.canInvokeAction(context, resourcePath, endpoint.actionId(), convertedId)
                && (!"edit".equalsIgnoreCase(endpoint.actionId())
                        || authorizationBridge.canInvokeAction(context, resourcePath, "update", convertedId));
        if (!authorityOk) {
            allowed = false;
            blockedCode = "MISSING_AUTHORITY";
            impedimentNote = "Usuário atual não possui autoridade de escrita para executar " + endpoint.title() + ".";
        }

        if (allowed && !isCollectionScope && !endpoint.allowedStates().isEmpty() && StringUtils.hasText(lifecycleState)) {
            boolean stateMatch = endpoint.allowedStates().stream()
                    .anyMatch(s -> s.equalsIgnoreCase(lifecycleState));
            if (!stateMatch) {
                allowed = false;
                blockedCode = "INVALID_LIFECYCLE_STATE";
                impedimentNote = "Ação '" + endpoint.title() + "' exige situação "
                        + String.join(" ou ", endpoint.allowedStates())
                        + ", mas o registro está em '" + lifecycleState + "'.";
            }
        }

        if (allowed && !isCollectionScope && stateSnapshot != null && stateSnapshot.metadata() != null) {
            String metaKey = "edit".equalsIgnoreCase(endpoint.actionId())
                    ? "operation.update.allowed"
                    : "action." + endpoint.actionId() + ".allowed";
            if (Boolean.FALSE.equals(stateSnapshot.metadata().get(metaKey))) {
                allowed = false;
                blockedCode = "STATE_RULE_BLOCKED";
                Object reasonMeta = stateSnapshot.metadata().get(metaKey.replace(".allowed", ".reason"));
                impedimentNote = reasonMeta != null && StringUtils.hasText(reasonMeta.toString())
                        ? reasonMeta.toString()
                        : "Ação indisponível para o estado operacional atual do registro.";
            }
        }

        ActionGuardAssessment guard = evaluatePreparationContributors(
                context, res.descriptor(), canonicalId, endpoint.actionId(), rawRecord, proposedInputs);
        String organizationId = guard != null ? trimToNull(guard.organizationId()) : null;
        String organizationLabel = guard != null ? trimToNull(guard.organizationLabel()) : null;
        if (allowed && guard != null && !guard.allowed()) {
            allowed = false;
            blockedCode = firstNonBlank(guard.blockedCode(), "HOST_SCOPE_BLOCKED");
            impedimentNote = firstNonBlank(guard.impedimentNote(), "Execução bloqueada pelas regras de escopo ativas.");
        }
        if (allowed
                && endpoint.requestDtoType() != null
                && proposedInputs != null
                && !proposedInputs.isEmpty()
                && !authorizationBridge.canModifyFields(
                        context, resourcePath, endpoint.requestDtoType(), proposedInputs.keySet())) {
            allowed = false;
            blockedCode = "FIELD_EDIT_DENIED";
            impedimentNote = "Usuário atual não possui autoridade para editar um ou mais campos solicitados.";
        }

        Map<String, Object> mergedPrefill = new LinkedHashMap<>();
        boolean seedFromRecord = "edit".equalsIgnoreCase(endpoint.actionId())
                || "partial-patch".equalsIgnoreCase(endpoint.operationKind());
        if (seedFromRecord && !uiRecord.isEmpty()) {
            for (AgentActionInputField field : endpoint.inputFields()) {
                if (uiRecord.containsKey(field.name()) && uiRecord.get(field.name()) != null) {
                    if (endpoint.requestDtoType() == null
                            || authorizationBridge.canModifyFields(
                                    context, resourcePath, endpoint.requestDtoType(), Set.of(field.name()))) {
                        mergedPrefill.put(field.name(), uiRecord.get(field.name()));
                    }
                }
            }
        }
        if (guard != null && !guard.enrichedPrefill().isEmpty()) {
            mergedPrefill.putAll(guard.enrichedPrefill());
        }
        if (proposedInputs != null && !proposedInputs.isEmpty()) {
            for (Map.Entry<String, Object> entry : proposedInputs.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    mergedPrefill.put(entry.getKey(), entry.getValue());
                }
            }
        }

        List<String> missingRequired = BridgeActionReflectionSupport.computeMissingRequiredFields(
                endpoint.inputFields(), mergedPrefill);
        boolean hasInputForm = endpoint.requestDtoType() != null && !endpoint.inputFields().isEmpty();
        String formMode = "workflow-action".equals(endpoint.operationKind())
                ? (hasInputForm ? "dialog" : "confirm-only")
                : "drawer";
        String submitUrl = resolveSubmitUrl(resourcePath, endpoint.relativePath(), canonicalId);
        String schemaUrl = hasInputForm
                ? buildFilteredSchemaUrl(
                        BridgeActionReflectionSupport.combinePath(resourcePath, endpoint.relativePath()),
                        endpoint.httpMethod())
                : null;

        return new AgentPreparedAction(
                res.descriptor().entityKey(),
                resourceKey,
                resourcePath,
                canonicalId,
                recordTitle,
                recordSubtitle,
                endpoint.actionId(),
                endpoint.title(),
                endpoint.description(),
                endpoint.operationKind(),
                formMode,
                endpoint.httpMethod(),
                submitUrl,
                schemaUrl,
                endpoint.riskLevel(),
                true,
                hasInputForm,
                organizationId,
                organizationLabel,
                lifecycleState,
                expectedVersionTag,
                allowed,
                blockedCode,
                impedimentNote,
                mergedPrefill,
                endpoint.inputFields(),
                missingRequired);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AgentActionExecutionOutcome invokeMutatingEndpoint(
            AgentExecutionContext context,
            RegisteredResource res,
            MutatingActionEndpoint endpoint,
            Object convertedId,
            String canonicalId,
            AgentExecuteActionRequest request,
            Object requestDto) {
        String resourceKey = res.descriptor().resourceKey();
        String resourcePath = res.descriptor().resourcePath();
        try {
            Object rawReturn;
            String headerEtag = null;
            if ("edit".equalsIgnoreCase(endpoint.actionId()) && endpoint.method() == null) {
                if (res.service() instanceof VersionedCreateUpdateResourceService versioned) {
                    if (resourceVersionEtagService == null) {
                        return failureOutcome(500, "VERSIONING_UNCONFIGURED", "Controle de concorrência otimista não configurado.");
                    }
                    ResourceVersionUpdatePrecondition precondition = new ResourceVersionUpdatePrecondition(
                            resourceVersionEtagService,
                            request.expectedVersionTag(),
                            currentResourceVersionScope(),
                            resourceKey,
                            convertedId);
                    ResourceRepresentationResult<?> resResult = versioned.update(convertedId, requestDto, precondition);
                    rawReturn = resResult != null ? resResult.body() : null;
                    if (resResult != null && resResult.persistedVersion().isPresent()) {
                        headerEtag = resourceVersionEtagService.create(
                                currentResourceVersionScope(), resourceKey, convertedId, resResult.persistedVersion().getAsLong());
                    }
                } else if (res.service() instanceof BaseCreateUpdateResourceService base) {
                    rawReturn = base.update(convertedId, requestDto);
                } else {
                    return failureOutcome(501, "UNSUPPORTED_OPERATION", "Serviço não suporta edição de registro.");
                }
            } else {
                Method m = endpoint.method();
                Object[] args = new Object[m.getParameterCount()];
                if (endpoint.pathVariableIndex() >= 0) {
                    args[endpoint.pathVariableIndex()] = convertedId;
                }
                if (endpoint.ifMatchHeaderIndex() >= 0) {
                    args[endpoint.ifMatchHeaderIndex()] = request.expectedVersionTag();
                }
                if (endpoint.idempotencyKeyHeaderIndex() >= 0) {
                    args[endpoint.idempotencyKeyHeaderIndex()] = request.idempotencyKey();
                }
                if (endpoint.requestBodyIndex() >= 0) {
                    args[endpoint.requestBodyIndex()] = requestDto;
                }
                ReflectionUtils.makeAccessible(m);
                rawReturn = m.invoke(res.controller(), args);
            }

            if (rawReturn instanceof ResponseEntity<?> responseEntity) {
                int status = responseEntity.getStatusCode().value();
                headerEtag = firstNonBlank(headerEtag, responseEntity.getHeaders().getETag());
                if (status == 412 || status == 428) {
                    return buildStaleOutcome(context, res, convertedId, canonicalId,
                            "O registro foi alterado por outro usuário ou processo antes da confirmação.");
                }
                if (!responseEntity.getStatusCode().is2xxSuccessful()) {
                    return mapNon2xxEntityOutcome(status, responseEntity.getBody());
                }
                return buildSuccessOutcome(context, res, endpoint.responseDtoType(), convertedId, unwrapControllerResult(responseEntity), headerEtag);
            }
            return buildSuccessOutcome(context, res, endpoint.responseDtoType(), convertedId, unwrapControllerResult(rawReturn), headerEtag);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException() != null ? ex.getTargetException() : ex;
            return mapExecutionException(context, res, convertedId, canonicalId, cause);
        } catch (Throwable ex) {
            return mapExecutionException(context, res, convertedId, canonicalId, ex);
        }
    }

    private AgentActionExecutionOutcome buildSuccessOutcome(
            AgentExecutionContext context,
            RegisteredResource res,
            Class<?> endpointResponseDtoType,
            Object convertedId,
            Object dto,
            String updatedEtag) {
        Map<String, Object> rawResult = dto != null ? toMap(res, dto) : Map.of();
        Class<?> projectionDtoType = endpointResponseDtoType != null
                && !Object.class.equals(endpointResponseDtoType)
                ? endpointResponseDtoType
                : res.responseDtoType();
        DualProjectedPayload projected = authorizationBridge.projectRecord(
                context, res.descriptor().resourcePath(), projectionDtoType, rawResult);
        String resolvedEtag = trimToNull(updatedEtag);
        if (!StringUtils.hasText(resolvedEtag) && convertedId != null && resourceVersionEtagService != null
                && StringUtils.hasText(res.descriptor().resourceKey())) {
            OptionalLong latestVer = loadLatestVersionIsolated(res, convertedId);
            if (latestVer.isPresent()) {
                resolvedEtag = resourceVersionEtagService.create(
                        currentResourceVersionScope(),
                        res.descriptor().resourceKey(),
                        convertedId,
                        latestVer.getAsLong());
            }
        }
        String updatedState = BridgeActionReflectionSupport.extractLifecycleState(rawResult, null);
        return new AgentActionExecutionOutcome(
                200,
                true,
                "CONFIRMED",
                "Ação executada com sucesso.",
                resolvedEtag,
                updatedState,
                projected.uiRecord(),
                projected.modelSafeRecord(),
                List.of(),
                Map.of(),
                Map.of());
    }

    private AgentActionExecutionOutcome mapExecutionException(
            AgentExecutionContext context,
            RegisteredResource res,
            Object convertedId,
            String canonicalId,
            Throwable cause) {
        if (cause instanceof ResourceVersionPreconditionException rve) {
            return buildStaleOutcome(context, res, convertedId, canonicalId,
                    firstNonBlank(rve.getMessage(), "A versão informada não corresponde ao estado atual do registro."));
        }
        String className = cause.getClass().getName();
        if (className.endsWith("OptimisticLockingFailureException")
                || className.endsWith("OptimisticLockException")
                || className.endsWith("ObjectOptimisticLockingFailureException")) {
            return buildStaleOutcome(context, res, convertedId, canonicalId,
                    "Conflito concorrente de atualização; o registro foi modificado por outra transação.");
        }
        if (cause instanceof PraxisRuleViolationException ruleEx) {
            List<Map<String, Object>> violations = new ArrayList<>();
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            if (ruleEx.getViolations() != null) {
                for (SlotViolationDetail detail : ruleEx.getViolations()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    if (StringUtils.hasText(detail.code())) entry.put("code", detail.code());
                    if (StringUtils.hasText(detail.slotKey())) entry.put("slotKey", detail.slotKey());
                    if (StringUtils.hasText(detail.targetField())) entry.put("targetField", detail.targetField());
                    if (StringUtils.hasText(detail.messagePtBr())) entry.put("issueNote", detail.messagePtBr());
                    if (StringUtils.hasText(detail.category())) entry.put("category", detail.category());
                    violations.add(entry);
                    if (StringUtils.hasText(detail.targetField()) && StringUtils.hasText(detail.messagePtBr())) {
                        fieldErrors.putIfAbsent(detail.targetField(), detail.messagePtBr());
                    }
                }
            }
            int status = ruleEx.getHttpStatus() >= 400 ? ruleEx.getHttpStatus() : 422;
            return new AgentActionExecutionOutcome(
                    status,
                    false,
                    "RULE_VIOLATION",
                    firstNonBlank(ruleEx.getMessage(), "Execução impedida por regra de negócio."),
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    List.copyOf(violations),
                    Map.copyOf(fieldErrors),
                    Map.of());
        }
        if (cause instanceof ResponseStatusException rse) {
            int status = rse.getStatusCode().value();
            if (status == 412 || status == 428) {
                return buildStaleOutcome(context, res, convertedId, canonicalId,
                        firstNonBlank(rse.getReason(), "A versão informada está desatualizada."));
            }
            return failureOutcome(status, "HTTP_" + status,
                    firstNonBlank(rse.getReason(), "Operação recusada pelo serviço de domínio."));
        }
        if (cause instanceof SecurityException
                || className.endsWith(".AccessDeniedException")
                || className.endsWith(".AuthorizationDeniedException")) {
            return failureOutcome(403, "ACCESS_DENIED", "Autoridade insuficiente para executar esta operação.");
        }
        if (cause instanceof jakarta.validation.ConstraintViolationException cve) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            if (cve.getConstraintViolations() != null) {
                for (var v : cve.getConstraintViolations()) {
                    String path = v.getPropertyPath() != null && StringUtils.hasText(v.getPropertyPath().toString())
                            ? v.getPropertyPath().toString()
                            : "_global";
                    fieldErrors.putIfAbsent(path, v.getMessage());
                }
            }
            return new AgentActionExecutionOutcome(
                    422, false, "VALIDATION_FAILED", "Existem campos inválidos ou ausentes na confirmação.",
                    null, null, Map.of(), Map.of(), List.of(), Map.copyOf(fieldErrors), Map.of());
        }
        log.warn("[MetadataAgentBridge] Action execution failure on {}.{}: {}",
                res.descriptor().entityKey(), canonicalId, cause.getMessage(), cause);
        return failureOutcome(422, "EXECUTION_REJECTED",
                firstNonBlank(cause.getMessage(), "Falha ao executar a ação governada."));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AgentActionExecutionOutcome buildStaleOutcome(
            AgentExecutionContext context,
            RegisteredResource res,
            Object convertedId,
            String canonicalId,
            String note) {
        Map<String, Object> snapshotMap = new LinkedHashMap<>();
        String freshEtag = null;
        String freshState = null;
        if (convertedId != null) {
            try {
                ResourceRepresentationResult<?> current = executeIsolatedReadOnly(
                        () -> ((BaseResourceQueryService) res.service()).findById(convertedId));
                if (current != null && current.body() != null) {
                    Map<String, Object> raw = toMap(res, current.body());
                    DualProjectedPayload projected = authorizationBridge.projectRecord(
                            context, res.descriptor().resourcePath(), res.responseDtoType(), raw);
                    ResourceStateSnapshot stateSnap = resolveItemStateSnapshot(res, convertedId);
                    freshState = BridgeActionReflectionSupport.extractLifecycleState(raw, stateSnap);
                    if (current.persistedVersion().isPresent() && resourceVersionEtagService != null
                            && StringUtils.hasText(res.descriptor().resourceKey())) {
                        freshEtag = resourceVersionEtagService.create(
                                currentResourceVersionScope(),
                                res.descriptor().resourceKey(),
                                convertedId,
                                current.persistedVersion().getAsLong());
                    }
                    snapshotMap.put("entityId", canonicalId);
                    if (freshEtag != null) snapshotMap.put("expectedVersionTag", freshEtag);
                    if (freshState != null) snapshotMap.put("recordLifecycleState", freshState);
                    snapshotMap.put("record", projected.uiRecord());
                }
            } catch (Exception ex) {
                log.debug("[MetadataAgentBridge] Unable to refresh snapshot on stale precondition: {}", ex.getMessage());
            }
        }
        return new AgentActionExecutionOutcome(
                412,
                false,
                "STALE_RESOURCE_VERSION",
                note,
                freshEtag,
                freshState,
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                snapshotMap);
    }

    private AgentActionExecutionOutcome mapNon2xxEntityOutcome(int status, Object body) {
        String note = "Operação recusada pelo serviço (HTTP " + status + ").";
        List<Map<String, Object>> violations = new ArrayList<>();
        if (body instanceof RestApiResponse<?> api && StringUtils.hasText(api.getMessage())) {
            note = api.getMessage();
        } else if (body instanceof ProblemDetail pd) {
            if (StringUtils.hasText(pd.getDetail())) {
                note = pd.getDetail();
            }
            if (pd.getProperties() != null && pd.getProperties().get("violations") instanceof List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        violations.add(copy);
                    }
                }
            }
        }
        return new AgentActionExecutionOutcome(
                status,
                false,
                "HTTP_" + status,
                note,
                null,
                null,
                Map.of(),
                Map.of(),
                List.copyOf(violations),
                Map.of(),
                Map.of());
    }

    private static AgentActionExecutionOutcome failureOutcome(int status, String code, String note) {
        return new AgentActionExecutionOutcome(
                status, false, code, note, null, null, Map.of(), Map.of(), List.of(), Map.of(), Map.of());
    }

    private DeterminationEndpoint resolveDeterminationEndpoint(RegisteredResource res, String determinationId) {
        List<DeterminationEndpoint> endpoints = res.determinations();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException(
                    "Recurso " + res.descriptor().entityKey() + " não possui endpoints /determinations/* declarados.");
        }
        if (!StringUtils.hasText(determinationId)) {
            if (endpoints.size() == 1) {
                return endpoints.get(0);
            }
            throw new IllegalArgumentException("determinationId é obrigatório quando há múltiplas determinações: "
                    + endpoints.stream().map(DeterminationEndpoint::determinationId).toList());
        }
        String needle = determinationId.trim().toLowerCase(Locale.ROOT);
        for (DeterminationEndpoint ep : endpoints) {
            if (ep.determinationId().equalsIgnoreCase(needle) || ep.fullPath().toLowerCase(Locale.ROOT).endsWith("/" + needle)) {
                return ep;
            }
        }
        throw new IllegalArgumentException("Determinação não encontrada neste recurso: " + determinationId);
    }

    private MutatingActionEndpoint resolveMutatingEndpoint(RegisteredResource res, String actionId) {
        String needle = actionId.trim().toLowerCase(Locale.ROOT);
        for (MutatingActionEndpoint ep : res.mutatingActions()) {
            if (ep.matchesActionId(needle)) {
                return ep;
            }
        }
        throw new IllegalArgumentException("Ação ou intenção de edição não encontrada neste recurso: " + actionId);
    }

    private Object invokeDeterminationEndpoint(
            RegisteredResource res,
            DeterminationEndpoint endpoint,
            Object convertedDraft) {
        Method m = endpoint.method();
        Object[] args = new Object[m.getParameterCount()];
        if (endpoint.requestBodyIndex() >= 0) {
            args[endpoint.requestBodyIndex()] = convertedDraft;
        }
        ReflectionUtils.makeAccessible(m);
        try {
            return m.invoke(res.controller(), args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException() != null ? ex.getTargetException() : ex;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Falha ao simular determinação: " + cause.getMessage(), cause);
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao invocar endpoint de determinação: " + ex.getMessage(), ex);
        }
    }

    private static Object unwrapControllerResult(Object result) {
        Object current = result;
        if (current instanceof ResponseEntity<?> entity) {
            current = entity.getBody();
        }
        if (current instanceof RestApiResponse<?> api) {
            current = api.getData();
        }
        return current;
    }

    private ResourceVersionScope currentResourceVersionScope() {
        ResourceVersionScope scope = resourceVersionScopeProvider != null
                ? resourceVersionScopeProvider.currentScope()
                : ResourceVersionScope.GLOBAL;
        return scope != null ? scope : ResourceVersionScope.GLOBAL;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> loadRawRecordIsolated(RegisteredResource res, Object convertedId) {
        if (convertedId == null) {
            return Map.of();
        }
        return executeIsolatedReadOnly(() -> {
            ResourceRepresentationResult<?> found = ((BaseResourceQueryService) res.service()).findById(convertedId);
            if (found == null || found.body() == null) {
                return null;
            }
            return toMap(res, found.body());
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OptionalLong loadLatestVersionIsolated(RegisteredResource res, Object convertedId) {
        try {
            return executeIsolatedReadOnly(() -> {
                ResourceRepresentationResult<?> found = ((BaseResourceQueryService) res.service()).findById(convertedId);
                return found != null ? found.persistedVersion() : OptionalLong.empty();
            });
        } catch (Exception ex) {
            return OptionalLong.empty();
        }
    }

    private ActionGuardAssessment evaluatePreparationContributors(
            AgentExecutionContext context,
            AgentEntityDescriptor entity,
            String entityId,
            String actionId,
            Map<String, Object> authoritativeRecord,
            Map<String, Object> proposedInputs) {
        if (preparationContributors.isEmpty()) {
            return null;
        }
        String orgId = null;
        String orgLabel = null;
        Map<String, Object> mergedPrefill = new LinkedHashMap<>();
        for (AgentActionPreparationContributor contributor : preparationContributors) {
            ActionGuardAssessment assessment = contributor.assessAction(
                    context, entity, entityId, actionId, authoritativeRecord, proposedInputs);
            if (assessment == null) {
                continue;
            }
            if (StringUtils.hasText(assessment.organizationId())) {
                orgId = assessment.organizationId().trim();
            }
            if (StringUtils.hasText(assessment.organizationLabel())) {
                orgLabel = assessment.organizationLabel().trim();
            }
            if (!assessment.enrichedPrefill().isEmpty()) {
                mergedPrefill.putAll(assessment.enrichedPrefill());
            }
            if (!assessment.allowed()) {
                return new ActionGuardAssessment(
                        false,
                        assessment.blockedCode(),
                        assessment.impedimentNote(),
                        orgId,
                        orgLabel,
                        mergedPrefill);
            }
        }
        return ActionGuardAssessment.allow(orgId, orgLabel, mergedPrefill);
    }

    private String stringifyEntityId(Object convertedId, String fallback) {
        if (convertedId == null) {
            return fallback;
        }
        if (CompositeIdSupport.isCompositeIdValue(convertedId)) {
            return CompositeIdSupport.encode(convertedId);
        }
        return String.valueOf(convertedId);
    }

    private static String resolveSubmitUrl(String resourcePath, String relativePath, String canonicalId) {
        String resolvedRel = relativePath == null ? "" : relativePath;
        if (StringUtils.hasText(canonicalId)) {
            resolvedRel = resolvedRel.replace("{id}", canonicalId);
        }
        return BridgeActionReflectionSupport.combinePath(resourcePath, resolvedRel);
    }

    private static String buildFilteredSchemaUrl(String templatePath, String httpMethod) {
        String method = StringUtils.hasText(httpMethod) ? httpMethod.trim().toLowerCase(Locale.ROOT) : "post";
        String encodedPath = URLEncoder.encode(templatePath, StandardCharsets.UTF_8);
        return "/schemas/filtered?path=" + encodedPath + "&operation=" + method + "&schemaType=request";
    }

    private <T> T executeRollbackOnlyRead(Supplier<T> supplier) {
        if (rollbackOnlyReadTemplate == null) {
            throw new IllegalStateException("Non-persistent simulation requires a configured PlatformTransactionManager.");
        }
        return rollbackOnlyReadTemplate.execute(status -> {
            status.setRollbackOnly();
            return supplier.get();
        });
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

        Object convertedId = String.class.equals(res.idType()) && query.indexOf(' ') >= 0
                ? null
                : tryConvertId(query, res.idType());
        if (convertedId != null) {
            try {
                ResourceRepresentationResult<?> found = executeIsolatedReadOnly(() -> service.findById(convertedId));
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
            return executeIsolatedReadOnly(() -> service.filter(filter, PageRequest.of(0, maxResults, defaultSort), null));
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
        GenericFilterDTO filterDto = populateFilterDto(res, filters);
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
                collectionActions,
                toAgentFieldDescriptors(res.responseFields()));
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
            representation = executeIsolatedReadOnly(() -> service.findById(convertedId));
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

        AtomicReference<ResourceStateSnapshot> snapshotCache = new AtomicReference<>();
        List<AgentActionAffordance> actions = resolveItemActions(
                context, res, convertedId, canonicalId, projected.uiRecord(), snapshotCache);
        List<AgentSurfaceSummary> surfaces = resolveItemSurfaces(
                context, res, convertedId, canonicalId, snapshotCache);
        List<AgentRelatedDataset> relatedDatasets = loadRelatedDatasets(
                context, res, canonicalId, surfaces, includeRelatedSurfaces);
        List<AgentProactiveAlert> alerts = evaluateAlerts(context, res.descriptor().entityKey(), canonicalId, projected.uiRecord());
        AgentEvaluatedCalculationMemory calculationMemory = evaluateCalculationMemoryForRecord(
                res, canonicalId, displayTitle, projected);

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
                surfaces,
                toAgentFieldDescriptors(res.responseFields()),
                calculationMemory));
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
                    modelSafeRows,
                    toAgentFieldDescriptors(child.responseFields())));
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
                try {
                    List<AgentProactiveAlert> found = executeIsolatedReadOnly(() -> contributor.evaluateAlerts(
                            context, entityKey, entityId, authoritativeRecord));
                    if (found != null) {
                        alerts.addAll(found);
                    }
                } catch (Exception ex) {
                    log.debug("Ignorando falha em AgentProactiveAlertContributor {}: {}",
                            contributor.getClass().getSimpleName(), ex.getMessage());
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
            Map<String, Object> uiRecord,
            AtomicReference<ResourceStateSnapshot> snapshotCache) {
        List<AgentActionAffordance> actions = new ArrayList<>();
        String resourceKey = res.descriptor().resourceKey();
        String resourcePath = res.descriptor().resourcePath();
        ResourceStateSnapshot stateSnapshot = snapshotCache != null ? snapshotCache.get() : null;
        boolean addedCatalogActions = false;
        Set<String> seenActionIds = new LinkedHashSet<>();
        if (actionCatalogService != null && StringUtils.hasText(resourceKey)) {
            ActionCatalogResponse response = tryMetadataLookup("findItemActions:" + resourceKey,
                    () -> actionCatalogService.findItemActions(resourceKey, convertedId));
            if (response != null && response.actions() != null) {
                addedCatalogActions = true;
                if (stateSnapshot == null) {
                    stateSnapshot = resolveItemStateSnapshot(res, convertedId);
                    if (snapshotCache != null) {
                        snapshotCache.compareAndSet(null, stateSnapshot);
                    }
                }
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
                    seenActionIds.add(item.id().toLowerCase(Locale.ROOT));
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
                            !authorized ? "Permissão insuficiente" : null,
                            item.order()));
                }
            }
        }
        if (!addedCatalogActions && res.mutatingActions() != null && !res.mutatingActions().isEmpty()) {
            if (stateSnapshot == null) {
                stateSnapshot = resolveItemStateSnapshot(res, convertedId);
                if (snapshotCache != null) {
                    snapshotCache.compareAndSet(null, stateSnapshot);
                }
            }
            String lifecycleState = BridgeActionReflectionSupport.extractLifecycleState(uiRecord, stateSnapshot);
            for (MutatingActionEndpoint ep : res.mutatingActions()) {
                if ("edit".equalsIgnoreCase(ep.actionId()) || ep.scope() != ActionScope.ITEM) {
                    continue;
                }
                if (!seenActionIds.add(ep.actionId().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (!ep.allowedStates().isEmpty() && StringUtils.hasText(lifecycleState)
                        && ep.allowedStates().stream().noneMatch(s -> s.equalsIgnoreCase(lifecycleState))) {
                    continue;
                }
                if (stateSnapshot != null && stateSnapshot.metadata() != null
                        && Boolean.FALSE.equals(stateSnapshot.metadata().get("action." + ep.actionId() + ".allowed"))) {
                    continue;
                }
                boolean authorized = authorizationBridge.canInvokeAction(context, resourcePath, ep.actionId(), convertedId);
                boolean hasForm = ep.requestDtoType() != null && !ep.inputFields().isEmpty();
                String submitPath = resolveSubmitUrl(resourcePath, ep.relativePath(), canonicalId);
                String schemaUrl = hasForm
                        ? buildFilteredSchemaUrl(
                                BridgeActionReflectionSupport.combinePath(resourcePath, ep.relativePath()),
                                ep.httpMethod())
                        : null;
                actions.add(new AgentActionAffordance(
                        ep.actionId(),
                        ep.title(),
                        ep.description(),
                        "partial-patch".equals(ep.operationKind()) ? "tune" : "bolt",
                        "partial-patch".equals(ep.operationKind()) ? "neutral" : "primary",
                        resourceKey,
                        resourcePath,
                        canonicalId,
                        ep.operationKind(),
                        hasForm ? ("partial-patch".equals(ep.operationKind()) ? "drawer" : "dialog") : "confirm-only",
                        ep.httpMethod(),
                        submitPath,
                        schemaUrl,
                        null,
                        Map.of(),
                        Map.of(),
                        true,
                        !authorized,
                        !authorized ? "Permissão insuficiente" : null,
                        ep.order()));
            }
        }
        boolean allowOpenApiCapabilityLookup = capabilityService != null
                && StringUtils.hasText(resourceKey)
                && (hasActiveHttpRequest() || surfaceDefinitionRegistry == null);
        if (allowOpenApiCapabilityLookup) {
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
                                "Abre o formulário de edição governado deste registro",
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
        } else if (res.service() instanceof BaseResourceCommandService<?, ?, ?, ?>
                && authorizationBridge.canInvokeAction(context, resourcePath, "update", convertedId)) {
            if (stateSnapshot == null) {
                stateSnapshot = resolveItemStateSnapshot(res, convertedId);
            }
            boolean stateAllowed = stateSnapshot == null
                    || stateSnapshot.metadata() == null
                    || (!Boolean.FALSE.equals(stateSnapshot.metadata().get("operation.update.allowed"))
                            && !Boolean.FALSE.equals(stateSnapshot.metadata().get("surface.edit.allowed")));
            if (stateAllowed) {
                actions.add(new AgentActionAffordance(
                        "edit",
                        "Editar registro",
                        "Abre o formulário de edição governado deste registro",
                        "edit",
                        "neutral",
                        resourceKey,
                        resourcePath,
                        canonicalId,
                        "edit",
                        "drawer",
                        "PUT",
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
        boolean addedCollectionCatalog = false;
        if (actionCatalogService != null && StringUtils.hasText(resourceKey)) {
            ActionCatalogResponse response = tryMetadataLookup("findCollectionActions:" + resourceKey,
                    () -> actionCatalogService.findCollectionActions(resourceKey));
            if (response != null && response.actions() != null) {
                addedCollectionCatalog = true;
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
                            !authorized ? "Permissão insuficiente" : null,
                            item.order()));
                }
            }
        }
        if (!addedCollectionCatalog && res.mutatingActions() != null && !res.mutatingActions().isEmpty()) {
            for (MutatingActionEndpoint ep : res.mutatingActions()) {
                if (ep.scope() != ActionScope.COLLECTION) {
                    continue;
                }
                boolean authorized = authorizationBridge.canInvokeAction(context, resourcePath, ep.actionId(), null);
                boolean hasForm = ep.requestDtoType() != null && !ep.inputFields().isEmpty();
                String submitPath = resolveSubmitUrl(resourcePath, ep.relativePath(), null);
                String schemaUrl = hasForm ? buildFilteredSchemaUrl(submitPath, ep.httpMethod()) : null;
                actions.add(new AgentActionAffordance(
                        ep.actionId(),
                        ep.title(),
                        ep.description(),
                        "playlist_play",
                        "accent",
                        resourceKey,
                        resourcePath,
                        null,
                        "workflow-action",
                        hasForm ? "dialog" : "confirm-only",
                        ep.httpMethod(),
                        submitPath,
                        schemaUrl,
                        null,
                        Map.of(),
                        Map.of(),
                        true,
                        !authorized,
                        !authorized ? "Permissão insuficiente" : null,
                        ep.order()));
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
            String canonicalId,
            AtomicReference<ResourceStateSnapshot> snapshotCache) {
        String resourceKey = res.descriptor().resourceKey();
        if (!StringUtils.hasText(resourceKey)) {
            return List.of();
        }
        if (!hasActiveHttpRequest() && surfaceDefinitionRegistry != null) {
            List<SurfaceDefinition> definitions = surfaceDefinitionRegistry.findByResourceKey(resourceKey);
            if (definitions == null || definitions.isEmpty()) {
                return List.of();
            }
            ResourceStateSnapshot stateSnapshot = snapshotCache != null ? snapshotCache.get() : null;
            if (stateSnapshot == null) {
                stateSnapshot = resolveItemStateSnapshot(res, convertedId);
                if (snapshotCache != null) {
                    snapshotCache.compareAndSet(null, stateSnapshot);
                }
            }
            List<AgentSurfaceSummary> summaries = new ArrayList<>();
            for (SurfaceDefinition def : definitions) {
                if (def.scope() != SurfaceScope.ITEM) {
                    continue;
                }
                summaries.add(toSurfaceSummary(def, context, stateSnapshot, canonicalId));
            }
            summaries.sort(Comparator.comparingInt(AgentSurfaceSummary::order));
            return summaries;
        }
        if (surfaceCatalogService == null) {
            return List.of();
        }
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
        String resourceKey = res.descriptor().resourceKey();
        if (StringUtils.hasText(resourceKey)) {
            if (!hasActiveHttpRequest() && surfaceDefinitionRegistry != null) {
                List<SurfaceDefinition> definitions = surfaceDefinitionRegistry.findByResourceKey(resourceKey);
                if (definitions != null && !definitions.isEmpty()) {
                    resourceSurfaces = definitions.stream()
                            .map(def -> toSurfaceSummary(def, context, null, null))
                            .sorted(Comparator.comparingInt(AgentSurfaceSummary::order))
                            .toList();
                }
            } else if (surfaceCatalogService != null) {
                SurfaceCatalogResponse surf = tryMetadataLookup("findByResourceKey:" + resourceKey,
                        () -> surfaceCatalogService.findByResourceKey(resourceKey));
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
        }
        AgentEntityDescriptor d = res.descriptor();
        StatsCapabilityDigest statsCapability = extractStatsCapability(context, res);
        List<QuickFilterSummary> quickFilters = extractQuickFilters(res);
        List<ResourceFactSummary> resourceFacts = extractResourceFacts(context, res);
        List<String> calculationTrailIds = extractCalculationTrailIds(res.responseDtoType());
        List<DeterminationSummary> determinations = res.determinations() == null
                ? List.of()
                : res.determinations().stream().map(DeterminationEndpoint::toSummary).toList();
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
                resourceSurfaces,
                statsCapability,
                quickFilters,
                resourceFacts,
                !calculationTrailIds.isEmpty(),
                calculationTrailIds,
                determinations);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AgentStatsResult aggregateStatsInternal(
            AgentExecutionContext context,
            RegisteredResource res,
            AgentStatsAggregationRequest request) {
        BaseResourceQueryService service = res.service();
        StatsFieldRegistry registry = tryMetadataLookup("statsRegistry:" + res.descriptor().entityKey(),
                service::getStatsFieldRegistry);
        if (registry == null || registry.isEmpty()) {
            return new AgentStatsResult(
                    res.descriptor().entityKey(),
                    res.descriptor().resourceKey(),
                    res.descriptor().resourcePath(),
                    firstNonBlank(request.title(), res.descriptor().title()),
                    res.descriptor().description(),
                    resolveOperationKind(request.operationKind()),
                    "bar",
                    request.field(),
                    request.field(),
                    "COUNT",
                    null,
                    request.granularity(),
                    0L,
                    List.of(),
                    "Este recurso não declarou dimensões estatísticas habilitadas.");
        }

        Set<String> blockedFields = findBlockedStatsFields(context, res, registry);
        if (StringUtils.hasText(request.field())
                && blockedFields.contains(request.field().trim().toLowerCase(Locale.ROOT))) {
            throw new SecurityException("Stats field restricted by governance policy: " + request.field().trim());
        }

        StatsSupportMode groupMode = safeStatsMode(service::getGroupByStatsSupportMode);
        StatsSupportMode tsMode = safeStatsMode(service::getTimeSeriesStatsSupportMode);
        StatsSupportMode distMode = safeStatsMode(service::getDistributionStatsSupportMode);

        String operationKind = resolveOperationKind(request.operationKind());
        StatsFieldDescriptor targetDescriptor = resolveTargetStatsField(
                registry, operationKind, request.field(), blockedFields);
        if (targetDescriptor == null) {
            throw new IllegalArgumentException(
                    "Nenhum campo estatístico elegível encontrado para " + operationKind
                            + (StringUtils.hasText(request.field()) ? " (campo=" + request.field() + ")" : ""));
        }

        if ("GROUP_BY".equals(operationKind) && groupMode == StatsSupportMode.DISABLED) {
            throw new IllegalArgumentException("Agregação GROUP_BY desabilitada neste recurso.");
        }
        if ("TIMESERIES".equals(operationKind) && tsMode == StatsSupportMode.DISABLED) {
            throw new IllegalArgumentException("Agregação TIMESERIES desabilitada neste recurso.");
        }
        if ("DISTRIBUTION".equals(operationKind) && distMode == StatsSupportMode.DISABLED) {
            throw new IllegalArgumentException("Agregação DISTRIBUTION desabilitada neste recurso.");
        }

        StatsMetric metricOp = resolveMetricOperation(request.metricOperation(), targetDescriptor);
        String metricField = trimToNull(request.metricField());
        if (metricOp != StatsMetric.COUNT && metricOp != StatsMetric.DISTINCT_COUNT) {
            if (StringUtils.hasText(metricField)
                    && blockedFields.contains(metricField.toLowerCase(Locale.ROOT))) {
                throw new SecurityException("Stats metricField restricted by governance policy: " + metricField);
            }
            if (!StringUtils.hasText(metricField)) {
                metricField = registry.descriptors().stream()
                        .filter(StatsFieldDescriptor::metricFieldEligible)
                        .filter(d -> !blockedFields.contains(d.field().toLowerCase(Locale.ROOT)))
                        .map(StatsFieldDescriptor::field)
                        .findFirst()
                        .orElse(targetDescriptor.metricFieldEligible()
                                && !blockedFields.contains(targetDescriptor.field().toLowerCase(Locale.ROOT))
                                        ? targetDescriptor.field()
                                        : null);
            }
            if (StringUtils.hasText(metricField)) {
                String checkField = metricField;
                StatsFieldDescriptor metricDesc = registry.resolve(checkField)
                        .orElseThrow(() -> new IllegalArgumentException("metricField inválido para stats: " + checkField));
                if (!metricDesc.metricFieldEligible()) {
                    throw new IllegalArgumentException("Campo não elegível como métrica numérica: " + checkField);
                }
            }
        } else if (metricOp == StatsMetric.COUNT) {
            metricField = null;
        } else if (StringUtils.hasText(metricField)
                && blockedFields.contains(metricField.toLowerCase(Locale.ROOT))) {
            throw new SecurityException("Stats metricField restricted by governance policy: " + metricField);
        }
        StatsMetricRequest metricRequest = new StatsMetricRequest(metricOp, metricField);

        GenericFilterDTO filterDto = populateFilterDto(res, request.filters());
        List<AgentStatsBucket> buckets = new ArrayList<>();
        String granularityUsed = trimToNull(request.granularity());

        if ("TIMESERIES".equals(operationKind)) {
            TimeSeriesGranularity gran = parseTimeSeriesGranularity(request.granularity());
            granularityUsed = gran.name();
            TimeSeriesStatsResponse tsResponse = service.timeSeriesStats(
                    new TimeSeriesStatsRequest<>(filterDto, targetDescriptor.field(), gran, metricRequest, null, null, Boolean.TRUE));
            if (tsResponse != null && tsResponse.points() != null) {
                for (TimeSeriesPoint pt : tsResponse.points()) {
                    String key = pt.start() != null ? pt.start().toString() : firstNonBlank(pt.label(), "");
                    String label = firstNonBlank(pt.label(), key);
                    buckets.add(new AgentStatsBucket(
                            key,
                            label,
                            toBigDecimal(pt.value(), pt.count()),
                            pt.count(),
                            pt.start() != null ? pt.start().toString() : null,
                            pt.end() != null ? pt.end().toString() : null));
                }
            }
        } else if ("DISTRIBUTION".equals(operationKind)) {
            DistributionMode distModeReq = "HISTOGRAM".equalsIgnoreCase(request.distributionMode())
                    || (targetDescriptor.distributionHistogramEligible() && !targetDescriptor.distributionTermsEligible())
                    ? DistributionMode.HISTOGRAM
                    : DistributionMode.TERMS;
            DistributionStatsResponse distResponse = service.distributionStats(
                    new DistributionStatsRequest<>(
                            filterDto,
                            targetDescriptor.field(),
                            distModeReq,
                            metricRequest,
                            null,
                            null,
                            request.limit(),
                            StatsBucketOrder.VALUE_DESC));
            if (distResponse != null && distResponse.buckets() != null) {
                for (DistributionBucket b : distResponse.buckets()) {
                    String key = b.key() != null ? String.valueOf(b.key()) : firstNonBlank(b.label(), "");
                    String label = firstNonBlank(b.label(), key);
                    buckets.add(new AgentStatsBucket(
                            key,
                            label,
                            toBigDecimal(b.value(), b.count()),
                            b.count(),
                            b.from() != null ? String.valueOf(b.from()) : null,
                            b.to() != null ? String.valueOf(b.to()) : null));
                }
            }
        } else {
            GroupByStatsResponse gbResponse = service.groupByStats(
                    new GroupByStatsRequest<>(
                            filterDto,
                            targetDescriptor.field(),
                            metricRequest,
                            request.limit(),
                            StatsBucketOrder.VALUE_DESC));
            if (gbResponse != null && gbResponse.buckets() != null) {
                for (GroupByBucket b : gbResponse.buckets()) {
                    String key = b.key() != null ? String.valueOf(b.key()) : firstNonBlank(b.label(), "N/D");
                    String label = resolveBucketLabel(res, targetDescriptor.field(), key, b.label());
                    buckets.add(new AgentStatsBucket(
                            key,
                            label,
                            toBigDecimal(b.value(), b.count()),
                            b.count(),
                            null,
                            null));
                }
            }
        }

        long totalElements = buckets.stream().mapToLong(AgentStatsBucket::count).sum();
        String fieldLabel = resolveFieldLabel(res, targetDescriptor.field());
        String chartKind = inferChartKind(request.chartKind(), operationKind, metricOp, buckets.size());
        String title = firstNonBlank(request.title(), res.descriptor().title() + " — " + fieldLabel);
        String subtitle = formatStatsSubtitlePtBr(res, metricOp, metricField, fieldLabel);
        String emptyReason = buckets.isEmpty() ? "Nenhum registro encontrado para os filtros informados." : null;

        return new AgentStatsResult(
                res.descriptor().entityKey(),
                res.descriptor().resourceKey(),
                res.descriptor().resourcePath(),
                title,
                subtitle,
                operationKind,
                chartKind,
                targetDescriptor.field(),
                fieldLabel,
                metricOp.name(),
                metricField,
                granularityUsed,
                totalElements,
                buckets,
                emptyReason);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<AgentEvaluatedCalculationMemory> evaluateCalculationMemoryInternal(
            AgentExecutionContext context,
            RegisteredResource res,
            String entityId,
            Map<String, Object> filters,
            int maxRecords) {
        Map<String, Object> calcMeta = CalculationMemoryMetadataResolver.resolve(res.responseDtoType());
        if (calcMeta == null || calcMeta.isEmpty()) {
            return List.of();
        }
        BaseResourceQueryService service = res.service();
        List<AgentEvaluatedCalculationMemory> results = new ArrayList<>();

        if (StringUtils.hasText(entityId)) {
            Object convertedId = tryConvertId(entityId, res.idType());
            if (convertedId == null) {
                return List.of();
            }
            try {
                ResourceRepresentationResult<?> representation = executeIsolatedReadOnly(() -> service.findById(convertedId));
                if (representation != null && representation.body() != null) {
                    evaluateAndAddCalculationMemory(context, res, calcMeta, representation.body(), results);
                }
            } catch (Exception ignored) {
                return List.of();
            }
            return results;
        }

        GenericFilterDTO filterDto = populateFilterDto(res, filters);
        Page<?> page = service.filter(filterDto, PageRequest.of(0, maxRecords, service.getDefaultSort()), null);
        if (page != null && page.getContent() != null) {
            for (Object dto : page.getContent()) {
                evaluateAndAddCalculationMemory(context, res, calcMeta, dto, results);
            }
        }
        return results;
    }

    private void evaluateAndAddCalculationMemory(
            AgentExecutionContext context,
            RegisteredResource res,
            Map<String, Object> calcMeta,
            Object dto,
            List<AgentEvaluatedCalculationMemory> out) {
        DualProjectedPayload projected = authorizationBridge.projectRecord(
                context,
                res.descriptor().resourcePath(),
                res.responseDtoType(),
                toMap(res, dto));
        String canonicalId = firstNonBlank(extractIdString(res, dto), "");
        String displayTitle = extractDisplayField(projected.uiRecord(), res.descriptor().titleField(), canonicalId);
        AgentEvaluatedCalculationMemory evaluated = AgentCalculationMemoryEvaluator.evaluate(
                res.descriptor(),
                canonicalId,
                displayTitle,
                calcMeta,
                projected);
        if (evaluated != null) {
            out.add(evaluated);
        }
    }

    private AgentEvaluatedCalculationMemory evaluateCalculationMemoryForRecord(
            RegisteredResource res,
            String canonicalId,
            String displayTitle,
            DualProjectedPayload projected) {
        Map<String, Object> calcMeta = CalculationMemoryMetadataResolver.resolve(res.responseDtoType());
        if (calcMeta == null || calcMeta.isEmpty()) {
            return null;
        }
        return AgentCalculationMemoryEvaluator.evaluate(
                res.descriptor(),
                canonicalId,
                displayTitle,
                calcMeta,
                projected);
    }

    private GenericFilterDTO populateFilterDto(RegisteredResource res, Map<String, Object> filters) {
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
        return filterDto;
    }

    private StatsCapabilityDigest extractStatsCapability(AgentExecutionContext context, RegisteredResource res) {
        StatsFieldRegistry registry = tryMetadataLookup("statsDigest:" + res.descriptor().entityKey(),
                () -> res.service().getStatsFieldRegistry());
        if (registry == null || registry.isEmpty()) {
            return null;
        }
        StatsSupportMode gMode = safeStatsMode(() -> res.service().getGroupByStatsSupportMode());
        StatsSupportMode tsMode = safeStatsMode(() -> res.service().getTimeSeriesStatsSupportMode());
        StatsSupportMode distMode = safeStatsMode(() -> res.service().getDistributionStatsSupportMode());
        org.praxisplatform.uischema.stats.StatsCapability cap =
                org.praxisplatform.uischema.stats.StatsCapability.from(registry, gMode, tsMode, distMode);
        if (cap.fields().isEmpty()) {
            return null;
        }
        Set<String> blockedFields = findBlockedStatsFields(context, res, registry);
        List<StatsFieldDigest> fields = cap.fields().stream()
                .filter(fc -> !blockedFields.contains(fc.field().toLowerCase(Locale.ROOT)))
                .map(fc -> new StatsFieldDigest(
                        fc.field(),
                        firstNonBlank(fc.label(), resolveFieldLabel(res, fc.field())),
                        fc.metrics(),
                        fc.groupByEligible(),
                        fc.timeSeriesEligible(),
                        fc.distributionTermsEligible(),
                        fc.distributionHistogramEligible(),
                        fc.metricFieldEligible()))
                .toList();
        if (fields.isEmpty()) {
            return null;
        }
        return new StatsCapabilityDigest(gMode.name(), tsMode.name(), distMode.name(), fields);
    }

    private Set<String> findBlockedStatsFields(
            AgentExecutionContext context,
            RegisteredResource res,
            StatsFieldRegistry registry) {
        Map<String, Object> probeMap = new LinkedHashMap<>();
        for (ResponseFieldMeta meta : res.responseFields()) {
            if (StringUtils.hasText(meta.name())) {
                probeMap.put(meta.name(), Boolean.TRUE);
            }
        }
        if (registry != null) {
            for (StatsFieldDescriptor desc : registry.descriptors()) {
                if (desc != null && StringUtils.hasText(desc.field())) {
                    probeMap.putIfAbsent(desc.field(), Boolean.TRUE);
                }
            }
        }
        Set<String> blocked = new LinkedHashSet<>();
        if (!probeMap.isEmpty()) {
            DualProjectedPayload projected = authorizationBridge.projectRecord(
                    context,
                    res.descriptor().resourcePath(),
                    res.responseDtoType(),
                    probeMap);
            if (projected != null && projected.hiddenFields() != null) {
                for (String hidden : projected.hiddenFields()) {
                    if (StringUtils.hasText(hidden)) {
                        blocked.add(hidden.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        for (ResponseFieldMeta meta : res.responseFields()) {
            if ("deny".equalsIgnoreCase(meta.aiVisibility()) || "mask".equalsIgnoreCase(meta.aiVisibility())) {
                blocked.add(meta.name().trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(blocked);
    }

    private static String formatStatsSubtitlePtBr(
            RegisteredResource res,
            StatsMetric metricOp,
            String metricField,
            String fieldLabel) {
        String opLabel = switch (metricOp) {
            case DISTINCT_COUNT -> "Contagem distinta";
            case SUM -> "Soma";
            case AVG -> "Média";
            case MIN -> "Mínimo";
            case MAX -> "Máximo";
            default -> "Quantidade";
        };
        StringBuilder sb = new StringBuilder(opLabel);
        if (StringUtils.hasText(metricField)) {
            sb.append(" (").append(firstNonBlank(resolveFieldLabel(res, metricField), metricField)).append(')');
        }
        if (StringUtils.hasText(fieldLabel)) {
            sb.append(" por ").append(fieldLabel);
        }
        return sb.toString();
    }

    private List<QuickFilterSummary> extractQuickFilters(RegisteredResource res) {
        try {
            Class<?> targetControllerClass = AopUtils.getTargetClass(res.controller());
            List<Map<String, Object>> resolved = ApiResourceQuickFilterResolver.resolveControllerQuickFilters(
                    targetControllerClass, res.controller(), res.filterDtoType());
            if (resolved == null || resolved.isEmpty()) {
                return List.of();
            }
            List<QuickFilterSummary> out = new ArrayList<>(resolved.size());
            for (Map<String, Object> item : resolved) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                String id = item.get("id") != null ? item.get("id").toString() : null;
                String label = item.get("label") != null ? item.get("label").toString() : id;
                if (!StringUtils.hasText(id) && !StringUtils.hasText(label)) {
                    continue;
                }
                out.add(new QuickFilterSummary(
                        id,
                        label,
                        item.get("description") != null ? item.get("description").toString() : null,
                        item.get("category") != null ? item.get("category").toString() : null));
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            log.debug("[AgentBridge] Quick filter introspection failed for '{}': {}", res.descriptor().entityKey(), ex.getMessage());
            return List.of();
        }
    }

    private List<ResourceFactSummary> extractResourceFacts(AgentExecutionContext context, RegisteredResource res) {
        String resourceKey = res.descriptor().resourceKey();
        if (!StringUtils.hasText(resourceKey)) {
            return List.of();
        }
        Map<String, ResourceFactSummary> byKey = new LinkedHashMap<>();
        String tenantId = context != null ? context.tenantId() : null;
        for (ResourceFactCatalogProvider provider : factCatalogProviders) {
            try {
                List<ResourceFactDescriptor> found = provider.findFacts(resourceKey, tenantId);
                if (found != null) {
                    for (ResourceFactDescriptor desc : found) {
                        if (desc != null && StringUtils.hasText(desc.key())) {
                            byKey.putIfAbsent(desc.key(), new ResourceFactSummary(
                                    desc.key(), desc.label(), desc.dataType(), desc.description(), desc.authority()));
                        }
                    }
                }
            } catch (Exception ex) {
                log.debug("[AgentBridge] Fact provider lookup skipped for '{}': {}", resourceKey, ex.getMessage());
            }
        }
        collectAnnotatedFacts(AopUtils.getTargetClass(res.controller()), byKey);
        collectAnnotatedFacts(res.responseDtoType(), byKey);
        return List.copyOf(byKey.values());
    }

    private static void collectAnnotatedFacts(Class<?> target, Map<String, ResourceFactSummary> out) {
        if (target == null) {
            return;
        }
        for (ResourceFact fact : AnnotatedElementUtils.getMergedRepeatableAnnotations(target, ResourceFact.class)) {
            if (fact != null && StringUtils.hasText(fact.key())) {
                out.putIfAbsent(fact.key(), new ResourceFactSummary(
                        fact.key(), fact.label(), fact.dataType(), fact.description(), fact.authority()));
            }
        }
    }

    private static List<String> extractCalculationTrailIds(Class<?> dtoType) {
        Map<String, Object> meta = CalculationMemoryMetadataResolver.resolve(dtoType);
        if (meta == null || !(meta.get("trails") instanceof List<?> trails) || trails.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(trails.size());
        for (Object item : trails) {
            if (item instanceof Map<?, ?> map && map.get("id") != null) {
                String id = map.get("id").toString().trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    private StatsSupportMode safeStatsMode(Supplier<StatsSupportMode> supplier) {
        StatsSupportMode mode = tryMetadataLookup("statsMode", supplier);
        return mode != null ? mode : StatsSupportMode.DISABLED;
    }

    private static StatsFieldDescriptor resolveTargetStatsField(
            StatsFieldRegistry registry,
            String operationKind,
            String requestedField,
            Set<String> blockedFields) {
        if (StringUtils.hasText(requestedField)) {
            Optional<StatsFieldDescriptor> exact = registry.resolve(requestedField.trim());
            if (exact.isPresent()) {
                return blockedFields.contains(exact.get().field().toLowerCase(Locale.ROOT)) ? null : exact.get();
            }
            for (StatsFieldDescriptor candidate : registry.descriptors()) {
                if (candidate.field().equalsIgnoreCase(requestedField.trim())) {
                    return blockedFields.contains(candidate.field().toLowerCase(Locale.ROOT)) ? null : candidate;
                }
            }
            return null;
        }
        for (StatsFieldDescriptor candidate : registry.descriptors()) {
            if (blockedFields.contains(candidate.field().toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean matches = switch (operationKind) {
                case "TIMESERIES" -> candidate.timeSeriesEligible();
                case "DISTRIBUTION" -> candidate.distributionTermsEligible() || candidate.distributionHistogramEligible();
                default -> candidate.groupByEligible();
            };
            if (matches) {
                return candidate;
            }
        }
        return null;
    }

    private static String resolveOperationKind(String rawKind) {
        if (!StringUtils.hasText(rawKind)) {
            return "GROUP_BY";
        }
        String norm = rawKind.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (norm) {
            case "TIMESERIES", "TIME_SERIES" -> "TIMESERIES";
            case "DISTRIBUTION" -> "DISTRIBUTION";
            default -> "GROUP_BY";
        };
    }

    private static StatsMetric resolveMetricOperation(String rawMetric, StatsFieldDescriptor targetDescriptor) {
        if (StringUtils.hasText(rawMetric)) {
            String norm = rawMetric.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            try {
                return StatsMetric.valueOf(norm);
            } catch (IllegalArgumentException ignored) {
                // fallback below
            }
        }
        if (targetDescriptor.metrics() != null && targetDescriptor.metrics().contains(StatsMetric.COUNT)) {
            return StatsMetric.COUNT;
        }
        return targetDescriptor.metrics() != null && !targetDescriptor.metrics().isEmpty()
                ? targetDescriptor.metrics().iterator().next()
                : StatsMetric.COUNT;
    }

    private static TimeSeriesGranularity parseTimeSeriesGranularity(String raw) {
        if (!StringUtils.hasText(raw)) {
            return TimeSeriesGranularity.MONTH;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "DAY" -> TimeSeriesGranularity.DAY;
            case "WEEK" -> TimeSeriesGranularity.WEEK;
            default -> TimeSeriesGranularity.MONTH;
        };
    }

    private static String inferChartKind(String requested, String operationKind, StatsMetric metricOp, int bucketCount) {
        if (StringUtils.hasText(requested)) {
            String norm = requested.trim().toLowerCase(Locale.ROOT);
            if ("horizontalbar".equals(norm) || "horizontal_bar".equals(norm) || "horizontal-bar".equals(norm)) {
                return "horizontal-bar";
            }
            if ("bar".equals(norm) || "line".equals(norm) || "pie".equals(norm) || "donut".equals(norm)) {
                return norm;
            }
        }
        if ("TIMESERIES".equals(operationKind)) {
            return "line";
        }
        if ("GROUP_BY".equals(operationKind) && metricOp == StatsMetric.COUNT && bucketCount > 0 && bucketCount <= 5) {
            return "donut";
        }
        return "bar";
    }

    private static String resolveFieldLabel(RegisteredResource res, String fieldName) {
        if (!StringUtils.hasText(fieldName)) {
            return "";
        }
        for (ResponseFieldMeta rf : res.responseFields()) {
            if (fieldName.equals(rf.name()) && StringUtils.hasText(rf.label())) {
                return rf.label();
            }
        }
        for (FilterFieldMeta ff : res.filterFields()) {
            if (fieldName.equals(ff.name()) && StringUtils.hasText(ff.label())) {
                return ff.label();
            }
        }
        return fieldName;
    }

    private static String resolveBucketLabel(RegisteredResource res, String fieldName, String key, String serverLabel) {
        if (StringUtils.hasText(serverLabel) && !serverLabel.equals(key)) {
            return serverLabel;
        }
        if (StringUtils.hasText(fieldName) && StringUtils.hasText(key)) {
            for (ResponseFieldMeta rf : res.responseFields()) {
                if (fieldName.equals(rf.name()) && rf.optionLabels() != null && rf.optionLabels().containsKey(key)) {
                    return rf.optionLabels().get(key);
                }
            }
        }
        return firstNonBlank(serverLabel, key);
    }

    private static BigDecimal toBigDecimal(Number value, long fallbackCount) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value != null) {
            return BigDecimal.valueOf(value.doubleValue());
        }
        return BigDecimal.valueOf(fallbackCount);
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
                .thenComparing(Comparator.comparingInt((RegisteredResource r) -> r.descriptor().matchPriority()).reversed()));
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
                        res.responseFields(),
                        res.determinations(),
                        res.mutatingActions()));
            } else {
                merged.add(new RegisteredResource(
                        mergeDescriptors(res.descriptor(), override),
                        res.controller(),
                        res.service(),
                        res.responseDtoType(),
                        res.idType(),
                        res.filterDtoType(),
                        res.filterFields(),
                        res.responseFields(),
                        res.determinations(),
                        res.mutatingActions()));
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
            Class<?> updateDtoType = AbstractCreateUpdateResourceController.class.isAssignableFrom(targetClass)
                    ? ResolvableType.forClass(targetClass).as(AbstractCreateUpdateResourceController.class)
                            .getGeneric(4).resolve(Object.class)
                    : null;
            List<DeterminationEndpoint> determinations =
                    BridgeActionReflectionSupport.discoverDeterminations(targetClass, basePath);
            List<MutatingActionEndpoint> mutatingActions =
                    BridgeActionReflectionSupport.discoverMutatingActions(
                            targetClass,
                            basePath,
                            updateDtoType,
                            AbstractCreateUpdateResourceController.class.isAssignableFrom(targetClass)
                                    && service instanceof BaseCreateUpdateResourceService<?, ?, ?, ?, ?>);

            list.add(new RegisteredResource(
                    descriptor,
                    controller,
                    service,
                    responseDtoType,
                    idType,
                    filterDtoType,
                    filterFields,
                    responseFields,
                    determinations,
                    mutatingActions));
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
        String label = ui != null && StringUtils.hasText(ui.label()) ? ui.label().trim() : name;
        String desc = ui != null ? ui.description() : "";
        FieldControlType ctrl = ui != null ? ui.controlType() : FieldControlType.AUTO;
        String aiVis = gov != null && gov.aiUsage() != null
                ? gov.aiUsage().visibility().wireValue()
                : "allow";
        boolean quickViewVisible = ui != null && ui.quickViewVisible();
        int order = ui != null ? ui.order() : 0;
        int quickViewOrder = ui != null ? (ui.quickViewOrder() != 0 ? ui.quickViewOrder() : ui.order()) : 0;
        boolean hidden = ui != null && ui.hidden();
        boolean tableHidden = ui != null && ui.tableHidden();
        String labelTrue = ui != null ? ui.labelTrue() : "";
        String labelFalse = ui != null ? ui.labelFalse() : "";
        Map<String, String> optionLabels = extractOptionLabels(ui);
        return new ResponseFieldMeta(
                name,
                label,
                desc,
                ctrl,
                aiVis,
                quickViewVisible,
                order,
                quickViewOrder,
                hidden,
                tableHidden,
                labelTrue,
                labelFalse,
                optionLabels);
    }

    private static Map<String, String> extractOptionLabels(UISchema ui) {
        if (ui == null || ui.optionItems() == null || ui.optionItems().length == 0) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (UiOption opt : ui.optionItems()) {
            if (opt != null && StringUtils.hasText(opt.value()) && StringUtils.hasText(opt.label())) {
                map.put(opt.value().trim(), opt.label().trim());
            }
        }
        return map.isEmpty() ? Map.of() : Map.copyOf(map);
    }

    private static List<AgentFieldDescriptor> toAgentFieldDescriptors(List<ResponseFieldMeta> metas) {
        if (metas == null || metas.isEmpty()) {
            return List.of();
        }
        List<AgentFieldDescriptor> descriptors = new ArrayList<>(metas.size());
        for (ResponseFieldMeta meta : metas) {
            String explicitLabel = meta.label() != null && !meta.label().equals(meta.name())
                    ? meta.label()
                    : null;
            descriptors.add(new AgentFieldDescriptor(
                    meta.name(),
                    explicitLabel,
                    meta.order(),
                    meta.quickViewVisible(),
                    meta.quickViewOrder(),
                    meta.hidden(),
                    meta.tableHidden(),
                    meta.labelTrue(),
                    meta.labelFalse(),
                    meta.optionLabels()));
        }
        return List.copyOf(descriptors);
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

    private static boolean hasActiveHttpRequest() {
        return RequestContextHolder.getRequestAttributes() != null;
    }

    private static AgentSurfaceSummary toSurfaceSummary(
            SurfaceDefinition def,
            AgentExecutionContext context,
            ResourceStateSnapshot stateSnapshot,
            String canonicalId) {
        boolean missingAuthority = def.requiredAuthorities() != null
                && !def.requiredAuthorities().isEmpty()
                && (context == null || context.authorities() == null
                        || !context.authorities().containsAll(def.requiredAuthorities()));
        boolean wrongState = def.allowedStates() != null
                && !def.allowedStates().isEmpty()
                && stateSnapshot != null
                && StringUtils.hasText(stateSnapshot.state())
                && !def.allowedStates().contains(stateSnapshot.state());
        org.praxisplatform.uischema.capability.AvailabilityDecision decision =
                DefaultResourceAvailabilityService.INSTANCE.evaluateSurfaceSnapshot(def, stateSnapshot);
        boolean available = !missingAuthority && !wrongState && (decision == null || decision.allowed());
        String reason = null;
        if (missingAuthority) {
            reason = "missing-authority";
        } else if (wrongState) {
            reason = "resource-state-blocked";
        } else if (decision != null && !decision.allowed()) {
            reason = decision.reason();
        }
        String contextualPath = def.contextualReadPath();
        if (contextualPath != null && canonicalId != null) {
            contextualPath = contextualPath.replace("{id}", canonicalId);
        }
        RelatedResourceSurface related = def.relatedResource();
        return new AgentSurfaceSummary(
                def.id(),
                firstNonBlank(def.title(), def.id()),
                def.description(),
                def.kind() != null ? def.kind().name() : null,
                def.scope() != null ? def.scope().name() : null,
                contextualPath,
                related != null ? related.childResourceKey() : null,
                related != null ? related.childResourcePath() : null,
                related != null ? related.childParentField() : null,
                available,
                reason,
                def.order());
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

    private <T> T executeIsolatedReadOnly(Supplier<T> supplier) {
        if (isolatedReadOnlyTxTemplate == null) {
            return supplier.get();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return supplier.get();
        }
        return isolatedReadOnlyTxTemplate.execute(status -> supplier.get());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResourceStateSnapshot resolveItemStateSnapshot(RegisteredResource res, Object convertedId) {
        if (res == null || res.service() == null || convertedId == null) {
            return null;
        }
        return tryMetadataLookup("findResourceStateSnapshot:" + res.descriptor().resourceKey(),
                () -> executeIsolatedReadOnly(() -> {
                    Optional<ResourceStateSnapshot> snapshot =
                            ((BaseResourceQueryService) res.service()).findResourceStateSnapshot(convertedId);
                    return snapshot != null ? snapshot.orElse(null) : null;
                }));
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
