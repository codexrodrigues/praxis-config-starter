package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("unit")
class ConfigDataSourceIsolationAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    ConfigDataSourceIsolationAutoConfiguration.class,
                                    ConfigTransactionManagerAliasAutoConfiguration.class,
                                    ConfigJdbcTemplateAutoConfiguration.class));

    @Test
    void backsOffCompletelyWhenConfigDatasourceUrlIsAbsent() {
        runner.withUserConfiguration(PrimaryInfrastructureConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean("configDataSource");
                            assertThat(context).doesNotHaveBean("configEntityManagerFactory");
                            assertThat(context).hasSingleBean(DataSource.class);
                            assertThat(context)
                                    .getBean(ConfigTransactionManagerNames.CONFIG)
                                    .isSameAs(context.getBean("transactionManager"));
                        });
    }

    @Test
    void backsOffWhenIsolationExplicitlyDisabled() {
        runner.withUserConfiguration(PrimaryInfrastructureConfiguration.class)
                .withPropertyValues(
                        "config.datasource.url=jdbc:h2:mem:disabled_cfg;DB_CLOSE_DELAY=-1",
                        "praxis.config.datasource.isolation.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean("configDataSource");
                            assertThat(context).doesNotHaveBean("configEntityManagerFactory");
                        });
    }

    @Test
    void isolatesDedicatedConfigBeansWhileKeepingPrimaryBeansAsDefault() {
        runner.withUserConfiguration(PrimaryInfrastructureConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:primary_app;DB_CLOSE_DELAY=-1",
                        "config.datasource.url=jdbc:h2:mem:isolated_cfg;DB_CLOSE_DELAY=-1",
                        "config.datasource.username=sa",
                        "config.datasource.password=",
                        "config.datasource.driver-class-name=org.h2.Driver",
                        "config.datasource.ddl-auto=none",
                        "praxis.config.datasource.isolation.enable-jpa-repositories=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasBean("configDataSource");
                            assertThat(context).hasBean("configJdbcTemplate");
                            assertThat(context).hasBean("configNamedParameterJdbcTemplate");
                            assertThat(context).hasBean("configEntityManagerFactory");
                            assertThat(context).hasBean(ConfigTransactionManagerNames.CONFIG);

                            DataSource primary = context.getBean("dataSource", DataSource.class);
                            DataSource isolated =
                                    context.getBean("configDataSource", DataSource.class);
                            assertThat(isolated).isNotSameAs(primary);
                            assertThat(context.getBean(DataSource.class)).isSameAs(primary);

                            PlatformTransactionManager primaryTx =
                                    context.getBean("transactionManager", PlatformTransactionManager.class);
                            PlatformTransactionManager configTx =
                                    context.getBean(
                                            ConfigTransactionManagerNames.CONFIG,
                                            PlatformTransactionManager.class);
                            assertThat(configTx).isNotSameAs(primaryTx);
                            assertThat(context.getBean(PlatformTransactionManager.class))
                                    .isSameAs(primaryTx);

                            JdbcTemplate configJdbc =
                                    context.getBean("configJdbcTemplate", JdbcTemplate.class);
                            NamedParameterJdbcTemplate configNamedJdbc =
                                    context.getBean(
                                            "configNamedParameterJdbcTemplate",
                                            NamedParameterJdbcTemplate.class);
                            assertThat(configJdbc.getDataSource()).isSameAs(isolated);
                            assertThat(configNamedJdbc.getJdbcOperations()).isSameAs(configJdbc);
                        });
    }

    @Test
    void failsFastWhenConfigDatasourceMatchesPrimaryTarget() {
        runner.withUserConfiguration(PrimaryInfrastructureConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:shared_target;DB_CLOSE_DELAY=-1",
                        "config.datasource.url=jdbc:h2:mem:shared_target;MODE=PostgreSQL",
                        "config.datasource.ddl-auto=none",
                        "praxis.config.datasource.isolation.enable-jpa-repositories=false")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalStateException.class)
                                    .hasStackTraceContaining("isolated database, schema, or role");
                        });
    }

    @Test
    void failsFastWhenRequirePostgresqlIsEnabledWithNonPostgresUrl() {
        runner.withPropertyValues(
                        "config.datasource.url=jdbc:h2:mem:not_pg;DB_CLOSE_DELAY=-1",
                        "config.datasource.require-postgresql=true",
                        "praxis.config.datasource.isolation.enable-jpa-repositories=false")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseInstanceOf(IllegalStateException.class)
                                    .hasStackTraceContaining("PostgreSQL");
                        });
    }

    @Test
    void respectsCustomUserOverridesForConfigDatasourceAndTransactionManager() {
        runner.withUserConfiguration(
                        PrimaryInfrastructureConfiguration.class, CustomConfigOverrides.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:primary_custom;DB_CLOSE_DELAY=-1",
                        "config.datasource.url=jdbc:h2:mem:ignored_cfg;DB_CLOSE_DELAY=-1",
                        "config.datasource.ddl-auto=none",
                        "praxis.config.datasource.isolation.enable-jpa-repositories=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DataSource customDs =
                                    context.getBean("configDataSource", DataSource.class);
                            assertThat(customDs)
                                    .isSameAs(CustomConfigOverrides.CUSTOM_DATA_SOURCE);
                            assertThat(
                                            context.getBean("configJdbcTemplate", JdbcTemplate.class)
                                                    .getDataSource())
                                    .isSameAs(customDs);
                            assertThat(
                                            context.getBean(
                                                    ConfigTransactionManagerNames.CONFIG,
                                                    PlatformTransactionManager.class))
                                    .isSameAs(CustomConfigOverrides.CUSTOM_TX_MANAGER);
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryInfrastructureConfiguration {

        @Bean(name = "dataSource")
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:primary_app;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean(name = "entityManagerFactory")
        EntityManagerFactory entityManagerFactory() {
            return Mockito.mock(EntityManagerFactory.class);
        }

        @Bean(name = "transactionManager")
        PlatformTransactionManager transactionManager() {
            return new JpaTransactionManager(entityManagerFactory());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomConfigOverrides {

        static final DataSource CUSTOM_DATA_SOURCE =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:custom_isolated_cfg;DB_CLOSE_DELAY=-1", "sa", "");
        static final PlatformTransactionManager CUSTOM_TX_MANAGER =
                Mockito.mock(PlatformTransactionManager.class);

        @Bean(name = "configDataSource")
        DataSource configDataSource() {
            return CUSTOM_DATA_SOURCE;
        }

        @Bean(name = ConfigTransactionManagerNames.CONFIG)
        PlatformTransactionManager configTransactionManager() {
            return CUSTOM_TX_MANAGER;
        }
    }
}
