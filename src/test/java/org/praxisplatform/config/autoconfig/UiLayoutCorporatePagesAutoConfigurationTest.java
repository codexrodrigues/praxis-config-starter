package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.EnterpriseRuntimeContextRequest;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationNode;
import org.praxisplatform.config.dto.EnterpriseRuntimeNavigationResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;
import org.praxisplatform.config.service.CorporatePageCatalogContributor;
import org.praxisplatform.config.service.EffectiveUiAudience;
import org.praxisplatform.config.service.EffectiveUiAudienceProvider;
import org.praxisplatform.config.service.EnterpriseRuntimeNavigationProvider;
import org.praxisplatform.config.service.PageCreationHost;
import org.praxisplatform.config.service.PageLifecycleAdmission;
import org.praxisplatform.config.service.UiLayoutBaselineMetadataAdmission;
import org.praxisplatform.config.service.UiLayoutDraftWorkspaceSource;
import org.praxisplatform.config.service.UiLayoutLifecycleAdmission;
import org.praxisplatform.config.service.UiLayoutLifecycleException;
import org.praxisplatform.config.service.UiLayoutLifecycleInvocation;
import org.praxisplatform.config.service.UiLayoutLifecycleInvocationProvider;
import org.praxisplatform.config.service.UiLayoutLifecycleOperation;
import org.praxisplatform.config.service.UiLayoutLifecycleStructureValidator;
import org.praxisplatform.config.service.UiLayoutReleaseValidator;
import org.praxisplatform.config.service.UiLayoutValidationAttempt;
import org.praxisplatform.config.service.UiLayoutValidationBudgetPolicy;
import org.praxisplatform.config.service.UiLayoutValidationContext;
import org.praxisplatform.config.service.UiLayoutValidationPurpose;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class UiLayoutCorporatePagesAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(UiLayoutCorporatePagesAutoConfiguration.class));

    @Test
    void backsOffCompletelyWithoutCorporatePageCatalogContributor() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PageCreationHost.class);
                    assertThat(context).doesNotHaveBean(UiLayoutLifecycleInvocationProvider.class);
                    assertThat(context).doesNotHaveBean(EnterpriseRuntimeNavigationProvider.class);
                });
    }

    @Test
    void registersAllTenSpisAndEnforcesFailClosedInvocationChecks() {
        runner.withUserConfiguration(WorkforceCatalogConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(UiLayoutLifecycleInvocationProvider.class);
                            assertThat(context).hasSingleBean(PageLifecycleAdmission.class);
                            assertThat(context).hasSingleBean(UiLayoutLifecycleAdmission.class);
                            assertThat(context).hasSingleBean(EffectiveUiAudienceProvider.class);
                            assertThat(context).hasSingleBean(EnterpriseRuntimeNavigationProvider.class);
                            assertThat(context).hasSingleBean(UiLayoutDraftWorkspaceSource.class);
                            assertThat(context).hasSingleBean(UiLayoutBaselineMetadataAdmission.class);
                            assertThat(context).hasSingleBean(UiLayoutValidationBudgetPolicy.class);
                            assertThat(context).hasSingleBean(UiLayoutReleaseValidator.class);
                            assertThat(context).hasSingleBean(UiLayoutLifecycleStructureValidator.class);
                            assertThat(context).hasSingleBean(PageCreationHost.class);

                            UiLayoutLifecycleInvocationProvider invocations =
                                    context.getBean(UiLayoutLifecycleInvocationProvider.class);
                            UiLayoutTarget pageTarget =
                                    new UiLayoutTarget(
                                            "praxis-dynamic-page",
                                            WorkforceCatalogConfiguration.PAGE_ID.toString());

                            assertThatThrownBy(() -> invocations.resolve(null, "1", pageTarget))
                                    .isInstanceOf(UiLayoutLifecycleException.class)
                                    .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                                    .isEqualTo(UiLayoutLifecycleException.Code.SESSION_REQUIRED);

                            Principal principal = () -> "alice.analyst";
                            UiLayoutLifecycleInvocation valid =
                                    invocations.resolve(principal, "1", pageTarget);
                            assertThat(valid.actorRef()).isEqualTo("alice.analyst");

                            UiLayoutTarget mismatchedGroupsTarget =
                                    new UiLayoutTarget("praxis-app-shell", UUID.randomUUID().toString());
                            assertThatThrownBy(
                                            () -> invocations.resolve(principal, "1", mismatchedGroupsTarget))
                                    .isInstanceOf(UiLayoutLifecycleException.class)
                                    .extracting(ex -> ((UiLayoutLifecycleException) ex).getCode())
                                    .isEqualTo(UiLayoutLifecycleException.Code.DENIED);

                            EffectiveUiAudience audience =
                                    context.getBean(EffectiveUiAudienceProvider.class)
                                            .resolve(principal, "1");
                            assertThat(audience.user()).isEqualTo("alice.analyst");

                            assertThatThrownBy(
                                            () ->
                                                    context.getBean(
                                                                    EnterpriseRuntimeNavigationProvider
                                                                            .class)
                                                            .getNavigation(
                                                                    new EnterpriseRuntimeContextRequest(
                                                                            principal,
                                                                            "pt-BR",
                                                                            "UTC",
                                                                            "workforce"),
                                                                    "1",
                                                                    null,
                                                                    null,
                                                                    50))
                                    .isInstanceOf(ResponseStatusException.class)
                                    .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

                            CorporatePageCatalogContributor contributor =
                                    context.getBean(CorporatePageCatalogContributor.class);
                            UUID rootGroupsId =
                                    contributor.navigationGroupsRootId("default", "prod");
                            UiLayoutLifecycleInvocation groupsInvocation =
                                    invocations.resolve(
                                            principal,
                                            "1",
                                            new UiLayoutTarget(
                                                    "praxis-app-shell", rootGroupsId.toString()));
                            try (var attempt =
                                    UiLayoutValidationAttempt.start(
                                            groupsInvocation,
                                            UiLayoutLifecycleOperation.CREATE_DRAFT,
                                            Duration.ofSeconds(10))) {
                                var validationContext =
                                        new UiLayoutValidationContext(
                                                attempt, UiLayoutValidationPurpose.AUTHORING);
                                var groupsSeed =
                                        context.getBean(UiLayoutDraftWorkspaceSource.class)
                                                .capture(groupsInvocation, validationContext);
                                assertThat(groupsSeed.targets()).hasSize(1);
                                assertThat(groupsSeed.targets().getFirst().document().toString())
                                        .contains("Workforce Operations");
                            }
                        });

        runner.withUserConfiguration(
                        WorkforceCatalogConfiguration.class,
                        ActiveRuntimeNavigationConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            Principal principal = () -> "alice.analyst";
                            EnterpriseRuntimeNavigationResponse navigation =
                                    context.getBean(EnterpriseRuntimeNavigationProvider.class)
                                            .getNavigation(
                                                    new EnterpriseRuntimeContextRequest(
                                                            principal, "pt-BR", "UTC", "workforce"),
                                                    "1",
                                                    null,
                                                    null,
                                                    50);
                            assertThat(navigation.nodes())
                                    .extracting(EnterpriseRuntimeNavigationNode::label)
                                    .contains("Workforce Operations");
                        });
    }

    @Test
    void allowsIndividualSpiOverrideViaConditionalOnMissingBean() {
        runner.withUserConfiguration(
                        WorkforceCatalogConfiguration.class, CustomBudgetOverrideConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            UiLayoutValidationBudgetPolicy budgetPolicy =
                                    context.getBean(UiLayoutValidationBudgetPolicy.class);
                            assertThat(
                                            budgetPolicy.budget(
                                                    UiLayoutLifecycleOperation.CREATE_DRAFT, null))
                                    .isEqualTo(Duration.ofSeconds(90));
                            assertThat(context).hasSingleBean(PageCreationHost.class);
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class WorkforceCatalogConfiguration {

        static final UUID GROUP_ID =
                UUID.nameUUIDFromBytes("workforce-group".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        static final UUID PAGE_ID =
                UUID.nameUUIDFromBytes("workforce-overview-page".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Bean
        CorporatePageCatalogContributor corporatePageCatalogContributor() {
            ObjectNode document = MAPPER.createObjectNode();
            document.put("schemaVersion", "praxis.page.v1");
            document.put("pageId", PAGE_ID.toString());
            document.put("title", "Workforce Analytics");
            document.putArray("widgets")
                    .addObject()
                    .put("id", "kpi-summary")
                    .put("type", "praxis-analytics-presentation")
                    .putObject("config")
                    .put("metric", "headcount");

            return new CorporatePageCatalogContributor() {
                @Override
                public String applicationId() {
                    return "workforce-suite";
                }

                @Override
                public List<NavigationGroupSeed> navigationGroups() {
                    return List.of(new NavigationGroupSeed(GROUP_ID, "Workforce Operations", "groups", 10));
                }

                @Override
                public List<NativePageSeed> nativePages() {
                    return List.of(
                            new NativePageSeed(
                                    "workforce-overview",
                                    "rev-1",
                                    PAGE_ID,
                                    document,
                                    "Workforce Analytics",
                                    "analytics"));
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBudgetOverrideConfiguration {

        @Bean
        UiLayoutValidationBudgetPolicy uiLayoutValidationBudgetPolicy() {
            return (operation, invocation) -> Duration.ofSeconds(90);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ActiveRuntimeNavigationConfiguration {

        @Bean
        EnterpriseRuntimeNavigationProvider enterpriseRuntimeNavigationProvider() {
            EnterpriseRuntimeNavigationNode node =
                    new EnterpriseRuntimeNavigationNode(
                            WorkforceCatalogConfiguration.GROUP_ID.toString(),
                            "Workforce Operations",
                            "groups",
                            "group",
                            "/pages/workforce",
                            "/pages/workforce",
                            "workforce",
                            null,
                            null,
                            null,
                            null,
                            List.of());
            return (request, expectedContextVersion, parentRef, cursor, pageSize) ->
                    new EnterpriseRuntimeNavigationResponse(
                            EnterpriseRuntimeNavigationResponse.SCHEMA_VERSION,
                            List.of(node),
                            List.of("navigation"),
                            Instant.now(),
                            expectedContextVersion,
                            "etag-nav-1",
                            List.of(),
                            null);
        }
    }
}
