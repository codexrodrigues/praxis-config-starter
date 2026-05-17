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
import org.praxisplatform.config.dto.DomainCatalogContextResponse;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.praxisplatform.config.dto.DomainCatalogItemResponse;
import org.praxisplatform.config.dto.DomainCatalogReleaseResponse;
import org.praxisplatform.config.exception.ConfigurationIngestionException;
import org.praxisplatform.config.rag.RagDocumentIdentity;
import org.praxisplatform.config.rag.RagMetadataKeys;
import org.praxisplatform.config.rag.RagResourceTypes;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.repository.DomainCatalogItemRepository;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
                100);
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
                100);
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
                ragPublicationBatchSize);
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
            int ragPublicationBatchSize) {
        this(
                releaseRepository,
                itemRepository,
                objectMapper,
                ragVectorStoreService,
                schemaValidationService,
                domainKnowledgeProjectionService.getIfAvailable(),
                domainCatalogRagPublicationEnabled,
                asyncRagPublicationEnabled,
                ragPublicationBatchSize);
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
            int ragPublicationBatchSize) {
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
    }

    @Transactional
    public DomainCatalogIngestionResponse ingest(JsonNode payload, String tenantId, String environment) {
        if (payload == null || !payload.isObject()) {
            throw new ConfigurationIngestionException("Domain catalog payload must be a JSON object");
        }
        schemaValidationService.validate(payload);
        String schemaVersion = requiredText(payload, "schemaVersion");
        String releaseKey = releaseKey(payload);
        String sourceHash = text(payload.path("release"), "sourceHash");
        Optional<DomainCatalogRelease> existingRelease = releaseRepository.findByReleaseKey(releaseKey);
        if (existingRelease
                .filter(release -> sameCatalogRelease(release, schemaVersion, sourceHash, tenantId, environment))
                .isPresent()) {
            DomainCatalogRelease release = existingRelease.get();
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

        DomainCatalogRelease release = existingRelease
                .orElseGet(DomainCatalogRelease::new);
        release.setReleaseKey(releaseKey);
        release.setSchemaVersion(schemaVersion);
        release.setServiceKey(text(payload.path("service"), "serviceKey"));
        release.setServiceName(text(payload.path("service"), "name"));
        release.setServiceVersion(text(payload.path("service"), "version"));
        release.setGeneratedAt(parseInstant(text(payload.path("release"), "generatedAt")));
        release.setSourceHash(sourceHash);
        release.setTenantId(normalize(tenantId));
        release.setEnvironment(normalize(environment));
        release.setRawPayload(write(payload));
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

        log.info("Ingested domain catalog release {} with {} item(s)", release.getReleaseKey(), items.size());
        return new DomainCatalogIngestionResponse(release.getId(), release.getReleaseKey(), items.size());
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
            String tenantId,
            String environment) {
        return release != null
                && StringUtils.hasText(sourceHash)
                && Objects.equals(release.getSchemaVersion(), schemaVersion)
                && Objects.equals(release.getSourceHash(), sourceHash)
                && Objects.equals(normalize(release.getTenantId()), normalize(tenantId))
                && Objects.equals(normalize(release.getEnvironment()), normalize(environment));
    }

    @Transactional(readOnly = true)
    public List<DomainCatalogItemResponse> search(
            String releaseKey,
            String itemType,
            String contextKey,
            String nodeType,
            String query,
            int limit) {
        if (!StringUtils.hasText(releaseKey)) {
            throw new IllegalArgumentException("releaseKey is required");
        }
        int resolvedLimit = Math.min(Math.max(limit, 1), 200);
        return itemRepository.search(
                        releaseKey,
                        normalize(itemType),
                        normalize(contextKey),
                        normalize(nodeType),
                        normalize(query),
                        PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DomainCatalogReleaseResponse> releases(String serviceKey, String tenantId, String environment, int limit) {
        int resolvedLimit = Math.min(Math.max(limit, 1), 100);
        return releaseRepository.findLatest(
                        normalize(serviceKey),
                        normalize(tenantId),
                        normalize(environment),
                        PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toReleaseResponse)
                .toList();
    }

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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
        List<DomainCatalogItemResponse> responses = new ArrayList<>();
        for (DomainCatalogRelease release : latestReleasesForScope(serviceKey, tenantId, environment, resourceKey)) {
            int remaining = resolvedLimit - responses.size();
            if (remaining <= 0) {
                break;
            }
            responses.addAll(search(release.getReleaseKey(), itemType, contextKey, nodeType, query, remaining));
        }
        return responses.stream().limit(resolvedLimit).toList();
    }

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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
        List<DomainCatalogItemResponse> items = new ArrayList<>();
        for (DomainCatalogRelease release : releases) {
            int remaining = resolvedLimit - items.size();
            if (remaining <= 0) {
                break;
            }
            items.addAll(search(release.getReleaseKey(), itemType, contextKey, nodeType, query, remaining));
        }
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

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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
        List<DomainCatalogItemResponse> responses = new ArrayList<>();
        for (DomainCatalogRelease release : latestReleasesForScope(serviceKey, tenantId, environment, resourceKey)) {
            int remaining = resolvedLimit - responses.size();
            if (remaining <= 0) {
                break;
            }
            List<DomainCatalogItemResponse> releaseEdges = search(
                    release.getReleaseKey(),
                    "edge",
                    null,
                    null,
                    query,
                    200);
            releaseEdges.stream()
                    .filter(edge -> matchesEdge(edge, sourceNodeKey, targetNodeKey, edgeType))
                    .limit(remaining)
                    .forEach(responses::add);
        }
        return responses;
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
            if (deniesAiVisibility(read(item.getPayload()))) {
                continue;
            }
            String content = item.getSearchableText();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            content = ragContent(item, content);
            String contentHash = RagDocumentIdentity.sha256(item.getItemType() + "|" + item.getItemKey() + "|" + item.getPayload());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(RagMetadataKeys.RESOURCE_TYPE, RagResourceTypes.DOMAIN_CATALOG);
            metadata.put(RagMetadataKeys.RESOURCE_ID, item.getItemKey());
            metadata.put(RagMetadataKeys.COMPONENT_ID, item.getItemKey());
            metadata.put(RagMetadataKeys.DOC_TYPE, item.getItemType());
            metadata.put(RagMetadataKeys.RELEASE_ID, release.getReleaseKey());
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
                        normalize(tenantId),
                        normalize(environment),
                        PageRequest.of(0, StringUtils.hasText(normalizedResourceKey) ? 100 : 1))
                .stream()
                .filter(release -> matchesResourceKey(release, normalizedResourceKey))
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
                normalize(tenantId),
                normalize(environment),
                PageRequest.of(0, 100));
        Map<String, DomainCatalogRelease> latestByService = new LinkedHashMap<>();
        for (DomainCatalogRelease release : releases) {
            if (!matchesResourceKey(release, normalizedResourceKey)) {
                continue;
            }
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

    private JsonNode read(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }
}
