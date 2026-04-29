package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Tag("unit")
class ConfigJdbcTemplateAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigJdbcTemplateAutoConfiguration.class));

    @Test
    void shouldRegisterNamedParameterTemplateFromDefaultJdbcTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        contextRunner
                .withBean(JdbcTemplate.class, () -> jdbcTemplate)
                .run(context -> {
                    assertThat(context).hasBean("configNamedParameterJdbcTemplate");
                    assertThat(context).hasSingleBean(NamedParameterJdbcTemplate.class);
                });
    }

    @Test
    void shouldPreferConfigJdbcTemplateWhenPresent() {
        JdbcTemplate defaultJdbcTemplate = mock(JdbcTemplate.class);
        JdbcTemplate configJdbcTemplate = mock(JdbcTemplate.class);

        contextRunner
                .withBean(JdbcTemplate.class, () -> defaultJdbcTemplate)
                .withBean("configJdbcTemplate", JdbcTemplate.class, () -> configJdbcTemplate)
                .run(context -> {
                    NamedParameterJdbcTemplate namedTemplate =
                            context.getBean("configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class);

                    assertThat(namedTemplate.getJdbcTemplate()).isSameAs(configJdbcTemplate);
                });
    }

    @Test
    void shouldNotOverrideHostProvidedConfigNamedParameterTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate customTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);

        contextRunner
                .withBean(JdbcTemplate.class, () -> jdbcTemplate)
                .withBean("configNamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class, () -> customTemplate)
                .run(context ->
                        assertThat(context.getBean("configNamedParameterJdbcTemplate")).isSameAs(customTemplate));
    }
}
