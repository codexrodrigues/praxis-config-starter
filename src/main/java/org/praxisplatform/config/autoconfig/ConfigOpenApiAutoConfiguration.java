package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Preserves explicitly closed Config DTO contracts in host-generated OpenAPI.
 * Swagger Core 2.2.22's OpenAPI 3.0 resolver omits the FALSE annotation value.
 * The host owns documentation dependencies; no global Jackson or runtime policy changes.
 */
@AutoConfiguration(beforeName = "org.springdoc.core.configuration.SpringDocConfiguration")
@ConditionalOnClass(name = {"io.swagger.v3.core.converter.ModelConverter",
        "org.springdoc.core.converters.ModelConverterRegistrar"})
public class ConfigOpenApiAutoConfiguration {
    @Bean
    ModelConverter configClosedDtoModelConverter() {
        return (type, context, chain) -> {
            var resolved = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
            Class<?> owner = type.getType() instanceof Class<?> clazz ? clazz
                    : type.getType() instanceof JavaType javaType ? javaType.getRawClass() : null;
            if (resolved == null || owner == null
                    || !owner.getPackageName().equals("org.praxisplatform.config.dto")) return resolved;
            var declaration = owner.getAnnotation(Schema.class);
            if (declaration == null || declaration.additionalProperties() != Schema.AdditionalPropertiesValue.FALSE) {
                return resolved;
            }
            var contract = resolved;
            if (resolved.get$ref() != null && resolved.get$ref().startsWith("#/components/schemas/")) {
                contract = context.getDefinedModels().get(resolved.get$ref().substring("#/components/schemas/".length()));
            }
            if (contract != null) contract.setAdditionalProperties(false);
            return resolved;
        };
    }
}
