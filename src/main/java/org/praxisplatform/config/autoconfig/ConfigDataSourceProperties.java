package org.praxisplatform.config.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades do {@code DataSource} dedicado e isolado para persistência do {@code praxis-config-starter}.
 *
 * <p>Ativado automaticamente por {@link ConfigDataSourceIsolationAutoConfiguration} quando
 * {@code config.datasource.url} é informado.
 */
@ConfigurationProperties(prefix = "config.datasource")
public class ConfigDataSourceProperties {

    /** URL JDBC dedicada para o armazenamento isolado de configurações e governança. */
    private String url;

    /** Usuário JDBC dedicado do banco de configurações. */
    private String username;

    /** Senha JDBC dedicada do banco de configurações. */
    private String password;

    /** Nome completo da classe do driver JDBC (opcional; inferido pela URL quando omitido). */
    private String driverClassName;

    /** Schema padrão aplicado às conexões e ao {@code EntityManagerFactory} do Config Store. */
    private String schema;

    /** Nome do pool Hikari do {@code DataSource} de configuração. */
    private String poolName = "praxis-config";

    /** Tamanho máximo do pool Hikari dedicado ao Config Store. */
    private int maximumPoolSize = 8;

    /** Quantidade mínima de conexões ociosas mantidas no pool do Config Store (0 favorece scale-to-zero). */
    private int minimumIdle = 0;

    /** Tempo máximo de ociosidade antes de encerrar conexões excedentes no pool, em milissegundos. */
    private long idleTimeoutMs = 120_000L;

    /** Tempo máximo de vida de uma conexão no pool, em milissegundos. */
    private long maxLifetimeMs = 600_000L;

    /** Intervalo de keepalive periódico de conexões ociosas, em milissegundos (0 desabilita). */
    private long keepaliveTimeMs = 0L;

    /** Tempo limite de aquisição de conexão no pool, em milissegundos. */
    private long connectionTimeoutMs = 10_000L;

    /** Tempo limite de validação de conexão no pool, em milissegundos. */
    private long validationTimeoutMs = 5_000L;

    /** Quando verdadeiro, exige explicitamente URL PostgreSQL ({@code jdbc:postgresql:...}). */
    private boolean requirePostgresql = false;

    /**
     * Quando verdadeiro, falha na inicialização se {@code config.datasource} apontar para o
     * mesmo alvo JDBC/usuário/schema que {@code spring.datasource}.
     */
    private boolean failIfMatchesPrimary = true;

    /** Estratégia {@code hibernate.hbm2ddl.auto} aplicada ao EntityManager do Config Store. */
    private String ddlAuto;

    /** Habilita log SQL do EntityManager do Config Store. */
    private boolean showSql = false;

    /** Dialeto Hibernate opcional explícito para o EntityManager do Config Store. */
    private String databasePlatform;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(String poolName) {
        this.poolName = poolName;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public int getMinimumIdle() {
        return minimumIdle;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public long getMaxLifetimeMs() {
        return maxLifetimeMs;
    }

    public void setMaxLifetimeMs(long maxLifetimeMs) {
        this.maxLifetimeMs = maxLifetimeMs;
    }

    public long getKeepaliveTimeMs() {
        return keepaliveTimeMs;
    }

    public void setKeepaliveTimeMs(long keepaliveTimeMs) {
        this.keepaliveTimeMs = keepaliveTimeMs;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public long getValidationTimeoutMs() {
        return validationTimeoutMs;
    }

    public void setValidationTimeoutMs(long validationTimeoutMs) {
        this.validationTimeoutMs = validationTimeoutMs;
    }

    public boolean isRequirePostgresql() {
        return requirePostgresql;
    }

    public void setRequirePostgresql(boolean requirePostgresql) {
        this.requirePostgresql = requirePostgresql;
    }

    public boolean isFailIfMatchesPrimary() {
        return failIfMatchesPrimary;
    }

    public void setFailIfMatchesPrimary(boolean failIfMatchesPrimary) {
        this.failIfMatchesPrimary = failIfMatchesPrimary;
    }

    public String getDdlAuto() {
        return ddlAuto;
    }

    public void setDdlAuto(String ddlAuto) {
        this.ddlAuto = ddlAuto;
    }

    public boolean isShowSql() {
        return showSql;
    }

    public void setShowSql(boolean showSql) {
        this.showSql = showSql;
    }

    public String getDatabasePlatform() {
        return databasePlatform;
    }

    public void setDatabasePlatform(String databasePlatform) {
        this.databasePlatform = databasePlatform;
    }
}
