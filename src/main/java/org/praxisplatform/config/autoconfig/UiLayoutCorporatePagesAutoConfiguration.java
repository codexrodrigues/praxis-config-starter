package org.praxisplatform.config.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.praxisplatform.config.service.CorporatePageCatalogContributor;
import org.praxisplatform.config.service.CorporatePageRuntimeSupport;
import org.praxisplatform.config.service.EffectiveUiAudienceProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeNavigationProvider;
import org.praxisplatform.config.service.PageCreationHost;
import org.praxisplatform.config.service.PageLifecycleAdmission;
import org.praxisplatform.config.service.PageRuntimeReadService;
import org.praxisplatform.config.service.UiLayoutBaselineMetadataAdmission;
import org.praxisplatform.config.service.UiLayoutDraftWorkspaceSource;
import org.praxisplatform.config.service.UiLayoutKpiCatalogOracle;
import org.praxisplatform.config.service.UiLayoutLifecycleAdmission;
import org.praxisplatform.config.service.UiLayoutLifecycleInvocationProvider;
import org.praxisplatform.config.service.UiLayoutLifecycleStructureValidator;
import org.praxisplatform.config.service.UiLayoutReleaseValidator;
import org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuração zero-boilerplate para páginas dinâmicas corporativas e catálogo de navegação
 * a partir de um único bean {@link CorporatePageCatalogContributor}.
 *
 * <p>Permanece completamente inativa enquanto o host não registrar um
 * {@link CorporatePageCatalogContributor}, preservando a política fail-closed padrão do starter.
 * Cada um dos 10 SPIs gerados é protegido por {@code @ConditionalOnMissingBean}, permitindo
 * customização pontual sem abrir mão do restante da infraestrutura.
 */
@AutoConfiguration(
        after = UiLayoutKpiCatalogAutoConfiguration.class,
        before = {
            UiLayoutResolutionAutoConfiguration.class,
            EnterpriseRuntimeAutoConfiguration.class
        })
@ConditionalOnBean(CorporatePageCatalogContributor.class)
public class UiLayoutCorporatePagesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CorporatePageRuntimeSupport corporatePageRuntimeSupport(
            CorporatePageCatalogContributor contributor,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<Clock> clockProvider,
            ObjectProvider<PageRuntimeReadService> pageRuntimeReadServiceProvider,
            ObjectProvider<UiLayoutKpiCatalogOracle> kpiCatalogOracleProvider) {
        ObjectMapper mapper =
                objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new CorporatePageRuntimeSupport(
                contributor, mapper, clock, pageRuntimeReadServiceProvider, kpiCatalogOracleProvider);
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutLifecycleInvocationProvider.class)
    UiLayoutLifecycleInvocationProvider corporateUiLayoutLifecycleInvocationProvider(
            CorporatePageRuntimeSupport support) {
        return support.invocationProvider();
    }

    @Bean
    @ConditionalOnMissingBean({PageLifecycleAdmission.class, UiLayoutLifecycleAdmission.class})
    PageLifecycleAdmission corporatePageLifecycleAdmission(CorporatePageRuntimeSupport support) {
        return support.pageLifecycleAdmission();
    }

    @Bean
    @ConditionalOnMissingBean(EffectiveUiAudienceProvider.class)
    EffectiveUiAudienceProvider corporateEffectiveUiAudienceProvider(
            CorporatePageRuntimeSupport support) {
        return support.audienceProvider();
    }

    @Bean
    @ConditionalOnMissingBean(EnterpriseRuntimeNavigationProvider.class)
    EnterpriseRuntimeNavigationProvider corporateEnterpriseRuntimeNavigationProvider(
            CorporatePageRuntimeSupport support) {
        return support.navigationProvider();
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutDraftWorkspaceSource.class)
    UiLayoutDraftWorkspaceSource corporateUiLayoutDraftWorkspaceSource(
            CorporatePageRuntimeSupport support) {
        return support.draftWorkspaceSource();
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutBaselineMetadataAdmission.class)
    UiLayoutBaselineMetadataAdmission corporateUiLayoutBaselineMetadataAdmission(
            CorporatePageRuntimeSupport support) {
        return support.baselineMetadataAdmission();
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutValidationBudgetPolicy.class)
    UiLayoutValidationBudgetPolicy corporateUiLayoutValidationBudgetPolicy(
            CorporatePageRuntimeSupport support) {
        return support.validationBudgetPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutReleaseValidator.class)
    UiLayoutReleaseValidator corporateUiLayoutReleaseValidator(CorporatePageRuntimeSupport support) {
        return support.releaseValidator();
    }

    @Bean
    @ConditionalOnMissingBean(UiLayoutLifecycleStructureValidator.class)
    UiLayoutLifecycleStructureValidator corporateUiLayoutLifecycleStructureValidator(
            CorporatePageRuntimeSupport support) {
        return support.structureValidator();
    }

    @Bean
    @ConditionalOnMissingBean(PageCreationHost.class)
    PageCreationHost corporatePageCreationHost(CorporatePageRuntimeSupport support) {
        return support.pageCreationHost();
    }
}
