package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgenticAuthoringRuntimeComponentGroundingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticAuthoringRuntimeComponentGroundingService service =
            new AgenticAuthoringRuntimeComponentGroundingService(objectMapper);

    @Test
    void groundsRuntimeObservationsIntoSafeCanonicalContext() throws Exception {
        ObjectNode context = service.ground(
                List.of(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-runtime-component-observation.v1",
                          "identity": {
                            "instanceId": "table:missionSummary",
                            "componentId": "praxis-table",
                            "componentType": "table",
                            "widgetKey": "missionSummary",
                            "ownerPackage": "@praxisui/table"
                          },
                          "refs": {
                            "componentMetadataId": "praxis-table",
                            "resourcePath": "/api/missions",
                            "resourceKey": "missions",
                            "runtimeSurfaceInstanceRef": "runtime-surface:missionSummary:praxis-table:missionSummary:/api/missions"
                          },
                          "lifecycle": {
                            "active": true,
                            "visible": true,
                            "capturedAt": "2099-01-01T00:00:00.000Z",
                            "ttlMs": 30000
                          },
                          "snapshot": {
                            "selectionDigest": {
                              "selectedCount": 1,
                              "selectedIds": ["1"],
                              "idField": "missaoId",
                              "sampleRows": [{"participante": "Ana Torres"}]
                            },
                            "schemaFieldRefs": ["titulo", "status", "prioridade", "ameaca"],
                            "stateDigest": {
                              "relationSurfaceRefs": [
                                {
                                  "id": "missionTeam",
                                  "sourceWidget": "missionSummary",
                                  "targetWidget": "missionTeam",
                                  "targetResourcePath": "operations/missao-participantes",
                                  "runtimeSurfaceInstanceRef": "runtime-surface:missionTeam:praxis-table:missionTeam:operations/missao-participantes",
                                  "targetRuntimeSurfaceInstanceRef": "runtime-surface:missionTeam:praxis-table:missionTeam:operations/missao-participantes",
                                  "targetSurface": "missionTeam",
                                  "semanticAliases": ["participantes", "equipe"],
                                  "queryMapping": {
                                    "sourceField": "missaoId",
                                    "targetFilterField": "missaoId",
                                    "targetPath": "filters.missaoId",
                                    "valueSource": "selectionDigest.selectedIds[0]"
                                  },
                                  "queryContextPath": "queryContext"
                                }
                              ],
                              "rawRows": [{"titulo": "Operacao Aurora"}]
                            }
                          },
                          "affordances": {
                            "activeSurfaceRefs": ["missionTeam"],
                            "activeActionRefs": ["table.selection", "dynamicPage.surface.open"]
                          },
                          "claims": [
                            {"kind": "surface", "ref": "missionTeam", "observed": true},
                            {"kind": "selection", "ref": "table-row-selection", "observed": true}
                          ],
                          "diagnostics": {
                            "redactionApplied": true,
                            "snapshotHash": "hash-1"
                          }
                        }
                        """)),
                AgenticAuthoringRuntimeComponentGroundingService.TRUST_BOUNDARY_UNTRUSTED_FRONTEND_OBSERVATION);

        org.assertj.core.api.Assertions.assertThat(context.path("canonicalContext").asText())
                .isEqualTo("GroundedRuntimeComponentContext");
        org.assertj.core.api.Assertions.assertThat(context.path("components")).hasSize(1);
        JsonNode component = context.path("components").get(0);
        org.assertj.core.api.Assertions.assertThat(component.path("identity").path("componentId").asText())
                .isEqualTo("praxis-table");
        org.assertj.core.api.Assertions.assertThat(component.path("snapshot").path("selectionDigest").path("selectedCount").asInt())
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(component.path("snapshot").path("selectionDigest").path("selectedIds").get(0).asText())
                .isEqualTo("1");
        org.assertj.core.api.Assertions.assertThat(context.path("allowedFields").toString())
                .contains("titulo", "status", "prioridade", "ameaca");
        org.assertj.core.api.Assertions.assertThat(context.path("availableSurfaces").toString())
                .contains("missionTeam");
        org.assertj.core.api.Assertions.assertThat(component.path("snapshot").path("relationSurfaceRefs").toString())
                .contains("sourceWidget")
                .contains("targetWidget")
                .contains("targetResourcePath")
                .contains("runtimeSurfaceInstanceRef")
                .contains("targetRuntimeSurfaceInstanceRef")
                .contains("semanticAliases")
                .contains("participantes")
                .contains("equipe")
                .contains("queryMapping")
                .contains("targetFilterField")
                .contains("operations/missao-participantes");
        org.assertj.core.api.Assertions.assertThat(component.path("refs").path("runtimeSurfaceInstanceRef").asText())
                .isEqualTo("runtime-surface:missionSummary:praxis-table:missionSummary:/api/missions");
        org.assertj.core.api.Assertions.assertThat(context.path("allowedOperations").toString())
                .contains("table.selection", "dynamicPage.surface.open");
        org.assertj.core.api.Assertions.assertThat(context.toString())
                .doesNotContain("sampleRows")
                .doesNotContain("rawRows")
                .doesNotContain("Ana Torres")
                .doesNotContain("Operacao Aurora");
    }

    @Test
    void rejectsObservationsOutsideExpectedTrustBoundary() throws Exception {
        ObjectNode context = service.ground(
                List.of(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-runtime-component-observation.v1",
                          "identity": {
                            "instanceId": "page:mission-command-center",
                            "componentId": "praxis-dynamic-page"
                          },
                          "lifecycle": {
                            "active": true,
                            "visible": true,
                            "capturedAt": "2026-06-05T12:00:00.000Z"
                          },
                          "diagnostics": {"redactionApplied": true}
                        }
                        """)),
                "trusted_client_claim");

        org.assertj.core.api.Assertions.assertThat(context.path("components")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(context.path("rejectedClaims").get(0).path("reason").asText())
                .isEqualTo("unsupported_trust_boundary");
        org.assertj.core.api.Assertions.assertThat(context.path("policy").path("mayExecuteActions").asBoolean())
                .isFalse();
    }

    @Test
    void rejectsStaleRuntimeObservationEnvelope() throws Exception {
        ObjectNode context = service.ground(
                List.of(objectMapper.readTree("""
                        {
                          "schemaVersion": "praxis-runtime-component-observation.v1",
                          "identity": {
                            "instanceId": "page:mission-command-center",
                            "componentId": "praxis-dynamic-page"
                          },
                          "lifecycle": {
                            "active": true,
                            "visible": true,
                            "capturedAt": "2020-01-01T00:00:00.000Z",
                            "ttlMs": 1000
                          },
                          "diagnostics": {"redactionApplied": true}
                        }
                        """)),
                AgenticAuthoringRuntimeComponentGroundingService.TRUST_BOUNDARY_UNTRUSTED_FRONTEND_OBSERVATION);

        org.assertj.core.api.Assertions.assertThat(context.path("components")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(context.path("rejectedClaims").get(0).path("reason").asText())
                .isEqualTo("stale_observation");
        org.assertj.core.api.Assertions.assertThat(context.path("policy").path("mayExposeRawRuntimeValues").asBoolean())
                .isFalse();
    }
}
