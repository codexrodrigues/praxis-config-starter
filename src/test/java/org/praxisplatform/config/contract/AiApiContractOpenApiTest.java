package org.praxisplatform.config.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("smoke")
class AiApiContractOpenApiTest {

    private static final String CONTRACT_FILE = "praxis-ai-api-contract-v1.1.openapi.yaml";

    @Test
    @SuppressWarnings("unchecked")
    void shouldLoadAndValidateAiOpenApiContract() throws IOException {
        Path contractPath = resolveContractPath();
        assertThat(Files.exists(contractPath))
                .as("OpenAPI contract file must exist")
                .isTrue();

        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(contractPath)) {
            document = new Yaml().load(in);
        }

        assertThat(document).isNotNull();
        assertThat(document.get("openapi")).isEqualTo("3.0.3");

        Map<String, Object> info = (Map<String, Object>) document.get("info");
        assertThat(info).isNotNull();
        assertThat(info.get("version")).isEqualTo("v1.1");

        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        assertThat(paths).isNotNull();
        assertThat(paths).containsKeys(
                "/api/praxis/config/ai/patch",
                "/api/praxis/config/ai/patch/stream/start",
                "/api/praxis/config/ai/patch/stream/{streamId}",
                "/api/praxis/config/ai/patch/stream/{streamId}/probe",
                "/api/praxis/config/ai/patch/stream/{streamId}/cancel",
                "/api/praxis/config/ai/authoring/component-capabilities",
                "/api/praxis/config/ai/authoring/resource-candidates",
                "/api/praxis/config/ai/authoring/intent-resolution",
                "/api/praxis/config/ai/authoring/page-preview",
                "/api/praxis/config/ai/authoring/page-apply");

        Map<String, Object> components = (Map<String, Object>) document.get("components");
        assertThat(components).isNotNull();

        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).isNotNull();
        assertThat(schemas).containsKeys(
                "AiOrchestratorRequest",
                "AiOrchestratorResponse",
                "AiPatchStreamStartResponse",
                "AiPatchStreamCancelResponse",
                "AiTurnEventEnvelope",
                "AgenticAuthoringIntentResolutionRequest",
                "AgenticAuthoringIntentResolutionResult",
                "AgenticAuthoringPlanRequest",
                "AgenticAuthoringAttachmentSummary",
                "AgenticAuthoringPendingClarification",
                "AgenticAuthoringConversationMessage",
                "AgenticAuthoringQuickReply",
                "AgenticAuthoringComponentCapabilitiesResult",
                "AgenticAuthoringComponentCapabilityCatalog",
                "AgenticAuthoringComponentCapability",
                "AgenticAuthoringComponentFieldAlias",
                "AgenticAuthoringComponentCapabilityExample",
                "AgenticAuthoringResourceCandidatesRequest",
                "AgenticAuthoringResourceCandidatesResult",
                "AgenticAuthoringCandidate",
                "AgenticAuthoringPreviewResult",
                "AgenticAuthoringApplyRequest");

        Map<String, Object> conversationContext = (Map<String, Object>) schemas.get("AgenticAuthoringConversationContext");
        assertThat(conversationContext).isNotNull();
        Map<String, Object> conversationContextProperties = (Map<String, Object>) conversationContext.get("properties");
        assertThat(conversationContextProperties).containsKeys(
                "sessionId",
                "clientTurnId",
                "conversationMessages",
                "pendingClarification",
                "attachmentSummaries");

        Map<String, Object> conversationMessages =
                (Map<String, Object>) conversationContextProperties.get("conversationMessages");
        assertThat((Map<String, Object>) conversationMessages.get("items"))
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringConversationMessage");
        Map<String, Object> pendingClarificationRef =
                (Map<String, Object>) conversationContextProperties.get("pendingClarification");
        assertThat(pendingClarificationRef)
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringPendingClarification");
        Map<String, Object> attachmentSummaries =
                (Map<String, Object>) conversationContextProperties.get("attachmentSummaries");
        assertThat((Map<String, Object>) attachmentSummaries.get("items"))
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringAttachmentSummary");

        Map<String, Object> pendingClarification =
                (Map<String, Object>) schemas.get("AgenticAuthoringPendingClarification");
        Map<String, Object> pendingClarificationProperties =
                (Map<String, Object>) pendingClarification.get("properties");
        assertThat(pendingClarificationProperties).containsKeys(
                "sourcePrompt",
                "questions",
                "assistantMessage",
                "clientTurnId",
                "diagnostics");

        Map<String, Object> intentResolutionRequest =
                (Map<String, Object>) schemas.get("AgenticAuthoringIntentResolutionRequest");
        assertThat((List<Object>) intentResolutionRequest.get("allOf"))
                .anySatisfy(entry -> assertThat((Map<String, Object>) entry)
                        .containsEntry("$ref", "#/components/schemas/AgenticAuthoringConversationContext"));
        Map<String, Object> planRequest = (Map<String, Object>) schemas.get("AgenticAuthoringPlanRequest");
        assertThat((List<Object>) planRequest.get("allOf"))
                .anySatisfy(entry -> assertThat((Map<String, Object>) entry)
                        .containsEntry("$ref", "#/components/schemas/AgenticAuthoringConversationContext"));

        Map<String, Object> intentResolution =
                (Map<String, Object>) schemas.get("AgenticAuthoringIntentResolutionResult");
        Map<String, Object> intentResolutionProperties =
                (Map<String, Object>) intentResolution.get("properties");
        assertThat(intentResolutionProperties).containsKeys(
                "effectivePrompt",
                "assistantMessage",
                "quickReplies",
                "pendingClarification",
                "clarificationQuestions");
        Map<String, Object> quickReplies = (Map<String, Object>) intentResolutionProperties.get("quickReplies");
        assertThat((Map<String, Object>) quickReplies.get("items"))
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringQuickReply");
        Map<String, Object> nextPendingClarification =
                (Map<String, Object>) intentResolutionProperties.get("pendingClarification");
        assertThat(nextPendingClarification)
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringPendingClarification");

        Map<String, Object> parameters = (Map<String, Object>) components.get("parameters");
        assertThat(parameters).isNotNull();
        assertThat(parameters).containsKeys(
                "ContractVersionHeader",
                "ContractSchemaHashHeader",
                "LastEventIdHeader",
                "AccessTokenQuery");
    }

    private Path resolveContractPath() {
        Path cwd = Paths.get("").toAbsolutePath();
        List<Path> candidates = List.of(
                cwd.resolve("docs/ai/contracts").resolve(CONTRACT_FILE).normalize(),
                cwd.resolve("../docs/ai/contracts").resolve(CONTRACT_FILE).normalize(),
                cwd.resolve("../../docs/ai/contracts").resolve(CONTRACT_FILE).normalize());
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return candidates.get(0);
    }
}
