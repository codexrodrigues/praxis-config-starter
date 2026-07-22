package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.service.AiProviderManagementService;

@ExtendWith(MockitoExtension.class)
class AgenticAuthoringComponentOperationSelectionServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private AiProviderManagementService provider;

    @Test
    void acceptsOneOrTwoDeclaredOperationsInSemanticOrder() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("column.renderer.set"), selection("column.renderer.set", "column.visibility.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").operationIds())
                .containsExactly("column.renderer.set");
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").operationIds())
                .containsExactly("column.renderer.set", "column.visibility.set");
    }

    @Test
    void rejectsUndeclaredOrOversizedSelectionsBeforeAnyPlanCanCompile() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(selection("unknown.operation"), selection("column.renderer.set", "column.visibility.set", "a", "b", "c", "d", "e"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").selected()).isFalse();
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").selected()).isFalse();
        verify(provider, org.mockito.Mockito.times(2)).generateJson(anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void doesNotCarryAPreviousProviderFailureIntoTheNextSelection() throws Exception {
        when(provider.generateJson(anyString(), any(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("transient provider failure"))
                .thenReturn(selection("column.renderer.set"));
        var service = new AgenticAuthoringComponentOperationSelectionService(provider, objectMapper, 9);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.select(request(), "praxis-table", objectMapper.createObjectNode(), manifest(), "t", "u", "e").operationIds())
                .containsExactly("column.renderer.set");
    }

    private AgenticAuthoringPlanRequest request() { return new AgenticAuthoringPlanRequest("compose", "openai", "gpt", "key"); }
    private JsonNode manifest() throws Exception { return objectMapper.readTree("""
        {"operations":[{"operationId":"column.renderer.set","title":"Renderer"},{"operationId":"column.visibility.set","title":"Visibility"}]}
        """); }
    private JsonNode selection(String... ids) {
        var root = objectMapper.createObjectNode(); root.put("schemaVersion", AgenticAuthoringComponentOperationSelectionService.SCHEMA_VERSION);
        root.put("componentId", "praxis-table"); root.putArray("goals"); root.put("requiresClarification", false); root.put("clarificationReason", "");
        var selected = root.putArray("selectedOperationIds"); for (String id : ids) selected.add(id); return root;
    }
}
