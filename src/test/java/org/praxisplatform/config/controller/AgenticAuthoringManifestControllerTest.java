package org.praxisplatform.config.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunErrorResponse;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringEffectCompilerRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestContractValidator;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestCompileResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestEditPlanRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringManifestValidationResult;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResolveTargetRequest;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringResolvedTarget;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringTargetResolverRegistry;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringValidatorRegistry;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.domain.Scope;
import org.praxisplatform.config.repository.AiRegistryRepository;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AgenticAuthoringManifestControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AgenticAuthoringManifestService manifestService;

    @Mock
    private AiRegistryRepository registryRepository;

    @Test
    void getManifestReturnsManifestFromService() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "manifestVersion": "1.0.0"
                }
                """);
        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);

        ResponseEntity<?> response = controller().getManifest("praxis-table");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(manifest);
    }

    @Test
    void resolveTargetReturnsResolvedTarget() throws Exception {
        AgenticAuthoringResolveTargetRequest request = new AgenticAuthoringResolveTargetRequest(
                objectMapper.readTree("""
                        {
                          "columns": [
                            { "field": "email" }
                          ]
                        }
                        """),
                "column.header.set",
                objectMapper.readTree("\"email\""),
                objectMapper.readTree("{ \"header\": \"Contato\" }"));
        AgenticAuthoringResolvedTarget expected = new AgenticAuthoringResolvedTarget(
                "resolved",
                "praxis-table",
                "column.header.set",
                "column",
                "column-by-field",
                "columns[]/0",
                objectMapper.readTree("{ \"field\": \"email\" }"),
                List.of("columns[]/0"),
                List.of());
        when(manifestService.resolveTarget("praxis-table", request)).thenReturn(expected);

        ResponseEntity<?> response = controller().resolveTarget("praxis-table", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
        verify(manifestService).resolveTarget("praxis-table", request);
    }

    @Test
    void validatePlanReturnsValidationResult() throws Exception {
        AgenticAuthoringManifestEditPlanRequest request = new AgenticAuthoringManifestEditPlanRequest(
                objectMapper.readTree("{ \"columns\": [] }"),
                objectMapper.readTree("{ \"operations\": [] }"));
        AgenticAuthoringManifestValidationResult expected = new AgenticAuthoringManifestValidationResult(
                false,
                List.of("plan.operations is required"),
                List.of(),
                objectMapper.readTree("{ \"componentId\": \"praxis-table\", \"operations\": [] }"));
        when(manifestService.validateEditPlan("praxis-table", request)).thenReturn(expected);

        ResponseEntity<?> response = controller().validatePlan("praxis-table", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void compilePatchReturnsCompiledPatch() throws Exception {
        AgenticAuthoringManifestEditPlanRequest request = new AgenticAuthoringManifestEditPlanRequest(
                objectMapper.readTree("{ \"columns\": [] }"),
                objectMapper.readTree("{ \"operations\": [] }"));
        JsonNode patch = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "patchKind": "component-config-patch",
                  "compiledOperations": [],
                  "operations": [],
                  "patchOperations": [],
                  "proposedConfig": {}
                }
                """);
        AgenticAuthoringManifestCompileResult expected = new AgenticAuthoringManifestCompileResult(
                true,
                List.of(),
                List.of(),
                patch);
        when(manifestService.compilePatch("praxis-table", request)).thenReturn(expected);

        ResponseEntity<?> response = controller().compilePatch("praxis-table", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void getManifestReturnsConfigurationErrorWhenServiceRejectsComponent() {
        when(manifestService.getManifest("missing-component"))
                .thenThrow(new IllegalArgumentException("authoringManifest not found"));

        ResponseEntity<?> response = controller().getManifest("missing-component");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(AgenticAuthoringDryRunErrorResponse.class);
        AgenticAuthoringDryRunErrorResponse body = (AgenticAuthoringDryRunErrorResponse) response.getBody();
        assertThat(body.valid()).isFalse();
        assertThat(body.failureCodes()).containsExactly("DRY_RUN_CONFIGURATION_INVALID");
        assertThat(body.message()).isEqualTo("authoringManifest not found");
    }

    @Test
    void listOperationsDelegatesToService() throws Exception {
        JsonNode operations = objectMapper.readTree("[{ \"operationId\": \"column.header.set\" }]");
        when(manifestService.listOperations("praxis-table")).thenReturn(operations);

        ResponseEntity<?> response = controller().listOperations("praxis-table");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(operations);
        verify(manifestService).listOperations("praxis-table");
    }

    @Test
    void listEditableTargetsDelegatesToService() throws Exception {
        JsonNode targets = objectMapper.readTree("[{ \"kind\": \"column\" }]");
        when(manifestService.listEditableTargets("praxis-table")).thenReturn(targets);

        ResponseEntity<?> response = controller().listEditableTargets("praxis-table");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(targets);
        verify(manifestService).listEditableTargets("praxis-table");
    }

    @Test
    void manifestEndpointsExposeHttpContract() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();
        JsonNode manifest = objectMapper.readTree("""
                {
                  "componentId": "praxis-table",
                  "operations": [
                    { "operationId": "column.header.set" }
                  ],
                  "editableTargets": [
                    { "kind": "column" }
                  ]
                }
                """);
        JsonNode operations = objectMapper.readTree("[{ \"operationId\": \"column.header.set\" }]");
        JsonNode targets = objectMapper.readTree("[{ \"kind\": \"column\" }]");
        AgenticAuthoringResolveTargetRequest resolveRequest = new AgenticAuthoringResolveTargetRequest(
                objectMapper.readTree("{ \"columns\": [{ \"field\": \"email\" }] }"),
                "column.header.set",
                objectMapper.readTree("\"email\""),
                objectMapper.readTree("{ \"header\": \"Contato\" }"));
        AgenticAuthoringResolvedTarget resolvedTarget = new AgenticAuthoringResolvedTarget(
                "resolved",
                "praxis-table",
                "column.header.set",
                "column",
                "column-by-field",
                "columns[]/0",
                objectMapper.readTree("{ \"field\": \"email\" }"),
                List.of("columns[]/0"),
                List.of());
        AgenticAuthoringManifestEditPlanRequest planRequest = new AgenticAuthoringManifestEditPlanRequest(
                objectMapper.readTree("{ \"columns\": [{ \"field\": \"email\" }] }"),
                objectMapper.readTree("""
                        {
                          "operations": [
                            {
                              "operationId": "column.header.set",
                              "target": "email",
                              "input": { "header": "Contato" }
                            }
                          ]
                        }
                        """));
        AgenticAuthoringManifestValidationResult validationResult = new AgenticAuthoringManifestValidationResult(
                true,
                List.of(),
                List.of(),
                planRequest.plan());
        AgenticAuthoringManifestCompileResult compileResult = new AgenticAuthoringManifestCompileResult(
                true,
                List.of(),
                List.of(),
                objectMapper.readTree("""
                        {
                          "compiledOperations": [
                            { "op": "set-value", "path": "/columns/0/header", "value": "Contato" }
                          ],
                          "patchOperations": [
                            { "op": "replace", "path": "/columns/0/header", "value": "Contato" }
                          ],
                          "proposedConfig": {
                            "columns": [
                              { "field": "email", "header": "Contato" }
                            ]
                          }
                        }
                        """));

        when(manifestService.getManifest("praxis-table")).thenReturn(manifest);
        when(manifestService.listEditableTargets("praxis-table")).thenReturn(targets);
        when(manifestService.listOperations("praxis-table")).thenReturn(operations);
        when(manifestService.resolveTarget("praxis-table", resolveRequest)).thenReturn(resolvedTarget);
        when(manifestService.validateEditPlan(eq("praxis-table"), any(AgenticAuthoringManifestEditPlanRequest.class)))
                .thenReturn(validationResult);
        when(manifestService.compilePatch(eq("praxis-table"), any(AgenticAuthoringManifestEditPlanRequest.class)))
                .thenReturn(compileResult);

        mockMvc.perform(get("/api/praxis/config/ai/authoring/manifests/{componentId}", "praxis-table"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.componentId").value("praxis-table"))
                .andExpect(jsonPath("$.operations[0].operationId").value("column.header.set"));

        mockMvc.perform(get("/api/praxis/config/ai/authoring/manifests/{componentId}/editable-targets", "praxis-table"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("column"));

        mockMvc.perform(get("/api/praxis/config/ai/authoring/manifests/{componentId}/operations", "praxis-table"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationId").value("column.header.set"));

        mockMvc.perform(post("/api/praxis/config/ai/authoring/manifests/{componentId}/resolve-target", "praxis-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("resolved"))
                .andExpect(jsonPath("$.path").value("columns[]/0"));

        mockMvc.perform(post("/api/praxis/config/ai/authoring/manifests/{componentId}/validate-plan", "praxis-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(planRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.normalizedPlan.operations[0].operationId").value("column.header.set"));

        mockMvc.perform(post("/api/praxis/config/ai/authoring/manifests/{componentId}/compile-patch", "praxis-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(planRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compiled").value(true))
                .andExpect(jsonPath("$.patch.compiledOperations[0].op").value("set-value"))
                .andExpect(jsonPath("$.patch.compiledOperations[0].path").value("/columns/0/header"))
                .andExpect(jsonPath("$.patch.patchOperations[0].path").value("/columns/0/header"));
    }

    @Test
    void manifestHttpEndpointsReturnConfigurationErrorAsBadRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();
        when(manifestService.getManifest("missing-component"))
                .thenThrow(new IllegalArgumentException("authoringManifest not found"));

        mockMvc.perform(get("/api/praxis/config/ai/authoring/manifests/{componentId}", "missing-component"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.failureCodes[0]").value("DRY_RUN_CONFIGURATION_INVALID"))
                .andExpect(jsonPath("$.message").value("authoringManifest not found"));
    }

    @Test
    void compilePatchHttpFlowUsesRealManifestServiceAndRegistryPayload() throws Exception {
        when(registryRepository.findByRegistryTypeAndRegistryKeyAndComponentTypeAndScopeAndScopeKey(
                eq("component_definition"),
                eq("praxis-table"),
                eq("component-definition"),
                eq(Scope.SYSTEM),
                eq("GLOBAL")))
                .thenReturn(java.util.Optional.of(AiRegistry.builder().payload(realServicePayload()).build()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgenticAuthoringManifestController(realManifestService()))
                .build();

        mockMvc.perform(post("/api/praxis/config/ai/authoring/manifests/{componentId}/compile-patch", "praxis-table")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "config": {
                                    "columns": [
                                      { "field": "email", "header": "Email" }
                                    ]
                                  },
                                  "plan": {
                                    "operationId": "column.header.set",
                                    "target": "email",
                                    "input": { "header": "Contato" }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compiled").value(true))
                .andExpect(jsonPath("$.failures").isArray())
                .andExpect(jsonPath("$.failures").isEmpty())
                .andExpect(jsonPath("$.patch.componentId").value("praxis-table"))
                .andExpect(jsonPath("$.patch.patchKind").value("component-config-patch"))
                .andExpect(jsonPath("$.patch.compiledOperations[0].op").value("merge-object-by-key"))
                .andExpect(jsonPath("$.patch.compiledOperations[0].resolvedPath").value("columns[]/0"))
                .andExpect(jsonPath("$.patch.compiledOperations[0].keyValue").value("email"))
                .andExpect(jsonPath("$.patch.compiledOperations[0].value.header").value("Contato"))
                .andExpect(jsonPath("$.patch.operations[0].effectKind").value("merge-by-key"))
                .andExpect(jsonPath("$.patch.operations[0].resolvedPath").value("columns[]/0"))
                .andExpect(jsonPath("$.patch.operations[0].keyValue").value("email"))
                .andExpect(jsonPath("$.patch.operations[0].value.header").value("Contato"))
                .andExpect(jsonPath("$.patch.patchOperations[0].op").value("replace"))
                .andExpect(jsonPath("$.patch.patchOperations[0].path").value("/columns/0/header"))
                .andExpect(jsonPath("$.patch.patchOperations[0].value").value("Contato"))
                .andExpect(jsonPath("$.patch.proposedConfig.columns[0].field").value("email"))
                .andExpect(jsonPath("$.patch.proposedConfig.columns[0].header").value("Contato"));
    }

    private AgenticAuthoringManifestController controller() {
        return new AgenticAuthoringManifestController(manifestService);
    }

    private AgenticAuthoringManifestService realManifestService() {
        AgenticAuthoringTargetResolverRegistry targetResolverRegistry = new AgenticAuthoringTargetResolverRegistry();
        return new AgenticAuthoringManifestService(
                registryRepository,
                objectMapper,
                targetResolverRegistry,
                new AgenticAuthoringValidatorRegistry(targetResolverRegistry),
                new AgenticAuthoringEffectCompilerRegistry(objectMapper, targetResolverRegistry),
                new AgenticAuthoringManifestContractValidator());
    }

    private String realServicePayload() {
        return """
                {
                  "componentDefinition": {
                    "jsonSchema": {
                      "authoringManifest": {
                        "schemaVersion": "1.0.0",
                        "componentId": "praxis-table",
                        "manifestVersion": "1.0.0",
                        "editableTargets": [
                          { "kind": "column", "resolver": "column-by-field" }
                        ],
                        "operations": [
                          {
                            "operationId": "column.header.set",
                            "target": {
                              "kind": "column",
                              "resolver": "column-by-field",
                              "ambiguityPolicy": "fail",
                              "required": true
                            },
                            "inputSchema": {
                              "type": "object",
                              "required": ["header"],
                              "properties": {
                                "header": { "type": "string" }
                              },
                              "additionalProperties": false
                            },
                            "effects": [
                              { "kind": "merge-by-key", "path": "columns[]", "key": "field" }
                            ],
                            "affectedPaths": ["columns[].header"],
                            "preconditions": ["config-initialized", "target-exists"],
                            "validators": ["target-column-exists"],
                            "submissionImpact": "visual-only"
                          }
                        ],
                        "validators": [
                          { "validatorId": "target-column-exists" }
                        ]
                      }
                    }
                  }
                }
                """;
    }
}
