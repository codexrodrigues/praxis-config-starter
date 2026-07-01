package org.praxisplatform.config.autoconfig;

import org.praxisplatform.config.controller.EnterpriseRuntimeContextController;
import org.praxisplatform.config.service.AiPrincipalContextResolver;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.DefaultEnterpriseRuntimeTenantProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeContextProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeTenantProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(EnterpriseRuntimeContextProvider.class)
public class EnterpriseRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiPrincipalContextResolver aiPrincipalContextResolver(
            @Value("${praxis.ai.security.corporate-mode:true}") boolean corporateMode,
            @Value("${praxis.ai.security.allow-header-identity-in-local:false}") boolean allowHeaderIdentityInLocal,
            @Value("${praxis.ai.security.local-default-tenant:demo}") String localDefaultTenant,
            @Value("${praxis.ai.security.local-default-user:demo}") String localDefaultUser,
            @Value("${praxis.ai.security.local-default-environment:local}") String localDefaultEnvironment,
            @Value("${praxis.ai.security.server-default-environment:prod}") String serverDefaultEnvironment) {
        return new AiPrincipalContextResolver(
                corporateMode,
                allowHeaderIdentityInLocal,
                localDefaultTenant,
                localDefaultUser,
                localDefaultEnvironment,
                serverDefaultEnvironment);
    }

    @Bean
    @ConditionalOnMissingBean
    public EnterpriseRuntimeContextProvider enterpriseRuntimeContextProvider() {
        return new DefaultEnterpriseRuntimeContextProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public EnterpriseRuntimeTenantProvider enterpriseRuntimeTenantProvider() {
        return new DefaultEnterpriseRuntimeTenantProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public EnterpriseRuntimeContextController enterpriseRuntimeContextController(
            AiPrincipalContextResolver principalContextResolver,
            EnterpriseRuntimeContextProvider runtimeContextProvider,
            EnterpriseRuntimeTenantProvider runtimeTenantProvider) {
        return new EnterpriseRuntimeContextController(
                principalContextResolver,
                runtimeContextProvider,
                runtimeTenantProvider);
    }
}
