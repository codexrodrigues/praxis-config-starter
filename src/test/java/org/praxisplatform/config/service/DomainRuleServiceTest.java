package org.praxisplatform.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.praxisplatform.config.dto.DomainRuleMaterializationRequest;
import org.praxisplatform.config.repository.DomainCatalogReleaseRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainRuleDefinitionRepository;
import org.praxisplatform.config.repository.DomainRuleMaterializationRepository;

@Tag("unit")
class DomainRuleServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
