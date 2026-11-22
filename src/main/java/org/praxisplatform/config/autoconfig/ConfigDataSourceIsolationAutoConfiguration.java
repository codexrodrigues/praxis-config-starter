package org.praxisplatform.config.autoconfig;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

/**
 * Auto-configuração zero-boilerplate de isolamento de banco de dados para o {@code praxis-config-starter}.
 *
 * <p>Ativada exclusivamente quando {@code config.datasource.url} está definido (e não desabilitada por
 * {@code praxis.config.datasource.isolation.enabled=false}). Quando inativa, os aliases legados
 * ({@link ConfigTransactionManagerAliasAutoConfiguration} e {@link ConfigJdbcTemplateAutoConfiguration})
 * preservam o compartilhamento transparente do banco primário da aplicação.
 *
 * <p>Quando ativa, registra beans dedicados {@code configDataSource}, {@code configJdbcTemplate},
 * {@code configNamedParameterJdbcTemplate}, {@code configEntityManagerFactory},
 * {@code configTransactionManager} ({@link ConfigTransactionManagerNames#CONFIG}) e vincula os repositórios
 * JPA de {@code org.praxisplatform.config.repository} a essa unidade de persistência isolada,
 * protegendo {@code spring.datasource.*} contra colisão de unicidade e contra reutilização acidental do
 * mesmo alvo JDBC/credencial.
 */
@AutoConfiguration(
        after = DataSourceAutoConfiguration.class,
        before = {
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            ConfigTransactionManagerAliasAutoConfiguration.class,
            ConfigJdbcTemplateAutoConfiguration.class
        })
@ConditionalOnClass({
    DataSource.class,
    HikariDataSource.class,
    LocalContainerEntityManagerFactoryBean.class,
    JpaTransactionManager.class
})
@Conditional(ConfigDataSourceIsolationAutoConfiguration.IsolationCondition.class)
@EnableConfigurationProperties(ConfigDataSourceProperties.class)
public class ConfigDataSourceIsolationAutoConfiguration {

    @Bean
    static BeanFactoryPostProcessor configDataSourcePrimaryFallbackPostProcessor() {
        return ConfigDataSourceIsolationAutoConfiguration::ensureNonConfigPrimaryWhenNeeded;
    }

    @Bean(name = "configDataSource", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "configDataSource")
    public HikariDataSource configDataSource(
            ConfigDataSourceProperties properties, Environment environment) {
        String url = requireNonBlank(properties.getUrl(), "config.datasource.url");
        if (properties.isRequirePostgresql()
                && !url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                    "config.datasource.url must point to PostgreSQL when require-postgresql=true.");
        }
        if (properties.isFailIfMatchesPrimary()) {
            verifyDistinctFromPrimary(properties, environment, url);
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(
                StringUtils.hasText(properties.getPoolName())
                        ? properties.getPoolName().trim()
                        : "praxis-config");
        hikari.setJdbcUrl(url);
        if (StringUtils.hasText(properties.getUsername())) {
            hikari.setUsername(properties.getUsername().trim());
        }
        if (properties.getPassword() != null) {
            hikari.setPassword(properties.getPassword());
        }
        if (StringUtils.hasText(properties.getDriverClassName())) {
            hikari.setDriverClassName(properties.getDriverClassName().trim());
        }
        if (StringUtils.hasText(properties.getSchema())) {
            hikari.setSchema(properties.getSchema().trim());
        }
        hikari.setMaximumPoolSize(Math.max(1, properties.getMaximumPoolSize()));
        hikari.setMinimumIdle(Math.max(0, Math.min(properties.getMinimumIdle(), hikari.getMaximumPoolSize())));
        if (properties.getIdleTimeoutMs() > 0L) {
            hikari.setIdleTimeout(Math.max(10_000L, properties.getIdleTimeoutMs()));
        }
        if (properties.getMaxLifetimeMs() > 0L) {
            hikari.setMaxLifetime(Math.max(30_000L, properties.getMaxLifetimeMs()));
        }
        if (properties.getKeepaliveTimeMs() >= 0L) {
            hikari.setKeepaliveTime(properties.getKeepaliveTimeMs());
        }
        hikari.setConnectionTimeout(Math.max(250L, properties.getConnectionTimeoutMs()));
        hikari.setValidationTimeout(Math.max(250L, properties.getValidationTimeoutMs()));
        return new HikariDataSource(hikari);
    }

    @Bean(name = "configJdbcTemplate")
    @ConditionalOnMissingBean(name = "configJdbcTemplate")
    public JdbcTemplate configJdbcTemplate(@Qualifier("configDataSource") DataSource configDataSource) {
        return new JdbcTemplate(configDataSource);
    }

    @Bean(name = "configNamedParameterJdbcTemplate")
    @ConditionalOnMissingBean(name = "configNamedParameterJdbcTemplate")
    public NamedParameterJdbcTemplate configNamedParameterJdbcTemplate(
            @Qualifier("configJdbcTemplate") JdbcTemplate configJdbcTemplate) {
        return new NamedParameterJdbcTemplate(configJdbcTemplate);
    }

    @Bean(name = "configEntityManagerFactory")
    @ConditionalOnMissingBean(name = "configEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean configEntityManagerFactory(
            @Qualifier("configDataSource") DataSource configDataSource,
            ConfigDataSourceProperties properties,
            Environment environment) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(configDataSource);
        factory.setPackagesToScan("org.praxisplatform.config.domain");
        factory.setPersistenceUnitName("config");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setShowSql(properties.isShowSql());
        if (StringUtils.hasText(properties.getDatabasePlatform())) {
            vendorAdapter.setDatabasePlatform(properties.getDatabasePlatform().trim());
        }
        factory.setJpaVendorAdapter(vendorAdapter);

        Map<String, Object> jpaProperties = new LinkedHashMap<>();
        String ddlAuto = firstText(
                properties.getDdlAuto(),
                environment.getProperty("spring.jpa.hibernate.ddl-auto"),
                "validate");
        jpaProperties.put("hibernate.hbm2ddl.auto", ddlAuto);
        jpaProperties.put("hibernate.show_sql", Boolean.toString(properties.isShowSql()));
        if (StringUtils.hasText(properties.getSchema())) {
            jpaProperties.put("hibernate.default_schema", properties.getSchema().trim());
        }
        if (StringUtils.hasText(properties.getDatabasePlatform())) {
            jpaProperties.put("hibernate.dialect", properties.getDatabasePlatform().trim());
        }
        factory.setJpaPropertyMap(jpaProperties);
        return factory;
    }

    @Bean(name = ConfigTransactionManagerNames.CONFIG)
    @ConditionalOnMissingBean(name = ConfigTransactionManagerNames.CONFIG)
    public PlatformTransactionManager configTransactionManager(
            @Qualifier("configEntityManagerFactory") EntityManagerFactory configEntityManagerFactory) {
        return new JpaTransactionManager(configEntityManagerFactory);
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(ConfigDataSourceIsolationAutoConfiguration.IsolationCondition.class)
    @ConditionalOnBean(name = "configEntityManagerFactory")
    @ConditionalOnProperty(
            prefix = "praxis.config.datasource.isolation",
            name = "enable-jpa-repositories",
            havingValue = "true",
            matchIfMissing = true)
    @EnableJpaRepositories(
            basePackages = "org.praxisplatform.config.repository",
            entityManagerFactoryRef = "configEntityManagerFactory",
            transactionManagerRef = ConfigTransactionManagerNames.CONFIG)
    static class IsolatedConfigJpaRepositoriesConfiguration {}

    static void ensureNonConfigPrimaryWhenNeeded(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        markNonConfigPrimary(beanFactory, DataSource.class, "dataSource", "configDataSource");
        markNonConfigPrimary(
                beanFactory,
                EntityManagerFactory.class,
                "entityManagerFactory",
                "configEntityManagerFactory");
        markNonConfigPrimary(
                beanFactory,
                PlatformTransactionManager.class,
                "transactionManager",
                ConfigTransactionManagerNames.CONFIG);
    }

    private static void markNonConfigPrimary(
            ConfigurableListableBeanFactory beanFactory,
            Class<?> beanType,
            String preferredPrimaryName,
            String configBeanName) {
        String[] names = beanFactory.getBeanNamesForType(beanType, true, false);
        if (names.length < 2) {
            return;
        }
        boolean hasPrimary = false;
        String fallbackCandidate = null;
        int nonConfigCount = 0;
        for (String name : names) {
            if (!beanFactory.containsBeanDefinition(name)) {
                continue;
            }
            BeanDefinition bd = beanFactory.getBeanDefinition(name);
            if (bd.isPrimary()) {
                hasPrimary = true;
            }
            if (!configBeanName.equals(name)) {
                nonConfigCount++;
                if (preferredPrimaryName.equals(name) || fallbackCandidate == null) {
                    fallbackCandidate = name;
                }
            }
        }
        if (!hasPrimary && nonConfigCount > 0 && fallbackCandidate != null) {
            beanFactory.getBeanDefinition(fallbackCandidate).setPrimary(true);
        }
    }

    private static void verifyDistinctFromPrimary(
            ConfigDataSourceProperties properties, Environment environment, String configUrl) {
        String primaryUrl = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(primaryUrl)) {
            return;
        }
        String primaryUser = trimToNull(environment.getProperty("spring.datasource.username"));
        String configUser = trimToNull(properties.getUsername());
        String primarySchema = trimToNull(environment.getProperty("spring.jpa.properties.hibernate.default_schema"));
        String configSchema = trimToNull(properties.getSchema());

        String normalizedPrimaryTarget = normalizeTarget(primaryUrl.trim());
        String normalizedConfigTarget = normalizeTarget(configUrl.trim());
        if (normalizedPrimaryTarget.equals(normalizedConfigTarget)
                && Objects.equals(primaryUser, configUser)
                && Objects.equals(primarySchema, configSchema)) {
            throw new IllegalStateException(
                    "config.datasource must target an isolated database, schema, or role distinct from spring.datasource.");
        }
    }

    static String normalizeTarget(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:")) {
            return jdbcUrl.toLowerCase(Locale.ROOT);
        }
        String stripped = jdbcUrl.substring("jdbc:".length());
        try {
            URI uri = URI.create(stripped);
            if (uri.getScheme() != null && uri.getHost() != null) {
                int port = uri.getPort();
                String portPart = port > 0 ? ":" + port : "";
                String path = uri.getPath() == null ? "" : uri.getPath();
                return (uri.getScheme() + "://" + uri.getHost() + portPart + path).toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) {
            // Subprotocols such as jdbc:h2:mem:db are not RFC-2396 hierarchical URIs.
        }
        int queryIdx = jdbcUrl.indexOf(';');
        if (queryIdx < 0) {
            queryIdx = jdbcUrl.indexOf('?');
        }
        String base = queryIdx >= 0 ? jdbcUrl.substring(0, queryIdx) : jdbcUrl;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(propertyName + " must not be blank.");
        }
        return value.trim();
    }

    private static String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return "";
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    static final class IsolationCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            boolean enabled =
                    env.getProperty("praxis.config.datasource.isolation.enabled", Boolean.class, true);
            if (!enabled) {
                return false;
            }
            return StringUtils.hasText(env.getProperty("config.datasource.url"));
        }
    }
}
