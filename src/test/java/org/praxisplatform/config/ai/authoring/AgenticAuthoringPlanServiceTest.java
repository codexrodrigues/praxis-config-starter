package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringPlanServiceTest {

    @TempDir
    private Path tempDir;

    @Mock
    private AiProviderManagementService providerManagementService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateMinimalFormPlanReturnsValidResultForAllowedFields() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ArgumentCaptor<AiCallConfig> configCaptor = ArgumentCaptor.forClass(AiCallConfig.class);
        when(providerManagementService.generateJson(
                any(),
                any(AiJsonSchema.class),
                configCaptor.capture(),
                eq("tenant"),
                eq("user"),
                eq("local"))).thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(
                        new AgenticAuthoringPlanRequest("Crie um formulario", "openai", "gpt-5.4-mini", "test-key"),
                        "tenant",
                        "user",
                        "local");

        assertThat(result.valid()).isTrue();
        assertThat(result.failureCodes()).isEmpty();
        assertThat(result.minimalFormPlan()).isSameAs(plan);
        assertThat(configCaptor.getValue().getProvider()).isEqualTo("openai");
        assertThat(configCaptor.getValue().getModel()).isEqualTo("gpt-5.4-mini");
        assertThat(configCaptor.getValue().getApiKey()).isEqualTo("test-key");
    }

    @Test
    void generateMinimalFormPlanFlagsBlockedFields() throws Exception {
        Files.writeString(tempDir.resolve("minimal-form-plan.v1.schema.json"), "{\"type\":\"object\"}");
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();
        properties.setContractsDir(tempDir);
        ObjectNode plan = minimalPlan();
        ObjectNode blocked = objectMapper.createObjectNode();
        blocked.put("name", "prioridadeId");
        blocked.put("label", "Prioridade");
        blocked.put("controlType", "select");
        blocked.put("required", false);
        ((ArrayNode) plan.path("fields")).add(blocked);
        when(providerManagementService.generateJson(any(), any(AiJsonSchema.class), any(), any(), any(), any()))
                .thenReturn(plan);

        AgenticAuthoringPlanResult result = service(properties)
                .generateMinimalFormPlan(new AgenticAuthoringPlanRequest("Crie um formulario", null, null, null), null, null, null);

        assertThat(result.valid()).isFalse();
        assertThat(result.failureCodes()).contains("field not allowed: prioridadeId", "blocked field present: prioridadeId");
    }

    @Test
    void generateMinimalFormPlanRequiresConfiguredContractsDir() {
        AgenticAuthoringArtifactProperties properties = new AgenticAuthoringArtifactProperties();

        assertThatThrownBy(() -> service(properties)
                .generateMinimalFormPlan(new AgenticAuthoringPlanRequest("Crie um formulario", null, null, null), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contracts-dir");
    }

    private AgenticAuthoringPlanService service(AgenticAuthoringArtifactProperties properties) {
        return new AgenticAuthoringPlanService(providerManagementService, properties);
    }

    private ObjectNode minimalPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0.0");
        plan.put("profileId", "create-minimal-form");
        plan.put("targetApp", "praxis-helpdesk-ui");
        plan.put("targetComponentId", "praxis-dynamic-page-builder");
        plan.put("apiUseCaseResolutionRef", "proofs/helpdesk-create-ticket-discovery.md#api-use-case");
        plan.put("fieldSelectionPlanRef", "proofs/helpdesk-create-ticket-discovery.md#field-selection");
        plan.put("submitActionRef", "POST /api/helpdesk/chamados");
        ArrayNode fields = plan.putArray("fields");
        ObjectNode title = fields.addObject();
        title.put("name", "titulo");
        title.put("label", "Titulo");
        title.put("controlType", "text");
        title.put("required", true);
        ObjectNode description = fields.addObject();
        description.put("name", "descricao");
        description.put("label", "Descricao");
        description.put("controlType", "textarea");
        description.put("required", false);
        ObjectNode clarification = plan.putObject("clarificationNeed");
        clarification.put("needed", false);
        clarification.put("code", "none");
        plan.putArray("sourceRefs").add("proofs/helpdesk-create-ticket-discovery.md");
        return plan;
    }
}
