package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.praxisplatform.config.dto.DomainCatalogIngestionResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Ponte in-process opcional e idempotente entre o gerador semântico de catálogo
 * ({@code SemanticDomainCatalogService} / {@link DomainCatalogLocalSourceProvider})
 * e o {@link DomainCatalogIngestionService}.
 */
@Service
@Slf4j
@ConditionalOnBean(DomainCatalogIngestionService.class)
@ConditionalOnProperty(prefix = "praxis.domain-catalog.auto-sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DomainCatalogAutoSyncService {

    private static final String SEMANTIC_CATALOG_CLASS =
            "org.praxisplatform.uischema.domain.SemanticDomainCatalogService";

    private final DomainCatalogIngestionService ingestionService;
    private final ObjectProvider<DomainCatalogLocalSourceProvider> localSourceProviders;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final boolean syncOnStartup;
    private final String defaultTenantId;
    private final String defaultEnvironment;
    private final List<String> configuredGroups;
    private final List<String> configuredResourceKeys;

    @Autowired
    public DomainCatalogAutoSyncService(
            DomainCatalogIngestionService ingestionService,
            ObjectProvider<DomainCatalogLocalSourceProvider> localSourceProviders,
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            @Value("${praxis.domain-catalog.auto-sync.on-startup:true}") boolean syncOnStartup,
            @Value("${praxis.domain-catalog.auto-sync.tenant-id:default}") String defaultTenantId,
            @Value("${praxis.domain-catalog.auto-sync.environment:dev}") String defaultEnvironment,
            @Value("${praxis.domain-catalog.auto-sync.groups:}") List<String> configuredGroups,
            @Value("${praxis.domain-catalog.auto-sync.resource-keys:}") List<String> configuredResourceKeys) {
        this.ingestionService = ingestionService;
        this.localSourceProviders = localSourceProviders;
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.syncOnStartup = syncOnStartup;
        this.defaultTenantId = StringUtils.hasText(defaultTenantId) ? defaultTenantId.trim() : "default";
        this.defaultEnvironment = StringUtils.hasText(defaultEnvironment) ? defaultEnvironment.trim() : "dev";
        this.configuredGroups = sanitizeList(configuredGroups);
        this.configuredResourceKeys = sanitizeList(configuredResourceKeys);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!syncOnStartup) {
            return;
        }
        try {
            synchronizeNow(defaultTenantId, defaultEnvironment);
        } catch (RuntimeException ex) {
            log.warn(
                    "Domain catalog auto-sync on startup could not complete (tenant={}, environment={}): {}",
                    defaultTenantId,
                    defaultEnvironment,
                    ex.getMessage());
        }
    }

    public List<DomainCatalogIngestionResponse> synchronizeNow(String tenantId, String environment) {
        String effectiveTenant = StringUtils.hasText(tenantId) ? tenantId.trim() : defaultTenantId;
        String effectiveEnvironment = StringUtils.hasText(environment) ? environment.trim() : defaultEnvironment;
        List<JsonNode> documents = collectLocalCatalogDocuments();
        if (documents.isEmpty()) {
            return List.of();
        }
        List<DomainCatalogIngestionResponse> ingested = new ArrayList<>(documents.size());
        for (JsonNode document : documents) {
            if (document == null || !document.isObject()) {
                continue;
            }
            DomainCatalogIngestionResponse release =
                    ingestionService.ingest(document, effectiveTenant, effectiveEnvironment);
            if (release != null) {
                ingested.add(release);
            }
        }
        if (!ingested.isEmpty()) {
            log.info(
                    "Auto-synchronized {} local domain catalog document(s) for tenant={}, environment={}",
                    ingested.size(),
                    effectiveTenant,
                    effectiveEnvironment);
        }
        return List.copyOf(ingested);
    }

    List<JsonNode> collectLocalCatalogDocuments() {
        List<JsonNode> documents = new ArrayList<>();
        if (localSourceProviders != null) {
            for (DomainCatalogLocalSourceProvider provider : localSourceProviders) {
                if (provider == null) {
                    continue;
                }
                List<JsonNode> provided = provider.localCatalogDocuments();
                if (provided != null) {
                    provided.stream()
                            .filter(node -> node != null && node.isObject())
                            .forEach(documents::add);
                }
            }
        }
        if (applicationContext != null && (!configuredGroups.isEmpty() || !configuredResourceKeys.isEmpty())) {
            documents.addAll(resolveFromSemanticDomainCatalogBean());
        }
        return List.copyOf(documents);
    }

    private List<JsonNode> resolveFromSemanticDomainCatalogBean() {
        try {
            Class<?> serviceType = Class.forName(SEMANTIC_CATALOG_CLASS);
            String[] beanNames = applicationContext.getBeanNamesForType(serviceType);
            if (beanNames.length == 0) {
                return List.of();
            }
            Object catalogService = applicationContext.getBean(beanNames[0]);
            List<JsonNode> resolved = new ArrayList<>();
            if (!configuredGroups.isEmpty()) {
                Method findByGroup = serviceType.getMethod("findByGroup", String.class);
                for (String group : configuredGroups) {
                    Object response = findByGroup.invoke(catalogService, group);
                    if (response != null) {
                        resolved.add(objectMapper.valueToTree(response));
                    }
                }
            }
            if (!configuredResourceKeys.isEmpty()) {
                Method findByResourceKey = serviceType.getMethod("findByResourceKey", String.class);
                for (String resourceKey : configuredResourceKeys) {
                    Object response = findByResourceKey.invoke(catalogService, resourceKey);
                    if (response != null) {
                        resolved.add(objectMapper.valueToTree(response));
                    }
                }
            }
            return resolved;
        } catch (ClassNotFoundException ignored) {
            return List.of();
        } catch (Exception ex) {
            log.warn("Could not collect domain catalog from SemanticDomainCatalogService bean: {}", ex.getMessage());
            return List.of();
        }
    }

    private static List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> sanitized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                sanitized.add(value.trim());
            }
        }
        return List.copyOf(sanitized);
    }
}
