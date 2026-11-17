package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.controller.GovernedRulesStudioCompositionController;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedConceptRootSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyRegistry;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionEndpointOwner;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.facts.RuleFactContract;
import org.praxisplatform.rules.facts.RuleFactSchema;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder.Rule;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider;
import org.praxisplatform.uischema.domain.DomainRuleSetDescriptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Tag("unit")
class GovernedRulesStudioAutoConfigurationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T15:00:00Z");

    enum LeaveOp {
        CREATE("CREATE");
        final String key;

        LeaveOp(String key) {
            this.key = key;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestHostMocksConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        GovernedRuleFamilyRegistry governedRuleFamilyRegistry() throws Exception {
            return new GovernedRuleFamilyRegistry(List.of(buildFamilyWithConcept()));
        }

        @Bean
        DomainRuleService domainRuleService() {
            return mock(DomainRuleService.class);
        }

        @Bean
        DomainRuleSnapshotService domainRuleSnapshotService() {
            return mock(DomainRuleSnapshotService.class);
        }

        @Bean
        DomainRuleGovernancePrincipalResolver domainRuleGovernancePrincipalResolver() {
            return mock(DomainRuleGovernancePrincipalResolver.class);
        }
    }

    @Test
    void autoConfiguresCompositionControllerAndMetadataDomainCatalogBridge() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withConfiguration(
                                AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                        .withUserConfiguration(TestHostMocksConfiguration.class);

        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GovernedRulesStudioCompositionService.class);
                    assertThat(context)
                            .hasSingleBean(GovernedRulesStudioCompositionController.class);
                    assertThat(context).hasSingleBean(DomainRuleSetCatalogProvider.class);

                    DomainRuleSetCatalogProvider provider =
                            context.getBean(DomainRuleSetCatalogProvider.class);
                    List<DomainRuleSetDescriptor> found =
                            provider.findRuleSets("corp.leaves", "1");
                    assertThat(found).hasSize(1);
                    assertThat(found.get(0).ruleSetKey()).isEqualTo("corp.leaves");
                    assertThat(found.get(0).label()).isEqualTo("Regras de Afastamento");
                    assertThat(found.get(0).conceptRootBindings()).containsKey("employee");
                    assertThat(found.get(0).reasonDescriptors()).containsKey("LEAVE-001");
                });
    }

    @Test
    void backsOffCompositionControllerWhenHostOwnsEndpointMarker() {
        GovernedRulesStudioCompositionEndpointOwner customOwner =
                new GovernedRulesStudioCompositionEndpointOwner() {};

        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withUserConfiguration(TestHostMocksConfiguration.class)
                .withBean(GovernedRulesStudioCompositionEndpointOwner.class, () -> customOwner)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(GovernedRulesStudioCompositionService.class);
                            assertThat(context)
                                    .doesNotHaveBean(GovernedRulesStudioCompositionController.class);
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = GovernedRulesStudioCompositionController.class,
            useDefaultFilters = false,
            includeFilters =
                    @org.springframework.context.annotation.ComponentScan.Filter(
                            type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                            classes = GovernedRulesStudioCompositionController.class))
    static class ComponentScannedControllerConfiguration {}

    @Test
    void backsOffCompositionControllerEvenWhenHostComponentScansStarterPackage() {
        GovernedRulesStudioCompositionEndpointOwner customOwner =
                new GovernedRulesStudioCompositionEndpointOwner() {};

        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withUserConfiguration(
                        TestHostMocksConfiguration.class,
                        ComponentScannedControllerConfiguration.class)
                .withBean(GovernedRulesStudioCompositionEndpointOwner.class, () -> customOwner)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(GovernedRulesStudioCompositionService.class);
                            assertThat(context)
                                    .doesNotHaveBean(GovernedRulesStudioCompositionController.class);
                        });
    }

    @Test
    void startsCleanlyWithoutMetadataStarterOnClasspath() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(DomainRuleSetCatalogProvider.class))
                .withUserConfiguration(TestHostMocksConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(GovernedRulesStudioCompositionService.class);
                            assertThat(context)
                                    .hasSingleBean(GovernedRulesStudioCompositionController.class);
                        });
    }

    private static GovernedRuleFamilyDescriptor<LeaveOp> buildFamilyWithConcept() throws Exception {
        Rule r1 =
                new Rule(
                        "LEAVE-001",
                        "days",
                        "Servidor ativo exigido.",
                        "LEAVE_POLICY",
                        "employee.active == true",
                        "{\"==\":[{\"var\":\"employee.active\"},true]}");
        RuleSetDefinition productRuleSet =
                OntologyRuleSetBuilder.requireReferencedFactPaths(
                        OntologyRuleSetBuilder.definition(
                                new RuleSetRef("hr-service", "corp.leaves", "corp.leaves", "CREATE", 1),
                                "corp.leaves.create",
                                "customer.",
                                List.of(r1),
                                List.of(),
                                false,
                                Map.of()));
        String schemaJson =
                """
                {
                  "type": "object",
                  "required": ["employee", "context"],
                  "additionalProperties": false,
                  "maxProperties": 2,
                  "properties": {
                    "employee": {
                      "type": "object",
                      "required": ["active"],
                      "additionalProperties": false,
                      "maxProperties": 1,
                      "properties": {
                        "active": { "type": "boolean" }
                      }
                    },
                    "context": {
                      "type": "object",
                      "required": ["tenantId"],
                      "additionalProperties": false,
                      "maxProperties": 1,
                      "properties": {
                        "tenantId": { "type": "string", "maxLength": 64 }
                      }
                    }
                  }
                }
                """;
        RuleFactContract contract =
                new RuleFactContract(
                        "host/1.0",
                        "CREATE",
                        "provider/1.0",
                        RuleFactSchema.compile(JSON.readTree(schemaJson)),
                        List.of());
        GovernedHostRuleBundleRuntime<LeaveOp> runtime =
                new GovernedHostRuleBundleRuntime<>(
                        LeaveOp.class,
                        "hr-service",
                        "corp.leaves",
                        "host/1.0",
                        "provider/1.0",
                        List.of(new OperationSpec<>(LeaveOp.CREATE, "CREATE", productRuleSet, contract)),
                        requested -> Optional.empty(),
                        Clock.fixed(NOW, ZoneId.of("UTC")),
                        ZoneId.of("UTC"),
                        RuleBindingExecutorRegistry.empty(),
                        OntologyRuleSetBuilder.catalogOf(List.of(r1)));
        return new GovernedRuleFamilyDescriptor<>(runtime)
                .withMetadata(
                        "corp.leaves",
                        "Regras de Afastamento",
                        "Regras corporativas de concessão de afastamento.",
                        Map.of(
                                "employee",
                                new GovernedConceptRootSpec(
                                        "leave_requester",
                                        "hr-service",
                                        "corp",
                                        "corp.employees")));
    }
}
