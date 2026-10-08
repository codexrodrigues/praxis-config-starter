package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.UiLayoutRevisionCommandRequest;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConfigOpenApiAutoConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigOpenApiAutoConfiguration.class));

    @Test void absentSwaggerDoesNotRequireDocumentationRuntime() {
        context.withClassLoader(new FilteredClassLoader("io.swagger.v3.core"))
                .run(result -> assertThat(result).doesNotHaveBean("configClosedDtoModelConverter"));
    }

    @Test void absentSpringdocDoesNotRegisterAnUnusedConverter() {
        context.withClassLoader(new FilteredClassLoader("org.springdoc"))
                .run(result -> assertThat(result).doesNotHaveBean("configClosedDtoModelConverter"));
    }

    @Test void realResolverClosesOnlyTheExplicitConfigDtoIncludingReferencedSchema() {
        var converters = new ModelConverters();
        converters.addConverter(new ConfigOpenApiAutoConfiguration().configClosedDtoModelConverter());
        var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(UiLayoutRevisionCommandRequest.class).resolveAsRef(true));
        assertThat(resolved.schema.get$ref()).isEqualTo("#/components/schemas/UiLayoutRevisionCommandRequest");
        var request = resolved.referencedSchemas.get("UiLayoutRevisionCommandRequest");
        assertThat(request.getAdditionalProperties()).isEqualTo(false);
        assertThat(request.getProperties()).containsOnlyKeys("commandRef", "target", "authoringDocument", "reason");
        assertThat(resolved.referencedSchemas.get("JsonNode").getAdditionalProperties()).isNull();
        var target = converters.resolveAsResolvedSchema(new AnnotatedType(UiLayoutTarget.class));
        assertThat(target.schema.getAdditionalProperties()).isNull();
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ForeignContract(String value) {}

    @Test void converterDoesNotRedefineOtherOwnersContracts() {
        var converters = new ModelConverters();
        converters.addConverter(new ConfigOpenApiAutoConfiguration().configClosedDtoModelConverter());
        var resolved = converters.resolveAsResolvedSchema(new AnnotatedType(ForeignContract.class));
        assertThat(resolved.schema.getAdditionalProperties()).isNull();
    }
}
