package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHead;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadActivationType;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSet;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetReader;
import org.praxisplatform.config.contract.PublishedRuleSnapshotHeadSetScope;
import org.praxisplatform.config.controller.GovernedRulesStudioCompositionController;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime;
import org.praxisplatform.config.runtime.GovernedHostRuleBundleRuntime.OperationSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedConceptRootSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedRuleDocumentationSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyDescriptor.GovernedRuleLegalBasisSpec;
import org.praxisplatform.config.runtime.GovernedRuleFamilyRegistry;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionEndpointOwner;
import org.praxisplatform.config.runtime.GovernedRulesStudioCompositionService;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipalResolver;
import org.praxisplatform.config.service.DomainRuleService;
import org.praxisplatform.config.service.DomainRuleSnapshotService;
import org.praxisplatform.rules.contract.CompositionPolicy;
import org.praxisplatform.rules.contract.DecisionAggregationPolicy;
import org.praxisplatform.rules.contract.DecisionBinding;
import org.praxisplatform.rules.contract.DecisionSlot;
import org.praxisplatform.rules.contract.DecisionSource;
import org.praxisplatform.rules.contract.DecisionStage;
import org.praxisplatform.rules.contract.OverridePolicy;
import org.praxisplatform.rules.contract.PublishedRuleSnapshot;
import org.praxisplatform.rules.contract.RuleDecision;
import org.praxisplatform.rules.contract.RuleExecutorRef;
import org.praxisplatform.rules.contract.RuleFailPolicy;
import org.praxisplatform.rules.contract.RuleParameterDefinition;
import org.praxisplatform.rules.contract.RuleParameterTemplate;
import org.praxisplatform.rules.contract.RuleParameterType;
import org.praxisplatform.rules.contract.RuleParameterization;
import org.praxisplatform.rules.contract.RuleRuntimeCompatibility;
import org.praxisplatform.rules.contract.RuleSetDefinition;
import org.praxisplatform.rules.contract.RuleSetRef;
import org.praxisplatform.rules.contract.RuleSnapshotApproval;
import org.praxisplatform.rules.contract.RuleSnapshotSource;
import org.praxisplatform.rules.contract.SlotCardinality;
import org.praxisplatform.rules.facts.RuleFactContract;
import org.praxisplatform.rules.facts.RuleFactSchema;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder;
import org.praxisplatform.rules.ontology.OntologyRuleSetBuilder.Rule;
import org.praxisplatform.rules.runtime.RuleBindingExecutorRegistry;
import org.praxisplatform.rules.violation.RuleErrorDescriptor;
import org.praxisplatform.uischema.action.ActionDefinition;
import org.praxisplatform.uischema.action.ActionDefinitionRegistry;
import org.praxisplatform.uischema.action.ActionExecutionContract;
import org.praxisplatform.uischema.action.ActionScope;
import org.praxisplatform.uischema.configuration.OpenApiUiSchemaAutoConfiguration;
import org.praxisplatform.uischema.domain.DomainCatalogResponse;
import org.praxisplatform.uischema.domain.DomainRuleSetCatalogProvider;
import org.praxisplatform.uischema.domain.DomainRuleSetDescriptor;
import org.praxisplatform.uischema.domain.RuleDocumentationDescriptor;
import org.praxisplatform.uischema.domain.SemanticDomainCatalogService;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.options.OptionSourceRegistry;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.surface.SurfaceDefinition;
import org.praxisplatform.uischema.surface.SurfaceDefinitionRegistry;
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
                .withClassLoader(
                        new FilteredClassLoader(
                                DomainRuleSetCatalogProvider.class,
                                RuleDocumentationDescriptor.class))
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

    @Test
    void prioritizesGovernedFamilyBridgeOverOpenApiUiSchemaFallbackRegardlessOfAutoConfigOrder() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                OpenApiUiSchemaAutoConfiguration.class,
                                GovernedRulesStudioAutoConfiguration.class))
                .withUserConfiguration(TestHostMocksConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(DomainRuleSetCatalogProvider.class);
                            DomainRuleSetCatalogProvider provider =
                                    context.getBean(DomainRuleSetCatalogProvider.class);
                            List<DomainRuleSetDescriptor> found =
                                    provider.findRuleSets("corp.leaves", "1");
                            assertThat(found).hasSize(1);
                            assertThat(found.get(0).ruleSetDocumentation()).isNotNull();
                            assertThat(found.get(0).ruleSetDocumentation().title())
                                    .isEqualTo("Governança de Afastamentos");
                            assertThat(found.get(0).ruleDocumentation()).containsKey("LEAVE-001");
                        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergesTenantDidacticDocumentationAndErrorDescriptorsAcrossParameterizeReplaceAndAugment()
            throws Exception {
        var basis =
                GovernedRuleLegalBasisSpec.of(
                        "CLT Art. 129",
                        "Consolidação das Leis do Trabalho",
                        "Art. 129",
                        "Federal",
                        "Garante período regular de afastamento.",
                        null);
        var paramBaselineDoc =
                GovernedRuleDocumentationSpec.of(
                        "Limite de dias parametrizável",
                        "Verifica o limite máximo de dias do afastamento.",
                        "Ao registrar solicitação.",
                        "Evita afastamentos acima do limite corporativo.",
                        List.of(basis),
                        "Ajustável por parâmetro no Policy Studio.");
        var replaceBaselineDoc =
                GovernedRuleDocumentationSpec.of(
                        "Elegibilidade padrão",
                        "Verifica elegibilidade padrão do produto.",
                        "Na criação.",
                        "Garante conformidade básica.",
                        List.of(basis),
                        "Substituível pelo cliente.");
        var augmentProductDoc =
                GovernedRuleDocumentationSpec.of(
                        "Regra fixa base",
                        "Verifica pré-requisito base.",
                        "Sempre.",
                        "Integridade mínima.",
                        List.of(basis),
                        "Extensível com novas regras do cliente.");

        var template =
                new RuleParameterTemplate(
                        "corp.leaves:max-days",
                        "1.0",
                        RuleExecutorRef.jsonLogic(
                                JSON.readTree("{\"<=\":[{\"var\":\"draft.days\"},30]}")),
                        RuleDecision.DENY,
                        "LEAVE-PARAM-001",
                        List.of("draft.days"),
                        List.of(
                                new RuleParameterDefinition(
                                        "maxDays",
                                        RuleParameterType.INTEGER,
                                        true,
                                        false,
                                        false,
                                        "dias",
                                        JSON.getNodeFactory().numberNode(30),
                                        java.math.BigDecimal.ONE,
                                        java.math.BigDecimal.valueOf(365),
                                        List.of(),
                                        List.of("/<=/1"))));

        RuleSetDefinition productRuleSet =
                new RuleSetDefinition(
                        new RuleSetRef("hr-service", "corp.leaves", "corp.leaves", "CREATE", 1),
                        List.of("context", "draft"),
                        List.of(
                                new DecisionSlot(
                                        "leaves.param-slot",
                                        DecisionStage.DOMAIN_DECISION,
                                        SlotCardinality.SINGLE,
                                        OverridePolicy.PARAMETERIZABLE,
                                        DecisionAggregationPolicy.SINGLE_RESULT,
                                        template,
                                        List.of()),
                                new DecisionSlot(
                                        "leaves.replace-slot",
                                        DecisionStage.DOMAIN_DECISION,
                                        SlotCardinality.SINGLE,
                                        OverridePolicy.REPLACEABLE,
                                        DecisionAggregationPolicy.SINGLE_RESULT,
                                        null,
                                        List.of()),
                                new DecisionSlot(
                                        "leaves.augment-slot",
                                        DecisionStage.DOMAIN_DECISION,
                                        SlotCardinality.MULTIPLE,
                                        OverridePolicy.RESTRICT_ONLY,
                                        DecisionAggregationPolicy.DENY_OVERRIDES,
                                        null,
                                        List.of())),
                        List.of(
                                new DecisionBinding(
                                        "product.leaves.param",
                                        "leaves.param-slot",
                                        DecisionSource.PRODUCT,
                                        null,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\"<=\":[{\"var\":\"draft.days\"},30]}")),
                                        List.of(),
                                        1,
                                        true,
                                        RuleDecision.DENY,
                                        "LEAVE-PARAM-001",
                                        List.of("draft.days"),
                                        null),
                                new DecisionBinding(
                                        "product.leaves.replace",
                                        "leaves.replace-slot",
                                        DecisionSource.PRODUCT,
                                        null,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\"!=\":[{\"var\":\"draft.reason\"},null]}")),
                                        List.of(),
                                        1,
                                        true,
                                        RuleDecision.DENY,
                                        "LEAVE-REP-001",
                                        List.of("draft.reason"),
                                        null),
                                new DecisionBinding(
                                        "product.leaves.augment",
                                        "leaves.augment-slot",
                                        DecisionSource.PRODUCT,
                                        null,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\"!=\":[{\"var\":\"context.tenantId\"},null]}")),
                                        List.of(),
                                        1,
                                        true,
                                        RuleDecision.DENY,
                                        "LEAVE-AUG-001",
                                        List.of("context.tenantId"),
                                        null)),
                        RuleRuntimeCompatibility.current(),
                        RuleFailPolicy.FAIL_CLOSED);

        String schemaJson =
                """
                {
                  "type": "object",
                  "required": ["draft", "context"],
                  "additionalProperties": false,
                  "maxProperties": 2,
                  "properties": {
                    "draft": {
                      "type": "object",
                      "required": ["days", "reason"],
                      "additionalProperties": false,
                      "maxProperties": 2,
                      "properties": {
                        "days": { "type": "integer" },
                        "reason": { "type": "string", "maxLength": 200 }
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
                        null);
        var family =
                new GovernedRuleFamilyDescriptor<>(runtime)
                        .withMetadata(
                                "corp.leaves",
                                "Regras de Afastamento",
                                "Descrição",
                                Map.of(),
                                GovernedRuleDocumentationSpec.of(
                                        "Família de Afastamentos",
                                        "Governa pedidos de afastamento.",
                                        "No CREATE.",
                                        "Controle institucional.",
                                        List.of(basis),
                                        "Ver slots."),
                                Map.of("leaves.augment-slot", augmentProductDoc),
                                Map.of(
                                        "product.leaves.param", paramBaselineDoc,
                                        "product.leaves.replace", replaceBaselineDoc,
                                        "product.leaves.augment", augmentProductDoc));

        UUID paramDefId = UUID.randomUUID();
        UUID replaceDefId = UUID.randomUUID();
        UUID augmentDefId = UUID.randomUUID();
        UUID unprefixedAugmentDefId = UUID.randomUUID();

        String custReplaceKey = "customer.t_acme.replace.leaves.replace-slot";
        String custAugmentKey = "customer.t_acme.augment.leaves.augment-slot.CUST-AUG-1";
        String custUnprefixedAugmentKey = "customer.t_acme.augment.leaves.augment-slot.CUST-MY-AUG";
        String custParamKey = "customer.t_acme.parameters.leaves.param-slot";

        RuleSetDefinition composedRuleSet =
                new RuleSetDefinition(
                        productRuleSet.ref(),
                        productRuleSet.availableRoots(),
                        productRuleSet.slots(),
                        List.of(
                                new DecisionBinding(
                                        custParamKey,
                                        "leaves.param-slot",
                                        DecisionSource.CUSTOMER,
                                        CompositionPolicy.PARAMETERIZE,
                                        null,
                                        List.of(),
                                        1,
                                        true,
                                        null,
                                        null,
                                        List.of(),
                                        new RuleParameterization(
                                                "corp.leaves:max-days",
                                                "1.0",
                                                JSON.createObjectNode().put("maxDays", 45))),
                                new DecisionBinding(
                                        custReplaceKey,
                                        "leaves.replace-slot",
                                        DecisionSource.CUSTOMER,
                                        CompositionPolicy.REPLACE_EXACT,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\"!=\":[{\"var\":\"draft.reason\"},\"BLOCKED\"]}")),
                                        List.of(),
                                        1,
                                        true,
                                        RuleDecision.DENY,
                                        "CUST-REP-1",
                                        List.of("draft.reason"),
                                        null),
                                new DecisionBinding(
                                        "product.leaves.augment",
                                        "leaves.augment-slot",
                                        DecisionSource.PRODUCT,
                                        null,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\"!=\":[{\"var\":\"context.tenantId\"},null]}")),
                                        List.of(),
                                        1,
                                        true,
                                        RuleDecision.DENY,
                                        "LEAVE-AUG-001",
                                        List.of("context.tenantId"),
                                        null),
                                new DecisionBinding(
                                        custAugmentKey,
                                        "leaves.augment-slot",
                                        DecisionSource.CUSTOMER,
                                        CompositionPolicy.AUGMENT,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\"<=\":[{\"var\":\"draft.days\"},15]}")),
                                        List.of(),
                                        2,
                                        true,
                                        RuleDecision.DENY,
                                        "CUST-AUG-1",
                                        List.of("draft.days"),
                                        null),
                                new DecisionBinding(
                                        custUnprefixedAugmentKey,
                                        "leaves.augment-slot",
                                        DecisionSource.CUSTOMER,
                                        CompositionPolicy.AUGMENT,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\">\":[{\"var\":\"draft.days\"},0]}")),
                                        List.of(),
                                        3,
                                        true,
                                        RuleDecision.DENY,
                                        "CUST-MY-AUG",
                                        List.of("draft.days"),
                                        null)),
                        RuleRuntimeCompatibility.current(),
                        RuleFailPolicy.FAIL_CLOSED,
                        Map.of(
                                custReplaceKey,
                                new RuleErrorDescriptor(
                                        "CUST-REP-1",
                                        "reason",
                                        "Motivo bloqueado pelo órgão.",
                                        "PRAXIS_POLICY_STUDIO",
                                        "CUSTOMER"),
                                custAugmentKey,
                                new RuleErrorDescriptor(
                                        "CUST-AUG-1",
                                        "days",
                                        "Máximo adicional de 15 dias pelo órgão.",
                                        "PRAXIS_POLICY_STUDIO",
                                        "CUSTOMER"),
                                custUnprefixedAugmentKey,
                                new RuleErrorDescriptor(
                                        "CUST-MY-AUG",
                                        "days",
                                        "Duração deve ser positiva.",
                                        "PRAXIS_POLICY_STUDIO",
                                        "CUSTOMER")));

        String hex64 = "A".repeat(64);
        PublishedRuleSnapshot snapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "corp.leaves:CREATE:v2",
                        "acme",
                        "local",
                        "hr-service",
                        2,
                        NOW.toString(),
                        null,
                        "host/1.0",
                        NOW.toString(),
                        null,
                        List.of(
                                new RuleSnapshotSource("product:hr-service:corp.leaves:CREATE", "product", 1, hex64),
                                new RuleSnapshotSource("non-uuid-test-id", "synthetic", 1, hex64),
                                new RuleSnapshotSource(paramDefId.toString(), "product.leaves.param", 2, hex64),
                                new RuleSnapshotSource(replaceDefId.toString(), "customer.replace", 1, hex64),
                                new RuleSnapshotSource(augmentDefId.toString(), "customer.augment", 1, hex64),
                                new RuleSnapshotSource(unprefixedAugmentDefId.toString(), "customer.augment.unprefixed", 1, hex64)),
                        List.of(new RuleSnapshotApproval("approval-1", "APPROVER", "reviewer", NOW.toString(), hex64)),
                        composedRuleSet);

        PublishedRuleSnapshotHeadSet headSet =
                new PublishedRuleSnapshotHeadSet(
                        new PublishedRuleSnapshotHeadSetScope("acme", "local", "corp.leaves", List.of("CREATE")),
                        Map.of(
                                "CREATE",
                                new PublishedRuleSnapshotHead(
                                        snapshot,
                                        "B".repeat(64),
                                        "etag-2",
                                        2L,
                                        PublishedRuleSnapshotHeadActivationType.ACTIVE)));

        DomainRuleDefinition paramDef =
                DomainRuleDefinition.builder()
                        .id(paramDefId)
                        .tenantId("acme")
                        .ruleKey("product.leaves.param")
                        .definition(
                                """
                                {
                                  "slotKey": "leaves.param-slot",
                                  "falseReasonCode": "LEAVE-PARAM-001",
                                  "documentation": {
                                    "summary": "Verifica o teto de 45 dias configurado pelo órgão."
                                  }
                                }
                                """)
                        .build();
        DomainRuleDefinition replaceDef =
                DomainRuleDefinition.builder()
                        .id(replaceDefId)
                        .tenantId("acme")
                        .ruleKey("customer.replace")
                        .definition(
                                """
                                {
                                  "slotKey": "leaves.replace-slot",
                                  "falseReasonCode": "CUST-REP-1",
                                  "documentation": {
                                    "title": "Motivo permitido pelo órgão",
                                    "summary": "Bloqueia solicitações com motivo BLOCKED.",
                                    "whenApplies": "Em toda criação de afastamento.",
                                    "rationale": "Norma interna do órgão.",
                                    "legalBasis": [{"code": "PORT-09/2026", "title": "Portaria 09"}],
                                    "customizationGuidance": "Editável no Policy Studio."
                                  }
                                }
                                """)
                        .build();
        DomainRuleDefinition augmentDef =
                DomainRuleDefinition.builder()
                        .id(augmentDefId)
                        .tenantId("acme")
                        .ruleKey("customer.augment")
                        .definition(
                                """
                                {
                                  "slotKey": "leaves.augment-slot",
                                  "falseReasonCode": "CUST-AUG-1",
                                  "documentation": {
                                    "title": "Teto adicional de 15 dias",
                                    "summary": "Exige no máximo 15 dias corridos para o órgão.",
                                    "whenApplies": "Ao informar duração.",
                                    "rationale": "Política complementar do órgão.",
                                    "legalBasis": [{"code": "RES-12/2026", "title": "Resolução 12"}],
                                    "customizationGuidance": "Regra adicional de restrição."
                                  }
                                }
                                """)
                        .build();

        DomainRuleDefinition unprefixedAugmentDef =
                DomainRuleDefinition.builder()
                        .id(unprefixedAugmentDefId)
                        .tenantId("acme")
                        .ruleKey("customer.augment.unprefixed")
                        .definition(
                                """
                                {
                                  "slotKey": "leaves.augment-slot",
                                  "falseReasonCode": "MY_AUG",
                                  "documentation": {
                                    "title": "Duração mínima positiva",
                                    "summary": "Garante quantidade positiva de dias no pedido."
                                  }
                                }
                                """)
                        .build();

        DomainRuleDefinitionRepository repo = mock(DomainRuleDefinitionRepository.class);
        when(repo.findAllById(any())).thenReturn(List.of(paramDef, replaceDef, augmentDef, unprefixedAugmentDef));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DomainRuleService.class, () -> mock(DomainRuleService.class))
                .withBean(DomainRuleSnapshotService.class, () -> mock(DomainRuleSnapshotService.class))
                .withBean(
                        DomainRuleGovernancePrincipalResolver.class,
                        () -> mock(DomainRuleGovernancePrincipalResolver.class))
                .withBean(GovernedRuleFamilyRegistry.class, () -> new GovernedRuleFamilyRegistry(List.of(family)))
                .withBean(PublishedRuleSnapshotHeadSetReader.class, () -> scope -> Optional.of(headSet))
                .withBean(DomainRuleDefinitionRepository.class, () -> repo)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DomainRuleSetCatalogProvider provider =
                                    context.getBean(DomainRuleSetCatalogProvider.class);
                            List<DomainRuleSetDescriptor> found =
                                    provider.findRuleSets("corp.leaves", "acme");
                            assertThat(found).hasSize(1);
                            DomainRuleSetDescriptor descriptor = found.get(0);

                            // Merged errorDescriptors into reasonDescriptors
                            assertThat(descriptor.reasonDescriptors())
                                    .containsKeys("CUST-REP-1", "CUST-AUG-1");
                            assertThat(descriptor.reasonDescriptors().get("CUST-REP-1").message())
                                    .isEqualTo("Motivo bloqueado pelo órgão.");
                            assertThat(descriptor.reasonDescriptors().get("CUST-AUG-1").targetField())
                                    .isEqualTo("days");

                            // PARAMETERIZE preserves product baseline title & legalBasis while updating summary
                            assertThat(descriptor.ruleDocumentation()).containsKey(custParamKey);
                            assertThat(descriptor.ruleDocumentation().get(custParamKey).title())
                                    .isEqualTo("Limite de dias parametrizável");
                            assertThat(descriptor.ruleDocumentation().get(custParamKey).summary())
                                    .isEqualTo("Verifica o teto de 45 dias configurado pelo órgão.");
                            assertThat(descriptor.ruleDocumentation().get(custParamKey).legalBasis())
                                    .hasSize(1);

                            // REPLACE_EXACT overrides with tenant docs
                            assertThat(descriptor.ruleDocumentation().get(custReplaceKey).title())
                                    .isEqualTo("Motivo permitido pelo órgão");
                            assertThat(descriptor.ruleDocumentation().get(custReplaceKey).legalBasis())
                                    .extracting(b -> b.code())
                                    .containsExactly("PORT-09/2026");

                            // AUGMENT adds tenant doc without clobbering product rule, sibling AUGMENT rule, or slot doc
                            assertThat(descriptor.ruleDocumentation().get(custAugmentKey).title())
                                    .isEqualTo("Teto adicional de 15 dias");
                            assertThat(descriptor.ruleDocumentation().get(custUnprefixedAugmentKey).title())
                                    .isEqualTo("Duração mínima positiva");
                            assertThat(descriptor.ruleDocumentation().get("CUST-MY-AUG").title())
                                    .isEqualTo("Duração mínima positiva");
                            assertThat(descriptor.ruleDocumentation().get("product.leaves.augment").title())
                                    .isEqualTo("Regra fixa base");
                            assertThat(descriptor.slotDocumentation().get("leaves.augment-slot").title())
                                    .isEqualTo("Regra fixa base");

                            // End-to-end projection into SemanticDomainCatalogService (/schemas/domain)
                            SemanticDomainCatalogService catalogService =
                                    createCatalogServiceForLeaves(provider);
                            DomainCatalogResponse catalog =
                                    catalogService.findByResourceKey("corp.leaves");
                            var ruleSetNode =
                                    catalog.nodes().stream()
                                            .filter(
                                                    n ->
                                                            "corp.leaves"
                                                                    .equals(n.metadata().get("ruleSetKey")))
                                            .findFirst()
                                            .orElseThrow();
                            assertThat((Map<String, String>) ruleSetNode.metadata().get("targetFieldLabels"))
                                    .containsEntry("days", "Dias de Afastamento")
                                    .containsEntry("reason", "Motivo do Afastamento");
                            var rules = (List<Map<String, Object>>) ruleSetNode.metadata().get("rules");
                            assertThat(rules)
                                    .anySatisfy(
                                            r -> {
                                                assertThat(r).containsEntry("bindingKey", custAugmentKey);
                                                assertThat(r).containsEntry("title", "Teto adicional de 15 dias");
                                                assertThat(r).containsEntry("targetFieldLabel", "Dias de Afastamento");
                                                assertThat(r).containsEntry("message", "Máximo adicional de 15 dias pelo órgão.");
                                            });
                        });

        // Graceful fallback when DomainRuleDefinitionRepository throws
        DomainRuleDefinitionRepository failingRepo = mock(DomainRuleDefinitionRepository.class);
        when(failingRepo.findAllById(any())).thenThrow(new RuntimeException("DB unavailable"));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DomainRuleService.class, () -> mock(DomainRuleService.class))
                .withBean(DomainRuleSnapshotService.class, () -> mock(DomainRuleSnapshotService.class))
                .withBean(
                        DomainRuleGovernancePrincipalResolver.class,
                        () -> mock(DomainRuleGovernancePrincipalResolver.class))
                .withBean(GovernedRuleFamilyRegistry.class, () -> new GovernedRuleFamilyRegistry(List.of(family)))
                .withBean(PublishedRuleSnapshotHeadSetReader.class, () -> scope -> Optional.of(headSet))
                .withBean(DomainRuleDefinitionRepository.class, () -> failingRepo)
                .run(
                        context -> {
                            DomainRuleSetCatalogProvider provider =
                                    context.getBean(DomainRuleSetCatalogProvider.class);
                            List<DomainRuleSetDescriptor> found =
                                    provider.findRuleSets("corp.leaves", "acme");
                            assertThat(found).hasSize(1);
                            // Product baseline doc still propagated onto PARAMETERIZE binding despite repo failure
                            assertThat(found.get(0).ruleDocumentation().get(custParamKey).title())
                                    .isEqualTo("Limite de dias parametrizável");
                        });
    }

    @Test
    void bridgeDefensivelySanitizesUnsafeLegacyLegalBasisUrlsWhilePreservingValidHttpUrls()
            throws Exception {
        GovernedRuleFamilyDescriptor<LeaveOp> family = buildFamilyWithConcept();
        UUID tenantDefId = UUID.randomUUID();
        String bindingKey = "customer.corp.leaves.create";
        String slotKey = "corp.leaves.create";

        var effectiveDef =
                new RuleSetDefinition(
                        new RuleSetRef("hr-service", "corp.leaves", "corp.leaves", "CREATE", 1),
                        List.of("employee", "context"),
                        List.of(
                                new DecisionSlot(
                                        slotKey,
                                        DecisionStage.DOMAIN_DECISION,
                                        SlotCardinality.SINGLE,
                                        OverridePolicy.REPLACEABLE,
                                        DecisionAggregationPolicy.SINGLE_RESULT,
                                        null,
                                        List.of())),
                        List.of(
                                new DecisionBinding(
                                        bindingKey,
                                        slotKey,
                                        DecisionSource.CUSTOMER,
                                        CompositionPolicy.REPLACE_EXACT,
                                        RuleExecutorRef.jsonLogic(
                                                JSON.readTree("{\"==\":[{\"var\":\"employee.active\"},true]}")),
                                        List.of(),
                                        1,
                                        true,
                                        RuleDecision.DENY,
                                        "LEAVE-001",
                                        List.of("employee.active"),
                                        null)),
                        RuleRuntimeCompatibility.current(),
                        RuleFailPolicy.FAIL_CLOSED);

        String hex64 = "C".repeat(64);
        PublishedRuleSnapshot snapshot =
                new PublishedRuleSnapshot(
                        PublishedRuleSnapshot.SNAPSHOT_CONTRACT_VERSION,
                        "snap-legacy-url",
                        "acme",
                        "local",
                        "hr-service",
                        1,
                        NOW.toString(),
                        null,
                        "host/1.0",
                        NOW.toString(),
                        null,
                        List.of(
                                new RuleSnapshotSource(
                                        tenantDefId.toString(), "customer.legacy", 1, hex64)),
                        List.of(
                                new RuleSnapshotApproval(
                                        "approval-1", "APPROVER", "reviewer", NOW.toString(), hex64)),
                        effectiveDef);
        PublishedRuleSnapshotHeadSet headSet =
                new PublishedRuleSnapshotHeadSet(
                        new PublishedRuleSnapshotHeadSetScope(
                                "acme", "local", "corp.leaves", List.of("CREATE")),
                        Map.of(
                                "CREATE",
                                new PublishedRuleSnapshotHead(
                                        snapshot,
                                        "D".repeat(64),
                                        "etag-legacy",
                                        1L,
                                        PublishedRuleSnapshotHeadActivationType.ACTIVE)));

        DomainRuleDefinition legacyRow =
                DomainRuleDefinition.builder()
                        .id(tenantDefId)
                        .tenantId("acme")
                        .ruleKey("customer.legacy")
                        .definition(
                                """
                                {
                                  "slotKey": "corp.leaves.create",
                                  "falseReasonCode": "LEAVE-001",
                                  "slotDocumentation": {
                                    "title": "Slot legado",
                                    "legalBasis": [
                                      {"code": "SLOT-BAD", "title": "Unsafe Slot URL", "url": "javascript:alert(1)"},
                                      {"code": "SLOT-OK", "title": "Safe Slot URL", "url": "  https://example.org/statutes/slot  "}
                                    ]
                                  },
                                  "documentation": {
                                    "title": "Regra legada",
                                    "legalBasis": [
                                      {"code": "RULE-JS", "title": "JS", "url": "javascript:alert(1)"},
                                      {"code": "RULE-DATA", "title": "Data", "url": "data:text/html,<script>alert(1)</script>"},
                                      {"code": "RULE-REL", "title": "ProtoRel", "url": "//evil.example/path"},
                                      {"code": "RULE-CRED", "title": "Creds", "url": "https://user:pass@example.com/path"},
                                      {"code": "RULE-OK", "title": "Statute", "url": "https://example.org/statutes/123"}
                                    ]
                                  }
                                }
                                """)
                        .build();

        DomainRuleDefinitionRepository repo = mock(DomainRuleDefinitionRepository.class);
        when(repo.findAllById(any())).thenReturn(List.of(legacyRow));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GovernedRulesStudioAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DomainRuleService.class, () -> mock(DomainRuleService.class))
                .withBean(DomainRuleSnapshotService.class, () -> mock(DomainRuleSnapshotService.class))
                .withBean(
                        DomainRuleGovernancePrincipalResolver.class,
                        () -> mock(DomainRuleGovernancePrincipalResolver.class))
                .withBean(GovernedRuleFamilyRegistry.class, () -> new GovernedRuleFamilyRegistry(List.of(family)))
                .withBean(PublishedRuleSnapshotHeadSetReader.class, () -> scope -> Optional.of(headSet))
                .withBean(DomainRuleDefinitionRepository.class, () -> repo)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DomainRuleSetCatalogProvider provider =
                                    context.getBean(DomainRuleSetCatalogProvider.class);
                            List<DomainRuleSetDescriptor> found =
                                    provider.findRuleSets("corp.leaves", "acme");
                            assertThat(found).hasSize(1);
                            DomainRuleSetDescriptor descriptor = found.get(0);

                            var slotBasis = descriptor.slotDocumentation().get("corp.leaves.create").legalBasis();
                            assertThat(slotBasis).hasSize(2);
                            assertThat(slotBasis.get(0).code()).isEqualTo("SLOT-BAD");
                            assertThat(slotBasis.get(0).url()).isNull();
                            assertThat(slotBasis.get(1).code()).isEqualTo("SLOT-OK");
                            assertThat(slotBasis.get(1).url()).isEqualTo("https://example.org/statutes/slot");

                            var ruleBasis = descriptor.ruleDocumentation().get(bindingKey).legalBasis();
                            assertThat(ruleBasis).hasSize(5);
                            assertThat(ruleBasis.get(0).url()).isNull();
                            assertThat(ruleBasis.get(1).url()).isNull();
                            assertThat(ruleBasis.get(2).url()).isNull();
                            assertThat(ruleBasis.get(3).url()).isNull();
                            assertThat(ruleBasis.get(4).code()).isEqualTo("RULE-OK");
                            assertThat(ruleBasis.get(4).url()).isEqualTo("https://example.org/statutes/123");
                        });
    }

    private static SemanticDomainCatalogService createCatalogServiceForLeaves(
            DomainRuleSetCatalogProvider ruleProvider) {
        JsonNode openApiDoc =
                JSON.valueToTree(
                        Map.of(
                                "components",
                                Map.of(
                                        "schemas",
                                        Map.of(
                                                "LeaveRequest",
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        Map.of(
                                                                "days",
                                                                Map.of(
                                                                        "type",
                                                                        "integer",
                                                                        "x-ui",
                                                                        Map.of("label", "Dias de Afastamento")),
                                                                "reason",
                                                                Map.of(
                                                                        "type",
                                                                        "string",
                                                                        "x-ui",
                                                                        Map.of("label", "Motivo do Afastamento"))))))));
        OpenApiDocumentService docs =
                new OpenApiDocumentService() {
                    @Override
                    public String resolveGroupFromPath(String path) {
                        return "corp";
                    }

                    @Override
                    public JsonNode getDocumentForGroup(String groupName) {
                        return openApiDoc;
                    }

                    @Override
                    public JsonNode getDocumentForGroupStrict(String groupName) {
                        return openApiDoc;
                    }

                    @Override
                    public String getOrComputeSchemaHash(
                            String schemaId, java.util.function.Supplier<JsonNode> payloadSupplier) {
                        return "h";
                    }

                    @Override
                    public void clearCaches() {}
                };
        ActionDefinitionRegistry actionRegistry =
                new ActionDefinitionRegistry() {
                    @Override
                    public List<ActionDefinition> findByResourceKey(String resourceKey) {
                        if (!"corp.leaves".equals(resourceKey)) {
                            return List.of();
                        }
                        return List.of(
                                new ActionDefinition(
                                        "create",
                                        "corp.leaves",
                                        "/api/corp/leaves",
                                        "corp",
                                        ActionScope.COLLECTION,
                                        "Solicitar afastamento",
                                        "Registra afastamento.",
                                        new CanonicalOperationRef(
                                                "corp", "createLeave", "/api/corp/leaves", "POST"),
                                        new CanonicalSchemaRef(
                                                "LeaveRequest",
                                                "request",
                                                "/schemas/filtered?schemaType=request"),
                                        null,
                                        10,
                                        "Registrado",
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        ActionExecutionContract.defaults(ActionScope.COLLECTION)));
                    }

                    @Override
                    public List<ActionDefinition> findByGroup(String group) {
                        return findByResourceKey("corp.leaves");
                    }
                };
        SurfaceDefinitionRegistry emptySurfaces =
                new SurfaceDefinitionRegistry() {
                    @Override
                    public List<SurfaceDefinition> findByResourceKey(String resourceKey) {
                        return List.of();
                    }

                    @Override
                    public List<SurfaceDefinition> findByGroup(String group) {
                        return List.of();
                    }
                };
        return new SemanticDomainCatalogService(
                actionRegistry,
                emptySurfaces,
                OptionSourceRegistry.empty(),
                docs,
                null,
                Clock.fixed(Instant.parse("2026-04-21T10:30:00Z"), ZoneOffset.UTC),
                "hr-service",
                "HR Service",
                "test",
                null,
                null,
                () -> Optional.of("acme"),
                ruleProvider);
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
                                        "corp.employees")),
                        GovernedRuleDocumentationSpec.of(
                                "Governança de Afastamentos",
                                "Reúne regras de elegibilidade para concessão de afastamento.",
                                "Na criação do pedido.",
                                "Assegura vínculo ativo.",
                                List.of(
                                        GovernedRuleLegalBasisSpec.of(
                                                "POL-RH-01",
                                                "Política Corporativa de RH",
                                                "Exige colaborador ativo.")),
                                "Consulte os slots configuráveis."),
                        Map.of(),
                        Map.of(
                                "LEAVE-001",
                                GovernedRuleDocumentationSpec.of(
                                        "Colaborador ativo",
                                        "Verifica se o colaborador solicitante está ativo.",
                                        "Ao solicitar afastamento.",
                                        "Impede concessão para desligados.",
                                        List.of(),
                                        "Regra fixa.")));
    }
}
