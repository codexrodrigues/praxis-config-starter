package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.praxisplatform.config.exception.ConfigurationIngestionException;
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
        assertThat(response.grounding().path("decisionDiagnostics").path("decisionKind").asText())
                .isEqualTo("semantic_domain_rule");
        assertThat(response.grounding().path("decisionDiagnostics").path("decisionStage").asText())
                .isEqualTo("intake");
        assertThat(response.grounding().path("decisionDiagnostics").path("decisionSource").asText())
                .isEqualTo("persisted_definition");
        assertThat(response.grounding().path("decisionDiagnostics").path("canonicalOwner").asText())
                .isEqualTo("praxis-config-starter");
        assertThat(response.grounding().path("decisionDiagnostics").path("predictedMaterializationCount").asInt())
                .isEqualTo(2);
        assertThat(response.grounding().path("decisionDiagnostics").path("runtimeSurfacesAreDerived").asBoolean())
                .isTrue();

        ArgumentCaptor<DomainRuleDefinition> captor = ArgumentCaptor.forClass(DomainRuleDefinition.class);
        verify(definitionRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleType()).isEqualTo("selection_eligibility");
        assertThat(captor.getValue().getResourceKey()).isEqualTo("procurement.suppliers");
        assertThat(captor.getValue().getStatus()).isEqualTo("draft");
    }

    @Test
    void intakesAndPublishesProcurementSupplierEligibilityDecisionAsOptionSourceProjection() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);
        AtomicReference<DomainRuleDefinition> persistedDefinition = new AtomicReference<>();

        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> {
            DomainRuleDefinition definition = invocation.getArgument(0);
            if (definition.getId() == null) {
                definition.onInsert();
            }
            persistedDefinition.set(definition);
            return definition;
        });
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "procurement.suppliers",
                List.of("approved", "active")))
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                eq("tenant-a"),
                eq("dev"),
                any(UUID.class)))
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "procurement.suppliers.rule.selection-eligibility:option_source:supplier"))
                .thenReturn(Optional.empty());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "procurement.suppliers.rule.selection-eligibility:backend_validation:procurement.suppliers"))
                .thenReturn(Optional.empty());
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            materialization.onInsert();
            return materialization;
        });

        var intake = service.intake(
                new DomainRuleIntakeRequest(
                        "Impedir seleção de fornecedores inativos ou bloqueados em pedidos de compra.",
                        "A decisão deve ser governada no Praxis e materializada como option_source.",
                        null,
                        "selection_eligibility",
                        "procurement",
                        "procurement.suppliers",
                        "praxis-api-quickstart",
                        objectMapper.readTree("""
                                {
                                  "summary": "Impedir seleção de fornecedores inativos ou bloqueados em pedidos de compra."
                                }
                                """),
                        objectMapper.readTree("""
                                {
                                  "optionSourceKey": "supplier",
                                  "validationMessageTemplate": "Fornecedor indisponivel para novos pedidos",
                                  "disabledReasonTemplate": "Fornecedor com status indisponivel"
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
                                  "ruleAuthoring": "governed"
                                }
                                """),
                        "llm",
                        "openai:gpt-5.4-mini"),
                "tenant-a",
                "dev");

        DomainRuleDefinition approvedDefinition = persistedDefinition.get();
        approvedDefinition.setStatus("approved");
        when(definitionRepository.findById(intake.definition().id())).thenReturn(Optional.of(approvedDefinition));

        var simulation = service.simulate(
                new DomainRuleSimulationRequest(
                        intake.definition().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                "tenant-a",
                "dev");
        var publication = service.publish(
                new DomainRulePublicationRequest(
                        intake.definition().id(),
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");

        assertThat(intake.grounding().path("decisionDiagnostics").path("decisionStage").asText())
                .isEqualTo("intake");
        assertThat(simulation.predictedMaterializations()).hasSize(2);
        assertThat(simulation.predictedMaterializations())
                .anySatisfy(target -> {
                    assertThat(target.path("targetLayer").asText()).isEqualTo("option_source");
                    assertThat(target.path("targetArtifactType").asText()).isEqualTo("resource-option-source");
                    assertThat(target.path("targetArtifactKey").asText()).isEqualTo("supplier");
                })
                .anySatisfy(target -> {
                    assertThat(target.path("targetLayer").asText()).isEqualTo("backend_validation");
                    assertThat(target.path("targetArtifactType").asText()).isEqualTo("resource-validation");
                    assertThat(target.path("targetArtifactKey").asText()).isEqualTo("procurement.suppliers");
                });
        assertThat(simulation.explainability().path("decisionDiagnostics").path("decisionSource").asText())
                .isEqualTo("persisted_definition");
        assertThat(publication.publicationStatus()).isEqualTo("published");
        assertThat(publication.definition().status()).isEqualTo("active");
        assertThat(publication.explainability().path("decisionDiagnostics").path("canonicalOwner").asText())
                .isEqualTo("praxis-config-starter");
        assertThat(publication.explainability().path("decisionDiagnostics").path("runtimeSurfacesAreDerived").asBoolean())
                .isTrue();
        assertThat(publication.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .hasSize(2);
        assertThat(publication.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .anySatisfy(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("created");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:option_source:supplier");
                    assertThat(outcome.path("sourceHash").asText()).startsWith("derived:sha256:");
                })
                .anySatisfy(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("created");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:backend_validation:procurement.suppliers");
                    assertThat(outcome.path("sourceHash").asText()).startsWith("derived:sha256:");
                });
        assertThat(publication.materializations()).hasSize(2);
        assertThat(publication.materializations())
                .anySatisfy(materialization -> {
                    JsonNode selectionPolicy = materialization.materializedPayload().path("selectionPolicy");
                    assertThat(materialization.status()).isEqualTo("applied");
                    assertThat(materialization.targetLayer()).isEqualTo("option_source");
                    assertThat(materialization.targetArtifactType()).isEqualTo("resource-option-source");
                    assertThat(materialization.targetArtifactKey()).isEqualTo("supplier");
                    assertThat(materialization.decisionDiagnostics().path("decisionStage").asText())
                            .isEqualTo("materialization");
                    assertThat(materialization.decisionDiagnostics().path("runtimeSurfacesAreDerived").asBoolean())
                            .isTrue();
                    assertThat(selectionPolicy.path("statusPropertyPath").asText()).isEqualTo("status");
                    assertThat(selectionPolicy.path("blockedStatuses"))
                            .extracting(JsonNode::asText)
                            .containsExactly("INACTIVE", "BLOCKED");
                    assertThat(selectionPolicy.path("validationMessageTemplate").asText())
                            .isEqualTo("Fornecedor indisponivel para novos pedidos");
                    assertThat(selectionPolicy.path("disabledReasonTemplate").asText())
                            .isEqualTo("Fornecedor com status indisponivel");
                })
                .anySatisfy(materialization -> {
                    JsonNode validationPolicy = materialization.materializedPayload().path("validationPolicy");
                    assertThat(materialization.status()).isEqualTo("applied");
                    assertThat(materialization.targetLayer()).isEqualTo("backend_validation");
                    assertThat(materialization.targetArtifactType()).isEqualTo("resource-validation");
                    assertThat(materialization.targetArtifactKey()).isEqualTo("procurement.suppliers");
                    assertThat(materialization.decisionDiagnostics().path("decisionStage").asText())
                            .isEqualTo("materialization");
                    assertThat(materialization.decisionDiagnostics().path("runtimeSurfacesAreDerived").asBoolean())
                            .isTrue();
                    assertThat(materialization.materializedPayload().path("kind").asText())
                            .isEqualTo("resource_validation_policy");
                    assertThat(validationPolicy.path("condition").path("in")).hasSize(2);
                    assertThat(validationPolicy.path("parameters").path("validationMessageTemplate").asText())
                            .isEqualTo("Fornecedor indisponivel para novos pedidos");
                });
    }

    @Test
    void publishesWorkflowActionPolicyAsDerivedWorkflowActionMaterialization() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("human-resources.folhas-pagamento.rule.mark-paid-compliance")
                .version(1)
                .ruleType("workflow_action_policy")
                .status("approved")
                .contextKey("human-resources")
                .resourceKey("human-resources.folhas-pagamento")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Bloquear pagamento de folha quando houver pendencia de compliance.\"}")
                .parameters("""
                        {
                          "workflowAction": {
                            "resourceKey": "human-resources.folhas-pagamento",
                            "actionId": "mark-paid"
                          },
                          "requiredStates": ["PROGRAMADA"],
                          "requiredApprovals": ["finance-owner"],
                          "message": "Pagamento bloqueado por decisao governada ate revisao de compliance."
                        }
                        """)
                .condition("""
                        {
                          "var": "complianceHold"
                        }
                        """)
                .governance("{\"approval\":\"finance-owner\"}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "human-resources.folhas-pagamento",
                List.of("approved", "active")))
                .thenReturn(List.of());
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                "tenant-a",
                "dev",
                definitionId))
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "human-resources.folhas-pagamento.rule.mark-paid-compliance:workflow_action:human-resources.folhas-pagamento:mark-paid"))
                .thenReturn(Optional.empty());
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            materialization.setId(UUID.randomUUID());
            materialization.onInsert();
            return materialization;
        });

        var response = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "finance-owner",
                        null),
                "tenant-a",
                "dev");

        assertThat(response.publicationStatus()).isEqualTo("published");
        assertThat(response.explainability().path("decisionDiagnostics").path("predictedMaterializationCount").asInt())
                .isEqualTo(1);
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .hasSize(1)
                .first()
                .satisfies(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("created");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("human-resources.folhas-pagamento.rule.mark-paid-compliance:workflow_action:human-resources.folhas-pagamento:mark-paid");
                    assertThat(outcome.path("targetLayer").asText()).isEqualTo("workflow_action");
                    assertThat(outcome.path("targetArtifactType").asText()).isEqualTo("resource-workflow-action");
                    assertThat(outcome.path("sourceHash").asText()).startsWith("derived:sha256:");
                });
        assertThat(response.materializations()).hasSize(1)
                .first()
                .satisfies(materialization -> {
                    JsonNode payload = materialization.materializedPayload();
                    assertThat(materialization.targetLayer()).isEqualTo("workflow_action");
                    assertThat(materialization.targetArtifactType()).isEqualTo("resource-workflow-action");
                    assertThat(materialization.targetArtifactKey())
                            .isEqualTo("human-resources.folhas-pagamento:mark-paid");
                    assertThat(materialization.targetPointer()).isEqualTo("/availabilityPolicy");
                    assertThat(materialization.materializedRuleId()).isEqualTo("workflow-action-policy");
                    assertThat(materialization.status()).isEqualTo("applied");
                    assertThat(materialization.sourceHash()).startsWith("derived:sha256:");
                    assertThat(materialization.decisionDiagnostics().path("runtimeSurfacesAreDerived").asBoolean())
                            .isTrue();
                    assertThat(payload.path("kind").asText()).isEqualTo("workflow_action_policy");
                    assertThat(payload.path("workflowAction").path("resourceKey").asText())
                            .isEqualTo("human-resources.folhas-pagamento");
                    assertThat(payload.path("workflowAction").path("actionId").asText()).isEqualTo("mark-paid");
                    assertThat(payload.path("availabilityPolicy").path("requiredStates"))
                            .extracting(JsonNode::asText)
                            .containsExactly("PROGRAMADA");
                    assertThat(payload.path("availabilityPolicy").path("condition").path("var").asText())
                            .isEqualTo("complianceHold");
                    assertThat(payload.path("availabilityPolicy").path("message").asText())
                            .isEqualTo("Pagamento bloqueado por decisao governada ate revisao de compliance.");
                    assertThat(payload.path("approvalPolicy").path("requiredApprovals"))
                            .extracting(JsonNode::asText)
                            .containsExactly("finance-owner");
                });
    }

    @Test
    void publishesApprovalPolicyAsDerivedApprovalMaterialization() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("human-resources.eventos-folha.rule.bulk-approve-approval")
                .version(1)
                .ruleType("approval_policy")
                .status("approved")
                .contextKey("human-resources")
                .resourceKey("human-resources.eventos-folha")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Exigir aprovacao gerencial para aprovacao em massa de eventos.\"}")
                .parameters("""
                        {
                          "approvalAction": {
                            "resourceKey": "human-resources.eventos-folha",
                            "actionId": "bulk-approve"
                          },
                          "requiredApprovals": ["payroll-manager"],
                          "approvalGroups": ["hr-payroll"],
                          "approverContext": "payroll-events",
                          "message": "Aprovacao em massa exige decisao gerencial governada.",
                          "severity": "blocking"
                        }
                        """)
                .condition("""
                        {
                          ">": [
                            { "var": "selectedCount" },
                            25
                          ]
                        }
                        """)
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "human-resources.eventos-folha",
                List.of("approved", "active")))
                .thenReturn(List.of());
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                "tenant-a",
                "dev",
                definitionId))
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "human-resources.eventos-folha.rule.bulk-approve-approval:approval_policy:human-resources.eventos-folha:bulk-approve"))
                .thenReturn(Optional.empty());
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            materialization.setId(UUID.randomUUID());
            materialization.onInsert();
            return materialization;
        });

        var response = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "payroll-manager",
                        null),
                "tenant-a",
                "dev");

        assertThat(response.publicationStatus()).isEqualTo("published");
        assertThat(response.explainability().path("decisionDiagnostics").path("predictedMaterializationCount").asInt())
                .isEqualTo(1);
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .hasSize(1)
                .first()
                .satisfies(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("created");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("human-resources.eventos-folha.rule.bulk-approve-approval:approval_policy:human-resources.eventos-folha:bulk-approve");
                    assertThat(outcome.path("targetLayer").asText()).isEqualTo("approval_policy");
                    assertThat(outcome.path("targetArtifactType").asText()).isEqualTo("resource-action-approval");
                    assertThat(outcome.path("sourceHash").asText()).startsWith("derived:sha256:");
                });
        assertThat(response.materializations()).hasSize(1)
                .first()
                .satisfies(materialization -> {
                    JsonNode payload = materialization.materializedPayload();
                    assertThat(materialization.targetLayer()).isEqualTo("approval_policy");
                    assertThat(materialization.targetArtifactType()).isEqualTo("resource-action-approval");
                    assertThat(materialization.targetArtifactKey())
                            .isEqualTo("human-resources.eventos-folha:bulk-approve");
                    assertThat(materialization.targetPointer()).isEqualTo("/approvalPolicy");
                    assertThat(materialization.materializedRuleId()).isEqualTo("approval-policy");
                    assertThat(materialization.status()).isEqualTo("applied");
                    assertThat(materialization.sourceHash()).startsWith("derived:sha256:");
                    assertThat(materialization.decisionDiagnostics().path("runtimeSurfacesAreDerived").asBoolean())
                            .isTrue();
                    assertThat(payload.path("kind").asText()).isEqualTo("approval_policy");
                    assertThat(payload.path("resourceAction").path("resourceKey").asText())
                            .isEqualTo("human-resources.eventos-folha");
                    assertThat(payload.path("resourceAction").path("actionId").asText()).isEqualTo("bulk-approve");
                    assertThat(payload.path("approvalPolicy").path("requiredApprovals"))
                            .extracting(JsonNode::asText)
                            .containsExactly("payroll-manager");
                    assertThat(payload.path("approvalPolicy").path("approvalGroups"))
                            .extracting(JsonNode::asText)
                            .containsExactly("hr-payroll");
                    assertThat(payload.path("approvalPolicy").path("approverContext").asText())
                            .isEqualTo("payroll-events");
                    assertThat(payload.path("approvalPolicy").path("condition").path(">").get(1).asInt())
                            .isEqualTo(25);
                    assertThat(payload.path("approvalPolicy").path("message").asText())
                            .isEqualTo("Aprovacao em massa exige decisao gerencial governada.");
                });
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
    void blocksDefinitionCreationWithNonCanonicalStatus() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        assertThatThrownBy(() -> service.createDefinition(new DomainRuleDefinitionRequest(
                "human-resources.funcionarios.rule.lgpd-cpf-guidance",
                null,
                "visual_guidance",
                "ready",
                "human-resources",
                "human-resources.funcionarios",
                "praxis-api-quickstart",
                "data-owner",
                "privacy-office",
                null,
                null,
                objectMapper.readTree("{\"summary\":\"Orientar analistas sobre CPF como dado pessoal.\"}"),
                objectMapper.readTree("{}"),
                null,
                objectMapper.readTree("{}"),
                null,
                "llm",
                "openai:gpt-5.4-mini",
                null), "tenant-a", "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("status must be one of [draft, proposed, approved, active, deprecated, retired, rejected]");

        verify(definitionRepository, org.mockito.Mockito.never()).save(any(DomainRuleDefinition.class));
    }

    @Test
    void createsFormConfigMaterializationForSharedRuleDefinition() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
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
        assertThat(response.decisionDiagnostics().path("decisionKind").asText())
                .isEqualTo("semantic_domain_rule");
        assertThat(response.decisionDiagnostics().path("decisionStage").asText())
                .isEqualTo("materialization");
        assertThat(response.decisionDiagnostics().path("decisionSource").asText())
                .isEqualTo("materialization_record");
        assertThat(response.decisionDiagnostics().path("canonicalOwner").asText())
                .isEqualTo("praxis-config-starter");
        assertThat(response.decisionDiagnostics().path("materializationKey").asText())
                .isEqualTo("funcionarios-form-demo:formRules:lgpd-cpf-guidance");
        assertThat(response.decisionDiagnostics().path("targetLayer").asText())
                .isEqualTo("form_config");
        assertThat(response.decisionDiagnostics().path("runtimeSurfacesAreDerived").asBoolean())
                .isTrue();
        assertThat(response.decisionDiagnostics().path("sourceHashPresent").asBoolean())
                .isTrue();
        assertThat(response.decisionDiagnostics().path("sourceHash").asText())
                .isEqualTo("hash-123");

        ArgumentCaptor<DomainRuleMaterialization> captor = ArgumentCaptor.forClass(DomainRuleMaterialization.class);
        verify(materializationRepository).save(captor.capture());
        assertThat(captor.getValue().getRuleDefinition()).isSameAs(definition);
        assertThat(captor.getValue().getMaterializedRuleId()).isEqualTo("lgpd-cpf-guidance");
    }

    @Test
    void reusesExistingMaterializationForSameStableKeyAndDefinition() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        UUID materializationId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(definitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("human-resources.funcionarios.rule.lgpd-cpf-guidance")
                .version(2)
                .ruleType("visual_guidance")
                .status("active")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();
        DomainRuleMaterialization existing = DomainRuleMaterialization.builder()
                .id(materializationId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleDefinition(definition)
                .materializationKey("funcionarios-form-demo:formRules:lgpd-cpf-guidance")
                .targetLayer("form_config")
                .targetArtifactType("praxis-dynamic-form")
                .targetArtifactKey("funcionarios-form-demo")
                .targetPointer("/formRules/-")
                .targetReleaseKey("page-builder-demo@local")
                .materializedRuleId("lgpd-cpf-guidance")
                .status("pending_review")
                .materializedPayload("{\"id\":\"lgpd-cpf-guidance\"}")
                .sourceHash("hash-123")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "funcionarios-form-demo:formRules:lgpd-cpf-guidance"))
                .thenReturn(Optional.of(existing));

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
                objectMapper.readTree("{\"id\":\"lgpd-cpf-guidance\"}"),
                "hash-123",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev");

        assertThat(response.id()).isEqualTo(materializationId);
        assertThat(response.sourceHash()).isEqualTo("hash-123");
        assertThat(response.decisionDiagnostics().path("sourceHash").asText()).isEqualTo("hash-123");
        assertThat(response.status()).isEqualTo("pending_review");
        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void blocksMaterializationCreationWhenStableKeyBelongsToAnotherDefinition() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID requestedDefinitionId = UUID.randomUUID();
        UUID existingDefinitionId = UUID.randomUUID();
        DomainRuleDefinition requestedDefinition = DomainRuleDefinition.builder()
                .id(requestedDefinitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("human-resources.funcionarios.rule.lgpd-cpf-guidance")
                .version(2)
                .ruleType("visual_guidance")
                .status("active")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();
        DomainRuleDefinition existingDefinition = DomainRuleDefinition.builder()
                .id(existingDefinitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("human-resources.funcionarios.rule.previous-guidance")
                .version(1)
                .ruleType("visual_guidance")
                .status("active")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();
        DomainRuleMaterialization existing = DomainRuleMaterialization.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .ruleDefinition(existingDefinition)
                .materializationKey("funcionarios-form-demo:formRules:lgpd-cpf-guidance")
                .targetLayer("form_config")
                .targetArtifactType("praxis-dynamic-form")
                .targetArtifactKey("funcionarios-form-demo")
                .status("pending_review")
                .materializedPayload("{}")
                .sourceHash("hash-previous")
                .build();

        when(definitionRepository.findById(requestedDefinitionId)).thenReturn(Optional.of(requestedDefinition));
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "funcionarios-form-demo:formRules:lgpd-cpf-guidance"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createMaterialization(new DomainRuleMaterializationRequest(
                requestedDefinitionId,
                "funcionarios-form-demo:formRules:lgpd-cpf-guidance",
                "form_config",
                "praxis-dynamic-form",
                "funcionarios-form-demo",
                "/formRules/-",
                "page-builder-demo@local",
                "lgpd-cpf-guidance",
                "pending_review",
                objectMapper.readTree("{\"id\":\"lgpd-cpf-guidance\"}"),
                "hash-123",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule materialization key already belongs to another definition");

        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void blocksMaterializationCreationWithNonCanonicalStatus() {
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
                .resourceKey("procurement.suppliers")
                .definition("{\"summary\":\"Impedir selecao de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));

        assertThatThrownBy(() -> service.createMaterialization(new DomainRuleMaterializationRequest(
                definitionId,
                "procurement.suppliers.rule.selection-eligibility:option_source:supplier",
                "option_source",
                "resource-option-source",
                "supplier",
                "/selectionPolicy",
                null,
                "selection-policy",
                "ready",
                null,
                "hash-selection-eligibility",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("status must be one of [draft, pending_review, applied, failed, superseded, reverted]");

        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void blocksAppliedMaterializationCreationWhenDefinitionIsNotActive() {
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
                .resourceKey("procurement.suppliers")
                .definition("{\"summary\":\"Impedir selecao de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));

        assertThatThrownBy(() -> service.createMaterialization(new DomainRuleMaterializationRequest(
                definitionId,
                "procurement.suppliers.rule.selection-eligibility:option_source:supplier",
                "option_source",
                "resource-option-source",
                "supplier",
                "/selectionPolicy",
                null,
                "selection-policy",
                "applied",
                null,
                "hash-selection-eligibility",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule materialization can only be applied when its definition is active");

        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void createsAppliedMaterializationWhenDefinitionIsActive() {
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
                .status("active")
                .resourceKey("procurement.suppliers")
                .definition("{\"summary\":\"Impedir selecao de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
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
                "applied",
                null,
                "hash-selection-eligibility",
                null,
                "llm",
                "openai:gpt-5.4-mini"), "tenant-a", "dev");

        assertThat(response.status()).isEqualTo("applied");
        assertThat(response.appliedAt()).isNotNull();
        assertThat(response.appliedByType()).isEqualTo("llm");
        assertThat(response.appliedBy()).isEqualTo("openai:gpt-5.4-mini");
    }

    @Test
    void createsOptionSourceMaterializationFromSelectionEligibilityDefinitionWhenPayloadIsOmitted() {
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
    void blocksMaterializationWhenDefinitionScopeDoesNotMatchRequestScope() {
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
                .resourceKey("procurement.suppliers")
                .definition("{\"summary\":\"Impedir selecao de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));

        assertThatThrownBy(() -> service.createMaterialization(new DomainRuleMaterializationRequest(
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
                "openai:gpt-5.4-mini"), "tenant-b", "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule tenantId does not match request scope");

        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
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
    void blocksDefinitionTransitionFromRetiredBackToActive() throws Exception {
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
                .status("retired")
                .definition("{}")
                .parameters("{}")
                .governance("{}")
                .build();
        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));

        assertThatThrownBy(() -> service.transitionDefinitionStatus(
                definitionId,
                new DomainRuleStatusTransitionRequest(
                        "active",
                        "human",
                        "privacy-office",
                        objectMapper.readTree("{\"review\":\"approved\"}")),
                "tenant-a",
                "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule definition status transition is not allowed: retired -> active");

        assertThat(definition.getStatus()).isEqualTo("retired");
        verify(definitionRepository, org.mockito.Mockito.never()).save(any(DomainRuleDefinition.class));
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
        assertThat(response.explainability().path("decisionDiagnostics").path("decisionKind").asText())
                .isEqualTo("semantic_domain_rule");
        assertThat(response.explainability().path("decisionDiagnostics").path("authoringMode").asText())
                .isEqualTo("governed");
        assertThat(response.explainability().path("decisionDiagnostics").path("decisionSource").asText())
                .isEqualTo("ad_hoc_request");
        assertThat(response.explainability().path("decisionDiagnostics").path("canonicalOwner").asText())
                .isEqualTo("praxis-config-starter");
        assertThat(response.explainability().path("decisionDiagnostics").path("runtimeSurfacesAreDerived").asBoolean())
                .isTrue();
        assertThat(response.explainability().path("decisionDiagnostics").path("existingCoverageCount").asInt())
                .isEqualTo(1);
        assertThat(response.explainability().path("decisionDiagnostics").path("predictedMaterializationCount").asInt())
                .isEqualTo(1);
        assertThat(response.explainability().path("decisionDiagnostics").path("requiredApprovalCount").asInt())
                .isEqualTo(1);
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
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .singleElement()
                .satisfies(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("selected_existing");
                    assertThat(outcome.path("materializationKey").asText()).isEqualTo("supplier:selection-policy");
                    assertThat(outcome.path("targetLayer").asText()).isEqualTo("backend_validation");
                    assertThat(outcome.path("statusAtResolution").asText()).isEqualTo("pending_review");
                });
        assertThat(response.materializations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.appliedBy()).isEqualTo("procurement-owner");
                });
    }

    @Test
    void reportsExistingDerivedMaterializationAsReusedDuringPublication() {
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
                .ruleType("selection_eligibility")
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
                .materializationKey("procurement.suppliers.rule.selection-eligibility:option_source:supplier")
                .targetLayer("option_source")
                .targetArtifactType("resource-option-source")
                .targetArtifactKey("supplier")
                .status("applied")
                .materializedPayload("{}")
                .sourceHash("derived:sha256:previous")
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
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .singleElement()
                .satisfies(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("reused");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:option_source:supplier");
                    assertThat(outcome.path("sourceHash").asText()).isEqualTo("derived:sha256:previous");
                    assertThat(outcome.path("statusAtResolution").asText()).isEqualTo("applied");
                });
    }

    @Test
    void blocksPublicationWhenSelectedMaterializationStatusIsNotPublishable() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID definitionId = UUID.randomUUID();
        UUID materializationId = UUID.randomUUID();
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
                .id(materializationId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleDefinition(definition)
                .materializationKey("supplier:selection-policy")
                .targetLayer("backend_validation")
                .targetArtifactType("resource-validation")
                .targetArtifactKey("procurement.suppliers")
                .status("failed")
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
        when(materializationRepository.findById(materializationId)).thenReturn(Optional.of(materialization));

        assertThatThrownBy(() -> service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        List.of(materializationId),
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule materialization status is not publishable: failed");

        assertThat(materialization.getStatus()).isEqualTo("failed");
        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void blocksPublicationWhenDefinitionStatusIsNotPublishable() {
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
                .status("rejected")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Impedir seleção de fornecedores bloqueados.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .governance("{}")
                .build();

        when(definitionRepository.findById(definitionId)).thenReturn(Optional.of(definition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                "tenant-a",
                "dev",
                "procurement.suppliers",
                List.of("approved", "active")))
                .thenReturn(List.of());

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

        assertThat(response.publicationStatus()).isEqualTo("blocked");
        assertThat(response.publicationReadiness()).isEqualTo("blocked_by_definition_status");
        assertThat(response.definition().status()).isEqualTo("rejected");
        assertThat(response.materializations()).isEmpty();
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("publicationStatus")
                .asText())
                .isEqualTo("blocked");
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("blockedReason")
                .asText())
                .isEqualTo("definition_status_not_publishable");
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("definitionStatusAtResolution")
                .asText())
                .isEqualTo("rejected");
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .isEmpty();
        verify(definitionRepository, org.mockito.Mockito.never()).save(any(DomainRuleDefinition.class));
        verify(materializationRepository, org.mockito.Mockito.never()).findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                any(),
                any(),
                any());
        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
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
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(any(), any(), any()))
                .thenReturn(Optional.empty());
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
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .hasSize(2);
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .anySatisfy(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("created");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:option_source:supplier");
                    assertThat(outcome.path("sourceHash").asText()).startsWith("derived:sha256:");
                })
                .anySatisfy(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("created");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:backend_validation:procurement.suppliers");
                    assertThat(outcome.path("sourceHash").asText()).startsWith("derived:sha256:");
                });
        assertThat(response.explainability().path("decisionDiagnostics").path("decisionSource").asText())
                .isEqualTo("persisted_definition");
        assertThat(response.explainability().path("decisionDiagnostics").path("materializationModel").asText())
                .isEqualTo("derived_projection");
        assertThat(response.explainability().path("decisionDiagnostics").path("predictedMaterializationCount").asInt())
                .isEqualTo(2);
        assertThat(response.materializations()).hasSize(2);
        assertThat(response.materializations())
                .anySatisfy(item -> {
                    assertThat(item.targetLayer()).isEqualTo("option_source");
                    assertThat(item.targetArtifactType()).isEqualTo("resource-option-source");
                    assertThat(item.targetArtifactKey()).isEqualTo("supplier");
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.materializedPayload().path("kind").asText()).isEqualTo("lookup_selection_policy");
                    assertThat(item.materializedPayload().path("selectionPolicy").path("blockedStatuses"))
                            .extracting(JsonNode::asText)
                            .containsExactly("INACTIVE", "BLOCKED");
                })
                .anySatisfy(item -> {
                    assertThat(item.targetLayer()).isEqualTo("backend_validation");
                    assertThat(item.targetArtifactType()).isEqualTo("resource-validation");
                    assertThat(item.targetArtifactKey()).isEqualTo("procurement.suppliers");
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.materializedPayload().path("kind").asText()).isEqualTo("resource_validation_policy");
                    assertThat(item.materializedPayload().path("validationPolicy").path("condition").path("in"))
                            .hasSize(2);
                });
    }

    @Test
    void publicationReusesExistingDerivedMaterializationForStableKeyAndSourceHash() {
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
        java.util.Map<String, AtomicInteger> keyLookups = new java.util.HashMap<>();
        java.util.Map<String, DomainRuleMaterialization> savedMaterializations = new java.util.HashMap<>();

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
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(any(), any(), any()))
                .thenAnswer(invocation -> {
                    String materializationKey = invocation.getArgument(2);
                    AtomicInteger lookupCount = keyLookups.computeIfAbsent(materializationKey, ignored -> new AtomicInteger());
                    return lookupCount.getAndIncrement() == 0
                            ? Optional.empty()
                            : Optional.ofNullable(savedMaterializations.get(materializationKey));
                });
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            if (materialization.getId() == null) {
                materialization.setId(UUID.randomUUID());
            }
            materialization.onInsert();
            savedMaterializations.put(materialization.getMaterializationKey(), materialization);
            return materialization;
        });

        var firstResponse = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");
        var secondResponse = service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");

        assertThat(firstResponse.publicationStatus()).isEqualTo("published");
        assertThat(firstResponse.materializations()).hasSize(2)
                .allSatisfy(item -> assertThat(item.sourceHash()).startsWith("derived:sha256:"));
        assertThat(secondResponse.publicationStatus()).isEqualTo("published");
        assertThat(secondResponse.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .hasSize(2);
        assertThat(secondResponse.explainability()
                .path("publicationDiagnostics")
                .path("materializationOutcomes"))
                .anySatisfy(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("reused");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:option_source:supplier");
                    assertThat(outcome.path("sourceHash").asText())
                            .isEqualTo(sourceHashFor(firstResponse, "option_source"));
                })
                .anySatisfy(outcome -> {
                    assertThat(outcome.path("resolution").asText()).isEqualTo("reused");
                    assertThat(outcome.path("materializationKey").asText())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:backend_validation:procurement.suppliers");
                    assertThat(outcome.path("sourceHash").asText())
                            .isEqualTo(sourceHashFor(firstResponse, "backend_validation"));
                });
        assertThat(secondResponse.materializations()).hasSize(2);
        assertThat(secondResponse.materializations())
                .anySatisfy(item -> {
                    assertThat(item.id()).isEqualTo(materializationFor(firstResponse, "option_source").id());
                    assertThat(item.materializationKey())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:option_source:supplier");
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.sourceHash()).isEqualTo(sourceHashFor(firstResponse, "option_source"));
                })
                .anySatisfy(item -> {
                    assertThat(item.id()).isEqualTo(materializationFor(firstResponse, "backend_validation").id());
                    assertThat(item.materializationKey())
                            .isEqualTo("procurement.suppliers.rule.selection-eligibility:backend_validation:procurement.suppliers");
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.sourceHash()).isEqualTo(sourceHashFor(firstResponse, "backend_validation"));
                });
        verify(materializationRepository, org.mockito.Mockito.times(4)).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void publicationBlocksDerivedMaterializationWhenStableKeyHasNoSourceHash() {
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
        DomainRuleMaterialization existing = DomainRuleMaterialization.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .ruleDefinition(definition)
                .materializationKey("procurement.suppliers.rule.selection-eligibility:option_source:supplier")
                .targetLayer("option_source")
                .targetArtifactType("resource-option-source")
                .targetArtifactKey("supplier")
                .targetPointer("/selectionPolicy")
                .materializedRuleId("selection-policy")
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
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "procurement.suppliers.rule.selection-eligibility:option_source:supplier"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Derived rule materialization key already exists without a sourceHash");

        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void publicationBlocksDerivedMaterializationWhenStableKeyBelongsToAnotherDefinition() {
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
        DomainRuleDefinition otherDefinition = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.selection-eligibility")
                .version(1)
                .ruleType("selection_eligibility")
                .status("active")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Outra decisao.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .condition("{}")
                .governance("{}")
                .build();
        DomainRuleMaterialization existing = DomainRuleMaterialization.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-a")
                .environment("dev")
                .ruleDefinition(otherDefinition)
                .materializationKey("procurement.suppliers.rule.selection-eligibility:option_source:supplier")
                .targetLayer("option_source")
                .targetArtifactType("resource-option-source")
                .targetArtifactKey("supplier")
                .targetPointer("/selectionPolicy")
                .materializedRuleId("selection-policy")
                .status("applied")
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
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(
                "tenant-a",
                "dev",
                "procurement.suppliers.rule.selection-eligibility:option_source:supplier"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.publish(
                new DomainRulePublicationRequest(
                        definitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule materialization key already belongs to another definition");

        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void derivedMaterializationSourceHashChangesWhenRuleSemanticsChange() {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);

        UUID inactiveDefinitionId = UUID.randomUUID();
        UUID suspendedDefinitionId = UUID.randomUUID();
        DomainRuleDefinition inactiveDefinition = DomainRuleDefinition.builder()
                .id(inactiveDefinitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.selection-eligibility")
                .version(1)
                .ruleType("selection_eligibility")
                .status("approved")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Impedir selecao de fornecedores inativos.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .condition("""
                        {
                          "in": [
                            { "var": "status" },
                            ["INACTIVE"]
                          ]
                        }
                        """)
                .governance("{}")
                .build();
        DomainRuleDefinition suspendedDefinition = DomainRuleDefinition.builder()
                .id(suspendedDefinitionId)
                .tenantId("tenant-a")
                .environment("dev")
                .ruleKey("procurement.suppliers.rule.selection-eligibility-suspended")
                .version(1)
                .ruleType("selection_eligibility")
                .status("approved")
                .contextKey("procurement")
                .resourceKey("procurement.suppliers")
                .serviceKey("praxis-api-quickstart")
                .definition("{\"summary\":\"Impedir selecao de fornecedores suspensos.\"}")
                .parameters("{\"optionSourceKey\":\"supplier\"}")
                .condition("""
                        {
                          "in": [
                            { "var": "status" },
                            ["SUSPENDED"]
                          ]
                        }
                        """)
                .governance("{}")
                .build();

        when(definitionRepository.findById(inactiveDefinitionId)).thenReturn(Optional.of(inactiveDefinition));
        when(definitionRepository.findById(suspendedDefinitionId)).thenReturn(Optional.of(suspendedDefinition));
        when(definitionRepository.findByTenantIdAndEnvironmentAndResourceKeyAndStatusIn(
                eq("tenant-a"),
                eq("dev"),
                eq("procurement.suppliers"),
                eq(List.of("approved", "active"))))
                .thenReturn(List.of());
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                eq("tenant-a"),
                eq("dev"),
                any(UUID.class)))
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(materializationRepository.save(any(DomainRuleMaterialization.class))).thenAnswer(invocation -> {
            DomainRuleMaterialization materialization = invocation.getArgument(0);
            if (materialization.getId() == null) {
                materialization.setId(UUID.randomUUID());
            }
            materialization.onInsert();
            return materialization;
        });

        var inactiveResponse = service.publish(
                new DomainRulePublicationRequest(
                        inactiveDefinitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");
        var suspendedResponse = service.publish(
                new DomainRulePublicationRequest(
                        suspendedDefinitionId,
                        null,
                        true,
                        "human",
                        "procurement-owner",
                        null),
                "tenant-a",
                "dev");

        assertThat(inactiveResponse.materializations()).hasSize(2);
        assertThat(inactiveResponse.materializations())
                .anySatisfy(item -> {
                    assertThat(item.targetLayer()).isEqualTo("option_source");
                    assertThat(item.targetArtifactKey()).isEqualTo("supplier");
                    assertThat(item.sourceHash()).startsWith("derived:sha256:");
                })
                .anySatisfy(item -> {
                    assertThat(item.targetLayer()).isEqualTo("backend_validation");
                    assertThat(item.targetArtifactKey()).isEqualTo("procurement.suppliers");
                    assertThat(item.sourceHash()).startsWith("derived:sha256:");
                });
        assertThat(suspendedResponse.materializations()).hasSize(2);
        assertThat(suspendedResponse.materializations())
                .allSatisfy(materialization ->
                        assertThat(materialization.sourceHash()).startsWith("derived:sha256:"));
        assertThat(sourceHashFor(inactiveResponse, "option_source"))
                .isNotEqualTo(sourceHashFor(suspendedResponse, "option_source"));
        assertThat(sourceHashFor(inactiveResponse, "backend_validation"))
                .isNotEqualTo(sourceHashFor(suspendedResponse, "backend_validation"));
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
                .ruleKey("procurement.purchase-orders.rule.supplier-backend-validation.20260424231531")
                .version(1)
                .ruleType("validation")
                .status("approved")
                .contextKey("procurement")
                .resourceKey("procurement.purchase-orders")
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
                "procurement.purchase-orders",
                List.of("approved", "active")))
                .thenReturn(List.of());
        when(definitionRepository.save(any(DomainRuleDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materializationRepository.findByTenantIdAndEnvironmentAndRuleDefinition_Id(
                "tenant-a",
                "dev",
                definitionId))
                .thenReturn(List.of());
        when(materializationRepository.findByTenantIdAndEnvironmentAndMaterializationKey(any(), any(), any()))
                .thenReturn(Optional.empty());
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
                    assertThat(item.targetArtifactKey()).isEqualTo("procurement.purchase-orders");
                    assertThat(item.targetPointer()).isEqualTo("/validationPolicy");
                    assertThat(item.materializedRuleId()).isEqualTo("backend-validation-policy");
                    assertThat(item.status()).isEqualTo("applied");
                    assertThat(item.sourceHash()).startsWith("derived:sha256:");
                    assertThat(item.sourceHash()).hasSizeLessThanOrEqualTo(128);
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
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("publicationStatus")
                .asText())
                .isEqualTo("blocked");
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("publicationReadiness")
                .asText())
                .isEqualTo("approval_required");
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("blockedReason")
                .asText())
                .isEqualTo("approval_required");
        assertThat(response.explainability()
                .path("publicationDiagnostics")
                .path("definitionStatusAtResolution")
                .asText())
                .isEqualTo("draft");
        assertThat(response.explainability().path("decisionDiagnostics").path("decisionSource").asText())
                .isEqualTo("persisted_definition");
        assertThat(response.explainability().path("decisionDiagnostics").path("requiredApprovalCount").asInt())
                .isEqualTo(1);
        assertThat(response.explainability().path("decisionDiagnostics").path("runtimeSurfacesAreDerived").asBoolean())
                .isTrue();
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

    @Test
    void blocksMaterializationTransitionFromRevertedDirectlyToApplied() throws Exception {
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
                .status("reverted")
                .materializedPayload("{}")
                .build();
        when(materializationRepository.findById(materializationId)).thenReturn(Optional.of(materialization));

        assertThatThrownBy(() -> service.transitionMaterializationStatus(
                materializationId,
                new DomainRuleStatusTransitionRequest(
                        "applied",
                        "llm",
                        "openai:gpt-5.4-mini",
                        objectMapper.readTree("{\"checks\":[\"schema-compatible\"]}")),
                "tenant-a",
                "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule materialization status transition is not allowed: reverted -> applied");

        assertThat(materialization.getStatus()).isEqualTo("reverted");
        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    @Test
    void blocksApplyingMaterializationWhenDefinitionIsNotActive() throws Exception {
        DomainRuleDefinitionRepository definitionRepository = mock(DomainRuleDefinitionRepository.class);
        DomainRuleMaterializationRepository materializationRepository = mock(DomainRuleMaterializationRepository.class);
        DomainRuleService service = service(definitionRepository, materializationRepository);
        UUID materializationId = UUID.randomUUID();
        DomainRuleDefinition definition = DomainRuleDefinition.builder()
                .id(UUID.randomUUID())
                .ruleKey("rule-a")
                .version(1)
                .ruleType("visual_guidance")
                .status("approved")
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

        assertThatThrownBy(() -> service.transitionMaterializationStatus(
                materializationId,
                new DomainRuleStatusTransitionRequest(
                        "applied",
                        "llm",
                        "openai:gpt-5.4-mini",
                        objectMapper.readTree("{\"checks\":[\"schema-compatible\"]}")),
                "tenant-a",
                "dev"))
                .isInstanceOf(ConfigurationIngestionException.class)
                .hasMessageContaining("Rule materialization can only be applied when its definition is active");

        assertThat(materialization.getStatus()).isEqualTo("pending_review");
        verify(materializationRepository, org.mockito.Mockito.never()).save(any(DomainRuleMaterialization.class));
    }

    private String sourceHashFor(
            org.praxisplatform.config.dto.DomainRulePublicationResponse response,
            String targetLayer) {
        return materializationFor(response, targetLayer).sourceHash();
    }

    private org.praxisplatform.config.dto.DomainRuleMaterializationResponse materializationFor(
            org.praxisplatform.config.dto.DomainRulePublicationResponse response,
            String targetLayer) {
        return response.materializations().stream()
                .filter(materialization -> targetLayer.equals(materialization.targetLayer()))
                .findFirst()
                .orElseThrow();
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
