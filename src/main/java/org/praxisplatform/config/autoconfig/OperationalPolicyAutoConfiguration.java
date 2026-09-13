package org.praxisplatform.config.autoconfig;

import java.time.Clock;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.OperationalPolicyService;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/** Embedded read surface backed exclusively by the canonical Config transaction and repositories. */
@AutoConfiguration(after = {ConfigTransactionManagerAliasAutoConfiguration.class, DomainRuleSnapshotAutoConfiguration.class})
@ConditionalOnBean({DomainRuleMaterializationRepository.class, DomainRuleService.class})
public class OperationalPolicyAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    OperationalPolicyService operationalPolicyService(DomainRuleMaterializationRepository materializations, DomainRuleService projections,
            @Qualifier(ConfigTransactionManagerNames.CONFIG) PlatformTransactionManager transactions) {
        return new OperationalPolicyService(materializations, projections, transactions, Clock.systemUTC());
    }
}
