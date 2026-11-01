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
            WITH winning_ids AS MATERIALIZED (
                SELECT DISTINCT ON (
                    COALESCE(
                        NULLIF(payload #>> '{componentDefinition,jsonSchema,authoringManifest,componentId}', ''),
                        registry_key
                    ),
                    md5(payload #>> '{componentDefinition,jsonSchema,authoringManifest}')
                )
                    id,
                    registry_key
                FROM ai_registry
                WHERE registry_type = :registryType
                  AND component_type = :componentType
                  AND scope = :scope
                  AND scope_key = :scopeKey
                  AND payload #> '{componentDefinition,jsonSchema,authoringManifest}' IS NOT NULL
                ORDER BY
                    COALESCE(
                        NULLIF(payload #>> '{componentDefinition,jsonSchema,authoringManifest,componentId}', ''),
                        registry_key
                    ),
                    md5(payload #>> '{componentDefinition,jsonSchema,authoringManifest}'),
                    CASE
                        WHEN registry_key = (payload #>> '{componentDefinition,jsonSchema,authoringManifest,componentId}')
                        THEN 0
                        ELSE 1
                    END,
                    registry_key
            )
            SELECT
                r.registry_key,
                r.payload #>> '{componentDefinition,description}',
                r.payload #>> '{componentDefinition,jsonSchema,friendlyName}',
                r.payload #>> '{componentDefinition,jsonSchema,selector}',
                r.payload #>> '{componentDefinition,jsonSchema,tags}',
                jsonb_build_object(
                    'componentId', m.manifest -> 'componentId',
                    'manifestVersion', m.manifest -> 'manifestVersion',
                    'schemaVersion', m.manifest -> 'schemaVersion',
                    'editableTargets', COALESCE(
                        (
                            SELECT jsonb_agg(jsonb_build_object('kind', et -> 'kind'))
                            FROM jsonb_array_elements(
                                CASE WHEN jsonb_typeof(m.manifest -> 'editableTargets') = 'array'
                                     THEN m.manifest -> 'editableTargets'
                                     ELSE '[]'::jsonb
                                END
                            ) et
                        ),
                        '[]'::jsonb
                    ),
                    'operations', COALESCE(
                        (
                            SELECT jsonb_agg(
                                jsonb_build_object(
                                    'operationId', op -> 'operationId',
                                    'title', op -> 'title',
                                    'label', op -> 'label',
                                    'description', op -> 'description',
                                    'target', jsonb_build_object('kind', op #> '{target,kind}'),
                                    'effects', COALESCE(
                                        (
                                            SELECT jsonb_agg(jsonb_build_object('kind', ef -> 'kind', 'handler', ef -> 'handler'))
                                            FROM jsonb_array_elements(
                                                CASE WHEN jsonb_typeof(op -> 'effects') = 'array'
                                                     THEN op -> 'effects'
                                                     ELSE '[]'::jsonb
                                                END
                                            ) ef
                                        ),
                                        '[]'::jsonb
                                    ),
                                    'inputSchema', jsonb_build_object(
                                        'properties', COALESCE(
                                            (
                                                SELECT jsonb_object_agg(k, 'true'::jsonb)
                                                FROM jsonb_object_keys(
                                                    CASE WHEN jsonb_typeof(op #> '{inputSchema,properties}') = 'object'
                                                         THEN op #> '{inputSchema,properties}'
                                                         ELSE '{}'::jsonb
                                                    END
                                                ) k
                                            ),
                                            '{}'::jsonb
                                        )
                                    ),
                                    'affectedPaths', op -> 'affectedPaths',
                                    'validators', op -> 'validators'
                                )
                            )
                            FROM jsonb_array_elements(
                                CASE WHEN jsonb_typeof(m.manifest -> 'operations') = 'array'
                                     THEN m.manifest -> 'operations'
                                     ELSE '[]'::jsonb
                                END
                            ) op
                        ),
                        '[]'::jsonb
                    ),
                    'examples', COALESCE(
                        (
                            SELECT jsonb_agg(
                                jsonb_build_object(
                                    'isPositive', ex -> 'isPositive',
                                    'operationId', ex -> 'operationId',
                                    'request', ex -> 'request'
                                )
                            )
                            FROM jsonb_array_elements(
                                CASE WHEN jsonb_typeof(m.manifest -> 'examples') = 'array'
                                     THEN m.manifest -> 'examples'
                                     ELSE '[]'::jsonb
                                END
                            ) ex
                            WHERE ex -> 'isPositive' = 'true'::jsonb
                        ),
                        '[]'::jsonb
                    )
                )::text
            FROM winning_ids w
            JOIN ai_registry r ON r.id = w.id
            CROSS JOIN LATERAL (
                SELECT r.payload #> '{componentDefinition,jsonSchema,authoringManifest}' AS manifest
            ) m
            ORDER BY w.registry_key
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
