package org.praxisplatform.config.service;

public record AiJsonSchema(String jsonSchema, Class<?> targetClass) {
    public static AiJsonSchema of(String jsonSchema, Class<?> targetClass) {
        return new AiJsonSchema(jsonSchema, targetClass);
    }

    public static AiJsonSchema ofSchema(String jsonSchema) {
        return new AiJsonSchema(jsonSchema, null);
    }

    public static AiJsonSchema ofClass(Class<?> targetClass) {
        return new AiJsonSchema(null, targetClass);
    }

    public boolean hasJsonSchema() {
        return jsonSchema != null && !jsonSchema.isBlank();
    }

    public boolean hasTargetClass() {
        return targetClass != null;
    }
}
