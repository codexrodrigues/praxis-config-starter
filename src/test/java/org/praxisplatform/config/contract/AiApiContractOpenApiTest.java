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
                "/api/praxis/config/ai/authoring/manifests/{componentId}",
                "/api/praxis/config/ai/authoring/manifests/{componentId}/editable-targets",
                "/api/praxis/config/ai/authoring/manifests/{componentId}/operations",
                "/api/praxis/config/ai/authoring/manifests/{componentId}/resolve-target",
                "/api/praxis/config/ai/authoring/manifests/{componentId}/validate-plan",
                "/api/praxis/config/ai/authoring/manifests/{componentId}/compile-patch",
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
                "AgenticAuthoringDryRunErrorResponse",
                "AgenticAuthoringManifestEditPlanRequest",
                "AgenticAuthoringResolveTargetRequest",
                "AgenticAuthoringResolvedTarget",
                "AgenticAuthoringManifestValidationResult",
                "AgenticAuthoringManifestCompileResult",
                "AgenticAuthoringCompiledComponentPatch",
                "AgenticAuthoringCompiledPatchOperation",
                "AgenticAuthoringResourceCandidatesRequest",
                "AgenticAuthoringResourceCandidatesResult",
                "AgenticAuthoringCandidate",
                "AgenticAuthoringPreviewResult",
                "AgenticAuthoringApplyRequest",
                "AiDomainCatalogContextHint",
                "AiDomainCatalogRelationshipHint");

        Map<String, Object> domainCatalogHint =
                (Map<String, Object>) schemas.get("AiDomainCatalogContextHint");
        Map<String, Object> domainCatalogHintProperties =
                (Map<String, Object>) domainCatalogHint.get("properties");
        assertThat(domainCatalogHintProperties).containsKeys(
                "schemaVersion",
                "serviceKey",
                "resourceKey",
                "releaseId",
                "releaseKey",
                "type",
                "itemTypes",
                "intent",
                "query",
                "contextKey",
                "nodeType",
                "recommendedOperation",
                "limit",
                "relationships");
        assertThat((List<String>) ((Map<String, Object>) domainCatalogHintProperties.get("recommendedOperation")).get("enum"))
                .containsExactly(
                        "rule.visibility.add",
                        "rule.validation.add",
                        "rule.visualBlockGuidance.add",
                        "rule.remove");
        assertThat((Map<String, Object>) domainCatalogHintProperties.get("schemaVersion"))
                .containsEntry("default", "praxis.ai.context-hints.domain-catalog/v0.1");
        assertThat((List<String>) ((Map<String, Object>) domainCatalogHintProperties.get("type")).get("enum"))
                .containsExactly("context", "node", "edge", "binding", "evidence", "governance", "vocabulary", "relationship");
        assertThat((List<String>) ((Map<String, Object>) domainCatalogHintProperties.get("intent")).get("enum"))
                .containsExactly("authoring", "explain", "validate", "ai-access-control");
        assertThat((List<String>) ((Map<String, Object>) domainCatalogHintProperties.get("recommendedOperation")).get("enum"))
                .containsExactly("rule.visibility.add", "rule.validation.add", "rule.visualBlockGuidance.add", "rule.remove");
        assertThat((Map<String, Object>) domainCatalogHintProperties.get("relationships"))
                .containsEntry("$ref", "#/components/schemas/AiDomainCatalogRelationshipHint");

        Map<String, Object> relationshipHint =
                (Map<String, Object>) schemas.get("AiDomainCatalogRelationshipHint");
        Map<String, Object> relationshipHintProperties =
                (Map<String, Object>) relationshipHint.get("properties");
        assertThat(relationshipHintProperties).containsKeys(
                "enabled",
                "federated",
                "serviceKey",
                "sourceNodeKey",
                "targetNodeKey",
                "edgeType",
                "query",
                "limit");
        assertThat((Map<String, Object>) relationshipHintProperties.get("limit"))
                .containsEntry("default", 8);

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

        Map<String, Object> resolveTargetRequest =
                (Map<String, Object>) schemas.get("AgenticAuthoringResolveTargetRequest");
        Map<String, Object> resolveTargetProperties =
                (Map<String, Object>) resolveTargetRequest.get("properties");
        assertThat(resolveTargetProperties).containsKeys("config", "operationId", "target", "input");
        Map<String, Object> resolvedTarget =
                (Map<String, Object>) schemas.get("AgenticAuthoringResolvedTarget");
        Map<String, Object> resolvedTargetProperties =
                (Map<String, Object>) resolvedTarget.get("properties");
        assertThat(resolvedTargetProperties).containsKeys(
                "status",
                "componentId",
                "operationId",
                "kind",
                "resolver",
                "path",
                "value",
                "candidates",
                "failures");
        Map<String, Object> compileResult =
                (Map<String, Object>) schemas.get("AgenticAuthoringManifestCompileResult");
        Map<String, Object> compileResultProperties =
                (Map<String, Object>) compileResult.get("properties");
        assertThat((Map<String, Object>) compileResultProperties.get("patch"))
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringCompiledComponentPatch");
        Map<String, Object> compiledPatch =
                (Map<String, Object>) schemas.get("AgenticAuthoringCompiledComponentPatch");
        Map<String, Object> compiledPatchProperties =
                (Map<String, Object>) compiledPatch.get("properties");
        assertThat(compiledPatchProperties).containsKeys(
                "componentId",
                "manifestVersion",
                "patchKind",
                "compiledOperations",
                "operations",
                "patchOperations",
                "proposedConfig");
        assertThat((Map<String, Object>) compiledPatchProperties.get("compiledOperations"))
                .extracting("items")
                .isEqualTo(Map.of("$ref", "#/components/schemas/AgenticAuthoringCompiledPatchOperation"));
        Map<String, Object> compiledOperation =
                (Map<String, Object>) schemas.get("AgenticAuthoringCompiledPatchOperation");
        Map<String, Object> compiledOperationProperties =
                (Map<String, Object>) compiledOperation.get("properties");
        assertThat(compiledOperationProperties).containsKeys(
                "op",
                "componentId",
                "operationId",
                "path",
                "resolvedPath",
                "key",
                "keyValue",
                "value",
                "removedValue",
                "removedIndex",
                "fromIndex",
                "toIndex",
                "appendedIndex",
                "handler",
                "submissionImpact",
                "compilerBoundary");
        assertThat((List<String>) ((Map<String, Object>) compiledOperationProperties.get("op")).get("enum"))
                .containsExactly(
                        "merge-object-by-key",
                        "merge-object",
                        "set-value",
                        "remove-by-key",
                        "append",
                        "append-unique",
                        "reorder-by-key",
                        "domain-patch");

        assertManifestEndpointUsesAuthoringError(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}");
        assertManifestEndpointUsesAuthoringError(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/editable-targets");
        assertManifestEndpointUsesAuthoringError(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/operations");
        assertManifestEndpointUsesAuthoringError(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/resolve-target");
        assertManifestEndpointUsesAuthoringError(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/validate-plan");
        assertManifestEndpointUsesAuthoringError(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/compile-patch");

        assertManifestEndpointSchemas(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}",
                "get",
                null,
                null);
        assertManifestEndpointSchemas(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/editable-targets",
                "get",
                null,
                null);
        assertManifestEndpointSchemas(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/operations",
                "get",
                null,
                null);
        assertManifestEndpointSchemas(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/resolve-target",
                "post",
                "#/components/schemas/AgenticAuthoringResolveTargetRequest",
                "#/components/schemas/AgenticAuthoringResolvedTarget");
        assertManifestEndpointSchemas(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/validate-plan",
                "post",
                "#/components/schemas/AgenticAuthoringManifestEditPlanRequest",
                "#/components/schemas/AgenticAuthoringManifestValidationResult");
        assertManifestEndpointSchemas(paths,
                "/api/praxis/config/ai/authoring/manifests/{componentId}/compile-patch",
                "post",
                "#/components/schemas/AgenticAuthoringManifestEditPlanRequest",
                "#/components/schemas/AgenticAuthoringManifestCompileResult");

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

        Map<String, Object> resourceCandidates =
                (Map<String, Object>) schemas.get("AgenticAuthoringResourceCandidatesResult");
        Map<String, Object> resourceCandidatesProperties =
                (Map<String, Object>) resourceCandidates.get("properties");
        assertThat(resourceCandidatesProperties).containsKeys(
                "assistantMessage",
                "quickReplies",
                "candidates",
                "warnings");
        Map<String, Object> resourceQuickReplies =
                (Map<String, Object>) resourceCandidatesProperties.get("quickReplies");
        assertThat((Map<String, Object>) resourceQuickReplies.get("items"))
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringQuickReply");

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

    @SuppressWarnings("unchecked")
    private void assertManifestEndpointUsesAuthoringError(Map<String, Object> paths, String path) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).isNotNull();
        String method = path.endsWith("editable-targets") || path.endsWith("operations") || path.endsWith("{componentId}")
                ? "get"
                : "post";
        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation).isNotNull();
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertThat(responses).containsKey("400");
        Map<String, Object> badRequest = (Map<String, Object>) responses.get("400");
        Map<String, Object> content = (Map<String, Object>) badRequest.get("content");
        Map<String, Object> mediaType = (Map<String, Object>) content.get("application/json");
        Map<String, Object> schema = (Map<String, Object>) mediaType.get("schema");
        assertThat(schema)
                .containsEntry("$ref", "#/components/schemas/AgenticAuthoringDryRunErrorResponse");
    }

    @SuppressWarnings("unchecked")
    private void assertManifestEndpointSchemas(
            Map<String, Object> paths,
            String path,
            String method,
            String requestSchemaRef,
            String successSchemaRef) {
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        assertThat(pathItem).isNotNull();
        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        assertThat(operation).isNotNull();

        if (requestSchemaRef == null) {
            assertThat(operation).doesNotContainKey("requestBody");
        } else {
            Map<String, Object> requestBody = (Map<String, Object>) operation.get("requestBody");
            assertThat(requestBody).isNotNull();
            assertJsonSchemaRef(requestBody, "content", requestSchemaRef);
        }

        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        assertThat(responses).isNotNull();
        Map<String, Object> okResponse = (Map<String, Object>) responses.get("200");
        assertThat(okResponse).isNotNull();
        if (successSchemaRef == null) {
            assertThat(resolveJsonSchema(okResponse, "content")).isNotNull();
        } else {
            assertJsonSchemaRef(okResponse, "content", successSchemaRef);
        }
    }

    @SuppressWarnings("unchecked")
    private void assertJsonSchemaRef(Map<String, Object> node, String contentField, String expectedRef) {
        Map<String, Object> schema = resolveJsonSchema(node, contentField);
        assertThat(schema).containsEntry("$ref", expectedRef);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveJsonSchema(Map<String, Object> node, String contentField) {
        Map<String, Object> content = (Map<String, Object>) node.get(contentField);
        assertThat(content).isNotNull();
        Map<String, Object> mediaType = (Map<String, Object>) content.get("application/json");
        assertThat(mediaType).isNotNull();
        Map<String, Object> schema = (Map<String, Object>) mediaType.get("schema");
        assertThat(schema).isNotNull();
        return schema;
    }
}
