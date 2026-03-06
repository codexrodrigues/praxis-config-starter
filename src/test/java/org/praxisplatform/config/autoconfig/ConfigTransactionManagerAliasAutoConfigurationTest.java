package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class ConfigTransactionManagerAliasAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigTransactionManagerAliasAutoConfiguration.class));

    @Test
    void shouldAliasTransactionManagerWhenConfigTransactionManagerMissing() {
        PlatformTransactionManager txManager = new NoopTransactionManager();
        contextRunner
                .withBean("transactionManager", PlatformTransactionManager.class, () -> txManager)
                .run(context -> {
                    assertThat(context).hasBean(ConfigTransactionManagerNames.CONFIG);
                    assertThat(context.getBean(ConfigTransactionManagerNames.CONFIG))
                            .isSameAs(txManager);
                });
    }

    @Test
    void shouldAliasSingleTransactionManagerWhenDefaultNameIsMissing() {
        PlatformTransactionManager txManager = new NoopTransactionManager();
        contextRunner
                .withBean("apiTransactionManager", PlatformTransactionManager.class, () -> txManager)
                .run(context -> {
                    assertThat(context).hasBean(ConfigTransactionManagerNames.CONFIG);
                    assertThat(context.getBean(ConfigTransactionManagerNames.CONFIG))
                            .isSameAs(txManager);
                });
    }

    @Test
    void shouldNotOverrideExistingConfigTransactionManager() {
        PlatformTransactionManager primaryTx = new NoopTransactionManager();
        PlatformTransactionManager configTx = new NoopTransactionManager();
        contextRunner
                .withBean("transactionManager", PlatformTransactionManager.class, () -> primaryTx)
                .withBean(ConfigTransactionManagerNames.CONFIG, PlatformTransactionManager.class, () -> configTx)
                .run(context -> {
                    assertThat(context).hasBean(ConfigTransactionManagerNames.CONFIG);
                    assertThat(context.getBean(ConfigTransactionManagerNames.CONFIG))
                            .isSameAs(configTx);
                });
    }

    @Test
    void shouldFailWhenMultipleTransactionManagersExistWithoutDefaultName() {
        PlatformTransactionManager firstTx = new NoopTransactionManager();
        PlatformTransactionManager secondTx = new NoopTransactionManager();
        contextRunner
                .withBean("apiTransactionManager", PlatformTransactionManager.class, () -> firstTx)
                .withBean("anotherTransactionManager", PlatformTransactionManager.class, () -> secondTx)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Unable to infer 'configTransactionManager' alias");
                });
    }

    @Test
    void shouldFailFastWhenNoTransactionManagerCandidateExists() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unable to infer 'configTransactionManager' alias")
                    .hasMessageContaining("found 0");
        });
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {

        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            throw new UnsupportedOperationException("No-op transaction manager for bean wiring test.");
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {
            throw new UnsupportedOperationException("No-op transaction manager for bean wiring test.");
        }

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {
            throw new UnsupportedOperationException("No-op transaction manager for bean wiring test.");
        }
    }
}
