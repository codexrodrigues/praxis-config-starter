package org.praxisplatform.config.rag;

import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

public final class RagFilters {

    private RagFilters() {}

    public static FilterExpressionBuilder.Op buildTenantEnvironmentFilter(
            FilterExpressionBuilder builder,
            String tenantId,
            String environment) {
        String normalizedTenant = normalize(tenantId);
        String normalizedEnv = normalize(environment);
        FilterExpressionBuilder.Op filter = null;
        if (normalizedTenant != null) {
            filter = builder.eq(RagMetadataKeys.TENANT_ID, normalizedTenant);
        }
        if (normalizedEnv != null) {
            FilterExpressionBuilder.Op envFilter = builder.eq(RagMetadataKeys.ENVIRONMENT, normalizedEnv);
            filter = filter == null ? envFilter : builder.and(filter, envFilter);
        }
        return filter;
    }

    public static Filter.Expression buildTenantEnvironmentExpression(String tenantId, String environment) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = buildTenantEnvironmentFilter(builder, tenantId, environment);
        return filter != null ? filter.build() : null;
    }

    public static FilterExpressionBuilder.Op buildReleaseFilter(
            FilterExpressionBuilder builder,
            String releaseId,
            boolean fallbackToLegacyVersionKey) {
        String normalizedRelease = RagDocumentIdentity.resolveReleaseId(releaseId, null, null);
        FilterExpressionBuilder.Op releaseFilter = builder.eq(RagMetadataKeys.RELEASE_ID, normalizedRelease);
        if (!fallbackToLegacyVersionKey) {
            return releaseFilter;
        }
        return builder.or(
                releaseFilter,
                builder.eq(RagMetadataKeys.VERSION, normalizedRelease));
    }

    public static FilterExpressionBuilder.Op buildScopedFilter(
            FilterExpressionBuilder builder,
            String tenantId,
            String environment,
            String releaseId,
            boolean fallbackToLegacyVersionKey) {
        FilterExpressionBuilder.Op releaseFilter =
                buildReleaseFilter(builder, releaseId, fallbackToLegacyVersionKey);
        FilterExpressionBuilder.Op tenantEnvFilter =
                buildTenantEnvironmentFilter(builder, tenantId, environment);
        if (tenantEnvFilter == null) {
            return releaseFilter;
        }
        return builder.and(releaseFilter, tenantEnvFilter);
    }

    public static Filter.Expression buildScopedExpression(
            String tenantId,
            String environment,
            String releaseId,
            boolean fallbackToLegacyVersionKey) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filter = buildScopedFilter(
                builder,
                tenantId,
                environment,
                releaseId,
                fallbackToLegacyVersionKey);
        return filter != null ? filter.build() : null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
