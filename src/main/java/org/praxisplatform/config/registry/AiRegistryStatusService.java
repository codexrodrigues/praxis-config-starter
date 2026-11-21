package org.praxisplatform.config.registry;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Serviço de status e prontidão do AI registry.
 *
 * <p>
 * Consolida contagens mínimas, componentes obrigatórios e estado do bootstrap para produzir o
 * relatório usado por health checks e endpoints de status do registry.
 * </p>
 */
public class AiRegistryStatusService {

    private static final String REGISTRY_TYPE_COMPONENT_DEF = "component_definition";
    private static final String COMPONENT_DEF_COMPONENT_TYPE = "component-definition";
    private static final String REGISTRY_TYPE_TEMPLATE = "template";
    private static final String SYSTEM_SCOPE_KEY = "GLOBAL";
    private static final long DEFAULT_CACHE_TTL_MS = 60_000L;

    private final AiRegistryRepository repository;
    private final AiRegistryBootstrapProperties bootstrapProperties;
    private final AiRegistryHealthProperties healthProperties;
    private final AiRegistryBootstrapState bootstrapState;
    private final Clock clock;
    private volatile CachedStatusReport cachedReport;

    public AiRegistryStatusService(
            AiRegistryRepository repository,
            AiRegistryBootstrapProperties bootstrapProperties,
            AiRegistryHealthProperties healthProperties,
            AiRegistryBootstrapState bootstrapState) {
        this(repository, bootstrapProperties, healthProperties, bootstrapState, Clock.systemUTC());
    }

    public AiRegistryStatusService(
            AiRegistryRepository repository,
            AiRegistryBootstrapProperties bootstrapProperties,
            AiRegistryHealthProperties healthProperties,
            AiRegistryBootstrapState bootstrapState,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.bootstrapProperties = bootstrapProperties;
        this.healthProperties = healthProperties;
        this.bootstrapState = bootstrapState;
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public void invalidateCache() {
        this.cachedReport = null;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onComponentDefinitionsChanged(AiRegistryComponentDefinitionsChangedEvent event) {
        invalidateCache();
    }

    public AiRegistryStatusReport getStatus() {
        long minComponentDefinitions = resolveMinComponentDefinitions();
        long minTemplates = resolveMinTemplates();
        List<String> requiredComponents = resolveRequiredComponents();
        long ttlMs = resolveCacheTtlMs();
        CacheKey cacheKey = cacheKey(minComponentDefinitions, minTemplates, requiredComponents);
        CachedStatusReport current = cachedReport;
        if (ttlMs > 0L
                && current != null
                && current.key().equals(cacheKey)
                && clock.millis() < current.expiresAtEpochMs()) {
            return current.report();
        }

        long componentDefinitionCount = repository.countByRegistryType(REGISTRY_TYPE_COMPONENT_DEF);
        long templateCount = repository.countByRegistryType(REGISTRY_TYPE_TEMPLATE);
        List<String> missingComponents = findMissingComponents(requiredComponents);

        boolean ready = componentDefinitionCount >= minComponentDefinitions
                && templateCount >= minTemplates
                && missingComponents.isEmpty();

        String status = resolveStatus(ready, missingComponents, componentDefinitionCount, templateCount,
                minComponentDefinitions, minTemplates);

        AiRegistryStatusReport report = AiRegistryStatusReport.builder()
                .ready(ready)
                .status(status)
                .componentDefinitionCount(componentDefinitionCount)
                .templateCount(templateCount)
                .minComponentDefinitions(minComponentDefinitions)
                .minTemplates(minTemplates)
                .requiredComponents(requiredComponents)
                .missingComponents(missingComponents)
                .bootstrap(bootstrapState)
                .build();

        if (ready && ttlMs > 0L) {
            long nowMillis = clock.millis();
            long expiresAt = Long.MAX_VALUE - ttlMs < nowMillis ? Long.MAX_VALUE : nowMillis + ttlMs;
            this.cachedReport = new CachedStatusReport(cacheKey, report, expiresAt);
        }
        return report;
    }

    private long resolveCacheTtlMs() {
        if (healthProperties == null) {
            return DEFAULT_CACHE_TTL_MS;
        }
        return Math.max(0L, healthProperties.getCacheTtlMs());
    }

    private CacheKey cacheKey(
            long minComponentDefinitions,
            long minTemplates,
            List<String> requiredComponents) {
        List<String> copiedRequired = requiredComponents == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(requiredComponents));
        if (bootstrapState == null) {
            return new CacheKey(minComponentDefinitions, minTemplates, copiedRequired,
                    null, null, false, false, null, null);
        }
        return new CacheKey(
                minComponentDefinitions,
                minTemplates,
                copiedRequired,
                bootstrapState.getAttemptedAt(),
                bootstrapState.getCompletedAt(),
                bootstrapState.isSucceeded(),
                bootstrapState.isSkipped(),
                bootstrapState.getError(),
                bootstrapState.getSnapshotHash());
    }

    private record CacheKey(
            long minComponentDefinitions,
            long minTemplates,
            List<String> requiredComponents,
            Instant bootstrapAttemptedAt,
            Instant bootstrapCompletedAt,
            boolean bootstrapSucceeded,
            boolean bootstrapSkipped,
            String bootstrapError,
            String bootstrapSnapshotHash) {
    }

    private record CachedStatusReport(
            CacheKey key,
            AiRegistryStatusReport report,
            long expiresAtEpochMs) {
    }

    private List<String> resolveRequiredComponents() {
        List<String> configured = healthProperties != null ? healthProperties.getRequiredComponents() : null;
        if (configured == null) {
            configured = bootstrapProperties != null ? bootstrapProperties.getRequiredComponents() : null;
        }
        if (configured == null) {
            return Collections.emptyList();
        }
        return configured;
    }

    private long resolveMinComponentDefinitions() {
        if (healthProperties != null && healthProperties.getMinComponentDefinitions() != null) {
            return healthProperties.getMinComponentDefinitions();
        }
        return bootstrapProperties != null ? bootstrapProperties.getMinComponentDefinitions() : 0L;
    }

    private long resolveMinTemplates() {
        if (healthProperties != null && healthProperties.getMinTemplates() != null) {
            return healthProperties.getMinTemplates();
        }
        return bootstrapProperties != null ? bootstrapProperties.getMinTemplates() : 0L;
    }

    private List<String> findMissingComponents(List<String> requiredComponents) {
        if (requiredComponents == null || requiredComponents.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> missing = new ArrayList<>();
        for (String componentId : requiredComponents) {
            if (componentId == null || componentId.isBlank()) {
                continue;
            }
            boolean exists = repository.existsByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                    REGISTRY_TYPE_COMPONENT_DEF,
                    componentId,
                    COMPONENT_DEF_COMPONENT_TYPE,
                    Scope.SYSTEM,
                    SYSTEM_SCOPE_KEY);
            if (!exists) {
                missing.add(componentId);
            }
        }
        return missing;
    }

    private String resolveStatus(
            boolean ready,
            List<String> missingComponents,
            long componentDefinitionCount,
            long templateCount,
            long minComponentDefinitions,
            long minTemplates) {
        if (ready) {
            return "ready";
        }
        if (missingComponents != null && !missingComponents.isEmpty()) {
            return "missing-components";
        }
        if (componentDefinitionCount < minComponentDefinitions || templateCount < minTemplates) {
            return "low-counts";
        }
        return "not-ready";
    }
}
