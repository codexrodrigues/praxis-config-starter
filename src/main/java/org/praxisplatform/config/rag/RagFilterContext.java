package org.praxisplatform.config.rag;

import org.springframework.ai.vectorstore.filter.Filter;

public final class RagFilterContext {

    private static final ThreadLocal<Filter.Expression> CURRENT = new ThreadLocal<>();

    private RagFilterContext() {}

    public static Filter.Expression get() {
        return CURRENT.get();
    }

    public static void set(Filter.Expression expression) {
        if (expression == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(expression);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
