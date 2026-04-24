package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.DomainRuleDefinition;
import org.praxisplatform.config.domain.DomainRuleMaterialization;
import org.praxisplatform.config.dto.DomainRuleDefinitionRequest;
import org.praxisplatform.config.dto.DomainRuleIntakeRequest;
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.dto.DomainRulePublicationRequest;
import org.praxisplatform.config.dto.DomainRuleSimulationRequest;
import org.praxisplatform.config.dto.DomainRuleStatusTransitionRequest;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;

@Tag("unit")
class DomainRuleServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void intakeCreatesDraftDefinitionAndGroundingForSimulation() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> {
            DomainRuleDefinition definition = invocation.getArgument(0);
            definition.onInsert();
            return definition;
        });

        var response = service.intake(
                new DomainRuleIntakeRequest(
                        "Impedir seleção de fornecedores bloqueados em pedidos de compra.",
                        "Vou abrir a trilha governada de regra compartilhada.",
                        null,
                        "selection_eligibility",
                        "procurement",
                        "procurement.suppliers",
                        "praxis-api-quickstart",
                        objectMapper.readTree("""
                                {
                                  "summary": "Impedir seleção de fornecedores bloqueados em pedidos de compra."
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "optionSourceKey": "supplier"
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "in": [
                                    { "var": "status" },
                                    ["INACTIVE", "BLOCKED"]
                                  ]
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "requiredApprovals": ["procurement-owner"]
                                }
                                """),
                        "llm",
                        "openai:gpt-5.4-mini"),
                "tenant-a",
                "dev");

        assertThat(response.ruleKey()).isEqualTo("procurement.suppliers.rule.selection-eligibility");
        assertThat(response.status()).isEqualTo("draft");
        assertThat(response.definition().definition().path("sourcePrompt").asText())
                .isEqualTo("Impedir seleção de fornecedores bloqueados em pedidos de compra.");
        assertThat(response.definition().definition().path("assistantMessage").asText())
                .isEqualTo("Vou abrir a trilha governada de regra compartilhada.");
        assertThat(response.grounding().path("nextEndpoint").asText())
                .isEqualTo("/api/praxis/config/domain-rules/simulations");
        assertThat(response.grounding().path("ruleDefinitionId").asText()).isEqualTo(response.definition().id().toString());

        ArgumentCaptor<DomainRuleDefinition> captor = ArgumentCaptor.forClass(DomainRuleDefinition.class);
        verify(definitionRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleType()).isEqualTo("selection_eligibility");
        assertThat(captor.getValue().getResourceKey()).isEqualTo("procurement.suppliers");
        assertThat(captor.getValue().getStatus()).isEqualTo("draft");
    }

    @Test
    void createsSharedRuleDefinitionWithoutMaterializingFormConfig() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> {
            DomainRuleDefinition definition = invocation.getArgument(0);
            definition.onInsert();
            return definition;
        });

        var response = service.createDefinition(new DomainRuleDefinitionRequest(
                "human-resources.funcionarios.rule.lgpd-cpf-guidance",
                null,
                "visual_guidance",
                "proposed",
                "human-resources",
                "human-resources.funcionarios",
                "praxis-api-quickstart",
                "data-owner",
                "privacy-office",
                null,
                null,
                objectMapper.readTree("""
                    {
                      "summary": "Orientar analistas sobre CPF como dado pessoal.",
                      "recommendedOperation": "rule.visualBlockGuidance.add"
                    }
                    """),
                objectMapper.readTree("""
                    { "field": "cpf", "visualBlockId": "lgpd-notice" }
                    """),
                objectMapper.readTree("""
                    { "!=": [{ "var": "cpf" }, null] }
                    """),
                objectMapper.readTree("""
                    {
                      "complianceTags": ["LGPD", "GDPR"],
                      "ruleAuthoring": "review_required",
                      "aiUsage": { "trainingUse": "deny" }
                    }
                    """),
                null,
                "llm",
                "openai:gpt-5.4-mini",
                null), "tenant-a", "dev");

        assertThat(response.id()).isNotNull();
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("proposed");
        assertThat(response.createdByType()).isEqualTo("llm");
        assertThat(response.definition().path("recommendedOperation").asText())
                .isEqualTo("rule.visualBlockGuidance.add");
        assertThat(response.governance().path("ruleAuthoring").asText()).isEqualTo("review_required");

        ArgumentCaptor<DomainRuleDefinition> captor = ArgumentCaptor.forClass(DomainRuleDefinition.class);
        verify(definitionRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(captor.getValue().getEnvironment()).isEqualTo("dev");
        assertThat(captor.getValue().getRuleType()).isEqualTo("visual_guidance");
    }

    @Test
    void createsFormConfigMaterializationForSharedRuleDefinition() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .ruleKey("human-resources.funcionarios.rule.lgpd-cpf-guidance")
                .version(2)
                .ruleType("visual_guidance")
                .status("active")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            materialization.onInsert();
            return materialization;
        });

        var response = service.createMaterialization(new DomainRuleMaterializationRequest(
                definitionId,
                "funcionarios-form-demo:formRules:lgpd-cpf-guidance",
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "/formRules/-",
                "page-builder-demo@local",
                "lgpd-cpf-guidance",
                "pending_review",
                objectMapper.readTree("""
                    {
                      "id": "lgpd-cpf-guidance",
                      "type": "visualBlockGuidance",
                      "targetType": "visualBlock",
                      "targets": ["lgpd-notice"],
                      "metadata": { "origin": "llm", "reviewStatus": "pending" }
                    }
                    """),
                "hash-123",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev");

        assertThat(response.id()).isNotNull();
        assertThat(response.ruleDefinitionId()).isEqualTo(definitionId);
        assertThat(response.ruleKey()).isEqualTo("human-resources.funcionarios.rule.lgpd-cpf-guidance");
        assertThat(response.ruleVersion()).isEqualTo(2);
        assertThat(response.targetLayer()).isEqualTo("form_config");
        assertThat(response.materializedPayload().path("metadata").path("reviewStatus").asText())
                .isEqualTo("pending");

        ArgumentCaptor<DomainRuleMaterialization> captor = ArgumentCaptor.forClass(DomainRuleMaterialization.class);
        verify(materializationRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleDefinition()).isSameAs(definition);
        assertThat(captor.getValue().getMaterializedRuleId()).isEqualTo("lgpd-cpf-guidance");
    }

    @Test
    void createsOptionSourceMaterializationFromSelectionEligibilityDefinitionWhenPayloadIsOmitted() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .ruleKey("procurement.suppliers.rule.selection-eligibility")
                .version(1)
                .ruleType("selection_eligibility")
                .status("approved")
                .resourceKey("procurement.suppliers")
                .definition("{\"summary\":\"Impedir selecao de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\",\"validationMessageTemplate\":\"Fornecedor indisponivel\"}")
                .condition("""
                        {
                          "in": [
                            { "var": "status" },
                            ["INACTIVE", "BLOCKED"]
                          ]
                        }
                        """)
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            materialization.onInsert();
            return materialization;
        });

        var response = service.createMaterialization(new DomainRuleMaterializationRequest(
                definitionId,
                "procurement.suppliers.rule.selection-eligibility:option_source:supplier",
                "option_source",
                "resource-option-source",
                "supplier",
                "/selectionPolicy",
                null,
                "selection-policy",
                "pending_review",
                null,
                "hash-selection-eligibility",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev");

        assertThat(response.targetLayer()).isEqualTo("option_source");
        assertThat(response.targetArtifactType()).isEqualTo("resource-option-source");
        assertThat(response.targetArtifactKey()).isEqualTo("supplier");
        assertThat(response.materializedPayload().path("kind").asText()).isEqualTo("lookup_selection_policy");
        assertThat(response.materializedPayload().path("selectionPolicy").path("statusPropertyPath").asText())
                .isEqualTo("status");
        assertThat(response.materializedPayload().path("selectionPolicy").path("blockedStatuses"))
                .extracting(JsonNode::asText)
                .containsExactly("INACTIVE", "BLOCKED");
        assertThat(response.materializedPayload().path("selectionPolicy").path("validationMessageTemplate").asText())
                .isEqualTo("Fornecedor indisponivel");
    }

    @Test
    void createsBackendValidationMaterializationFromValidationDefinitionWhenPayloadIsOmitted() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .ruleKey("procurement.suppliers.rule.status-validation")
                .version(1)
                .ruleType("validation")
                .status("approved")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Bloquear fornecedores inativos no backend.\"}")
                .parameters("""
                        {
                          "severity": "error",
                          "validationMessageTemplate": "Fornecedor indisponivel"
                        }
                        """)
                .condition("""
                        {
                          "in": [
                            { "var": "status" },
                            ["INACTIVE", "BLOCKED"]
                          ]
                        }
                        """)
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            materialization.onInsert();
            return materialization;
        });

        var response = service.createMaterialization(new DomainRuleMaterializationRequest(
                definitionId,
                "procurement.suppliers.rule.status-validation:backend_validation:procurement.suppliers",
                "backend_validation",
                "resource-validation",
                "procurement.suppliers",
                "/validationPolicy",
                null,
                "backend-validation-policy",
                "pending_review",
                null,
                "hash-status-validation",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev");

        assertThat(response.targetLayer()).isEqualTo("backend_validation");
        assertThat(response.targetArtifactType()).isEqualTo("resource-validation");
        assertThat(response.targetArtifactKey()).isEqualTo("procurement.suppliers");
        assertThat(response.materializedPayload().path("kind").asText()).isEqualTo("resource_validation_policy");
        assertThat(response.materializedPayload().path("validationPolicy").path("ruleKey").asText())
                .isEqualTo("procurement.suppliers.rule.status-validation");
        assertThat(response.materializedPayload().path("validationPolicy").path("resourceKey").asText())
                .isEqualTo("procurement.suppliers");
        assertThat(response.materializedPayload().path("validationPolicy").path("condition").path("in"))
                .hasSize(2);
        assertThat(response.materializedPayload().path("validationPolicy").path("severity").asText())
                .isEqualTo("error");
        assertThat(response.materializedPayload().path("validationPolicy").path("validationMessageTemplate").asText())
                .isEqualTo("Fornecedor indisponivel");
    }

    @Test
    void findsDefinitionsByResourceAndDefaultAuthoringStatuses() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);
        DomainRuleDefinition active = DomainRuleDefinition.builder()
                .ruleKey("rule-a")
                .version(1)
                .ruleType("visual_guidance")
                .status("active")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                eq("tenant-a"),
                eq("dev"),
                eq("human-resources.funcionarios"),
                eq(List.of("draft", "proposed", "approved", "active"))))
                .thenReturn(List.of(active));

        var response = service.definitions(
                "tenant-a",
                "dev",
                "human-resources.funcionarios",
                null,
                null,
                null);

        assertThat(response).singleElement()
                .satisfies(definition -> assertThat(definition.ruleKey()).isEqualTo("rule-a"));
    }

    @Test
    void transitionsDefinitionToActiveWithGovernanceTimestamps() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);
        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("rule-a")
                .version(1)
                .ruleType("visual_guidance")
                .status("approved")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.transitionDefinitionStatus(
                definitionId,
                new DomainRuleStatusTransitionRequest(
                        "active",
                        "human",
                        "privacy-office",
                        objectMapper.readTree("{\"review\":\"approved\"}")),
                "tenant-a",
                "dev");

        assertThat(response.status()).isEqualTo("active");
        assertThat(response.approvedBy()).isEqualTo("privacy-office");
        assertThat(response.approvedAt()).isNotNull();
        assertThat(response.activatedAt()).isNotNull();
        assertThat(response.validationResult().path("review").asText()).isEqualTo("approved");
        verify(definitionRepository).save(definition);
    }

    @Test
    void simulatesProcurementRuleAndDetectsExistingCoverage() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        DomainRuleDefinition existing = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.selection-policy")
                .version(3)
                .ruleType("policy_reference")
                .status("active")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("""
                        {
                          "summary": "Bloquear fornecedores inativos ou bloqueados no lookup de pedidos."
                        }
                        """)
                .parameters("""
                        {
                          "optionSourceKey": "supplier"
                        }
                        """)
                .governance("""
                        {
                          "requiredApprovals": ["procurement-owner"]
                        }
                        """)
                .build();

        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "procurement.suppliers",
                List.of("approved", "active")))
                .thenReturn(List.of(existing));

        var response = service.simulate(
                new DomainRuleSimulationRequest(
                        null,
                        "procurement.suppliers.rule.selection-eligibility",
                        "policy_reference",
                        "procurement",
                        "procurement.suppliers",
                        "praxis-api-quickstart",
                        objectMapper.readTree("""
                                {
                                  "summary": "Impedir seleção de fornecedores bloqueados em pedidos de compra."
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "optionSourceKey": "supplier"
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "in": [
                                    { "var": "status" },
                                    ["INACTIVE", "BLOCKED"]
                                  ]
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "requiredApprovals": ["procurement-owner"]
                                }
                                """)),
                "tenant-a",
                "dev");

        assertThat(response.result()).isEqualTo("pass_with_existing_coverage");
        assertThat(response.existingCoverage()).hasSize(1);
        assertThat(response.predictedMaterializations()).hasSize(1);
        assertThat(response.predictedMaterializations().get(0).path("targetLayer").asText()).isEqualTo("option_source");
        assertThat(response.requiredApprovals()).hasSize(1);
        assertThat(response.requiredApprovals().get(0).asText()).isEqualTo("procurement-owner");
        assertThat(response.grounding().path("source").asText()).isEqualTo("ad_hoc_request");
        assertThat(response.explainability().path("recommendedAction").asText()).isEqualTo("review_existing_coverage");
        assertThat(response.explainability().path("publicationReadiness").asText()).isEqualTo("blocked_by_existing_coverage");
        assertThat(response.explainability().path("summary").asText()).contains("Existing approved or active coverage was found");
        assertThat(response.explainability().path("nextSteps")).hasSize(4);
        assertThat(response.explainability().path("nextSteps").get(0).path("kind").asText()).isEqualTo("review_existing_coverage");
        assertThat(response.explainability().path("nextSteps").get(1).path("kind").asText()).isEqualTo("request_approval");
        assertThat(response.explainability().path("nextSteps").get(2).path("kind").asText()).isEqualTo("persist_definition");
        assertThat(response.explainability().path("nextSteps").get(3).path("kind").asText()).isEqualTo("resolve_warnings");
    }

    @Test
    void publishesPersistedRuleWhenSimulationReadinessIsReadyToPublish() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.selection-eligibility")
                .version(2)
                .ruleType("policy_reference")
                .status("approved")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Impedir seleção de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .governance("{}")
                .build();
        DomainRuleMaterialization materialization = DomainRuleMaterialization.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .ruleDefinition(definition)
                .materializationKey("supplier:selection-policy")
                .targetLayer("backend_validation")
                .targetArtifactType("resource-validation")
                .targetArtifactKey("procurement.suppliers")
                .status("pending_review")
                .materializedPayload("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "procurement.suppliers",
                List.of("approved", "active")))
                .thenReturn(List.of());
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                "tenant-a",
                "dev",
                definitionId))
                .thenReturn(List.of(materialization));
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");

        assertThat(response.publicationStatus()).isEqualTo("published");
        assertThat(response.publicationReadiness()).isEqualTo("ready_to_publish");
        assertThat(response.definition().status()).isEqualTo("active");
        assertThat(response.definition().approvedBy()).isEqualTo("procurement-owner");
        assertThat(response.explainability().path("recommendedAction").asText()).isEqualTo("materialize_or_activate");
        assertThat(response.materializations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.appliedBy()).isEqualTo("procurement-owner");
                });
    }

    @Test
    void publishesSelectionEligibilityRuleByAutoCreatingOptionSourceMaterialization() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.selection-eligibility")
                .version(1)
                .ruleType("selection_eligibility")
                .status("approved")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Impedir selecao de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .condition("""
                        {
                          "in": [
                            { "var": "status" },
                            ["INACTIVE", "BLOCKED"]
                          ]
                        }
                        """)
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "procurement.suppliers",
                List.of("approved", "active")))
                .thenReturn(List.of());
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                "tenant-a",
                "dev",
                definitionId))
                .thenReturn(List.of());
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            if (materialization.getId() == null) {
                materialization.setId(UUID.randomUUID());
            }
            materialization.onInsert();
            return materialization;
        });

        var response = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");

        assertThat(response.publicationStatus()).isEqualTo("published");
        assertThat(response.materializations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.targetLayer()).isEqualTo("option_source");
                    assertThat(item.targetArtifactType()).isEqualTo("resource-option-source");
                    assertThat(item.targetArtifactKey()).isEqualTo("supplier");
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.materializedPayload().path("kind").asText()).isEqualTo("lookup_selection_policy");
                    assertThat(item.materializedPayload().path("selectionPolicy").path("blockedStatuses"))
                            .extracting(JsonNode::asText)
                            .containsExactly("INACTIVE", "BLOCKED");
                });
    }

    @Test
    void publishesValidationRuleByAutoCreatingBackendValidationMaterialization() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.status-validation")
                .version(1)
                .ruleType("validation")
                .status("approved")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Bloquear fornecedores inativos no backend.\"}")
                .parameters("{\"severity\":\"error\",\"validationMessageTemplate\":\"Fornecedor indisponivel\"}")
                .condition("""
                        {
                          "in": [
                            { "var": "status" },
                            ["INACTIVE", "BLOCKED"]
                          ]
                        }
                        """)
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "procurement.suppliers",
                List.of("approved", "active")))
                .thenReturn(List.of());
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                "tenant-a",
                "dev",
                definitionId))
                .thenReturn(List.of());
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            if (materialization.getId() == null) {
                materialization.setId(UUID.randomUUID());
            }
            materialization.onInsert();
            return materialization;
        });

        var response = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");

        assertThat(response.publicationStatus()).isEqualTo("published");
        assertThat(response.materializations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.targetLayer()).isEqualTo("backend_validation");
                    assertThat(item.targetArtifactType()).isEqualTo("resource-validation");
                    assertThat(item.targetArtifactKey()).isEqualTo("procurement.suppliers");
                    assertThat(item.targetPointer()).isEqualTo("/validationPolicy");
                    assertThat(item.materializedRuleId()).isEqualTo("backend-validation-policy");
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.materializedPayload().path("kind").asText()).isEqualTo("resource_validation_policy");
                    assertThat(item.materializedPayload().path("validationPolicy").path("severity").asText())
                            .isEqualTo("error");
                });
    }

    @Test
    void blocksPublicationWhenApprovalsAreRequired() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("human-resources.funcionarios.rule.lgpd-cpf-guidance")
                .version(1)
                .ruleType("visual_guidance")
                .status("draft")
                .contextKey("human-resources")
                .resourceKey("human-resources.funcionarios")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Avisar o analista quando CPF estiver presente.\"}")
                .parameters("{}")
                .governance("{\"ruleAuthoring\":\"review_required\"}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "human-resources.funcionarios",
                List.of("approved", "active")))
                .thenReturn(List.of());

        var response = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "privacy-office",
                        null),
                "tenant-a",
                "dev");

        assertThat(response.publicationStatus()).isEqualTo("blocked");
        assertThat(response.publicationReadiness()).isEqualTo("approval_required");
        assertThat(response.definition().status()).isEqualTo("draft");
        assertThat(response.materializations()).isEmpty();
        verify(definitionRepository, org.mockito.Mockito.times(2)).findById(definitionId);
    }

    @Test
    void transitionsMaterializationToAppliedWithAuditFields() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);
        UUID materializationId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .ruleKey("rule-a")
                .version(1)
                .ruleType("visual_guidance")
                .status("active")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();
        DomainRuleMaterialization materialization = DomainRuleMaterialization.builder()
                .id(materializationId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleDefinition(definition)
                .materializationKey("form:rule-a")
                .targetLayer("form_config")
                .targetArtifactType("praxis-dynamic-form")
                .targetArtifactKey("funcionarios-form-demo")
                .status("pending_review")
                .materializedPayload("{}")
                .build();
        when(materializationRepository.findById(materializationId)).thenReturn(Optional.of(materialization));
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.transitionMaterializationStatus(
                materializationId,
                new DomainRuleStatusTransitionRequest(
                        "applied",
                        "llm",
                        "openai:gpt-5.4-mini",
                        objectMapper.readTree("{\"checks\":[\"schema-compatible\"]}")),
                "tenant-a",
                "dev");

        assertThat(response.status()).isEqualTo("applied");
        assertThat(response.appliedByType()).isEqualTo("llm");
        assertThat(response.appliedBy()).isEqualTo("openai:gpt-5.4-mini");
        assertThat(response.appliedAt()).isNotNull();
        assertThat(response.validationResult().path("checks").get(0).asText()).isEqualTo("schema-compatible");
        verify(materializationRepository).save(materialization);
    }

    private DomainRuleService service(
            DomainRuleDefinitionRepository definitionRepository,
            DomainRuleMaterializationRepository materializationRepository) {
        return new DomainRuleService(
                definitionRepository,
                materializationRepository,
                mock(DomainCatalogReleaseRepository.class),
                mock(DomainKnowledgeChangeSetRepository.class),
                objectMapper);
    }
}
