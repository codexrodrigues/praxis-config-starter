package org.praxisplatform.config.ai.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentCaptor;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;
import org.praxisplatform.config.service.AiAssistantObservationService;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiStreamAccessTokenService;
import org.praxisplatform.config.service.AiThreadService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.AiTurnService;
import org.praxisplatform.config.service.SchemaFetchResult;
import org.praxisplatform.config.service.SchemaRetrievalService;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class AgenticAuthoringRuntimeFormValueGroundingAndPreflightTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blocksDisallowedIngressAndZeroRetentionStorageBeforeResolveThreadBuildTitleAndObservation() {
        AgenticAuthoringTurnEngine turnEngine = mock(AgenticAuthoringTurnEngine.class);
        AiThreadService threadService = mock(AiThreadService.class);
        AiTurnService turnService = mock(AiTurnService.class);
        AiTurnEventService turnEventService = mock(AiTurnEventService.class);
        AiStreamAccessTokenService tokenService = mock(AiStreamAccessTokenService.class);
        AiAssistantObservationService observationService = mock(AiAssistantObservationService.class);

        AgenticAuthoringTurnStreamService streamService = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                tokenService);
        ReflectionTestUtils.setField(streamService, "assistantObservationService", observationService);

        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-1", "dev", true);

        ObjectNode blockedIngressHints = objectMapper.createObjectNode();
        blockedIngressHints.putObject("ingressPolicy").put("processingAllowed", false);
        AgenticAuthoringTurnStreamRequest blockedIngressRequest = new AgenticAuthoringTurnStreamRequest(
                "Preencha o formulário com CPF 123.456.789-01",
                "ergon",
                "employees-form",
                "/payroll/employees",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                null,
                "turn-blocked-ingress-1",
                List.of(),
                null,
                List.of(),
                blockedIngressHints,
                null,
                null);

        ResponseStatusException ingressEx = assertThrows(
                ResponseStatusException.class,
                () -> streamService.start(blockedIngressRequest, "http://localhost:8080", principal));
        assertEquals(HttpStatus.FORBIDDEN, ingressEx.getStatusCode());
        assertEquals(
                AgenticAuthoringIngressRetentionPreflight.INGRESS_BLOCKED_REASON,
                ingressEx.getReason());
        verifyNoInteractions(threadService, turnService, turnEventService, observationService);

        ObjectNode zeroRetentionStrictHints = objectMapper.createObjectNode();
        ObjectNode rva = zeroRetentionStrictHints.putObject("runtimeValueAssistance");
        rva.put("retentionPolicy", "zero");
        rva.put("allowDurableThreadStorage", false);
        AgenticAuthoringTurnStreamRequest zeroRetentionStrictRequest = new AgenticAuthoringTurnStreamRequest(
                "Preencha os campos sensíveis sem retenção",
                "ergon",
                "employees-form",
                "/payroll/employees",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                null,
                "turn-zero-retention-strict-1",
                List.of(),
                null,
                List.of(),
                zeroRetentionStrictHints,
                null,
                null);

        ResponseStatusException retentionEx = assertThrows(
                ResponseStatusException.class,
                () -> streamService.start(zeroRetentionStrictRequest, "http://localhost:8080", principal));
        assertEquals(HttpStatus.FORBIDDEN, retentionEx.getStatusCode());
        assertEquals(
                AgenticAuthoringIngressRetentionPreflight.ZERO_RETENTION_STORAGE_BLOCKED_REASON,
                retentionEx.getReason());
        verifyNoInteractions(threadService, turnService, turnEventService, observationService);
        streamService.shutdown();
    }

    @Test
    void sanitizesThreadTitleSeedAndSkipsObservationCaptureWhenZeroRetentionIsPermittedForTransientTurn() {
        AgenticAuthoringTurnEngine turnEngine = mock(AgenticAuthoringTurnEngine.class);
        AiThreadService threadService = mock(AiThreadService.class);
        AiTurnService turnService = mock(AiTurnService.class);
        AiTurnEventService turnEventService = mock(AiTurnEventService.class);
        AiStreamAccessTokenService tokenService = mock(AiStreamAccessTokenService.class);
        AiAssistantObservationService observationService = mock(AiAssistantObservationService.class);

        AgenticAuthoringTurnStreamService streamService = new AgenticAuthoringTurnStreamService(
                turnEngine,
                threadService,
                turnService,
                turnEventService,
                tokenService);
        ReflectionTestUtils.setField(streamService, "assistantObservationService", observationService);
        ReflectionTestUtils.setField(streamService, "maxActiveGlobal", 10);
        ReflectionTestUtils.setField(streamService, "maxActivePerTenant", 10);
        ReflectionTestUtils.setField(streamService, "maxActivePerUser", 10);

        UUID threadId = UUID.randomUUID();
        AiThread resolvedThread = new AiThread();
        resolvedThread.setThreadId(threadId);
        when(threadService.resolveThread(any(), any(), any(), any(), any())).thenReturn(resolvedThread);
        when(turnEventService.findStartMetadata(any(), any())).thenReturn(Optional.empty());
        when(turnEventService.findLatestSemanticDecision(any(), any())).thenReturn(Optional.empty());
        when(turnEventService.appendStartEventIfAbsent(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new AiTurnEventService.StreamStartAppendResult(
                        AiTurnEventEnvelope.builder()
                                .streamId(inv.getArgument(1))
                                .threadId(inv.getArgument(2))
                                .turnId(inv.getArgument(3))
                                .seq(1L)
                                .type("status")
                                .timestamp(Instant.now())
                                .payload(objectMapper.valueToTree(inv.getArgument(4)))
                                .build(),
                        true));
        when(turnEngine.execute(any(), any(), any(), any()))
                .thenReturn(AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome.noop(null));

        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-1", "dev", true);
        ObjectNode hints = objectMapper.createObjectNode();
        hints.putObject("runtimeValueAssistance").put("retentionPolicy", "zero");

        String sensitivePrompt = "Preencha com CPF 123.456.789-00 e salário confidencial";
        AgenticAuthoringTurnStreamRequest request = new AgenticAuthoringTurnStreamRequest(
                sensitivePrompt,
                "ergon",
                "employees-form",
                "/payroll/employees",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                null,
                "turn-zero-retention-transient-1",
                List.of(),
                null,
                List.of(),
                hints,
                null,
                null);

        streamService.start(request, "http://localhost:8080", principal);

        ArgumentCaptor<AiOrchestratorRequest> orchestratorReqCaptor =
                ArgumentCaptor.forClass(AiOrchestratorRequest.class);
        ArgumentCaptor<String> titleSeedCaptor = ArgumentCaptor.forClass(String.class);
        verify(threadService).resolveThread(
                orchestratorReqCaptor.capture(),
                eq("tenant-a"),
                eq("user-1"),
                eq("dev"),
                titleSeedCaptor.capture());

        assertEquals(
                AgenticAuthoringIngressRetentionPreflight.NEUTRAL_ZERO_RETENTION_TITLE,
                titleSeedCaptor.getValue());
        assertEquals(
                AgenticAuthoringIngressRetentionPreflight.NEUTRAL_ZERO_RETENTION_TITLE,
                orchestratorReqCaptor.getValue().getUserPrompt());
        org.junit.jupiter.api.Assertions.assertNull(orchestratorReqCaptor.getValue().getCurrentState());
        org.junit.jupiter.api.Assertions.assertNull(orchestratorReqCaptor.getValue().getContextHints());
        verify(observationService, never()).captureStream(any(), any(), any(), any(), any(), any(), any());
        streamService.shutdown();
    }

    @Test
    void groundsOnPostCreationSchemaRejectsFilterSchemaAndEnforcesReadOnlyFieldAccessAiPolicyReactiveAndLookupRules() {
        SchemaRetrievalService schemaRetrievalService = mock(SchemaRetrievalService.class);
        ObjectNode postCreationSchema = buildEmployeesPostCreationSchema();

        when(schemaRetrievalService.fetchSchemaResult(
                        any(AiSchemaContext.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    AiSchemaContext ctx = inv.getArgument(0);
                    if ("/payroll/employees".equals(ctx.getPath())
                            && "post".equalsIgnoreCase(ctx.getOperation())
                            && "request".equalsIgnoreCase(ctx.getSchemaType())) {
                        return SchemaFetchResult.success(
                                postCreationSchema,
                                "http://localhost:8080/schemas/filtered?path=/payroll/employees&operation=post&schemaType=request");
                    }
                    return SchemaFetchResult.failure(
                            SchemaFetchResult.Status.NOT_FOUND,
                            404,
                            "http://localhost:8080/schemas/filtered",
                            "NOT_FOUND",
                            "Schema not found");
                });

        AgenticAuthoringRuntimeFormValueGroundingService groundingService =
                new AgenticAuthoringRuntimeFormValueGroundingService(objectMapper, schemaRetrievalService);
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-1", "dev", true);

        // 1. Rejects /filter path for creation grounding
        ObjectNode filterHints = objectMapper.createObjectNode();
        ObjectNode filterRva = filterHints.putObject("runtimeValueAssistance");
        filterRva.putObject("targetIdentity")
                .put("componentId", "employees-form")
                .put("instanceId", "inst-1")
                .put("resourcePath", "/payroll/employees/filter")
                .put("mode", "create")
                .put("valueRevision", 1);
        AgenticAuthoringTurnStreamRequest filterRequest = new AgenticAuthoringTurnStreamRequest(
                "Preencha o formulário",
                "ergon",
                "employees-form",
                "/payroll/employees",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                null,
                "turn-filter-1",
                List.of(),
                null,
                List.of(),
                filterHints,
                null,
                null);

        AgenticAuthoringRuntimeFormValueGroundingService.GroundingResult filterResult =
                groundingService.groundProposal(filterRequest, null, principal, "http://localhost:8080");
        assertFalse(filterResult.valid());
        assertEquals("filter-schema-forbidden-for-creation-grounding", filterResult.blockReason());
        verifyNoInteractions(schemaRetrievalService);

        // 2. Grounds on POST /payroll/employees and validates each field and lookup candidate
        ObjectNode validHints = objectMapper.createObjectNode();
        ObjectNode rva = validHints.putObject("runtimeValueAssistance");
        rva.putObject("targetIdentity")
                .put("componentId", "employees-form")
                .put("instanceId", "inst-1")
                .put("resourcePath", "/payroll/employees")
                .put("mode", "create")
                .put("valueRevision", 2);
        ArrayNode deptCandidates = rva.putObject("authorizedOptionCandidates").putArray("departmentId");
        deptCandidates.addObject()
                .put("id", 10)
                .put("label", "Engenharia")
                .put("status", "ACTIVE");
        deptCandidates.addObject()
                .put("id", 20)
                .put("label", "Departamento Inativo")
                .put("status", "INACTIVE");

        ArrayNode candidates = objectMapper.createArrayNode();
        candidates.addObject()
                .put("fieldPath", "fullName")
                .put("proposedValue", "Mariana Costa")
                .put("provenance", "synthetic-example")
                .put("syntheticData", true);
        candidates.addObject()
                .put("fieldPath", "departmentId")
                .put("proposedValue", 10)
                .put("provenance", "authorized-option-source")
                .put("syntheticData", false);
        candidates.addObject()
                .put("fieldPath", "id")
                .put("proposedValue", 999);
        candidates.addObject()
                .put("fieldPath", "salary")
                .put("proposedValue", 12000);
        candidates.addObject()
                .put("fieldPath", "secretNotes")
                .put("proposedValue", "Nota confidencial");
        candidates.addObject()
                .put("fieldPath", "netSalary")
                .put("proposedValue", 9500);
        candidates.addObject()
                .put("fieldPath", "cpf")
                .put("proposedValue", "***.456.789-**");
        candidates.addObject()
                .put("fieldPath", "costCenterId")
                .put("proposedValue", 999);

        AgenticAuthoringTurnStreamRequest postRequest = new AgenticAuthoringTurnStreamRequest(
                "Preencha com exemplo válido",
                "ergon",
                "employees-form",
                "/payroll/employees",
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                null,
                null,
                "turn-post-1",
                List.of(),
                null,
                List.of(),
                validHints,
                null,
                null);

        AgenticAuthoringRuntimeFormValueGroundingService.GroundingResult result =
                groundingService.groundProposal(postRequest, candidates, principal, "http://localhost:8080");
        assertTrue(result.valid());
        JsonNode proposal = result.proposal();
        assertNotNull(proposal);
        assertEquals("praxis-runtime-form-value-proposal.v1", proposal.path("schemaVersion").asText());
        assertEquals("post", proposal.path("grounding").path("operation").asText());
        assertEquals("/payroll/employees", proposal.path("grounding").path("schemaPath").asText());

        JsonNode accepted = proposal.path("assignments");
        assertEquals(2, accepted.size());
        assertEquals("fullName", accepted.get(0).path("fieldPath").asText());
        assertEquals("departmentId", accepted.get(1).path("fieldPath").asText());
        assertEquals("Engenharia", accepted.get(1).path("optionLabel").asText());

        Map<String, String> rejectedReasons = new LinkedHashMap<>();
        for (JsonNode rejected : proposal.path("rejectedFields")) {
            rejectedReasons.put(
                    rejected.path("fieldPath").asText(),
                    rejected.path("reasonCode").asText());
        }
        assertEquals("read-only", rejectedReasons.get("id"));
        assertEquals("restricted-by-field-access", rejectedReasons.get("salary"));
        assertEquals("restricted-by-ai-policy", rejectedReasons.get("secretNotes"));
        assertEquals("reactive-determination-target", rejectedReasons.get("netSalary"));
        assertEquals("masked-or-redacted-value", rejectedReasons.get("cpf"));
        assertEquals("unverified-option-source-id", rejectedReasons.get("costCenterId"));

        // Verify blocked status in LookupSelectionPolicy is also rejected
        ArrayNode blockedLookupCandidates = objectMapper.createArrayNode();
        blockedLookupCandidates.addObject()
                .put("fieldPath", "departmentId")
                .put("proposedValue", 20);
        AgenticAuthoringRuntimeFormValueGroundingService.GroundingResult blockedLookupResult =
                groundingService.groundProposal(
                        postRequest, blockedLookupCandidates, principal, "http://localhost:8080");
        assertFalse(blockedLookupResult.valid());
        assertEquals(
                "option-source-selection-policy-blocked",
                blockedLookupResult.proposal().path("rejectedFields").get(0).path("reasonCode").asText());
    }

    @Test
    void executesStandaloneFormRuntimeValueTurnWithoutStructuralPreviewAndSynthesizesGroundedProposal() {
        AgenticAuthoringLlmIntentResolverService llmResolver = mock(AgenticAuthoringLlmIntentResolverService.class);
        when(llmResolver.resolve(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AgenticAuthoringLlmIntentResolution(
                        true,
                        "modify",
                        "form",
                        "fill_form_values",
                        "/payroll/employees",
                        null,
                        "new_instruction",
                        "Preparei valores de exemplo para revisão no formulário.",
                        List.of(),
                        List.of(),
                        List.of("llm-compact-targeted-component-intent-used"),
                        null,
                        null,
                        false,
                        "component_authoring")));

        AgenticAuthoringIntentResolverService realIntentResolver = new AgenticAuthoringIntentResolverService(
                objectMapper,
                null,
                llmResolver,
                new AgenticAuthoringComponentCapabilitiesService());

        ObjectNode standaloneHints = objectMapper.createObjectNode();
        ObjectNode rva = standaloneHints.putObject("runtimeValueAssistance");
        rva.put("requested", true);
        rva.putObject("targetIdentity")
                .put("componentId", "praxis-dynamic-form")
                .put("instanceId", "dynamic-form:employees-1")
                .put("resourcePath", "/payroll/employees")
                .put("mode", "create")
                .put("valueRevision", 1);
        rva.putArray("nonOverwritableFields")
                .addObject()
                .put("fieldPath", "cpf")
                .put("reason", "disabled");
        rva.putObject("authorizedOptionCandidates")
                .putArray("departmentId")
                .addObject()
                .put("id", 10)
                .put("label", "Engenharia")
                .put("status", "ACTIVE");

        AgenticAuthoringIntentResolutionRequest intentRequest = new AgenticAuthoringIntentResolutionRequest(
                "Preencha este formulário com exemplos válidos",
                "ergon",
                "praxis-dynamic-form",
                "/payroll/employees",
                objectMapper.createObjectNode().put("resourcePath", "/payroll/employees"),
                "dynamic-form:employees-1",
                null,
                null,
                null,
                "sess-standalone-1",
                "turn-standalone-1",
                List.of(),
                null,
                List.of(),
                standaloneHints,
                null);

        AgenticAuthoringIntentResolutionResult resolvedIntent =
                realIntentResolver.resolve(intentRequest, "tenant-a", "user-1", "dev");
        assertTrue(resolvedIntent.valid());
        assertEquals("modify", resolvedIntent.operationKind());
        assertEquals("form", resolvedIntent.artifactKind());
        assertEquals("fill_form_values", resolvedIntent.changeKind());
        assertNotNull(resolvedIntent.target());
        assertEquals("dynamic-form:employees-1", resolvedIntent.target().widgetKey());
        assertEquals("praxis-dynamic-form", resolvedIntent.target().componentId());

        AgenticAuthoringIntentResolverService mockIntentResolver = mock(AgenticAuthoringIntentResolverService.class);
        when(mockIntentResolver.resolve(any(), any(), any(), any(), any())).thenReturn(resolvedIntent);
        when(mockIntentResolver.resolve(any(), any(), any(), any())).thenReturn(resolvedIntent);

        AgenticAuthoringPreviewService previewService = mock(AgenticAuthoringPreviewService.class);
        SchemaRetrievalService schemaRetrievalService = mock(SchemaRetrievalService.class);
        ObjectNode postCreationSchema = buildEmployeesPostCreationSchema();
        when(schemaRetrievalService.fetchSchemaResult(any(AiSchemaContext.class), any(), any(), any(), any()))
                .thenReturn(SchemaFetchResult.success(
                        postCreationSchema,
                        "http://localhost:8080/schemas/filtered?path=/payroll/employees&operation=post&schemaType=request"));

        AgenticAuthoringTurnEngine engine = new AgenticAuthoringTurnEngine(
                mockIntentResolver,
                previewService,
                objectMapper,
                new AgenticAuthoringCurrentPageAnalyzer(objectMapper),
                null,
                null,
                null,
                schemaRetrievalService,
                new AgenticAuthoringComponentCapabilitiesService(),
                null,
                null);

        AgenticAuthoringTurnStreamRequest streamRequest = new AgenticAuthoringTurnStreamRequest(
                "Preencha este formulário com exemplos válidos",
                "ergon",
                "praxis-dynamic-form",
                "/payroll/employees",
                objectMapper.createObjectNode().put("resourcePath", "/payroll/employees"),
                "dynamic-form:employees-1",
                null,
                null,
                null,
                "sess-standalone-1",
                "turn-standalone-1",
                List.of(),
                null,
                List.of(),
                standaloneHints,
                null,
                null);

        Map<String, Object> capturedEvents = new LinkedHashMap<>();
        AgenticAuthoringTurnEventSink sink = new AgenticAuthoringTurnEventSink() {
            @Override
            public AgenticAuthoringTurnEventAppendResult append(String type, Object payload) {
                capturedEvents.put(type, payload);
                return new AgenticAuthoringTurnEventAppendResult(type, true);
            }

            @Override
            public boolean terminalReached() {
                return capturedEvents.containsKey("result") || capturedEvents.containsKey("error");
            }
        };
        AiPrincipalContext principal = new AiPrincipalContext("tenant-a", "user-1", "dev", true);

        AgenticAuthoringTurnEngine.AgenticAuthoringTurnOutcome outcome =
                engine.execute(streamRequest, principal, sink, "http://localhost:8080");

        verifyNoInteractions(previewService);
        assertEquals(AgenticAuthoringTurnEngine.Completion.COMPLETE, outcome.completion());
        @SuppressWarnings("unchecked")
        Map<String, Object> resultPayload = (Map<String, Object>) capturedEvents.get("result");
        assertNotNull(resultPayload);
        assertEquals(Boolean.TRUE, resultPayload.get("canApply"));
        JsonNode proposal = (JsonNode) resultPayload.get("runtimeValueProposal");
        assertNotNull(proposal);
        assertEquals(2, proposal.path("assignments").size());
        assertEquals("fullName", proposal.path("assignments").get(0).path("fieldPath").asText());
        assertEquals("departmentId", proposal.path("assignments").get(1).path("fieldPath").asText());
        assertEquals("authorized-option-source", proposal.path("assignments").get(1).path("provenance").asText());
        assertEquals("Engenharia", proposal.path("assignments").get(1).path("optionLabel").asText());

        // Also verify blocked runtime value turn (autoSaveEnabled=true) surfaces exact blockMessage and canApply=false
        rva.put("autoSaveEnabled", true);
        Map<String, Object> blockedEvents = new LinkedHashMap<>();
        AgenticAuthoringTurnEventSink blockedSink = new AgenticAuthoringTurnEventSink() {
            @Override
            public AgenticAuthoringTurnEventAppendResult append(String type, Object payload) {
                blockedEvents.put(type, payload);
                return new AgenticAuthoringTurnEventAppendResult(type, true);
            }

            @Override
            public boolean terminalReached() {
                return blockedEvents.containsKey("result") || blockedEvents.containsKey("error");
            }
        };
        engine.execute(streamRequest, principal, blockedSink, "http://localhost:8080");
        verifyNoInteractions(previewService);
        @SuppressWarnings("unchecked")
        Map<String, Object> blockedPayload = (Map<String, Object>) blockedEvents.get("result");
        assertNotNull(blockedPayload);
        assertEquals(Boolean.FALSE, blockedPayload.get("canApply"));
        String blockedMessage = String.valueOf(blockedPayload.get("assistantMessage"));
        assertTrue(blockedMessage.toLowerCase().contains("autosave"));
        assertFalse(blockedMessage.toLowerCase().contains("deseja aplicar"));
    }

    private ObjectNode buildEmployeesPostCreationSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("x-schema-hash", "employees-post-hash-v1");
        ObjectNode xUiRoot = schema.putObject("x-ui");
        ArrayNode determinations = xUiRoot.putArray("reactiveDeterminations");
        ObjectNode det = determinations.addObject();
        det.put("id", "calc-net-salary");
        det.putArray("outputs").addObject().put("fieldPath", "netSalary");

        ObjectNode props = schema.putObject("properties");

        props.putObject("id")
                .put("type", "integer")
                .put("readOnly", true);

        ObjectNode fullName = props.putObject("fullName");
        fullName.put("type", "string");
        fullName.putObject("x-ui").put("label", "Nome completo");

        ObjectNode cpf = props.putObject("cpf");
        cpf.put("type", "string");
        cpf.putObject("x-ui").put("label", "CPF");

        ObjectNode salary = props.putObject("salary");
        salary.put("type", "number");
        salary.putObject("x-ui")
                .put("label", "Salário base")
                .putObject("fieldAccess")
                .put("canWrite", false)
                .put("accessMode", "READ_ONLY");

        ObjectNode secretNotes = props.putObject("secretNotes");
        secretNotes.put("type", "string");
        secretNotes.putObject("x-domain-governance")
                .putObject("aiUsage")
                .put("visibility", "deny");

        ObjectNode netSalary = props.putObject("netSalary");
        netSalary.put("type", "number");
        netSalary.putObject("x-ui").put("label", "Salário líquido");

        ObjectNode departmentId = props.putObject("departmentId");
        departmentId.put("type", "integer");
        ObjectNode deptOptionSource = departmentId.putObject("x-ui")
                .put("label", "Departamento")
                .putObject("optionSource");
        deptOptionSource.put("key", "department");
        deptOptionSource.put("type", "RESOURCE_ENTITY");
        deptOptionSource.put("filterEndpoint", "/departments/option-sources/department/options/filter");
        deptOptionSource.put("byIdsEndpoint", "/departments/option-sources/department/options/by-ids");
        ObjectNode selectionPolicy = deptOptionSource.putObject("entityLookup").putObject("selectionPolicy");
        selectionPolicy.putArray("allowedStatuses").add("ACTIVE");
        selectionPolicy.putArray("blockedStatuses").add("INACTIVE");

        ObjectNode costCenterId = props.putObject("costCenterId");
        costCenterId.put("type", "integer");
        ObjectNode ccOptionSource = costCenterId.putObject("x-ui")
                .put("label", "Centro de Custo")
                .putObject("optionSource");
        ccOptionSource.put("key", "costCenter");
        ccOptionSource.put("type", "RESOURCE_ENTITY");
        ccOptionSource.put("filterEndpoint", "/cost-centers/option-sources/costCenter/options/filter");
        ccOptionSource.put("byIdsEndpoint", "/cost-centers/option-sources/costCenter/options/by-ids");

        return schema;
    }
}
