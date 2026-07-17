package org.praxisplatform.config.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.projection.AiRegistryComponentCapabilityProjection;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.data.jpa.repository.JpaContext;
import org.springframework.transaction.annotation.Transactional;

public class AiRegistryComponentCapabilityRepositoryImpl
        implements AiRegistryComponentCapabilityRepository {

    private static final String COMPONENT_CAPABILITY_QUERY = """
            SELECT
                registry_key,
                payload #>> '{componentDefinition,description}',
                payload #>> '{componentDefinition,jsonSchema,friendlyName}',
                payload #>> '{componentDefinition,jsonSchema,selector}',
                payload #>> '{componentDefinition,jsonSchema,tags}',
                payload #>> '{componentDefinition,jsonSchema,authoringManifest}'
            FROM ai_registry
            WHERE registry_type = :registryType
              AND component_type = :componentType
              AND scope = :scope
              AND scope_key = :scopeKey
              AND payload #> '{componentDefinition,jsonSchema,authoringManifest}' IS NOT NULL
            ORDER BY registry_key
            """;

    private final EntityManager entityManager;

    public AiRegistryComponentCapabilityRepositoryImpl(JpaContext jpaContext) {
        this.entityManager = jpaContext.getEntityManagerByManagedType(AiRegistry.class);
    }

    @Override
    @Transactional(transactionManager = ConfigTransactionManagerNames.CONFIG, readOnly = true)
    public List<AiRegistryComponentCapabilityProjection> findComponentCapabilityProjections(
            String registryType,
            String componentType,
            String scope,
            String scopeKey,
            long queryTimeoutMs) {
        Query query = entityManager.createNativeQuery(COMPONENT_CAPABILITY_QUERY);
        query.setParameter("registryType", registryType);
        query.setParameter("componentType", componentType);
        query.setParameter("scope", scope);
        query.setParameter("scopeKey", scopeKey);
        query.setHint("jakarta.persistence.query.timeout", boundedTimeout(queryTimeoutMs));
        List<?> rows = query.getResultList();
        return rows.stream()
                .map(this::toProjection)
                .toList();
    }

    private AiRegistryComponentCapabilityProjection toProjection(Object row) {
        if (!(row instanceof Object[] values) || values.length < 6) {
            throw new IllegalStateException("Unexpected component capability projection row.");
        }
        return new AiRegistryComponentCapabilityProjection(
                text(values[0]),
                text(values[1]),
                text(values[2]),
                text(values[3]),
                text(values[4]),
                text(values[5]));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int boundedTimeout(long queryTimeoutMs) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, queryTimeoutMs));
    }
}
