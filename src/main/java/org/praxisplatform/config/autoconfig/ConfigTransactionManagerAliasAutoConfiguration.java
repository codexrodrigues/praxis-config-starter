package org.praxisplatform.config.autoconfig;

import java.util.Arrays;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration
@ConditionalOnClass(PlatformTransactionManager.class)
public class ConfigTransactionManagerAliasAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ConfigTransactionManagerNames.CONFIG)
    static SmartInitializingSingleton configTransactionManagerAliasInitializer(
            ConfigurableApplicationContext applicationContext) {
        return () -> {
            var beanFactory = applicationContext.getBeanFactory();
            if (!(beanFactory instanceof BeanDefinitionRegistry)) {
                return;
            }
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

            if (beanFactory.containsBean(ConfigTransactionManagerNames.CONFIG)
                    || registry.isAlias(ConfigTransactionManagerNames.CONFIG)) {
                return;
            }

            boolean hasDefaultTransactionManager = beanFactory.containsBean("transactionManager");
            if (hasDefaultTransactionManager) {
                registry.registerAlias("transactionManager", ConfigTransactionManagerNames.CONFIG);
                return;
            }

            String[] txManagerBeanNames = Arrays.stream(BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                            beanFactory,
                            PlatformTransactionManager.class,
                            true,
                            false))
                    .filter(beanName -> !ConfigTransactionManagerNames.CONFIG.equals(beanName))
                    .toArray(String[]::new);

            if (txManagerBeanNames.length == 1) {
                registry.registerAlias(txManagerBeanNames[0], ConfigTransactionManagerNames.CONFIG);
                return;
            }

            throw new IllegalStateException(
                    "Unable to infer '" + ConfigTransactionManagerNames.CONFIG + "' alias: expected bean named "
                            + "'transactionManager' or exactly one PlatformTransactionManager candidate, but found "
                            + txManagerBeanNames.length + " (" + String.join(", ", txManagerBeanNames) + ").");
        };
    }
}
