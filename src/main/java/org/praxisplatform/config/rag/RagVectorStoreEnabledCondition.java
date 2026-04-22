package org.praxisplatform.config.rag;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class RagVectorStoreEnabledCondition implements Condition {

    private static final String CANONICAL_PROPERTY = "praxis.ai.rag.vector-store.enabled";
    private static final String ENVIRONMENT_ALIAS = "PRAXIS_AI_RAG_VECTOR_STORE_ENABLED";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        Binder binder = Binder.get(environment);
        BindResult<Boolean> canonical = binder.bind(CANONICAL_PROPERTY, Boolean.class);
        if (canonical.isBound()) {
            return canonical.get();
        }
        String environmentAlias = environment.getProperty(ENVIRONMENT_ALIAS);
        if (environmentAlias != null && !environmentAlias.isBlank()) {
            return Boolean.parseBoolean(environmentAlias);
        }
        return true;
    }
}
