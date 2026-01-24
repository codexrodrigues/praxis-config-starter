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

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
