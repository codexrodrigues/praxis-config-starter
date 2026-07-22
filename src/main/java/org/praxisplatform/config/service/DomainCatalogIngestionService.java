package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.domain.DomainCatalogItem;
import org.praxisplatform.config.domain.DomainCatalogRelease;
import org.praxisplatform.config.domain.DomainCatalogReleaseChangedEvent;
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainCatalogRagStatusResponse;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagFilters;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.DomainCatalogItemRepository;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@ConditionalOnBean({DomainCatalogReleaseRepository.class, DomainCatalogItemRepository.class})
public class DomainCatalogIngestionService {

    private static final List<String> ITEM_ARRAYS = List.of(
            "contexts",
            "nodes",
            "edges",
            "bindings",
            "aliases",
            "evidence",
            "governance"
    );

    private final DomainCatalogReleaseRepository releaseRepository;
    private final DomainCatalogItemRepository itemRepository;
    private final ObjectMapper objectMapper;
    private final RagVectorStoreService ragVectorStoreService;
    private final DomainCatalogSchemaValidationService schemaValidationService;
    private final DomainKnowledgeProjectionService domainKnowledgeProjectionService;
    private final boolean domainCatalogRagPublicationEnabled;
    private final boolean asyncRagPublicationEnabled;
    private final int ragPublicationBatchSize;
    private final ExecutorService ragPublicationExecutor;
    private final ApplicationEventPublisher applicationEventPublisher;

    public DomainCatalogIngestionService(
            DomainCatalogReleaseRepository releaseRepository,
            DomainCatalogItemRepository itemRepository,
            ObjectMapper objectMapper,
            RagVectorStoreService ragVectorStoreService,
            DomainCatalogSchemaValidationService schemaValidationService) {
        this(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                schemaValidationService,
                (DomainKnowledgeProjectionService) null,
                true,
                false,
                100,
                event -> { });
    }

    DomainCatalogIngestionService(
            DomainCatalogReleaseRepository releaseRepository,
            DomainCatalogItemRepository itemRepository,
            ObjectMapper objectMapper,
            RagVectorStoreService ragVectorStoreService,
            DomainCatalogSchemaValidationService schemaValidationService,
            boolean domainCatalogRagPublicationEnabled) {
        this(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                schemaValidationService,
                (DomainKnowledgeProjectionService) null,
                domainCatalogRagPublicationEnabled,
                false,
                100,
                event -> { });
    }

    DomainCatalogIngestionService(
            DomainCatalogReleaseRepository releaseRepository,
            DomainCatalogItemRepository itemRepository,
            ObjectMapper objectMapper,
            RagVectorStoreService ragVectorStoreService,
            DomainCatalogSchemaValidationService schemaValidationService,
            boolean domainCatalogRagPublicationEnabled,
            boolean asyncRagPublicationEnabled,
            int ragPublicationBatchSize) {
        this(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                schemaValidationService,
                (DomainKnowledgeProjectionService) null,
                domainCatalogRagPublicationEnabled,
                asyncRagPublicationEnabled,
                ragPublicationBatchSize,
                event -> { });
    }

    @Autowired
    public DomainCatalogIngestionService(
            DomainCatalogReleaseRepository releaseRepository,
            DomainCatalogItemRepository itemRepository,
            ObjectMapper objectMapper,
            RagVectorStoreService ragVectorStoreService,
            DomainCatalogSchemaValidationService schemaValidationService,
            ObjectProvider<DomainKnowledgeProjectionService> domainKnowledgeProjectionService,
            @Value("${praxis.domain-catalog.rag-publication.enabled:true}")
            boolean domainCatalogRagPublicationEnabled,
            @Value("${praxis.domain-catalog.rag-publication.async-enabled:true}")
            boolean asyncRagPublicationEnabled,
            @Value("${praxis.domain-catalog.rag-publication.batch-size:100}")
            int ragPublicationBatchSize,
            ApplicationEventPublisher applicationEventPublisher) {
        this(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                schemaValidationService,
                domainKnowledgeProjectionService.getIfAvailable(),
                domainCatalogRagPublicationEnabled,
                asyncRagPublicationEnabled,
                ragPublicationBatchSize,
                applicationEventPublisher);
    }

    DomainCatalogIngestionService(
            DomainCatalogReleaseRepository releaseRepository,
            DomainCatalogItemRepository itemRepository,
            ObjectMapper objectMapper,
            RagVectorStoreService ragVectorStoreService,
            DomainCatalogSchemaValidationService schemaValidationService,
            DomainKnowledgeProjectionService domainKnowledgeProjectionService,
            boolean domainCatalogRagPublicationEnabled,
            boolean asyncRagPublicationEnabled,
            int ragPublicationBatchSize,
            ApplicationEventPublisher applicationEventPublisher) {
        this(releaseRepository, itemRepository, objectMapper, ragVectorStoreService, schemaValidationService,
                domainKnowledgeProjectionService, domainCatalogRagPublicationEnabled, asyncRagPublicationEnabled,
                ragPublicationBatchSize, applicationEventPublisher, true);
    }

    private DomainCatalogIngestionService(
            DomainCatalogReleaseRepository releaseRepository,
            DomainCatalogItemRepository itemRepository,
            ObjectMapper objectMapper,
            RagVectorStoreService ragVectorStoreService,
            DomainCatalogSchemaValidationService schemaValidationService,
            DomainKnowledgeProjectionService domainKnowledgeProjectionService,
            boolean domainCatalogRagPublicationEnabled,
            boolean asyncRagPublicationEnabled,
            int ragPublicationBatchSize,
            ApplicationEventPublisher applicationEventPublisher,
            boolean ignored) {
        this.releaseRepository = releaseRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
        this.ragVectorStoreService = ragVectorStoreService;
        this.schemaValidationService = schemaValidationService;
        this.domainKnowledgeProjectionService = domainKnowledgeProjectionService;
        this.domainCatalogRagPublicationEnabled = domainCatalogRagPublicationEnabled;
        this.asyncRagPublicationEnabled = asyncRagPublicationEnabled;
        this.ragPublicationBatchSize = Math.max(1, ragPublicationBatchSize);
        this.ragPublicationExecutor = asyncRagPublicationEnabled ? createRagPublicationExecutor() : null;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG)
    public DomainCatalogIngestionResponse ingest(JsonNode payload, String tenantId, String environment) {
        if (payload == null || !payload.isObject()) {
            throw new ConfigurationIngestionException("Domain catalog payload must be a JSON object");
        }
        schemaValidationService.validate(payload);
        String schemaVersion = requiredText(payload, "schemaVersion");
        String releaseKey = releaseKey(payload);
        String sourceHash = text(payload.path("release"), "sourceHash");
        String resourceKey = text(payload, "resourceKey");
        String resolvedTenantId = normalize(tenantId);
        String resolvedEnvironment = normalize(environment);
        String rawPayload = write(payload);
        Optional<DomainCatalogRelease> existingRelease = releaseRepository.findByReleaseKeyAndScope(
                releaseKey,
                resolvedTenantId,
                resolvedEnvironment);
        if (existingRelease
                .filter(release -> sameCatalogRelease(release, schemaVersion, sourceHash, rawPayload))
                .isPresent()) {
            DomainCatalogRelease release = existingRelease.get();
            if (!StringUtils.hasText(release.getResourceKey()) && StringUtils.hasText(resourceKey)) {
                release.setResourceKey(resourceKey);
                releaseRepository.save(release);
                publishReleaseChanged(release);
                log.info("Repaired missing resourceKey for domain catalog release {}", release.getReleaseKey());
            }
            long existingItemCount = itemRepository.countByRelease(release);
            log.info(
                    "Skipped domain catalog release {} because sourceHash {} is already ingested",
                    release.getReleaseKey(),
                    release.getSourceHash()
            );
            return new DomainCatalogIngestionResponse(
                    release.getId(),
                    release.getReleaseKey(),
                    Math.toIntExact(Math.min(existingItemCount, Integer.MAX_VALUE))
            );
        }

        if (existingRelease.isPresent()) {
            throw new ConfigurationIngestionException(
                    "Domain catalog releaseKey already identifies different immutable content in the requested scope: "
                            + releaseKey);
        }

        DomainCatalogRelease release = new DomainCatalogRelease();
        release.setReleaseKey(releaseKey);
        release.setSchemaVersion(schemaVersion);
        release.setServiceKey(text(payload.path("service"), "serviceKey"));
        release.setServiceName(text(payload.path("service"), "name"));
        release.setServiceVersion(text(payload.path("service"), "version"));
        release.setResourceKey(resourceKey);
        release.setGeneratedAt(parseInstant(text(payload.path("release"), "generatedAt")));
        release.setSourceHash(sourceHash);
        release.setTenantId(resolvedTenantId);
        release.setEnvironment(resolvedEnvironment);
        release.setRawPayload(rawPayload);
        release = releaseRepository.save(release);

        itemRepository.deleteByRelease(release);
        List<DomainCatalogItem> items = extractItems(payload, release);
        itemRepository.saveAll(items);
        if (domainKnowledgeProjectionService != null) {
            domainKnowledgeProjectionService.project(release, items);
        }
        if (domainCatalogRagPublicationEnabled) {
            publishRagDocumentsAfterPersistence(release, items);
        } else {
            log.debug("Domain catalog RAG publication disabled for release {}", release.getReleaseKey());
        }
        publishReleaseChanged(release);

        log.info("Ingested domain catalog release {} with {} item(s)", release.getReleaseKey(), items.size());
        return new DomainCatalogIngestionResponse(release.getId(), release.getReleaseKey(), items.size());
    }

    private void publishReleaseChanged(DomainCatalogRelease release) {
        applicationEventPublisher.publishEvent(new DomainCatalogReleaseChangedEvent(
                release.getTenantId(),
                release.getEnvironment(),
                release.getServiceKey(),
                release.getResourceKey(),
                release.getReleaseKey(),
                Instant.now()));
    }

    @PreDestroy
    void shutdownRagPublicationExecutor() {
        if (ragPublicationExecutor != null) {
            ragPublicationExecutor.shutdownNow();
        }
    }

    private boolean sameCatalogRelease(
            DomainCatalogRelease release,
            String schemaVersion,
            String sourceHash,
            String rawPayload) {
        return release != null
                && Objects.equals(release.getSchemaVersion(), schemaVersion)
                && (StringUtils.hasText(sourceHash)
                        ? Objects.equals(release.getSourceHash(), sourceHash)
                        : sameJson(release.getRawPayload(), rawPayload));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<DomainCatalogItemResponse> search(
            String releaseKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        if (!StringUtils.hasText(releaseKey)) {
            throw new IllegalArgumentException("releaseKey is required");
        }
        DomainCatalogRelease release = releaseRepository.findByReleaseKeyAndScope(
                        releaseKey.trim(),
                        normalize(tenantId),
                        normalize(environment))
                .orElseThrow(() -> new ConfigurationIngestionException(
                        "Domain catalog release not found in the requested scope: " + releaseKey.trim()));
        return search(release, itemType, contextKey, nodeType, query, limit);
    }

    private List<DomainCatalogItemResponse> search(
            DomainCatalogRelease release,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), 200);
        return itemRepository.search(
                        release,
                        normalize(itemType),
                        normalize(contextKey),
                        normalize(nodeType),
                        normalize(query),
                        PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Preserves the latest-release selection owned by this service while collapsing a
     * service-wide or federated lookup into one item query. A single-resource lookup keeps the
     * narrow repository path; a multi-resource lookup no longer performs one remote round trip
     * per release.
     */
    private List<DomainCatalogItemResponse> search(
            List<DomainCatalogRelease> releases,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        if (releases == null || releases.isEmpty()) {
            return List.of();
        }
        if (releases.size() == 1) {
            return search(releases.get(0), itemType, contextKey, nodeType, query, limit);
        }
        int resolvedLimit = Math.min(Math.max(limit, 1), 200);
        return itemRepository.searchAcrossReleases(
                        releases,
                        normalize(itemType),
                        normalize(contextKey),
                        normalize(nodeType),
                        normalize(query),
                        PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<DomainCatalogReleaseResponse> releases(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), 100);
        return releaseRepository.findLatest(
                        normalize(serviceKey),
                        normalize(resourceKey),
                        normalize(tenantId),
                        normalize(environment),
                        PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toReleaseResponse)
                .toList();
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainCatalogRagStatusResponse ragStatus(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment) {
        DomainCatalogRelease release = latestRelease(serviceKey, tenantId, environment, resourceKey);
        long expectedDocumentCount = itemRepository.findByRelease(release).stream()
                .filter(this::isRagIndexable)
                .count();
        RagVectorStoreService.RagCorpusReleaseStatus status = ragVectorStoreService.corpusReleaseStatus(
                release.getTenantId(),
                release.getEnvironment(),
                release.getReleaseKey(),
                RagResourceTypes.DOMAIN_CATALOG,
                expectedDocumentCount);
        return DomainCatalogRagStatusResponse.from(
                toReleaseResponse(release),
                RagResourceTypes.DOMAIN_CATALOG,
                domainCatalogRagPublicationEnabled,
                ragVectorStoreService.isAvailable(),
                status);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<DomainCatalogItemResponse> searchLatest(
            String serviceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        return searchLatest(serviceKey, null, tenantId, environment, itemType, contextKey, nodeType, query, limit);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<DomainCatalogItemResponse> searchLatest(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), 200);
        return search(
                latestReleasesForScope(serviceKey, tenantId, environment, resourceKey),
                itemType,
                contextKey,
                nodeType,
                query,
                resolvedLimit);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainCatalogContextResponse contextLatest(
            String serviceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        return contextLatest(serviceKey, null, tenantId, environment, itemType, contextKey, nodeType, query, limit);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainCatalogContextResponse contextLatest(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), 200);
        List<DomainCatalogRelease> releases = latestReleasesForScope(serviceKey, tenantId, environment, resourceKey);
        List<DomainCatalogItemResponse> items = search(
                releases,
                itemType,
                contextKey,
                nodeType,
                query,
                resolvedLimit);
        boolean scopedSingleRelease = StringUtils.hasText(normalize(serviceKey)) && releases.size() == 1;
        return new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                scopedSingleRelease ? toReleaseResponse(releases.get(0)) : null,
                normalize(query),
                normalize(itemType),
                normalize(contextKey),
                normalize(nodeType),
                retrievalGuidance(!scopedSingleRelease),
                governedContextItems(items.stream().limit(resolvedLimit).toList()));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public DomainCatalogContextResponse contextLatestSemantic(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), 200);
        List<DomainCatalogRelease> releases = latestReleasesForScope(
                serviceKey,
                tenantId,
                environment,
                resourceKey);
        List<DomainCatalogItemResponse> items = semanticContextItems(
                releases,
                tenantId,
                environment,
                itemType,
                contextKey,
                nodeType,
                query,
                resolvedLimit);
        boolean scopedSingleRelease = StringUtils.hasText(normalize(serviceKey)) && releases.size() == 1;
        return new DomainCatalogContextResponse(
                "praxis.domain-catalog-context/v0.1",
                scopedSingleRelease ? toReleaseResponse(releases.get(0)) : null,
                normalize(query),
                normalize(itemType),
                normalize(contextKey),
                normalize(nodeType),
                retrievalGuidance(!scopedSingleRelease),
                governedContextItems(items));
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<DomainCatalogItemResponse> relationshipsLatest(
            String serviceKey,
            String tenantId,
            String environment,
            String sourceNodeKey,
            String targetNodeKey,
            String edgeType,
            String query,
            int limit) {
        return relationshipsLatest(serviceKey, null, tenantId, environment, sourceNodeKey, targetNodeKey, edgeType, query, limit);
    }

    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<DomainCatalogItemResponse> relationshipsLatest(
            String serviceKey,
            String resourceKey,
            String tenantId,
            String environment,
            String sourceNodeKey,
            String targetNodeKey,
            String edgeType,
            String query,
            int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), 200);
        return search(
                        latestReleasesForScope(serviceKey, tenantId, environment, resourceKey),
                        "edge",
                        null,
                        null,
                        query,
                        200)
                .stream()
                .filter(edge -> matchesEdge(edge, sourceNodeKey, targetNodeKey, edgeType))
                .limit(resolvedLimit)
                .toList();
    }

    private List<String> retrievalGuidance(boolean federated) {
        List<String> guidance = new ArrayList<>();
        guidance.add("Use this context as the semantic vocabulary for the requested business scope.");
        guidance.add("Prefer node items for business concepts, fields, actions, states and policy hints.");
        guidance.add("Use governance items to respect privacy, compliance and AI visibility constraints.");
        guidance.add("Use binding and evidence items to cite runtime/API/schema sources.");
        guidance.add("Do not infer executable rules from policy_hint nodes unless an executable rule binding is present.");
        if (federated) {
            guidance.add("This context may include items from multiple latest releases or services; keep boundaries explicit when citing or applying it.");
        }
        return List.copyOf(guidance);
    }

    private List<DomainCatalogItem> extractItems(JsonNode payload, DomainCatalogRelease release) {
        Map<String, DomainCatalogItem> itemsByCanonicalKey = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (String arrayName : ITEM_ARRAYS) {
            JsonNode array = payload.path(arrayName);
            if (!array.isArray()) {
                continue;
            }
            String itemType = singularType(arrayName);
            for (JsonNode itemPayload : array) {
                if (!itemPayload.isObject()) {
                    continue;
                }
                String itemKey = itemKey(itemType, itemPayload);
                if (!StringUtils.hasText(itemKey)) {
                    continue;
                }
                String canonicalKey = itemType + "\u0000" + itemKey;
                DomainCatalogItem item = DomainCatalogItem.builder()
                        .release(release)
                        .itemType(itemType)
                        .itemKey(itemKey)
                        .contextKey(text(itemPayload, "contextKey"))
                        .nodeType(text(itemPayload, "nodeType"))
                        .bindingType(text(itemPayload, "bindingType"))
                        .edgeType(text(itemPayload, "edgeType"))
                        .payload(write(itemPayload))
                        .searchableText(searchableText(itemType, itemPayload))
                        .build();
                if (itemsByCanonicalKey.putIfAbsent(canonicalKey, item) != null) {
                    duplicateCount++;
                }
            }
        }
        if (duplicateCount > 0) {
            log.warn(
                    "Ignored {} duplicate domain catalog item(s) for release {} using canonical itemType/itemKey identity",
                    duplicateCount,
                    release.getReleaseKey()
            );
        }
        return new ArrayList<>(itemsByCanonicalKey.values());
    }

    private void publishRagDocuments(DomainCatalogRelease release, List<DomainCatalogItem> items) {
        if (!ragVectorStoreService.isAvailable() || items == null || items.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        List<Document> documents = new ArrayList<>();
        int index = 0;
        for (DomainCatalogItem item : items) {
            if (!isRagIndexable(item)) {
                continue;
            }
            String content = item.getSearchableText();
            content = ragContent(item, content);
            String contentHash = RagDocumentIdentity.sha256(item.getItemType() + "|" + item.getItemKey() + "|" + item.getPayload());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.DOMAIN_CATALOG);
            metadata.put(RagMetadataKeys.RESOURCE_ID, item.getItemKey());
            metadata.put(RagMetadataKeys.RESOURCE_KEY, release.getResourceKey());
            metadata.put(RagMetadataKeys.SERVICE_KEY, release.getServiceKey());
            metadata.put(RagMetadataKeys.COMPONENT_ID, item.getItemKey());
            metadata.put(RagMetadataKeys.DOC_TYPE, item.getItemType());
            metadata.put(RagMetadataKeys.CONTEXT_KEY, item.getContextKey());
            metadata.put(RagMetadataKeys.NODE_TYPE, item.getNodeType());
            metadata.put(RagMetadataKeys.RELEASE_ID, ragReleaseId(release));
            metadata.put(RagMetadataKeys.CONTENT_HASH, contentHash);
            metadata.put(RagMetadataKeys.CHUNK_INDEX, 0);
            metadata.put(RagMetadataKeys.TENANT_ID, release.getTenantId());
            metadata.put(RagMetadataKeys.ENVIRONMENT, release.getEnvironment());
            metadata.put(RagMetadataKeys.VERSION, release.getSchemaVersion());
            metadata.entrySet().removeIf(entry -> Objects.isNull(entry.getValue()));
            documents.add(Document.builder()
                    .id(RagDocumentIdentity.buildDocumentId(
                            release.getTenantId(),
                            release.getEnvironment(),
                            item.getItemKey(),
                            release.getReleaseKey(),
                            RagResourceTypes.DOMAIN_CATALOG,
                            contentHash,
                            index++))
                    .text(content)
                    .metadata(metadata)
                    .build());
        }
        if (documents.isEmpty()) {
            return;
        }
        int publishedDocuments = 0;
        for (int start = 0; start < documents.size(); start += ragPublicationBatchSize) {
            int end = Math.min(start + ragPublicationBatchSize, documents.size());
            ragVectorStoreService.upsertDocuments(documents.subList(start, end));
            publishedDocuments += end - start;
        }
        log.info(
                "Published {} domain catalog RAG document(s) for release {} in {} ms using batchSize={}",
                publishedDocuments,
                release.getReleaseKey(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                ragPublicationBatchSize);
    }

    private void publishRagDocumentsAfterPersistence(DomainCatalogRelease release, List<DomainCatalogItem> items) {
        Runnable task = () -> {
            try {
                publishRagDocuments(release, items);
            } catch (RuntimeException ex) {
                log.warn(
                        "Domain catalog release {} was persisted, but RAG publication failed: {}",
                        release.getReleaseKey(),
                        ex.getMessage()
                );
            }
        };
        if (!asyncRagPublicationEnabled) {
            task.run();
            return;
        }
        Runnable asyncTask = () -> {
            try {
                ragPublicationExecutor.execute(task);
            } catch (RuntimeException ex) {
                log.warn(
                        "Domain catalog release {} was persisted, but RAG publication could not be scheduled: {}",
                        release.getReleaseKey(),
                        ex.getMessage()
                );
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncTask.run();
                }
            });
        } else {
            asyncTask.run();
        }
        log.debug("Scheduled asynchronous domain catalog RAG publication for release {}", release.getReleaseKey());
    }

    private List<DomainCatalogItemResponse> semanticContextItems(
            List<DomainCatalogRelease> releases,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        if (!domainCatalogRagPublicationEnabled
                || !ragVectorStoreService.isAvailable()
                || !StringUtils.hasText(query)
                || releases == null
                || releases.isEmpty()) {
            return List.of();
        }
        try {
            Filter.Expression filter = semanticContextFilter(
                    releases,
                    tenantId,
                    environment,
                    itemType,
                    contextKey,
                    nodeType);
            List<Document> documents = ragVectorStoreService.search(query, limit, filter);
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }
            Map<String, DomainCatalogItemResponse> itemsByDocumentIdentity = new LinkedHashMap<>();
            for (DomainCatalogRelease release : releases) {
                for (DomainCatalogItem item : itemRepository.findByRelease(release)) {
                    if (matchesSemanticContextItem(item, itemType, contextKey, nodeType)) {
                        DomainCatalogItemResponse response = toResponse(item);
                        itemsByDocumentIdentity.put(
                                semanticDocumentIdentity(
                                        ragReleaseId(release),
                                        item.getItemType(),
                                        item.getItemKey()),
                                response);
                    }
                }
            }
            List<DomainCatalogItemResponse> rankedItems = new ArrayList<>();
            for (Document document : documents) {
                DomainCatalogItemResponse item = itemsByDocumentIdentity.get(semanticDocumentIdentity(
                        documentMetadata(document, RagMetadataKeys.RELEASE_ID),
                        documentMetadata(document, RagMetadataKeys.DOC_TYPE),
                        documentMetadata(document, RagMetadataKeys.RESOURCE_ID)));
                if (item != null) {
                    rankedItems.add(item);
                }
            }
            return rankedItems.stream().limit(limit).toList();
        } catch (RuntimeException ex) {
            log.warn(
                    "Could not retrieve semantic domain catalog context for tenant={}, environment={}: {}",
                    normalize(tenantId),
                    normalize(environment),
                    ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private Filter.Expression semanticContextFilter(
            List<DomainCatalogRelease> releases,
            String tenantId,
            String environment,
            String itemType,
            String contextKey,
            String nodeType) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = builder.eq(
                RagMetadataKeys.RESOURCE_TYPE,
                RagResourceTypes.DOMAIN_CATALOG);
        FilterExpressionBuilder.Op scope = RagFilters.buildTenantEnvironmentFilter(
                builder,
                tenantId,
                environment);
        if (scope != null) {
            filter = builder.and(filter, scope);
        }
        FilterExpressionBuilder.Op releaseFilter = null;
        for (DomainCatalogRelease release : releases) {
            FilterExpressionBuilder.Op candidate = builder.eq(
                    RagMetadataKeys.RELEASE_ID,
                    ragReleaseId(release));
            releaseFilter = releaseFilter == null
                    ? candidate
                    : builder.or(releaseFilter, candidate);
        }
        filter = builder.and(filter, releaseFilter);
        if (StringUtils.hasText(normalize(itemType))) {
            filter = builder.and(filter, builder.eq(RagMetadataKeys.DOC_TYPE, normalize(itemType)));
        }
        if (StringUtils.hasText(normalize(contextKey))) {
            filter = builder.and(filter, builder.eq(RagMetadataKeys.CONTEXT_KEY, normalize(contextKey)));
        }
        if (StringUtils.hasText(normalize(nodeType))) {
            filter = builder.and(filter, builder.eq(RagMetadataKeys.NODE_TYPE, normalize(nodeType)));
        }
        return filter.build();
    }

    private boolean matchesSemanticContextItem(
            DomainCatalogItem item,
            String itemType,
            String contextKey,
            String nodeType) {
        return item != null
                && isRagIndexable(item)
                && matchesOptionalValue(item.getItemType(), itemType)
                && matchesOptionalValue(item.getContextKey(), contextKey)
                && matchesOptionalValue(item.getNodeType(), nodeType);
    }

    private boolean matchesOptionalValue(String value, String expected) {
        String normalizedExpected = normalize(expected);
        return !StringUtils.hasText(normalizedExpected)
                || Objects.equals(normalize(value), normalizedExpected);
    }

    private String semanticDocumentIdentity(String releaseKey, String itemType, String itemKey) {
        return String.join(
                "\u0000",
                Objects.toString(releaseKey, ""),
                Objects.toString(itemType, ""),
                Objects.toString(itemKey, ""));
    }

    private String documentMetadata(Document document, String key) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        return Objects.toString(document.getMetadata().get(key), "");
    }

    private String ragReleaseId(DomainCatalogRelease release) {
        return RagDocumentIdentity.resolveReleaseId(
                release == null ? null : release.getReleaseKey(),
                release == null ? null : release.getSchemaVersion(),
                release == null || release.getGeneratedAt() == null
                        ? null
                        : release.getGeneratedAt().toString());
    }

    private ExecutorService createRagPublicationExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                ragPublicationThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private ThreadFactory ragPublicationThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "praxis-domain-catalog-rag-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private List<DomainCatalogItemResponse> governedContextItems(List<DomainCatalogItemResponse> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .filter(item -> !deniesAiVisibility(item.payload()))
                .map(this::governedContextItem)
                .toList();
    }

    private DomainCatalogItemResponse governedContextItem(DomainCatalogItemResponse item) {
        String visibility = aiVisibility(item.payload());
        if (!"mask".equals(visibility) && !"summarize_only".equals(visibility)) {
            return item;
        }
        return new DomainCatalogItemResponse(
                item.id(),
                item.releaseKey(),
                item.itemType(),
                item.itemKey(),
                item.contextKey(),
                item.nodeType(),
                item.bindingType(),
                item.edgeType(),
                sanitizedAiPayload(item.payload(), visibility)
        );
    }

    private JsonNode sanitizedAiPayload(JsonNode payload, String visibility) {
        ObjectNode sanitized = objectMapper.createObjectNode();
        copyText(payload, sanitized, "governanceKey");
        copyText(payload, sanitized, "nodeKey");
        copyText(payload, sanitized, "annotationType");
        copyText(payload, sanitized, "classification");
        copyText(payload, sanitized, "dataCategory");
        if (payload != null && payload.path("complianceTags").isArray()) {
            sanitized.set("complianceTags", payload.path("complianceTags"));
        }
        if (payload != null && payload.path("aiUsage").isObject()) {
            sanitized.set("aiUsage", payload.path("aiUsage"));
        }
        sanitized.put("contextVisibility", visibility);
        sanitized.put("payloadMode", "governed-summary");
        return sanitized;
    }

    private String ragContent(DomainCatalogItem item, String fallbackContent) {
        JsonNode payload = read(item.getPayload());
        String visibility = aiVisibility(payload);
        if (!"mask".equals(visibility) && !"summarize_only".equals(visibility)) {
            return fallbackContent;
        }
        StringJoiner joiner = new StringJoiner(" | ");
        add(joiner, item.getItemType());
        add(joiner, item.getItemKey());
        add(joiner, text(payload, "nodeKey"));
        add(joiner, text(payload, "annotationType"));
        add(joiner, text(payload, "classification"));
        add(joiner, text(payload, "dataCategory"));
        JsonNode complianceTags = payload.path("complianceTags");
        if (complianceTags.isArray()) {
            add(joiner, complianceTags.toString());
        }
        JsonNode aiUsage = payload.path("aiUsage");
        if (aiUsage.isObject()) {
            add(joiner, aiUsage.toString());
        }
        return joiner.toString();
    }

    private boolean isRagIndexable(DomainCatalogItem item) {
        return item != null
                && StringUtils.hasText(item.getSearchableText())
                && !deniesAiVisibility(read(item.getPayload()));
    }

    private boolean deniesAiVisibility(JsonNode payload) {
        return "deny".equals(aiVisibility(payload));
    }

    private String aiVisibility(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        String visibility = text(payload.path("aiUsage"), "visibility");
        return visibility == null ? null : visibility.toLowerCase();
    }

    private void copyText(JsonNode source, ObjectNode target, String field) {
        String value = text(source, field);
        if (StringUtils.hasText(value)) {
            target.put(field, value);
        }
    }

    private DomainCatalogItemResponse toResponse(DomainCatalogItem item) {
        return new DomainCatalogItemResponse(
                item.getId(),
                item.getRelease().getReleaseKey(),
                item.getItemType(),
                item.getItemKey(),
                item.getContextKey(),
                item.getNodeType(),
                item.getBindingType(),
                item.getEdgeType(),
                read(item.getPayload())
        );
    }

    private DomainCatalogReleaseResponse toReleaseResponse(DomainCatalogRelease release) {
        return new DomainCatalogReleaseResponse(
                release.getId(),
                release.getReleaseKey(),
                release.getSchemaVersion(),
                release.getServiceKey(),
                release.getServiceName(),
                release.getServiceVersion(),
                release.getGeneratedAt(),
                release.getSourceHash(),
                release.getTenantId(),
                release.getEnvironment(),
                release.getCreatedAt()
        );
    }

    private DomainCatalogRelease latestRelease(String serviceKey, String tenantId, String environment) {
        return latestRelease(serviceKey, tenantId, environment, null);
    }

    private DomainCatalogRelease latestRelease(String serviceKey, String tenantId, String environment, String resourceKey) {
        String normalizedResourceKey = normalize(resourceKey);
        return releaseRepository.findLatest(
                        normalize(serviceKey),
                        normalizedResourceKey,
                        normalize(tenantId),
                        normalize(environment),
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No domain catalog release found for the requested scope"));
    }

    private List<DomainCatalogRelease> latestReleasesForScope(String serviceKey, String tenantId, String environment) {
        return latestReleasesForScope(serviceKey, tenantId, environment, null);
    }

    private List<DomainCatalogRelease> latestReleasesForScope(
            String serviceKey,
            String tenantId,
            String environment,
            String resourceKey) {
        String normalizedServiceKey = normalize(serviceKey);
        if (StringUtils.hasText(normalizedServiceKey)) {
            if (StringUtils.hasText(normalize(resourceKey))) {
                return List.of(latestRelease(normalizedServiceKey, tenantId, environment, resourceKey));
            }
            return latestReleasesForServiceScope(normalizedServiceKey, tenantId, environment);
        }
        return latestReleasesByService(tenantId, environment, resourceKey);
    }

    private List<DomainCatalogRelease> latestReleasesForServiceScope(
            String serviceKey,
            String tenantId,
            String environment) {
        List<DomainCatalogRelease> releases = releaseRepository.findLatest(
                normalize(serviceKey),
                null,
                normalize(tenantId),
                normalize(environment),
                PageRequest.of(0, 100));
        Map<String, DomainCatalogRelease> latestByResource = new LinkedHashMap<>();
        for (DomainCatalogRelease release : releases) {
            String key = latestByServiceKey(serviceKey, release, null);
            latestByResource.putIfAbsent(key, release);
        }
        if (latestByResource.isEmpty()) {
            throw new IllegalArgumentException("No domain catalog release found for the requested scope");
        }
        return List.copyOf(latestByResource.values());
    }

    private List<DomainCatalogRelease> latestReleasesByService(String tenantId, String environment) {
        return latestReleasesByService(tenantId, environment, null);
    }

    private List<DomainCatalogRelease> latestReleasesByService(String tenantId, String environment, String resourceKey) {
        String normalizedResourceKey = normalize(resourceKey);
        List<DomainCatalogRelease> releases = releaseRepository.findLatest(
                null,
                normalizedResourceKey,
                normalize(tenantId),
                normalize(environment),
                PageRequest.of(0, 100));
        Map<String, DomainCatalogRelease> latestByService = new LinkedHashMap<>();
        for (DomainCatalogRelease release : releases) {
            String serviceKey = normalize(release.getServiceKey());
            String key = StringUtils.hasText(serviceKey)
                    ? latestByServiceKey(serviceKey, release, normalizedResourceKey)
                    : release.getReleaseKey();
            latestByService.putIfAbsent(key, release);
        }
        if (latestByService.isEmpty()) {
            throw new IllegalArgumentException("No domain catalog release found for the requested scope");
        }
        return List.copyOf(latestByService.values());
    }

    private String latestByServiceKey(String serviceKey, DomainCatalogRelease release, String resourceKey) {
        if (StringUtils.hasText(resourceKey)) {
            return serviceKey + ":" + resourceKeyFromReleaseKey(release.getReleaseKey());
        }
        if (hasStructuredResourceReleaseKey(release.getReleaseKey())) {
            return serviceKey + ":" + resourceKeyFromReleaseKey(release.getReleaseKey());
        }
        return serviceKey;
    }

    private boolean hasStructuredResourceReleaseKey(String releaseKey) {
        return StringUtils.hasText(releaseKey) && releaseKey.split(":", 3).length >= 3;
    }

    private boolean matchesResourceKey(DomainCatalogRelease release, String resourceKey) {
        if (!StringUtils.hasText(resourceKey)) {
            return true;
        }
        return resourceKey.equals(resourceKeyFromReleaseKey(release.getReleaseKey()));
    }

    private String resourceKeyFromReleaseKey(String releaseKey) {
        if (!StringUtils.hasText(releaseKey)) {
            return "";
        }
        String[] parts = releaseKey.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    private boolean matchesEdge(
            DomainCatalogItemResponse edge,
            String sourceNodeKey,
            String targetNodeKey,
            String edgeType) {
        return matchesText(text(edge.payload(), "sourceNodeKey"), sourceNodeKey)
                && matchesText(text(edge.payload(), "targetNodeKey"), targetNodeKey)
                && matchesText(edge.edgeType(), edgeType);
    }

    private boolean matchesText(String actual, String expected) {
        String normalizedExpected = normalize(expected);
        return !StringUtils.hasText(normalizedExpected) || normalizedExpected.equals(normalize(actual));
    }

    private String searchableText(String itemType, JsonNode node) {
        StringJoiner joiner = new StringJoiner(" | ");
        joiner.add(itemType);
        add(joiner, text(node, "contextKey"));
        add(joiner, text(node, "nodeKey"));
        add(joiner, text(node, "edgeKey"));
        add(joiner, text(node, "bindingKey"));
        add(joiner, text(node, "aliasKey"));
        add(joiner, text(node, "evidenceKey"));
        add(joiner, text(node, "governanceKey"));
        add(joiner, text(node, "label"));
        add(joiner, text(node, "description"));
        add(joiner, text(node, "nodeType"));
        add(joiner, text(node, "bindingType"));
        add(joiner, text(node, "edgeType"));
        add(joiner, text(node, "alias"));
        add(joiner, text(node, "summary"));
        add(joiner, text(node, "annotationType"));
        add(joiner, text(node, "classification"));
        add(joiner, text(node, "dataCategory"));
        add(joiner, text(node, "nodeKey"));
        JsonNode complianceTags = node.path("complianceTags");
        if (complianceTags.isArray()) {
            add(joiner, complianceTags.toString());
        }
        JsonNode aiUsage = node.path("aiUsage");
        if (aiUsage.isObject()) {
            add(joiner, aiUsage.toString());
        }
        JsonNode metadata = node.path("metadata");
        if (metadata.isObject()) {
            add(joiner, metadata.toString());
        }
        return joiner.toString();
    }

    private void add(StringJoiner joiner, String value) {
        if (StringUtils.hasText(value)) {
            joiner.add(value);
        }
    }

    private String itemKey(String itemType, JsonNode node) {
        return switch (itemType) {
            case "context" -> text(node, "contextKey");
            case "node" -> text(node, "nodeKey");
            case "edge" -> text(node, "edgeKey");
            case "binding" -> text(node, "bindingKey");
            case "alias" -> text(node, "aliasKey");
            case "evidence" -> text(node, "evidenceKey");
            case "governance" -> text(node, "governanceKey");
            default -> null;
        };
    }

    private String singularType(String arrayName) {
        return switch (arrayName) {
            case "contexts" -> "context";
            case "nodes" -> "node";
            case "edges" -> "edge";
            case "bindings" -> "binding";
            case "aliases" -> "alias";
            case "evidence" -> "evidence";
            case "governance" -> "governance";
            default -> arrayName;
        };
    }

    private String releaseKey(JsonNode payload) {
        String releaseKey = text(payload.path("release"), "releaseKey");
        if (StringUtils.hasText(releaseKey)) {
            return releaseKey;
        }
        String serviceKey = text(payload.path("service"), "serviceKey");
        String generatedAt = text(payload.path("release"), "generatedAt");
        return (StringUtils.hasText(serviceKey) ? serviceKey : "domain-catalog")
                + ":"
                + (StringUtils.hasText(generatedAt) ? generatedAt : Instant.now());
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) {
            throw new ConfigurationIngestionException("Domain catalog field '" + field + "' is required");
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText(null);
        return normalize(value);
    }

    private Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new ConfigurationIngestionException("Failed to serialize domain catalog payload", ex);
        }
    }

    private boolean sameJson(String existingRawPayload, String candidateRawPayload) {
        return Objects.equals(read(existingRawPayload), read(candidateRawPayload));
    }

    private JsonNode read(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }
}
